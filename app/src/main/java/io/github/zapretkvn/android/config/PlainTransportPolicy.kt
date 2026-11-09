package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Xray 26.11 отказывается строить VLESS без TLS, Reality и шифрования, если адрес сервера не частный
 * (`infra/conf/xray.go: requiresTransportSecurity`). Ядро собирает outbound сразу при старте, поэтому один такой сервер
 * в профиле останавливал бы запуск целиком, да ещё с сырым английским текстом. Правило повторено здесь, чтобы такой
 * сервер отвергался при импорте (в подписке пропускается) и объяснялся при запуске уже сохранённого профиля (CFG-121).
 * Решение о правиле принимает Xray: приложение его не обходит, а только заранее называет причину.
 */
object PlainTransportPolicy {
    const val FAILURE_CODE = "CFG-121"

    const val IMPORT_MESSAGE =
        "VLESS без TLS, Reality и шифрования к публичному адресу больше не принимается ядром Xray 26.11. " +
            "Попросите у провайдера ключ с security=tls или security=reality."

    const val RUNTIME_MESSAGE =
        "Сервер в этом профиле использует VLESS без TLS, Reality и шифрования на публичном адресе: ядро Xray 26.11 такое " +
            "не запускает. Обновите подписку (такие серверы будут пропущены) или попросите ключ с TLS или Reality."

    private val PRIVATE_DOMAIN_SUFFIXES =
        listOf("lan", "localdomain", "example", "invalid", "localhost", "test", "local", "home.arpa", "internal")
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** true — ссылка VLESS без защиты транспорта к публичному адресу. */
    fun violatesLink(link: String): Boolean {
        if (!link.startsWith("vless://", ignoreCase = true)) return false
        val rest = link.substring("vless://".length)
        val authority = rest.substringBefore('?').substringBefore('#').substringBefore('/')
        val query = rest.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            if (key.isEmpty()) null else key to pair.substringAfter('=', "").lowercase()
        }.toMap()
        if ((query["security"] ?: "none") !in setOf("", "none")) return false
        if ((query["encryption"] ?: "none") !in setOf("", "none")) return false
        return needsTransportSecurity(hostOf(authority.substringAfterLast('@')))
    }

    /** Тот же вопрос про сохранённый outbound: ссылка `uri` или поля sing-box (`server`, `tls`). */
    fun violates(outbound: JsonObject): Boolean {
        if (outbound.text("type")?.lowercase() != "vless") return false
        outbound.text("uri")?.takeIf { it.isNotBlank() }?.let { return violatesLink(it) }
        val tls = outbound["tls"] as? JsonObject
        if (tls?.text("enabled") == "true" || (tls?.get("reality") as? JsonObject)?.text("enabled") == "true") return false
        if ((outbound.text("encryption") ?: "none").lowercase() !in setOf("", "none")) return false
        return needsTransportSecurity(outbound.text("server").orEmpty())
    }

    /** Теги outbound профиля, которые нарушают правило. */
    fun violatingTags(root: JsonObject): List<String> =
        ((root["outbounds"] as? JsonArray).orEmpty()).mapNotNull { element ->
            (element as? JsonObject)?.takeIf(::violates)?.let { it.text("tag").orEmpty() }
        }

    /** Профиль без серверов, которые Xray не соберёт, и теги убранных серверов. */
    data class Stripped(val root: JsonObject, val removed: List<String>)

    /**
     * Серверы-заглушки провайдера («осталось 30 дней», `vless://…@0.0.0.0:1?security=none`) лежат в подписке рядом с
     * настоящими, и Xray 26.11 отвергает их при сборке, останавливая запуск всего профиля. Такие серверы убираются из
     * рантайм-конфига вместе со ссылками на них в группах; сохранённый профиль не меняется.
     */
    fun strip(root: JsonObject): Stripped {
        val removed = violatingTags(root)
        if (removed.isEmpty()) return Stripped(root, emptyList())
        val drop = removed.toSet()
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull element
            if (outbound.text("tag") in drop && violates(outbound)) return@mapNotNull null
            val members = outbound["outbounds"] as? JsonArray ?: return@mapNotNull outbound
            val kept = members.filter { (it as? JsonPrimitive)?.contentOrNull !in drop }
            val fields = outbound.toMutableMap()
            fields["outbounds"] = JsonArray(kept)
            if (outbound.text("default") in drop) fields.remove("default")
            JsonObject(fields)
        }
        return Stripped(JsonObject(root.toMutableMap().apply { this["outbounds"] = JsonArray(outbounds) }), removed)
    }

    /** true — группа осталась без участников после [strip]. */
    fun hasEmptyGroup(root: JsonObject): Boolean = (root["outbounds"] as? JsonArray).orEmpty().any { element ->
        val outbound = element as? JsonObject ?: return@any false
        val type = outbound.text("type")
        (type == "selector" || type == "urltest") && (outbound["outbounds"] as? JsonArray)?.isEmpty() == true
    }

    private fun hostOf(hostPort: String): String {
        val trimmed = hostPort.trim()
        if (trimmed.startsWith("[")) return trimmed.substringAfter('[').substringBefore(']')
        return trimmed.substringBefore(':')
    }

    private fun needsTransportSecurity(rawHost: String): Boolean {
        val host = rawHost.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return true
        if (':' in host) return !isPrivateIpv6(host)
        IPV4.matchEntire(host)?.let { match ->
            val octets = match.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) return true
            return !isPrivateIpv4(octets)
        }
        if ('.' !in host) return false // имя без точки Xray считает частным
        return PRIVATE_DOMAIN_SUFFIXES.none { host == it || host.endsWith(".$it") }
    }

    private fun isPrivateIpv4(o: List<Int>): Boolean =
        o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)

    private fun isPrivateIpv6(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "::1") return true
        val first = lower.substringBefore(':')
        val head = first.toIntOrNull(16) ?: return false
        // fc00::/7 (уникальные локальные) и fe80::/10 (локальный канал)
        return (head and 0xFE00) == 0xFC00 || (head and 0xFFC0) == 0xFE80
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
