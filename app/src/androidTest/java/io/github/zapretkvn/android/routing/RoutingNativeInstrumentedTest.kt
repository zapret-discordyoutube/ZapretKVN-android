package io.github.zapretkvn.android.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutingNativeInstrumentedTest {
    private val container
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ZapretApplication).container

    @Test
    fun everyManagedPresetAndBlockPassPinnedLibboxWithPackagedRuleSets() = runBlocking {
        val installed = container.ruleSetAssetManager.ensureInstalled()
        val rules = listOf(
            ManagedRoutingRule(
                RoutingMatchType.Domain,
                listOf("blocked.example"),
                RoutingRuleAction.Block,
            ),
            ManagedRoutingRule(
                RoutingMatchType.IpCidr,
                listOf("192.0.2.0/24", "2001:db8::/32"),
                RoutingRuleAction.Direct,
            ),
        )
        RoutingPreset.entries.filterNot { it == RoutingPreset.Custom }.forEach { preset ->
            val result = RoutingConfigEditor.apply(profile(), preset, rules, installed)
            Libbox.checkConfig(result.json)
            assertTrue(result.json.contains("\"route\""))
        }
    }

    @Test
    fun packagedRuleSetsInstallOfflineAndRebindToPrivateFiles() = runBlocking {
        val installed = container.ruleSetAssetManager.ensureInstalled()
        val result = RoutingConfigEditor.apply(
            profile(),
            RoutingPreset.RussiaDirect,
            emptyList(),
            installed,
        )

        assertTrue(installed.paths.values.all { it.startsWith(container.appContext.filesDir.absolutePath) })
        assertTrue(installed.paths.values.all { java.io.File(it).isFile })
        Libbox.checkConfig(result.json)
    }

    @Test
    fun packagedRuleSetColdInstallAndPinnedCoreLoadStaySmall() = runBlocking {
        val root = java.io.File(container.appContext.filesDir, "rule-sets")
        root.deleteRecursively()
        val installStart = System.nanoTime()
        val installed = container.ruleSetAssetManager.ensureInstalled()
        val installMillis = (System.nanoTime() - installStart) / 1_000_000
        val totalBytes = installed.paths.values.sumOf { java.io.File(it).length() }
        val configured = RoutingConfigEditor.apply(
            profile(),
            RoutingPreset.RussiaDirect,
            emptyList(),
            installed,
        ).json
        val loadStart = System.nanoTime()
        repeat(20) { Libbox.checkConfig(configured) }
        val averageLoadMillis = (System.nanoTime() - loadStart) / 1_000_000.0 / 20.0

        println(
            "ROUTING_PERF api=${android.os.Build.VERSION.SDK_INT} bytes=$totalBytes " +
                "cold_install_ms=$installMillis average_check_ms=$averageLoadMillis",
        )

        assertTrue("Unexpected rule-set size: $totalBytes", totalBytes == 50_114L)
        assertTrue("Cold extraction took ${installMillis}ms", installMillis < 5_000)
        assertTrue("Average pinned-core load took ${averageLoadMillis}ms", averageLoadMillis < 1_000.0)
    }

    private fun profile(): String = ManagedProfileFactory.single(
        ManagedServer(
            displayName = "Test",
            identityKey = "test|server",
            outbound = JsonObject(mapOf("type" to JsonPrimitive("direct"))),
        ),
    )
}
