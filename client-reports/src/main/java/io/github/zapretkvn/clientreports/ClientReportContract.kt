package io.github.zapretkvn.clientreports

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Контракт отчёта о подключении (vpnbot `client_reports/contract.py`, schema 2 с 19.11.2026; решения
 * владельца 15–19.11.2026). Один отчёт — одна попытка подключения к одному серверу: сервер каталога
 * (id и продукт) или чужой сервер ([ForeignServer], только по кнопке), исход, шаг лестницы, на котором
 * попытка оборвалась, длительность, версия приложения и подробности [AttemptDetails] (адрес и порт
 * сервера, транспорт, тип сети, настройки, версии ядер). Ни аккаунта, ни ключа: сеть ставит сам приёмник
 * (прямо — он же хранит адрес отправителя и его /24 или /48 — или по токену из `GET /v1/network`).
 * Любое поле вне контракта отклоняет весь пакет, поэтому всё проверяется здесь.
 */
object ClientReportContract {
    const val SCHEMA = 2
    const val MAX_BATCH = 50
    const val MAX_ELAPSED_MS = 600_000L
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Продукты каталога vpnbot (`protocol_ports/<product>/catalogue.py`). */
    val PRODUCTS = setOf("awg", "hysteria2", "mtproto", "vless", "wireguard")

    /** Лестница шагов клиента (`vpnbot_contracts.observer_probe.TARGET_STEPS`). */
    val STEPS = listOf("resolve", "connect", "handshake", "auth", "tunnel", "request")

    const val SENT_DIRECT = "direct"
    const val SENT_TUNNEL = "tunnel"

    // Те же выражения, что у приёмника: отчёт, который он отклонил бы, не ставится в очередь.
    private val INSTALL_ID = Regex("^[0-9a-f]{32}$")
    private val REPORT_ID = Regex("^[0-9a-f]{32}$")
    private val SERVER_ID = Regex("^[a-z0-9_-]{1,64}$")
    private val CODE = Regex("^[a-z][a-z0-9_]{0,47}$")
    private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")
    private val NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val NETWORK_TOKEN = Regex("^[A-Za-z0-9_.-]{1,256}$")
    private val SHORT = Regex("^[a-z0-9]{1,16}$")
    private val SETTING = Regex("^[a-z0-9_]{1,24}$")
    private val VERSION_VALUE = Regex("^[0-9A-Za-z.+_-]{1,32}$")
    private val HOST_NAME = Regex("^[A-Za-z0-9.-]{1,253}$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]{2,45}$")

    val NETWORK_TYPES = setOf("wifi", "cellular", "ethernet", "other")
    val MODES = setOf("tun", "proxy")
    val VERSION_KEYS = setOf("os", "xray", "hysteria", "singbox", "amnezia")
    const val MAX_FOREIGN_NAME = 64

    /** IP-литерал без зоны: так его примет приёмник (`server_ip`, `foreign.host`). */
    fun isIpLiteral(value: String): Boolean =
        IPV4.matches(value) || (':' in value && IPV6.matches(value) && value.count { it == ':' } in 2..7)

    fun isForeignHost(value: String): Boolean = isIpLiteral(value) || HOST_NAME.matches(value) && value.any(Char::isLetter)

    fun isInstallId(value: String): Boolean = INSTALL_ID.matches(value)

    /** 32 lowercase hex characters from [java.security.SecureRandom]. */
    fun newReportId(): String {
        val bytes = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    fun isServerId(value: String): Boolean = SERVER_ID.matches(value)
    fun isNetworkToken(value: String): Boolean = NETWORK_TOKEN.matches(value)
    fun isAppIdentity(app: AppIdentity): Boolean =
        NAME.matches(app.name) && VERSION.matches(app.version) && NAME.matches(app.platform)

    fun isValid(record: AttemptRecord): Boolean {
        // Ровно одно из двух: сервер каталога или чужой сервер.
        val foreign = record.foreign
        val ours = isServerId(record.serverId) && record.product in PRODUCTS
        if (foreign == null && !ours) return false
        if (foreign != null && (record.serverId.isNotEmpty() || record.product.isNotEmpty() || !isValid(foreign))) return false
        if (record.details != record.details.cleaned()) return false
        if (record.elapsedMs !in 0..MAX_ELAPSED_MS || record.attemptedAtMillis <= 0) return false
        if (record.detail.isNotEmpty() && !CODE.matches(record.detail)) return false
        if (record.networkToken.isNotEmpty() && !isNetworkToken(record.networkToken)) return false
        if (record.reportId.isNotEmpty() && !REPORT_ID.matches(record.reportId)) return false
        return when (record.outcome) {
            ReportOutcome.Failed -> record.failedStep in STEPS
            ReportOutcome.Connected, ReportOutcome.Cancelled -> record.failedStep.isEmpty()
        }
    }

    fun isValid(foreign: ForeignServer): Boolean =
        isForeignHost(foreign.host) && SHORT.matches(foreign.protocol) &&
            foreign.name.codePointCount(0, foreign.name.length) <= MAX_FOREIGN_NAME &&
            foreign.name.codePoints().noneMatch(::isControlOrFormat)

    internal fun isShort(value: String) = SHORT.matches(value)
    internal fun isSetting(value: String) = SETTING.matches(value)
    internal fun isVersionValue(value: String) = VERSION_VALUE.matches(value)

    /**
     * Управляющие и форматирующие символы Unicode (и одинокие половинки суррогатных пар): приёмник
     * отклоняет имя с ними. Считается по кодовым точкам: флаг страны в имени — пара, она остаётся.
     */
    fun isControlOrFormat(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
            type == Character.SURROGATE.toInt() || type == Character.UNASSIGNED.toInt()
    }

    /** Отчёт ещё свидетельство: не старше недели (приёмник старше не принимает). */
    fun isFresh(record: AttemptRecord, nowMillis: Long): Boolean =
        nowMillis - record.attemptedAtMillis < MAX_AGE_MS - FRESHNESS_MARGIN_MS &&
            record.attemptedAtMillis - nowMillis < CLOCK_AHEAD_MS

    /** Запас к сроку недели: пакет идёт по сети, часы приёмника могут уйти вперёд. */
    private const val FRESHNESS_MARGIN_MS = 60L * 60 * 1000
    private const val CLOCK_AHEAD_MS = 5L * 60 * 1000
}

enum class ReportOutcome(val code: String) {
    Connected("connected"),
    Failed("failed"),
    Cancelled("cancelled"),
    ;

    companion object {
        fun of(code: String): ReportOutcome? = entries.firstOrNull { it.code == code }
    }
}

/** Приложение, как его называет отчёт: `zapret-kvn`, версия, `android`/`windows`. */
data class AppIdentity(val name: String, val version: String, val platform: String)

/** Сервер каталога vpnbot, названный подпиской (заголовок `zapret-server-ids`). */
data class ServerRef(val serverId: String, val product: String)

/**
 * Чужой сервер (не из подписок сервиса): только по кнопке «Отправить отчёт» (решение владельца
 * 19.11.2026). Адрес и порт — в общих [AttemptDetails.serverIp] и [AttemptDetails.port].
 */
data class ForeignServer(val host: String, val protocol: String, val name: String) {
    companion object {
        /** Имя без управляющих символов, не длиннее 64; хост и протокол в нижнем регистре. null — не годится. */
        fun of(host: String, protocol: String, name: String): ForeignServer? {
            val kept = name.codePoints().filter { !ClientReportContract.isControlOrFormat(it) }.toArray()
            val cleanName = String(kept, 0, kept.size).trim().let { text ->
                val points = text.codePoints().limit(ClientReportContract.MAX_FOREIGN_NAME.toLong()).toArray()
                String(points, 0, points.size)
            }
            val candidate = ForeignServer(host.trim().removePrefix("[").removeSuffix("]").lowercase(), protocol.lowercase(), cleanName)
            return candidate.takeIf(ClientReportContract::isValid)
        }
    }
}

/** Настройки приложения в отчёте; null — неизвестно (поле не пишется). */
data class ReportSettings(
    val mode: String? = null,
    val dns: String? = null,
    val masking: String? = null,
    val quicBlocked: Boolean? = null,
    val ipv6: Boolean? = null,
) {
    fun cleaned(): ReportSettings? = ReportSettings(
        mode = mode?.takeIf { it in ClientReportContract.MODES },
        dns = dns?.takeIf(ClientReportContract::isSetting),
        masking = masking?.takeIf(ClientReportContract::isSetting),
        quicBlocked = quicBlocked,
        ipv6 = ipv6,
    ).takeIf { it != ReportSettings() }
}

/**
 * Подробности попытки (schema 2, всё необязательно): адрес, к которому подключались, порт, транспорт,
 * защита, тип сети, настройки и версии ядер. Неверное значение не отклоняет отчёт — оно выпадает
 * ([cleaned]): потерять отчёт из-за одной версии хуже, чем не знать её.
 */
data class AttemptDetails(
    val serverIp: String? = null,
    val port: Int? = null,
    val transport: String? = null,
    val security: String? = null,
    val networkType: String? = null,
    val settings: ReportSettings? = null,
    val versions: Map<String, String> = emptyMap(),
) {
    fun cleaned(): AttemptDetails = AttemptDetails(
        serverIp = serverIp?.trim()?.removePrefix("[")?.removeSuffix("]")?.takeIf(ClientReportContract::isIpLiteral),
        port = port?.takeIf { it in 1..65535 },
        transport = transport?.lowercase()?.takeIf(ClientReportContract::isShort),
        security = security?.lowercase()?.takeIf(ClientReportContract::isShort),
        networkType = networkType?.takeIf { it in ClientReportContract.NETWORK_TYPES },
        settings = settings?.cleaned(),
        versions = versions.filter { (key, value) -> key in ClientReportContract.VERSION_KEYS && ClientReportContract.isVersionValue(value) },
    )
}

/** Версии ядер в виде контракта (`^[0-9A-Za-z.+_-]{1,32}$`). */
object ReportVersions {
    private val PSEUDO = Regex("^(\\d+\\.\\d+\\.\\d+)-0\\.\\d{14}-([0-9a-f]{12})$")

    /** `app/v2.14.0` → `2.14.0`, `module@v3.1.2026` → `3.1.2026`, псевдоверсия Go → `база-коммит`. */
    fun short(value: String?): String? {
        var text = value?.trim()?.substringAfterLast('@')?.substringAfterLast('/')?.removePrefix("v") ?: return null
        PSEUDO.matchEntire(text)?.let { text = "${it.groupValues[1]}-${it.groupValues[2]}" }
        return text.take(32).takeIf { it.isNotEmpty() && ClientReportContract.isVersionValue(it) }
    }
}

/**
 * Попытка в очереди устройства. Путь отправки (`sent_via`) и install_id добавляются при отправке:
 * путь становится известен только тогда, а идентификатор установки не дублируется в каждой строке.
 * [networkToken] — токен сети, в которой шла попытка (пустой, если его не было).
 */
data class AttemptRecord(
    val serverId: String,
    val product: String,
    val outcome: ReportOutcome,
    val failedStep: String,
    val detail: String,
    val elapsedMs: Long,
    val attemptedAtMillis: Long,
    val networkToken: String,
    /** Чужой сервер вместо [serverId]/[product] (те тогда пустые). */
    val foreign: ForeignServer? = null,
    val details: AttemptDetails = AttemptDetails(),
    /**
     * One attempt, one id (vpnbot «report_id live» 2026-11-19): made once when the attempt is recorded and kept in
     * the queue, so a re-send after a lost reply is stored once. Empty for queue lines written before it.
     */
    val reportId: String = "",
)

object ClientReportJson {
    /** Тело `POST /v1/reports`: ровно поля контракта, ничего сверх. */
    fun batch(installId: String, app: AppIdentity, records: List<AttemptRecord>, sentVia: String): String {
        require(ClientReportContract.isInstallId(installId)) { "install_id" }
        require(ClientReportContract.isAppIdentity(app)) { "app" }
        require(sentVia == ClientReportContract.SENT_DIRECT || sentVia == ClientReportContract.SENT_TUNNEL)
        require(records.size in 1..ClientReportContract.MAX_BATCH) { "batch size" }
        return buildJsonObject {
            put(
                "reports",
                buildJsonArray {
                    records.forEach { record ->
                        require(ClientReportContract.isValid(record)) { "record" }
                        add(report(installId, app, record, sentVia))
                    }
                },
            )
        }.toString()
    }

    private fun report(installId: String, app: AppIdentity, record: AttemptRecord, sentVia: String): JsonObject =
        buildJsonObject {
            put("schema", ClientReportContract.SCHEMA)
            put("install_id", installId)
            put(
                "app",
                buildJsonObject {
                    put("name", app.name)
                    put("version", app.version)
                    put("platform", app.platform)
                },
            )
            val foreign = record.foreign
            if (foreign == null) {
                put("server_id", record.serverId)
                put("product", record.product)
            } else {
                put(
                    "foreign",
                    buildJsonObject {
                        put("host", foreign.host)
                        put("protocol", foreign.protocol)
                        if (foreign.name.isNotEmpty()) put("name", foreign.name)
                    },
                )
            }
            put("outcome", record.outcome.code)
            if (record.outcome == ReportOutcome.Failed) put("failed_step", record.failedStep)
            if (record.detail.isNotEmpty()) put("detail", record.detail)
            put("elapsed_ms", record.elapsedMs)
            put("attempted_at", isoUtc(record.attemptedAtMillis))
            put("sent_via", sentVia)
            if (record.networkToken.isNotEmpty()) put("network_token", record.networkToken)
            if (record.reportId.isNotEmpty()) put("report_id", record.reportId)
            details(record.details).forEach { (key, value) -> put(key, value) }
        }

    /** Необязательные поля schema 2: только известные значения. */
    private fun details(details: AttemptDetails): JsonObject = buildJsonObject {
        details.serverIp?.let { put("server_ip", it) }
        details.port?.let { put("port", it) }
        details.transport?.let { put("transport", it) }
        details.security?.let { put("security", it) }
        details.networkType?.let { put("network_type", it) }
        details.settings?.let { settings ->
            put(
                "settings",
                buildJsonObject {
                    settings.mode?.let { put("mode", it) }
                    settings.dns?.let { put("dns", it) }
                    settings.masking?.let { put("masking", it) }
                    settings.quicBlocked?.let { put("quic_blocked", it) }
                    settings.ipv6?.let { put("ipv6", it) }
                },
            )
        }
        if (details.versions.isNotEmpty()) {
            put("versions", buildJsonObject { details.versions.toSortedMap().forEach { (key, value) -> put(key, value) } })
        }
    }

    /** `2026-11-19T12:34:56Z`: приёмник читает ISO-8601 с поясом, секунд достаточно. */
    fun isoUtc(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    /** Строка очереди на диске (свой короткий формат, не тело запроса). */
    fun queueLine(record: AttemptRecord): String = buildJsonObject {
        put("v", QUEUE_VERSION)
        put("s", record.serverId)
        put("p", record.product)
        put("o", record.outcome.code)
        put("f", record.failedStep)
        put("d", record.detail)
        put("e", record.elapsedMs)
        put("t", record.attemptedAtMillis)
        put("n", record.networkToken)
        if (record.reportId.isNotEmpty()) put("r", record.reportId)
        record.foreign?.let { foreign ->
            put("g", buildJsonObject { put("h", foreign.host); put("p", foreign.protocol); put("m", foreign.name) })
        }
        details(record.details).takeIf { it.isNotEmpty() }?.let { put("x", it) }
    }.toString()

    fun parseQueueLine(line: String): AttemptRecord? = runCatching {
        val json = Json.parseToJsonElement(line) as JsonObject
        // Версия 1 — строки schema 1 до 19.11.2026: читаются как отчёты без подробностей.
        if (json["v"]?.jsonPrimitive?.intOrNull !in setOf(1, QUEUE_VERSION)) return null
        fun text(key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val foreign = (json["g"] as? JsonObject)?.let { g ->
            fun field(key: String) = (g[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            ForeignServer(field("h"), field("p"), field("m"))
        }
        AttemptRecord(
            serverId = text("s"),
            product = text("p"),
            outcome = ReportOutcome.of(text("o")) ?: return null,
            failedStep = text("f"),
            detail = text("d"),
            elapsedMs = json["e"]?.jsonPrimitive?.longOrNull ?: return null,
            attemptedAtMillis = json["t"]?.jsonPrimitive?.longOrNull ?: return null,
            networkToken = text("n"),
            foreign = foreign,
            details = (json["x"] as? JsonObject)?.let(::parseDetails) ?: AttemptDetails(),
            reportId = text("r"),
        ).takeIf(ClientReportContract::isValid)
    }.getOrNull()

    private fun parseDetails(x: JsonObject): AttemptDetails {
        fun text(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        val settings = (x["settings"] as? JsonObject)?.let {
            ReportSettings(text(it, "mode"), text(it, "dns"), text(it, "masking"), flag(it, "quic_blocked"), flag(it, "ipv6"))
        }
        val versions = (x["versions"] as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
        }?.toMap().orEmpty()
        return AttemptDetails(
            serverIp = text(x, "server_ip"),
            port = (x["port"] as? JsonPrimitive)?.intOrNull,
            transport = text(x, "transport"),
            security = text(x, "security"),
            networkType = text(x, "network_type"),
            settings = settings,
            versions = versions,
        ).cleaned()
    }

    private const val QUEUE_VERSION = 2
}
