package io.github.zapretkvn.android.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Выбор маршрута к Forgejo без промежуточных сообщений пользователю.
 *
 * - VPN включён: прямой запрос и запрос через VPN идут наперегонки (второй стартует через
 *   [hedgeMillis], чтобы не перезапускать VPN ради быстрого прямого ответа), побеждает первый успех.
 * - VPN выключен: сначала прямой, при маршрутной ошибке — временный VPN-маршрут, если это разрешено.
 * Маршрут, который сработал последним, пробуется первым.
 */
class UpdateRouter(
    private val clientFor: (UpdateRoute) -> UpdateHttpClient,
    private val vpnFallback: UpdateVpnFallback?,
    private val vpnActive: () -> Boolean,
    private val prefs: UpdatePrefs,
    private val hedgeMillis: Long = DEFAULT_HEDGE_MILLIS,
) {
    /** Результат и занятый маршрут. Пока аренда открыта, временный VPN-маршрут остаётся включённым. */
    class Lease<T>(
        val value: T,
        val route: UpdateRoute,
        val client: UpdateHttpClient,
        private val session: UpdateVpnSession?,
    ) {
        private var closed = false

        suspend fun close() {
            if (closed) return
            closed = true
            withContext(NonCancellable) { runCatching { session?.close() } }
        }
    }

    /** Порядок попыток: запомненный маршрут первым. */
    fun preferredOrder(): List<UpdateRoute> =
        if (prefs.lastRoute == UpdateRoute.Vpn) listOf(UpdateRoute.Vpn, UpdateRoute.Direct)
        else listOf(UpdateRoute.Direct, UpdateRoute.Vpn)

    /**
     * Малый запрос (проверка релиза). [allowStartingVpn] — можно ли поднимать VPN, который сейчас выключен:
     * фоновая задача этого не делает.
     */
    suspend fun <T> race(allowStartingVpn: Boolean, block: suspend (UpdateHttpClient) -> T): Lease<T> {
        val vpnUp = vpnActive()
        val routes = preferredOrder().filter { it == UpdateRoute.Direct || usableVpn(vpnUp, allowStartingVpn) }
        if (vpnUp && routes.size == 2) return raceTwo(routes[0], routes[1], block)
        return sequential(routes, block)
    }

    /** Большой запрос (загрузка): маршруты по очереди, без параллельной нагрузки. */
    suspend fun <T> sequential(routes: List<UpdateRoute>, block: suspend (UpdateHttpClient) -> T): Lease<T> {
        val failures = ArrayList<Pair<UpdateRoute, String>>()
        for (route in routes) {
            try {
                return attempt(route, block).also { prefs.lastRoute = route }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures += route to describe(error)
                if (!isRouteFailure(error)) throw error
            }
        }
        throw UpdateRoutesFailedException(failures)
    }

    /** Маршруты для загрузки после проверки: победивший в проверке первым, остальные запасными. */
    fun downloadOrder(winner: UpdateRoute?, allowStartingVpn: Boolean): List<UpdateRoute> {
        val vpnUp = vpnActive()
        val first = winner ?: preferredOrder().first()
        return listOf(first, if (first == UpdateRoute.Direct) UpdateRoute.Vpn else UpdateRoute.Direct)
            .filter { it == UpdateRoute.Direct || usableVpn(vpnUp, allowStartingVpn) }
    }

    private fun usableVpn(vpnUp: Boolean, allowStartingVpn: Boolean): Boolean =
        vpnFallback != null && (vpnUp || allowStartingVpn)

    private suspend fun <T> raceTwo(
        first: UpdateRoute,
        second: UpdateRoute,
        block: suspend (UpdateHttpClient) -> T,
    ): Lease<T> {
        // Отдельный scope: проигравшая попытка может ещё дозавершаться, но не держит вызывающего.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val results = Channel<Pair<UpdateRoute, Result<Lease<T>>>>(Channel.UNLIMITED)
        val firstFinished = CompletableDeferred<Boolean>()
        scope.launch {
            val outcome = runAttempt(first, block)
            firstFinished.complete(outcome.isSuccess)
            deliver(results, first, outcome)
        }
        scope.launch {
            // Первый успех отменяет вторую попытку до старта; провал или молчание дольше hedge запускают её.
            if (withTimeoutOrNull(hedgeMillis) { firstFinished.await() } == true) return@launch
            deliver(results, second, runAttempt(second, block))
        }
        val failures = ArrayList<Pair<UpdateRoute, String>>()
        try {
            repeat(2) {
                val (route, outcome) = results.receive()
                val lease = outcome.getOrNull()
                if (lease != null) {
                    prefs.lastRoute = route
                    return lease
                }
                val error = outcome.exceptionOrNull() ?: return@repeat
                failures += route to describe(error)
                if (!isRouteFailure(error)) throw error
            }
            throw UpdateRoutesFailedException(failures)
        } finally {
            scope.cancel()
            results.close()
            // Попытка, успевшая закончиться, но не принятая, обязана вернуть VPN в прежнее состояние.
            while (true) {
                val late = results.tryReceive().getOrNull() ?: break
                late.second.getOrNull()?.close()
            }
        }
    }

    private suspend fun <T> deliver(
        results: Channel<Pair<UpdateRoute, Result<Lease<T>>>>,
        route: UpdateRoute,
        outcome: Result<Lease<T>>,
    ) {
        if (results.trySend(route to outcome).isFailure) outcome.getOrNull()?.close()
    }

    private suspend fun <T> runAttempt(
        route: UpdateRoute,
        block: suspend (UpdateHttpClient) -> T,
    ): Result<Lease<T>> = try {
        Result.success(attempt(route, block))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun <T> attempt(route: UpdateRoute, block: suspend (UpdateHttpClient) -> T): Lease<T> {
        val client = clientFor(route)
        if (route == UpdateRoute.Direct) return Lease(block(client), route, client, null)
        val fallback = vpnFallback ?: throw UpdateException("VPN-маршрут недоступен.", retryViaVpn = true)
        val session = try {
            fallback.connect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw UpdateException(
                error.message ?: "VPN-маршрут недоступен.",
                cause = error,
                retryViaVpn = true,
            )
        }
        return try {
            Lease(block(client), route, client, session)
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { session.close() } }
            throw error
        }
    }

    private fun isRouteFailure(error: Throwable): Boolean =
        error !is UpdateException || error.retryViaVpn

    private fun describe(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(200)

    companion object {
        const val DEFAULT_HEDGE_MILLIS = 3_000L
    }
}
