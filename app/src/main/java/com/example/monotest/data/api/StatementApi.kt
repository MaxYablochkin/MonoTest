package com.example.monotest.data.api

data class TxDto(
    val id: String,
    val cardId: String,
    val seq: Long,
    val time: Long,
    val amountKop: Long,
    val title: String,
    val subtitle: String
)

/**
 * [asOfSeq] is taken from the same atomic snapshot as [items].
 */
data class Page(
    val items: List<TxDto>,
    val hasMore: Boolean,
    val asOfSeq: Long
)

interface StatementApi {
    /**
     * Up to [limit] transactions for [cardId] with `seq < before` (if `before == null`, the newest ones),
     * ordered by descending `seq`.
     */
    suspend fun older(cardId: String, before: Long?, limit: Int): Page

    /**
     * Up to [limit] transactions for [cardId] with `seq > after`,
     * ordered by ascending `seq` (oldest among the new ones first).
     */
    suspend fun newer(cardId: String, after: Long, limit: Int): Page
}
