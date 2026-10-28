package io.github.zapretkvn.android.network.probes

import android.net.Network
import android.os.SystemClock
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.BootstrapHostOverlay
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.ManagedHealthEndpoint
import io.github.zapretkvn.android.config.ManagedHealthProbe
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.config.ProxyBootstrapTarget
import io.github.zapretkvn.android.config.ProxyIpFamily
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.network.BootstrapCache
import io.github.zapretkvn.android.network.BootstrapCacheEntry
import io.github.zapretkvn.android.network.BootstrapResolver
import io.github.zapretkvn.android.network.VpnNetworkLostException
import io.github.zapretkvn.android.network.VpnNetworkProvider
import io.github.zapretkvn.android.network.userMessage
import io.github.zapretkvn.android.network.probes.services.NetworkServiceProbeTransport
import io.github.zapretkvn.networkbootstrap.CodedFailure
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HealthCheckResult(
    val externalIpProbeAllowed: Boolean,
)

/**
 * CodedFailure с DNS-200: проба идёт через VPN-сеть, поэтому коды системного
 * bootstrap-DNS (DNS-1xx) из cause-цепочки не должны попадать в итоговую ошибку.
 */
/**
 * Strict Private DNS не ответил через VPN ни на повторы DNS-пробы, ни в HTTPS-проверке.
 * Его путь к резолверу уже прямой (как без VPN), поэтому туннель тут ни при чём:
 * действие за пользователем, повтор лишь множит заметные запросы — код терминальный.
 */
internal class StrictPrivateDnsVpnException(cause: Throwable?) : IllegalStateException(
    "Частный DNS Android не отвечает через VPN. Выключите «Частный DNS» в настройках " +
        "Android или выберите «Автоматически» и подключитесь снова.",
    cause,
), CodedFailure {
    override val failureCode = "DNS-111"
    override val userMessage = checkNotNull(message)
    override val technicalDetail = listOfNotNull(
        "private_dns=strict",
        generateSequence(cause) { it.cause }.filterIsInstance<CodedFailure>().firstOrNull()?.failureCode,
    ).joinToString(" ")
}

internal class VpnDnsHealthException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause), CodedFailure {
    override val failureCode = "DNS-200"
    override val userMessage = message
    override val technicalDetail = (cause as? CodedFailure)?.technicalDetail
}

/**
 * Через сервер за всё время проверки не пришло ни байта, и DNS молчал: это сервер
 * (или путь до него), а не режим DNS. Код трафика VPN-200 — стартовый перебор
 * считает сервер мёртвым и ищет замену; режимы DNS не перебираются.
 */
internal class VpnServerSilentException(dnsFailure: Throwable) : IOException(), CodedFailure {
    override val failureCode = "VPN-200"
    override val userMessage = "Сервер не отвечает: за время проверки через него не пришло ни одного ответа."
    // DNS-отказ не кладётся причиной: по ней перебирались бы режимы DNS
    // (AutomaticDnsFallbackPolicy) вместо замены сервера.
    override val technicalDetail = "transport_cold dns=" +
        ((dnsFailure as? CodedFailure)?.failureCode ?: dnsFailure.javaClass.simpleName)
    override val message: String get() = userMessage
}

internal class VpnHealthTimeoutException : IllegalStateException(), CodedFailure {
    override val failureCode = "VPN-120"
    override val userMessage = "Проверка VPN не завершилась за 20 секунд."
    override val technicalDetail = "health_deadline_ms=20000"
    override val message: String get() = userMessage
}

enum class VpnHealthStage(
    val diagnosticKey: String,
    val diagnosticLabel: String,
) {
    AwaitVpnNetwork("vpn_network", "Ожидание VPN-сети Android"),
    DnsUdpProbe("dns_udp", "DNS через TUN (UDP)"),
    DnsTcpProbe("dns_tcp", "DNS через TUN (TCP fallback)"),
    DnsAndroidProbe("dns_android", "DNS через Android VPN network"),
    HttpsProbe("https_probe", "HTTPS-проверка через VPN"),
}

enum class VpnHealthStageOutcome {
    Success,
    Recovered,
    Failed,
}

data class PreparedBootstrap(
    val target: ProxyBootstrapTarget?,
    val addresses: List<InetAddress>,
    val resolvedAtEpochMillis: Long,
    val overlay: BootstrapHostOverlay? = null,
    /** Адреса взяты из свежего LKG-кэша без ожидания резолва; кэш обновляет фоновая задача. */
    val fromCache: Boolean = false,
    /**
     * Пустое TCP-соединение к серверу не установилось за [ProxyBootstrapper] тайм-аут.
     * Это не приговор: подключение всё равно идёт, решают рукопожатие ядра и проверка
     * связи после запуска (а при мёртвом сервере — резерв группы).
     */
    val tcpPreflightFailed: Boolean = false,
)

class ProxyBootstrapper(
    private val resolver: BootstrapResolver,
    private val cache: BootstrapCache,
    /**
     * Область фонового обновления кэша (stale-while-revalidate). Задача ограничена
     * тайм-аутами резолва и сокетов, поэтому после остановки VPN не живёт дольше
     * нескольких секунд; новая подготовка того же профиля отменяет предыдущую.
     */
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val refreshJobs = ConcurrentHashMap<String, Job>()

    suspend fun prepare(
        profileId: String,
        rawJson: String,
        underlying: Network,
        noCacheLookup: Boolean = false,
    ): PreparedBootstrap = prepare(profileId, ParsedConfig.parse(rawJson), underlying, noCacheLookup)

    suspend fun prepare(
        profileId: String,
        config: ParsedConfig,
        underlying: Network,
        noCacheLookup: Boolean = false,
    ): PreparedBootstrap {
        val target = BootstrapConfig.target(config) ?: return PreparedBootstrap(null, emptyList(), 0)
        val now = System.currentTimeMillis()
        if (!target.requiresDns) {
            val literal = numericAddresses(listOf(target.hostname))
            if (literal.isEmpty()) error("Некорректный IP-адрес VPN-сервера.")
            val preflightFailed = target.tcpPreflightSupported && firstReachable(underlying, target, literal) == null
            return PreparedBootstrap(target, literal, now, tcpPreflightFailed = preflightFailed)
        }
        val lkg = cache.find(profileId, target.hostname, now)
        // Stale-while-revalidate: свежий (до 24 ч) адрес из кэша берём сразу, если сервер
        // отвечает на TCP-preflight и подмена исключена TLS-аутентификацией
        // (staleAddressAllowed). Резолв (DoH + система, до 3 с) уходит в фон и обновляет
        // кэш. Для UDP-транспортов (Hysteria2, TUIC) preflight невозможен, поэтому без
        // ожидания берём только совсем недавний ответ (UDP_CACHE_FAST_PATH_MILLIS):
        // повторное подключение в течение часа не платит за резолв.
        // noCacheLookup — явная просьба о свежем ответе: кэш тогда не используется.
        val fastPathEligible = lkg != null && if (target.tcpPreflightSupported) {
            lkg.isFreshAt(now)
        } else {
            lkg.ageAt(now) <= UDP_CACHE_FAST_PATH_MILLIS
        }
        if (!noCacheLookup && target.staleAddressAllowed && fastPathEligible) {
            val cached = numericAddresses(lkg!!.addresses)
            val reachable = cached.isNotEmpty() &&
                (!target.tcpPreflightSupported || firstReachable(underlying, target, cached) != null)
            if (reachable) {
                scheduleRefresh(profileId, target, underlying, cached)
                return PreparedBootstrap(
                    target,
                    cached,
                    lkg.resolvedAtEpochMillis,
                    lkg.overlay(target),
                    fromCache = true,
                )
            }
        }
        val resolved = try {
            Result.success(resolver.resolve(underlying, target.hostname, noCacheLookup))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (resolved.isSuccess) {
            val addresses = resolved.getOrThrow()
            if (!target.tcpPreflightSupported || firstReachable(underlying, target, addresses) != null) {
                return PreparedBootstrap(target, addresses, now, target.overlay(addresses))
            }
            if (target.staleAddressAllowed && lkg?.isFreshAt(now) == true) {
                val cached = numericAddresses(lkg.addresses)
                if (firstReachable(underlying, target, cached) != null) {
                    return PreparedBootstrap(target, cached, lkg.resolvedAtEpochMillis, lkg.overlay(target))
                }
            }
            // Пустое TCP-соединение не ответило за 1,5 с — раньше подключение на этом
            // обрывалось («VPN-сервер не отвечает»), хотя сервер работал: медленная сеть,
            // потерянный SYN, ТСПУ, не отвечающий на пустые соединения. Решает настоящее
            // рукопожатие ядра, как в других клиентах.
            return PreparedBootstrap(
                target,
                addresses,
                now,
                target.overlay(addresses),
                tcpPreflightFailed = true,
            )
        }

        if (target.staleAddressAllowed && lkg?.isUsableAt(now) == true) {
            val cached = numericAddresses(lkg.addresses)
            if (!target.tcpPreflightSupported || firstReachable(underlying, target, cached) != null) {
                return PreparedBootstrap(target, cached, lkg.resolvedAtEpochMillis, lkg.overlay(target))
            }
        }
        throw checkNotNull(resolved.exceptionOrNull())
    }

    suspend fun recordSuccess(profileId: String, prepared: PreparedBootstrap) {
        val target = prepared.target ?: return
        if (!target.staleAddressAllowed || prepared.addresses.isEmpty()) return
        // Кэш-адреса не перезаписываем: фоновое обновление могло уже записать новее.
        if (prepared.fromCache) return
        cache.recordSuccess(
            profileId = profileId,
            hostname = target.hostname,
            addresses = prepared.addresses,
            resolvedAtEpochMillis = prepared.resolvedAtEpochMillis,
        )
    }

    private fun scheduleRefresh(
        profileId: String,
        target: ProxyBootstrapTarget,
        underlying: Network,
        used: List<InetAddress>,
    ) {
        refreshJobs.remove(profileId)?.cancel()
        val job = refreshScope.launch {
            try {
                val addresses = resolver.resolve(underlying, target.hostname)
                if (addresses.isEmpty()) return@launch
                val sameSet = addresses.mapNotNull(InetAddress::getHostAddress).toSet() ==
                    used.mapNotNull(InetAddress::getHostAddress).toSet()
                // Новые адреса попадают в кэш, только если сервер на них отвечает;
                // совпавший набор уже проверен preflight и лишней проверки не требует.
                // Для UDP-транспортов TCP-проверка невозможна: свежий ответ DNS пишем сразу
                // (подмену исключает TLS сервера), чтобы повтор после отказа не взял старый IP.
                if (!sameSet && target.tcpPreflightSupported &&
                    firstReachable(underlying, target, addresses) == null
                ) return@launch
                cache.recordSuccess(
                    profileId = profileId,
                    hostname = target.hostname,
                    addresses = addresses,
                    resolvedAtEpochMillis = System.currentTimeMillis(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Фоновое обновление best-effort: подключение уже идёт на кэш-адресах.
            }
        }
        refreshJobs[profileId] = job
        job.invokeOnCompletion { refreshJobs.remove(profileId, job) }
    }

    private suspend fun firstReachable(
        network: Network,
        target: ProxyBootstrapTarget,
        addresses: List<InetAddress>,
    ): InetAddress? = withContext(Dispatchers.IO) {
        addresses.take(MAX_SOCKET_CANDIDATES).firstOrNull { address ->
            runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(address, target.port), SOCKET_TIMEOUT_MILLIS)
                }
            }.isSuccess
        }
    }

    private fun numericAddresses(values: List<String>): List<InetAddress> = values.mapNotNull { value ->
        if (':' !in value && !IPV4.matches(value)) return@mapNotNull null
        runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun BootstrapCacheEntry.overlay(target: ProxyBootstrapTarget) = BootstrapHostOverlay(
        outboundTag = target.outboundTag,
        hostname = target.hostname,
        addresses = addresses,
    )

    private fun ProxyBootstrapTarget.overlay(addresses: List<InetAddress>) = BootstrapHostOverlay(
        outboundTag = outboundTag,
        hostname = hostname,
        addresses = addresses.mapNotNull(InetAddress::getHostAddress).distinct(),
    )

    private companion object {
        /** Без проверки достижимости (UDP) кэш берётся без ожидания резолва только такой давности. */
        const val UDP_CACHE_FAST_PATH_MILLIS = 60L * 60 * 1_000
        const val SOCKET_TIMEOUT_MILLIS = 1_500
        const val MAX_SOCKET_CANDIDATES = 3
        val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    }
}

class VpnHealthPipeline internal constructor(
    private val vpnNetworks: VpnNetworkProvider,
    /** Общая полоса проб: проверка подключения занимает её первой, вытесняя остальные пробы. */
    private val lane: ProbeLane?,
) {
    constructor(vpnNetworks: VpnNetworkProvider) : this(vpnNetworks, null)

    // Резолв внутри туннеля должен падать быстрее bootstrap (8 с): при мёртвом
    // DNS каждый лишний тайм-аут задерживает fallback на следующий DNS-режим.
    // Только системный резолв через VPN-сеть: доверенный DoH по IP ушёл бы в туннель
    // (медленно и запрещено политикой узла), он нужен лишь bootstrap на физической сети.
    private val resolver = BootstrapResolver(
        timeoutMillis = HEALTH_DNS_RESOLVE_TIMEOUT_MILLIS,
        trustedDoh = false,
        hedgeAfterMillis = HEALTH_DNS_HEDGE_MILLIS,
    )

    // Спасательная проба медленного туннеля: полезный трафик может ходить, не
    // укладываясь в короткие таймауты основной гонки. Резолв ограничен 5 с,
    // сокет 8 с, чтобы худший провал уложился в общий 20-секундный deadline.
    private val rescueResolver = BootstrapResolver(
        timeoutMillis = HEALTH_RESCUE_RESOLVE_TIMEOUT_MILLIS,
        trustedDoh = false,
        hedgeAfterMillis = HEALTH_DNS_HEDGE_MILLIS,
    )

    /**
     * Проверка подключения занимает общую полосу проб: идущая проверка сервисов или серверов
     * получает сигнал уступить. Полоса ждётся не дольше [ProbeLane.CONNECT_WAIT_MILLIS], затем
     * проверка идёт без неё: подключение никогда не зависит от чужой пробы. Время ожидания
     * не входит в [HEALTH_TIMEOUT_MILLIS].
     */
    suspend fun verify(
        mode: DnsMode,
        internalDnsServer: String,
        proxyIpFamily: ProxyIpFamily = ProxyIpFamily.Unspecified,
        strictPrivateDns: Boolean = false,
        /**
         * Пришёл ли через сервер хоть один байт. Пока нет, транспорт ещё поднимается
         * (первое рукопожатие QUIC/TLS бывает долгим), и отказ DNS — не вина режима
         * DNS: проба повторяется до общего срока, ядро не разрушается.
         */
        transportWarm: () -> Boolean = { true },
        onNetworkLease: (Long) -> Unit = {},
        onNetworkLost: () -> Unit = {},
        onStageStarted: (VpnHealthStage) -> Unit = {},
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit = { _, _, _ -> },
    ): HealthCheckResult {
        val lease = lane?.acquireOrNull(ProbeLane.Owner.Connect, ProbeLane.CONNECT_WAIT_MILLIS)
        try {
            return verifyOnLane(
                mode, internalDnsServer, proxyIpFamily, strictPrivateDns, transportWarm,
                onNetworkLease, onNetworkLost, onStageStarted, onStageFinished,
            )
        } finally {
            lease?.release()
        }
    }

    private suspend fun verifyOnLane(
        mode: DnsMode,
        internalDnsServer: String,
        proxyIpFamily: ProxyIpFamily,
        strictPrivateDns: Boolean,
        transportWarm: () -> Boolean,
        onNetworkLease: (Long) -> Unit,
        onNetworkLost: () -> Unit,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ): HealthCheckResult {
        onStageStarted(VpnHealthStage.AwaitVpnNetwork)
        if (VpnTestHooks.consumeHealthFailureOverride()) {
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Failed,
                "test_override",
            )
            throw HttpsProbeFailure(
                diagnosticDetail = "test_override",
                message = "Тестовая ошибка health-check DNS/HTTPS.",
                cause = null,
            )
        }
        if (VpnTestHooks.consumeHealthSuccessOverride()) {
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Success,
                "test_override",
            )
            return HealthCheckResult(externalIpProbeAllowed = false)
        }
        val healthDeadline = SystemClock.elapsedRealtime() + HEALTH_TIMEOUT_MILLIS
        return try {
            withTimeoutOrNull(HEALTH_TIMEOUT_MILLIS) {
                val lease = try {
                    vpnNetworks.acquireActive()
                } catch (error: Throwable) {
                    onStageFinished(
                        VpnHealthStage.AwaitVpnNetwork,
                        VpnHealthStageOutcome.Failed,
                        rootCauseName(error),
                    )
                    throw error
                }
                lease.use {
                    lease.runWhileActive { vpnNetwork ->
                        vpnNetworks.requireActive(vpnNetwork)
                        onNetworkLease(vpnNetwork.networkHandle)
                        onStageFinished(
                            VpnHealthStage.AwaitVpnNetwork,
                            VpnHealthStageOutcome.Success,
                            "active=true leased=true",
                        )
                        verifyOnNetwork(
                            vpnNetwork = vpnNetwork,
                            mode = mode,
                            internalDnsServer = internalDnsServer,
                            proxyIpFamily = proxyIpFamily,
                            strictPrivateDns = strictPrivateDns,
                            transportWarm = transportWarm,
                            deadlineElapsedRealtimeMillis = healthDeadline,
                            onStageStarted = onStageStarted,
                            onStageFinished = onStageFinished,
                        )
                    }
                }
            } ?: throw VpnHealthTimeoutException()
        } catch (lost: VpnNetworkLostException) {
            onNetworkLost()
            throw lost
        }
    }

    private suspend fun verifyOnNetwork(
        vpnNetwork: Network,
        mode: DnsMode,
        internalDnsServer: String,
        proxyIpFamily: ProxyIpFamily,
        strictPrivateDns: Boolean,
        transportWarm: () -> Boolean,
        deadlineElapsedRealtimeMillis: Long,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ): HealthCheckResult {
        if (VpnTestHooks.consumeDnsProbeFailure()) {
                val failedStage = if (mode == DnsMode.Automatic || mode == DnsMode.Secure) {
                    VpnHealthStage.DnsUdpProbe
                } else {
                    VpnHealthStage.DnsAndroidProbe
                }
                onStageStarted(failedStage)
                onStageFinished(failedStage, VpnHealthStageOutcome.Failed, "test_override")
                throw VpnDnsHealthException(
                    "DNS через VPN не отвечает: тестовый внутренний DNS недоступен.",
                )
        }
        // DNS-проба и HTTPS-проба идут параллельно: HTTPS сам резолвит хост через
        // ту же VPN-сеть, поэтому его успех доказывает и DNS.
        //
        // Пробы живут в отдельной области, а не в coroutineScope: их сокеты блокирующие
        // и на отмену корутины не реагируют до своего тайм-аута. Структурная область
        // (и cancelAndJoin) ждала бы проигравшую пробу — до 2,5 с DNS или до 20 с HTTPS.
        // Проигравшая отменяется без ожидания и сама закончится по своему тайм-ауту;
        // её поздние отчёты об этапах после вердикта отбрасываются.
        val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val decided = AtomicBoolean(false)
        val runningDnsStage = AtomicReference<VpnHealthStage?>(null)
        val httpsRunning = AtomicBoolean(false)
        val dnsFailed = AtomicBoolean(false)
        val dns = probes.async {
            try {
                dnsProbe(
                    vpnNetwork,
                    mode,
                    internalDnsServer,
                    strictPrivateDns,
                    transportWarm,
                    deadlineElapsedRealtimeMillis,
                    { stage ->
                        if (!decided.get()) {
                            runningDnsStage.set(stage)
                            onStageStarted(stage)
                        }
                    },
                    { stage, outcome, detail ->
                        if (runningDnsStage.compareAndSet(stage, null) && !decided.get()) {
                            onStageFinished(stage, outcome, detail)
                        }
                    },
                )
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                dnsFailed.set(true)
                error
            }
        }
        val https = probes.async {
            try {
                httpsStage(
                    vpnNetwork = vpnNetwork,
                    proxyIpFamily = proxyIpFamily,
                    deadlineElapsedRealtimeMillis = deadlineElapsedRealtimeMillis,
                    // Если DNS уже провален, долгую спасательную пробу не ждём:
                    // быстрый отказ нужен для перехода на следующий DNS-режим.
                    // При strict Private DNS следующего режима нет, а HTTPS — сквозная
                    // проверка того же системного DNS: она и решает.
                    rescueAllowed = { strictPrivateDns || !dnsFailed.get() },
                    onStageStarted = { stage ->
                        if (!decided.get()) {
                            httpsRunning.set(true)
                            onStageStarted(stage)
                        }
                    },
                    onStageFinished = { stage, outcome, detail ->
                        if (httpsRunning.getAndSet(false) && !decided.get()) {
                            onStageFinished(stage, outcome, detail)
                        }
                    },
                )
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                error
            }
        }
        // Закрывает идущие этапы проигравшей пробы и запрещает их поздние отчёты.
        fun settle(dnsDetail: String, httpsDetail: String, dnsOutcome: VpnHealthStageOutcome) {
            if (!decided.compareAndSet(false, true)) return
            runningDnsStage.getAndSet(null)?.let { stage -> onStageFinished(stage, dnsOutcome, dnsDetail) }
            if (httpsRunning.getAndSet(false)) {
                onStageFinished(VpnHealthStage.HttpsProbe, VpnHealthStageOutcome.Failed, httpsDetail)
            }
        }
        try {
            // Решает первый завершившийся результат. Успех HTTPS — сразу «здоров».
            // Провал DNS-пробы (уже с повтором запроса) — сильный признак мёртвого DNS:
            // HTTPS получает лишь короткую фору, на живом туннеле он к этому моменту
            // обычно уже готов.
            val httpsFirst = select {
                https.onAwait { error -> true to error }
                dns.onAwait { error -> false to error }
            }
            val dnsError: Throwable?
            val httpsError: Throwable?
            if (httpsFirst.first) {
                httpsError = httpsFirst.second
                if (httpsError == null) {
                    settle("superseded=https_ok", "superseded=https_ok", VpnHealthStageOutcome.Success)
                    return HealthCheckResult(externalIpProbeAllowed = true)
                }
                dnsError = dns.await()
            } else {
                dnsError = httpsFirst.second
                httpsError = if (dnsError == null || strictPrivateDns) {
                    https.await()
                } else if (!transportWarm()) {
                    // Сервер молчал весь срок: ждать HTTPS бессмысленно, он идёт тем же путём.
                    settle("cancelled=server_silent", "cancelled=server_silent", VpnHealthStageOutcome.Failed)
                    throw VpnServerSilentException(dnsError)
                } else {
                    // Обёртка отличает «HTTPS успешен» (ошибки нет) от «фора истекла» (null).
                    val graced = withTimeoutOrNull(HTTPS_GRACE_AFTER_DNS_FAILURE_MILLIS) { listOf(https.await()) }
                    if (graced != null) {
                        graced.single()
                    } else {
                        settle("cancelled=dns_failed", "cancelled=dns_failed", VpnHealthStageOutcome.Failed)
                        dnsError
                    }
                }
                if (httpsError == null) {
                    settle("superseded=https_ok", "superseded=https_ok", VpnHealthStageOutcome.Success)
                    return HealthCheckResult(externalIpProbeAllowed = true)
                }
            }
            settle("cancelled", "cancelled", VpnHealthStageOutcome.Failed)
            if (strictPrivateDns && dnsError != null) throw StrictPrivateDnsVpnException(dnsError)
            if (dnsError != null && !transportWarm()) throw VpnServerSilentException(dnsError)
            // HTTPS не прошёл: причину определяет DNS-проба (классификация DNS-200
            // нужна AutomaticDnsFallbackPolicy), иначе остаётся ошибка HTTPS.
            throw dnsError ?: checkNotNull(httpsError)
        } finally {
            // Любой выход, включая отмену извне (общий лимит, смена сети): пробы
            // отменяются без ожидания.
            decided.set(true)
            probes.cancel()
        }
    }

    private suspend fun dnsProbe(
        vpnNetwork: Network,
        mode: DnsMode,
        internalDnsServer: String,
        strictPrivateDns: Boolean,
        transportWarm: () -> Boolean,
        deadlineElapsedRealtimeMillis: Long,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ) {
        // Холодный транспорт: повтор до общего срока (с запасом на вердикт), а не отказ.
        val coldBudget = (deadlineElapsedRealtimeMillis - SystemClock.elapsedRealtime() - COLD_RETRY_MARGIN_MILLIS)
            .coerceAtLeast(0L)
        val retryWhileCold: (Throwable) -> Boolean = { !transportWarm() }
        if (mode == DnsMode.Automatic || mode == DnsMode.Secure) {
            StrictPrivateDnsProbe.resolve(budgetMillis = coldBudget, retryWhen = retryWhileCold) {
                rawDnsProbe(
                    internalDnsServer,
                    ManagedHealthProbe.endpoints.first().host,
                    onStageStarted,
                    onStageFinished,
                )
            }
            return
        }
        // Запрос идёт через resolver VPN-сети, то есть входит в TUN. В «DNS Android» с
        // проксируемыми именами через узел health-хост по DNS-правилам runtime-конфига
        // разрешает DNS узла внутри туннеля (путь пользователя к проксируемым сайтам);
        // при strict Private DNS и в резерве Auto — системный DNS Android.
        onStageStarted(VpnHealthStage.DnsAndroidProbe)
        try {
            val host = ManagedHealthProbe.endpoints.first().host
            var attempts = 1
            val addresses = if (strictPrivateDns) {
                StrictPrivateDnsProbe.resolve(
                    budgetMillis = STRICT_PRIVATE_DNS_PROBE_BUDGET_MILLIS,
                    onAttempt = { attempts = it },
                ) { resolver.resolve(vpnNetwork, host) }
            } else {
                StrictPrivateDnsProbe.resolve(
                    budgetMillis = coldBudget,
                    onAttempt = { attempts = it },
                    retryWhen = retryWhileCold,
                ) { resolver.resolve(vpnNetwork, host) }
            }
            onStageFinished(
                VpnHealthStage.DnsAndroidProbe,
                VpnHealthStageOutcome.Success,
                "answers=${addresses.size}" + if (attempts > 1) " attempts=$attempts" else "",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onStageFinished(
                VpnHealthStage.DnsAndroidProbe,
                VpnHealthStageOutcome.Failed,
                rootCauseName(error),
            )
            throw dnsFailure(error)
        }
    }

    private suspend fun httpsStage(
        vpnNetwork: Network,
        proxyIpFamily: ProxyIpFamily,
        deadlineElapsedRealtimeMillis: Long,
        rescueAllowed: () -> Boolean,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ) {
        onStageStarted(VpnHealthStage.HttpsProbe)
        if (VpnTestHooks.consumeHttpsProbeFailure()) {
            onStageFinished(
                VpnHealthStage.HttpsProbe,
                VpnHealthStageOutcome.Failed,
                "test_override",
            )
            error("HTTPS-проверка через VPN не прошла: тестовый endpoint недоступен.")
        }
        try {
            val result = httpsProbe(vpnNetwork, proxyIpFamily, deadlineElapsedRealtimeMillis, rescueAllowed)
            onStageFinished(
                VpnHealthStage.HttpsProbe,
                if (result.rescued) VpnHealthStageOutcome.Recovered else VpnHealthStageOutcome.Success,
                "endpoint=${result.endpoint.code} status=${result.status} " +
                    "family=${result.addressFamily.diagnosticName}" +
                    if (result.rescued) " recovered=slow_tunnel" else "",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onStageFinished(
                VpnHealthStage.HttpsProbe,
                VpnHealthStageOutcome.Failed,
                (error as? HttpsProbeFailure)?.diagnosticDetail ?: rootCauseName(error),
            )
            throw error
        }
    }

    private suspend fun rawDnsProbe(
        dnsServer: String,
        hostname: String,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ) {
            val query = dnsQuery(hostname)
            onStageStarted(VpnHealthStage.DnsUdpProbe)
            val udp = try {
                withProbeSockets { register -> udpDns(dnsServer, query, register) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsUdpProbe,
                    VpnHealthStageOutcome.Recovered,
                    rootCauseName(error),
                )
                null
            }
            if (udp != null) {
                try {
                    val validation = DnsHealthResponseValidator.validate(query, udp, allowFallback = true)
                    if (!validation.needsTcpFallback) {
                        onStageFinished(
                            VpnHealthStage.DnsUdpProbe,
                            VpnHealthStageOutcome.Success,
                            "response_bytes=${udp.size} rcode=${validation.rcode} answers=${validation.answerCount}",
                        )
                        return
                    }
                    onStageFinished(
                        VpnHealthStage.DnsUdpProbe,
                        VpnHealthStageOutcome.Recovered,
                        "tcp_fallback=${validation.fallbackReason}",
                    )
                } catch (error: Throwable) {
                    onStageFinished(
                        VpnHealthStage.DnsUdpProbe,
                        VpnHealthStageOutcome.Failed,
                        rootCauseName(error),
                    )
                    throw dnsFailure(error)
                }
            }

            onStageStarted(VpnHealthStage.DnsTcpProbe)
            val tcp = try {
                withProbeSockets { register -> tcpDns(dnsServer, query, register) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw dnsFailure(error)
            }
            try {
                val validation = DnsHealthResponseValidator.validate(query, tcp, allowFallback = false)
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Success,
                    "response_bytes=${tcp.size} rcode=${validation.rcode} answers=${validation.answerCount}",
                )
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw dnsFailure(error)
            }
    }

    private fun dnsFailure(error: Throwable): VpnDnsHealthException {
        if (error is VpnDnsHealthException) return error
        val detail = (error as? CodedFailure)?.failureCode
            ?: error.message?.trim()?.trimEnd('.')
                ?.takeIf(String::isNotBlank)
            ?: error.javaClass.simpleName
        return VpnDnsHealthException("DNS через VPN не отвечает: $detail.", error)
    }

    private fun udpDns(
        dnsServer: String,
        query: ByteArray,
        register: (AutoCloseable) -> Unit,
    ): ByteArray {
        DatagramSocket().use { socket ->
            register(socket)
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            val endpoint = InetSocketAddress(InetAddress.getByName(dnsServer), 53)
            socket.send(DatagramPacket(query, query.size, endpoint))
            val buffer = ByteArray(MAX_DNS_PACKET)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return response.data.copyOf(response.length)
        }
    }

    private fun tcpDns(
        dnsServer: String,
        query: ByteArray,
        register: (AutoCloseable) -> Unit,
    ): ByteArray {
        Socket().use { socket ->
            register(socket)
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(InetAddress.getByName(dnsServer), 53), DNS_TIMEOUT_MILLIS)
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val size = input.readUnsignedShort()
            require(size in 12..MAX_DNS_PACKET) { "Некорректный размер DNS-ответа." }
            return ByteArray(size).also(input::readFully)
        }
    }

    private suspend fun httpsProbe(
        vpnNetwork: Network,
        proxyIpFamily: ProxyIpFamily,
        deadlineElapsedRealtimeMillis: Long,
        rescueAllowed: () -> Boolean = { true },
    ): HttpsProbeResult {
        // Время до отказа отделяет «ждали весь тайм-аут» от «сразу отказ».
        val failedAfterMillis = ConcurrentHashMap<String, Long>()
        val outcome = HealthProbeRace.firstSuccess(
            candidates = ManagedHealthProbe.endpoints,
            staggerMillis = HTTPS_PROBE_STAGGER_MILLIS,
            isFatal = { false },
        ) { endpoint ->
            val startedAt = SystemClock.elapsedRealtime()
            try {
                httpsProbeOne(vpnNetwork, endpoint, proxyIpFamily)
            } catch (error: Throwable) {
                failedAfterMillis[endpoint.code] = SystemClock.elapsedRealtime() - startedAt
                throw error
            }
        }
        return when (outcome) {
            is HealthProbeRace.Outcome.Success -> HttpsProbeResult(
                endpoint = outcome.candidate,
                status = outcome.value,
                addressFamily = proxyIpFamily,
            )
            is HealthProbeRace.Outcome.AllFailed -> {
                if (outcome.failures.all { it.second is VpnHealthAddressFamilyException }) {
                    throw outcome.failures.first().second
                }
                rescueSlowTunnel(
                    vpnNetwork = vpnNetwork,
                    proxyIpFamily = proxyIpFamily,
                    failures = outcome.failures.map { (endpoint, error) ->
                        ProbeFailure(endpoint, error, failedAfterMillis[endpoint.code])
                    },
                    deadlineElapsedRealtimeMillis = deadlineElapsedRealtimeMillis,
                    rescueAllowed = rescueAllowed,
                )
            }
        }
    }

    /**
     * Живой, но медленный туннель проваливает основную гонку по коротким
     * таймаутам, хотя полезный трафик через него ходит. Одна последовательная
     * повторная проба с удвоенными таймаутами отделяет «медленно» от «мертво».
     */
    private suspend fun rescueSlowTunnel(
        vpnNetwork: Network,
        proxyIpFamily: ProxyIpFamily,
        failures: List<ProbeFailure>,
        deadlineElapsedRealtimeMillis: Long,
        rescueAllowed: () -> Boolean = { true },
    ): HttpsProbeResult {
        val budget = HttpsRescueBudget.of(
            deadlineElapsedRealtimeMillis - SystemClock.elapsedRealtime(),
        )?.takeIf { rescueAllowed() } ?: throw httpsProbeFailure(failures, null)
        val rescueEndpoint = ManagedHealthProbe.endpoints.first()
        // Собственный лимит пробы короче общего deadline: иначе его отмена
        // закрывает сокет пробы, и в отчёт уходит ложное «Socket closed».
        val rescueStartedAt = SystemClock.elapsedRealtime()
        val attempt = withTimeoutOrNull(budget.wallMillis) {
            try {
                Result.success(
                    httpsProbeOne(
                        vpnNetwork = vpnNetwork,
                        endpoint = rescueEndpoint,
                        proxyIpFamily = proxyIpFamily,
                        hostResolver = rescueResolver,
                        timeoutMillis = budget.socketTimeoutMillis,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: VpnHealthAddressFamilyException) {
                throw error
            } catch (error: Throwable) {
                // Сокет закрыт нашей же отменой: это тайм-аут, а не ответ сети.
                ensureActive()
                Result.failure(error)
            }
        }
        val rescueError = when {
            attempt == null -> SocketTimeoutException(
                "no response within ${budget.wallMillis} ms",
            )
            attempt.isSuccess -> return HttpsProbeResult(
                endpoint = rescueEndpoint,
                status = attempt.getOrThrow(),
                addressFamily = proxyIpFamily,
                rescued = true,
            )
            else -> checkNotNull(attempt.exceptionOrNull())
        }
        throw httpsProbeFailure(
            failures,
            ProbeFailure(
                rescueEndpoint,
                rescueError,
                SystemClock.elapsedRealtime() - rescueStartedAt,
            ),
        )
    }

    /**
     * technicalDetail начинается с `verdict=<вид>`: по нему главный экран
     * выбирает объяснение, и усечение хвоста диагностикой его не теряет.
     * Дальше компактно `<endpoint>:<вид>@<мс>` для каждой пробы; полные
     * тексты исключений остаются в сообщении ошибки.
     */
    private fun httpsProbeFailure(
        failures: List<ProbeFailure>,
        rescue: ProbeFailure?,
    ): HttpsProbeFailure {
        val all = failures + listOfNotNull(rescue)
        val verdict = HttpsProbeVerdict.of(all.map { HttpsProbeFailureKind.of(it.error) })
        val compact = all.joinToString(" ") { failure ->
            val name = if (failure === rescue) "rescue-${failure.endpoint.code}" else failure.endpoint.code
            val kind = HttpsProbeFailureKind.of(failure.error).token
            name + ":" + kind + (failure.elapsedMillis?.let { "@$it" } ?: "")
        }
        val messages = all.joinToString("; ") { failure ->
            val name = if (failure === rescue) "rescue-${failure.endpoint.code}" else failure.endpoint.code
            "$name:${RuntimeErrors.describe(failure.error)}"
        }
        return HttpsProbeFailure(
            diagnosticDetail = "${HttpsProbeVerdict.DETAIL_PREFIX}${verdict.token} $compact",
            message = "HTTPS через VPN: $messages",
            cause = rescue?.error ?: failures.lastOrNull()?.error,
        )
    }

    private class ProbeFailure(
        val endpoint: ManagedHealthEndpoint,
        val error: Throwable,
        val elapsedMillis: Long?,
    )

    private suspend fun httpsProbeOne(
        vpnNetwork: Network,
        endpoint: ManagedHealthEndpoint,
        proxyIpFamily: ProxyIpFamily,
        hostResolver: BootstrapResolver = resolver,
        timeoutMillis: Int = HTTPS_ENDPOINT_TIMEOUT_MILLIS,
    ): Int {
        if (proxyIpFamily == ProxyIpFamily.Ipv4Only || proxyIpFamily == ProxyIpFamily.Ipv6Only) {
            val addresses = hostResolver.resolve(vpnNetwork, endpoint.host)
            val address = selectHealthAddress(addresses, proxyIpFamily)
                ?: throw VpnHealthAddressFamilyException(
                    requiredFamily = proxyIpFamily,
                    endpointCode = endpoint.code,
                    answerCount = addresses.size,
                )
            return withProbeSockets { register ->
                httpsProbeOnePinned(vpnNetwork, endpoint, address, timeoutMillis, register)
            }
        }
        return withProbeSockets { register ->
            httpsProbeOneDefault(vpnNetwork, endpoint, timeoutMillis, register)
        }
    }

    /**
     * Выполняет блокирующую пробу на Dispatchers.IO так, что отмена корутины
     * немедленно закрывает её сокеты: проигравшие участники параллельной гонки
     * освобождают поток сразу, а не по своему тайм-ауту.
     */
    private suspend fun <T> withProbeSockets(block: (register: (AutoCloseable) -> Unit) -> T): T {
        val resources = ConcurrentLinkedQueue<AutoCloseable>()
        val closed = AtomicBoolean(false)
        fun closeAll() {
            closed.set(true)
            while (true) {
                val resource = resources.poll() ?: return
                runCatching(resource::close)
            }
        }
        return coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    closeAll()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    block { resource ->
                        resources += resource
                        if (closed.get()) runCatching(resource::close)
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun httpsProbeOneDefault(
        vpnNetwork: Network,
        endpoint: ManagedHealthEndpoint,
        timeoutMillis: Int,
        register: (AutoCloseable) -> Unit,
    ): Int {
        val connection = vpnNetwork.openConnection(URL(endpoint.url)) as HttpsURLConnection
        register(AutoCloseable { connection.disconnect() })
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.requestMethod = "GET"
            val status = connection.responseCode
            if (status !in HTTP_REACHABLE_STATUS_RANGE) throw UnexpectedHttpsStatus(status)
            return status
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens the selected numeric address on Android's VPN Network while keeping the
     * original hostname for TLS SNI, certificate verification and HTTP Host.
     */
    private fun httpsProbeOnePinned(
        vpnNetwork: Network,
        endpoint: ManagedHealthEndpoint,
        address: InetAddress,
        timeoutMillis: Int,
        register: (AutoCloseable) -> Unit,
    ): Int {
        val url = URL(endpoint.url)
        val port = url.port.takeIf { it >= 0 } ?: 443
        val plain = vpnNetwork.socketFactory.createSocket()
        register(plain)
        try {
            plain.soTimeout = timeoutMillis
            plain.connect(InetSocketAddress(address, port), timeoutMillis)
            val tls = HttpsURLConnection.getDefaultSSLSocketFactory()
                .createSocket(plain, endpoint.host, port, true) as SSLSocket
            register(tls)
            tls.use {
                it.soTimeout = timeoutMillis
                it.sslParameters = it.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(endpoint.host))
                }
                it.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, it.session)) {
                    throw SSLPeerUnverifiedException(
                        "Сертификат HTTPS health endpoint не соответствует имени.",
                    )
                }
                val path = buildString {
                    append(url.path.ifBlank { "/" })
                    url.query?.let { query -> append('?').append(query) }
                }
                val output = BufferedOutputStream(it.outputStream)
                output.write(
                    (
                        "GET $path HTTP/1.1\r\n" +
                            "Host: ${endpoint.host}\r\n" +
                            // Как у браузера: имя приложения в заголовке выделяло бы пробу.
                            "User-Agent: ${NetworkServiceProbeTransport.USER_AGENT}\r\n" +
                            "Accept: */*\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val statusLine = BufferedInputStream(it.inputStream).use(::readHttpStatusLine)
                val status = statusLine
                    .split(' ', limit = 3)
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: throw IOException("Некорректный HTTP status health endpoint.")
                if (status !in HTTP_REACHABLE_STATUS_RANGE) throw UnexpectedHttpsStatus(status)
                return status
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun readHttpStatusLine(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_HTTP_STATUS_LINE_BYTES) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.isEmpty() || bytes.size >= MAX_HTTP_STATUS_LINE_BYTES) {
            throw IOException("Некорректная строка HTTP status health endpoint.")
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    /** Стабильный код вместо obfuscated-имени класса в release-сборке. */
    private fun rootCauseName(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        return (
            chain.filterIsInstance<CodedFailure>().firstOrNull()?.failureCode
                ?: chain.last().javaClass.simpleName
            ).take(80)
    }

    private data class HttpsProbeResult(
        val endpoint: ManagedHealthEndpoint,
        val status: Int,
        val addressFamily: ProxyIpFamily,
        val rescued: Boolean = false,
    )

    /**
     * CodedFailure с VPN-200: без этого safeError достаёт из cause-цепочки
     * BootstrapFailureException резолва внутри туннеля и сообщает DNS-101
     * «Системный DNS», хотя системный DNS не участвовал.
     */
    private class HttpsProbeFailure(
        val diagnosticDetail: String,
        message: String,
        cause: Throwable?,
    ) : IOException(message, cause), CodedFailure {
        override val failureCode = "VPN-200"
        override val userMessage = message
        override val technicalDetail = diagnosticDetail
    }

    private class UnexpectedHttpsStatus(val status: Int) : IOException("HTTP $status")

    private class VpnHealthAddressFamilyException(
        private val requiredFamily: ProxyIpFamily,
        endpointCode: String,
        answerCount: Int,
    ) : IOException(), CodedFailure {
        override val failureCode = "VPN-201"
        override val userMessage = when (requiredFamily) {
            ProxyIpFamily.Ipv4Only ->
                "HTTPS-проверка: WireGuard-профиль поддерживает только IPv4, " +
                    "но проверочный узел не вернул IPv4-адрес."
            ProxyIpFamily.Ipv6Only ->
                "HTTPS-проверка: WireGuard-профиль поддерживает только IPv6, " +
                    "но проверочный узел не вернул IPv6-адрес."
            else -> "HTTPS-проверка: IP-семейство WireGuard несовместимо с проверочным узлом."
        }
        override val technicalDetail =
            "health_family=${requiredFamily.diagnosticName} endpoint=$endpointCode answers=$answerCount"

        override val message: String
            get() = userMessage
    }

    private fun dnsQuery(hostname: String): ByteArray {
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val labels = hostname.trimEnd('.').split('.')
        val size = 12 + labels.sumOf { 1 + it.encodeToByteArray().size } + 1 + 4
        val output = ByteArray(size)
        output[0] = (id ushr 8).toByte()
        output[1] = id.toByte()
        output[2] = 0x01
        output[5] = 0x01
        var offset = 12
        labels.forEach { label ->
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63)
            output[offset++] = bytes.size.toByte()
            bytes.copyInto(output, offset)
            offset += bytes.size
        }
        output[offset++] = 0
        output[offset++] = 0
        output[offset++] = 1
        output[offset++] = 0
        output[offset] = 1
        return output
    }

    internal companion object {
        /** Потолок одной проверки; им же запуск меряет бюджет на резервный сервер. */
        const val HEALTH_TIMEOUT_MILLIS = 20_000L
        const val HEALTH_DNS_RESOLVE_TIMEOUT_MILLIS = 3_000L

        /** Один повтор системного запроса, если ответа нет за это время (потеря UDP-пакета). */
        const val HEALTH_DNS_HEDGE_MILLIS = 800L

        /** Фора HTTPS-пробе после окончательного провала DNS-пробы. */
        const val HTTPS_GRACE_AFTER_DNS_FAILURE_MILLIS = 1_500L

        /**
         * Запас до общего срока: новая попытка не начинается позже, чем за длительность
         * одной полной DNS-пробы (UDP + TCP по 2,5 с), — вердикт выносит проверка, а не
         * общий тайм-аут VPN-120.
         */
        const val COLD_RETRY_MARGIN_MILLIS = 5_500L

        /**
         * Strict Private DNS: Android проверяет резолвер заново на каждой новой
         * VPN-сети, и первый ответ может опоздать. Повторы DNS-пробы — внутри этого
         * бюджета (меньше [HEALTH_TIMEOUT_MILLIS]).
         */
        const val STRICT_PRIVATE_DNS_PROBE_BUDGET_MILLIS = 12_000L
        const val DNS_TIMEOUT_MILLIS = 2_500
        const val HTTPS_ENDPOINT_TIMEOUT_MILLIS = 4_000
        const val HTTPS_PROBE_STAGGER_MILLIS = 1_000L
        const val HEALTH_RESCUE_RESOLVE_TIMEOUT_MILLIS = 5_000L
        const val MAX_HTTP_STATUS_LINE_BYTES = 512
        const val MAX_DNS_PACKET = 65_535
        val HTTP_REACHABLE_STATUS_RANGE = 200..599
    }
}

private val ProxyIpFamily.diagnosticName: String
    get() = when (this) {
        ProxyIpFamily.Ipv4Only -> "ipv4"
        ProxyIpFamily.Ipv6Only -> "ipv6"
        ProxyIpFamily.DualStack -> "dual"
        ProxyIpFamily.Unspecified -> "resolver"
    }

internal data class DnsResponseValidation(
    val rcode: Int,
    val answerCount: Int,
    val needsTcpFallback: Boolean,
    val fallbackReason: String?,
)

internal object DnsHealthResponseValidator {
    fun validate(
        query: ByteArray,
        response: ByteArray,
        allowFallback: Boolean,
    ): DnsResponseValidation {
        if (query.size < DNS_HEADER_BYTES || response.size < DNS_HEADER_BYTES ||
            response[0] != query[0] || response[1] != query[1]
        ) {
            error("DNS через VPN вернул некорректный ответ.")
        }
        val flags = unsignedShort(response, 2)
        val questionCount = unsignedShort(response, 4)
        val answerCount = unsignedShort(response, 6)
        if (flags and QR_RESPONSE == 0 || questionCount != 1 || response.size < query.size ||
            !response.copyOfRange(DNS_HEADER_BYTES, query.size)
                .contentEquals(query.copyOfRange(DNS_HEADER_BYTES, query.size))
        ) {
            error("DNS через VPN вернул ответ на другой запрос.")
        }
        val rcode = flags and RCODE_MASK
        if (rcode != 0) error("DNS через VPN вернул ошибку $rcode.")
        val truncated = flags and TRUNCATED_RESPONSE != 0
        if (!allowFallback && (truncated || answerCount == 0)) {
            error("DNS через VPN вернул пустой или обрезанный TCP-ответ.")
        }
        return DnsResponseValidation(
            rcode = rcode,
            answerCount = answerCount,
            needsTcpFallback = truncated || answerCount == 0,
            fallbackReason = when {
                truncated -> "truncated"
                answerCount == 0 -> "empty_answer"
                else -> null
            },
        )
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private const val DNS_HEADER_BYTES = 12
    private const val QR_RESPONSE = 0x8000
    private const val TRUNCATED_RESPONSE = 0x0200
    private const val RCODE_MASK = 0x000f
}

/**
 * Бюджет спасательной HTTPS-пробы внутри общего health deadline. Резолв,
 * connect и чтение по отдельности ограничены [socketTimeoutMillis], но вместе
 * могут превысить остаток deadline; [wallMillis] ограничивает всю пробу и
 * оставляет запас, чтобы её исход успел попасть в отчёт.
 */
internal data class HttpsRescueBudget(
    val wallMillis: Long,
    val socketTimeoutMillis: Int,
) {
    companion object {
        const val SOCKET_TIMEOUT_MILLIS = 8_000
        const val MIN_REMAINING_MILLIS = 8_500L
        const val DEADLINE_MARGIN_MILLIS = 500L

        /** null — до общего deadline не хватает времени на осмысленную пробу. */
        fun of(remainingMillis: Long): HttpsRescueBudget? {
            if (remainingMillis < MIN_REMAINING_MILLIS) return null
            val wallMillis = remainingMillis - DEADLINE_MARGIN_MILLIS
            return HttpsRescueBudget(
                wallMillis = wallMillis,
                socketTimeoutMillis = minOf(SOCKET_TIMEOUT_MILLIS.toLong(), wallMillis).toInt(),
            )
        }
    }
}

internal fun selectHealthAddress(
    addresses: List<InetAddress>,
    family: ProxyIpFamily,
): InetAddress? = when (family) {
    ProxyIpFamily.Ipv4Only -> addresses.firstOrNull { it is Inet4Address }
    ProxyIpFamily.Ipv6Only -> addresses.firstOrNull { it is Inet6Address }
    ProxyIpFamily.DualStack,
    ProxyIpFamily.Unspecified,
    -> addresses.firstOrNull()
}
