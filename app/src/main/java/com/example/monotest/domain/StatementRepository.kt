@file:OptIn(ExperimentalCoroutinesApi::class)

package com.example.monotest.domain

import com.example.monotest.data.api.StatementApi
import com.example.monotest.data.api.TxDto
import com.example.monotest.database.CardSyncEntity
import com.example.monotest.database.StatementDao
import com.example.monotest.database.TxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun TxDto.toEntity() = TxEntity(id, cardId, seq, time, amountKop, title, subtitle)

class StatementRepository(
    private val api: StatementApi,
    private val dao: StatementDao,
    private val cards: List<String>,
    private val pageSize: Int = 20
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(card: String) = locks.getOrPut(card) { Mutex() }

    /**
     * Set of cardIds currently filling a multi-page gap at the top (`newer` returned `hasMore = true`).
     * Remains set if an error interrupts the loop so the UI keeps the gap banner visible alongside Retry.
     */
    private val _gapCards = MutableStateFlow<Set<String>>(emptySet())
    val gapCards: StateFlow<Set<String>> = _gapCards.asStateFlow()

    /**
     * Initial open and pull-to-refresh for a single [card].
     * Advances `newestSeq` page-by-page until `hasMore == false`.
     */
    suspend fun refresh(card: String) = lock(card).withLock {
        var s = dao.sync(card) ?: return@withLock firstPage(card)
        while (true) {
            val p = retry { api.newer(card, after = s.newestSeq, limit = pageSize) }
            check(p.items.all { it.seq > s.newestSeq }) { "contract: newer" }
            check(p.items.isNotEmpty() || !p.hasMore) { "contract: empty page with hasMore" }
            if (p.hasMore) {
                _gapCards.update { it + card }
            }
            s = s.copy(
                newestSeq = if (p.hasMore) p.items.last().seq
                else maxOf(p.asOfSeq, p.items.lastOrNull()?.seq ?: s.newestSeq)
            )
            commit(p.items, s)
            if (!p.hasMore) {
                _gapCards.update { it - card }
                return@withLock
            }
        }
    }

    /**
     * Loads one older page for [card] if [expectedOldest] still matches the current `oldestSeq`.
     */
    suspend fun loadMore(card: String, expectedOldest: Long) = lock(card).withLock {
        val s = dao.sync(card) ?: return@withLock
        if (s.reachedEnd || s.oldestSeq != expectedOldest) return@withLock
        val p = retry { api.older(card, before = s.oldestSeq, limit = pageSize) }
        check(p.items.all { it.seq < s.oldestSeq } && (p.items.isNotEmpty() || !p.hasMore)) {
            "contract: older"
        }
        commit(
            p.items,
            s.copy(
                oldestSeq = p.items.lastOrNull()?.seq ?: s.oldestSeq,
                reachedEnd = !p.hasMore
            )
        )
    }

    /**
     * Sequentially loads up to [pages] older pages for [card], reading the live `oldestSeq`
     * from the database after each committed page.
     */
    suspend fun loadMorePages(card: String, pages: Int) {
        if (dao.sync(card) == null) {
            refresh(card)
        }
        repeat(pages) {
            val s = dao.sync(card) ?: return
            if (s.reachedEnd) return
            loadMore(card, s.oldestSeq)
        }
    }

    suspend fun clearLocalStorage() {
        _gapCards.value = emptySet()
        dao.clearAll()
    }

    private suspend fun firstPage(card: String) {
        val p = retry { api.older(card, before = null, limit = pageSize) }
            .also { check(it.items.isNotEmpty() || !it.hasMore) { "contract: first page" } }
        commit(
            p.items,
            CardSyncEntity(
                cardId = card,
                oldestSeq = p.items.lastOrNull()?.seq ?: p.asOfSeq,
                newestSeq = p.asOfSeq,
                reachedEnd = !p.hasMore
            )
        )
        _gapCards.update { it - card }
    }

    private suspend fun commit(items: List<TxDto>, s: CardSyncEntity) {
        dao.savePage(items.map { it.toEntity() }, s)
    }

    /**
     * Refreshes all cards in parallel. Collects non-cancellation exceptions so unaffected cards
     * still finish updating, then rethrows the first encountered error.
     */
    suspend fun refreshAll() = coroutineScope {
        cards.map { c ->
            async {
                try {
                    refresh(c)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            }
        }.awaitAll().firstNotNullOfOrNull { it }?.let { throw it }
    }

    /**
     * Advances the shallowest card (`maxByOrNull { it.oldestSeq }`) that hasn't reached the end,
     * since that card restricts the lower bound of the unified intersection window.
     */
    suspend fun loadMoreAll() {
        val shallow = dao.allSync()
            .filter { it.cardId in cards && !it.reachedEnd }
            .maxByOrNull { it.oldestSeq } ?: return
        loadMore(shallow.cardId, shallow.oldestSeq)
    }

    fun observeCard(card: String): Flow<List<TxEntity>> = dao.observeCard(card)

    fun observeSync(): Flow<List<CardSyncEntity>> = dao.observeSync()

    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    /**
     * Unified timeline across all cards: intersection of loaded windows `[max(oldestSeq) .. min(newestSeq)]`.
     */
    fun observeAll(): Flow<List<TxEntity>> = dao.observeSync().flatMapLatest { rows ->
        val relevant = rows.filter { it.cardId in cards }
        if (relevant.size < cards.size) {
            flowOf(emptyList())
        } else {
            val lo = relevant.filterNot { it.reachedEnd }.maxOfOrNull { it.oldestSeq } ?: 0L
            val hi = relevant.minOf { it.newestSeq }
            if (lo > hi) flowOf(emptyList()) else dao.observeWindow(lo = lo, hi = hi)
        }
    }

    private suspend fun <T> retry(times: Int = 3, block: suspend () -> T): T {
        var wait = 200L
        repeat(times - 1) {
            try {
                return block()
            } catch (e: IOException) {
                delay(wait)
                wait *= 2
            }
        }
        return block()
    }
}