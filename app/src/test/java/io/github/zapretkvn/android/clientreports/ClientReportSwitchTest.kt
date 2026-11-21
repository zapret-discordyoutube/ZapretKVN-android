package io.github.zapretkvn.android.clientreports

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientReportSwitchTest {
    @Test
    fun reportsAreOnUnlessATestRunTurnsThemOff() {
        assertTrue(ClientReportSwitch.enabled(null, debug = false))
        assertTrue(ClientReportSwitch.enabled("on", debug = false))
        assertFalse(ClientReportSwitch.enabled("off", debug = false))
        assertFalse(ClientReportSwitch.enabled(" OFF ", debug = false))
        assertFalse(ClientReportSwitch.enabled("0", debug = false))
    }

    @Test
    fun aDebugBuildNeverSends() {
        assertFalse(ClientReportSwitch.enabled(null, debug = true))
        assertFalse(ClientReportSwitch.enabled("on", debug = true))
        // These unit tests run on the debug variant: the default is off here.
        assertFalse(ClientReportSwitch.enabled())
    }
}
