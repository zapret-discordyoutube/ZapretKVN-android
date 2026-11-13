package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Домены, которые ВСЕГДА идут через выбранный VPN, даже если их адрес лежит в российской сети.
 *
 * Зачем (поле 2026-11-13, 0.5.45): режим «Россия напрямую» отправляет в direct всё, что `zapret-ru-ip` считает российским
 * адресом. Кеш-серверы YouTube (`rr1---sn-….googlevideo.com`) стоят и в сетях российских операторов, их адреса российские,
 * и видео уходило мимо VPN в канал, где YouTube блокируется: трафик «встал совсем». Решение выбирает не адрес, а имя
 * сервиса: правило по имени стоит выше пользовательских и выше RU-direct, поэтому такой домен не может уйти напрямую.
 * Приложение это видит и само: потоки идут через туннель, и детектор мёртвого кластера (`DeadVideoClusterTracker`) работает.
 *
 * Список короткий и явный; добавлять сюда только сервисы, которые блокируются и при российском адресе.
 * Правило живёт только в runtime-копии, сохранённый профиль не меняется.
 */
internal object ForcedProxyDomains {
    /** Суффиксы доменов (sing-box `domain_suffix`: сам домен и все поддомены). */
    val suffixes: List<String> = listOf(
        "googlevideo.com",
        "youtube.com",
        "youtu.be",
        "ytimg.com",
        "ggpht.com",
        "youtube-nocookie.com",
        "youtubei.googleapis.com",
    )

    /** Правило `route` на [proxyTag] по именам [suffixes]. */
    fun rule(proxyTag: String): JsonObject = buildJsonObject {
        put("domain_suffix", JsonArray(suffixes.map(::JsonPrimitive)))
        put("action", "route")
        put("outbound", proxyTag)
    }

    /** Это правило создали мы (его надо заменить, а не размножить при пересборке конфигурации). */
    fun isForcedRule(element: JsonElement): Boolean {
        val rule = element as? JsonObject ?: return false
        if ((rule["action"] as? JsonPrimitive)?.contentOrNull != "route") return false
        val names = (rule["domain_suffix"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return false
        return names == suffixes
    }

    /** Домен [host] входит в список (для тестов и журнала). */
    fun covers(host: String): Boolean {
        val name = host.trimEnd('.').lowercase()
        return suffixes.any { name == it || name.endsWith(".$it") }
    }
}
