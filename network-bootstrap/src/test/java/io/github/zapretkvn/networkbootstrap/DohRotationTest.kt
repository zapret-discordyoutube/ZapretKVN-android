package io.github.zapretkvn.networkbootstrap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DohRotationTest {
    private val servers = listOf("8.8.8.8", "1.1.1.1", "94.140.14.14")

    @After
    fun reset() = DohRotation.reset()

    @Test
    fun `asks one resolver at a time and stops at the first answer`() {
        val asked = mutableListOf<String>()
        val round = DohRotation.ask(servers, { it == "ok" }) { server ->
            asked += server
            if (server == "1.1.1.1") "ok" else "fail"
        }
        assertEquals("ok", round.winner)
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), asked)
    }

    @Test
    fun `the last resolver that answered goes first next time`() {
        DohRotation.ask(servers, { it == "ok" }) { if (it == "94.140.14.14") "ok" else "fail" }
        val asked = mutableListOf<String>()
        DohRotation.ask(servers, { it == "ok" }) { server -> asked += server; "ok" }
        assertEquals(listOf("94.140.14.14"), asked)
    }

    @Test
    fun `no winner returns every answer for the merge policy`() {
        val round = DohRotation.ask(servers, { false }) { "missing:$it" }
        assertNull(round.winner)
        assertEquals(servers.map { "missing:$it" }, round.all)
    }
}
