package io.github.zapretkvn.android.vpn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppScopePreflightTest {
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
    fun `revision four applies skipped Brave and Twitter migrations once`() {
        val migratedPackages = setOf("com.brave.browser", "com.twitter.android")

        assertEquals(4, PopularAppSuggestions.MIGRATION_REVISION)
        assertEquals(
            setOf("com.twitter.android"),
            PopularAppSuggestions.packagesAddedInCurrentRevision,
        )
        assertEquals(
            migratedPackages,
            pendingSuggestedPackages(
                suggestedPackageMigrations = PopularAppSuggestions.packagesAddedByRevision,
                storedSuggestionRevision = 2,
                suggestionRevision = 4,
            ),
        )
        assertEquals(
            sortedSetOf("org.telegram.messenger") + migratedPackages,
            mergeSuggestedPackages(
                currentPackages = setOf("org.telegram.messenger"),
                suggestedPackages = emptySet(),
                newlySuggestedPackages = migratedPackages,
                initialized = true,
                mode = AppScopeMode.Include,
                storedSuggestionRevision = 2,
                suggestionRevision = 4,
            ),
        )
        assertEquals(
            setOf("com.twitter.android"),
            pendingSuggestedPackages(
                suggestedPackageMigrations = PopularAppSuggestions.packagesAddedByRevision,
                storedSuggestionRevision = 3,
                suggestionRevision = 4,
            ),
        )
        assertEquals(
            emptySet<String>(),
            pendingSuggestedPackages(
                suggestedPackageMigrations = PopularAppSuggestions.packagesAddedByRevision,
                storedSuggestionRevision = 4,
                suggestionRevision = 4,
            ),
        )
        assertEquals(
            sortedSetOf("org.telegram.messenger"),
            mergeSuggestedPackages(
                currentPackages = setOf("org.telegram.messenger"),
                suggestedPackages = emptySet(),
                newlySuggestedPackages = emptySet(),
                initialized = true,
                mode = AppScopeMode.Include,
                storedSuggestionRevision = 4,
                suggestionRevision = 4,
            ),
        )
    }

    @Test
    fun `new suggestions are added once to an initialized include list`() {
        assertEquals(
            sortedSetOf("com.example.current", "com.openai.chatgpt", "notion.id"),
            mergeSuggestedPackages(
                currentPackages = setOf("com.example.current"),
                suggestedPackages = setOf("com.example.current", "com.spotify.music"),
                newlySuggestedPackages = setOf("com.openai.chatgpt", "notion.id"),
                initialized = true,
                mode = AppScopeMode.Include,
                storedSuggestionRevision = 0,
                suggestionRevision = 1,
            ),
        )
        assertEquals(
            sortedSetOf("com.example.current"),
            mergeSuggestedPackages(
                currentPackages = setOf("com.example.current"),
                suggestedPackages = emptySet(),
                newlySuggestedPackages = setOf("com.openai.chatgpt"),
                initialized = true,
                mode = AppScopeMode.Include,
                storedSuggestionRevision = 1,
                suggestionRevision = 1,
            ),
        )
    }

    @Test
    fun `exclude mode does not add new suggestions to the direct bypass list`() {
        assertEquals(
            sortedSetOf("com.example.direct"),
            mergeSuggestedPackages(
                currentPackages = setOf("com.example.direct"),
                suggestedPackages = emptySet(),
                newlySuggestedPackages = setOf("com.openai.chatgpt"),
                initialized = true,
                mode = AppScopeMode.Exclude,
                storedSuggestionRevision = 0,
                suggestionRevision = 1,
            ),
        )
    }

    @Test
    fun `fresh initialization keeps all installed suggestions`() {
        assertEquals(
            sortedSetOf("com.openai.chatgpt", "com.spotify.music"),
            mergeSuggestedPackages(
                currentPackages = emptySet(),
                suggestedPackages = setOf("com.openai.chatgpt", "com.spotify.music"),
                newlySuggestedPackages = setOf("com.openai.chatgpt"),
                initialized = false,
                mode = AppScopeMode.Include,
                storedSuggestionRevision = 0,
                suggestionRevision = 1,
            ),
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
