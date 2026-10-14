package io.github.zapretkvn.android.network.probes.services

import kotlin.random.Random

/** Жёсткие пределы одного запуска: проверка не должна быть заметна по объёму. */
internal object ServiceProbeBudget {
    const val MAX_REQUESTS = 40
    const val MAX_BYTES = 512 * 1024

    /** Оценка рукопожатия TLS новой связи; тело ответа считается отдельно. */
    const val HANDSHAKE_BYTES_ESTIMATE = 6 * 1024
    const val MAX_BODY_BYTES = 4 * 1024
    const val RUN_TIMEOUT_MILLIS = 25_000L
}

/** Кому нужен результат запроса: сервис и роль адреса в нём. */
internal data class ProbeConsumer(val service: ServiceId, val role: EndpointRole)

internal sealed interface ProbeTarget {
    data class Https(val endpoint: ProbeEndpoint) : ProbeTarget
    data class Tcp(val endpoint: TcpEndpoint) : ProbeTarget
}

/** Один реальный запрос; одинаковые запросы разных сервисов склеены в один. */
internal data class PlannedRequest(
    val path: PathKind,
    val target: ProbeTarget,
    val consumers: List<ProbeConsumer>,
) {
    val key: String get() = when (target) {
        is ProbeTarget.Https -> "${path.token}|${target.endpoint.key}"
        is ProbeTarget.Tcp -> "${path.token}|tcp:${target.endpoint.ip}:${target.endpoint.port}"
    }

    /** Имя или адрес, к которому пойдёт запрос. */
    val host: String get() = when (target) {
        is ProbeTarget.Https -> target.endpoint.host
        is ProbeTarget.Tcp -> target.endpoint.ip
    }

    val isPrimary: Boolean get() = consumers.any { it.role == EndpointRole.Primary }
}

internal data class ProbePlan(val requests: List<PlannedRequest>, val dropped: Int) {
    /** Все имена и адреса плана: сверяются с каталогом и с серверами профиля в тестах. */
    val hosts: Set<String> get() = requests.mapTo(linkedSetOf(), PlannedRequest::host)
}

/**
 * Составляет список запросов только из [ServiceDefinition]: профиль сюда не
 * передаётся вовсе, поэтому цель не может оказаться сервером пользователя.
 * Туннель проверяет все адреса сервиса, прямой путь — только основной.
 */
internal object ServiceProbePlanner {
    fun plan(
        definitions: List<ServiceDefinition>,
        tunnel: Boolean,
        direct: Boolean,
        random: Random = Random.Default,
        maxRequests: Int = ServiceProbeBudget.MAX_REQUESTS,
    ): ProbePlan {
        val tunnelRequests = if (tunnel) build(PathKind.Tunnel, definitions, primaryOnly = false) else emptyList()
        val directRequests = if (direct) build(PathKind.Direct, definitions, primaryOnly = true) else emptyList()
        // Порядок внутри пути случайный, чтобы проверка не повторяла один и тот же узор; туннель идёт первым.
        val ordered = tunnelRequests.shuffled(random) + directRequests.shuffled(random)
        return ProbePlan(ordered.take(maxRequests), dropped = (ordered.size - maxRequests).coerceAtLeast(0))
    }

    private fun build(
        path: PathKind,
        definitions: List<ServiceDefinition>,
        primaryOnly: Boolean,
    ): List<PlannedRequest> {
        val byKey = LinkedHashMap<String, PlannedRequest>()
        fun add(target: ProbeTarget, consumer: ProbeConsumer) {
            val request = PlannedRequest(path, target, listOf(consumer))
            byKey.merge(request.key, request) { old, _ -> old.copy(consumers = old.consumers + consumer) }
        }
        definitions.forEach { service ->
            service.endpoints
                .filter { !primaryOnly || it.role == EndpointRole.Primary }
                .forEach { add(ProbeTarget.Https(it), ProbeConsumer(service.id, it.role)) }
            if (!primaryOnly) {
                service.tcp.forEach { add(ProbeTarget.Tcp(it), ProbeConsumer(service.id, EndpointRole.Secondary)) }
            }
        }
        return byKey.values.toList()
    }
}
