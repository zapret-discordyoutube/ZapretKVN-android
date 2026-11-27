package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.profiles.AndroidAtomicProfileWriter
import io.github.zapretkvn.android.profiles.AtomicProfileWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun interface SubscriptionFetcher {
    fun fetch(source: SubscriptionSource): String
}

class HttpSubscriptionFetcher(
    private val device: DeviceIdentity = DeviceIdentity(),
    private val appVersion: String = "",
    // Opens one connection for [url]. When [direct] is true the connection is
    // bound to the non-VPN network so the fetch bypasses the tunnel. The
    // subscription host answers the same on every path, so it is tried directly
    // first; the default (VPN when connected) path is a fallback only when the
    // direct attempt returns no answer.
    private val openConnection: (url: URL, direct: Boolean) -> HttpURLConnection? =
        { url, direct -> if (direct) null else url.openConnection() as? HttpURLConnection },
    // Whether a non-VPN network exists right now, so a direct attempt is worth
    // making. Default: never, so behaviour is unchanged until it is wired.
    private val directAvailable: () -> Boolean = { false },
    // Connection explicitly bound to the running VPN network, or null when the
    // tunnel is down. Plain http:// links travel only this way.
    private val tunnelConnection: (url: URL) -> HttpURLConnection? = { null },
    // Direct path through the browser transport (Chrome on the wire); null result —
    // the transport is unavailable in this build and HttpURLConnection is used.
    private val browserDirect: ((url: String, headers: Map<String, String>) -> Reply?)? = null,
    // Когда ни один путь не ответил: проверка по стадиям и вывод, где оборвалось и кто виноват.
    // null — как раньше, сырой текст сетевого исключения.
    private val diagnose: ((host: String, port: Int) -> Diagnosis?)? = null,
    // Строки журнала загрузки (адрес замаскирован, путь и токен не пишутся никогда).
    private val log: (String) -> Unit = {},
    // Ответ подписки получен: url источника и есть ли в ответе заголовок [SERVICE_HEADER].
    private val onServiceMarker: (url: String, present: Boolean) -> Unit = { _, _ -> },
    // Ответ подписки получен: заголовок id серверов каталога ([SERVER_IDS_HEADER]) для отчётов о подключениях.
    private val onServerIds: (url: String, serviceReply: Boolean, value: String?) -> Unit = { _, _, _ -> },
    // Ответ подписки получен: аккаунт WARP, который сервис отдаёт в заголовке [WARP_ACCOUNT_HEADER] (владелец 2026-11-27).
    private val onWarpAccount: (url: String, serviceReply: Boolean, value: String?) -> Unit = { _, _, _ -> },
    // Подписка не получена после попытки по сети: HTTP-статус ответа (null — ответа не было), ошибка и путь
    // последней попытки (direct | tunnel) — для отчёта об ошибке приложения (ни адреса, ни ссылки).
    private val onFailure: (url: String, status: Int?, error: Throwable, route: String) -> Unit = { _, _, _, _ -> },
    // VPN поднят: «сеть по умолчанию» — туннель.
    private val vpnActive: () -> Boolean = { false },
) : SubscriptionFetcher {
    /** Путь и статус последней попытки одной загрузки (для [onFailure]). */
    private class Track {
        var path: Path? = null
        var status: Int? = null
    }

    override fun fetch(source: SubscriptionSource): String {
        val track = Track()
        return try {
            fetchTracked(source, track)
        } catch (error: Exception) {
            val path = track.path
            if (path != null && error !is kotlinx.coroutines.CancellationException) {
                val route = when (path) {
                    Path.Direct -> "direct"
                    Path.Tunnel -> "tunnel"
                    Path.Default -> if (runCatching(vpnActive).getOrDefault(false)) "tunnel" else "direct"
                }
                runCatching { onFailure(source.url, track.status, error, route) }
            }
            throw error
        }
    }

    private fun fetchTracked(source: SubscriptionSource, track: Track): String {
        val url = validatedUrl(source.url)
        val parsed = URI(url)
        val host = parsed.host.orEmpty()
        val port = if (parsed.port > 0) parsed.port else if (parsed.scheme.equals("https", true)) 443 else 80
        val tag = FetchMasking.hostTag(host, port)
        val started = System.nanoTime()
        fun elapsed() = "%.1f".format((System.nanoTime() - started) / 1e9)
        // Ссылка без шифрования: токен в адресе и список серверов в ответе провайдер
        // прочитал бы целиком. Такая ссылка идёт только через туннель, напрямую — никогда.
        if (exposesCleartext(url)) {
            log("subscription $tag: http:// — только через туннель")
            return fetchVia(source, Path.Tunnel, track)
        }
        val direct = directAvailable()
        log("subscription $tag: загрузка начата, прямой путь=${if (direct) "есть" else "нет"}")
        if (direct) {
            try {
                return fetchVia(source, Path.Direct, track).also { log("subscription $tag: ответ получен напрямую за ${elapsed()} с") }
            } catch (transport: IOException) {
                // No response reached us on the direct (non-VPN) path — only
                // this "no answer" case is retried through the default network,
                // which is the VPN tunnel when connected. A real HTTP answer
                // (ImportException, e.g. a 404 for a revoked link) is
                // authoritative and propagates instead of being masked here.
                log(
                    "subscription $tag: ответа нет напрямую за ${elapsed()} с: " +
                        "${FetchMasking.redactHost(transport.message.orEmpty(), host)} [${transport.javaClass.simpleName}]",
                )
            }
        }
        try {
            return fetchVia(source, Path.Default, track).also { log("subscription $tag: ответ получен через сеть по умолчанию за ${elapsed()} с") }
        } catch (transport: IOException) {
            log(
                "subscription $tag: ответа нет и по умолчанию за ${elapsed()} с: " +
                    "${FetchMasking.redactHost(transport.message.orEmpty(), host)} [${transport.javaClass.simpleName}]",
            )
            val diagnosis = runCatching { diagnose?.invoke(host, port) }.getOrNull()
                ?: throw transport
            diagnosis.logLines.forEach(log)
            throw SubscriptionUnreachableException(diagnosis.message(), transport, diagnosis.vpnUntried)
        }
    }

    private enum class Path { Direct, Default, Tunnel }

    private fun open(url: URL, path: Path): HttpURLConnection? = when (path) {
        Path.Direct -> openConnection(url, true)
        Path.Default -> openConnection(url, false)
        Path.Tunnel -> tunnelConnection(url) ?: throw ImportException(CLEARTEXT_NEEDS_TUNNEL)
    }

    private fun fetchVia(source: SubscriptionSource, path: Path, track: Track): String {
        track.path = path
        track.status = null
        var current = validatedUrl(source.url)
        val origin = URI(current).host.orEmpty()
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            // Идентификатор устройства принадлежит только исходному провайдеру:
            // на редиректе к чужому хосту он снимается, как браузеры снимают Authorization.
            val sameOrigin = URI(current).host.orEmpty().equals(origin, ignoreCase = true)
            val effective = if (sameOrigin) source else source.copy(sendHwid = false, hwid = "")
            val headers = SubscriptionIdentity.requestHeaders(effective, device, appVersion)
            val reply = exchange(current, path, headers)
            if (reply.status in REDIRECT_CODES) {
                if (redirectIndex == MAX_REDIRECTS) {
                    throw ImportException("Слишком много перенаправлений подписки.")
                }
                val location = reply.location
                    ?: throw ImportException("Сервер вернул перенаправление без адреса.")
                val next = runCatching { URI(current).resolve(location).toString() }
                    .map(::validatedUrl)
                    .getOrElse {
                        throw ImportException("Сервер вернул некорректный адрес перенаправления.")
                    }
                if (current.startsWith("https://", true) && next.startsWith("http://", true)) {
                    throw ImportException("Переход подписки с HTTPS на HTTP запрещён.")
                }
                current = next
                return@repeat
            }
            if (reply.status !in 200..299) {
                track.status = reply.status
                throw ImportException(
                    FetchJudge.answeredMessage(
                        reply.status,
                        SubscriptionIdentity.describeHttpFailure(reply.status, reply.headerNames),
                    ),
                )
            }
            SubscriptionIdentity.describeHwidRefusal(reply.status, reply.headerNames)
                ?.let { throw ImportException(FetchJudge.answeredMessage(reply.status, it)) }
            val body = reply.body ?: throw ImportException("Подписка больше 4 МБ.")
            val serviceReply = isServiceReply(reply.headerNames)
            onServiceMarker(source.url, serviceReply)
            onServerIds(source.url, serviceReply, reply.serverIds)
            onWarpAccount(source.url, serviceReply, reply.warpAccount)
            return body.toString(Charsets.UTF_8)
        }
        throw ImportException("Не удалось получить подписку.")
    }

    /** Один ответ сервера подписки, каким бы путём он ни пришёл. [body] null — больше лимита. */
    class Reply(
        val status: Int,
        val headerNames: Set<String>,
        val location: String?,
        val body: ByteArray?,
        /** Значение заголовка [SERVER_IDS_HEADER] (id серверов каталога сервиса) или null. */
        val serverIds: String? = null,
        /** Значение заголовка [WARP_ACCOUNT_HEADER] (аккаунт WARP, который отдаёт сервис) или null. */
        val warpAccount: String? = null,
    )

    /**
     * Прямой путь — браузерным транспортом, если он есть: ClientHello и HTTP/2 как у
     * Chrome. Иначе, и для туннеля, — HttpURLConnection на выбранной сети.
     */
    private fun exchange(url: String, path: Path, headers: Map<String, String>): Reply {
        if (path == Path.Direct) browserDirect?.invoke(url, headers)?.let { return it }
        val connection = open(URL(url), path) ?: throw ImportException("URL подписки не является HTTP(S).")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            // Subscription snapshots and HTTP validators are intentionally not cached.
            // Every user refresh is an unconditional fetch, so a parser/runtime revision can
            // never strand an old partially parsed body behind a newly stored ETag.
            connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val names = connection.headerFields.orEmpty().keys.filterNotNull().toSet()
            if (status !in 200..299) return Reply(status, names, connection.getHeaderField("Location"), null)
            if (connection.contentLengthLong > MAX_IMPORT_BYTES) return Reply(status, names, null, null)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMPORT_BYTES) return Reply(status, names, null, null)
                    output.write(buffer, 0, count)
                }
                return Reply(
                    status, names, null, output.toByteArray(),
                    connection.getHeaderField(SERVER_IDS_HEADER),
                    connection.getHeaderField(WARP_ACCOUNT_HEADER),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CLEARTEXT_NEEDS_TUNNEL =
            "Ссылка подписки без шифрования (http://): без VPN её и список серверов видит провайдер. " +
                "Подключите VPN и обновите подписку или возьмите ссылку https://."

        /** http:// не к самому устройству: адрес и ответ уходят в сеть открытым текстом. */
        fun exposesCleartext(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (!uri.scheme.equals("http", ignoreCase = true)) return false
            val host = uri.host?.trim('[', ']')?.lowercase() ?: return false
            return host != "localhost" && host != "127.0.0.1" && host != "::1"
        }

        fun validatedUrl(raw: String): String {
            val value = raw.trim()
            val uri = runCatching { URI(value) }
                .getOrElse { throw ImportException("Некорректный URL подписки.") }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw ImportException("Поддерживаются только полные HTTP(S) URL подписок.")
            }
            // Fragments are client-side labels and are never sent in an HTTP request.
            // Drop them before fetching and persisting the refresh source.
            val fetchUri = if (uri.rawFragment != null) URI(value.substringBefore('#')) else uri
            return fetchUri.toASCIIString()
        }

        /**
         * Ответ подписки самого сервиса Zapret VPN (vpnbot, решение владельца 2026-11-16). Для
         * её профилей приложение не показывает адрес сервера и не берёт страну из GeoIP:
         * страна — только флаг в имени. Достаточно наличия заголовка; имя хоста подписки
         * в приложение не зашивается (домен сменяется и не должен попасть в открытый код).
         */
        const val SERVICE_HEADER = "zapret-vpn-service"

        /** Id серверов каталога сервиса для отчётов о подключениях (`client-reports`, решение владельца 19.11.2026). */
        const val SERVER_IDS_HEADER = io.github.zapretkvn.clientreports.ServerIdHeader.NAME

        /** Аккаунт WARP, который сервис отдаёт подписке (`windows/docs/WARP_ACCOUNT_DELIVERY.md`). */
        const val WARP_ACCOUNT_HEADER = io.github.zapretkvn.android.config.WarpAccountDelivery.HEADER

        fun isServiceReply(headerNames: Collection<String>): Boolean =
            headerNames.any { it.equals(SERVICE_HEADER, ignoreCase = true) }

        private const val TIMEOUT_MILLIS = 15_000
        private const val MAX_REDIRECTS = 3
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * A refresh binding may describe either one ordinary profile or one member of a split
 * subscription. Split metadata lets the updater reconcile the whole group after a single fetch.
 */
data class SubscriptionBinding(
    val source: SubscriptionSource,
    val splitGroupId: String? = null,
    val splitMemberKey: String? = null,
    val splitBaseName: String? = null,
    val knownMemberKeys: Set<String> = emptySet(),
) {
    val isSplit: Boolean get() = splitGroupId != null && splitMemberKey != null
}

/** Stores refresh URLs outside profiles/index.json, which remains credentials-free UI metadata. */
class SubscriptionSourceStore(
    private val root: File,
    private val writer: AtomicProfileWriter = AndroidAtomicProfileWriter(),
) {
    private val mutex = Mutex()
    private val serviceIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Профили подписок сервиса Zapret VPN (ответ пришёл с [HttpSubscriptionFetcher.SERVICE_HEADER]).
     * Обновляется при каждой записи источников и отметок; до [refreshServiceProfileIds] пуст.
     */
    val serviceProfileIds: StateFlow<Set<String>> = serviceIds.asStateFlow()

    /**
     * Отметка «ответ этой ссылки — от сервиса». Хранится SHA-256 нормализованного URL, а не
     * сам URL: токен ссылки не копируется во второй файл. Вызывается из загрузчика подписки
     * (поток IO, блокирующий вызов).
     */
    /** Did this link answer with our service's header before (the mark of [markService])? */
    fun isService(url: String): Boolean = runBlocking {
        mutex.withLock { urlHash(url)?.let { it in readMarks() } ?: false }
    }

    fun markService(url: String, present: Boolean) = runBlocking {
        mutex.withLock {
            val hash = urlHash(url) ?: return@withLock
            val marks = readMarks()
            val next = if (present) marks + hash else marks - hash
            if (next != marks) writeMarks(next)
            publishServiceIds(read(), next)
        }
    }

    suspend fun refreshServiceProfileIds() = withContext(Dispatchers.IO) {
        mutex.withLock { publishServiceIds(read(), readMarks()) }
    }

    suspend fun get(profileId: String): SubscriptionSource? = withContext(Dispatchers.IO) {
        mutex.withLock { read()[profileId]?.source }
    }

    suspend fun binding(profileId: String): SubscriptionBinding? = withContext(Dispatchers.IO) {
        mutex.withLock { read()[profileId] }
    }

    suspend fun splitGroup(profileId: String): Map<String, SubscriptionBinding> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val entries = read()
                val groupId = entries[profileId]?.splitGroupId ?: return@withLock emptyMap()
                entries.filterValues { it.splitGroupId == groupId }
            }
        }

    /** Atomically replaces every binding in a split group. An empty map removes the group. */
    suspend fun replaceSplitGroup(
        groupId: String,
        bindings: Map<String, SubscriptionBinding>,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(groupId.isNotBlank()) { "Пустой id группы подписки." }
            require(bindings.values.all { it.splitGroupId == groupId && it.isSplit }) {
                "Некорректная группа подписки."
            }
            val entries = read().filterValues { it.splitGroupId != groupId }.toMutableMap()
            bindings.forEach { (profileId, binding) ->
                entries[profileId] = binding.normalized()
            }
            write(entries)
        }
    }

    suspend fun ids(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { read().keys }
    }

    suspend fun put(profileId: String, source: SubscriptionSource) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read().toMutableMap()
            val normalized = source.normalized()
            val existing = entries[profileId]
            if (existing?.isSplit == true) {
                entries.replaceAll { _, binding ->
                    if (binding.splitGroupId == existing.splitGroupId) binding.copy(source = normalized)
                    else binding
                }
            } else {
                entries[profileId] = SubscriptionBinding(normalized)
            }
            write(entries)
        }
    }

    suspend fun remove(profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read().toMutableMap()
            if (entries.remove(profileId) != null) write(entries)
        }
    }

    suspend fun retain(profileIds: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read()
            val retained = entries.filterKeys(profileIds::contains)
            if (retained.size != entries.size) write(retained)
        }
    }

    private fun read(): Map<String, SubscriptionBinding> {
        if (!file.isFile) return emptyMap()
        return try {
            val rootObject = JsonConfig.parse(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: throw ImportException("Хранилище источников подписок повреждено.")
            rootObject.mapNotNull { (id, value) -> parseEntry(value)?.let { id to it } }.toMap()
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("Не удалось прочитать источники подписок.", error)
        }
    }

    /** Записи до появления настроек идентификации хранились одной строкой URL. */
    private fun parseEntry(value: JsonElement): SubscriptionBinding? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.let { SubscriptionBinding(SubscriptionSource(it)) }
        is JsonObject -> value.string(URL_KEY)?.let { url ->
            val source = SubscriptionSource(
                url = url,
                clientProfile = SubscriptionClientProfile.parse(value.string(CLIENT_PROFILE_KEY)),
                sendHwid = value.boolean(SEND_HWID_KEY),
                hwid = value.string(HWID_KEY).orEmpty(),
                userAgent = value.string(USER_AGENT_KEY).orEmpty(),
            )
            val groupId = value.string(SPLIT_GROUP_KEY)
            val memberKey = value.string(SPLIT_MEMBER_KEY)
            SubscriptionBinding(
                source = source,
                splitGroupId = groupId?.takeIf { memberKey != null },
                splitMemberKey = memberKey?.takeIf { groupId != null },
                splitBaseName = value.string(SPLIT_BASE_NAME_KEY),
                knownMemberKeys = value.stringSet(KNOWN_MEMBERS_KEY),
            )
        }
        else -> null
    }

    private fun publishServiceIds(entries: Map<String, SubscriptionBinding>, marks: Set<String>) {
        serviceIds.value = if (marks.isEmpty()) {
            emptySet()
        } else {
            entries.filterValues { urlHash(it.source.url) in marks }.keys
        }
    }

    private fun readMarks(): Set<String> {
        if (!serviceFile.isFile) return emptySet()
        // Повреждённый файл отметок — не повод ломать подписки: отметки вернёт следующая загрузка.
        val array = runCatching { JsonConfig.parse(serviceFile.readText(Charsets.UTF_8)) as? JsonArray }
            .getOrNull() ?: return emptySet()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.toSet()
    }

    private fun writeMarks(marks: Set<String>) {
        val json = buildJsonArray { marks.sorted().forEach { add(JsonPrimitive(it)) } }
        writer.writeAtomic(serviceFile, JsonConfig.format(json).toByteArray(Charsets.UTF_8))
    }

    private fun urlHash(url: String): String? {
        val normalized = runCatching { HttpSubscriptionFetcher.validatedUrl(url) }.getOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun write(entries: Map<String, SubscriptionBinding>) {
        publishServiceIds(entries, readMarks())
        val json = buildJsonObject {
            entries.toSortedMap().forEach { (id, binding) ->
                val source = binding.source
                putJsonObject(id) {
                    put(URL_KEY, source.url)
                    put(CLIENT_PROFILE_KEY, source.clientProfile.id)
                    put(SEND_HWID_KEY, source.sendHwid)
                    if (source.sendHwid && source.hwid.isNotBlank()) put(HWID_KEY, source.hwid)
                    if (source.userAgent.isNotBlank()) put(USER_AGENT_KEY, source.userAgent)
                    if (binding.isSplit) {
                        put(SPLIT_GROUP_KEY, binding.splitGroupId!!)
                        put(SPLIT_MEMBER_KEY, binding.splitMemberKey!!)
                        binding.splitBaseName?.takeIf(String::isNotBlank)
                            ?.let { put(SPLIT_BASE_NAME_KEY, it) }
                        put(
                            KNOWN_MEMBERS_KEY,
                            buildJsonArray {
                                binding.knownMemberKeys.sorted().forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    }
                }
            }
        }
        writer.writeAtomic(file, JsonConfig.format(json).toByteArray(Charsets.UTF_8))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.stringSet(key: String): Set<String> =
        (this[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .toSet()

    private fun SubscriptionSource.normalized(): SubscriptionSource = copy(
        url = HttpSubscriptionFetcher.validatedUrl(url),
        hwid = if (sendHwid) SubscriptionIdentity.validateHwid(hwid) else "",
    )

    private fun SubscriptionBinding.normalized(): SubscriptionBinding = copy(
        source = source.normalized(),
        knownMemberKeys = knownMemberKeys.filter(String::isNotBlank).toSet(),
    )

    private val file: File get() = File(root, "index.json")
    private val serviceFile: File get() = File(root, "service.json")

    private companion object {
        const val URL_KEY = "url"
        const val CLIENT_PROFILE_KEY = "client_profile"
        const val SEND_HWID_KEY = "send_hwid"
        const val HWID_KEY = "hwid"
        const val USER_AGENT_KEY = "user_agent"
        const val SPLIT_GROUP_KEY = "split_group"
        const val SPLIT_MEMBER_KEY = "split_member"
        const val SPLIT_BASE_NAME_KEY = "split_base_name"
        const val KNOWN_MEMBERS_KEY = "known_members"
    }
}
