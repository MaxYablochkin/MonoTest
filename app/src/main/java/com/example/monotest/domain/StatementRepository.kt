@file:OptIn(ExperimentalCoroutinesApi::class)

package com.example.monotest.domain

import com.example.monotest.data.api.StatementApi
import com.example.monotest.data.api.TxCursor
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
import java.util.concurrent.atomic.AtomicInteger

fun TxDto.toEntity() = TxEntity(id, cardId, time, amountKop, title, subtitle)

class StatementRepository(
    private val api: StatementApi,
    private val dao: StatementDao,
    private val cards: List<String>,
    private val pageSize: Int = 20,
    private val lateArrivalWindowSec: Long = 0L
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val resetEpoch = AtomicInteger(0)
    private fun lock(card: String) = locks.getOrPut(card) { Mutex() }

    private val _gapCards = MutableStateFlow<Set<String>>(emptySet())
    val gapCards: StateFlow<Set<String>> = _gapCards.asStateFlow()

    suspend fun refresh(card: String) = lock(card).withLock {
        val epoch = resetEpoch.get()
        var s = dao.sync(card) ?: return@withLock firstPage(card, epoch)
        val latestStored = dao.rowsOf(card).firstOrNull()?.cursor
        var queryAfter = when {
            latestStored == null -> TxCursor(time = Long.MIN_VALUE, id = "")
            lateArrivalWindowSec > 0L -> {
                val lookback = TxCursor(time = latestStored.time - lateArrivalWindowSec, id = "")
                maxOf(s.oldestCursor, lookback)
            }
            else -> latestStored
        }
        while (true) {
            if (resetEpoch.get() != epoch) return@withLock
            val p = retry { api.newer(card, after = queryAfter, limit = pageSize) }
            if (resetEpoch.get() != epoch) return@withLock
            check(p.items.all { it.cursor > queryAfter }) { "contract: newer" }
            check(p.items.zipWithNext().all { (a, b) -> a.cursor < b.cursor }) {
                "contract: newer ordering"
            }
            check(p.items.isNotEmpty() || !p.hasMore) { "contract: empty page with hasMore" }
            if (p.hasMore) {
                _gapCards.update { it + card }
            }
            val lastItemCursor = p.items.lastOrNull()?.cursor
            if (lastItemCursor != null) {
                queryAfter = lastItemCursor
            }
            val candidateNewest = if (p.hasMore) {
                lastItemCursor ?: s.newestCursor
            } else {
                maxOf(p.asOf, lastItemCursor ?: s.newestCursor)
            }
            val nextNewest = maxOf(s.newestCursor, candidateNewest)
            val firstItemCursor = p.items.firstOrNull()?.cursor
            val nextOldest = if (firstItemCursor != null) {
                minOf(s.oldestCursor, firstItemCursor)
            } else {
                s.oldestCursor
            }
            s = s.copy(
                oldestTime = nextOldest.time,
                oldestId = nextOldest.id,
                newestTime = nextNewest.time,
                newestId = nextNewest.id
            )
            commit(p.items, s)
            if (!p.hasMore) {
                _gapCards.update { it - card }
                return@withLock
            }
        }
    }

    suspend fun loadMore(card: String, expectedOldest: TxCursor) = lock(card).withLock {
        val epoch = resetEpoch.get()
        val s = dao.sync(card) ?: return@withLock
        val currentOldest = s.oldestCursor
        if (s.reachedEnd || currentOldest != expectedOldest) return@withLock
        val p = retry { api.older(card, before = currentOldest, limit = pageSize) }
        if (resetEpoch.get() != epoch) return@withLock
        check(p.items.all { it.cursor < currentOldest } && (p.items.isNotEmpty() || !p.hasMore)) {
            "contract: older"
        }
        check(p.items.zipWithNext().all { (a, b) -> a.cursor > b.cursor }) {
            "contract: older ordering"
        }
        val nextOldest = p.items.lastOrNull()?.cursor ?: currentOldest
        commit(
            p.items,
            s.copy(
                oldestTime = nextOldest.time,
                oldestId = nextOldest.id,
                reachedEnd = !p.hasMore
            )
        )
    }

    suspend fun loadMorePages(card: String, pages: Int) {
        if (dao.sync(card) == null) {
            refresh(card)
        }
        repeat(pages) {
            val s = dao.sync(card) ?: return
            if (s.reachedEnd) return
            loadMore(card, s.oldestCursor)
        }
    }

    suspend fun clearLocalStorage() {
        resetEpoch.incrementAndGet()
        val sortedCards = (cards + locks.keys).distinct().sorted()
        withAllLocks(sortedCards, 0) {
            _gapCards.value = emptySet()
            dao.clearAll()
        }
    }

    private suspend fun withAllLocks(
        sortedCards: List<String>,
        index: Int,
        block: suspend () -> Unit
    ) {
        if (index >= sortedCards.size) {
            block()
        } else {
            lock(sortedCards[index]).withLock {
                withAllLocks(sortedCards, index + 1, block)
            }
        }
    }

    private suspend fun firstPage(card: String, epoch: Int) {
        val p = retry { api.older(card, before = null, limit = pageSize) }
            .also {
                check(it.items.isNotEmpty() || !it.hasMore) { "contract: first page" }
                check(it.items.zipWithNext().all { (a, b) -> a.cursor > b.cursor }) {
                    "contract: first page ordering"
                }
            }
        if (resetEpoch.get() != epoch) return
        val newest = maxOf(p.asOf, p.items.firstOrNull()?.cursor ?: p.asOf)
        val oldest = p.items.lastOrNull()?.cursor ?: newest
        commit(
            p.items,
            CardSyncEntity(
                cardId = card,
                oldestTime = oldest.time,
                oldestId = oldest.id,
                newestTime = newest.time,
                newestId = newest.id,
                reachedEnd = !p.hasMore
            )
        )
        _gapCards.update { it - card }
    }

    private suspend fun commit(items: List<TxDto>, s: CardSyncEntity) {
        dao.savePage(items.map { it.toEntity() }, s)
    }

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

    suspend fun loadMoreAll() {
        val shallow = dao.allSync()
            .filter { it.cardId in cards && !it.reachedEnd }
            .maxByOrNull { it.oldestCursor } ?: return
        loadMore(shallow.cardId, shallow.oldestCursor)
    }

    fun observeCard(card: String): Flow<List<TxEntity>> = dao.observeCard(card)

    fun observeSync(): Flow<List<CardSyncEntity>> = dao.observeSync()

    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    fun observeAll(): Flow<List<TxEntity>> = dao.observeSync().flatMapLatest { rows ->
        val relevant = rows.filter { it.cardId in cards }
        if (relevant.size < cards.size) {
            flowOf(emptyList())
        } else {
            val lo = relevant.filterNot { it.reachedEnd }.maxOfOrNull { it.oldestCursor }
                ?: TxCursor(time = Long.MIN_VALUE, id = "")
            val hi = relevant.minOf { it.newestCursor }
            if (lo > hi) {
                flowOf(emptyList())
            } else {
                dao.observeWindow(
                    loTime = lo.time,
                    loId = lo.id,
                    hiTime = hi.time,
                    hiId = hi.id
                )
            }
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
