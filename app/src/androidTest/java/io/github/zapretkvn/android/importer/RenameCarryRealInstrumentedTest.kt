package io.github.zapretkvn.android.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.profiles.ManagedProfileEditor
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rename keeps the chosen server on REAL data with lines that share one host and port (owner decision 2026-11-28, the rows
 * «Exit · Entry»; 0.6.74 matches the whole outbound except the tag). The vpnbot test person whose VLESS subscription has such
 * lines is pushed to `/data/local/tmp/kvn-test-subscription-shared` (`adb push`); without it the test skips. The app's own
 * fetcher and importer read the live body, every line is renamed, the refresh is run for each line that shares its address
 * with another one, and the choice must follow it. Nothing is printed: only the assertions.
 */
@RunWith(AndroidJUnit4::class)
class RenameCarryRealInstrumentedTest {
    private val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZapretApplication).container

    private fun shell(command: String): String =
        FileInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).fileDescriptor)
            .use { it.readBytes().toString(Charsets.UTF_8) }.trim()

    @Test
    fun aRenameKeepsTheChosenLineOfARealSubscriptionWhereLinesShareAnAddress() = runBlocking {
        val file = shell("cat /data/local/tmp/kvn-test-subscription-shared").takeIf { it.startsWith("{") }
        assumeTrue("no real test subscription with shared addresses on the device", file != null)
        val url = JSONObject(file!!).getString("vless_subscription_url")
        val servers = withContext(Dispatchers.IO) {
            val body = container.subscriptionFetcher.fetch(SubscriptionSource(url))
            (ImportParser.parse(body, ProfileSource.Url) as ImportCandidate.Managed).servers
        }
        fun address(index: Int) = "${servers[index].outbound["server"]}:${servers[index].outbound["server_port"]}"
        val shared = servers.indices.filter { i -> servers.indices.count { address(it) == address(i) } > 1 }
        assumeTrue("the real subscription has no lines sharing a host and port", shared.isNotEmpty())
        val tagsBefore = ManagedProfileFactory.stableTags(servers)
        val renamed = servers.map { it.copy(displayName = it.displayName + " · Timama") }
        val tagsAfter = ManagedProfileFactory.stableTags(renamed)
        val original = ManagedProfileFactory.subscription(servers)
        var checked = 0
        for (index in shared) {
            val selected = ConfigAnalyzer.selectServer(original, ConfigAnalyzer.MANAGED_SELECTOR_TAG, tagsBefore[index])
            val refreshed = ManagedProfileEditor.refreshServers(selected, renamed)
            assertEquals("the choice must follow its line #$index after the rename", tagsAfter[index], refreshed.selectedTag)
            assertFalse("a renamed line is not a changed selection (#$index)", refreshed.selectionChanged)
            checked++
        }
        assertTrue("checked every shared line", checked == shared.size && checked >= 2)
    }

    /**
     * The live check of the production rename (2026-11-28, «Exit · Entry»): the live body already carries the NEW names; the
     * profile a person stored before the rename is rebuilt from the same lines with the OLD names (the new name without the
     * « · Timentry» / « · Timama» / « · Bebepf» entry part, which the service also names in zapret-server-ids). Every line that
     * shares a host and port must keep its chosen server through the refresh.
     */
    @Test
    fun anOldNameProfileKeepsItsChoiceWhenTheLiveRenamedBodyArrives() = runBlocking {
        val file = shell("cat /data/local/tmp/kvn-test-subscription-shared").takeIf { it.startsWith("{") }
        assumeTrue("no real test subscription with shared addresses on the device", file != null)
        val url = JSONObject(file!!).getString("vless_subscription_url")
        val live = withContext(Dispatchers.IO) {
            val body = container.subscriptionFetcher.fetch(SubscriptionSource(url))
            (ImportParser.parse(body, ProfileSource.Url) as ImportCandidate.Managed).servers
        }
        val entryPart = Regex(" · (Timentry|Timama|Bebepf|Bebebera)")
        assumeTrue("the live body has no renamed «Exit · Entry» lines", live.any { entryPart.containsMatchIn(it.displayName) })
        val old = live.map { it.copy(displayName = it.displayName.replaceFirst(entryPart, "")) }
        fun address(list: List<io.github.zapretkvn.android.profiles.ManagedServer>, i: Int) =
            "${list[i].outbound["server"]}:${list[i].outbound["server_port"]}"
        val shared = old.indices.filter { i -> old.indices.count { address(old, it) == address(old, i) } > 1 }
        assumeTrue("no lines share a host and port", shared.isNotEmpty())
        val oldTags = ManagedProfileFactory.stableTags(old)
        val liveTags = ManagedProfileFactory.stableTags(live)
        val stored = ManagedProfileFactory.subscription(old)
        for (index in shared) {
            val selected = ConfigAnalyzer.selectServer(stored, ConfigAnalyzer.MANAGED_SELECTOR_TAG, oldTags[index])
            val refreshed = ManagedProfileEditor.refreshServers(selected, live)
            assertEquals("the choice must follow line #$index from its old name to the new one", liveTags[index], refreshed.selectedTag)
            assertFalse("a renamed line is not a changed selection (#$index)", refreshed.selectionChanged)
        }
        assertTrue("checked at least two shared lines", shared.size >= 2)
    }
}
