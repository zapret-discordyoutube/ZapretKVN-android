package io.github.zapretkvn.android.vpn

import android.net.VpnService
import io.github.zapretkvn.android.BuildConfig
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Small lifecycle counters used by diagnostics and the Android lifecycle gate. */
internal object VpnRuntimeMetrics {
    private val sessions = AtomicInteger()
    private val libboxInstances = AtomicInteger()
    private val adapters = AtomicInteger()
    private val tunDescriptors = AtomicInteger()
    private val networkCallbacks = AtomicInteger()
    private val createdLibboxInstances = AtomicInteger()
    private val registeredNetworkCallbacks = AtomicInteger()
    private val uplinkTotal = AtomicLong()
    private val downlinkTotal = AtomicLong()
    private val trafficStatusUpdates = AtomicInteger()
    private val statusClients = AtomicInteger()
    private val logClients = AtomicInteger()

    fun snapshot(): VpnRuntimeSnapshot = VpnRuntimeSnapshot(
        activeSessions = sessions.get(),
        activeLibboxInstances = libboxInstances.get(),
        activePlatformAdapters = adapters.get(),
        activeTunDescriptors = tunDescriptors.get(),
        activeNetworkCallbacks = networkCallbacks.get(),
        activeStatusClients = statusClients.get(),
        activeLogClients = logClients.get(),
    )

    fun sessionOpened() {
        uplinkTotal.set(0)
        downlinkTotal.set(0)
        trafficStatusUpdates.set(0)
        sessions.incrementAndGet()
    }
    fun sessionClosed() = sessions.decrementAndGet().requireNonNegative("session")
    fun libboxOpened() {
        createdLibboxInstances.incrementAndGet()
        libboxInstances.incrementAndGet()
    }
    fun libboxClosed() = libboxInstances.decrementAndGet().requireNonNegative("libbox")
    fun adapterOpened() = adapters.incrementAndGet()
    fun adapterClosed() = adapters.decrementAndGet().requireNonNegative("adapter")
    fun tunOpened() = tunDescriptors.incrementAndGet()
    fun tunClosed() = tunDescriptors.decrementAndGet().requireNonNegative("TUN")
    fun callbackOpened() {
        registeredNetworkCallbacks.incrementAndGet()
        networkCallbacks.incrementAndGet()
    }
    fun callbackClosed() = networkCallbacks.decrementAndGet().requireNonNegative("callback")
    fun libboxCreationCount(): Int = createdLibboxInstances.get()
    fun callbackRegistrationCount(): Int = registeredNetworkCallbacks.get()
    fun updateTraffic(uplink: Long, downlink: Long) {
        uplinkTotal.set(uplink.coerceAtLeast(0))
        downlinkTotal.set(downlink.coerceAtLeast(0))
        trafficStatusUpdates.incrementAndGet()
    }
    fun trafficTotal(): Long = uplinkTotal.get() + downlinkTotal.get()
    fun trafficUpdateCount(): Int = trafficStatusUpdates.get()
    fun statusClientOpened() = statusClients.incrementAndGet()
    fun statusClientClosed() = statusClients.decrementAndGet().requireNonNegative("status client")
    fun logClientOpened() = logClients.incrementAndGet()
    fun logClientClosed() = logClients.decrementAndGet().requireNonNegative("log client")

    private fun Int.requireNonNegative(resource: String) {
        check(this >= 0) { "Отрицательный lifecycle-счётчик: $resource." }
    }
}

internal data class VpnRuntimeSnapshot(
    val activeSessions: Int,
    val activeLibboxInstances: Int,
    val activePlatformAdapters: Int,
    val activeTunDescriptors: Int,
    val activeNetworkCallbacks: Int,
    val activeStatusClients: Int,
    val activeLogClients: Int,
) {
    val isIdle: Boolean
        get() = this == Idle

    companion object {
        val Idle = VpnRuntimeSnapshot(0, 0, 0, 0, 0, 0, 0)
    }
}

/** Debug-only deterministic fault injection. R8 removes these branches from release. */
internal object VpnTestHooks {
    private val failProtect = AtomicBoolean(false)
    private val failAfterEstablish = AtomicBoolean(false)
    private val nextHealthSuccess = AtomicBoolean(false)
    private val nextHealthFailure = AtomicBoolean(false)
    private val nextBootstrapResolutionFailure = AtomicBoolean(false)
    private val nextDnsProbeFailure = AtomicBoolean(false)
    private val nextHttpsProbeFailure = AtomicBoolean(false)
    private val nextCaptivePortal = AtomicBoolean(false)
    private val nextVpnSystemPolicy = AtomicReference<VpnSystemPolicy?>(null)
    private val effectiveRoutingTransform = AtomicReference<((String) -> String)?>(null)

    fun failNextProtect() {
        check(BuildConfig.DEBUG)
        failProtect.set(true)
    }

    fun failNextPostEstablish() {
        check(BuildConfig.DEBUG)
        failAfterEstablish.set(true)
    }

    fun succeedNextHealthCheck() {
        check(BuildConfig.DEBUG)
        nextHealthSuccess.set(true)
    }

    fun failNextHealthCheck() {
        check(BuildConfig.DEBUG)
        nextHealthFailure.set(true)
    }

    fun failNextBootstrapResolution() {
        check(BuildConfig.DEBUG)
        nextBootstrapResolutionFailure.set(true)
    }

    fun failNextDnsProbe() {
        check(BuildConfig.DEBUG)
        nextDnsProbeFailure.set(true)
    }

    fun failNextHttpsProbe() {
        check(BuildConfig.DEBUG)
        nextHttpsProbeFailure.set(true)
    }

    fun reportNextNetworkAsCaptivePortal() {
        check(BuildConfig.DEBUG)
        nextCaptivePortal.set(true)
    }

    fun reportNextVpnSystemPolicy(alwaysOn: Boolean, lockdown: Boolean) {
        check(BuildConfig.DEBUG)
        nextVpnSystemPolicy.set(VpnSystemPolicy(true, alwaysOn, lockdown))
    }

    fun setEffectiveRoutingTransform(transform: (String) -> String) {
        check(BuildConfig.DEBUG)
        effectiveRoutingTransform.set(transform)
    }

    fun reset() {
        failProtect.set(false)
        failAfterEstablish.set(false)
        nextHealthSuccess.set(false)
        nextHealthFailure.set(false)
        nextBootstrapResolutionFailure.set(false)
        nextDnsProbeFailure.set(false)
        nextHttpsProbeFailure.set(false)
        nextCaptivePortal.set(false)
        nextVpnSystemPolicy.set(null)
        effectiveRoutingTransform.set(null)
    }

    fun transformEffectiveRouting(raw: String): String =
        if (BuildConfig.DEBUG) effectiveRoutingTransform.get()?.invoke(raw) ?: raw else raw

    fun protect(service: VpnService, fd: Int): Boolean =
        if (BuildConfig.DEBUG && failProtect.compareAndSet(true, false)) false else service.protect(fd)

    fun protect(service: VpnService, socket: DatagramSocket): Boolean =
        if (BuildConfig.DEBUG && failProtect.compareAndSet(true, false)) false else service.protect(socket)

    fun afterTunEstablished() {
        if (BuildConfig.DEBUG && failAfterEstablish.compareAndSet(true, false)) {
            error("Тестовая ошибка после создания TUN.")
        }
    }

    fun consumeHealthSuccessOverride(): Boolean =
        BuildConfig.DEBUG && nextHealthSuccess.compareAndSet(true, false)

    fun consumeHealthFailureOverride(): Boolean =
        BuildConfig.DEBUG && nextHealthFailure.compareAndSet(true, false)

    fun consumeBootstrapResolutionFailure(): Boolean =
        BuildConfig.DEBUG && nextBootstrapResolutionFailure.compareAndSet(true, false)

    fun consumeDnsProbeFailure(): Boolean =
        BuildConfig.DEBUG && nextDnsProbeFailure.compareAndSet(true, false)

    fun consumeHttpsProbeFailure(): Boolean =
        BuildConfig.DEBUG && nextHttpsProbeFailure.compareAndSet(true, false)

    fun consumeCaptivePortalOverride(): Boolean =
        BuildConfig.DEBUG && nextCaptivePortal.compareAndSet(true, false)

    fun consumeVpnSystemPolicyOverride(): VpnSystemPolicy? =
        if (BuildConfig.DEBUG) nextVpnSystemPolicy.getAndSet(null) else null
}
