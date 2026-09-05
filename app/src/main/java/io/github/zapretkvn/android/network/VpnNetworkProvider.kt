package io.github.zapretkvn.android.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.networkbootstrap.CodedFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine

/** A VPN Network plus the callback that proves it stayed usable for one probe. */
class VpnNetworkLease internal constructor(
    val network: Network,
    private val invalidated: CompletableDeferred<Unit>,
    private val closeCallback: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    suspend fun <T> runWhileActive(block: suspend (Network) -> T): T = coroutineScope {
        val work = async { block(network) }
        val loss = async {
            invalidated.await()
            throw VpnNetworkLostException()
        }
        try {
            select {
                work.onAwait { it }
                loss.onAwait { it }
            }
        } finally {
            work.cancel()
            loss.cancel()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeCallback()
    }
}

internal class VpnNetworkLostException : IllegalStateException(), CodedFailure {
    override val failureCode = "NET-102"
    override val userMessage = "VPN-сеть Android изменилась во время проверки. Подключение будет запущено заново."
    override val technicalDetail = "vpn_network_lost"
    override val message: String get() = userMessage
}

/** Resolves and leases the VPN network used by app-level health and identity probes. */
class VpnNetworkProvider(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    @SuppressLint("MissingPermission")
    suspend fun acquireActive(): VpnNetworkLease = suspendCancellableCoroutine { continuation ->
        val unregistered = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val invalidated = CompletableDeferred<Unit>()
        val leasedNetwork = AtomicReference<Network?>(null)
        lateinit var callback: ConnectivityManager.NetworkCallback

        fun unregister() {
            if (unregistered.compareAndSet(false, true)) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                VpnRuntimeMetrics.callbackClosed()
            }
        }

        fun complete(network: Network) {
            if (!continuation.isActive || !isActiveVpn(network) ||
                !delivered.compareAndSet(false, true)
            ) return
            leasedNetwork.set(network)
            continuation.resume(
                VpnNetworkLease(network, invalidated, ::unregister),
                onCancellation = { _, lease, _ -> lease.close() },
            )
        }

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = complete(network)

            override fun onLost(network: Network) {
                if (leasedNetwork.get() == network) {
                    invalidated.complete(Unit)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (leasedNetwork.get() == network &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    invalidated.complete(Unit)
                } else {
                    complete(network)
                }
            }
        }
        VpnRuntimeMetrics.callbackOpened()
        try {
            connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        } catch (error: Throwable) {
            unregister()
            continuation.cancel(CancellationException("Не удалось наблюдать VPN-сеть Android.", error))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { unregister() }
        connectivity.activeNetwork?.let(::complete)
    }

    suspend fun awaitActive(): Network = acquireActive().use(VpnNetworkLease::network)

    fun requireActive(network: Network) {
        check(connectivity.activeNetwork == network && isActiveVpn(network)) {
            "VPN не является активной сетью приложения."
        }
    }

    private fun isActiveVpn(network: Network): Boolean = connectivity.activeNetwork == network &&
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}
