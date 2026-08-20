package io.github.zapretkvn.android.importer

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Расшифровка ссылок `happ://crypt*` клиента Happ.
 *
 * Ссылка-контейнер прячет внутри одну строку — как правило, URL подписки.
 * Поддержаны все пять поколений формата:
 *
 * - `crypt` — RSA-1024, PKCS#1 v1.5;
 * - `crypt2`…`crypt4` — RSA-4096, PKCS#1 v1.5;
 * - `crypt5` — RSA-4096 поверх ChaCha20-Poly1305 с байтовыми перестановками.
 *
 * В первых четырёх поколениях шифротекст режется на блоки размером с модуль ключа,
 * каждый расшифровывается отдельно, результаты склеиваются. В пятом поколении ключ
 * выбирается по маркеру, извлекаемому из самого payload.
 *
 * Ключевой материал лежит в ресурсе `happ_keys.txt` рядом с этим классом.
 */
internal object HappCrypt {

    /** Схемы в порядке убывания длины префикса, чтобы `crypt5` не перехватил `crypt`. */
    private val CRYPT_SCHEMES: List<Pair<String, Int>> = listOf(
        "happ://crypt5/" to 4,
        "happ://crypt4/" to 3,
        "happ://crypt3/" to 2,
        "happ://crypt2/" to 1,
        "happ://crypt/" to 0,
    )

    private const val CRYPT5_NONCE_SIZE = 12
    private const val CRYPT5_SALT_SIZE = 8

    /** Смещение соли в salted-раскладке: nonce(12) + tag(2). */
    private const val CRYPT5_SALT_OFFSET = 14
    private const val MARKER_SIZE = 4
    private const val KEYS_RESOURCE = "/io/github/zapretkvn/android/importer/happ_keys.txt"

    private val keys: HappKeyTable by lazy(::loadKeys)

    fun isCryptLink(value: String): Boolean {
        val lowered = value.trim().lowercase()
        return CRYPT_SCHEMES.any { (prefix, _) -> lowered.startsWith(prefix) }
    }

    /** Расшифровать ссылку и вернуть открытый текст. */
    fun decrypt(value: String): String {
        val text = value.trim()
        val lowered = text.lowercase()
        for ((prefix, ordinal) in CRYPT_SCHEMES) {
            if (!lowered.startsWith(prefix)) continue
            val payload = text.substring(prefix.length).trim()
            if (payload.isEmpty()) {
                throw ImportException("Ссылка Happ не содержит зашифрованных данных.")
            }
            return if (ordinal == 4) decryptCrypt5(payload) else decryptCrypt1to4(payload, ordinal)
        }
        throw ImportException("Ссылка не является happ://crypt.")
    }

    private fun decryptCrypt1to4(payload: String, ordinal: Int): String {
        val key = loadKey(
            // Индекс поколения, а не позиция в списке: пропуск строки в таблице
            // иначе расшифровывал бы ссылку соседним ключом.
            keys.generations[ordinal]
                ?: throw ImportException("Ключ Happ для этого поколения отсутствует."),
        )
        val ciphertext = decodeLooseBase64(payload)
        val blockSize = key.modulus.bitLength() / 8
        if (ciphertext.isEmpty() || ciphertext.size % blockSize != 0) {
            throw ImportException("Длина зашифрованных данных не кратна размеру ключа Happ.")
        }
        val plaintext = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < ciphertext.size) {
            val block = ciphertext.copyOfRange(offset, offset + blockSize)
            plaintext.write(
                runCatching { rsaDecrypt(key, block) }.getOrElse {
                    throw ImportException("Ссылка зашифрована ключом Happ, которого нет в таблице.")
                },
            )
            offset += blockSize
        }
        return decodeText(plaintext.toByteArray())
    }

    private fun decryptCrypt5(payload: String): String {
        val swapped = swapBlockHalves(latin1Bytes(payload))
        if (swapped.size < MARKER_SIZE * 2 + CRYPT5_NONCE_SIZE) {
            throw ImportException("Ссылка happ://crypt5 слишком короткая.")
        }
        val marker = String(
            swapped.copyOf(MARKER_SIZE) + swapped.copyOfRange(swapped.size - MARKER_SIZE, swapped.size),
            Charsets.ISO_8859_1,
        )
        val encodedKey = keys.crypt5[marker]
            ?: throw ImportException(
                "Ключ Happ для этой ссылки неизвестен (маркер $marker). " +
                    "Скорее всего, ссылка выпущена более новой версией Happ.",
            )
        val key = loadKey(encodedKey)
        val body = swapped.copyOfRange(MARKER_SIZE, swapped.size - MARKER_SIZE)

        // Раскладку тела различает первый байт после nonce: цифра — legacy, иначе salted.
        // Порядок попыток задаётся эвристикой, но пробуются обе.
        val saltedFirst = !isDigit(body, CRYPT5_NONCE_SIZE)
        var firstError: Exception? = null
        for (salted in listOf(saltedFirst, !saltedFirst)) {
            try {
                return decryptCrypt5Body(body, key, salted)
            } catch (error: Exception) {
                if (firstError == null) firstError = error
            }
        }
        throw ImportException("Не удалось расшифровать ссылку happ://crypt5.", firstError)
    }

    private fun decryptCrypt5Body(body: ByteArray, key: RSAPrivateKey, salted: Boolean): String {
        val nonce = body.copyOf(CRYPT5_NONCE_SIZE)
        val salt: ByteArray
        val cursor: Int
        if (salted) {
            if (body.size < CRYPT5_SALT_OFFSET + CRYPT5_SALT_SIZE) {
                throw ImportException("Тело happ://crypt5 обрезано.")
            }
            salt = body.copyOfRange(CRYPT5_SALT_OFFSET, CRYPT5_SALT_OFFSET + CRYPT5_SALT_SIZE)
            cursor = CRYPT5_SALT_OFFSET + CRYPT5_SALT_SIZE
        } else {
            salt = ByteArray(0)
            cursor = CRYPT5_NONCE_SIZE
        }

        var lengthEnd = cursor
        while (isDigit(body, lengthEnd)) lengthEnd++
        if (lengthEnd == cursor) {
            throw ImportException("В теле happ://crypt5 нет длины сегмента.")
        }
        val segmentLength = String(body.copyOfRange(cursor, lengthEnd), Charsets.ISO_8859_1)
            .toIntOrNull()
            ?: throw ImportException("В теле happ://crypt5 нет длины сегмента.")

        // Байт-разделитель произвольный: он пропускается по позиции, а не сравнивается.
        val packed = body.copyOfRange(lengthEnd, body.size)
        if (packed.isEmpty() || segmentLength > packed.size - 1) {
            throw ImportException("Тело happ://crypt5 обрезано.")
        }
        val segment = packed.copyOfRange(1, 1 + segmentLength)
        val rsaCiphertext = packed.copyOfRange(1 + segmentLength, packed.size)

        var chachaKey = decodeLooseBase64(
            String(
                swapPairs(rsaDecrypt(key, decodeLooseBase64(latin1String(rsaCiphertext)))),
                Charsets.ISO_8859_1,
            ),
        )
        if (chachaKey.size != ChaCha20Poly1305.KEY_SIZE) {
            throw ImportException("Ключ ChaCha20 в ссылке happ://crypt5 имеет неверный размер.")
        }
        if (salt.isNotEmpty()) {
            chachaKey = ByteArray(chachaKey.size) { index ->
                (chachaKey[index].toInt() xor salt[index % salt.size].toInt()).toByte()
            }
        }

        val opened = try {
            ChaCha20Poly1305.decrypt(chachaKey, nonce, decodeLooseBase64(latin1String(segment)))
        } catch (error: ChaCha20Poly1305.AuthenticationException) {
            throw ImportException("Проверка целостности ссылки happ://crypt5 не прошла.", error)
        }
        return decodeText(decodeLooseBase64(latin1String(swapPairs(opened))))
    }

    /** `AB` -> `BA` в каждой паре байт. Операция обратна самой себе. */
    private fun swapPairs(data: ByteArray): ByteArray {
        val buffer = data.copyOf()
        var index = 0
        while (index < buffer.size - 1) {
            val first = buffer[index]
            buffer[index] = buffer[index + 1]
            buffer[index + 1] = first
            index += 2
        }
        return buffer
    }

    /** `ABCD` -> `CDAB` в каждом блоке из 4 байт; хвост короче блока не трогается. */
    private fun swapBlockHalves(data: ByteArray): ByteArray {
        val buffer = data.copyOf()
        var index = 0
        val end = buffer.size - buffer.size % 4
        while (index < end) {
            val first = buffer[index]
            val second = buffer[index + 1]
            buffer[index] = buffer[index + 2]
            buffer[index + 1] = buffer[index + 3]
            buffer[index + 2] = first
            buffer[index + 3] = second
            index += 4
        }
        return buffer
    }

    private fun isDigit(data: ByteArray, index: Int): Boolean =
        index < data.size && data[index] >= 48 && data[index] <= 57

    private fun rsaDecrypt(key: RSAPrivateKey, data: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, key)
            doFinal(data)
        }

    private fun loadKey(encoded: String): RSAPrivateKey = runCatching {
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
            as RSAPrivateKey
    }.getOrElse { throw ImportException("Ключ Happ повреждён.", it) }

    /** Декодировать base64, принимая url-safe алфавит и отсутствующий padding. */
    private fun decodeLooseBase64(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrElse {
            throw ImportException("Повреждённые данные ссылки Happ (base64).", it)
        }
    }

    private fun latin1Bytes(value: String): ByteArray {
        // Аналог "latin-1" с ignore: символы вне диапазона пропускаются.
        val buffer = ByteArray(value.length)
        var size = 0
        for (character in value) {
            if (character.code <= 0xff) buffer[size++] = character.code.toByte()
        }
        return buffer.copyOf(size)
    }

    private fun latin1String(data: ByteArray): String = String(data, Charsets.ISO_8859_1)

    private fun decodeText(data: ByteArray): String {
        val text = String(data, Charsets.UTF_8)
        if (text.contains('�')) {
            throw ImportException("Расшифрованные данные Happ не являются текстом.")
        }
        return text.trim()
    }

    private data class HappKeyTable(
        val generations: Map<Int, String>,
        val crypt5: Map<String, String>,
    )

    private fun loadKeys(): HappKeyTable {
        // Абсолютный путь: R8 переименовывает классы, а относительный путь
        // разрешается по пакету класса и после обфускации не нашёл бы ресурс.
        val stream = HappCrypt::class.java.getResourceAsStream(KEYS_RESOURCE)
            ?: throw ImportException("Ключи Happ не найдены в сборке.")
        val generations = linkedMapOf<Int, String>()
        val crypt5 = linkedMapOf<String, String>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val parts = trimmed.split(':', limit = 3)
                if (parts.size != 3) return@forEach
                when (parts[0]) {
                    "gen" -> parts[1].toIntOrNull()?.let { generations[it] = parts[2] }
                    "crypt5" -> crypt5[parts[1]] = parts[2]
                }
            }
        }
        return HappKeyTable(generations, crypt5)
    }
}
