package io.github.zapretkvn.android.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The NaiveProxy subscription of the key service (2026-11-27, asked by the NaiveProxy link session): the real URL of a
 * synthetic person, fetched by the app's own fetcher and parsed by the app's own importer, twice (a refresh): two naive
 * servers, no bad line, the same identities on the second pass. The URL is pushed to `/data/local/tmp/kvn-test-naive-subscription`
 * (`adb push`); without it the test skips. The assertions are the result; nothing of the URL or the keys is printed.
 */
@RunWith(AndroidJUnit4::class)
class NaiveSubscriptionRealInstrumentedTest {
    private val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZapretApplication).container

    private fun shell(command: String): String =
        FileInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).fileDescriptor)
            .use { it.readBytes().toString(Charsets.UTF_8) }.trim()

    @Test
    fun aNaiveSubscriptionImportsOneServerPerLineAndRefreshesToTheSameIdentities() = runBlocking {
        val url = shell("cat /data/local/tmp/kvn-test-naive-subscription").takeIf { it.startsWith("https://") }
        assumeTrue("no NaiveProxy test subscription on the device", url != null)
        fun importOnce(): ImportCandidate.Managed {
            val body = container.subscriptionFetcher.fetch(SubscriptionSource(url!!))
            return ImportParser.parse(body, ProfileSource.Url) as ImportCandidate.Managed
        }
        val first = withContext(Dispatchers.IO) { importOnce() }
        val second = withContext(Dispatchers.IO) { importOnce() }
        assertEquals("one server per naive line", 2, first.servers.size)
        assertTrue("the refresh must give the same identities", first.servers.map { it.identityKey } == second.servers.map { it.identityKey })
        assertTrue("every server is a naive outbound", first.servers.all { "naive" in it.outbound["type"].toString() })
    }
}
