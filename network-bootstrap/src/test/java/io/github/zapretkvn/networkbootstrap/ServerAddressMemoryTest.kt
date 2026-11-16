package io.github.zapretkvn.networkbootstrap

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressMemoryTest {
    @After
    fun reset() = ServerAddressMemory.resetForTest()

    @Test
    fun `an answer replaces only its own address family`() {
        ServerAddressMemory.resetForTest()
        ServerAddressMemory.record("Fi.Example.", listOf("203.0.113.7"))
        ServerAddressMemory.record("fi.example", listOf("2001:db8::7"))
        assertEquals(setOf("203.0.113.7", "2001:db8::7"), ServerAddressMemory.addressesOf("fi.example"))
        ServerAddressMemory.record("fi.example", listOf("198.51.100.9"))
        assertEquals(setOf("198.51.100.9", "2001:db8::7"), ServerAddressMemory.addressesOf("FI.example"))
        ServerAddressMemory.record("fi.example", emptyList())
        assertEquals(2, ServerAddressMemory.addressesOf("fi.example").size)
        assertTrue(ServerAddressMemory.addressesOf(null).isEmpty())
    }

    @Test
    fun `remembered answers come back from the file`() {
        ServerAddressMemory.resetForTest()
        val file = Files.createTempFile("server-addresses", ".txt").toFile()
        try {
            file.writeText("a.example\t203.0.113.7,2001:db8::7\nbroken-line\n\tno-host\n")
            ServerAddressMemory.attach(file)
            assertEquals(setOf("203.0.113.7", "2001:db8::7"), ServerAddressMemory.addressesOf("a.example"))
            assertTrue(ServerAddressMemory.addressesOf("broken-line").isEmpty())
        } finally {
            ServerAddressMemory.resetForTest()
            file.delete()
        }
    }
}
