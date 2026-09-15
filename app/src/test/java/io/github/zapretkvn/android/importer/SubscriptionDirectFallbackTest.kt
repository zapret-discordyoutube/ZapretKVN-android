package io.github.zapretkvn.android.importer

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Direct-first subscription fetch: the VPN path is a fallback only when the
 * direct (non-VPN) attempt returns no answer. A real HTTP answer on the direct
 * path — including a 404 for a revoked link — is authoritative and terminal,
 * never masked by a VPN retry.
 */
class SubscriptionDirectFallbackTest {
    private fun serve(status: Int, body: String): Pair<HttpServer, AtomicInteger> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger(0)
        server.createContext("/subscription") { exchange ->
            hits.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to hits
    }

    private fun url(server: HttpServer): String =
        "http://127.0.0.1:${server.address.port}/subscription"

    @Test
    fun `direct transport failure falls back to the default path`() {
        val (defaultServer, defaultHits) = serve(200, "DIRECT-FIRST-OK")
        try {
            val fetcher = HttpSubscriptionFetcher(
                openConnection = { requestUrl, direct ->
                    if (direct) throw IOException("_ssl.c:993 handshake timed out")
                    requestUrl.openConnection() as? HttpURLConnection
                },
                directAvailable = { true },
            )
            assertEquals(
                "DIRECT-FIRST-OK",
                fetcher.fetch(SubscriptionSource(url(defaultServer))),
            )
            assertEquals(1, defaultHits.get())
        } finally {
            defaultServer.stop(0)
        }
    }

    @Test
    fun `direct http answer is terminal and never falls back to the vpn path`() {
        val (directServer, _) = serve(404, "revoked")
        val (defaultServer, defaultHits) = serve(200, "SHOULD-NOT-BE-USED")
        try {
            val fetcher = HttpSubscriptionFetcher(
                openConnection = { _, direct ->
                    val target = if (direct) url(directServer) else url(defaultServer)
                    URL(target).openConnection() as? HttpURLConnection
                },
                directAvailable = { true },
            )
            assertThrows(ImportException::class.java) {
                fetcher.fetch(SubscriptionSource(url(directServer)))
            }
            // The server already answered directly: the VPN path is never tried.
            assertEquals(0, defaultHits.get())
        } finally {
            directServer.stop(0)
            defaultServer.stop(0)
        }
    }

    @Test
    fun `without a direct network the default path is used unchanged`() {
        val (defaultServer, defaultHits) = serve(200, "DEFAULT-ONLY")
        try {
            var directOpened = false
            val fetcher = HttpSubscriptionFetcher(
                openConnection = { requestUrl, direct ->
                    if (direct) directOpened = true
                    requestUrl.openConnection() as? HttpURLConnection
                },
                directAvailable = { false },
            )
            assertEquals(
                "DEFAULT-ONLY",
                fetcher.fetch(SubscriptionSource(url(defaultServer))),
            )
            assertFalse(directOpened)
            assertEquals(1, defaultHits.get())
        } finally {
            defaultServer.stop(0)
        }
    }
}
