package io.github.zapretkvn.android.importer

import java.math.BigInteger
import java.security.MessageDigest

/**
 * AEAD ChaCha20-Poly1305 по RFC 8439.
 *
 * Платформенный `javax.crypto` умеет этот алгоритм только с API 28, а приложение
 * поддерживает API 26, поэтому расшифровка ссылок `happ://crypt5` считается здесь.
 * Данные короткие (одна ссылка подписки), поэтому Poly1305 использует BigInteger.
 */
internal object ChaCha20Poly1305 {

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16

    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val POLY1305_PRIME: BigInteger =
        TWO.pow(130).subtract(BigInteger.valueOf(5))
    private val POLY1305_CLAMP: BigInteger =
        BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
    private val TAG_MODULUS: BigInteger = TWO.pow(128)

    class AuthenticationException : Exception("Poly1305 tag mismatch")

    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(key.size == KEY_SIZE) { "ChaCha20 key must be $KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "ChaCha20 nonce must be $NONCE_SIZE bytes" }
        if (ciphertextWithTag.size < TAG_SIZE) throw AuthenticationException()
        val ciphertext = ciphertextWithTag.copyOf(ciphertextWithTag.size - TAG_SIZE)
        val tag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - TAG_SIZE, ciphertextWithTag.size)
        val macKey = chacha20Block(key, nonce, counter = 0).copyOf(KEY_SIZE)
        val expected = poly1305(macKey, macData(aad, ciphertext))
        // Сравнение за постоянное время: contentEquals выходит на первом различии.
        if (!MessageDigest.isEqual(expected, tag)) throw AuthenticationException()
        return chacha20(key, nonce, ciphertext, initialCounter = 1)
    }

    fun chacha20(
        key: ByteArray,
        nonce: ByteArray,
        data: ByteArray,
        initialCounter: Int,
    ): ByteArray {
        val output = ByteArray(data.size)
        var offset = 0
        var counter = initialCounter
        while (offset < data.size) {
            val block = chacha20Block(key, nonce, counter)
            val length = minOf(64, data.size - offset)
            for (index in 0 until length) {
                output[offset + index] = (data[offset + index].toInt() xor block[index].toInt()).toByte()
            }
            offset += length
            counter++
        }
        return output
    }

    private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (index in 0 until 8) state[4 + index] = readLittleEndianInt(key, index * 4)
        state[12] = counter
        for (index in 0 until 3) state[13 + index] = readLittleEndianInt(nonce, index * 4)

        val working = state.copyOf()
        repeat(10) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        val block = ByteArray(64)
        for (index in 0 until 16) {
            writeLittleEndianInt(block, index * 4, working[index] + state[index])
        }
        return block
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]
        state[d] = Integer.rotateLeft(state[d] xor state[a], 16)
        state[c] += state[d]
        state[b] = Integer.rotateLeft(state[b] xor state[c], 12)
        state[a] += state[b]
        state[d] = Integer.rotateLeft(state[d] xor state[a], 8)
        state[c] += state[d]
        state[b] = Integer.rotateLeft(state[b] xor state[c], 7)
    }

    private fun macData(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val output = ByteArray(
            padded(aad.size) + padded(ciphertext.size) + 16,
        )
        aad.copyInto(output, 0)
        ciphertext.copyInto(output, padded(aad.size))
        val lengthsOffset = padded(aad.size) + padded(ciphertext.size)
        writeLittleEndianLong(output, lengthsOffset, aad.size.toLong())
        writeLittleEndianLong(output, lengthsOffset + 8, ciphertext.size.toLong())
        return output
    }

    private fun padded(size: Int): Int = size + ((16 - size % 16) % 16)

    private fun poly1305(macKey: ByteArray, message: ByteArray): ByteArray {
        val r = littleEndianNumber(macKey.copyOf(16)).and(POLY1305_CLAMP)
        val s = littleEndianNumber(macKey.copyOfRange(16, 32))
        var accumulator = BigInteger.ZERO
        var offset = 0
        while (offset < message.size) {
            val length = minOf(16, message.size - offset)
            val chunk = message.copyOfRange(offset, offset + length)
            val block = littleEndianNumber(chunk).add(TWO.pow(8 * length))
            accumulator = accumulator.add(block).multiply(r).mod(POLY1305_PRIME)
            offset += length
        }
        val tag = accumulator.add(s).mod(TAG_MODULUS)
        val output = ByteArray(TAG_SIZE)
        val bytes = tag.toByteArray()
        // BigInteger отдаёт big-endian со знаковым байтом; тег нужен little-endian.
        var index = 0
        for (position in bytes.indices.reversed()) {
            if (index >= TAG_SIZE) break
            output[index++] = bytes[position]
        }
        return output
    }

    private fun littleEndianNumber(data: ByteArray): BigInteger {
        val reversed = ByteArray(data.size + 1)
        for (index in data.indices) reversed[data.size - index] = data[index]
        return BigInteger(reversed)
    }

    private fun readLittleEndianInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLittleEndianInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value ushr 8) and 0xff).toByte()
        data[offset + 2] = ((value ushr 16) and 0xff).toByte()
        data[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun writeLittleEndianLong(data: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            data[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
        }
    }
}
