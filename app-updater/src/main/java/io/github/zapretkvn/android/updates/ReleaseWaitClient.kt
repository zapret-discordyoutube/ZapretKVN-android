package io.github.zapretkvn.android.updates

import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.coroutineContext

/**
 * Слушатель службы очереди обновлений — тот же, что у Zapret KVN для Windows.
 *
 * Служба держит вопрос «есть ли версия новее моей?» до нескольких минут и отвечает сразу, как только
 * версия вышла и до этой программы дошла очередь (ступени раздачи, ширина канала). Так новая версия
 * приходит за секунды, а не через сутки, а сломанную служба перестаёт раздавать сама: каждая программа
 * сообщает ей «дошла» или «не вышло» ([report], один раз).
 *
 * Слушатель работает, пока процесс жив (его запускает приложение, пока включён VPN); разрешение
 * передаётся в [onGrant] один раз на версию — дальше обычная проверка и загрузка [UpdateController].
 */
class ReleaseWaitClient(
    private val currentVersion: String,
    private val http: suspend (url: String, readTimeoutMillis: Int) -> String,
    private val report: () -> Map<String, String>,
    private val reportDelivered: (Map<String, String>) -> Unit,
    private val onGrant: (version: String) -> Unit,
    private val baseUrl: String = WAIT_URL,
    private val channel: String = CHANNEL_STABLE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) {
    @Volatile
    private var grantedVersion: String? = null
    private var ticket = 0L

    /** Цикл вопросов к службе; выходит только отменой корутины. */
    suspend fun run() {
        val started = clock()
        var failures = 0
        var first = true
        while (coroutineContext.isActive) {
            val hold = when {
                first -> 0
                clock() - started < WARMUP_MILLIS -> WARMUP_HOLD_SECONDS
                else -> null
            }
            val told = report()
            val asked = clock()
            val answer = try {
                val raw = http(url(hold, told), if (hold == 0) PROBE_TIMEOUT_MILLIS else READ_TIMEOUT_MILLIS)
                Json.parseToJsonElement(raw).jsonObject
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            first = false
            if (answer == null) {
                failures += 1
                pause(minOf(FIRST_FAILURE_PAUSE_MILLIS shl (failures - 1).coerceAtMost(8), MAX_FAILURE_PAUSE_MILLIS))
                continue
            }
            failures = 0
            if (told.isNotEmpty()) reportDelivered(told)
            val granted = handle(answer)
            // Пока версия ставится, служба на каждый вопрос сразу отвечает «есть новее»: без паузы
            // слушатель задавал бы десятки вопросов в минуту. Так же — любой ответ, пришедший
            // слишком быстро на вопрос с ожиданием.
            val quick = clock() - asked < QUICK_ANSWER_MILLIS
            if (granted || (hold != 0 && quick)) pause(QUICK_ANSWER_PAUSE_MILLIS)
        }
    }

    /** True — служба впервые разрешила эту версию, и она передана в [onGrant]. */
    private fun handle(answer: JsonObject): Boolean {
        answer["ticket"]?.jsonPrimitive?.longOrNull?.let { ticket = it }
        if (answer["changed"]?.jsonPrimitive?.booleanOrNull != true) return false
        val version = answer["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return false
        if (version == grantedVersion || version == currentVersion) return false
        grantedVersion = version
        onGrant(version)
        return true
    }

    internal fun url(hold: Int?, told: Map<String, String>): String {
        val params = linkedMapOf("channel" to channel, "known" to currentVersion)
        if (hold != null) params["hold"] = hold.toString()
        if (ticket > 0) params["ticket"] = ticket.toString()
        params.putAll(told)
        return baseUrl + "?" + params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

    companion object {
        // Очередь обновлений — на первом зеркале: основное имя git.zapret.moe режут по TLS.
        val WAIT_URL = "https://${ForgejoHttpsClient.FORGEJO_HOSTS.first()}/api/zapret/kvn-android/wait"
        const val CHANNEL_STABLE = "stable"

        /** Первый вопрос — без ожидания; служба отвечает сразу. */
        const val PROBE_TIMEOUT_MILLIS = 15_000

        /** Служба держит вопрос до четырёх с половиной минут; ждём немного дольше. */
        const val READ_TIMEOUT_MILLIS = 330_000
        const val WARMUP_MILLIS = 120_000L
        const val WARMUP_HOLD_SECONDS = 45
        const val QUICK_ANSWER_MILLIS = 20_000L
        const val QUICK_ANSWER_PAUSE_MILLIS = 120_000L
        const val FIRST_FAILURE_PAUSE_MILLIS = 60_000L
        const val MAX_FAILURE_PAUSE_MILLIS = 180_000L
    }
}
