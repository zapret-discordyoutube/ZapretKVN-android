package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.clientreports.ServerIdHeader
import io.github.zapretkvn.clientreports.ServerRef
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Id каталога vpnbot для серверов подписок сервиса: из заголовка [ServerIdHeader.NAME] ответа подписки,
 * по тегу сервера (тег строится из того же имени фрагмента, [ManagedProfileFactory.serverTag]).
 * Хранится по SHA-256 адреса подписки (сам адрес с токеном во второй файл не копируется); новое
 * обновление подписки заменяет её карту целиком, ответ без заголовка карту удаляет.
 */
internal class ServerIdStore(private val file: File) {
    @Volatile
    private var byUrl: Map<String, Map<String, ServerRef>>? = null

    @Synchronized
    fun update(url: String, serviceReply: Boolean, headerValue: String?) {
        val key = urlHash(url)
        val parsed = if (serviceReply) ServerIdHeader.parse(headerValue) else emptyMap()
        val byTag = LinkedHashMap<String, ServerRef>()
        val repeated = HashSet<String>()
        parsed.forEach { (fragment, ref) ->
            val tag = ManagedProfileFactory.serverTag(fragment)
            // Два имени дали один тег: какой из серверов выбран, уже не понять — оба без отчёта.
            if (byTag.put(tag, ref) != null) repeated += tag
        }
        repeated.forEach(byTag::remove)
        val current = load()
        val next = if (byTag.isEmpty()) current - key else current + (key to byTag)
        if (next != current) save(next)
    }

    /** Сервер каталога для выбранного тега и типа outbound; null — не сервер сервиса или неоднозначно. */
    fun lookup(tag: String, outboundType: String?): ServerRef? {
        val products = ServerIdHeader.productsForOutboundType(outboundType)
        if (products.isEmpty()) return null
        val matches = load().values.mapNotNull { it[tag] }.filter { it.product in products }.toSet()
        return matches.singleOrNull()
    }

    fun mappedServers(): Int = load().values.sumOf { it.size }

    @Synchronized
    private fun load(): Map<String, Map<String, ServerRef>> {
        byUrl?.let { return it }
        val parsed = runCatching {
            val root = Json.parseToJsonElement(file.readText()) as JsonObject
            root.mapValues { (_, tags) ->
                (tags as JsonObject).mapNotNull { (tag, value) ->
                    val fields = value as? JsonObject ?: return@mapNotNull null
                    val id = (fields["server_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val product = (fields["product"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    tag to ServerRef(id, product)
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        byUrl = parsed
        return parsed
    }

    private fun save(value: Map<String, Map<String, ServerRef>>) {
        byUrl = value
        val json = buildJsonObject {
            value.forEach { (url, tags) ->
                put(
                    url,
                    buildJsonObject {
                        tags.forEach { (tag, ref) ->
                            put(
                                tag,
                                buildJsonObject {
                                    put("server_id", ref.serverId)
                                    put("product", ref.product)
                                },
                            )
                        }
                    },
                )
            }
        }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun urlHash(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray()).joinToString("") { "%02x".format(it) }
}
