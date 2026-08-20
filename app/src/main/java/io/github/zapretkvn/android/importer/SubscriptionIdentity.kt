package io.github.zapretkvn.android.importer

/**
 * Совместимость с панелями, которые считают устройства по заголовку `X-HWID`
 * (Remnawave и производные). Заголовки собираются здесь, чтобы unit-тесты
 * работали без Android SDK: всё платформенное приходит через [DeviceIdentity].
 */
enum class SubscriptionClientProfile(val id: String) {
    Zapret("zapret"),
    Happ("happ"),
    Incy("incy"),
    V2RayTun("v2raytun"),
    Custom("custom"),
    ;

    companion object {
        fun parse(value: String?): SubscriptionClientProfile {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == normalized } ?: Custom
        }
    }
}

/** Платформенные признаки устройства, которые уходят в заголовки `X-Device-*`. */
data class DeviceIdentity(
    val os: String = "Android",
    val osVersion: String = "",
    val model: String = "",
    val locale: String = "ru-RU",
)

/** Сохранённый источник обновления профиля вместе с настройками идентификации. */
data class SubscriptionSource(
    val url: String,
    val clientProfile: SubscriptionClientProfile = SubscriptionClientProfile.Zapret,
    val sendHwid: Boolean = false,
    val hwid: String = "",
    val userAgent: String = "",
)

/** Результат разбора ссылки: адрес подписки и профиль клиента, если ссылка его задаёт. */
data class ResolvedSubscriptionSource(
    val url: String,
    val profileHint: SubscriptionClientProfile? = null,
)

object SubscriptionIdentity {

    const val MAX_HWID_LENGTH = 128

    private const val LEGACY_USER_AGENT = "Zapret-KVN-Android"

    private val WRAPPED_SOURCE_PREFIXES = linkedMapOf(
        "happ://add/" to SubscriptionClientProfile.Happ,
        "incy://add/" to SubscriptionClientProfile.Incy,
        "incy://import/" to SubscriptionClientProfile.Incy,
        "v2raytun://import/" to SubscriptionClientProfile.V2RayTun,
    )

    private val DEEP_LINK_SCHEMES = setOf("happ", "incy", "v2raytun")

    private val BASE64_ALPHABET = Regex("[A-Za-z0-9_+/=-]+")

    fun validateHwid(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) throw ImportException("HWID не задан.")
        if (text.length > MAX_HWID_LENGTH || text.any { it.code < 32 || it.code == 127 }) {
            throw ImportException("HWID содержит недопустимые символы или слишком длинный.")
        }
        return text
    }

    fun defaultUserAgent(appVersion: String): String =
        if (appVersion.isBlank()) LEGACY_USER_AGENT else "ZapretKVN/$appVersion"

    fun userAgent(
        profile: SubscriptionClientProfile,
        device: DeviceIdentity,
        appVersion: String,
    ): String = when (profile) {
        SubscriptionClientProfile.Happ -> "Happ/${appVersionOf(profile, appVersion)}"
        SubscriptionClientProfile.Incy -> "INCY/${appVersionOf(profile, appVersion)}/${device.os}"
        SubscriptionClientProfile.V2RayTun -> "v2rayTun/${appVersionOf(profile, appVersion)}"
        // Прежние подписки создавались без выбора профиля и уже известны панелям
        // под этим User-Agent, поэтому базовый профиль его сохраняет.
        SubscriptionClientProfile.Zapret, SubscriptionClientProfile.Custom -> LEGACY_USER_AGENT
    }

    fun requestHeaders(
        source: SubscriptionSource,
        device: DeviceIdentity,
        appVersion: String,
    ): Map<String, String> {
        val profile = source.clientProfile
        val headers = linkedMapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to safeHeaderValue(
                source.userAgent.trim().ifEmpty { userAgent(profile, device, appVersion) },
                "User-Agent",
            ),
        )
        if (profile in EMULATED_PROFILES) {
            headers["Accept"] = "*/*"
            headers["X-App-Version"] = appVersionOf(profile, appVersion)
        }
        if (profile == SubscriptionClientProfile.Incy) {
            headers["X-Client"] = "INCY"
            headers["X-Device-Locale"] = safeHeaderValue(device.locale, "X-Device-Locale")
        }
        if (source.sendHwid) {
            val hwid = validateHwid(source.hwid)
            headers["X-HWID"] = safeHeaderValue(hwid, "X-HWID")
            headers["X-Device-OS"] = safeHeaderValue(device.os.ifBlank { "Android" }, "X-Device-OS")
            headers["X-Ver-OS"] = safeHeaderValue(device.osVersion.ifBlank { "Android" }, "X-Ver-OS")
            headers["X-Device-Model"] =
                safeHeaderValue(device.model.ifBlank { "Android" }, "X-Device-Model")
            if (profile == SubscriptionClientProfile.Incy) headers["X-Device-ID"] = hwid
            if (profile == SubscriptionClientProfile.Happ) {
                headers["X-Device-Locale"] = safeHeaderValue(device.locale, "X-Device-Locale")
            }
        }
        return headers
    }

    /**
     * Панели с лимитом устройств отвечают обычным 404 и называют причину отдельным
     * заголовком. Без их разбора пользователь видит «HTTP 404» и не понимает, что
     * упёрся в лимит устройств.
     */
    fun describeHttpFailure(status: Int, headerNames: Collection<String?>): String {
        val normalized = headerNames.filterNotNull().map { it.lowercase() }.toSet()
        if ("x-hwid-max-devices-reached" in normalized || "x-hwid-limit" in normalized) {
            return "Достигнут лимит устройств подписки. Отвяжите лишнее устройство " +
                "в личном кабинете или у провайдера."
        }
        if ("x-hwid-not-supported" in normalized) {
            return "Провайдер требует идентификатор устройства (HWID). " +
                "Включите отправку HWID в настройках подписки."
        }
        return "Сервер подписки вернул HTTP $status."
    }

    /** Развернуть открытую add/import-ссылку клиента в обычный HTTP(S) URL подписки. */
    fun resolveSource(value: String): ResolvedSubscriptionSource {
        val text = value.trim()
        val lowered = text.lowercase()
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme == "http" || scheme == "https") {
            return ResolvedSubscriptionSource(HttpSubscriptionFetcher.validatedUrl(text))
        }
        if (HappCrypt.isCryptLink(text)) {
            val decrypted = decodeWrappedHttpUrl(HappCrypt.decrypt(text).trim())
            if (isHttpUrl(decrypted)) {
                return ResolvedSubscriptionSource(
                    HttpSubscriptionFetcher.validatedUrl(decrypted),
                    SubscriptionClientProfile.Happ,
                )
            }
            throw ImportException(
                "Расшифрованная ссылка Happ не содержит адрес подписки HTTP/HTTPS.",
            )
        }
        if (scheme in DEEP_LINK_SCHEMES) {
            val body = text.substringAfter("://", missingDelimiterValue = "")
            if (body.substringBefore('?').lowercase().trimStart('/').startsWith("crypt")) {
                throw ImportException(
                    "Зашифрованные proprietary deep links этим клиентом не расшифровываются.",
                )
            }
            val queryUrl = queryParameter(text, "url") ?: queryParameter(text, "data")
            var candidate = queryUrl?.trim().orEmpty()
            var hint = if (candidate.isNotEmpty()) {
                SubscriptionClientProfile.parse(scheme)
            } else {
                null
            }
            if (candidate.isEmpty()) {
                for ((prefix, profile) in WRAPPED_SOURCE_PREFIXES) {
                    if (lowered.startsWith(prefix)) {
                        candidate = urlDecode(text.substring(prefix.length).substringBefore('#'))
                            .trim()
                        hint = profile
                        break
                    }
                }
            }
            if (candidate.isNotEmpty() && hint != null) {
                val decoded = decodeWrappedHttpUrl(candidate)
                if (isHttpUrl(decoded)) {
                    return ResolvedSubscriptionSource(
                        HttpSubscriptionFetcher.validatedUrl(decoded),
                        hint,
                    )
                }
            }
        }
        throw ImportException(
            "URL подписки должен использовать HTTP/HTTPS или открытую add/import-ссылку " +
                "Happ, INCY, v2RayTun.",
        )
    }

    private val EMULATED_PROFILES = setOf(
        SubscriptionClientProfile.Happ,
        SubscriptionClientProfile.Incy,
        SubscriptionClientProfile.V2RayTun,
    )

    private fun appVersionOf(profile: SubscriptionClientProfile, appVersion: String): String =
        when (profile) {
            SubscriptionClientProfile.Happ -> "3.13.0"
            SubscriptionClientProfile.Incy -> "1.0"
            SubscriptionClientProfile.V2RayTun -> "2.3.5"
            else -> appVersion.ifBlank { "1.0" }
        }

    private fun safeHeaderValue(value: String, name: String): String {
        val text = value.trim()
        if (text.isEmpty() || text.any { it == '\r' || it == '\n' }) {
            throw ImportException("$name содержит недопустимое значение.")
        }
        return text
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
        return (scheme == "http" || scheme == "https") &&
            value.substringAfter("://").substringBefore('/').substringBefore('?').isNotBlank()
    }

    private fun queryParameter(url: String, name: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
            ?.let(::urlDecode)
    }

    private fun urlDecode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    /** Клиенты оборачивают адрес подписки в base64; открытый URL возвращается как есть. */
    private fun decodeWrappedHttpUrl(value: String): String {
        if (isHttpUrl(value)) return value
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || !BASE64_ALPHABET.matches(compact)) return value
        return runCatching { decodeBase64(compact.trimEnd('=')).trim() }.getOrDefault(value)
    }
}
