package io.github.zapretkvn.android.profiles

import java.net.URLDecoder

/**
 * Ключ NaiveProxy, который продаёт VPN-бот (владелец грамматики — `vpnbot_contracts/naive_link.py`, подтверждено его сессией
 * 2026-11-27):
 *
 *     naive+https://<client_id>:<secret>@<host>:<port>[?insecure-concurrency=<n>]#<name>
 *
 * - схема строго `naive+https`, пробелов нигде нет;
 * - `client_id` — `[A-Za-z0-9._-]{1,128}`, `secret` — `[A-Za-z0-9_-]{22,128}`, без процентного кодирования;
 * - хост — доменное имя в нижнем регистре, не IP (сервер отвечает TLS только на своё имя);
 * - порт по умолчанию 443; путь пустой или `/`;
 * - в query допустим только `insecure-concurrency` (бот выдаёт 1..4, приложение принимает 1..99 и предупреждает выше 4:
 *   ключ исполняется так, как выдан);
 * - имя после `#` — UTF-8 с процентным кодированием.
 *
 * Сообщения об ошибках никогда не содержат secret.
 */
class NaiveKeyException(message: String) : IllegalArgumentException(message)

data class NaiveKey(
    val clientId: String,
    val secret: String,
    val host: String,
    val port: Int,
    val concurrency: Int,
    val name: String,
) {
    /** Что получает родной клиент в `--proxy`: без query и имени. */
    val proxyUrl: String get() = "https://$clientId:$secret@$host:$port"

    val concurrencyWarning: String?
        get() = if (concurrency > CONCURRENCY_WARN_ABOVE) {
            "В ключе NaiveProxy указано соединений: $concurrency. Разработчики naive не советуют больше " +
                "$CONCURRENCY_WARN_ABOVE: так трафик легче заметить."
        } else {
            null
        }

    companion object {
        const val SCHEME = "naive+https"
        const val CONCURRENCY_MAX = 99
        const val CONCURRENCY_WARN_ABOVE = 4

        private val CLIENT_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val SECRET = Regex("^[A-Za-z0-9_-]{22,128}$")
        // Не меньше двух меток, последняя начинается с буквы (так IP-адрес не подходит никогда).
        private val HOST = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$")

        fun looksLikeKey(text: String): Boolean = text.startsWith("$SCHEME://")

        fun parse(line: String): NaiveKey {
            if (line.any(Char::isWhitespace)) {
                throw NaiveKeyException("В ключе NaiveProxy не должно быть пробелов и переводов строки.")
            }
            if (!line.startsWith("$SCHEME://")) throw NaiveKeyException("Ключ NaiveProxy начинается с naive+https://.")
            val body = line.removePrefix("$SCHEME://")
            val fragmentAt = body.indexOf('#')
            val rawName = if (fragmentAt >= 0) body.substring(fragmentAt + 1) else ""
            val withoutFragment = if (fragmentAt >= 0) body.substring(0, fragmentAt) else body
            val queryAt = withoutFragment.indexOf('?')
            val rawQuery = if (queryAt >= 0) withoutFragment.substring(queryAt + 1) else ""
            val authorityAndPath = if (queryAt >= 0) withoutFragment.substring(0, queryAt) else withoutFragment
            val pathAt = authorityAndPath.indexOf('/')
            val authority = if (pathAt >= 0) authorityAndPath.substring(0, pathAt) else authorityAndPath
            val path = if (pathAt >= 0) authorityAndPath.substring(pathAt) else ""
            if (path != "" && path != "/") throw NaiveKeyException("В ключе NaiveProxy не должно быть пути.")
            val at = authority.lastIndexOf('@')
            if (at < 0) throw NaiveKeyException("В ключе NaiveProxy нет client_id и secret.")
            val userInfo = authority.substring(0, at)
            val hostPort = authority.substring(at + 1)
            val colon = userInfo.indexOf(':')
            val clientId = if (colon >= 0) userInfo.substring(0, colon) else userInfo
            val secret = if (colon >= 0) userInfo.substring(colon + 1) else ""
            if (colon < 0 || !CLIENT_ID.matches(clientId)) throw NaiveKeyException("В ключе NaiveProxy неверный client_id.")
            if (!SECRET.matches(secret)) throw NaiveKeyException("В ключе NaiveProxy неверный secret.")
            val portAt = hostPort.lastIndexOf(':')
            val host = if (portAt >= 0) hostPort.substring(0, portAt) else hostPort
            if (host.isEmpty() || host.length > 253 || !HOST.matches(host)) {
                throw NaiveKeyException("В ключе NaiveProxy адрес сервера должен быть доменным именем в нижнем регистре.")
            }
            val port = if (portAt >= 0) {
                hostPort.substring(portAt + 1).takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 5 }?.toInt()
                    ?: throw NaiveKeyException("В ключе NaiveProxy неверный порт.")
            } else {
                443
            }
            if (port !in 1..65535) throw NaiveKeyException("В ключе NaiveProxy порт вне диапазона 1-65535.")
            var concurrency = 1
            if (rawQuery.isNotEmpty()) {
                val pairs = rawQuery.split('&')
                if (pairs.size != 1 || pairs[0].substringBefore('=') != "insecure-concurrency") {
                    throw NaiveKeyException("В ключе NaiveProxy допустим только параметр insecure-concurrency.")
                }
                val value = pairs[0].substringAfter('=', "")
                val parsed = value.takeIf { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } }?.toInt()
                if (parsed == null || parsed !in 1..CONCURRENCY_MAX) {
                    throw NaiveKeyException("insecure-concurrency в ключе NaiveProxy должен быть от 1 до $CONCURRENCY_MAX.")
                }
                concurrency = parsed
            }
            val name = runCatching { URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8") }.getOrDefault("")
            if (name.isBlank() || name.length > 200 || name.any { it.code < 32 || it.code == 127 }) {
                throw NaiveKeyException("В ключе NaiveProxy нет имени после #.")
            }
            return NaiveKey(clientId, secret, host, port, concurrency, name)
        }
    }
}
