package io.github.zapretkvn.android.config

/**
 * Факты о входе через WARP для отчёта о подключении (как `WarpFacts` на Windows, `network/warp_hop_health.py`).
 *
 * Только чтение для модуля отчётов: слова результата из словаря проб контракта (answered/timeout/reset/refused/unknown),
 * без адресов и ключей. `register` нет, если файл аккаунта уже был (ничего не запускалось: проба не шлётся). `retries`
 * и `tunnel` на Android пока не измеряются (ядро пишет повторы рукопожатия на уровне WARN, счётчика нет), поэтому
 * остаются пустыми, а не выдуманными.
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

        fun record(enabled: Boolean, registration: WarpAccountRegistration.Outcome?) {
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
