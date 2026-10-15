package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Неизменяемый разобранный JSON профиля: исходный текст и дерево, которое один
 * раз получено из него. Подключение разбирает профиль один раз и передаёт этот
 * объект всем анализаторам вместо повторного [JsonConfig.parse] одного и того же
 * текста.
 *
 * [raw] — ровно тот текст, из которого построено дерево, либо (для дерева,
 * собранного кодом, [fromTree]) его [JsonConfig.format]; вычисляется лениво и
 * один раз. Дерево не изменяется: правки строят новое дерево и новый
 * [ParsedConfig].
 */
class ParsedConfig private constructor(
    /** Разобранное дерево; не обязательно объект — как и у [JsonConfig.parse]. */
    val element: JsonElement,
    rawText: String?,
) {
    /** Корень, если он объект; иначе null (вызывающий решает, это ошибка или пустой результат). */
    val root: JsonObject? = element as? JsonObject

    private val rawLazy = if (rawText != null) lazyOf(rawText) else lazy { JsonConfig.format(element) }

    /** Текст конфигурации: исходный или (для собранного дерева) отформатированный, считается один раз. */
    val raw: String get() = rawLazy.value

    override fun equals(other: Any?): Boolean = other is ParsedConfig && other.element == element

    override fun hashCode(): Int = element.hashCode()

    override fun toString(): String = "ParsedConfig(${element::class.java.simpleName})"

    companion object {
        /** Разбор с теми же исключениями при синтаксической ошибке, что и [JsonConfig.parse]. */
        fun parse(raw: String): ParsedConfig = ParsedConfig(JsonConfig.parse(raw), raw)

        /** Дерево, уже построенное кодом: текст форматируется лениво и без повторного разбора. */
        fun fromTree(element: JsonElement): ParsedConfig = ParsedConfig(element, null)

        /** Дерево с заранее известным текстом [raw], который ему соответствует. */
        fun of(element: JsonElement, raw: String): ParsedConfig = ParsedConfig(element, raw)
    }
}
