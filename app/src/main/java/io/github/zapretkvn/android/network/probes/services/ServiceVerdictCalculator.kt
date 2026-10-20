package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind

/** Ответ транспорта на один запрос: без исключений и без адресов. */
internal sealed interface TransportResult {
    val bytes: Int

    /** [bodySample] — не больше 4 КБ в нижнем регистре, только если запрос его просил. */
    data class Http(
        val status: Int,
        val millis: Int,
        val bodySample: String?,
        override val bytes: Int,
    ) : TransportResult

    data class TcpOpen(val millis: Int, override val bytes: Int) : TransportResult
    data class Failure(val kind: HttpsProbeFailureKind, override val bytes: Int) : TransportResult
}

/** Чистые правила: ответ → итог запроса → вердикты → заголовок строки. */
internal object ServiceVerdictCalculator {
    fun classify(endpoint: ProbeEndpoint, result: TransportResult): PathOutcome = when (result) {
        is TransportResult.Failure -> PathOutcome.Blocked(result.kind)
        is TransportResult.TcpOpen -> PathOutcome.Ok(result.millis, status = 0)
        is TransportResult.Http -> classifyHttp(endpoint, result)
    }

    private fun classifyHttp(endpoint: ProbeEndpoint, response: TransportResult.Http): PathOutcome {
        val geo = endpoint.geo
        val status = response.status
        if (geo != null && status in geo.statuses) {
            val body = response.bodySample.orEmpty()
            if (geo.markers.any { it in body }) return PathOutcome.GeoRefused(response.millis, status)
        }
        // 451 без маркера всё равно отказ по закону/региону, а не сбой сети.
        if (status == 451) return PathOutcome.GeoRefused(response.millis, status)
        return when {
            endpoint.anyStatus -> PathOutcome.Ok(response.millis, status)
            // 403 от защиты (challenge) без маркера региона: TLS и HTTP работают.
            status in 100..499 -> PathOutcome.Ok(response.millis, status)
            else -> PathOutcome.Blocked(HttpsProbeFailureKind.HttpStatus)
        }
    }

    /** Итог пути: основной запрос решает, побочные понижают до Partial или дают отказ региона. */
    fun verdict(primary: PathOutcome?, secondary: List<PathOutcome>): ServiceVerdict {
        if (primary == null || primary is PathOutcome.Skipped) return ServiceVerdict.NotMeasured
        if (primary is PathOutcome.GeoRefused || secondary.any { it is PathOutcome.GeoRefused }) {
            return ServiceVerdict.GeoRefused
        }
        return when (primary) {
            is PathOutcome.Ok -> when {
                secondary.any { it is PathOutcome.Blocked } -> ServiceVerdict.Partial
                primary.millis >= ServiceQuality.SLOW_MILLIS -> ServiceVerdict.Slow
                else -> ServiceVerdict.Ok
            }
            is PathOutcome.Blocked -> when (primary.kind) {
                HttpsProbeFailureKind.Timeout,
                HttpsProbeFailureKind.Reset,
                HttpsProbeFailureKind.Tls,
                HttpsProbeFailureKind.Dns,
                -> ServiceVerdict.Blocked
                HttpsProbeFailureKind.HttpStatus,
                HttpsProbeFailureKind.Other,
                -> ServiceVerdict.Unreachable
            }
            else -> ServiceVerdict.NotMeasured
        }
    }

    private fun ServiceVerdict.opens() = this == ServiceVerdict.Ok || this == ServiceVerdict.Partial || this == ServiceVerdict.Slow

    /**
     * Что показать в строке. Туннель по правилам профиля — это то, что видит
     * приложение, поэтому он главный; «напрямую» уточняет, нужен ли VPN вообще.
     */
    fun headline(tunnel: ServiceVerdict, direct: ServiceVerdict, route: RouteHint): Headline {
        val directOpens = direct == ServiceVerdict.Ok
        return when {
            tunnel == ServiceVerdict.NotMeasured && direct == ServiceVerdict.NotMeasured -> Headline.NotChecked
            tunnel == ServiceVerdict.GeoRefused ->
                if (directOpens && route == RouteHint.Direct) Headline.Direct else Headline.GeoRefused
            tunnel == ServiceVerdict.Ok -> when {
                direct.opens() -> when (route) {
                    RouteHint.Direct -> Headline.Direct
                    RouteHint.Proxy -> Headline.ViaVpn
                    RouteHint.Unknown -> Headline.Both
                }
                direct == ServiceVerdict.NotMeasured -> when (route) {
                    RouteHint.Direct -> Headline.Direct
                    RouteHint.Proxy -> Headline.ViaVpn
                    RouteHint.Unknown -> Headline.Both
                }
                else -> Headline.ViaVpn
            }
            tunnel == ServiceVerdict.Partial ->
                if (directOpens && route == RouteHint.Direct) Headline.Direct else Headline.Partial
            tunnel == ServiceVerdict.Slow ->
                if (directOpens && route == RouteHint.Direct) Headline.Direct else Headline.Slow
            tunnel == ServiceVerdict.NotMeasured ->
                if (directOpens) Headline.Direct else Headline.Unavailable
            // Туннель не открыл сервис.
            else -> if (directOpens && route != RouteHint.Proxy) Headline.Direct else Headline.Unavailable
        }
    }

    /** Задержка для строки: путь, который виден в заголовке. */
    fun latency(headline: Headline, tunnel: PathResult, direct: PathResult): Int? = when (headline) {
        Headline.Direct -> direct.latencyMillis ?: tunnel.latencyMillis
        Headline.NotChecked -> null
        else -> tunnel.latencyMillis ?: direct.latencyMillis
    }
}
