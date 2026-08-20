package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProcfsConnectionOwnerTest {
    @Test
    fun ipv4KeysCoverNativeIpv4AndMappedIpv6ProcRows() {
        val keys = AndroidProcfsConnectionOwner.localKeys("192.168.232.2", 443)

        assertTrue("02E8A8C0:01BB" in keys)
        assertTrue("0000000000000000FFFF000002E8A8C0:01BB" in keys)
    }

    @Test
    fun parserReturnsUidOnlyForTheExactLocalSocket() {
        val header =
            "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"
        val row =
            "0: 0000000000000000FFFF000002E8A8C0:9687 " +
                "0000000000000000FFFF0000BC01FB8E:146C 01 00000000:00000000 " +
                "00:00000000 00000000 10012 0 183121"

        assertEquals(
            10012,
            AndroidProcfsConnectionOwner.parseUid(
                sequenceOf(header, row),
                setOf("0000000000000000FFFF000002E8A8C0:9687"),
            ),
        )
        assertNull(
            AndroidProcfsConnectionOwner.parseUid(
                sequenceOf(header, row),
                setOf("0000000000000000FFFF000002E8A8C0:9688"),
            ),
        )
    }
}
