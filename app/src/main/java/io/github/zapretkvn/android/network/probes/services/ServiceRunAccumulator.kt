package io.github.zapretkvn.android.network.probes.services


/**
 * Собирает итоги запросов запуска в снимки сервисов. Чистый класс без
 * потоков: снимок сервиса готов, когда пришли все его запросы; недошедшие
 * запросы закрываются через [finish].
 */
internal class ServiceRunAccumulator(
    plan: ProbePlan,
    private val definitions: List<ServiceDefinition>,
    private val route: (ServiceId) -> RouteHint,
    private val networkIdentity: String,
    private val generation: Long,
    private val clock: () -> Long,
) {
    private class Pending(val request: PlannedRequest, var outcome: PathOutcome? = null)

    private val pending = plan.requests.map(::Pending)
    private val byService = HashMap<ServiceId, MutableList<Pair<ProbeConsumer, Pending>>>()
    private val published = HashSet<ServiceId>()

    init {
        pending.forEach { item ->
            item.request.consumers.forEach { consumer ->
                byService.getOrPut(consumer.service) { mutableListOf() } += consumer to item
            }
        }
    }

    val services: Set<ServiceId> get() = byService.keys

    /** Принять итог запроса; вернуть снимки сервисов, которые этим закрылись. */
    fun accept(request: PlannedRequest, outcome: PathOutcome): List<ServiceSnapshot> {
        pending.firstOrNull { it.request === request && it.outcome == null }?.outcome = outcome
        return ready()
    }

    /** Запуск кончился: всё, чего нет, считается пропущенным по [reason]. */
    fun finish(reason: SkipReason): List<ServiceSnapshot> {
        pending.filter { it.outcome == null }.forEach { it.outcome = PathOutcome.Skipped(reason) }
        return ready()
    }

    val completedRequests: Int get() = pending.count { it.outcome != null && it.outcome !is PathOutcome.Skipped }

    private fun ready(): List<ServiceSnapshot> = definitions.mapNotNull { definition ->
        val entries = byService[definition.id] ?: return@mapNotNull null
        if (definition.id in published || entries.any { it.second.outcome == null }) return@mapNotNull null
        published += definition.id
        snapshot(definition, entries)
    }

    private fun snapshot(
        definition: ServiceDefinition,
        entries: List<Pair<ProbeConsumer, Pending>>,
    ): ServiceSnapshot {
        fun path(kind: PathKind): PathResult {
            val own = entries.filter { it.second.request.path == kind }
            if (own.isEmpty()) return PathResult.NotMeasured
            val primary = own.firstOrNull { it.first.role == EndpointRole.Primary }?.second?.outcome
            val secondary = own.filter { it.first.role == EndpointRole.Secondary }.mapNotNull { it.second.outcome }
            val verdict = ServiceVerdictCalculator.verdict(primary, secondary)
            val failure = (listOfNotNull(primary) + secondary)
                .filterIsInstance<PathOutcome.Blocked>()
                .firstOrNull()?.kind
            val latency = when (primary) {
                is PathOutcome.Ok -> primary.millis
                is PathOutcome.GeoRefused -> primary.millis
                else -> null
            }
            return PathResult(
                verdict = verdict,
                latencyMillis = latency,
                failure = failure,
                completed = own.count { it.second.outcome !is PathOutcome.Skipped },
                planned = own.size,
            )
        }
        val tunnel = path(PathKind.Tunnel)
        val direct = path(PathKind.Direct)
        val hint = route(definition.id)
        val headline = ServiceVerdictCalculator.headline(tunnel.verdict, direct.verdict, hint)
        return ServiceSnapshot(
            service = definition.id,
            tunnel = tunnel,
            direct = direct,
            route = hint,
            headline = headline,
            latencyMillis = ServiceVerdictCalculator.latency(headline, tunnel, direct),
            measuredAtEpochMillis = clock(),
            networkIdentity = networkIdentity,
            generation = generation,
        )
    }
}
