package io.github.zapretkvn.android.config

import java.io.File
import java.net.InetAddress
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Другие адреса, порты и имена WARP, которые сервис присылает приложению (владелец 2026-11-27: «WARP должен пробиваться отовсюду»).
 *
 * Новые края Cloudflare, которые работают у какого-то провайдера, находятся без выпуска приложения, если сервис отдаёт их
 * сам: заголовок подписки `zapret-warp-edges` (base64 от JSON `{"v":1,"edges":[...]}`). Приложение пишет проверенный список
 * в файл рядом с аккаунтом (`warp-entry-profile.json.edges`), а сборка конфигурации кладёт его в опцию `extra_edges`
 * endpoint-а `warp` (патч ядра 0031): эти края пробуются сразу после двух первых адресов аккаунта. Правила проверки те же, что
 * у ядра: только литерал IP, известный транспорт, имя TLS из букв, цифр, точек и дефисов, не больше 16 краёв. Список из сети
 * не должен суметь сломать запуск, поэтому всё негодное молча отбрасывается.
 */
object WarpEdges {
    const val HEADER = "zapret-warp-edges"
    const val MAX_EDGES = 16
    private const val MAX_HEADER_BYTES = 32 * 1024
    private val TRANSPORTS = setOf("wireguard", "masque-http2", "masque-quic")
    private val SNI = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val IPV4 = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

    fun fileFor(account: File): File = File(account.path + ".edges")

    private fun literalIp(text: String): Boolean {
        if (IPV4.matches(text)) return text.split('.').all { it.toInt() in 0..255 }
        // an IPv6 literal has colons only: never let the resolver see a name here
        return ':' in text && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
            runCatching { InetAddress.getByName(text) }.isSuccess
    }

    /** The valid edges of a decoded list, at most [MAX_EDGES], duplicates dropped, each as the object the core reads. */
    fun valid(edges: JsonArray): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (element in edges) {
            if (out.size >= MAX_EDGES) break
            val edge = element as? JsonObject ?: continue
            val transport = edge["transport"]?.jsonPrimitive?.content ?: continue
            val address = edge["address"]?.jsonPrimitive?.content ?: continue
            if (transport !in TRANSPORTS || !literalIp(address)) continue
            val port = edge["port"]?.jsonPrimitive?.intOrNull ?: 0
            if (port !in 0..65535) continue
            val sni = edge["sni"]?.jsonPrimitive?.content.orEmpty()
            if (sni.isNotEmpty() && (transport == "wireguard" || !SNI.matches(sni))) continue
            val item = JsonObject(
                buildMap {
                    put("transport", JsonPrimitive(transport))
                    put("address", JsonPrimitive(address))
                    if (port != 0) put("port", JsonPrimitive(port))
                    if (sni.isNotEmpty()) put("sni", JsonPrimitive(sni))
                },
            )
            if (item !in out) out += item
        }
        return out
    }

    /** The edges kept for this account, or none: a missing or broken file is no list at all. */
    fun load(account: File): List<JsonObject> = runCatching {
        valid(Json.parseToJsonElement(fileFor(account).readText()).jsonObject["edges"]?.jsonArray ?: JsonArray(emptyList()))
    }.getOrDefault(emptyList())

    /** status: none (no header), invalid, same (already kept), written. An empty valid list removes the file. */
    fun apply(headerValue: String?, directory: File): WarpAccountDelivery.Outcome {
        val value = headerValue?.trim().orEmpty()
        if (value.isEmpty()) return WarpAccountDelivery.Outcome("none")
        if (value.length > MAX_HEADER_BYTES) return WarpAccountDelivery.Outcome("invalid", "the header is too large")
        val document = runCatching {
            Json.parseToJsonElement(String(Base64.getDecoder().decode(value), Charsets.UTF_8)).jsonObject
        }.getOrElse { return WarpAccountDelivery.Outcome("invalid", "the header is not base64 of JSON") }
        if (document["v"]?.jsonPrimitive?.intOrNull != 1) return WarpAccountDelivery.Outcome("invalid", "unknown schema")
        val edges = valid(document["edges"] as? JsonArray ?: return WarpAccountDelivery.Outcome("invalid", "no edges"))
        val file = fileFor(File(directory, WarpAccountDelivery.ACCOUNT_FILE))
        if (edges.isEmpty()) {
            val had = file.delete()
            return WarpAccountDelivery.Outcome(if (had) "written" else "same")
        }
        val text = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("v" to JsonPrimitive(1), "edges" to JsonArray(edges))))
        if (runCatching { file.readText() }.getOrNull() == text) return WarpAccountDelivery.Outcome("same")
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".warp-edges-", null, file.parentFile)
        try {
            temporary.writeText(text)
            if (!temporary.renameTo(file)) error("cannot keep the edges file")
        } finally {
            temporary.delete()
        }
        return WarpAccountDelivery.Outcome("written")
    }
}
