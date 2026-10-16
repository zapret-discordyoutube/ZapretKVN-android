package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Снимает из outbounds то, что само ходит к серверам или выделяется в трафике.
 * urltest проверяет весь пул при старте и затем каждые несколько минут — ровный
 * ритм соединений ко всем серверам сразу их и выдаёт. Он становится selector с
 * тем же тегом и составом (первый сервер по умолчанию). multiplex (sing-mux)
 * держит долгое соединение со своим рисунком кадров — его поле удаляется.
 * Один код для импорта (меняется сохраняемый JSON, с предупреждением) и для запуска
 * (страховка профилей, сохранённых раньше).
 */
internal object QuietOutbounds {
    data class Result(
        val outbounds: JsonArray,
        val urltestReplaced: Int,
        val multiplexRemoved: Int,
    ) {
        val changed: Boolean get() = urltestReplaced > 0 || multiplexRemoved > 0

        /** Предупреждения импорта: без тегов — в них бывают куски ссылок. */
        val notes: List<String>
            get() = buildList {
                if (urltestReplaced > 0) add(URLTEST_NOTE)
                if (multiplexRemoved > 0) add(MULTIPLEX_NOTE)
            }
    }

    data class JsonResult(val json: String, val notes: List<String>)

    fun apply(outbounds: JsonArray): Result {
        var urltest = 0
        var multiplex = 0
        val quiet = outbounds.map { element ->
            var outbound = element as? JsonObject ?: return@map element
            if (outbound.typeIs("urltest")) {
                outbound = toSelector(outbound)
                urltest++
            }
            if ("multiplex" in outbound) {
                outbound = JsonObject(outbound - "multiplex")
                multiplex++
            }
            outbound
        }
        return Result(JsonArray(quiet), urltest, multiplex)
    }

    /** Для импорта: JSON без изменений, если менять нечего. */
    fun applyToJson(json: String): JsonResult {
        val root = JsonConfig.parse(json) as? JsonObject ?: return JsonResult(json, emptyList())
        val outbounds = root["outbounds"] as? JsonArray ?: return JsonResult(json, emptyList())
        val result = apply(outbounds)
        if (!result.changed) return JsonResult(json, emptyList())
        val updated = JsonObject(root + ("outbounds" to result.outbounds))
        return JsonResult(JsonConfig.format(updated), result.notes)
    }

    private fun toSelector(urltest: JsonObject): JsonObject {
        val members = urltest["outbounds"] as? JsonArray
        val first = members?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString) }
        return JsonObject(
            buildMap {
                put("type", JsonPrimitive("selector"))
                urltest["tag"]?.let { put("tag", it) }
                members?.let { put("outbounds", it) }
                first?.let { put("default", it) }
                urltest["interrupt_exist_connections"]?.let { put("interrupt_exist_connections", it) }
            },
        )
    }

    private fun JsonObject.typeIs(type: String): Boolean =
        (this["type"] as? JsonPrimitive)?.content.equals(type, ignoreCase = true)

    const val URLTEST_NOTE =
        "Автовыбор urltest заменён обычным выбором сервера: он сам проверял все серверы " +
            "каждые несколько минут, а такие проверки выдают серверы провайдеру."
    const val MULTIPLEX_NOTE =
        "Мультиплексирование (multiplex) отключено: такое соединение легче отличить от обычного HTTPS."
}
