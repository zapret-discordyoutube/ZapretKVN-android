package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.AppIdentity
import io.github.zapretkvn.clientreports.AttemptClassifier
import io.github.zapretkvn.clientreports.AttemptFacts
import io.github.zapretkvn.clientreports.AttemptRecord
import io.github.zapretkvn.clientreports.BlockDiagnosis
import io.github.zapretkvn.clientreports.Diagnosis
import io.github.zapretkvn.clientreports.DiagnosisFacts
import io.github.zapretkvn.clientreports.ClientReportContract
import io.github.zapretkvn.clientreports.ClientReportJson
import io.github.zapretkvn.clientreports.IngestHostCache
import io.github.zapretkvn.clientreports.IngestHosts
import io.github.zapretkvn.clientreports.NetworkToken
import io.github.zapretkvn.clientreports.NetworkTokenCache
import io.github.zapretkvn.clientreports.ReportQueue
import io.github.zapretkvn.clientreports.ReportSendPolicy
import io.github.zapretkvn.clientreports.SendAction
import io.github.zapretkvn.clientreports.ServerRef
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
 * После отказа [BlockProbe] проверяет путь до сервера по ступеням ([BlockDiagnosis]): вывод идёт и в
 * отчёт, и на экран ошибки ([failureReport]); кнопка «Отправить отчёт» — [forceReport]. После удачи
 * один раз в [BlockDiagnosis.MIN_INTERVAL_MILLIS] на сервер меряется заморозка через туннель.
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
    /** Сеть поднятого туннеля для замера заморозки. */
    private val vpnNetwork: () -> Network? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val app = AppIdentity(APP_NAME, safeVersion(appVersion), PLATFORM)
    private val queue = ReportQueue(File(root, "queue.jsonl"))
    private val tokens = NetworkTokenCache(File(root, "network-token"))
    private val ingest = IngestHostCache(File(root, "ingest-hosts"))
    private val random = SecureRandom().asKotlinRandom()

    /** Имя → когда оно не ответило; такое имя идёт последним [IngestHosts.FAILED_FOR_MILLIS]. */
    private val failedHosts = HashMap<String, Long>()
    private val flushing = Mutex()
    private val preflight = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 32
    }

    @Volatile
    private var stoppedUntilNextAttempt = false

    /** Сервер (адрес) → когда его проверяли; проба не чаще [BlockDiagnosis.MIN_INTERVAL_MILLIS]. */
    private val probedAt = HashMap<String, Long>()
    private val frozenCheckedAt = HashMap<String, Long>()

    /** Попытки, о которых отчёт уже пишет проверка сервера: отказ запуска их не дублирует. */
    private val attemptedGenerations = object : LinkedHashMap<Long, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 16
    }

    private val failureState = MutableStateFlow<FailureReport?>(null)
    private var failure: PendingFailure? = null
    private val failureLock = Mutex()

    /** Последний отказ для экрана ошибки: что нашла проверка пути и что с отчётом. */
    val failureReport: StateFlow<FailureReport?> = failureState.asStateFlow()

    /** Итог предварительного TCP-соединения к [tag] в сессии [generation] (только для его же отчёта). */
    fun notePreflight(generation: Long, tag: String, failed: Boolean) {
        synchronized(preflight) { preflight["$generation|$tag"] = failed }
    }

    fun preflightFailed(generation: Long, tag: String): Boolean =
        synchronized(preflight) { preflight["$generation|$tag"] == true }

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
    ) {
        if (!facts.succeeded && !facts.cancelled) synchronized(attemptedGenerations) { attemptedGenerations[generation] = true }
        scope.launch {
            runCatching {
                val ref = serverIds.lookup(tag, outboundType)
                if (facts.cancelled || facts.succeeded) {
                    // Заморозка меряется только у серверов сервиса: о чужом отчёта нет, а 64 КБ тратятся.
                    val diagnosis = if (facts.succeeded && ref != null) checkFreeze(target) else null
                    if (facts.succeeded) clearFailure()
                    if (ref != null) record(ref, AttemptClassifier.classify(facts, diagnosis), elapsedMs, attemptedAtMillis)
                    return@runCatching
                }
                onFailure(PendingFailure(generation, ref, facts, elapsedMs, attemptedAtMillis, target))
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
                onFailure(PendingFailure(generation, serverIds.lookup(tag, outboundType), facts, 0, startedAt, target))
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
        if (target != null && !pending.facts.networkLost && probe != null && takeSlot(probedAt, target.host)) {
            pending.checking = true
            publish(pending)
            val facts = runCatching { probe.run(target) }.getOrNull()
            val diagnosis = facts?.let { if (target.udp) BlockDiagnosis.diagnoseUdp(it) else BlockDiagnosis.diagnose(it) }
            pending.diagnosisFacts = facts
            pending.diagnosis = diagnosis
            pending.checked = true
            pending.checking = false
            log("client-report: проверка пути → ${diagnosis?.let { "${it.failedStep}/${it.detail}" } ?: "без вывода"} (${summary(facts)})")
        }
        val ref = pending.ref
        if (ref != null) {
            pending.record = record(ref, AttemptClassifier.classify(pending.facts, pending.diagnosis), pending.elapsedMs, pending.attemptedAt)
        }
        pending.status = statusOf(pending)
        publish(pending)
    }

    /**
     * Кнопка «Отправить отчёт»: отказ, о котором отчёт ещё не написан, пишется с пометкой `local_*`,
     * если он не про сервер; очередь отправляется сразу. Итог — на [failureReport].
     */
    fun forceReport() {
        scope.launch {
            runCatching {
                val pending = failure ?: return@runCatching
                val ref = pending.ref ?: return@runCatching
                pending.status = SendStatus.Sending
                publish(pending)
                if (pending.record == null) {
                    pending.record = record(ref, AttemptClassifier.classifyForced(pending.facts, pending.diagnosis), pending.elapsedMs, pending.attemptedAt, flushNow = false)
                }
                stoppedUntilNextAttempt = false
                flushLocked(wait = true)
                pending.status = statusOf(pending)
                log("client-report: отчёт по кнопке — ${if (pending.status == SendStatus.Sent) "отправлен" else "в очереди"}")
                publish(pending)
            }.onFailure { log("client-report: отчёт по кнопке не записан: ${it.javaClass.simpleName}") }
        }
    }

    private fun statusOf(pending: PendingFailure): SendStatus {
        val record = pending.record ?: return if (pending.ref == null) SendStatus.NotOurs else SendStatus.Available
        return if (queue.pending(now()).contains(record)) SendStatus.Queued else SendStatus.Sent
    }

    private fun publish(pending: PendingFailure) {
        if (failure !== pending) return
        failureState.value = FailureReport(
            generation = pending.generation,
            checking = pending.checking,
            diagnosisText = if (pending.checked) BlockDiagnosis.describe(pending.diagnosis, pending.diagnosisFacts) else null,
            detail = pending.diagnosis?.detail,
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

    /** Заморозка после удачи: не чаще раза в [BlockDiagnosis.MIN_INTERVAL_MILLIS] на сервер, только TCP. */
    private suspend fun checkFreeze(target: ProbeTarget?): Diagnosis? {
        val probe = probe
        if (target == null || target.udp || probe == null || !takeSlot(frozenCheckedAt, target.host)) return null
        val stalled = runCatching { probe.freeze(vpnNetwork()) }.getOrNull()
        val diagnosis = BlockDiagnosis.freeze(stalled)
        log("client-report: замер заморозки → ${stalled?.let { "остановка после $it Б" } ?: "без остановки"}${if (diagnosis != null) ", freeze_16k" else ""}")
        return diagnosis
    }

    private fun takeSlot(map: HashMap<String, Long>, key: String): Boolean = synchronized(map) {
        val last = map[key]
        if (last != null && now() - last < BlockDiagnosis.MIN_INTERVAL_MILLIS) return false
        map[key] = now()
        if (map.size > 64) map.entries.removeAll { now() - it.value >= BlockDiagnosis.MIN_INTERVAL_MILLIS }
        true
    }

    /** Ступени проверки одной строкой для журнала (без адресов). */
    private fun summary(facts: DiagnosisFacts?): String = facts?.let {
        "control=${it.control} tcp=${it.tcpTarget} tcp443=${it.tcpOtherPort} tls=${it.tlsRealName} " +
            "neutral=${it.tlsNeutralName} elsewhere=${it.tlsRealNameElsewhere} dns=${it.systemDns?.size}/${it.dohDns?.size}" +
            if (it.dohNameMissing) " nxdomain" else ""
    } ?: "проба не удалась"

    private suspend fun record(
        ref: ServerRef,
        verdict: io.github.zapretkvn.clientreports.AttemptVerdict?,
        elapsedMs: Long,
        attemptedAtMillis: Long,
        flushNow: Boolean = true,
    ): AttemptRecord? {
        verdict ?: return null
        val key = networkKey()
        if (tokens.needsRefresh(key, now())) refreshToken(key)
        val record = AttemptRecord(
            serverId = ref.serverId,
            product = ref.product,
            outcome = verdict.outcome,
            failedStep = verdict.failedStep,
            detail = verdict.detail,
            elapsedMs = elapsedMs.coerceIn(0, ClientReportContract.MAX_ELAPSED_MS),
            attemptedAtMillis = attemptedAtMillis,
            networkToken = tokens.current(key, now()),
        )
        queue.add(record)
        log(
            "client-report: ${ref.product}/${ref.serverId} ${verdict.outcome.code}" +
                (if (verdict.failedStep.isNotEmpty()) " на шаге ${verdict.failedStep} (${verdict.detail})" else "") +
                ", в очереди ${queue.size()}",
        )
        if (flushNow) {
            stoppedUntilNextAttempt = false
            flushLocked()
        }
        return record
    }

    /** Повод отправить очередь (подключение, запуск приложения). */
    fun flush() {
        if (stoppedUntilNextAttempt) return
        scope.launch { runCatching { flushLocked() } }
    }

    private suspend fun flushLocked(wait: Boolean = false) {
        if (wait) flushing.lock() else if (!flushing.tryLock()) return
        try {
            while (true) {
                val installId = installId()
                val batch = IngestHosts.takeBatch(queue.pending(now()), ClientReportContract.MAX_BATCH, IngestHosts.MAX_BODY_BYTES) {
                    ClientReportJson.batch(installId, app, it, ClientReportContract.SENT_DIRECT).toByteArray(Charsets.UTF_8).size
                }
                if (batch.isEmpty()) return
                val action = send(batch)
                when (action) {
                    SendAction.Remove, SendAction.Drop -> queue.remove(batch)
                    SendAction.KeepAndStop, SendAction.TryOtherPath -> {
                        stoppedUntilNextAttempt = action == SendAction.KeepAndStop
                        return
                    }
                }
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
            val body = ClientReportJson.batch(installId, app, batch, via).toByteArray(Charsets.UTF_8)
            for (host in hostOrder().take(IngestHosts.TRIES_PER_PATH)) {
                val status = post(open, host, body) ?: run {
                    noteFailed(host)
                    null
                } ?: continue
                val action = ReportSendPolicy.afterResponse(status)
                if (action == SendAction.TryOtherPath) {
                    noteFailed(host)
                    continue
                }
                log("client-report: пакет ${batch.size}, ${body.size} Б ($via, ${IngestHosts.domainOf(host)}) → HTTP $status")
                return action
            }
        }
        return null
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

    /** Одно короткое соединение на запрос: без повторного использования, тело фиксированной длины. */
    private fun post(open: (URL) -> HttpURLConnection?, host: String, body: ByteArray): Int? = runCatching {
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
            connection.responseCode
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
    }
}

/** Состояние отчёта о последнем отказе для экрана. */
enum class SendStatus { Available, Sending, Sent, Queued, NotOurs }

data class FailureReport(
    val generation: Long,
    /** Идёт проверка пути. */
    val checking: Boolean,
    /** Что нашла проверка; null — не проверяли (недавно проверен или проверка ещё идёт). */
    val diagnosisText: String?,
    val detail: String?,
    val status: SendStatus,
)

private class PendingFailure(
    val generation: Long,
    val ref: ServerRef?,
    val facts: AttemptFacts,
    val elapsedMs: Long,
    val attemptedAt: Long,
    val target: ProbeTarget?,
) {
    @Volatile var checking = false
    @Volatile var checked = false
    @Volatile var diagnosis: Diagnosis? = null
    @Volatile var diagnosisFacts: DiagnosisFacts? = null
    @Volatile var record: AttemptRecord? = null
    @Volatile var status: SendStatus = if (ref == null) SendStatus.NotOurs else SendStatus.Available
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
