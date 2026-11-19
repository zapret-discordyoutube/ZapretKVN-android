package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.AppIdentity
import io.github.zapretkvn.clientreports.AttemptClassifier
import io.github.zapretkvn.clientreports.AttemptFacts
import io.github.zapretkvn.clientreports.AttemptRecord
import io.github.zapretkvn.clientreports.ClientReportContract
import io.github.zapretkvn.clientreports.ClientReportJson
import io.github.zapretkvn.clientreports.IngestHostCache
import io.github.zapretkvn.clientreports.IngestHosts
import io.github.zapretkvn.clientreports.NetworkToken
import io.github.zapretkvn.clientreports.NetworkTokenCache
import io.github.zapretkvn.clientreports.ReportQueue
import io.github.zapretkvn.clientreports.ReportSendPolicy
import io.github.zapretkvn.clientreports.SendAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
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

    /** Итог предварительного TCP-соединения к [tag] в сессии [generation] (только для его же отчёта). */
    fun notePreflight(generation: Long, tag: String, failed: Boolean) {
        synchronized(preflight) { preflight["$generation|$tag"] = failed }
    }

    fun preflightFailed(generation: Long, tag: String): Boolean =
        synchronized(preflight) { preflight["$generation|$tag"] == true }

    /**
     * Конец одной проверки сервера [tag]. Сервер не из подписки сервиса или отказ не про сервер —
     * ничего не пишется. Вызов дешёвый: запись и отправка идут в фоне.
     */
    fun onAttempt(tag: String, outboundType: String?, facts: AttemptFacts, elapsedMs: Long, attemptedAtMillis: Long) {
        scope.launch {
            runCatching {
                val ref = serverIds.lookup(tag, outboundType) ?: return@runCatching
                val verdict = AttemptClassifier.classify(facts) ?: return@runCatching
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
                stoppedUntilNextAttempt = false
                flushLocked()
            }.onFailure { log("client-report: запись не удалась: ${it.javaClass.simpleName}") }
        }
    }

    /** Повод отправить очередь (подключение, запуск приложения). */
    fun flush() {
        if (stoppedUntilNextAttempt) return
        scope.launch { runCatching { flushLocked() } }
    }

    private suspend fun flushLocked() {
        if (!flushing.tryLock()) return
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
