package io.github.zapretkvn.networkbootstrap

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

enum class PrivateDnsMode {
    Off,
    Automatic,
    Strict,
}

object PrivateDnsClassifier {
    fun classify(apiLevel: Int, active: Boolean, serverName: String?): PrivateDnsMode = when {
        apiLevel < Build.VERSION_CODES.P -> PrivateDnsMode.Off
        !serverName.isNullOrBlank() -> PrivateDnsMode.Strict
        active -> PrivateDnsMode.Automatic
        else -> PrivateDnsMode.Off
    }
}

data class UnderlyingNetworkState(
    val network: Network? = null,
    val transport: String = "other",
    val interfaceName: String? = null,
    val interfaceIndex: Int = -1,
    val metered: Boolean = false,
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
    val privateDnsMode: PrivateDnsMode = PrivateDnsMode.Off,
    val privateDnsActive: Boolean = false,
    val privateDnsServerName: String? = null,
    val dnsServers: List<String> = emptyList(),
) {
    val identity: String?
        get() = network?.let { "${it.networkHandle}:${interfaceName.orEmpty()}" }
}

data class StableNetworkResult<T>(
    val network: UnderlyingNetworkState,
    val value: T,
)

private data class BootstrapNetworkKey(
    val identity: String?,
    val validated: Boolean,
    val captivePortal: Boolean,
    val privateDnsMode: PrivateDnsMode,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
    val dnsServers: List<String>,
)

private fun UnderlyingNetworkState.bootstrapKey() = BootstrapNetworkKey(
    identity = identity,
    validated = validated,
    captivePortal = captivePortal,
    privateDnsMode = privateDnsMode,
    privateDnsActive = privateDnsActive,
    privateDnsServerName = privateDnsServerName,
    dnsServers = dnsServers,
)

/**
 * Tracks a non-VPN network from ordered callback payloads. A network is published only after
 * both capabilities and link properties have been delivered for it.
 */
class UnderlyingNetworkMonitor(context: Context) : AutoCloseable {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(UnderlyingNetworkState) -> Unit>()
    private val candidates = ConcurrentHashMap<Network, CandidateParts>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    var current: UnderlyingNetworkState = UnderlyingNetworkState()
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            candidates.putIfAbsent(network, CandidateParts())
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            candidates.compute(network) { _, previous ->
                (previous ?: CandidateParts()).copy(capabilities = capabilities)
            }
            publishBestCandidate()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            candidates.compute(network) { _, previous ->
                (previous ?: CandidateParts()).copy(linkProperties = linkProperties)
            }
            publishBestCandidate()
        }

        override fun onLost(network: Network) {
            candidates.remove(network)
            publishBestCandidate()
        }
    }

    fun start() {
        check(!closed.get()) { "Network monitor is already closed." }
        if (!started.compareAndSet(false, true)) return
        try {
            registerCallback()
        } catch (error: Throwable) {
            started.set(false)
            throw error
        }
    }

    fun observe(observer: (UnderlyingNetworkState) -> Unit): AutoCloseable {
        check(!closed.get()) { "Network monitor is already closed." }
        observers += observer
        observer(current)
        return AutoCloseable { observers -= observer }
    }

    /**
     * Ждёт сеть, удовлетворяющую [accept]. Предикат позволяет вызывающему коду
     * требовать не просто наличие `Network`, а завершённую настройку линка:
     * во время Wi‑Fi ↔ cellular Android публикует кандидата раньше, чем тот
     * становится пригодным для bootstrap.
     */
    suspend fun awaitUnderlying(
        timeoutMillis: Long = DEFAULT_NETWORK_TIMEOUT_MILLIS,
        accept: (UnderlyingNetworkState) -> Boolean = ACCEPT_ANY,
    ): UnderlyingNetworkState =
        try {
            withTimeout(timeoutMillis) {
                current.takeIf { it.network != null && accept(it) }
                    ?: suspendCancellableCoroutine { continuation ->
                        var registration: AutoCloseable? = null
                        registration = observe { state ->
                            if (state.network != null && accept(state) && continuation.isActive) {
                                registration?.close()
                                continuation.resume(state)
                            }
                        }
                        if (!continuation.isActive) registration.close()
                        continuation.invokeOnCancellation { registration.close() }
                    }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw BootstrapFailureException(
                BootstrapFailureCode.NetworkUnavailable,
                technicalDetail = "timeout_ms=$timeoutMillis",
                cause = timeout,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }

    suspend fun awaitStableUnderlying(
        timeoutMillis: Long = DEFAULT_NETWORK_TIMEOUT_MILLIS,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        settleAttempts: Int = DEFAULT_SETTLE_ATTEMPTS,
        accept: (UnderlyingNetworkState) -> Boolean = ACCEPT_ANY,
    ): UnderlyingNetworkState {
        require(settleAttempts > 0)
        var candidate = awaitUnderlying(timeoutMillis, accept)
        repeat(settleAttempts) {
            delay(settleMillis)
            val latest = current
            if (latest.network == null || !accept(latest)) return@repeat
            if (latest == candidate) return latest
            candidate = latest
        }
        throw BootstrapFailureException(
            BootstrapFailureCode.NetworkChanged,
            technicalDetail = "settle_ms=$settleMillis,attempts=$settleAttempts," +
                "identity=${candidate.identity.orEmpty()}",
        )
    }

    suspend fun <T> runOnStableNetwork(
        maxNetworkChanges: Int = DEFAULT_MAX_NETWORK_CHANGES,
        timeoutMillis: Long = DEFAULT_NETWORK_TIMEOUT_MILLIS,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        settleAttempts: Int = DEFAULT_SETTLE_ATTEMPTS,
        accept: (UnderlyingNetworkState) -> Boolean = ACCEPT_ANY,
        operation: suspend (UnderlyingNetworkState) -> T,
    ): StableNetworkResult<T> {
        require(maxNetworkChanges >= 0)
        var state = awaitStableUnderlying(timeoutMillis, settleMillis, settleAttempts, accept)
        repeat(maxNetworkChanges + 1) { attempt ->
            val outcome = try {
                Result.success(operation(state))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            val latest = current
            when (
                NetworkTransitionPolicy.decide(
                    startedKey = state.bootstrapKey(),
                    currentKey = latest.bootstrapKey(),
                    attempt = attempt,
                    maxNetworkChanges = maxNetworkChanges,
                )
            ) {
                NetworkTransitionDecision.Accept ->
                    return StableNetworkResult(state, outcome.getOrThrow())
                NetworkTransitionDecision.Retry -> {
                    state = awaitStableUnderlying(timeoutMillis, settleMillis, settleAttempts, accept)
                    return@repeat
                }
                NetworkTransitionDecision.Fail -> throw BootstrapFailureException(
                    BootstrapFailureCode.NetworkChanged,
                    technicalDetail = "network_or_dns_policy_changed",
                    cause = outcome.exceptionOrNull(),
                )
            }
        }
        throw BootstrapFailureException(
            BootstrapFailureCode.NetworkChanged,
            technicalDetail = "identity=${state.identity.orEmpty()}",
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
        } else {
            connectivity.registerNetworkCallback(request, callback, handler)
        }
    }

    private fun publishBestCandidate() {
        if (closed.get()) return
        val ready = candidates.mapNotNull { (network, parts) -> parts.toCandidate(network) }
        val active = connectivity.activeNetwork
        val selected = ready.firstOrNull { it.state.network == active }
            ?: ready.maxWithOrNull(
                compareBy<Candidate> { it.score }
                    .thenBy { it.state.network?.networkHandle ?: Long.MIN_VALUE },
            )
        publish(selected?.state ?: UnderlyingNetworkState())
    }

    private fun CandidateParts.toCandidate(network: Network): Candidate? {
        val capabilities = capabilities ?: return null
        val linkProperties = linkProperties ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return null
        val interfaceName = linkProperties.interfaceName ?: return null
        val interfaceIndex = runCatching {
            NetworkInterface.getByName(interfaceName)?.index ?: -1
        }.getOrDefault(-1)
        if (interfaceIndex < 0) return null
        val privateDnsName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProperties.privateDnsServerName?.trim()?.takeIf(String::isNotEmpty)
        } else {
            null
        }
        val privateDnsActive =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && linkProperties.isPrivateDnsActive
        return Candidate(
            state = UnderlyingNetworkState(
                network = network,
                transport = transportName(capabilities),
                interfaceName = interfaceName,
                interfaceIndex = interfaceIndex,
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                privateDnsMode = PrivateDnsClassifier.classify(
                    apiLevel = Build.VERSION.SDK_INT,
                    active = privateDnsActive,
                    serverName = privateDnsName,
                ),
                privateDnsActive = privateDnsActive,
                privateDnsServerName = privateDnsName,
                dnsServers = linkProperties.dnsServers
                    .mapNotNull { it.hostAddress }
                    .distinct()
                    .sorted(),
            ),
            score = candidateScore(capabilities),
        )
    }

    private fun candidateScore(capabilities: NetworkCapabilities): Int {
        var score = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 1_000
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) score += 100
        score += when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 10
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) score += 1
        return score
    }

    private fun transportName(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
    }

    private fun publish(next: UnderlyingNetworkState) {
        if (next == current) return
        current = next
        observers.forEach { observer -> observer(next) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observers.clear()
        candidates.clear()
        if (started.compareAndSet(true, false)) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        current = UnderlyingNetworkState()
    }

    private data class CandidateParts(
        val capabilities: NetworkCapabilities? = null,
        val linkProperties: LinkProperties? = null,
    )

    private data class Candidate(
        val state: UnderlyingNetworkState,
        val score: Int,
    )

    companion object {
        const val DEFAULT_NETWORK_TIMEOUT_MILLIS = 8_000L
        const val DEFAULT_SETTLE_MILLIS = 250L
        const val DEFAULT_SETTLE_ATTEMPTS = 8
        const val DEFAULT_MAX_NETWORK_CHANGES = 1
        private val ACCEPT_ANY: (UnderlyingNetworkState) -> Boolean = { true }
    }
}
