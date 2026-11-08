package io.github.zapretkvn.runtimelog

/**
 * Слой, в котором родилась строка журнала. Однобуквенный код идёт в файл: строка короче, а
 * `grep ' D'` отбирает слой без разбора формата.
 *
 * Слои повторяют путь пакета от приложения до сервера, чтобы по журналу было видно, на каком
 * шаге пропал ответ: приложение → сеть Android → DNS → маршрутизация → TUN → outbound → трафик.
 */
enum class LogLayer(val code: Char, val title: String) {
    App('A', "app"),
    Network('N', "network"),
    Dns('D', "dns"),
    Routing('R', "routing"),
    Tun('T', "tun"),
    Outbound('O', "outbound"),
    Traffic('F', "traffic"),
    Core('C', "core"),
    ;

    companion object {
        fun of(code: Char): LogLayer? = entries.firstOrNull { it.code == code }
    }
}

/** Одна запись журнала: момент, слой, уровень (0 panic … 6 trace, как у ядра) и текст как есть. */
data class LogRecord(
    val epochMillis: Long,
    val layer: LogLayer,
    val level: Int,
    val text: String,
)
