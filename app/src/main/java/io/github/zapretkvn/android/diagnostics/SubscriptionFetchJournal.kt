package io.github.zapretkvn.android.diagnostics

/**
 * Последние строки о загрузке подписок для диагностического отчёта. Адрес сервера в
 * строках уже замаскирован (`FetchMasking.hostTag`), путь и токен ссылки сюда не попадают.
 * Ограничен по длине: старое вытесняется, отчёт остаётся маленьким.
 */
class SubscriptionFetchJournal(private val capacity: Int = 120) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String, nowMillis: Long = System.currentTimeMillis()) {
        lines.addLast("$nowMillis $line")
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
