package io.github.zapretkvn.android.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

class CurrentWifiSsidReader(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * One user-initiated snapshot. Android redacts the SSID when precise-location
     * access or the system location switch is unavailable; callers must fail safe.
     */
    @SuppressLint("MissingPermission")
    fun read(): String? = connectivity.allNetworks
        .mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@mapNotNull null
            }
            val ssid = wifiSsid(capabilities)
                ?.let(TrustedWifiName::normalize)
                ?: return@mapNotNull null
            val score = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                1
            } else {
                0
            }
            score to ssid
        }
        .maxByOrNull(Pair<Int, String>::first)
        ?.second

    @Suppress("DEPRECATION")
    private fun wifiSsid(capabilities: NetworkCapabilities): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (capabilities.transportInfo as? WifiInfo)?.ssid
        } else {
            wifiManager.connectionInfo?.ssid
        }
}
