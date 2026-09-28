package io.github.zapretkvn.android.vpn.runtime

/**
 * Запускаемая и активная сессии. Переходы сверяют поколение атомарно с
 * заменой: запуск, отменённый командой пользователя, не может стать активным.
 */
internal class SessionSlot(
    private val currentGeneration: () -> Long,
) {
    @Volatile private var pending: CoreSession? = null
    @Volatile private var active: CoreSession? = null

    fun active(): CoreSession? = active

    @Synchronized
    fun registerPending(session: CoreSession, generation: Long): Boolean {
        if (generation != currentGeneration()) return false
        check(pending == null) { "Параллельный запуск VPN запрещён." }
        pending = session
        return true
    }

    @Synchronized
    fun activate(session: CoreSession, generation: Long): Boolean {
        if (generation != currentGeneration() || pending !== session) return false
        pending = null
        active = session
        return true
    }

    @Synchronized
    fun discard(session: CoreSession) {
        if (pending === session) pending = null
        if (active === session) active = null
    }

    /** Забирает все сессии; закрывает их вызывающий. */
    @Synchronized
    fun detachAll(): List<CoreSession> {
        val sessions = listOfNotNull(active, pending).distinct()
        active = null
        pending = null
        return sessions
    }
}
