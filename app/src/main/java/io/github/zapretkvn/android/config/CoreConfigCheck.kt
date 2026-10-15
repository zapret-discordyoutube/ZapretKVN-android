package io.github.zapretkvn.android.config

import io.nekohasekai.libbox.Libbox
import java.security.MessageDigest

/**
 * Единственное место, где конфигурация проверяется ядром ([Libbox.checkConfig]).
 *
 * Проверка поднимает в libbox целый `box.New` и закрывает его, это 90–180 мс, а
 * запуск затем строит тот же конфиг ещё раз. Поэтому успешно прошедшие
 * конфигурации запоминаются по SHA-256 текста: повтор того же конфига
 * (переподключение на той же сети, повторная замена сервера) проверку
 * пропускает. В кэше только хэши и только успехи; отказ всегда проверяется
 * заново. Кэш живёт в памяти процесса.
 */
class CoreConfigCheck internal constructor(
    private val checker: (String) -> Unit,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val passed = object : LinkedHashMap<String, Unit>(capacity * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
    }

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * Бросает исключение ядра как есть, если конфигурация отвергнута.
     *
     * @return `true` — проверка пропущена, конфиг уже проходил её в этом процессе.
     */
    fun check(config: String): Boolean {
        val key = fingerprint(config)
        if (synchronized(passed) { passed[key] != null }) return true
        checker(config)
        synchronized(passed) { passed[key] = Unit }
        return false
    }

    /** Забыть все запомненные успехи (например, после смены файлов, на которые ссылается конфиг). */
    fun clear() = synchronized(passed) { passed.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 8

        /** Общий кэш процесса: им пользуются запуск, переключатель серверов, замена и импорт. */
        val shared = CoreConfigCheck({ config -> Libbox.checkConfig(config) })

        /** Проверка через общий кэш; см. [CoreConfigCheck.check]. */
        fun check(config: String): Boolean = shared.check(config)

        internal fun fingerprint(config: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(config.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
