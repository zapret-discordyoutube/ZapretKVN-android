package io.github.zapretkvn.android.network.probes.services

/** Сервис, доступность которого показывает карточка Главной. */
enum class ServiceId(val token: String, val title: String) {
    YouTube("youtube", "YouTube"),
    ChatGpt("chatgpt", "ChatGPT"),
    Gemini("gemini", "Gemini"),
    Instagram("instagram", "Instagram"),
    Discord("discord", "Discord"),
    Telegram("telegram", "Telegram"),
    Claude("claude", "Claude"),
    X("x", "X"),
}

enum class ProbeMethod { Head, Get }

/** Основной адрес решает, открывается ли сервис; побочный — «работает по-настоящему». */
enum class EndpointRole { Primary, Secondary }

/**
 * Признак «сервис не работает из региона узла»: ответ с одним из [statuses]
 * читается (не больше 4 КБ), и если в нём есть один из [markers] — это отказ
 * самого сервиса, а не провайдера. Маркеры в нижнем регистре.
 */
data class GeoRule(val statuses: Set<Int>, val markers: List<String>)

/**
 * Один HTTPS-запрос проверки. [anyStatus] — засчитывается любой ответ сервера,
 * важен сам TLS до нужного имени (SNI), а не код.
 */
data class ProbeEndpoint(
    val host: String,
    val path: String = "/",
    val method: ProbeMethod = ProbeMethod.Head,
    val role: EndpointRole = EndpointRole.Primary,
    val anyStatus: Boolean = false,
    val geo: GeoRule? = null,
) {
    /** Один и тот же запрос из двух сервисов выполняется один раз. */
    val key: String get() = "${method.name}:$host$path"
}

/** Запрос только на установку TCP-соединения: для Telegram, у которого нет HTTPS-аналога MTProto. */
data class TcpEndpoint(val ip: String, val port: Int)

data class ServiceDefinition(
    val id: ServiceId,
    val endpoints: List<ProbeEndpoint>,
    val tcp: List<TcpEndpoint> = emptyList(),
    val enabledByDefault: Boolean = true,
) {
    val primary: ProbeEndpoint get() = endpoints.first { it.role == EndpointRole.Primary }
}

/**
 * Единственный источник целей проверки. Здесь только публичные константы
 * сервисов: ни один адрес не берётся из профиля, поэтому проверка не может
 * обратиться к серверу пользователя. Неудачный адрес — правка данных, а не
 * логики.
 */
object ServiceCatalog {
    /** Публичный адрес Telegram DC2: проверка только открывает и закрывает TCP-соединение. */
    private val TELEGRAM_DC = TcpEndpoint(ip = "149.154.167.51", port = 443)

    val all: List<ServiceDefinition> = listOf(
        ServiceDefinition(
            ServiceId.YouTube,
            listOf(
                ProbeEndpoint("www.youtube.com", "/generate_204", ProbeMethod.Get),
                ProbeEndpoint(
                    "redirector.googlevideo.com",
                    role = EndpointRole.Secondary,
                    anyStatus = true,
                ),
            ),
        ),
        ServiceDefinition(
            ServiceId.ChatGpt,
            listOf(
                ProbeEndpoint("chatgpt.com"),
                // Без ключа: 401 — сервис доступен, 403 с маркером — страна узла не поддерживается.
                ProbeEndpoint(
                    "api.openai.com",
                    "/v1/models",
                    ProbeMethod.Get,
                    EndpointRole.Secondary,
                    anyStatus = true,
                    geo = GeoRule(setOf(403, 451), listOf("unsupported_country_region_territory")),
                ),
            ),
        ),
        ServiceDefinition(
            ServiceId.Gemini,
            listOf(
                ProbeEndpoint("gemini.google.com"),
                // 403 без ключа — регион поддерживается; 400 FAILED_PRECONDITION — нет.
                ProbeEndpoint(
                    "generativelanguage.googleapis.com",
                    "/v1beta/models",
                    ProbeMethod.Get,
                    EndpointRole.Secondary,
                    anyStatus = true,
                    geo = GeoRule(setOf(400, 403, 451), listOf("user location is not supported")),
                ),
            ),
        ),
        ServiceDefinition(
            ServiceId.Instagram,
            listOf(
                ProbeEndpoint("www.instagram.com"),
                ProbeEndpoint("static.cdninstagram.com", role = EndpointRole.Secondary, anyStatus = true),
            ),
        ),
        ServiceDefinition(
            ServiceId.Discord,
            listOf(
                ProbeEndpoint("discord.com", "/api/v9/gateway", ProbeMethod.Get),
                ProbeEndpoint("gateway.discord.gg", role = EndpointRole.Secondary, anyStatus = true),
            ),
        ),
        ServiceDefinition(
            ServiceId.Telegram,
            listOf(ProbeEndpoint("t.me")),
            tcp = listOf(TELEGRAM_DC),
        ),
        ServiceDefinition(
            ServiceId.Claude,
            listOf(
                ProbeEndpoint("claude.ai"),
                ProbeEndpoint(
                    "api.anthropic.com",
                    method = ProbeMethod.Get,
                    role = EndpointRole.Secondary,
                    anyStatus = true,
                    geo = GeoRule(setOf(403, 451), listOf("request not allowed")),
                ),
            ),
            enabledByDefault = false,
        ),
        ServiceDefinition(
            ServiceId.X,
            listOf(
                ProbeEndpoint("x.com"),
                ProbeEndpoint("abs.twimg.com", role = EndpointRole.Secondary, anyStatus = true),
            ),
            enabledByDefault = false,
        ),
    )

    val defaults: List<ServiceDefinition> get() = all.filter { it.enabledByDefault }

    /** Сервисы, которые проверяются, пока пользователь не выбрал свой набор. */
    val defaultIds: Set<ServiceId> get() = defaults.mapTo(linkedSetOf()) { it.id }

    /**
     * Определения выбранных сервисов в порядке каталога. Пустой выбор (или набор
     * неизвестных сервисов) не оставляет проверку без целей: берутся [defaults].
     * Чем меньше сервисов выбрано, тем меньше запросов в одном запуске.
     */
    fun resolve(ids: Set<ServiceId>): List<ServiceDefinition> =
        all.filter { it.id in ids }.ifEmpty { defaults }

    fun definition(id: ServiceId): ServiceDefinition? = all.firstOrNull { it.id == id }

    /** Все имена и адреса, к которым проверка вообще может обратиться. */
    val allTargets: Set<String> by lazy {
        buildSet {
            all.forEach { service ->
                service.endpoints.forEach { add(it.host) }
                service.tcp.forEach { add(it.ip) }
            }
        }
    }
}

/**
 * Выбор сервисов для проверки в виде, пригодном для хранения. В настройках лежат
 * токены [ServiceId.token]; отсутствие записи означает набор по умолчанию, поэтому
 * чтение не требует миграции.
 */
object ServiceSelection {
    /** `null` (записи нет) и мусорный набор дают [ServiceCatalog.defaultIds]. */
    fun parse(tokens: Set<String>?): Set<ServiceId> {
        if (tokens == null) return ServiceCatalog.defaultIds
        val known = ServiceId.entries.filter { it.token in tokens }
        return if (known.isEmpty()) ServiceCatalog.defaultIds else known.toCollection(linkedSetOf())
    }

    fun tokens(ids: Set<ServiceId>): Set<String> = ids.mapTo(linkedSetOf(), ServiceId::token)

    /** Включить или выключить один сервис; последний включённый выключить нельзя. */
    fun toggle(current: Set<ServiceId>, id: ServiceId, enabled: Boolean): Set<ServiceId> {
        val next = if (enabled) current + id else current - id
        return if (next.isEmpty()) current else next
    }
}
