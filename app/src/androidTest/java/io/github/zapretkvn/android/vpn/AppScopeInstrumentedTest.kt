package io.github.zapretkvn.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.ZapretApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppScopeInstrumentedTest {
    private val application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as ZapretApplication
    private val container
        get() = application.container

    @After
    fun clearSelection() = runBlocking {
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.replaceBlocklist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
    }

    @Test
    fun allowlistPersistsAndCannotExposeOwnPackage() = runBlocking {
        container.appSelectionStore.replaceAllowlist(
            setOf(application.packageName, SETTINGS_PACKAGE),
        )

        val stored = container.appSelectionStore.selection.first()
        assertEquals(setOf(SETTINGS_PACKAGE), stored.allowedPackages)
        assertTrue(stored.initialized)
        assertFalse(
            container.appCatalog.load().apps.any { it.packageName == application.packageName },
        )
    }

    @Test
    fun defaultsAreAddedOnceButNeverReturnAfterUserClearsThem() = runBlocking {
        container.appSelectionStore.replaceAllowlist(
            packages = emptySet(),
            initialized = false,
        )

        container.appSelectionStore.initializeIfNeeded(setOf(SETTINGS_PACKAGE))
        val initialized = container.appSelectionStore.selection.first()
        assertEquals(setOf(SETTINGS_PACKAGE), initialized.allowedPackages)
        assertTrue(initialized.initialized)

        container.appSelectionStore.setAllowed(SETTINGS_PACKAGE, allowed = false)
        val cleared = container.appSelectionStore.selection.first()
        assertTrue(cleared.allowedPackages.isEmpty())

        container.appSelectionStore.initializeIfNeeded(setOf(SETTINGS_PACKAGE))
        assertTrue(container.appSelectionStore.selection.first().allowedPackages.isEmpty())
    }

    @Test
    fun realPackageManagerPreflightAddsInternalHealthPackage() {
        val added = mutableListOf<String>()

        val result = container.vpnAppScopePreflight.apply(
            userAllowlist = setOf(SETTINGS_PACKAGE),
            sink = added::add,
        )

        assertTrue(result is VpnAppScopeResult.Ready)
        assertEquals(listOf(SETTINGS_PACKAGE, application.packageName), added)
    }

    @Test
    fun excludeModePersistsAndUsesAndroidDisallowedContract() = runBlocking {
        container.appSelectionStore.replaceAllowlist(setOf(SETTINGS_PACKAGE))
        container.appSelectionStore.setMode(AppScopeMode.Exclude)
        val stored = container.appSelectionStore.selection.first()
        val excluded = mutableListOf<String>()

        val result = container.vpnAppScopePreflight.apply(
            selectedPackages = stored.allowedPackages,
            mode = stored.mode,
            allowedSink = AllowedApplicationSink { error("include must not be used") },
            disallowedSink = DisallowedApplicationSink(excluded::add),
        )

        assertEquals(AppScopeMode.Exclude, stored.mode)
        assertEquals(VpnAppScopeResult.Ready(AppScopeMode.Exclude, excluded), result)
        assertEquals(listOf(SETTINGS_PACKAGE), excluded)
    }

    @Test
    fun blocklistCannotOverlapVpnScope() = runBlocking {
        container.appSelectionStore.replaceAllowlist(emptySet(), initialized = false)
        container.appSelectionStore.replaceBlocklist(emptySet())

        container.appSelectionStore.setAllowed(SETTINGS_PACKAGE, allowed = true)
        container.appSelectionStore.setBlocked(SETTINGS_PACKAGE, blocked = true)
        val blocked = container.appSelectionStore.selection.first()
        assertTrue(blocked.allowedPackages.isEmpty())
        assertEquals(setOf(SETTINGS_PACKAGE), blocked.blockedPackages)

        container.appSelectionStore.setAllowed(SETTINGS_PACKAGE, allowed = true)
        val allowed = container.appSelectionStore.selection.first()
        assertEquals(setOf(SETTINGS_PACKAGE), allowed.allowedPackages)
        assertTrue(allowed.blockedPackages.isEmpty())
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
