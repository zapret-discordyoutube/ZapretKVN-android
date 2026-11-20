package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.AppIdentity
import io.github.zapretkvn.clientreports.AttemptFacts
import io.github.zapretkvn.clientreports.AttemptOutcome
import io.github.zapretkvn.clientreports.AttemptDetails
import io.github.zapretkvn.clientreports.AttemptRecord
import io.github.zapretkvn.clientreports.BatchDelivery
import io.github.zapretkvn.clientreports.ForeignServer
import io.github.zapretkvn.clientreports.ClientReportContract
import io.github.zapretkvn.clientreports.ClientReportJson
import io.github.zapretkvn.clientreports.IngestHostCache
import io.github.zapretkvn.clientreports.IngestHosts
import io.github.zapretkvn.clientreports.NetworkToken
import io.github.zapretkvn.clientreports.NetworkTokenCache
import io.github.zapretkvn.clientreports.ReportCheck
import io.github.zapretkvn.clientreports.ReportChecks
import io.github.zapretkvn.clientreports.ReportOutcome
import io.github.zapretkvn.clientreports.ReportClock
import io.github.zapretkvn.clientreports.ReportQueue
import io.github.zapretkvn.clientreports.ReportSendPolicy
import io.github.zapretkvn.clientreports.SendAction
import io.github.zapretkvn.clientreports.ServerRef
import io.github.zapretkvn.clientreports.ServerVerdict
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Отчёты о подключениях к серверам сервиса для status.zapret.moe (этап S4 «честного покрытия»,
 * решения владельца 15–19.11.2026): приложение само, без вопроса и без настройки, сообщает, дошла ли
 * попытка до сервера каталога и на каком шаге оборвалась. Без аккаунта, ключа и адреса: сеть ставит
 * приёмник. Отчёт ставится в очередь на устройстве и уходит любым рабочим путём: сначала напрямую
 * (приёмник видит сеть сам), затем через туннель (сеть подтверждает токен, взятый раньше напрямую).
 *
 * Решения — в модуле `client-reports` (контракт, классификация, очередь, токен, имена приёмника
 * [IngestHosts]); здесь только сеть и привязка к Android. Таймеров нет: отправка идёт по событиям —
 * новая попытка и подключение. Каждый запрос — своё новое соединение с телом не больше 8 КБ, имена
 * приёмника и их домены чередуются (решение владельца 19.11.2026).
 *
 * Schema 3 (vpnbot «schema 3 live» / «completion live» 19.11.2026): приложение шлёт факты, vpnbot судит и отвечает
 * вердиктом на каждый отчёт; его текст — на экране ошибки ([failureReport]). Отказ уходит сразу; если путь
 * проверяется ([BlockProbe], редко: [PATH_CHECK_PER_SERVER_MILLIS], [PATH_CHECK_PER_INSTALL_MILLIS]), тот же отчёт
 * приходит целиком после проверки. Кнопка «Отправить отчёт» — [forceReport]. После удачи раз в
 * [FLOW_CHECK_PER_SERVER_MILLIS] на сервер меряется поток через туннель.
 */
internal class ClientReporter(
    private val root: File,
    appVersion: String,
    private val scope: CoroutineScope,
    val serverIds: ServerIdStore,
    /** Соединение мимо VPN (основная сеть) или null, если её нет. */
    private val directConnection: (URL) -> HttpURLConnection?,
    /** Соединение через поднятый туннель или null, если его нет. */
    private val tunnelConnection: (URL) -> HttpURLConnection?,
    /** Ключ текущей основной сети (не адрес и не имя Wi-Fi): токен сети привязан к нему. */
    private val networkKey: () -> String?,
    private val log: (String) -> Unit,
    /** Пробы пути мимо VPN; null — без проверки (тесты). */
    private val probe: BlockProbe? = null,
    /** Сеть поднятого туннеля для замера потока. */
    private val vpnNetwork: () -> Network? = { null },
    /** Туннель поднят, данных нет: тот же сервис через туннель и напрямую; null — без неё (тесты). */
    private val noDataProbe: NoDataProbe? = null,
    /** Подробности, общие для попыток (schema 2): тип сети, настройки, версии ОС и ядер. */
    private val context: suspend () -> AttemptDetails = { AttemptDetails() },
    private val now: () -> Long = System::currentTimeMillis,
    /** false — прогон тестов ([ClientReportSwitch]): ничего не пишется и не отправляется; экран и проверка пути работают. */
    private val enabled: Boolean = ClientReportSwitch.enabled(),
) {
    init {
        if (!enabled) log("client-report: выключены (${ClientReportSwitch.PROPERTY}=off), ничего не пишется и не отправляется")
    }

    private val app = AppIdentity(APP_NAME, safeVersion(appVersion), PLATFORM)
    private val queue = ReportQueue(File(root, "queue.jsonl"))
    // «-2»: vpnbot's v2 (2026-11-19 19:37 UTC) no longer places a v1 value in any network, so one kept by an
    // older version is left behind and a fresh one is asked (the value itself stays opaque).
    private val tokens = NetworkTokenCache(File(root, "network-token-2"))
    private val ingest = IngestHostCache(File(root, "ingest-hosts"))
    private val random = SecureRandom().asKotlinRandom()

    /** Имя → когда оно не ответило; такое имя идёт последним [IngestHosts.FAILED_FOR_MILLIS]. */
    private val failedHosts = HashMap<String, Long>()

    /** The ingest's clock from its replies: `attempted_at` goes in its time (vpnbot 2026-11-20). */
    private val clock = ReportClock()

    /** report_id → the offset it was accepted with: its completion copy keeps the same `attempted_at`. */
    private val sentOffsets = LinkedHashMap<String, Long>()
    private val flushing = Mutex()
    private val preflight = object : LinkedHashMap<String, ReportCheck>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReportCheck>?) = size > 32
    }

    @Volatile
    private var stoppedUntilNextAttempt = false

    /** Имя, адрес сервера и установка → когда проверяли (см. [probeSlot]). */
    private val probedAt = HashMap<String, Long>()
    private val flowCheckedAt = HashMap<String, Long>()

    /** report_id → вердикт vpnbot для экрана (последние несколько). */
    private val verdicts = object : LinkedHashMap<String, ServerVerdict>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerVerdict>?) = size > 64
    }

    /** Попытки, о которых отчёт уже пишет проверка сервера: отказ запуска их не дублирует. */
    private val attemptedGenerations = object : LinkedHashMap<Long, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 16
    }

    /** Имя сервера → адрес, к которому подключался запуск (bootstrap): `server_ip` отчёта. */
    private val addresses = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    private val failureState = MutableStateFlow<FailureReport?>(null)
    private var failure: PendingFailure? = null
    private val failureLock = Mutex()

    /** Последний отказ для экрана ошибки: что нашла проверка пути и что с отчётом. */
    val failureReport: StateFlow<FailureReport?> = failureState.asStateFlow()

    /** Итог предварительного TCP-соединения к [tag] в сессии [generation] (только для его же отчёта). */
    fun notePreflight(generation: Long, tag: String, check: ReportCheck?) {
        if (check == null) return
        synchronized(preflight) { preflight["$generation|$tag"] = check }
    }

    fun preflightFailed(generation: Long, tag: String): Boolean =
        synchronized(preflight) { preflight["$generation|$tag"] }?.let { it.result != ReportChecks.ANSWERED } == true

    private fun preflightOf(generation: Long, tag: String): ReportCheck? = synchronized(preflight) { preflight["$generation|$tag"] }

    /** Адрес сервера [host], выбранный запуском (DoH, система или кэш). */
    fun noteAddress(host: String, address: String?) {
        if (address.isNullOrBlank()) return
        synchronized(addresses) { addresses[host.lowercase()] = address }
    }

    /** Новая попытка подключения: прошлый отказ с экрана снимается. */
    fun beginAttempt(generation: Long) {
        scope.launch {
            failureLock.lock()
            try {
                if (failure?.generation != generation) {
                    failure = null
                    failureState.value = null
                }
            } finally {
                failureLock.unlock()
            }
        }
    }

    /**
     * Конец одной проверки сервера [tag]. Отчёт пишется только о сервере из подписки сервиса и только
     * об отказе, который про сервер; проверка пути и экран — для любого сервера. Вызов дешёвый: пробы,
     * запись и отправка идут в фоне.
     */
    fun onAttempt(
        tag: String,
        outboundType: String?,
        facts: AttemptFacts,
        elapsedMs: Long,
        attemptedAtMillis: Long,
        generation: Long = 0,
        target: ProbeTarget? = null,
        /** Туннель той же попытки ещё обслуживает этот сервер: только тогда пробы «нет данных» что-то значат. */
        stillServing: (() -> Boolean)? = null,
    ) {
        if (!facts.succeeded && !facts.cancelled) synchronized(attemptedGenerations) { attemptedGenerations[generation] = true }
        scope.launch {
            runCatching {
                val ref = serverIds.lookup(tag, outboundType)
                if (facts.cancelled || facts.succeeded) {
                    // Поток меряется только у серверов сервиса: о чужом удачном отчёта нет, а 64 КБ тратятся.
                    val found = LinkedHashMap<String, ReportCheck>()
                    if (facts.succeeded && ref != null) checkFlow(target)?.let { found["flow"] = it }
                    if (facts.succeeded) clearFailure()
                    if (ref != null) {
                        record(
                            ref, null, AttemptOutcome.of(facts), elapsedMs, attemptedAtMillis, details(target, null),
                            carrier = carrierOf(target, outboundType), coreCode = "", checks = found,
                        )
                    }
                    return@runCatching
                }
                onFailure(PendingFailure(generation, tag, ref, facts, elapsedMs, attemptedAtMillis, target, outboundType, stillServing, preflightOf(generation, tag)))
            }.onFailure { log("client-report: запись не удалась: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Отказ запуска до проверки сервера (адрес, ядро, настройки) или без неё: факты — код ошибки.
     * Если проверка этой попытки уже записала отказ, ничего не делается.
     */
    fun onStartFailure(generation: Long, tag: String, outboundType: String?, code: String, networkLost: Boolean, target: ProbeTarget?) {
        if (synchronized(attemptedGenerations) { attemptedGenerations.containsKey(generation) }) return
        val startedAt = now()
        scope.launch {
            runCatching {
                val facts = AttemptFacts(
                    coreCode = code.takeIf(String::isNotBlank),
                    networkLost = networkLost,
                    udpTransport = target?.udp == true,
                )
                onFailure(PendingFailure(generation, tag, serverIds.lookup(tag, outboundType), facts, 0, startedAt, target, outboundType, null, preflightOf(generation, tag)))
            }.onFailure { log("client-report: отказ запуска не записан: ${it.javaClass.simpleName}") }
        }
    }

    private suspend fun onFailure(pending: PendingFailure) {
        failureLock.lock()
        try {
            failure = pending
            publish(pending)
        } finally {
            failureLock.unlock()
        }
        val target = pending.target
        val probe = probe
        val coreCode = AttemptOutcome.coreCode(pending.facts)
        // Туннель поднят, данных нет: путь до сервера доказан, говорить могут только пробы «нет данных».
        val noData = pending.facts.trafficFailed && noDataProbe != null && target != null &&
            pending.stillServing?.invoke() == true && probeSlot(target)
        val path = !noData && !pending.facts.trafficFailed && target != null && probe != null &&
            ReportChecks.needsPathCheck(coreCode) && probeSlot(target)
        // The app's own health check is a fact it already has (vpnbot 2026-11-20): no traffic, no rarity limit; it goes
        // with the first copy when the core gave no code.
        if (coreCode.isEmpty()) pending.facts.health?.let { pending.checks[ReportChecks.HEALTH] = it }
        // Completion (vpnbot «completion live» 19.11.2026): часть ядра уходит сразу — панель видит отказ без ожидания,
        // и попытка встаёт в сеть, из которой сделана; целиком тот же отчёт — после проверки.
        pending.record = recordFailure(pending, complete = if (noData || path) false else null)
        pending.status = statusOf(pending)
        publish(pending)
        if (noData) {
            pending.checking = true
            publish(pending)
            runCatching { noDataProbe?.run() }.getOrNull()?.let { pending.checks.putAll(it) }
        } else if (path && target != null && probe != null) {
            pending.checking = true
            publish(pending)
            val known = synchronized(addresses) { addresses[target.host.lowercase()] }
            val facts = runCatching { probe.run(target, known) }.getOrNull() ?: PathFacts()
            pending.checks.putAll(facts.checks)
            pending.probedAddress = facts.probedAddress
            facts.probedAddress?.let { takeSlot(probedAt, "ip:$it", PATH_CHECK_PER_SERVER_MILLIS) }
        } else if (!pending.facts.udpTransport && pending.preflight != null) {
            // Предпроверка TCP уже была частью запуска (не новая проба): её факт идёт, когда путь не проверялся.
            pending.checks["tcp_preflight"] = pending.preflight
        }
        if (pending.checking) {
            pending.checking = false
            pending.checked = true
            log("client-report: проверка пути → ${summary(pending.checks)}")
            // Everything that can be sent goes by itself (owner 2026-11-19: «send everything we possibly can
            // automatically, even without the button»); a server of the user's own goes in full.
            pending.record?.let { first -> pending.record = recordFailure(pending, complete = true, reportId = first.reportId) }
        }
        pending.status = statusOf(pending)
        publish(pending)
    }

    private suspend fun recordFailure(pending: PendingFailure, complete: Boolean? = null, reportId: String = "", flushNow: Boolean = true): AttemptRecord? {
        val ref = pending.ref
        val foreign = if (ref == null) foreignOf(pending) ?: return null else null
        return record(
            ref, foreign, ReportOutcome.Failed, pending.elapsedMs, pending.attemptedAt, details(pending.target, pending.probedAddress),
            carrier = carrierOf(pending.target, pending.outboundType), coreCode = AttemptOutcome.coreCode(pending.facts),
            checks = pending.checks, complete = complete, reportId = reportId, flushNow = flushNow,
        )
    }

    private fun carrierOf(target: ProbeTarget?, outboundType: String?): String = target?.carrier ?: ReportChecks.carrierOf(outboundType)

    /**
     * Не чаще одной проверки на установку за [PATH_CHECK_PER_INSTALL_MILLIS] и на имя и адрес сервера за
     * [PATH_CHECK_PER_SERVER_MILLIS] (строки на одном адресе, например «через Россию», делят его).
     */
    private fun probeSlot(target: ProbeTarget): Boolean {
        val address = BlockProbe.literalAddress(target.host)?.hostAddress ?: synchronized(addresses) { addresses[target.host.lowercase()] }
        val keys = buildList {
            add("install" to PATH_CHECK_PER_INSTALL_MILLIS)
            add("host:${target.host.lowercase()}" to PATH_CHECK_PER_SERVER_MILLIS)
            address?.let { add("ip:$it" to PATH_CHECK_PER_SERVER_MILLIS) }
        }
        synchronized(probedAt) {
            if (keys.any { (key, interval) -> probedAt[key]?.let { now() - it < interval } == true }) return false
        }
        keys.forEach { (key, interval) -> takeSlot(probedAt, key, interval) }
        return true
    }

    /**
     * Кнопка «Отправить отчёт»: отказ, о котором отчёт ещё не написан, пишется с пометкой `local_*`,
     * если он не про сервер; очередь отправляется сразу. Итог — на [failureReport].
     */
    fun forceReport() {
        scope.launch {
            runCatching {
                val pending = failure ?: return@runCatching
                // Чужой сервер — полностью (адрес, порт, протокол, имя); обычно уже записан сам, кнопка лишь отправляет сразу.
                if (pending.ref == null && foreignOf(pending) == null) return@runCatching
                pending.status = SendStatus.Sending
                publish(pending)
                if (pending.record == null) pending.record = recordFailure(pending, flushNow = false)
                stoppedUntilNextAttempt = false
                flushLocked(wait = true)
                pending.status = statusOf(pending)
                log("client-report: отчёт по кнопке — ${if (pending.status == SendStatus.Sent) "отправлен" else "в очереди"}")
                publish(pending)
            }.onFailure { log("client-report: отчёт по кнопке не записан: ${it.javaClass.simpleName}") }
        }
    }

    private fun foreignOf(pending: PendingFailure): ForeignServer? {
        val target = pending.target ?: return null
        return ForeignServer.of(target.host, target.protocol, pending.tag)
    }

    /** Подробности отчёта: общие из [context] и про этот сервер (адрес, порт, транспорт, защита). */
    private suspend fun details(target: ProbeTarget?, probedAddress: String?): AttemptDetails {
        val base = runCatching { context() }.getOrDefault(AttemptDetails())
        if (target == null) return base.cleaned()
        val literal = BlockProbe.literalAddress(target.host)?.hostAddress
        val noted = synchronized(addresses) { addresses[target.host.lowercase()] }
        return base.copy(
            serverIp = literal ?: noted ?: probedAddress,
            port = target.port,
            transport = target.transport,
            security = target.security,
        ).cleaned()
    }

    private fun statusOf(pending: PendingFailure): SendStatus {
        val record = pending.record ?: return if (pending.ref == null && foreignOf(pending) == null) SendStatus.NotOurs else SendStatus.Available
        return if (queue.pending(now()).contains(record)) SendStatus.Queued else SendStatus.Sent
    }

    private fun publish(pending: PendingFailure) {
        if (failure !== pending) return
        val verdict = pending.record?.let { record -> synchronized(verdicts) { verdicts[record.reportId] } }
        failureState.value = FailureReport(
            generation = pending.generation,
            checking = pending.checking,
            diagnosisText = verdict?.text ?: if (pending.record != null && !pending.checking) WAITING_TEXT else null,
            detail = verdict?.detail,
            status = pending.status,
        )
    }

    /** Удача снимает отказ с экрана: на ошибку больше нечего показывать. */
    private suspend fun clearFailure() {
        failureLock.lock()
        try {
            failure = null
            failureState.value = null
        } finally {
            failureLock.unlock()
        }
    }

    /** `flow` после удачи: не чаще раза в [FLOW_CHECK_PER_SERVER_MILLIS] на сервер, только TCP-строки. */
    private suspend fun checkFlow(target: ProbeTarget?): ReportCheck? {
        val probe = probe
        if (target == null || target.carrier != "tcp" || probe == null || !takeSlot(flowCheckedAt, target.host, FLOW_CHECK_PER_SERVER_MILLIS)) return null
        val flow = runCatching { probe.flow(vpnNetwork()) }.getOrNull()
        log("client-report: замер потока → ${flow?.let { "${it.result}, ${it.bytes} Б, ${it.millis} мс" } ?: "не запускался"}")
        return flow
    }

    private fun takeSlot(map: HashMap<String, Long>, key: String, intervalMillis: Long): Boolean = synchronized(map) {
        val last = map[key]
        if (last != null && now() - last < intervalMillis) return false
        if (map.size > 64) map.entries.removeAll { now() - it.value >= PATH_CHECK_PER_SERVER_MILLIS }
        map[key] = now()
        true
    }

    /** Пробы одной строкой для журнала: имена, итоги и время, без адресов. */
    private fun summary(checks: Map<String, ReportCheck>): String =
        ReportChecks.ordered(checks).entries.joinToString(" ") { (probe, check) ->
            "$probe=${check.result}/${check.millis}мс" + (check.bytes?.let { "/${it}Б" } ?: "")
        }.ifEmpty { "проб нет" }

    private suspend fun record(
        ref: ServerRef?,
        foreign: ForeignServer?,
        outcome: ReportOutcome,
        elapsedMs: Long,
        attemptedAtMillis: Long,
        details: AttemptDetails,
        carrier: String,
        coreCode: String,
        checks: Map<String, ReportCheck>,
        complete: Boolean? = null,
        reportId: String = "",
        flushNow: Boolean = true,
    ): AttemptRecord? {
        if (ref == null && foreign == null || !enabled) return null
        val key = networkKey()
        if (tokens.needsRefresh(key, now())) refreshToken(key)
        val record = AttemptRecord(
            serverId = ref?.serverId.orEmpty(),
            product = ref?.product.orEmpty(),
            outcome = outcome,
            failedStep = "",
            detail = "",
            elapsedMs = elapsedMs.coerceIn(0, ClientReportContract.MAX_ELAPSED_MS),
            attemptedAtMillis = attemptedAtMillis,
            networkToken = tokens.current(key, now()),
            foreign = foreign,
            details = details,
            reportId = reportId.ifEmpty { ClientReportContract.newReportId() },
            via = ref?.via.orEmpty(),
            carrier = carrier.takeIf { it in ReportChecks.CARRIERS } ?: "tcp",
            coreCode = ReportChecks.coreCodeOf(coreCode),
            checks = ReportChecks.ordered(checks),
            complete = complete,
        )
        queue.add(record)
        val part = when (complete) { false -> ", первая копия"; true -> ", дополнение"; null -> "" }
        log(
            "client-report: ${ref?.let { "${it.product}/${it.serverId}" } ?: "чужой ${foreign?.protocol}"} ${outcome.code}" +
                (if (record.coreCode.isNotEmpty()) " ${record.coreCode}" else "") +
                ", проб ${record.checks.size}$part, в очереди ${queue.size()}",
        )
        if (flushNow) {
            stoppedUntilNextAttempt = false
            flushLocked()
        }
        return record
    }

    /** Повод отправить очередь (подключение, запуск приложения). */
    fun flush() {
        if (stoppedUntilNextAttempt || !enabled) return
        scope.launch { runCatching { flushLocked() } }
    }

    private suspend fun flushLocked(wait: Boolean = false) {
        if (!enabled) return
        if (wait) flushing.lock() else if (!flushing.tryLock()) return
        try {
            while (true) {
                val installId = installId()
                val batch = IngestHosts.takeBatch(queue.pending(now()), ClientReportContract.MAX_BATCH, IngestHosts.MAX_BODY_BYTES) {
                    ClientReportJson.batch(installId, app, it, ClientReportContract.SENT_DIRECT).toByteArray(Charsets.UTF_8).size
                }
                if (batch.isEmpty()) return
                // A refused batch is split until the refused reports stand alone: the good ones are never lost with them.
                val stopped = BatchDelivery.deliver(batch, ::sendCorrected, { part, action ->
                    queue.remove(part)
                    if (action == SendAction.Drop) log("client-report: приёмник отклонил ${part.size} отч., убраны")
                }) ?: continue
                stoppedUntilNextAttempt = stopped == SendAction.KeepAndStop
                return
            }
        } finally {
            flushing.unlock()
        }
    }

    /**
     * Пакет напрямую, а без ответа — через туннель; на каждом пути до [IngestHosts.TRIES_PER_PATH]
     * имён приёмника. Если не ответило ни одно имя, один раз спрашивается лента имён и попытка
     * повторяется. [SendAction.TryOtherPath] — не ответил никто.
     */
    /** A refusal right after the ingest's clock was first learnt is sent once more in its time. */
    private fun sendCorrected(batch: List<AttemptRecord>): SendAction {
        val used = clock.applied()
        val action = send(batch)
        if (action != SendAction.Drop || clock.applied() == used) return action
        log("client-report: часы устройства расходятся с приёмником на ${clock.applied() / 1000} с — повтор во времени приёмника")
        return send(batch)
    }

    private fun send(batch: List<AttemptRecord>): SendAction {
        if (ingest.feedDue(now(), nothingAnswered = false)) refreshFeed()
        sendOnce(batch)?.let { return it }
        if (ingest.feedDue(now(), nothingAnswered = true) && refreshFeed()) sendOnce(batch)?.let { return it }
        log("client-report: пакет ${batch.size} не ушёл: приёмник не ответил ни по одному имени")
        return SendAction.TryOtherPath
    }

    private fun sendOnce(batch: List<AttemptRecord>): SendAction? {
        val installId = installId()
        for ((via, open) in listOf(ClientReportContract.SENT_DIRECT to directConnection, ClientReportContract.SENT_TUNNEL to tunnelConnection)) {
            val offset = clock.applied()
            val sent = synchronized(sentOffsets) { HashMap(sentOffsets) }
            val body = ClientReportJson.batch(installId, app, batch, via, offset, sent).toByteArray(Charsets.UTF_8)
            for (host in hostOrder().take(IngestHosts.TRIES_PER_PATH)) {
                val (status, reply) = post(open, host, body) ?: run {
                    noteFailed(host)
                    null
                } ?: continue
                val action = ReportSendPolicy.afterResponse(status)
                if (action == SendAction.TryOtherPath) {
                    noteFailed(host)
                    continue
                }
                log("client-report: пакет ${batch.size}, ${body.size} Б ($via, ${IngestHosts.domainOf(host)}) → HTTP $status")
                if (action == SendAction.Remove) {
                    noteSentOffsets(batch, offset, sent)
                    noteVerdicts(batch, ClientReportJson.parseReply(reply, batch.size))
                }
                return action
            }
        }
        return null
    }

    private fun noteSentOffsets(batch: List<AttemptRecord>, offset: Long, sent: Map<String, Long>) {
        synchronized(sentOffsets) {
            batch.filter { it.reportId.isNotEmpty() }.forEach { sentOffsets[it.reportId] = sent[it.reportId] ?: offset }
            while (sentOffsets.size > 256) sentOffsets.remove(sentOffsets.keys.first())
        }
    }

    /** Вердикты vpnbot по report_id; вердикт отказа на экране — сразу. */
    private fun noteVerdicts(batch: List<AttemptRecord>, found: List<ServerVerdict?>) {
        synchronized(verdicts) {
            batch.zip(found).forEach { (record, verdict) ->
                if (verdict != null && record.reportId.isNotEmpty()) verdicts[record.reportId] = verdict
            }
        }
        val pending = failure ?: return
        val verdict = pending.record?.let { record -> synchronized(verdicts) { verdicts[record.reportId] } } ?: return
        log("client-report: вердикт ${verdict.detail} (${verdict.level}, ${verdict.confidence})")
        pending.status = statusOf(pending)
        publish(pending)
    }

    private fun hostOrder(): List<String> {
        val failed = synchronized(failedHosts) {
            val cutoff = now() - IngestHosts.FAILED_FOR_MILLIS
            failedHosts.entries.removeAll { it.value < cutoff }
            failedHosts.keys.toSet()
        }
        return IngestHosts.order(ingest.hosts(), failed, random)
    }

    private fun noteFailed(host: String) {
        synchronized(failedHosts) { failedHosts[host] = now() }
    }

    /** Одно короткое соединение на запрос: без повторного использования, тело фиксированной длины. Код и ответ (вердикты). */
    private fun post(open: (URL) -> HttpURLConnection?, host: String, body: ByteArray): Pair<Int, String?>? = runCatching {
        val connection = open(URL("https://$host/v1/reports")) ?: return null
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            clock.learn(connection.getHeaderFieldDate("Date", 0), now())
            val reply = if (status == 202 || status == 200) {
                runCatching { connection.inputStream.use { it.readNBytesCompat(MAX_REPLY_BYTES).toString(Charsets.UTF_8) } }.getOrNull()
            } else {
                null
            }
            status to reply
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Имена приёмника из ленты zapret-links (входные страницы script.google.com и gist), напрямую,
     * затем через туннель. true — узнали хотя бы одно имя.
     */
    private fun refreshFeed(): Boolean {
        ingest.noteFeedAttempt(now())
        for (open in listOf(directConnection, tunnelConnection)) {
            for (feed in IngestHosts.FEEDS) {
                val text = get(open, feed, IngestHosts.MAX_FEED_BYTES) ?: continue
                val hosts = IngestHosts.parseFeed(text)
                if (hosts.isEmpty()) continue
                ingest.store(hosts, now())
                log("client-report: имена приёмника из ленты ${URL(feed).host}: ${hosts.size}")
                return true
            }
        }
        log("client-report: лента имён приёмника не ответила")
        return false
    }

    private fun get(open: (URL) -> HttpURLConnection?, url: String, maxBytes: Int): String? = runCatching {
        val connection = open(URL(url)) ?: return null
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode != 200) return null
            connection.inputStream.use { stream ->
                val bytes = stream.readNBytesCompat(maxBytes + 1)
                if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** `GET /v1/network` только напрямую: через туннель приёмник увидел бы сеть VPN-сервера. */
    private fun refreshToken(key: String?) {
        if (key == null) return
        if (ingest.feedDue(now(), nothingAnswered = false)) refreshFeed()
        var token: String? = null
        for (host in hostOrder().take(IngestHosts.TRIES_PER_PATH)) {
            val text = get(directConnection, "https://$host/v1/network?install_id=${installId()}", MAX_ANSWER_BYTES)
            if (text == null) {
                noteFailed(host)
                continue
            }
            token = runCatching {
                ((Json.parseToJsonElement(text) as JsonObject)["network_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }.getOrNull()
            break
        }
        if (token != null && ClientReportContract.isNetworkToken(token)) {
            tokens.store(NetworkToken(token, now(), key))
            log("client-report: токен сети получен")
        }
    }

    @Synchronized
    private fun installId(): String {
        val file = File(root, "install-id")
        runCatching { file.readText().trim() }.getOrNull()?.takeIf(ClientReportContract::isInstallId)?.let { return it }
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val id = bytes.joinToString("") { "%02x".format(it) }
        root.mkdirs()
        file.writeText(id)
        return id
    }

    private fun safeVersion(version: String): String {
        val cleaned = version.filter { it.isLetterOrDigit() && it.code < 128 || it in ".+-" }.take(32)
        return cleaned.ifEmpty { "0" }
    }

    companion object {
        const val APP_NAME = "zapret-kvn"
        const val PLATFORM = "android"
        private const val TIMEOUT_MILLIS = 15_000
        private const val MAX_ANSWER_BYTES = 4096

        /** 50 вердиктов с текстами. */
        private const val MAX_REPLY_BYTES = 64 * 1024

        /**
         * Проверка пути редкая (владелец 19.11.2026: «check as rarely as possible and only if it's really necessary»):
         * раз в сутки на сервер (имя и адрес), раз в 10 минут на установку — перебор серверов проверяет не больше
         * одного. vpnbot складывает отчёты всех установок, поэтому редкая проверка теряет мало.
         */
        const val PATH_CHECK_PER_SERVER_MILLIS = 24L * 60 * 60 * 1000
        const val PATH_CHECK_PER_INSTALL_MILLIS = 10L * 60 * 1000

        /** Поток меряется внутри туннеля (на проводе ничего нового), но не на каждом подключении. */
        const val FLOW_CHECK_PER_SERVER_MILLIS = 6L * 60 * 60 * 1000

        /** Строка экрана, пока ответа vpnbot нет (своих правил и текстов вердиктов у приложения нет). */
        const val WAITING_TEXT = "Разбор сбоя появится, когда отчёт дойдёт до сервиса."
    }
}

/** Состояние отчёта о последнем отказе для экрана; [NotOurs] — отправлять нечего (нет адреса сервера). */
enum class SendStatus { Available, Sending, Sent, Queued, NotOurs }

data class FailureReport(
    val generation: Long,
    /** Идёт проверка пути. */
    val checking: Boolean,
    /** Текст вердикта vpnbot для этого отчёта (как есть) или [ClientReporter.WAITING_TEXT]; null — показывать нечего. */
    val diagnosisText: String?,
    /** Код этого вердикта vpnbot. */
    val detail: String?,
    val status: SendStatus,
)

private class PendingFailure(
    val generation: Long,
    /** Тег сервера в профиле (его имя): имя чужого сервера в отчёте. */
    val tag: String,
    val ref: ServerRef?,
    val facts: AttemptFacts,
    val elapsedMs: Long,
    val attemptedAt: Long,
    val target: ProbeTarget?,
    val outboundType: String? = null,
    /** Туннель той же попытки ещё обслуживает этот сервер (для проб «нет данных»). */
    val stillServing: (() -> Boolean)? = null,
    /** Предпроверка TCP запуска (уже сделанная, не новая проба). */
    val preflight: ReportCheck? = null,
) {
    @Volatile var checking = false
    @Volatile var checked = false
    val checks: MutableMap<String, ReportCheck> = java.util.concurrent.ConcurrentHashMap()
    @Volatile var probedAddress: String? = null
    @Volatile var record: AttemptRecord? = null
    @Volatile var status: SendStatus = if (ref == null && target == null) SendStatus.NotOurs else SendStatus.Available
}

/** Не больше [limit] байт из потока (InputStream.readNBytes есть только с API 33). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
