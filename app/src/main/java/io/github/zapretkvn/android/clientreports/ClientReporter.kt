package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.AppIdentity
import io.github.zapretkvn.clientreports.AttemptClassifier
import io.github.zapretkvn.clientreports.AttemptFacts
import io.github.zapretkvn.clientreports.AttemptRecord
import io.github.zapretkvn.clientreports.ClientReportContract
import io.github.zapretkvn.clientreports.ClientReportJson
import io.github.zapretkvn.clientreports.NetworkToken
import io.github.zapretkvn.clientreports.NetworkTokenCache
import io.github.zapretkvn.clientreports.ReportQueue
import io.github.zapretkvn.clientreports.ReportSendPolicy
import io.github.zapretkvn.clientreports.SendAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
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
 * Решения — в модуле `client-reports` (контракт, классификация, очередь, токен); здесь только сеть
 * и привязка к Android. Таймеров нет: отправка идёт по событиям — новая попытка и подключение.
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
                val batch = queue.pending(now()).take(ClientReportContract.MAX_BATCH)
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

    /** Пакет напрямую, а без ответа — через туннель. [SendAction.TryOtherPath] — ни один путь не ответил. */
    private fun send(batch: List<AttemptRecord>): SendAction {
        val installId = installId()
        for ((via, open) in listOf(ClientReportContract.SENT_DIRECT to directConnection, ClientReportContract.SENT_TUNNEL to tunnelConnection)) {
            val body = ClientReportJson.batch(installId, app, batch, via)
            val status = post(open, body)
            val action = ReportSendPolicy.afterResponse(status)
            if (action == SendAction.TryOtherPath) continue
            log("client-report: пакет ${batch.size} ($via) → HTTP $status")
            return action
        }
        return SendAction.TryOtherPath
    }

    private fun post(open: (URL) -> HttpURLConnection?, body: String): Int? = runCatching {
        val connection = open(URL("$ENDPOINT/v1/reports")) ?: return null
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** `GET /v1/network` только напрямую: через туннель приёмник увидел бы сеть VPN-сервера. */
    private fun refreshToken(key: String?) {
        if (key == null) return
        val token = runCatching {
            val connection = directConnection(URL("$ENDPOINT/v1/network?install_id=${installId()}")) ?: return
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.useCaches = false
                if (connection.responseCode != 200) return
                val text = connection.inputStream.use { it.readBytes().take(MAX_ANSWER_BYTES).toByteArray().toString(Charsets.UTF_8) }
                ((Json.parseToJsonElement(text) as JsonObject)["network_token"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
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
        const val ENDPOINT = "https://reports.zapret.moe"
        const val APP_NAME = "zapret-kvn"
        const val PLATFORM = "android"
        private const val TIMEOUT_MILLIS = 15_000
        private const val MAX_ANSWER_BYTES = 4096
    }
}
