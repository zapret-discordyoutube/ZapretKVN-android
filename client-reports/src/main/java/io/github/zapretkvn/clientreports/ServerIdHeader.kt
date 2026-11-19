package io.github.zapretkvn.clientreports

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Заголовок `zapret-server-ids` ответа подписки сервиса (решение владельца 19.11.2026): id каталога
 * каждого сервера, не трогая сами ссылки для других клиентов. Значение — base64url без выравнивания от
 * сжатого zlib (RFC 1950) JSON `{"<имя из #фрагмента>": {"server_id": "...", "product": "..."}}`:
 * без сжатия карта большой подписки VLESS заняла бы ~111 КБ, сервер держит значение ≤ 14 336 знаков.
 *
 * Имя — фрагмент ссылки после percent-декодирования, то есть то, что приложение показывает и из чего
 * строит тег сервера. Сервер без записи (или с записью вне контракта) в отчёты не попадает:
 * приёмник отклонил бы весь пакет, а угадывать id по имени нельзя.
 */
object ServerIdHeader {
    const val NAME = "zapret-server-ids"

    /** Больше этого значение не читается: заголовок такой длины — не карта серверов. */
    const val MAX_VALUE_CHARS = 16 * 1024

    fun parse(value: String?): Map<String, ServerRef> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_VALUE_CHARS) return emptyMap()
        val decoded = runCatching {
            String(inflate(Base64.getUrlDecoder().decode(raw.trimEnd('='))), Charsets.UTF_8)
        }.getOrNull() ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(decoded) as? JsonObject }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, ServerRef>()
        root.forEach { (name, entry) ->
            val fields = entry as? JsonObject ?: return@forEach
            val serverId = (fields["server_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            val product = (fields["product"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            if (name.isBlank() || !ClientReportContract.isServerId(serverId)) return@forEach
            if (product !in ClientReportContract.PRODUCTS) return@forEach
            result[name] = ServerRef(serverId, product)
        }
        return result
    }

    /** Распаковка zlib с потолком: значение заголовка не должно раздуться в память. */
    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("truncated")
                output.write(buffer, 0, count)
                check(output.size() <= MAX_INFLATED_BYTES) { "too large" }
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** Больше этого карта не распаковывается: ~1000 серверов занимают ~110 КБ JSON. */
    const val MAX_INFLATED_BYTES = 512 * 1024

    /**
     * Продукт каталога, который может обслуживать outbound этого типа. Запись заголовка берётся,
     * только если её продукт совпадает: одно имя в подписках VLESS и Hysteria 2 — разные серверы.
     */
    fun productsForOutboundType(type: String?): Set<String> = when (type?.lowercase()) {
        // Подписка продукта VLESS несёт и строки Trojan/Shadowsocks/VMess: их продукт тоже vless.
        "vless", "trojan", "shadowsocks", "vmess" -> setOf("vless")
        "hysteria2" -> setOf("hysteria2")
        "wireguard", "awg", "amneziawg" -> setOf("wireguard", "awg")
        else -> emptySet()
    }
}
