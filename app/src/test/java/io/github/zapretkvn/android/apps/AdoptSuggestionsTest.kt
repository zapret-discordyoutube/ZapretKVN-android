package io.github.zapretkvn.android.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 15.10.2026: Telegram-клиент, поставленный после первого запуска, оставался вне VPN —
 * пользователь видел «один Telegram работает, второй нет».
 */
class AdoptSuggestionsTest {
    private val telegram = "org.telegram.messenger"
    private val zastogram = "org.zastogram.messenger"
    private val chrome = "com.android.chrome"

    @Test
    fun `a recommended app installed later joins the vpn`() {
        val result = adoptSuggestions(
            installedSuggested = setOf(telegram, zastogram),
            allowed = setOf(telegram),
            blocked = emptySet(),
            offered = setOf(telegram),
            mode = AppScopeMode.Include,
        )
        assertEquals(setOf(zastogram), result.adopted)
        assertEquals(setOf(telegram, zastogram), result.allowed)
        assertEquals(setOf(telegram, zastogram), result.offered)
    }

    @Test
    fun `an app the user unchecked is not added back`() {
        val result = adoptSuggestions(
            installedSuggested = setOf(telegram, chrome),
            allowed = setOf(telegram),
            blocked = emptySet(),
            offered = setOf(telegram, chrome),
            mode = AppScopeMode.Include,
        )
        assertTrue(result.adopted.isEmpty())
        assertEquals(setOf(telegram), result.allowed)
    }

    @Test
    fun `first run of the new version adds recommended apps missed before`() {
        val result = adoptSuggestions(
            installedSuggested = setOf(telegram, zastogram),
            allowed = setOf(telegram),
            blocked = emptySet(),
            offered = null,
            mode = AppScopeMode.Include,
        )
        assertEquals(setOf(zastogram), result.adopted)
        assertEquals(setOf(telegram, zastogram), result.offered)
    }

    @Test
    fun `a blocked app is never added`() {
        val result = adoptSuggestions(
            installedSuggested = setOf(zastogram),
            allowed = emptySet(),
            blocked = setOf(zastogram),
            offered = emptySet(),
            mode = AppScopeMode.Include,
        )
        assertTrue(result.adopted.isEmpty())
        assertTrue(zastogram !in result.allowed)
    }

    @Test
    fun `exclude mode only remembers the app because it already uses the vpn`() {
        val result = adoptSuggestions(
            installedSuggested = setOf(zastogram),
            allowed = emptySet(),
            blocked = emptySet(),
            offered = emptySet(),
            mode = AppScopeMode.Exclude,
        )
        assertTrue(result.adopted.isEmpty())
        assertTrue(result.allowed.isEmpty())
        assertEquals(setOf(zastogram), result.offered)
    }

    @Test
    fun `the message names adopted apps by label`() {
        val apps = listOf(
            InstalledApp(zastogram, "ZaStoGram", system = false, enabled = true, suggestion = "ZaStoGram"),
            InstalledApp(telegram, "Telegram", system = false, enabled = true, suggestion = "Telegram"),
        )
        assertNull(adoptedAppsMessage(emptySet(), apps))
        val message = checkNotNull(adoptedAppsMessage(setOf(zastogram), apps))
        assertTrue(message, message.contains("ZaStoGram"))
        assertTrue(message, !message.contains("Telegram,"))
    }
}
