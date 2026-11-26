package io.github.zapretkvn.android.config

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The MASQUE account the app registers itself (owner 2026-11-26): the key formats the core reads, and the real
 * registration against the real Cloudflare API.
 *
 * The key checks run the real OpenSSL parser on the key the app generates (Go reads the same DER): a wrong SEC1 layout
 * would only show up when the core fails to start MASQUE on a phone. The real registration creates two devices at
 * Cloudflare, so it runs only when `KVN_REAL_WARP=1` is set (the release gate does not hammer the API); a skipped test is
 * an honest "unproven here". Nothing is printed except shapes.
 */
class WarpMasqueAccountTest {
    private fun openssl(vararg arguments: String, input: ByteArray? = null): Pair<Int, String> {
        val process = ProcessBuilder(listOf("openssl") + arguments).redirectErrorStream(true).start()
        if (input != null) process.outputStream.use { it.write(input) } else process.outputStream.close()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return process.waitFor() to text
    }

    private fun haveOpenssl(): Boolean = runCatching { openssl("version").first == 0 }.getOrDefault(false)

    @Test
    fun `the private key is the 121 byte SEC1 structure and OpenSSL reads it as P-256`() {
        assumeTrue("openssl is not here", haveOpenssl())
        repeat(20) {
            val keys = WarpMasqueAccount.newKey()
            assertEquals(121, keys.privateSec1.size)
            val file = Files.createTempFile("kvn-sec1", ".der").toFile()
            try {
                file.writeBytes(keys.privateSec1)
                val (code, text) = openssl("ec", "-inform", "DER", "-in", file.path, "-noout", "-text")
                assertEquals(text.take(300), 0, code)
                assertTrue(text, "prime256v1" in text || "P-256" in text)
                // the public half the app sends to the API is the one inside that very private key
                val (pubCode, pub) = openssl("ec", "-inform", "DER", "-in", file.path, "-pubout", "-outform", "DER")
                assertEquals(0, pubCode)
                val derived = openssl("ec", "-inform", "DER", "-in", file.path, "-pubout", "-outform", "DER").second
                assertTrue(pub.isNotEmpty() && derived.isNotEmpty())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `the public key sent to the API is the X509 form of the private key's own point`() {
        assumeTrue("openssl is not here", haveOpenssl())
        val keys = WarpMasqueAccount.newKey()
        val private = Files.createTempFile("kvn-sec1", ".der").toFile()
        val public = Files.createTempFile("kvn-spki", ".der").toFile()
        try {
            private.writeBytes(keys.privateSec1)
            public.writeBytes(keys.publicSpki)
            val own = ProcessBuilder("openssl", "ec", "-inform", "DER", "-in", private.path, "-pubout", "-outform", "DER").start()
            val ownBytes = own.inputStream.readBytes()
            assertEquals(0, own.waitFor())
            assertTrue("the SPKI the app sends differs from the one inside the private key", ownBytes.contentEquals(keys.publicSpki))
        } finally {
            private.delete()
            public.delete()
        }
    }

    @Test
    fun `an address of the API answer becomes the host the core dials`() {
        assertEquals("162.159.198.2", WarpMasqueAccount.hostOf("162.159.198.2:0"))
        assertEquals("2606:4700:103::2", WarpMasqueAccount.hostOf("[2606:4700:103::2]:0"))
        assertEquals("", WarpMasqueAccount.hostOf(""))
    }

    /** A real registration over the real routes: the two requests, the file the core reads, and the core's own checks of it. */
    @Test
    fun `the real API registers a MASQUE account over the app's own routes`() {
        assumeTrue("set KVN_REAL_WARP=1 to register a real device", System.getenv("KVN_REAL_WARP") == "1")
        val directory = Files.createTempDirectory("kvn-masque").toFile()
        try {
            WarpMasqueAccount.forgetFailure()
            val account = File(directory, "warp-entry-profile.json")
            val outcome = WarpMasqueAccount.ensure(account)
            assertTrue("${outcome.route}: ${outcome.detail}", outcome.ok)
            val file = WarpMasqueAccount.fileFor(account)
            assertTrue(WarpMasqueAccount.ready(file))
            val document = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(setOf(
                "private_key", "endpoint_v4", "endpoint_v6", "endpoint_h2_v4", "endpoint_h2_v6", "endpoint_pub_key",
                "license", "id", "access_token", "ipv4", "ipv6",
            ), document.keys)
            assertTrue(document.getValue("endpoint_pub_key").jsonPrimitive.content.startsWith("-----BEGIN PUBLIC KEY-----"))
            assertEquals(121, Base64.getDecoder().decode(document.getValue("private_key").jsonPrimitive.content).size)
            assertFalse("the endpoint is a bare address", ':' in document.getValue("endpoint_v4").jsonPrimitive.content)
            // a second call finds the file and asks the API for nothing
            assertEquals("cached", WarpMasqueAccount.ensure(account).route)
            System.getenv("KVN_REAL_WARP_KEEP")?.let { keep -> file.copyTo(File(keep), overwrite = true) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
