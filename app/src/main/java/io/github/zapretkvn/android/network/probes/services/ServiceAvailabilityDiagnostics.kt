package io.github.zapretkvn.android.network.probes.services

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Раздел `service_availability` отчёта диагностики. Только токены перечислений
 * и числа: ни адресов сервисов, ни IP, ни текста исключений, ни тегов серверов.
 */
internal object ServiceAvailabilityDiagnostics {
    fun toJson(state: ServiceAvailabilityState, nowEpochMillis: Long): JsonObject = buildJsonObject {
        put("running", state.running)
        put("services", buildJsonArray {
            state.items.forEach { (id, availability) ->
                add(
                    buildJsonObject {
                        put("service", id.token)
                        put("state", stateToken(availability))
                        val snapshot = availability.snapshot()
                        if (snapshot == null) {
                            put("headline", JsonNull)
                        } else {
                            put("headline", snapshot.headline.token)
                            put("route_hint", snapshot.route.token)
                            put("age_s", ((nowEpochMillis - snapshot.measuredAtEpochMillis) / 1_000L).coerceAtLeast(0L))
                            put("latency_bucket", bucket(snapshot.latencyMillis))
                            put("tunnel", pathJson(snapshot.tunnel))
                            put("direct", pathJson(snapshot.direct))
                        }
                    },
                )
            }
        })
        val run = state.lastRun
        if (run == null) {
            put("last_run", JsonNull)
        } else {
            put(
                "last_run",
                buildJsonObject {
                    put("trigger", run.trigger.token)
                    put("duration_ms", run.durationMillis)
                    put("requests_planned", run.requestsPlanned)
                    put("requests_done", run.requestsDone)
                    put("bytes", run.bytes)
                    put("byte_budget", ServiceProbeBudget.MAX_BYTES)
                    put("request_budget", ServiceProbeBudget.MAX_REQUESTS)
                },
            )
        }
    }

    private fun pathJson(path: PathResult): JsonObject = buildJsonObject {
        put("verdict", path.verdict.token)
        put("failure", path.failure?.token ?: "none")
        put("completed", path.completed)
        put("planned", path.planned)
    }

    private fun stateToken(availability: ServiceAvailability): String = when (availability) {
        ServiceAvailability.NotChecked -> "not_checked"
        is ServiceAvailability.Checking -> "checking"
        is ServiceAvailability.Result -> "result"
        is ServiceAvailability.Stale -> "stale"
    }

    fun bucket(millis: Int?): String = when {
        millis == null -> "none"
        millis < 150 -> "lt_150"
        millis < 400 -> "150_400"
        millis < 1_000 -> "400_1000"
        millis < 3_000 -> "1000_3000"
        else -> "ge_3000"
    }
}
