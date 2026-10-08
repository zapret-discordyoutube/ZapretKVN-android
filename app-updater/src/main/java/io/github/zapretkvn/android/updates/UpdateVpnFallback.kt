package io.github.zapretkvn.android.updates

import android.content.Intent
import java.io.File
import java.net.URL
import java.net.URLConnection

/**
 * App-owned bridge used only after a retryable updater request has failed.
 * The updater module does not know how the host application implements its VPN.
 */
fun interface UpdateVpnFallback {
    suspend fun connect(): UpdateVpnSession
}

/**
 * App-owned source of connections that bypass an active VPN: the direct updater
 * attempt must reach Forgejo over the physical network even while the app's own
 * traffic is routed through the tunnel. Returns null when no physical network is
 * visible; the request then uses the process default route.
 */
fun interface UpdateDirectConnections {
    fun open(url: URL): URLConnection?
}

/** Restores the VPN state that existed before the temporary updater route was enabled. */
fun interface UpdateVpnSession {
    suspend fun close()
}

/** Creates the app-owned, FileProvider-backed installer intent for a verified APK. */
fun interface UpdateInstallIntentFactory {
    fun create(file: File): Intent
}
