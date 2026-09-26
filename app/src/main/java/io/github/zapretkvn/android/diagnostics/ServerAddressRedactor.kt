package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Адреса VPN-серверов не выходят в текст, который пользователь видит, копирует
 * или отправляет в поддержку: вместо адреса — «<сервер ep-…>».
 *
 * Метка — хэш случайного id профиля и тега outbound, а не адреса: хэш IPv4
 * подбирается перебором за секунды. Та же метка стоит в `endpoint` контекста
 * диагностики, поэтому строки лога и экспорт связываются без адреса.
 *
 * Реестр только растёт за процесс: в буферах остаются строки про прошлые
 * сессии. Снимок (шаблон, словарь) заменяется целиком — маскировка идёт из
 * потоков ядра без блокировки.
 */
object ServerAddressRedactor {
    private class Snapshot(val pattern: Regex?, val refs: Map<String, String>)

    private val lock = Any()
    private val refs = LinkedHashMap<String, String>()

    @Volatile
    private var snapshot = Snapshot(null, emptyMap())

    fun serverRef(profileId: String, tag: String): String =
        "ep-${digest("$profileId|$tag").take(12)}"

    /** Все серверы профиля: `outbounds[].server` и `endpoints[].peers[].address`. */
    fun registerProfile(profileId: String, rawJson: String) {
        val root = runCatching { JsonConfig.parse(rawJson) as? JsonObject }.getOrNull() ?: return
        val pairs = mutableListOf<Pair<String, String>>()
        (root["outbounds"] as? JsonArray)?.forEach { element ->
            val outbound = element as? JsonObject ?: return@forEach
            val tag = outbound.string("tag") ?: return@forEach
            outbound.string("server")?.let { pairs += it to serverRef(profileId, tag) }
        }
        (root["endpoints"] as? JsonArray)?.forEach { element ->
            val endpoint = element as? JsonObject ?: return@forEach
            val tag = endpoint.string("tag") ?: return@forEach
            (endpoint["peers"] as? JsonArray)?.forEach { peer ->
                (peer as? JsonObject)?.string("address")?.let { pairs += it to serverRef(profileId, tag) }
            }
        }
        register(pairs)
    }

    /** IP, в которые приложение само разрешило имя уже известного сервера. */
    fun registerAliases(server: String, addresses: Iterable<String>) {
        val ref = snapshot.refs[normalize(server)] ?: return
        register(addresses.map { it to ref })
    }

    fun register(pairs: Iterable<Pair<String, String>>) {
        synchronized(lock) {
            var changed = false
            for ((address, ref) in pairs) {
                for (variant in variants(address)) {
                    if (variant !in refs) {
                        refs[variant] = ref
                        changed = true
                    }
                }
            }
            if (changed) rebuild()
        }
    }

    fun redact(text: String): String {
        val current = snapshot
        val pattern = current.pattern ?: return text
        if (text.isEmpty()) return text
        return pattern.replace(text) { match ->
            "<сервер ${current.refs[match.value.lowercase()] ?: "unknown"}>"
        }
    }

    internal fun clearForTests() {
        synchronized(lock) {
            refs.clear()
            snapshot = Snapshot(null, emptyMap())
        }
    }

    private fun rebuild() {
        val names = refs.keys.filter { ':' !in it }
        val ipv6 = refs.keys.filter { ':' in it }
        val parts = buildList {
            if (names.isNotEmpty()) add("$NAME_LEFT${trieRegex(names)}$NAME_RIGHT")
            if (ipv6.isNotEmpty()) add("$IPV6_LEFT${trieRegex(ipv6)}$IPV6_RIGHT")
        }
        snapshot = Snapshot(
            pattern = parts.takeIf { it.isNotEmpty() }
                ?.let { Regex(it.joinToString("|"), RegexOption.IGNORE_CASE) },
            refs = HashMap(refs),
        )
    }

    private fun variants(address: String): Set<String> {
        val host = normalize(address)
        if (host.length < 4 || host == "localhost" || !isPublic(host)) return emptySet()
        val result = linkedSetOf(host)
        if (!IPV4_LITERAL.matches(host) && ':' !in host) {
            // 185-109-21-120.sslip.io / 185.109.21.120.nip.io раскрывают IP,
            // а ядро потом печатает уже сам IP.
            EMBEDDED_IPV4.findAll(host).forEach { match ->
                val octets = match.groupValues.drop(1).map(String::toInt)
                if (octets.all { it <= 255 }) {
                    val embedded = octets.joinToString(".")
                    if (isPublic(embedded)) result += embedded
                }
            }
        }
        return result
    }

    private fun normalize(address: String): String = address
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .trimEnd('.')
        .lowercase()

    /** Доменные имена и публичные IP; локальные и частные адреса не скрываются. */
    private fun isPublic(host: String): Boolean {
        if (IPV4_LITERAL.matches(host)) {
            val octets = host.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.any { it > 255 }) return false
            val (a, b) = octets
            return !(
                a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168)
                )
        }
        if (':' in host) {
            if (!IPV6_LITERAL.matches(host)) return false
            // Литерал IPv6 разбирается без DNS-запроса.
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            val first = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
            return !(
                address.isLoopbackAddress || address.isAnyLocalAddress ||
                    address.isLinkLocalAddress || address.isMulticastAddress ||
                    (first and 0xfe) == 0xfc
                )
        }
        return true
    }

    /** Альтернация с общими префиксами: пул в сотни адресов без перебора каждого. */
    private fun trieRegex(words: Collection<String>): String {
        class Node {
            val children = sortedMapOf<Char, Node>()
            var terminal = false
        }
        val root = Node()
        for (word in words) {
            var node = root
            for (char in word) node = node.children.getOrPut(char) { Node() }
            node.terminal = true
        }
        fun build(node: Node): String {
            val branches = node.children.map { (char, child) -> Regex.escape(char.toString()) + build(child) }
            if (branches.isEmpty()) return ""
            val body = if (branches.size == 1) branches.single() else branches.joinToString("|", "(?:", ")")
            // Жадно: сначала самое длинное совпадение.
            return if (node.terminal) "(?:$body)?" else body
        }
        return build(root)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun digest(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    // «1.2.3.4» не совпадает внутри «11.2.3.45», «a.example.com» — внутри
    // «b.a.example.com»; «:порт» и точка в конце предложения границу не рвут.
    private const val NAME_LEFT = "(?<![0-9A-Za-z.-])"
    private const val NAME_RIGHT = "(?![0-9A-Za-z-]|\\.[0-9A-Za-z])"
    private const val IPV6_LEFT = "(?<![0-9A-Fa-f:])"
    private const val IPV6_RIGHT = "(?![0-9A-Fa-f:])"
    private val IPV4_LITERAL = Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")
    private val IPV6_LITERAL = Regex("[0-9a-f:.]+")
    private val EMBEDDED_IPV4 = Regex("(?<![0-9])(\\d{1,3})[.-](\\d{1,3})[.-](\\d{1,3})[.-](\\d{1,3})(?![0-9])")
}
