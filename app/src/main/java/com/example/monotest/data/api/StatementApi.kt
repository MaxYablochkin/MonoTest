package com.example.monotest.data.api

/**
 * Composite cursor `(time, id)` that defines a strict, deterministic total order
 * across transactions without any global sequence counter (`seq`).
 *
 * Canonical descending order in statements: `time DESC, id DESC`.
 * Therefore, cursor A is "newer" (greater) than cursor B iff:
 * `A.time > B.time || (A.time == B.time && A.id > B.id)`.
 */
data class TxCursor(
    val time: Long,
    val id: String
) : Comparable<TxCursor> {
    override fun compareTo(other: TxCursor): Int {
        val timeCmp = this.time.compareTo(other.time)
        return if (timeCmp != 0) timeCmp else this.id.compareTo(other.id)
    }
}

data class TxDto(
    val id: String,
    val cardId: String,
    val time: Long,
    val amountKop: Long,
    val title: String,
    val subtitle: String
) {
    val cursor: TxCursor get() = TxCursor(time = time, id = id)
}

/**
 * [asOf] is the upper cursor boundary `(time, id)` of the server snapshot at the moment
 * the page was produced (accounting for the server's safe settlement horizon so late-arriving
 * operations inside the active window are never skipped).
 */
data class Page(
    val items: List<TxDto>,
    val hasMore: Boolean,
    val asOf: TxCursor
)

interface StatementApi {
    /**
     * Up to [limit] transactions for [cardId] strictly older than [before] (`cursor < before`;
     * if `before == null`, the newest ones up to [Page.asOf]), ordered by descending `(time, id)`.
     */
    suspend fun older(cardId: String, before: TxCursor?, limit: Int): Page

    /**
     * Up to [limit] transactions for [cardId] strictly newer than [after] (`cursor > after`),
     * ordered by ascending `(time, id)` (oldest among the new ones first).
     */
    suspend fun newer(cardId: String, after: TxCursor, limit: Int): Page
}
