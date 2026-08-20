package io.github.zapretkvn.android.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChaCha20Poly1305Test {

    /** RFC 8439, §2.8.2 — эталонный вектор AEAD_CHACHA20_POLY1305. */
    @Test
    fun `rfc 8439 vector decrypts`() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val ciphertext = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )
        val expected = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
                "for the future, sunscreen would be it."
            ).toByteArray(Charsets.UTF_8)

        assertArrayEquals(expected, ChaCha20Poly1305.decrypt(key, nonce, ciphertext, aad))
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val ciphertext = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )

        assertThrows(ChaCha20Poly1305.AuthenticationException::class.java) {
            ChaCha20Poly1305.decrypt(key, nonce, ciphertext)
        }
    }

    /** RFC 8439, §2.4.2 — поток ключа при counter = 1. */
    @Test
    fun `rfc 8439 keystream matches`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000090000004a00000000")
        val plaintext = ByteArray(64)
        val expected = hex(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
        )

        assertArrayEquals(expected, ChaCha20Poly1305.chacha20(key, nonce, plaintext, 1))
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
