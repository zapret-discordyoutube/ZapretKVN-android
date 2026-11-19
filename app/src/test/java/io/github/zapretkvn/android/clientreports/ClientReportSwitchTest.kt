package io.github.zapretkvn.android.clientreports

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientReportSwitchTest {
    @Test
    fun reportsAreOnUnlessATestRunTurnsThemOff() {
        assertTrue(ClientReportSwitch.enabled(null))
        assertTrue(ClientReportSwitch.enabled("on"))
        assertFalse(ClientReportSwitch.enabled("off"))
        assertFalse(ClientReportSwitch.enabled(" OFF "))
        assertFalse(ClientReportSwitch.enabled("0"))
    }
}
