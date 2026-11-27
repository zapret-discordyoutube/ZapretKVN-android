package io.github.zapretkvn.android.config

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Аккаунт MASQUE для входа через WARP, который приложение регистрирует само (владелец 2026-11-26: «WARP не работает на
 * мобильных данных»).
 *
 * Когда оператор обрывает WireGuard, ядро переходит на MASQUE ([WarpEntryHop], патчи ядра 0026 и 0027). Для него нужен
 * второй аккаунт: устройство и ключ ECDSA, записанный в API Cloudflare. Ядро умеет регистрировать его само, но тем же
 * одним запросом к API, который сеть сбрасывает или тормозит (диагностика 0.6.62: запрос ядра отменён через 7 с). Поэтому
 * приложение делает то же, что для аккаунта WireGuard ([WarpAccountRegistration]): те же пути, включая разрезанный ClientHello,
 * и пишет файл рядом с аккаунтом WireGuard (`<файл>.masque`, формат файла ядра, закрытый). Ядро находит его и API не зовёт.
 *
 * Два запроса: регистрация устройства (POST, одноразовый ключ WireGuard) и запись ключа MASQUE (PATCH, токен устройства).
 * Блокирующий вызов: только из фонового потока. В журнал идёт только путь и итог.
 */
object WarpMasqueAccount {
    private const val KEY_TYPE = "secp256r1"
    private const val TUNNEL_TYPE = "masque"
    /** Адрес конечной точки MASQUE по HTTP/2, как в ядре (`cloudflare.DefaultEndpointH2V4`). */
    private const val DEFAULT_H2_V4 = "162.159.198.2"

    /** Запрос к API: тот же канал, что у регистрации WireGuard. */
    fun interface Requester {
        fun send(route: WarpAccountRegistration.Route, method: String, path: String, token: String?, body: ByteArray, timeoutMs: Int): ByteArray
    }

    private val systemRequester = Requester { route, method, path, token, body, timeoutMs ->
        WarpAccountRegistration.request(route.target, method, path, token, body, route.fragmented, timeoutMs)
    }

    /** Файл аккаунта MASQUE рядом с файлом аккаунта WireGuard. */
    fun fileFor(wireGuardAccount: File): File = File(wireGuardAccount.path + ".masque")

    fun ready(file: File): Boolean = runCatching {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        listOf("private_key", "endpoint_pub_key", "ipv4").all { root[it]?.jsonPrimitive?.content?.isNotEmpty() == true }
    }.getOrDefault(false)

    @Volatile
    private var failedAt = 0L

    /** После неудачи пути пропускаются на десять минут: ядро справится со своим запросом, а подключение ждать не должно. */
    private const val RETRY_MS = 600_000L

    /**
     * Файл аккаунта MASQUE есть; если нет, он регистрируется по путям [WarpAccountRegistration.routes].
     * [budgetMs] это всё время вызова, каждому запросу достаётся не больше, чем осталось.
     */
    fun ensure(
        wireGuardAccount: File,
        addresses: () -> List<String> = { emptyList() },
        timeoutMs: Int = 8000,
        budgetMs: Int = 25_000,
        requester: Requester = systemRequester,
        viaVpn: Boolean = false,
    ): WarpAccountRegistration.Outcome {
        val file = fileFor(wireGuardAccount)
        if (ready(file)) return WarpAccountRegistration.Outcome(true, "cached", "account file is there")
        val started = System.nanoTime()
        fun took() = (System.nanoTime() - started) / 1_000_000
        val before = failedAt
        if (!viaVpn && before != 0L && started - before < RETRY_MS * 1_000_000L) {
            return WarpAccountRegistration.Outcome(false, "skipped", "the routes failed a moment ago", 0)
        }
        // Through the VPN (the connect that runs without the entry because the provider blocks the API): one plain route.
        val routes = if (viaVpn) listOf(WarpAccountRegistration.Route("vpn", null, false)) else WarpAccountRegistration.routes(addresses)

        fun <T> overRoutes(first: WarpAccountRegistration.Route?, step: (WarpAccountRegistration.Route, Int) -> T): Pair<WarpAccountRegistration.Route, T>? {
            val ordered = listOfNotNull(first) + routes.filter { it != first }
            for (route in ordered) {
                val remaining = budgetMs - took()
                if (remaining < 2_000) return null
                try {
                    return route to step(route, minOf(timeoutMs.toLong(), remaining / 2).toInt())
                } catch (_: Exception) {
                    continue
                }
            }
            return null
        }

        // 1. the device: a throwaway WireGuard key, the answer carries the id and the token
        val wgKey = WarpAccountRegistration.newKey().second
        val registered = overRoutes(null) { route, timeout ->
            val raw = requester.send(route, "POST", "/v0a4471/reg", null, WarpAccountRegistration.registrationBody(wgKey), timeout)
            Json.parseToJsonElement(String(raw)).jsonObject.also {
                check(it["id"]?.jsonPrimitive?.content?.isNotEmpty() == true && it["token"]?.jsonPrimitive?.content?.isNotEmpty() == true) {
                    "the answer holds no device"
                }
            }
        }
        if (registered == null) {
            if (!viaVpn) failedAt = System.nanoTime()
            return WarpAccountRegistration.Outcome(false, "none", "no route registered the device in ${budgetMs / 1000} s", took())
        }
        val device = registered.second
        val id = device.getValue("id").jsonPrimitive.content
        val token = device.getValue("token").jsonPrimitive.content

        // 2. the MASQUE key: the same route first (it just worked), then the others
        val keys = newKey()
        val enrolled = overRoutes(registered.first) { route, timeout ->
            val body = JsonObject(
                linkedMapOf(
                    "key" to JsonPrimitive(Base64.getEncoder().encodeToString(keys.publicSpki)),
                    "key_type" to JsonPrimitive(KEY_TYPE),
                    "tunnel_type" to JsonPrimitive(TUNNEL_TYPE),
                    "name" to JsonPrimitive("PC"),
                ),
            ).toString().toByteArray()
            Json.parseToJsonElement(String(requester.send(route, "PATCH", "/v0a4471/reg/$id", token, body, timeout))).jsonObject
        }
        if (enrolled == null) {
            if (!viaVpn) failedAt = System.nanoTime()
            return WarpAccountRegistration.Outcome(false, registered.first.name, "the MASQUE key was not accepted in ${budgetMs / 1000} s", took())
        }
        return try {
            write(file, keys.privateSec1, id, token, enrolled.second)
            WarpAccountRegistration.Outcome(true, enrolled.first.name, "registered", took())
        } catch (error: Exception) {
            WarpAccountRegistration.Outcome(false, enrolled.first.name, "${error.javaClass.simpleName}: ${error.message}".take(160), took())
        }
    }

    /** Только для тестов: забыть недавнюю неудачу. */
    internal fun forgetFailure() {
        failedAt = 0L
    }

    class Keys(val privateSec1: ByteArray, val publicSpki: ByteArray)

    /** Пара ключей P-256: закрытый в виде SEC1 (как читает ядро, Go `x509.ParseECPrivateKey`), открытый в виде PKIX (X.509 SPKI). */
    internal fun newKey(): Keys {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val private = pair.private as ECPrivateKey
        val public = pair.public as ECPublicKey
        return Keys(sec1(private.s, public.w.affineX, public.w.affineY), public.encoded)
    }

    private val PRIME256V1_OID = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

    /** SEC1 `ECPrivateKey` для P-256 (RFC 5915): версия 1, ключ, параметры кривой, открытая точка. Всегда 121 байт. */
    internal fun sec1(d: BigInteger, x: BigInteger, y: BigInteger): ByteArray {
        fun fixed(value: BigInteger): ByteArray {
            val raw = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            require(raw.size <= 32) { "a P-256 value is at most 32 bytes" }
            return ByteArray(32 - raw.size) + raw
        }
        val point = byteArrayOf(0x04) + fixed(x) + fixed(y)
        val content = byteArrayOf(0x02, 0x01, 0x01) +
            byteArrayOf(0x04, 0x20) + fixed(d) +
            byteArrayOf(0xA0.toByte(), 0x0A) + PRIME256V1_OID +
            byteArrayOf(0xA1.toByte(), 0x44, 0x03, 0x42, 0x00) + point
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    /** Ответ API на запись ключа -> файл, который читает ядро (`protocol/masque` `Config`). Пишется целиком, атомарно. */
    internal fun write(file: File, privateSec1: ByteArray, id: String, token: String, profile: JsonObject) {
        val config = profile["config"]?.jsonObject ?: error("the answer holds no account")
        val peer = config["peers"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("the answer holds no peer")
        val endpoint = peer["endpoint"]?.jsonObject ?: error("the answer holds no endpoint")
        val addresses = config["interface"]?.jsonObject?.get("addresses")?.jsonObject ?: error("the answer holds no address")
        val publicKey = peer["public_key"]?.jsonPrimitive?.content.orEmpty()
        val v4 = addresses["v4"]?.jsonPrimitive?.content.orEmpty()
        check(publicKey.isNotEmpty() && v4.isNotEmpty()) { "the answer holds no MASQUE account" }
        val document = JsonObject(
            linkedMapOf(
                "private_key" to JsonPrimitive(Base64.getEncoder().encodeToString(privateSec1)),
                "endpoint_v4" to JsonPrimitive(hostOf(endpoint["v4"]?.jsonPrimitive?.content.orEmpty())),
                "endpoint_v6" to JsonPrimitive(hostOf(endpoint["v6"]?.jsonPrimitive?.content.orEmpty())),
                "endpoint_h2_v4" to JsonPrimitive(DEFAULT_H2_V4),
                "endpoint_h2_v6" to JsonPrimitive(""),
                "endpoint_pub_key" to JsonPrimitive(publicKey),
                "license" to JsonPrimitive(profile["account"]?.jsonObject?.get("license")?.jsonPrimitive?.content.orEmpty()),
                "id" to JsonPrimitive(id),
                "access_token" to JsonPrimitive(token),
                "ipv4" to JsonPrimitive(v4),
                "ipv6" to JsonPrimitive(addresses["v6"]?.jsonPrimitive?.content.orEmpty()),
            ),
        )
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".masque-account-", null, file.parentFile)
        try {
            temporary.writeText(document.toString())
            if (!temporary.renameTo(file)) error("cannot keep the account file")
        } finally {
            temporary.delete()
        }
    }

    /** `162.159.198.2:0` -> `162.159.198.2`, `[2606:4700::1]:0` -> `2606:4700::1` (как ядро берёт адрес из `endpoint.v4`/`v6`). */
    internal fun hostOf(endpoint: String): String {
        val text = endpoint.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("[")) return text.substringAfter('[').substringBefore(']')
        return if (text.count { it == ':' } == 1) text.substringBefore(':') else text
    }
}
