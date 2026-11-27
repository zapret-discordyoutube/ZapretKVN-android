package io.github.zapretkvn.android.config

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Аккаунт WARP, который сервис отдаёт в заголовке подписки (владелец 2026-11-27, `windows/docs/WARP_ACCOUNT_DELIVERY.md`).
 *
 * Провайдер, который блокирует API регистрации Cloudflare (и заодно наши серверы), оставляет приложение без аккаунта:
 * регистрироваться не через что. Сервис регистрирует аккаунты сам и присылает их в заголовке `zapret-warp-account`: base64 от
 * JSON с двумя файлами, которые читает ядро (`warp-entry-profile.json` и его `.masque`). Здесь payload проверяется теми же
 * правилами, что у загрузчиков ядра, и пишется в файлы, но только там, где аккаунта нет (рабочий аккаунт устройства не
 * заменяется, кроме payload с `"replace": true`, нового значения). Ничего из payload в журнал не идёт: там закрытые ключи и
 * токены устройств.
 */
object WarpAccountDelivery {
    const val HEADER = "zapret-warp-account"
    private const val SCHEMA = 1
    private const val MAX_HEADER_BYTES = 32 * 1024
    const val ACCOUNT_FILE = "warp-entry-profile.json"
    private const val DELIVERED_SUFFIX = ".delivered"

    /** status: none (заголовка нет), invalid, kept (аккаунты уже есть), written. */
    data class Outcome(val status: String, val detail: String = "", val wroteWireGuard: Boolean = false, val wroteMasque: Boolean = false)

    private fun wireGuardOk(document: JsonObject): Boolean =
        document["private_key"]?.jsonPrimitive?.content?.isNotEmpty() == true &&
            (document["peers"] as? JsonArray)?.isNotEmpty() == true &&
            (document["interface"] as? JsonObject)?.get("addresses")?.let { it as? JsonObject }
                ?.get("v4")?.jsonPrimitive?.content?.isNotEmpty() == true

    private fun masqueOk(document: JsonObject): Boolean =
        listOf("private_key", "endpoint_pub_key", "ipv4").all { document[it]?.jsonPrimitive?.content?.isNotEmpty() == true }

    /** @param headerValue значение заголовка из ответа подписки сервиса или null, когда заголовка нет. */
    fun apply(headerValue: String?, directory: File): Outcome {
        val value = headerValue?.trim() ?: return Outcome("none")
        if (value.isEmpty()) return Outcome("none")
        if (value.length > MAX_HEADER_BYTES) return Outcome("invalid", "the header is too large")
        val payload = runCatching {
            Json.parseToJsonElement(String(Base64.getDecoder().decode(value), Charsets.UTF_8)).jsonObject
        }.getOrElse { return Outcome("invalid", "the header is not base64 of JSON") }
        if (payload["v"]?.jsonPrimitive?.content?.toIntOrNull() != SCHEMA) return Outcome("invalid", "unknown schema")
        val wireGuard = payload["wireguard"] as? JsonObject
        val masque = payload["masque"] as? JsonObject
        if (wireGuard == null || masque == null || !wireGuardOk(wireGuard) || !masqueOk(masque)) {
            return Outcome("invalid", "the payload lacks an account")
        }
        val account = File(directory, ACCOUNT_FILE)
        val masqueFile = WarpMasqueAccount.fileFor(account)
        val delivered = File(account.path + DELIVERED_SUFFIX)
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        val replaceAsked = runCatching { payload["replace"]?.jsonPrimitive?.boolean == true }.getOrDefault(false)
        val replace = replaceAsked && runCatching { delivered.readText().trim() }.getOrDefault("") != digest
        var wroteWireGuard = false
        var wroteMasque = false
        if (replace || !WarpAccountRegistration.accountFileReady(account)) {
            writeAtomically(account, wireGuard)
            wroteWireGuard = true
        }
        if (replace || !WarpMasqueAccount.ready(masqueFile)) {
            writeAtomically(masqueFile, masque)
            wroteMasque = true
        }
        if (!wroteWireGuard && !wroteMasque) return Outcome("kept")
        // an edge remembered for the old account says nothing about the new one
        File(account.path + ".edge").delete()
        delivered.writeText(digest, Charsets.US_ASCII)
        return Outcome("written", "", wroteWireGuard, wroteMasque)
    }

    private fun writeAtomically(file: File, document: JsonObject) {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".warp-delivered-", null, file.parentFile)
        try {
            temporary.writeText(Json.encodeToString(JsonObject.serializer(), document))
            if (!temporary.renameTo(file)) error("cannot keep the account file")
        } finally {
            temporary.delete()
        }
    }
}
