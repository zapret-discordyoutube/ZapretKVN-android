package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.ui.UiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSelectionTest {
    private val defaults = setOf(
        ServiceId.YouTube, ServiceId.ChatGpt, ServiceId.Gemini,
        ServiceId.Instagram, ServiceId.Discord, ServiceId.Telegram,
    )

    @Test
    fun defaultsAreSixOnAndClaudeAndXOff() {
        assertEquals(defaults, ServiceCatalog.defaultIds)
        assertFalse(ServiceId.Claude in ServiceCatalog.defaultIds)
        assertFalse(ServiceId.X in ServiceCatalog.defaultIds)
        assertEquals(defaults, UiSettings().serviceChecks)
    }

    @Test
    fun missingStoredValueMeansDefaultsWithoutMigration() {
        assertEquals(defaults, ServiceSelection.parse(null))
    }

    @Test
    fun storedTokensRoundTripAndUnknownOnesAreIgnored() {
        val chosen = setOf(ServiceId.Claude, ServiceId.X, ServiceId.YouTube)
        assertEquals(chosen, ServiceSelection.parse(ServiceSelection.tokens(chosen)))
        assertEquals(
            setOf(ServiceId.Discord),
            ServiceSelection.parse(setOf("discord", "removed-in-the-future")),
        )
    }

    @Test
    fun storedSetWithoutAnyKnownServiceFallsBackToDefaults() {
        assertEquals(defaults, ServiceSelection.parse(emptySet()))
        assertEquals(defaults, ServiceSelection.parse(setOf("nope")))
    }

    @Test
    fun toggleAddsRemovesAndNeverEmptiesTheSelection() {
        val one = setOf(ServiceId.Telegram)
        assertEquals(setOf(ServiceId.Telegram, ServiceId.X), ServiceSelection.toggle(one, ServiceId.X, true))
        assertEquals(one, ServiceSelection.toggle(one, ServiceId.Telegram, false))
        assertEquals(
            setOf(ServiceId.Telegram),
            ServiceSelection.toggle(setOf(ServiceId.Telegram, ServiceId.X), ServiceId.X, false),
        )
    }

    @Test
    fun resolveKeepsCatalogOrderAndOnlyPlansSelectedServices() {
        val resolved = ServiceCatalog.resolve(setOf(ServiceId.X, ServiceId.YouTube))
        assertEquals(listOf(ServiceId.YouTube, ServiceId.X), resolved.map { it.id })
        assertTrue(ServiceCatalog.resolve(setOf(ServiceId.Claude)).single().id == ServiceId.Claude)
    }

    @Test
    fun resolveOfAnEmptySelectionIsTheDefaultCatalog() {
        assertEquals(ServiceCatalog.defaults, ServiceCatalog.resolve(emptySet()))
    }
}
