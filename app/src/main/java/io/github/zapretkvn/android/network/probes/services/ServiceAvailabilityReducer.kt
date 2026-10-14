package io.github.zapretkvn.android.network.probes.services

/**
 * Переходы состояния карточки, как у LatencyProbeReducer: пакет результатов
 * принимается, только если совпали номер запуска и сеть. Всё в памяти: что
 * пользователь проверял, нигде не сохраняется.
 */
internal object ServiceAvailabilityReducer {
    data class BeginResult(val state: ServiceAvailabilityState, val started: Boolean)

    fun begin(
        state: ServiceAvailabilityState,
        requestId: Long,
        networkIdentity: String,
        services: List<ServiceId>,
        trigger: ServiceCheckTrigger,
    ): BeginResult {
        if (state.running) return BeginResult(state, started = false)
        val items = services.associateWith { id ->
            ServiceAvailability.Checking(
                state.items[id]?.snapshot(),
                previousStale = state.items[id] is ServiceAvailability.Stale,
            )
        }
        return BeginResult(
            state.copy(
                requestId = requestId,
                running = true,
                networkIdentity = networkIdentity,
                items = items,
                trigger = trigger,
                noNetwork = false,
            ),
            started = true,
        )
    }

    fun publishBatch(
        state: ServiceAvailabilityState,
        requestId: Long,
        networkIdentity: String,
        batch: Map<ServiceId, ServiceSnapshot>,
    ): ServiceAvailabilityState {
        if (!state.matches(requestId, networkIdentity)) return state
        return state.copy(
            items = state.items.mapValues { (id, current) ->
                val snapshot = batch[id]
                // Снимок «не проверено» (всё пропущено) не затирает прежний результат.
                if (snapshot == null || snapshot.headline == Headline.NotChecked) current
                else ServiceAvailability.Result(snapshot)
            },
        )
    }

    fun complete(
        state: ServiceAvailabilityState,
        requestId: Long,
        networkIdentity: String,
        summary: ServiceRunSummary,
    ): ServiceAvailabilityState {
        if (!state.matches(requestId, networkIdentity)) return state
        return state.copy(
            running = false,
            items = state.items.mapValues { (_, current) -> current.settled(staleWhenOld = true) },
            lastRun = summary,
        )
    }

    fun cancel(
        state: ServiceAvailabilityState,
        requestId: Long,
        networkIdentity: String,
    ): ServiceAvailabilityState {
        if (!state.matches(requestId, networkIdentity)) return state
        return state.copy(
            running = false,
            items = state.items.mapValues { (_, current) -> current.settled(staleWhenOld = false) },
        )
    }

    /** Проверке без VPN нечем проверять: физической сети нет. Прежние результаты остаются как были. */
    fun noNetwork(state: ServiceAvailabilityState): ServiceAvailabilityState =
        if (state.running) state else state.copy(noNetwork = true)

    /** Сеть, сервер или профиль сменились: прежние выводы устарели, запуск не идёт. */
    fun markStale(state: ServiceAvailabilityState): ServiceAvailabilityState = state.copy(
        running = false,
        noNetwork = false,
        items = state.items.mapValues { (_, current) ->
            when (current) {
                ServiceAvailability.NotChecked -> current
                is ServiceAvailability.Checking ->
                    current.previous?.let(ServiceAvailability::Stale) ?: ServiceAvailability.NotChecked
                is ServiceAvailability.Result -> ServiceAvailability.Stale(current.snapshot)
                is ServiceAvailability.Stale -> current
            }
        },
    )

    private fun ServiceAvailabilityState.matches(requestId: Long, networkIdentity: String): Boolean =
        running && this.requestId == requestId && this.networkIdentity == networkIdentity

    /**
     * Строка, которой так и не пришёл результат, возвращается к прежнему
     * значению: после обычного завершения прежний результат старше текущего
     * запуска, поэтому помечается устаревшим.
     */
    private fun ServiceAvailability.settled(staleWhenOld: Boolean): ServiceAvailability = when (this) {
        is ServiceAvailability.Checking -> when (val last = previous) {
            null -> ServiceAvailability.NotChecked
            else -> if (staleWhenOld || previousStale) {
                ServiceAvailability.Stale(last)
            } else {
                ServiceAvailability.Result(last)
            }
        }
        else -> this
    }
}
