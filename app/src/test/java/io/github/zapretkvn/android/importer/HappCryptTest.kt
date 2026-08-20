package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.importer.HappVectors.int
import io.github.zapretkvn.android.importer.HappVectors.string
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HappCryptTest {

    /** Векторы совпадают с эталонными из десктопного клиента: пять поколений и обе раскладки crypt5. */
    @Test
    fun `reference vectors decrypt`() {
        val vectors = loadVectors()
        assertEquals(6, vectors.size)
        vectors.forEach { vector ->
            val name = vector.string("name").orEmpty()
            val plaintext = HappCrypt.decrypt(vector.string("link").orEmpty())
            val expected = vector.string("expected")
            if (expected != null) {
                assertEquals(name, expected, plaintext)
            } else {
                // Открытый текст этих векторов — произвольная строка, которую незачем
                // держать в репозитории; закрепляем её длиной и хешем.
                assertEquals(name, vector.int("expected_len"), plaintext.length)
                assertEquals(name, vector.string("expected_sha256"), sha256(plaintext))
            }
        }
    }

    @Test
    fun `both crypt5 layouts are covered`() {
        val names = loadVectors().mapNotNull { it.string("name") }.toSet()
        assertTrue(names.any { it.startsWith("crypt5-legacy") })
        assertTrue(names.any { it.startsWith("crypt5-salted") })
    }

    @Test
    fun `crypt5 prefix is not captured by the crypt prefix`() {
        assertTrue(HappCrypt.isCryptLink("happ://crypt5/payload"))
        assertTrue(HappCrypt.isCryptLink("HAPP://CRYPT/payload"))
        assertFalse(HappCrypt.isCryptLink("happ://add/https://sub.example/profile"))
    }

    @Test
    fun `unknown crypt5 marker is reported`() {
        val error = assertThrows(ImportException::class.java) {
            HappCrypt.decrypt("happ://crypt5/${"z".repeat(64)}")
        }
        assertTrue(error.message.orEmpty().contains("маркер"))
    }

    @Test
    fun `empty payload is rejected`() {
        assertThrows(ImportException::class.java) { HappCrypt.decrypt("happ://crypt/") }
    }

    @Test
    fun `truncated crypt1 ciphertext is rejected`() {
        val error = assertThrows(ImportException::class.java) {
            HappCrypt.decrypt("happ://crypt/QUJDRA==")
        }
        assertTrue(error.message.orEmpty().contains("размеру ключа"))
    }

    private fun loadVectors(): List<JsonObject> = HappVectors.all()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
