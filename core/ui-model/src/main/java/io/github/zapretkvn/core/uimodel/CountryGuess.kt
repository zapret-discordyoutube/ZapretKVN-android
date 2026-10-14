package io.github.zapretkvn.core.uimodel

import java.util.Locale

/**
 * Страна сервера, когда в имени нет эмодзи-флага: «hy2-nl-1», «DE Frankfurt»,
 * «Нидерланды · Hysteria», «amsterdam-2». Всё офлайн, без GeoIP и сети.
 *
 * Двухбуквенный токен считается кодом страны, только если это настоящий
 * ISO-код и он написан заглавными, стоит первым в имени или пришёл из имени
 * хоста — иначе «no-tls» превратился бы в Норвегию.
 */
object CountryGuess {
    private val ISO = Locale.getISOCountries().toHashSet()

    /** Токены, похожие на коды стран, но в именах серверов почти всегда значат другое. */
    private val NOT_COUNTRIES = setOf("WS", "TV", "AI", "IO", "ME", "TO", "IS", "AM", "AS", "BY", "IN", "IT", "NO", "SO", "TG")

    private val NAMES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { put(it, code) }
        add("RU", "russia", "россия", "moscow", "москва", "spb", "petersburg", "петербург")
        add("NL", "netherlands", "нидерланды", "голландия", "holland", "amsterdam", "амстердам")
        add("DE", "germany", "германия", "deutschland", "frankfurt", "франкфурт", "berlin", "берлин", "nuremberg", "falkenstein")
        add("FI", "finland", "финляндия", "helsinki", "хельсинки")
        add("SE", "sweden", "швеция", "stockholm", "стокгольм")
        add("US", "usa", "сша", "america", "америка", "new york", "newyork", "los angeles", "chicago", "dallas", "miami", "seattle")
        add("GB", "uk", "united kingdom", "великобритания", "англия", "england", "london", "лондон")
        add("FR", "france", "франция", "paris", "париж")
        add("PL", "poland", "польша", "warsaw", "варшава")
        add("LV", "latvia", "латвия", "riga", "рига")
        add("LT", "lithuania", "литва", "vilnius", "вильнюс")
        add("EE", "estonia", "эстония", "tallinn", "таллин")
        add("KZ", "kazakhstan", "казахстан", "almaty", "алматы", "astana", "астана")
        add("TR", "turkey", "turkiye", "турция", "istanbul", "стамбул")
        add("AE", "uae", "оаэ", "emirates", "dubai", "дубай")
        add("JP", "japan", "япония", "tokyo", "токио")
        add("SG", "singapore", "сингапур")
        add("HK", "hong kong", "hongkong", "гонконг")
        add("CH", "switzerland", "швейцария", "zurich", "цюрих")
        add("AT", "austria", "австрия", "vienna", "вена")
        add("CZ", "czechia", "czech", "чехия", "prague", "прага")
        add("ES", "spain", "испания", "madrid", "мадрид")
        add("IT", "italy", "италия", "milan", "милан", "rome")
        add("NO", "norway", "норвегия", "oslo", "осло")
        add("DK", "denmark", "дания", "copenhagen")
        add("CA", "canada", "канада", "toronto", "montreal")
        add("AM", "armenia", "армения", "yerevan", "ереван")
        add("GE", "georgia", "грузия", "tbilisi", "тбилиси")
        add("RS", "serbia", "сербия", "belgrade", "белград")
        add("MD", "moldova", "молдова", "chisinau")
        add("RO", "romania", "румыния", "bucharest")
        add("BG", "bulgaria", "болгария", "sofia")
        add("HU", "hungary", "венгрия", "budapest")
        add("UA", "ukraine", "украина", "kyiv", "kiev")
        add("BY", "belarus", "беларусь", "минск", "minsk")
        add("IL", "israel", "израиль", "tel aviv")
        add("IN", "india", "индия", "mumbai")
        add("KR", "korea", "корея", "seoul", "сеул")
        add("CN", "china", "китай")
        add("TW", "taiwan", "тайвань")
        add("BR", "brazil", "бразилия")
        add("AU", "australia", "австралия", "sydney")
        add("IE", "ireland", "ирландия", "dublin")
        add("BE", "belgium", "бельгия", "brussels")
        add("LU", "luxembourg", "люксембург")
        add("PT", "portugal", "португалия", "lisbon")
        add("GR", "greece", "греция", "athens")
        add("CY", "cyprus", "кипр")
        add("IS", "iceland", "исландия", "reykjavik")
        add("UZ", "uzbekistan", "узбекистан", "tashkent")
        add("KG", "kyrgyzstan", "киргизия", "кыргызстан", "bishkek")
        add("AZ", "azerbaijan", "азербайджан", "baku")
        add("VN", "vietnam", "вьетнам")
        add("TH", "thailand", "таиланд")
        add("ID", "indonesia", "индонезия")
        add("MY", "malaysia", "малайзия")
    }

    private val TOKEN = Regex("[\\p{L}]+")

    /** Код страны из имени сервера или null. */
    fun fromName(name: String): String? = guess(name)

    /**
     * Код страны из имени хоста: город или страна в любой метке («stockholm.vpn.net»)
     * либо код в начале первой метки перед цифрой или дефисом («nl1.example.com»,
     * «de-fra.vpn.net»). IP-адрес и обычные домены («my.server.io») — null.
     */
    fun fromHost(host: String?): String? {
        val value = host?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty() || value.all { it.isDigit() || it == '.' || it == ':' }) return null
        val labels = value.split('.')
        labels.dropLast(1).flatMap { TOKEN.findAll(it).map { m -> m.value }.toList() }
            .firstNotNullOfOrNull { NAMES[it] }?.let { return it }
        val match = HOST_CODE.find(labels.first()) ?: return null
        val code = match.groupValues[1].uppercase(Locale.ROOT)
        return code.takeIf { it in ISO && it !in NOT_COUNTRIES }
    }

    private val HOST_CODE = Regex("^([a-z]{2})(?:\\d|-)")

    private fun guess(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        // Сначала названия стран и городов: они однозначнее двухбуквенных кодов.
        NAMES.entries
            .filter { (name, _) -> name.contains(' ') && lower.contains(name) }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        tokens.firstNotNullOfOrNull { NAMES[it.lowercase(Locale.ROOT)] }?.let { return it }
        tokens.forEachIndexed { index, token ->
            if (token.length != 2) return@forEachIndexed
            val code = token.uppercase(Locale.ROOT)
            if (code !in ISO) return@forEachIndexed
            val explicit = token == code || index == 0
            if (explicit && (code !in NOT_COUNTRIES || token == code && index == 0)) return code
        }
        return null
    }

    /** «NL» → «🇳🇱». */
    fun flagOf(code: String): String =
        code.uppercase(Locale.ROOT).map { char ->
            String(Character.toChars(0x1F1E6 + (char - 'A')))
        }.joinToString("")
}

/** Хост из «host:port», «[v6]:port» или просто «host». */
fun endpointHost(endpoint: String?): String? {
    val value = endpoint?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']').ifEmpty { null }
    return if (value.count { it == ':' } == 1) value.substringBefore(':') else value
}

/**
 * Дополняет имена без флага страной: сначала по серверу с тем же адресом,
 * у которого флаг есть (часто это VLESS и Hysteria на одном узле), затем по
 * словам имени и по имени хоста. Возвращает тег → код страны только для тех,
 * у кого флага в имени не было.
 */
fun inferCountries(servers: List<Pair<String, String?>>): Map<String, String> {
    val parsed = servers.map { (tag, endpoint) -> Triple(tag, ServerName.parse(tag), endpointHost(endpoint)) }
    val byHost = parsed
        .filter { it.second.countryCode != null && it.third != null }
        .groupBy({ it.third!! }, { it.second.countryCode!! })
        .mapValues { (_, codes) -> codes.groupingBy { it }.eachCount().maxBy { it.value }.key }
    return buildMap {
        parsed.forEach { (tag, name, host) ->
            if (name.countryCode != null) return@forEach
            val code = host?.let(byHost::get)
                ?: CountryGuess.fromName(name.title)
                ?: CountryGuess.fromHost(host)
            if (code != null) put(tag, code)
        }
    }
}
