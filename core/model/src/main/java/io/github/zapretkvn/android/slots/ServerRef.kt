package io.github.zapretkvn.android.slots

/**
 * A server as the user knows it: the profile it lives in and its tag there. A tag alone is not an identity across
 * profiles (two subscriptions can both have «Paris»), so everything stored about a slot's server uses this form.
 *
 * The stored text is `<profile id>|<tag>`; profile ids are 32 lowercase hex digits and never contain `|`, so the first
 * `|` splits it even when the tag holds `|` itself. A stored value without the profile prefix is an older pin (before
 * the pool spanned profiles) and means "this tag in the active profile".
 */
data class ServerRef(val profileId: String, val tag: String) {
    fun encode(): String = "$profileId$SEPARATOR$tag"

    companion object {
        private const val SEPARATOR = "|"
        private val PROFILE_ID = Regex("[0-9a-f]{32}")

        /** The reference in [text], or null when it is an older, unqualified value. */
        fun parse(text: String): ServerRef? {
            val cut = text.indexOf(SEPARATOR)
            if (cut != 32) return null
            val id = text.substring(0, cut)
            if (!PROFILE_ID.matches(id)) return null
            val tag = text.substring(cut + 1)
            return if (tag.isEmpty()) null else ServerRef(id, tag)
        }
    }
}

/**
 * Outbound types a slot may use as a server (one protocol outbound with an address). The pool code and the «Сервисы»
 * tab read the same list, so the tab never offers a server the pool would silently drop.
 */
val SLOT_SERVER_TYPES: Set<String> = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic")
