package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DnsAddressBookTest {
    private val line = "INFO[0018] [70602119 83ms] dns: exchanged A rr1.sn-1giz7n7l.googlevideo.com. 193 IN A 74.125.11.70"

    @Test
    fun `an address is mapped to the googlevideo name that DNS answered`() {
        val book = DnsAddressBook()
        book.observe(line)
        assertEquals("rr1.sn-1giz7n7l.googlevideo.com", book.nameFor("74.125.11.70:443"))
        assertNull(book.nameFor("74.125.11.71:443"))
        assertNull(book.nameFor(""))
    }

    @Test
    fun `ipv6 answers and bracketed destinations work`() {
        val book = DnsAddressBook()
        book.observe("INFO[1] [1 5ms] dns: exchanged AAAA rr2.sn-x.googlevideo.com. 60 IN AAAA 2a00:1450:4001:80b::200e")
        assertEquals("rr2.sn-x.googlevideo.com", book.nameFor("[2a00:1450:4001:80b::200e]:443"))
    }

    @Test
    fun `only googlevideo names are remembered and cname lines are ignored`() {
        val book = DnsAddressBook()
        book.observe("INFO[1] [1 5ms] dns: exchanged A youtubei.googleapis.com. 171 IN A 172.217.112.4")
        book.observe("INFO[1] [1 5ms] dns: exchanged CNAME rr4---sn-x.googlevideo.com. 0 IN CNAME rr4.sn-x.googlevideo.com.")
        book.observe("INFO[1] [1 5ms] dns: exchanged A notgooglevideo.com. 5 IN A 1.1.1.1")
        assertEquals(0, book.size())
        assertNull(book.nameFor("172.217.112.4:443"))
    }

    @Test
    fun `enrich fills a missing name and leaves a named flow alone`() {
        val book = DnsAddressBook()
        book.observe(line)
        val unnamed = FlowSnap("a", "tcp", "", "74.125.11.70:443", "app", "hy2", 1, 0, 1, 1)
        assertEquals("rr1.sn-1giz7n7l.googlevideo.com", book.enrich(unnamed).domain)
        val named = unnamed.copy(domain = "other.example.com")
        assertSame(named, book.enrich(named))
        val unknown = unnamed.copy(destination = "9.9.9.9:443")
        assertSame(unknown, book.enrich(unknown))
    }

    @Test
    fun `an entry expires and the book stays bounded`() {
        var now = 1_000L
        val book = DnsAddressBook({ now }, maxEntries = 3, lifetimeMillis = 10_000)
        book.observe(line)
        now += 10_001
        assertNull("expired", book.nameFor("74.125.11.70:443"))
        repeat(10) { i -> book.observe("INFO[1] [1 5ms] dns: exchanged A rr$i.sn-x$i.googlevideo.com. 5 IN A 10.0.0.$i") }
        assertEquals(3, book.size())
        assertEquals("the newest survive", "rr9.sn-x9.googlevideo.com", book.nameFor("10.0.0.9:443"))
    }
}
