package io.github.zapretkvn.android.network

import android.content.Context
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.networkbootstrap.UnderlyingNetworkMonitor

typealias PrivateDnsMode = io.github.zapretkvn.networkbootstrap.PrivateDnsMode
typealias UnderlyingNetworkState = io.github.zapretkvn.networkbootstrap.UnderlyingNetworkState

internal object PrivateDnsClassifier {
    fun classify(apiLevel: Int, active: Boolean, serverName: String?): PrivateDnsMode =
        io.github.zapretkvn.networkbootstrap.PrivateDnsClassifier.classify(
            apiLevel = apiLevel,
            active = active,
            serverName = serverName,
        )
}

/** App lifecycle adapter; network selection and snapshot consistency live in network-bootstrap. */
class DefaultNetworkMonitor(context: Context) : AutoCloseable {
    private val delegate = UnderlyingNetworkMonitor(context)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    val current: UnderlyingNetworkState
        get() = delegate.current

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            delegate.start()
            VpnRuntimeMetrics.callbackOpened()
        } catch (error: Throwable) {
            started.set(false)
            throw error
        }
    }

    fun observe(observer: (UnderlyingNetworkState) -> Unit): AutoCloseable = delegate.observe(observer)

    suspend fun awaitUnderlying(
        timeoutMillis: Long = 8_000,
        accept: (UnderlyingNetworkState) -> Boolean = { true },
    ): UnderlyingNetworkState = delegate.awaitUnderlying(timeoutMillis, accept)

    suspend fun awaitStableUnderlying(timeoutMillis: Long = 8_000): UnderlyingNetworkState =
        delegate.awaitStableUnderlying(timeoutMillis)

    suspend fun <T> runOnStableNetwork(
        maxNetworkChanges: Int = UnderlyingNetworkMonitor.DEFAULT_MAX_NETWORK_CHANGES,
        timeoutMillis: Long = UnderlyingNetworkMonitor.DEFAULT_NETWORK_TIMEOUT_MILLIS,
        accept: (UnderlyingNetworkState) -> Boolean = { true },
        operation: suspend (UnderlyingNetworkState) -> T,
    ): io.github.zapretkvn.networkbootstrap.StableNetworkResult<T> =
        delegate.runOnStableNetwork(
            maxNetworkChanges = maxNetworkChanges,
            timeoutMillis = timeoutMillis,
            accept = accept,
            operation = operation,
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        delegate.close()
        if (started.compareAndSet(true, false)) VpnRuntimeMetrics.callbackClosed()
    }
}
