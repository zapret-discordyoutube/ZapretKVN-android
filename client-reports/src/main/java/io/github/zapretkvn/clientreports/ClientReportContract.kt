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
 * Контракт отчёта о подключении (vpnbot `client_reports/contract.py`, schema 1; решения владельца
 * 15–19.11.2026). Один отчёт — одна попытка подключения к одному серверу каталога: id сервера,
 * продукт, исход, шаг лестницы, на котором попытка оборвалась, длительность, версия приложения.
 * Ни аккаунта, ни ключа, ни адреса: сеть ставит сам приёмник (прямо или по токену из
 * `GET /v1/network`). Любое поле вне контракта отклоняет весь пакет, поэтому всё проверяется здесь.
 */
object ClientReportContract {
    const val SCHEMA = 1
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
    private val SERVER_ID = Regex("^[a-z0-9_-]{1,64}$")
    private val CODE = Regex("^[a-z][a-z0-9_]{0,47}$")
    private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")
    private val NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val NETWORK_TOKEN = Regex("^[A-Za-z0-9_.-]{1,256}$")

    fun isInstallId(value: String): Boolean = INSTALL_ID.matches(value)
    fun isServerId(value: String): Boolean = SERVER_ID.matches(value)
    fun isNetworkToken(value: String): Boolean = NETWORK_TOKEN.matches(value)
    fun isAppIdentity(app: AppIdentity): Boolean =
        NAME.matches(app.name) && VERSION.matches(app.version) && NAME.matches(app.platform)

    fun isValid(record: AttemptRecord): Boolean {
        if (!isServerId(record.serverId) || record.product !in PRODUCTS) return false
        if (record.elapsedMs !in 0..MAX_ELAPSED_MS || record.attemptedAtMillis <= 0) return false
        if (record.detail.isNotEmpty() && !CODE.matches(record.detail)) return false
        if (record.networkToken.isNotEmpty() && !isNetworkToken(record.networkToken)) return false
        return when (record.outcome) {
            ReportOutcome.Failed -> record.failedStep in STEPS
            ReportOutcome.Connected, ReportOutcome.Cancelled -> record.failedStep.isEmpty()
        }
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
            put("server_id", record.serverId)
            put("product", record.product)
            put("outcome", record.outcome.code)
            if (record.outcome == ReportOutcome.Failed) put("failed_step", record.failedStep)
            if (record.detail.isNotEmpty()) put("detail", record.detail)
            put("elapsed_ms", record.elapsedMs)
            put("attempted_at", isoUtc(record.attemptedAtMillis))
            put("sent_via", sentVia)
            if (record.networkToken.isNotEmpty()) put("network_token", record.networkToken)
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
    }.toString()

    fun parseQueueLine(line: String): AttemptRecord? = runCatching {
        val json = Json.parseToJsonElement(line) as JsonObject
        if (json["v"]?.jsonPrimitive?.intOrNull != QUEUE_VERSION) return null
        fun text(key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        AttemptRecord(
            serverId = text("s"),
            product = text("p"),
            outcome = ReportOutcome.of(text("o")) ?: return null,
            failedStep = text("f"),
            detail = text("d"),
            elapsedMs = json["e"]?.jsonPrimitive?.longOrNull ?: return null,
            attemptedAtMillis = json["t"]?.jsonPrimitive?.longOrNull ?: return null,
            networkToken = text("n"),
        ).takeIf(ClientReportContract::isValid)
    }.getOrNull()

    private const val QUEUE_VERSION = 1
}
