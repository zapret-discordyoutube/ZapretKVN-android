package io.github.zapretkvn.android.apps

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppScopePreflightTest {
    @Test
    fun `whole-app blocklist is empty by default`() {
        assertTrue(AppSelection().blockedPackages.isEmpty())
        assertTrue(AppsUiState().blockedPackages.isEmpty())
    }

    @Test
    fun `empty user allowlist never touches builder`() {
        val added = mutableListOf<String>()
        val result = preflight(available = setOf(OWN_PACKAGE)).apply(
            userAllowlist = emptySet(),
            sink = added::add,
        )

        assertEquals(VpnAppScopeResult.EmptyAllowlist, result)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `only missing selected package is rejected because no app remains`() {
        val added = mutableListOf<String>()
        val result = preflight(available = setOf(OWN_PACKAGE)).apply(
            userAllowlist = setOf("com.example.removed"),
            sink = added::add,
        )

        assertEquals(
            VpnAppScopeResult.MissingApplications(listOf("com.example.removed")),
            result,
        )
        assertTrue(added.isEmpty())
    }

    @Test
    fun `missing package is skipped when another selected app remains`() {
        val added = mutableListOf<String>()
        val result = preflight(
            available = setOf("com.example.installed", OWN_PACKAGE),
        ).apply(
            userAllowlist = setOf("com.example.removed", "com.example.installed"),
            sink = added::add,
        )

        assertEquals(
            VpnAppScopeResult.Ready(
                mode = AppScopeMode.Include,
                effectivePackages = listOf("com.example.installed", OWN_PACKAGE),
                skippedPackages = listOf("com.example.removed"),
            ),
            result,
        )
        assertEquals(listOf("com.example.installed", OWN_PACKAGE), added)
    }

    @Test
    fun `builder failure rejects launch instead of using partial list`() {
        val result = preflight(
            available = setOf("com.example.client", OWN_PACKAGE),
        ).apply(
            userAllowlist = setOf("com.example.client"),
            sink = AllowedApplicationSink { packageName ->
                if (packageName == OWN_PACKAGE) throw IOException("package disappeared")
            },
        )

        assertTrue(result is VpnAppScopeResult.BuilderFailure)
        assertEquals(OWN_PACKAGE, (result as VpnAppScopeResult.BuilderFailure).packageName)
    }

    @Test
    fun `ready scope adds stable user list and hidden health package`() {
        val added = mutableListOf<String>()
        val result = preflight(
            available = setOf("com.example.beta", "com.example.alpha", OWN_PACKAGE),
        ).apply(
            userAllowlist = setOf(" com.example.beta ", "com.example.alpha", OWN_PACKAGE),
            sink = added::add,
        )

        val expected = listOf("com.example.alpha", "com.example.beta", OWN_PACKAGE)
        assertEquals(VpnAppScopeResult.Ready(expected), result)
        assertEquals(expected, added)
    }

    @Test
    fun `exclude mode applies disallowed packages and keeps own app in VPN`() {
        val allowed = mutableListOf<String>()
        val disallowed = mutableListOf<String>()
        val result = preflight(setOf("bypass.app", OWN_PACKAGE)).apply(
            selectedPackages = setOf("bypass.app", OWN_PACKAGE),
            mode = AppScopeMode.Exclude,
            allowedSink = AllowedApplicationSink(allowed::add),
            disallowedSink = DisallowedApplicationSink(disallowed::add),
        )

        assertEquals(
            VpnAppScopeResult.Ready(AppScopeMode.Exclude, listOf("bypass.app")),
            result,
        )
        assertTrue(allowed.isEmpty())
        assertEquals(listOf("bypass.app"), disallowed)
    }

    @Test
    fun `empty exclude list is blocked`() {
        val result = preflight(setOf(OWN_PACKAGE)).apply(
            selectedPackages = setOf(OWN_PACKAGE),
            mode = AppScopeMode.Exclude,
            allowedSink = AllowedApplicationSink { },
            disallowedSink = DisallowedApplicationSink { },
        )

        assertEquals(VpnAppScopeResult.EmptyAllowlist, result)
    }

    @Test
    fun `blocked apps join include boundary but never Android exclude list`() {
        val include = AppSelection(
            allowedPackages = setOf("com.example.vpn"),
            blockedPackages = setOf("com.example.blocked"),
            mode = AppScopeMode.Include,
        )
        val exclude = include.copy(mode = AppScopeMode.Exclude)

        assertEquals(
            setOf("com.example.vpn", "com.example.blocked"),
            include.selectedPackagesForTunBoundary(),
        )
        assertEquals(
            setOf("com.example.vpn"),
            exclude.selectedPackagesForTunBoundary(),
        )
    }

    @Test
    fun `suggestions contain requested apps and never contain TikTok`() {
        val packages = PopularAppSuggestions.packageNames

        assertTrue("com.openai.chatgpt" in packages)
        assertTrue("com.anthropic.claude" in packages)
        assertTrue("com.google.android.apps.bard" in packages)
        assertTrue("ai.perplexity.app.android" in packages)
        assertTrue("com.microsoft.copilot" in packages)
        assertTrue("com.deepseek.chat" in packages)
        assertTrue("ai.x.grok" in packages)
        assertTrue("com.suno.android" in packages)
        assertTrue("com.spotify.music" in packages)
        assertTrue("notion.id" in packages)
        assertTrue("com.instagram.android" in packages)
        assertTrue("com.twitter.android" in packages)
        assertTrue("com.google.android.youtube" in packages)
        assertTrue("com.google.android.apps.youtube.music" in packages)
        assertTrue("app.revanced.android.youtube" in packages)
        assertTrue("app.revanced.android.apps.youtube.music" in packages)
        assertTrue("app.rvx.android.youtube" in packages)
        assertTrue("app.rvx.android.apps.youtube.music" in packages)
        assertTrue("com.vanced.android.youtube" in packages)
        assertTrue("com.vanced.android.apps.youtube.music" in packages)
        assertTrue("app.morphe.android.youtube" in packages)
        assertTrue("app.morphe.androoid.youtube" in packages)
        assertTrue("app.morphe.android.apps.youtube.music" in packages)
        assertTrue("org.telegram.messenger" in packages)
        assertTrue("org.telegram.messenger.beta" in packages)
        assertTrue("org.telegram.messenger.web" in packages)
        assertTrue("org.zastogram.messenger" in packages)
        assertTrue("tw.nekomimi.nekogram" in packages)
        assertTrue("com.radolyn.ayugram" in packages)
        assertTrue("org.forkgram.messenger" in packages)
        assertTrue("com.whatsapp" in packages)
        assertTrue("com.discord" in packages)
        assertTrue("org.thoughtcrime.securesms" in packages)
        assertTrue("com.android.chrome" in packages)
        assertTrue("org.chromium.chrome" in packages)
        assertTrue("org.chromium.chrome.dev" in packages)
        assertTrue("org.mozilla.firefox" in packages)
        assertTrue("com.brave.browser" in packages)
        assertFalse(packages.any { it.contains("tiktok", ignoreCase = true) })
    }

    @Test
    fun `disabled recommended system apps are selected by default but ordinary system apps are not`() {
        val packages = defaultVpnPackages(
            listOf(
                installedApp(
                    packageName = "com.google.android.apps.bard",
                    system = true,
                    enabled = false,
                    suggestion = "Gemini",
                ),
                installedApp(
                    packageName = "com.google.android.youtube",
                    system = true,
                    enabled = false,
                    suggestion = "YouTube",
                ),
                installedApp(
                    packageName = "com.google.android.apps.youtube.music",
                    system = true,
                    enabled = false,
                    suggestion = "YouTube Music",
                ),
                installedApp(
                    packageName = "com.android.settings",
                    system = true,
                    enabled = true,
                    suggestion = null,
                ),
            ),
        )

        assertEquals(
            setOf(
                "com.google.android.apps.bard",
                "com.google.android.youtube",
                "com.google.android.apps.youtube.music",
            ),
            packages,
        )
    }

    @Test
    fun `any installed browser is suggested without a hardcoded package`() {
        assertEquals(
            "Браузер",
            suggestedAppLabel(
                packageName = "com.example.browser",
                browserPackages = setOf("com.example.browser"),
            ),
        )
        assertEquals(
            null,
            suggestedAppLabel(
                packageName = "com.example.notes",
                browserPackages = setOf("com.example.browser"),
            ),
        )
    }

    @Test
    fun `pyaterochka web handler is not suggested as a browser`() {
        assertEquals(
            null,
            suggestedAppLabel(
                packageName = "ru.pyaterochka.app.browser",
                browserPackages = setOf("ru.pyaterochka.app.browser"),
            ),
        )
    }

    @Test
    fun `any tg scheme handler is suggested without a hardcoded package`() {
        assertEquals(
            "Telegram-клиент",
            suggestedAppLabel(
                packageName = "com.example.telegramfork",
                browserPackages = emptySet(),
                telegramPackages = setOf("com.example.telegramfork"),
            ),
        )
        assertEquals(
            null,
            suggestedAppLabel(
                packageName = "com.example.notes",
                browserPackages = emptySet(),
                telegramPackages = setOf("com.example.telegramfork"),
            ),
        )
    }

    @Test
    fun `any youtube link handler is suggested without a hardcoded package`() {
        assertEquals(
            "YouTube-клиент",
            suggestedAppLabel(
                packageName = "com.example.youtube.client",
                browserPackages = emptySet(),
                youtubePackages = setOf("com.example.youtube.client"),
            ),
        )
        assertEquals(
            "Браузер",
            suggestedAppLabel(
                packageName = "com.example.browser",
                browserPackages = setOf("com.example.browser"),
                youtubePackages = setOf("com.example.browser"),
            ),
        )
    }

    @Test
    fun `application picker prompt requires zero selected applications`() {
        assertFalse(AppsUiState(initialized = false).needsAppSelection)
        assertTrue(
            AppsUiState(
                initialized = true,
                allowedPackages = emptySet(),
            ).needsAppSelection,
        )
        assertFalse(
            AppsUiState(
                initialized = true,
                allowedPackages = setOf("com.example.browser"),
            ).needsAppSelection,
        )
        assertFalse(
            AppsUiState(
                initialized = true,
                blockedPackages = setOf("com.example.blocked"),
            ).needsAppSelection,
        )
        assertTrue(
            AppsUiState(
                initialized = true,
                blockedPackages = setOf("com.example.blocked"),
                scopeMode = AppScopeMode.Exclude,
            ).needsAppSelection,
        )
    }

    private fun preflight(available: Set<String>) = VpnAppScopePreflight(
        ownPackageName = OWN_PACKAGE,
        packageAvailability = PackageAvailability { packageName ->
            packageName in available
        },
    )

    private fun installedApp(
        packageName: String,
        system: Boolean,
        enabled: Boolean,
        suggestion: String?,
    ) = InstalledApp(
        packageName = packageName,
        label = packageName,
        system = system,
        enabled = enabled,
        suggestion = suggestion,
    )

    private companion object {
        const val OWN_PACKAGE = "io.github.zapretkvn.android"
    }
}
