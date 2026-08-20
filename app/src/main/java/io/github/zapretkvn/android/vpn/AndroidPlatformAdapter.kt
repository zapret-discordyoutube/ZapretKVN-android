package io.github.zapretkvn.android.vpn

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

internal class AndroidPlatformAdapter(
    private val service: ZapretVpnService,
    private val selectedPackages: Set<String>,
    private val scopeMode: AppScopeMode,
    private val expectedPackages: List<String>,
    private val scopePreflight: VpnAppScopePreflight,
    private val networkMonitor: DefaultNetworkMonitor,
    private val sessionName: String,
) : PlatformInterface, AutoCloseable {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val interfaceListeners = mutableMapOf<InterfaceUpdateListener, AutoCloseable>()
    private val closed = AtomicBoolean(false)
    private val pfdLock = Any()

    @Volatile
    private var tunDescriptor: ParcelFileDescriptor? = null

    @Volatile
    var internalDnsServer: String? = null
        private set

    init {
        VpnRuntimeMetrics.adapterOpened()
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(VpnTestHooks.protect(service, fd)) {
            "Android не разрешил исключить сокет ядра из VPN."
        }
    }

    private fun verifyProtectCapability() {
        DatagramSocket().use { socket ->
            check(VpnTestHooks.protect(service, socket)) {
                "Android не разрешил исключить проверочный сокет из VPN."
            }
        }
    }

    override fun bindInterfaceControl(fd: Int, interfaceName: String) {
        error("Явная привязка к интерфейсу не поддерживается Android VPN.")
    }

    override fun openTun(options: TunOptions): Int {
        check(!closed.get()) { "VPN уже закрывается." }
        check(VpnService.prepare(service) == null) { "Разрешение Android VPN отозвано." }
        check(options.autoRoute) { "libbox запросил TUN без auto_route." }

        val includePackages = options.includePackage.consume()
        val excludePackages = options.excludePackage.consume()
        val actualPackages = if (scopeMode == AppScopeMode.Include) includePackages else excludePackages
        check(
            if (scopeMode == AppScopeMode.Include) excludePackages.isEmpty()
            else includePackages.isEmpty()
        ) { "Runtime TUN смешивает include_package и exclude_package." }
        check(actualPackages.toSet() == expectedPackages.toSet()) {
            "Runtime package scope не совпадает с проверенным Android scope."
        }

        val inet4Addresses = options.inet4Address.consume()
        val inet6Addresses = options.inet6Address.consume()
        check(inet4Addresses.isNotEmpty() && inet6Addresses.isNotEmpty()) {
            "libbox не передал dual-stack адреса TUN."
        }

        check(options.mtu in MIN_MTU..MAX_MTU) {
            "MTU TUN должен быть в диапазоне $MIN_MTU..$MAX_MTU."
        }
        val builder = service.Builder()
            .setSession(sessionName)
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        inet4Addresses.forEach { builder.addAddress(it.address, it.prefix) }
        inet6Addresses.forEach { builder.addAddress(it.address, it.prefix) }

        options.dnsServerAddress.value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.also { internalDnsServer = it }
            ?.let(builder::addDnsServer)
            ?: error("libbox не вернул внутренний DNS-адрес TUN.")

        val inet4Routes = options.inet4RouteRange.consume()
        val inet6Routes = options.inet6RouteRange.consume()
        (inet4Routes.ifEmpty { listOf(RouteSpec("0.0.0.0", 0)) }).forEach {
            builder.addRoute(it.address, it.prefix)
        }
        (inet6Routes.ifEmpty { listOf(RouteSpec("::", 0)) }).forEach {
            builder.addRoute(it.address, it.prefix)
        }

        when (val result = scopePreflight.apply(selectedPackages, scopeMode, builder)) {
            is VpnAppScopeResult.Ready -> {
                check(result.mode == scopeMode)
                check(result.effectivePackages.toSet() == expectedPackages.toSet())
            }
            VpnAppScopeResult.EmptyAllowlist -> error("Пустая область приложений заблокирована.")
            is VpnAppScopeResult.MissingApplications -> error(
                "Не осталось доступных выбранных приложений.",
            )
            is VpnAppScopeResult.BuilderFailure -> error(
                "Android отклонил приложение ${result.packageName}: ${result.reason}",
            )
        }

        check(!options.isHTTPProxyEnabled) {
            "Системный HTTP proxy в TUN пока не поддерживается."
        }

        val descriptor = builder.establish()
            ?: error("Android не создал TUN: разрешение VPN отозвано.")
        synchronized(pfdLock) {
            if (closed.get() || tunDescriptor != null) {
                descriptor.close()
                error("Повторное создание TUN запрещено.")
            }
            tunDescriptor = descriptor
            VpnRuntimeMetrics.tunOpened(options.mtu)
        }
        verifyProtectCapability()
        VpnTestHooks.afterTunEstablished()
        networkMonitor.current.network?.let { service.setUnderlyingNetworks(arrayOf(it)) }
        return descriptor.fd
    }

    // libbox's procfs shortcut returns only a UID and therefore loses Android
    // package names. Package-based route/DNS rules would silently stop matching
    // on API 26-28, so keep ownership resolution in the platform adapter where
    // the UID can be mapped through PackageManager.
    override fun useProcFS(): Boolean = false

    @SuppressLint("NewApi")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
        } else {
            AndroidProcfsConnectionOwner.findUid(ipProtocol, sourceAddress, sourcePort)
        }
        check(uid != Process.INVALID_UID) { "Android не нашёл владельца соединения." }
        val packages = service.packageManager.getPackagesForUid(uid).orEmpty().toList()
        check(packages.isNotEmpty()) { "Android не сопоставил владельца соединения с приложением." }
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(ListStringIterator(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(interfaceListeners) {
            check(!closed.get()) { "VPN уже закрывается." }
            if (interfaceListeners.containsKey(listener)) return
            interfaceListeners[listener] = networkMonitor.observe { state ->
                state.network?.let { service.setUnderlyingNetworks(arrayOf(it)) }
                    ?: service.setUnderlyingNetworks(null)
                listener.updateDefaultInterface(
                    state.interfaceName.orEmpty(),
                    state.interfaceIndex,
                    state.metered,
                    false,
                )
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(interfaceListeners) {
            interfaceListeners.remove(listener)?.close()
        }
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = connectivity.allNetworks.mapNotNull { network ->
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = properties.interfaceName ?: return@mapNotNull null
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: return@mapNotNull null
            BoxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
                addresses = ListStringIterator(javaInterface.interfaceAddresses.map { interfaceAddress ->
                    val host = if (interfaceAddress.address is Inet6Address) {
                        Inet6Address.getByAddress(interfaceAddress.address.address).hostAddress
                    } else {
                        interfaceAddress.address.hostAddress
                    }
                    "$host/${interfaceAddress.networkPrefixLength}"
                })
                dnsServer = ListStringIterator(properties.dnsServers.mapNotNull { it.hostAddress })
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                flags = interfaceFlags(javaInterface, capabilities)
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }.distinctBy { it.name }
        return ListNetworkInterfaceIterator(result)
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport = AndroidLocalDnsTransport {
        networkMonitor.current.network
    }
    override fun systemCertificates(): StringIterator? = null
    override fun sendNotification(notification: Notification) = Unit

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(interfaceListeners) {
            interfaceListeners.values.forEach { runCatching { it.close() } }
            interfaceListeners.clear()
        }
        internalDnsServer = null
        service.setUnderlyingNetworks(null)
        synchronized(pfdLock) {
            val descriptor = tunDescriptor
            tunDescriptor = null
            if (descriptor != null) {
                runCatching { descriptor.close() }
                VpnRuntimeMetrics.tunClosed()
            }
        }
        VpnRuntimeMetrics.adapterClosed()
    }

    private fun interfaceFlags(
        networkInterface: NetworkInterface,
        capabilities: NetworkCapabilities,
    ): Int {
        var flags = 0
        if (networkInterface.isUp) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        if (networkInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
        if (networkInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
        if (networkInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            flags = flags or OsConstants.IFF_UP
        }
        return flags
    }

    private companion object {
        const val MIN_MTU = 1280
        const val MAX_MTU = 9000
    }
}

internal object AndroidProcfsConnectionOwner {
    fun findUid(ipProtocol: Int, sourceAddress: String, sourcePort: Int): Int {
        val protocol = when (ipProtocol) {
            OsConstants.IPPROTO_TCP -> "tcp"
            OsConstants.IPPROTO_UDP -> "udp"
            else -> return Process.INVALID_UID
        }
        val localKeys = localKeys(sourceAddress, sourcePort)
        if (localKeys.isEmpty()) return Process.INVALID_UID
        repeat(LOOKUP_ATTEMPTS) { attempt ->
            for (path in listOf("/proc/net/$protocol", "/proc/net/${protocol}6")) {
                val uid = runCatching {
                    File(path).useLines { lines -> parseUid(lines, localKeys) }
                }.getOrNull()
                if (uid != null) return uid
            }
            if (attempt + 1 < LOOKUP_ATTEMPTS) SystemClock.sleep(LOOKUP_RETRY_MILLIS)
        }
        return Process.INVALID_UID
    }

    internal fun parseUid(lines: Sequence<String>, localKeys: Set<String>): Int? {
        lines.drop(1).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size > UID_COLUMN && fields[LOCAL_ADDRESS_COLUMN].uppercase(Locale.ROOT) in localKeys) {
                return fields[UID_COLUMN].toIntOrNull()
            }
        }
        return null
    }

    internal fun localKeys(sourceAddress: String, sourcePort: Int): Set<String> {
        if (sourcePort !in 1..0xffff) return emptySet()
        val address = runCatching {
            InetAddress.getByName(sourceAddress.substringBefore('%')).address
        }.getOrNull() ?: return emptySet()
        val port = sourcePort.toString(16).padStart(4, '0').uppercase(Locale.ROOT)
        val addresses = buildList {
            add(address)
            if (address.size == 4) {
                add(ByteArray(16).apply {
                    this[10] = 0xff.toByte()
                    this[11] = 0xff.toByte()
                    address.copyInto(this, destinationOffset = 12)
                })
            }
        }
        return addresses.mapTo(linkedSetOf()) { bytes ->
            bytes.asList()
                .chunked(4)
                .flatMap { it.reversed() }
                .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xff) } +
                ":$port"
        }
    }

    private const val LOCAL_ADDRESS_COLUMN = 1
    private const val UID_COLUMN = 7
    private const val LOOKUP_ATTEMPTS = 3
    private const val LOOKUP_RETRY_MILLIS = 2L
}
