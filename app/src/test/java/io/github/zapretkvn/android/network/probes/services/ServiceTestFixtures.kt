package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind

internal fun http(
    status: Int = 200,
    millis: Int = 40,
    body: String? = null,
    bytes: Int = 2_000,
) = TransportResult.Http(status, millis, body, bytes)

internal fun failure(kind: HttpsProbeFailureKind = HttpsProbeFailureKind.Reset) =
    TransportResult.Failure(kind, 1_024)

internal fun path(
    verdict: ServiceVerdict,
    latency: Int? = null,
    failure: HttpsProbeFailureKind? = null,
) = PathResult(verdict, latency, failure, completed = 1, planned = 1)

internal fun snapshot(
    service: ServiceId = ServiceId.YouTube,
    headline: Headline = Headline.ViaVpn,
    measuredAt: Long = 1_000L,
    network: String = "wifi-1",
    latency: Int? = 80,
) = ServiceSnapshot(
    service = service,
    tunnel = path(ServiceVerdict.Ok, latency),
    direct = path(ServiceVerdict.Blocked, null, HttpsProbeFailureKind.Reset),
    route = RouteHint.Unknown,
    headline = headline,
    latencyMillis = latency,
    measuredAtEpochMillis = measuredAt,
    networkIdentity = network,
    generation = 1L,
)

/** Транспорт-заглушка: ответ решает [handler], вызовы записываются. */
internal class FakeTransport(
    private val handler: suspend (ProbeTarget) -> TransportResult,
) : ServiceProbeTransport {
    val calls = java.util.concurrent.CopyOnWriteArrayList<ProbeTarget>()

    override suspend fun https(endpoint: ProbeEndpoint): TransportResult {
        val target = ProbeTarget.Https(endpoint)
        calls += target
        return handler(target)
    }

    override suspend fun tcp(target: TcpEndpoint): TransportResult {
        val probe = ProbeTarget.Tcp(target)
        calls += probe
        return handler(probe)
    }

    val hosts: List<String>
        get() = calls.map {
            when (it) {
                is ProbeTarget.Https -> it.endpoint.host
                is ProbeTarget.Tcp -> it.endpoint.ip
            }
        }
}

internal class FakeTunnel(override val transport: ServiceProbeTransport) : TunnelPath {
    var closed = false
    override fun close() {
        closed = true
    }
}
