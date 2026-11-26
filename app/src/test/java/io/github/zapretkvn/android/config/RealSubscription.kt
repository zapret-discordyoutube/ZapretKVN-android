package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * The real test person of vpnbot as test data (owner decision 2026-11-25: tests run on real data only).
 *
 * `/root/.config/kvn-test-subscription` (override: environment variable `KVN_TEST_SUBSCRIPTION`) holds one JSON object
 * with the two live subscription URLs of the test person. The app imports the URLs itself, so this fetches them as the
 * app does and runs the app's own importer. Nothing here prints a value of the file or of an imported server.
 * Without the file the tests skip: a skipped test is an honest "unproven", an invented profile would only prove that the
 * test agrees with itself.
 */
object RealSubscription {
    private val path: File = File(System.getenv("KVN_TEST_SUBSCRIPTION") ?: "/root/.config/kvn-test-subscription")

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "ZapretKVN")
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The profile the app stores for the VLESS servers (Xray-owned) of the test person, as raw sing-box JSON. */
    val vlessProfile: String by lazy { profileOf("vless_subscription_url") }

    /** The profile for the Hysteria2 servers of the test person. */
    val hysteriaProfile: String by lazy { profileOf("hysteria2_subscription_url") }

    /** The sing-box profile the app stores for the test person's AmneziaWG config. */
    val awgProfile: String by lazy {
        requireIt()
        val text = Json.parseToJsonElement(path.readText()).jsonObject.getValue("awg_conf").jsonPrimitive.content
        (ImportParser.parse(text, ProfileSource.Link) as ImportCandidate.WireGuard).json
    }

    fun requireIt() = Assume.assumeTrue("no real test subscription here (KVN_TEST_SUBSCRIPTION)", path.isFile)

    private fun profileOf(key: String): String {
        requireIt()
        val url = Json.parseToJsonElement(path.readText()).jsonObject.getValue(key).jsonPrimitive.content
        val candidate = ImportParser.parse(fetch(url), ProfileSource.Url)
        val managed = candidate as ImportCandidate.Managed
        return ManagedProfileFactory.subscription(managed.servers)
    }
}
