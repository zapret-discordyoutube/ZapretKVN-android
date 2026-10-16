package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Отключённая проверка сертификата сервера (`tls.insecure`) без закреплённого
 * отпечатка позволяет любому посреднику, в том числе оборудованию провайдера,
 * выдать себя за VPN-сервер и читать трафик. Такой outbound не импортируется и не
 * запускается: одно правило для ссылок, подписок, сырого JSON и уже сохранённых
 * профилей.
 *
 * Отпечаток делает `insecure` безопасным — доверие ровно одному сертификату:
 * Hysteria2 — `certificate_sha256` или `pinSHA256` в исходной ссылке `uri`;
 * TLS-клиент sing-box — `tls.certificate_public_key_sha256`. VLESS с `uri` идёт
 * в Xray, который поле `tls` не читает (там проверка по `pcs`/`vcn` или обычная).
 */
object TlsVerificationPolicy {
    const val FAILURE_CODE = "CFG-120"

    const val IMPORT_MESSAGE =
        "Ключ отключает проверку сертификата сервера (allowInsecure/insecure). Так любой, " +
            "кто стоит между вами и сервером, может подменить сервер и читать трафик, " +
            "поэтому такие ключи не добавляются. Попросите у провайдера ключ с нормальным " +
            "сертификатом или с отпечатком сертификата (pinSHA256 для Hysteria2, pcs для VLESS)."

    const val RUNTIME_MESSAGE =
        "Сервер в этом профиле отключает проверку сертификата: его может подменить любой " +
            "посредник, поэтому подключение не запускается. Обновите подписку или попросите " +
            "у провайдера ключ с нормальным сертификатом."

    fun skippedServersWarning(count: Int): String =
        "Пропущены серверы, которые отключают проверку сертификата: $count. " +
            "Их может подменить посредник; остальные серверы добавлены."

    /** true — outbound отключает проверку сертификата без закреплённого отпечатка. */
    fun violates(outbound: JsonObject): Boolean {
        val tls = outbound["tls"] as? JsonObject ?: return false
        if (tls.flag("insecure") != true) return false
        val type = outbound.text("type")?.lowercase()
        if (type == "vless" && !outbound.text("uri").isNullOrBlank()) return false
        if (!outbound.text("certificate_sha256").isNullOrBlank()) return false
        if (tls.hasValues("certificate_public_key_sha256")) return false
        if (type == "hysteria2" && outbound.text("uri")?.let(::uriHasPin) == true) return false
        return true
    }

    /** Теги outbound/endpoint профиля, которые нарушают правило. */
    fun violatingTags(root: JsonObject): List<String> =
        listOf("outbounds", "endpoints").flatMap { section ->
            (root[section] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.takeIf(::violates)?.let { it.text("tag").orEmpty() }
            }
        }

    private fun uriHasPin(uri: String): Boolean {
        val query = uri.substringAfter('?', "").substringBefore('#')
        return query.split('&').any { pair ->
            val key = pair.substringBefore('=').lowercase()
            key == "pinsha256" && pair.substringAfter('=', "").isNotBlank()
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.hasValues(key: String): Boolean = when (val value: JsonElement? = this[key]) {
        is JsonArray -> value.any { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
        is JsonPrimitive -> !value.contentOrNull.isNullOrBlank()
        else -> false
    }
}
