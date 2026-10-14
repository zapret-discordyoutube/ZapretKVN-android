package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind

/** Путь запроса: через сеть VPN (по правилам профиля) или мимо него по физической сети. */
enum class PathKind(val token: String) {
    Tunnel("tunnel"),
    Direct("direct"),
}

enum class SkipReason(val token: String) {
    /** VPN-сеть Android недоступна или пропала во время проверки. */
    NoVpn("no_vpn"),

    /** Физическая сеть недоступна для привязки сокета (нет сети, lockdown). */
    DirectUnavailable("direct_unavailable"),

    /** Исчерпан бюджет запросов, байтов или времени запуска. */
    Budget("budget"),
}

/** Итог одного запроса. Только перечисления и числа: ни адресов, ни текста исключений. */
sealed interface PathOutcome {
    data class Ok(val millis: Int, val status: Int) : PathOutcome
    data class Blocked(val kind: HttpsProbeFailureKind) : PathOutcome

    /** Сервис сам отказал узлу по региону (маркер в ответе), а не провайдер. */
    data class GeoRefused(val millis: Int, val status: Int) : PathOutcome
    data class Skipped(val reason: SkipReason) : PathOutcome
}

/** Вердикт по пути (туннель или напрямую) для одного сервиса. */
enum class ServiceVerdict(val token: String) {
    Ok("ok"),
    Partial("partial"),
    GeoRefused("geo_refused"),
    Blocked("blocked"),
    Unreachable("unreachable"),
    NotMeasured("not_measured"),
}

enum class RouteHint(val token: String) { Proxy("proxy"), Direct("direct"), Unknown("unknown") }

/** Итоговая строка карточки: что пользователю важно знать о сервисе. */
enum class Headline(val token: String) {
    ViaVpn("via_vpn"),
    Direct("direct"),
    Both("both"),
    Partial("partial"),
    GeoRefused("geo_refused"),
    Unavailable("unavailable"),
    NotChecked("not_checked"),
}

data class PathResult(
    val verdict: ServiceVerdict,
    /** Задержка основного запроса: DNS + TCP + TLS + заголовки ответа. */
    val latencyMillis: Int?,
    val failure: HttpsProbeFailureKind?,
    val completed: Int,
    val planned: Int,
) {
    companion object {
        val NotMeasured = PathResult(ServiceVerdict.NotMeasured, null, null, 0, 0)
    }
}

data class ServiceSnapshot(
    val service: ServiceId,
    val tunnel: PathResult,
    val direct: PathResult,
    val route: RouteHint,
    val headline: Headline,
    val latencyMillis: Int?,
    val measuredAtEpochMillis: Long,
    val networkIdentity: String,
    val generation: Long,
)

/** Состояние одной строки карточки. */
sealed interface ServiceAvailability {
    data object NotChecked : ServiceAvailability
    data class Checking(
        val previous: ServiceSnapshot?,
        val previousStale: Boolean = false,
    ) : ServiceAvailability
    data class Result(val snapshot: ServiceSnapshot) : ServiceAvailability
    data class Stale(val snapshot: ServiceSnapshot) : ServiceAvailability
}

enum class ServiceCheckTrigger(val token: String) {
    Auto("auto"),
    Tap("tap"),
}

data class ServiceRunSummary(
    val trigger: ServiceCheckTrigger,
    val durationMillis: Long,
    val requestsPlanned: Int,
    val requestsDone: Int,
    val bytes: Int,
    val finishedAtElapsedMillis: Long,
)

data class ServiceAvailabilityState(
    val requestId: Long = 0,
    val running: Boolean = false,
    val networkIdentity: String? = null,
    val items: Map<ServiceId, ServiceAvailability> = emptyMap(),
    val trigger: ServiceCheckTrigger? = null,
    val lastRun: ServiceRunSummary? = null,
) {
    companion object {
        val Initial = ServiceAvailabilityState()
    }
}

fun ServiceAvailability.snapshot(): ServiceSnapshot? = when (this) {
    ServiceAvailability.NotChecked -> null
    is ServiceAvailability.Checking -> previous
    is ServiceAvailability.Result -> snapshot
    is ServiceAvailability.Stale -> snapshot
}
