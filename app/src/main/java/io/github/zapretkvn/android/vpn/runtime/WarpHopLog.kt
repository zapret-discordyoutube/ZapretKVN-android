package io.github.zapretkvn.android.vpn.runtime

/**
 * What the core says about the WARP entry hop in its first seconds, as lines of the app layer of the runtime log
 * (owner request 2026-11-26, "improve the logs", after a diagnostic whose slow start could not be explained).
 *
 * The core prefixes every line with the seconds since it started (`INFO[0003]`), so each line says when, on the core's own
 * clock, the tunnel answered or did not: the time a start spent waiting for WARP is then read from one place. Only ports
 * are kept, never addresses. Pure: the caller decides where the lines go.
 */
internal object WarpHopLog {
    private val RETRY = Regex("""^\w+\[(\d+)] endpoint/warp\[kvn-warp-entry]: WARP handshake did not answer at \S*?:(\d+), trying""")
    private val ANSWERED = Regex("""^\w+\[(\d+)] endpoint/warp\[kvn-warp-entry]: WARP tunnel answered at \S*?:(\d+) \(attempt (\d+)\)""")
    private val FIRST_RESPONSE = Regex("""^\w+\[(\d+)] endpoint/warp\[kvn-warp-entry]: peer\(.*\) - received handshake response""")

    /** Later handshakes are rekeys every couple of minutes: only the start is interesting. */
    private const val START_WINDOW_SECONDS = 30

    fun lines(messages: List<String>): List<String> = messages.mapNotNull { message ->
        RETRY.find(message)?.let { match ->
            return@mapNotNull "вход через WARP: рукопожатие не ответило на порту ${match.groupValues[2]}, " +
                "пробуется следующий адрес (${match.groupValues[1].toInt()} с после запуска ядра)"
        }
        ANSWERED.find(message)?.let { match ->
            return@mapNotNull "вход через WARP: туннель ответил на порту ${match.groupValues[2]}, " +
                "попытка ${match.groupValues[3]} (${match.groupValues[1].toInt()} с после запуска ядра)"
        }
        FIRST_RESPONSE.find(message)?.let { match ->
            val seconds = match.groupValues[1].toInt()
            if (seconds <= START_WINDOW_SECONDS) {
                "вход через WARP: рукопожатие получено ($seconds с после запуска ядра)"
            } else {
                null
            }
        }
    }
}
