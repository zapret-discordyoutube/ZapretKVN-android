package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NaiveProxy keys (owner decision 2026-11-27): the stock naive client owns the protocol; sing-box never translates it.
 * Uses the real canary key when this machine has it (`/root/.config/kvn-test-naive-key-android` or
 * `KVN_TEST_NAIVE_KEY`), else the contract line of the bot. The live run is the instrumented test on the emulator.
 */
class NaiveImportTest {
    private val contract = "naive+https://k-7f3a:Q2xpZW50U2VjcmV0MDEyMzQ1Njc4OQ@q417.ru1.example.com:443" +
        "?insecure-concurrency=2#%F0%9F%87%A9%F0%9F%87%AA%20Germany"

    private val line: String by lazy {
        val file = File(System.getenv("KVN_TEST_NAIVE_KEY") ?: "/root/.config/kvn-test-naive-key-android")
        if (file.isFile) file.readText().trim() else contract
    }

    private fun managed(input: String = line) = ImportParser.parse(input, ProfileSource.Link) as ImportCandidate.Managed

    @Test
    fun `a key becomes a naive server owned by the stock client`() {
        val candidate = managed()
        val outbound = candidate.servers.single().outbound
        assertEquals("naive", outbound["type"]?.jsonPrimitive?.content)
        assertTrue(outbound.containsKey("username"))
        assertFalse("the path of the client is runtime-only", outbound.containsKey("executable_path"))
    }

    @Test
    fun `pasted text with a note and a subscription line both find the key`() {
        assertEquals(1, managed("заметка\n$line\n").servers.size)
        assertEquals(1, ImportParser.parse(line, ProfileSource.Subscription).let { (it as ImportCandidate.Managed).servers.size })
    }

    @Test
    fun `a bad key is a clear import error without the secret`() {
        val bad = "naive+https://a:Q2xpZW50U2VjcmV0MDEyMzQ1Njc4OQ@192.0.2.7:443#n"
        val error = runCatching { ImportParser.parse(bad, ProfileSource.Link) }.exceptionOrNull()
        assertTrue(error is ImportException)
        assertFalse(error!!.message.orEmpty().contains("Q2xpZW50U2VjcmV0MDEyMzQ1Njc4OQ"))
    }

    @Test
    fun `a concurrency above the advice is warned about in the preview`() {
        val secret = "Q2xpZW50U2VjcmV0MDEyMzQ1Njc4OQ"
        val candidate = managed("naive+https://a:$secret@h.example.com?insecure-concurrency=9#n")
        assertTrue(candidate.servers.single().importWarnings.isNotEmpty() || candidate.importWarnings.isNotEmpty())
    }

    @Test
    fun `the runtime copy gets the client path and the stored profile never does`() {
        val stored = ManagedProfileFactory.subscription(managed().servers)
        assertFalse(stored.contains("executable_path"))
        val withPath = RuntimeConfigBuilder.build(
            ParsedConfig.parse(stored), options = RuntimeConfigOptions(naiveExecutablePath = "/data/app/lib/arm64/libnaive.so"),
        )
        val ready = withPath as? RuntimeConfigResult.Ready ?: error("expected a runtime config, got $withPath")
        val naive = (ParsedConfig.parse(ready.json).root!!["outbounds"] as JsonArray)
            .map { it as JsonObject }.single { it["type"]?.jsonPrimitive?.content == "naive" }
        assertEquals("/data/app/lib/arm64/libnaive.so", naive["executable_path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `without the client a profile with a naive server is refused clearly`() {
        val stored = ManagedProfileFactory.subscription(managed().servers)
        val result = RuntimeConfigBuilder.build(ParsedConfig.parse(stored), options = RuntimeConfigOptions())
        assertTrue(result is RuntimeConfigResult.Invalid)
        assertTrue((result as RuntimeConfigResult.Invalid).message.contains("NaiveProxy"))
    }
}
