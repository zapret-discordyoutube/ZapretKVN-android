package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Вход через Cloudflare WARP (владелец 2026-11-25): устройство -> WARP -> наш сервер -> интернет.
 *
 * Провайдер видит только адрес Cloudflare, а не адрес нашего сервера. Схема Hiddify «Proxy unblocker», доработанная.
 *
 * Всё внутри sing-box (прокладка, docs/ARCHITECTURE.md): WARP это endpoint `warp` ядра, он держит WireGuard на
 * официальном движке Amnezia (патч ядра 0005), аккаунт и MTU ему даёт патч 0022. Здесь только сборка конфигурации:
 * каждому серверу, который сам ходит на свой адрес, ставится `detour` на этот endpoint.
 *
 * Правило владельца: WARP только ВХОД, никогда не выход и не сервер на выбор. Его тег может стоять лишь в `detour`
 * серверов (и в собственном теге endpoint-а); [violations] проверяет готовый конфиг и не даёт запуститься, если он
 * попал в selector, в правило маршрута или в `route.final`. Прямой трафик («Россия напрямую») в WARP не входит:
 * через него идёт только соединение с нашим сервером.
 *
 * Сохранённый профиль не меняется: правка только в runtime-копии.
 */
object WarpEntryHop {
    /** Тег endpoint-а. Живёт только в runtime-копии, в списках серверов его нет. */
    const val TAG = "kvn-warp-entry"

    /**
     * Прямой outbound только для регистрации аккаунта WARP (`profile.detour`). Запрос к API Cloudflare идёт обычным HTTP-клиентом
     * Go, у которого на Android нет ни DNS (нет /etc/resolv.conf), ни защищённого сокета: без своего dialer-а запрос не
     * доходит (проверено на эмуляторе 2026-11-25: файл аккаунта не появился). Через этот outbound он идёт защищённым
     * сокетом и резолвится резолвером sing-box, мимо туннеля и мимо входа.
     */
    const val API_TAG = "kvn-warp-api"

    /**
     * MTU туннеля WARP. Ядро по умолчанию берёт 1280, а Hysteria2 шлёт датаграммы с 1252 байтами данных:
     * по IPv6 это 1300 байт пакета, и в 1280 они не помещаются. 1330 помещает их и остаётся ниже 1500 с
     * накладными расходами WireGuard (80 байт по IPv6).
     */
    const val MTU = 1330

    /**
     * Шум перед рукопожатием (владелец 2026-11-26: WARP должен работать там, где его блокируют, и «голого WireGuard» не
     * должно быть): несколько мусорных датаграмм и первый пакет в форме DNS-запроса к российскому домену, та же цепочка,
     * что строит маскировка WireGuard приложения (`dns_chain("yandex.ru")`). Шлёт только инициатор, поэтому обычный
     * WireGuard-сервер Cloudflare их игнорирует и отвечает на рукопожатие (проверено на настоящем WARP 2026-11-26: без шума,
     * с мусором и с мусором плюс этим I1 ответил везде). Только клиентские параметры Amnezia: H1-H4 и S1-S4 должны
     * совпадать с сервером, а у Cloudflare он обычный.
     */
    internal val NOISE = JsonObject(
        mapOf(
            "jc" to JsonPrimitive(3),
            "jmin" to JsonPrimitive(10),
            "jmax" to JsonPrimitive(30),
            "i1" to JsonPrimitive("<r 2><b 0x010000010000000000000679616e6465780272750000010001>"),
        ),
    )

    /** Типы без своего сервера: у них нечего вести через вход. `warp` это сам вход. */
    private val LOCAL_TYPES = setOf("direct", "block", "dns", "selector", "urltest", "warp")

    /** Endpoint-ы, которые сами ходят на адрес пира и потому тоже входят через WARP. */
    private val DIALING_ENDPOINT_TYPES = setOf("wireguard", "awg")

    data class Result(val outbounds: JsonArray?, val endpoints: JsonArray?, val chained: Int)

    /**
     * Ставит `detour` на WARP серверам и добавляет сам endpoint, если хоть один сервер проведён через него.
     *
     * - сервер с уже заданным `detour` не трогается: он не первое звено своей цепочки;
     * - имя сервера (не IP) резолвится [resolver] без WARP: иначе имя уйдёт в DNS ядра, а его последнее
     *   правило ведёт через те самые серверы, которые ждут адрес (Hiddify получал таймауты у всех CDN-серверов).
     *   Заданный пользователем `domain_resolver` не меняется;
     * - [profilePath] файл аккаунта WARP (в закрытом каталоге приложения), чтобы не регистрировать устройство
     *   при каждом запуске.
     */
    fun apply(
        outbounds: JsonArray?,
        endpoints: JsonArray?,
        profilePath: String,
        resolver: String?,
    ): Result {
        var chained = 0
        val chainedOutbounds = outbounds?.let { list ->
            JsonArray(
                list.filterNot { (it as? JsonObject)?.string("tag") == API_TAG }.map { element ->
                    val outbound = element as? JsonObject ?: return@map element
                    if (!dialsServer(outbound)) return@map element
                    chained++
                    chain(outbound, resolver)
                },
            )
        }
        val withoutOldHop = endpoints?.let { list ->
            JsonArray(list.filterNot { (it as? JsonObject)?.string("tag") == TAG })
        }
        val chainedEndpoints = withoutOldHop?.let { list ->
            JsonArray(
                list.map { element ->
                    val endpoint = element as? JsonObject ?: return@map element
                    if (!dialsPeer(endpoint)) return@map element
                    chained++
                    chain(endpoint, resolver)
                },
            )
        }
        if (chained == 0) return Result(chainedOutbounds ?: outbounds, withoutOldHop, 0)
        val api = JsonObject(
            buildMap {
                put("type", JsonPrimitive("direct"))
                put("tag", JsonPrimitive(API_TAG))
                if (resolver != null) put("domain_resolver", JsonPrimitive(resolver))
            },
        )
        val hop = JsonObject(
            mapOf(
                "type" to JsonPrimitive("warp"),
                "tag" to JsonPrimitive(TAG),
                "mtu" to JsonPrimitive(MTU),
                "amnezia" to NOISE,
                "profile" to JsonObject(
                    mapOf("cache_path" to JsonPrimitive(profilePath), "detour" to JsonPrimitive(API_TAG)),
                ),
            ),
        )
        val allEndpoints = JsonArray((chainedEndpoints?.toList() ?: emptyList()) + hop)
        return Result(JsonArray((chainedOutbounds?.toList() ?: emptyList()) + api), allEndpoints, chained)
    }

    private fun chain(target: JsonObject, resolver: String?): JsonObject {
        val updated = target.toMutableMap()
        updated["detour"] = JsonPrimitive(TAG)
        if (resolver != null && updated["domain_resolver"] == null && hasName(target)) {
            updated["domain_resolver"] = JsonPrimitive(resolver)
        }
        return JsonObject(updated)
    }

    private fun dialsServer(outbound: JsonObject): Boolean {
        val type = outbound.string("type") ?: return false
        if (type in LOCAL_TYPES || hasForeignDetour(outbound)) return false
        // sing-box outbound с `server`, либо Xray-модуль, который хранит ссылку в `uri`
        return outbound.containsKey("server") || outbound.containsKey("uri")
    }

    private fun dialsPeer(endpoint: JsonObject): Boolean =
        endpoint.string("type") in DIALING_ENDPOINT_TYPES && !hasForeignDetour(endpoint)

    /**
     * Свой `detour` (не наш вход) значит, что цель не первое звено своей цепочки. Наш тег здесь не чужой: так копия,
     * уже собранная прежним проходом, остаётся проведённой через вход, а не теряет его.
     */
    private fun hasForeignDetour(target: JsonObject): Boolean {
        val detour = target.string("detour") ?: return target.containsKey("detour")
        return detour != TAG
    }

    /** Есть ли у цели имя, которое надо резолвить (а не IP-литерал, которому резолвить нечего). */
    private fun hasName(target: JsonObject): Boolean {
        val server = target.string("server")
        if (server != null) return !isIpLiteral(server)
        if (target.containsKey("uri")) return true
        val peers = target["peers"] as? JsonArray ?: return false
        return peers.any { peer ->
            val address = (peer as? JsonObject)?.string("address")
            address != null && address.isNotBlank() && !isIpLiteral(address)
        }
    }

    /**
     * Места, где тег WARP стоит не как `detour` сервера (и не как собственный тег endpoint-а). Пустой список
     * значит, что вход нигде не стал выходом. Проверяется готовый конфиг, после всех проходов сборщика.
     */
    fun violations(root: JsonObject): List<String> = buildList {
        fun walk(element: JsonElement, path: String, allowedDetour: Boolean, allowedTag: Boolean) {
            when (element) {
                is JsonPrimitive -> if (element.isString && element.content == TAG) add(path)
                is JsonArray -> element.forEachIndexed { index, child ->
                    walk(child, "$path[$index]", allowedDetour, allowedTag)
                }
                is JsonObject -> element.forEach { (key, child) ->
                    val childPath = "$path.$key"
                    val isOwnTag = allowedTag && key == "tag" && child is JsonPrimitive
                    val isDetour = allowedDetour && key == "detour" && child is JsonPrimitive
                    if (isOwnTag || isDetour) return@forEach
                    walk(child, childPath, allowedDetour, allowedTag)
                }
            }
        }
        for ((key, value) in root) {
            when (key) {
                // outbounds[]: `detour` разрешён; `tag` самого входа в outbounds стоять не должен
                "outbounds" -> walk(value, key, allowedDetour = true, allowedTag = false)
                // endpoints[]: и detour серверов, и собственный тег endpoint-а `warp`
                "endpoints" -> walk(value, key, allowedDetour = true, allowedTag = true)
                // dns.servers[].detour разрешён (запрос имени нашего сервера входит через WARP)
                "dns" -> walk(value, key, allowedDetour = true, allowedTag = false)
                else -> walk(value, key, allowedDetour = false, allowedTag = false)
            }
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        if (host.contains(':')) return host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
