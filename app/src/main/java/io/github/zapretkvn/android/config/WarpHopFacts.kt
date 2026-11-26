package io.github.zapretkvn.android.config

/**
 * Факты о входе через WARP для отчёта о подключении (как `WarpFacts` на Windows, `network/warp_hop_health.py`).
 *
 * Только чтение для модуля отчётов: слова результата из словаря проб контракта (answered/timeout/reset/refused/unknown),
 * без адресов и ключей. `register` нет, если файл аккаунта уже был (ничего не запускалось: проба не шлётся). `retries`
 * и `tunnel` берутся из собственных строк ядра о входе (`noteCoreLines`, как `HopHealth` на Windows): число рукопожатий без
 * ответа и итог первых секунд туннеля; пока ядро ничего не сказало, они пусты, а не выдуманы.
 */
data class WarpHopFacts(
    val enabled: Boolean,
    val register: Probe?,
    val retries: Int? = null,
    val tunnel: Probe? = null,
) {
    data class Probe(val result: String, val ms: Long)

    companion object {
        @Volatile
        private var current = WarpHopFacts(enabled = false, register = null)

        /** Факты текущего подключения; пишет SessionStarter при старте сессии. */
        fun current(): WarpHopFacts = current

        /** Что ядро сказало о входе с начала этого подключения (до [TUNNEL_WINDOW_SECONDS] секунд ядра). */
        private var unanswered = 0
        private var answeredAtMs: Long? = null
        private var lastRetryAtMs = 0L

        private const val TUNNEL_WINDOW_SECONDS = 30
        private const val RETRY_LIMIT = 4
        private val RETRY = Regex("""^\w+\[(\d+)] endpoint/warp\[kvn-warp-entry]: WARP handshake did not answer""")
        private val ANSWER = Regex(
            """^\w+\[(\d+)] endpoint/warp\[kvn-warp-entry]: (?:WARP tunnel answered at |peer\(.*\) - received handshake response)""",
        )

        /**
         * Счёт строк ядра о входе, только для отчёта и только в окне старта: порты и адреса сюда не попадают.
         * [tunnel] = answered (с временем ответа на часах ядра) или timeout, когда [RETRY_LIMIT] рукопожатий остались без
         * ответа; пока ни того ни другого нет, пусто.
         */
        @Synchronized
        fun noteCoreLines(messages: List<String>) {
            if (!current.enabled) return
            for (message in messages) {
                RETRY.find(message)?.let { match ->
                    val seconds = match.groupValues[1].toLong()
                    if (seconds <= TUNNEL_WINDOW_SECONDS && answeredAtMs == null) {
                        unanswered += 1
                        lastRetryAtMs = seconds * 1000
                    }
                    return@let
                }
                ANSWER.find(message)?.let { match ->
                    val seconds = match.groupValues[1].toLong()
                    if (seconds <= TUNNEL_WINDOW_SECONDS && answeredAtMs == null) answeredAtMs = seconds * 1000
                }
            }
            val answered = answeredAtMs
            current = current.copy(
                retries = unanswered,
                tunnel = when {
                    answered != null -> Probe("answered", answered)
                    unanswered >= RETRY_LIMIT -> Probe("timeout", lastRetryAtMs)
                    else -> null
                },
            )
        }

        @Synchronized
        fun record(enabled: Boolean, registration: WarpAccountRegistration.Outcome?) {
            unanswered = 0
            answeredAtMs = null
            lastRetryAtMs = 0L
            current = WarpHopFacts(
                enabled = enabled,
                register = registration?.takeIf { it.route != "cached" }?.let { Probe(resultOf(it), it.elapsedMs) },
            )
        }

        /** Слово словаря проб для итога регистрации аккаунта. */
        internal fun resultOf(outcome: WarpAccountRegistration.Outcome): String {
            if (outcome.ok) return "answered"
            val text = outcome.detail.lowercase()
            return when {
                "reset" in text || "forcibly closed" in text || "closed by the remote host" in text || "eof" in text -> "reset"
                "refused" in text -> "refused"
                "timeout" in text || "timed out" in text || "no answer in time" in text -> "timeout"
                else -> "unknown"
            }
        }
    }
}
