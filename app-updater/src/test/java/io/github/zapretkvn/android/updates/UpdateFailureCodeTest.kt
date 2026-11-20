package io.github.zapretkvn.android.updates

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateFailureCodeTest {
    @Test
    fun aFailedStepIsNamedWithoutItsText() {
        assertEquals("check_net", UpdateController.failureCode("check", IOException()))
        assertEquals("download_net", UpdateController.failureCode("download", UpdateException("x", retryViaVpn = true)))
        assertEquals("install", UpdateController.failureCode("install", UpdateException("x")))
    }
}
