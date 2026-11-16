package io.github.zapretkvn.masking.tester

/** What the "Подобрать" runner is doing, for the screen. The runner is the platform adapter. */
sealed interface TuneState {
    data object Idle : TuneState

    /** [candidate] is 1-based. */
    data class Running(val serverName: String, val candidate: Int, val candidates: Int, val title: String) : TuneState

    /** [kept] is the id of the candidate the user kept as the server's choice. */
    data class Done(
        val serverName: String,
        val serverKey: String,
        val ranked: List<CandidateTester.Ranked>,
        val kept: String? = null,
    ) : TuneState

    data class Failed(val message: String) : TuneState
}
