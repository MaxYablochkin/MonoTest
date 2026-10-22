@file:OptIn(ExperimentalCoroutinesApi::class)

package com.example.monotest

import com.example.monotest.cards.mapper.mapTransactionsToGroups
import com.example.monotest.data.api.FakeStatementApi
import com.example.monotest.data.api.Page
import com.example.monotest.data.api.StatementApi
import com.example.monotest.database.CardSyncEntity
import com.example.monotest.database.StatementDao
import com.example.monotest.database.TxEntity
import com.example.monotest.domain.StatementRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.collections.all
import kotlin.collections.take
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * SQL-accurate in-memory implementation of [StatementDao] that enforces:
 * - `PRIMARY KEY(id)` with `OnConflictStrategy.IGNORE`
 * - `UNIQUE(seq)` index with `OnConflictStrategy.IGNORE`
 * - `ORDER BY seq DESC`
 * - `seq BETWEEN :lo AND :hi`
 * - Atomic `savePage(items, syncEntity)`
 */
class InMemoryStatementDao : StatementDao {
    private val lock = Any()
    private val txById = LinkedHashMap<String, TxEntity>()
    private val syncByCard = LinkedHashMap<String, CardSyncEntity>()

    private val txState = MutableStateFlow<List<TxEntity>>(emptyList())
    private val syncState = MutableStateFlow<List<CardSyncEntity>>(emptyList())

    override suspend fun insert(items: List<TxEntity>) = synchronized(lock) {
        val existingSeqs = txById.values.mapTo(HashSet()) { it.seq }
        for (item in items) {
            if (!txById.containsKey(item.id) && item.seq !in existingSeqs) {
                txById[item.id] = item
                existingSeqs.add(item.seq)
            }
        }
        txState.value = txById.values.sortedByDescending { it.seq }
    }

    override suspend fun upsert(s: CardSyncEntity) = synchronized(lock) {
        syncByCard[s.cardId] = s
        syncState.value = syncByCard.values.toList()
    }

    override suspend fun sync(c: String): CardSyncEntity? = synchronized(lock) {
        syncByCard[c]
    }

    override suspend fun allSync(): List<CardSyncEntity> = synchronized(lock) {
        syncByCard.values.toList()
    }

    override fun observeSync(): Flow<List<CardSyncEntity>> = syncState

    override fun observeCard(c: String): Flow<List<TxEntity>> =
        txState.map { list -> list.filter { it.cardId == c }.sortedByDescending { it.seq } }

    override fun observeWindow(lo: Long, hi: Long): Flow<List<TxEntity>> =
        txState.map { list -> list.filter { it.seq in lo..hi }.sortedByDescending { it.seq } }

    override suspend fun rowsOf(c: String): List<TxEntity> = synchronized(lock) {
        txById.values.filter { it.cardId == c }.sortedByDescending { it.seq }
    }

    override fun observeTotalCount(): Flow<Int> = txState.map { it.size }

    override suspend fun clearTx() = synchronized(lock) {
        txById.clear()
        txState.value = emptyList()
    }

    override suspend fun clearSync() = synchronized(lock) {
        syncByCard.clear()
        syncState.value = emptyList()
    }

    override suspend fun savePage(items: List<TxEntity>, syncEntity: CardSyncEntity) =
        synchronized(lock) {
            val existingSeqs = txById.values.mapTo(HashSet()) { it.seq }
            for (item in items) {
                if (!txById.containsKey(item.id) && item.seq !in existingSeqs) {
                    txById[item.id] = item
                    existingSeqs.add(item.seq)
                }
            }
            syncByCard[syncEntity.cardId] = syncEntity
            txState.value = txById.values.sortedByDescending { it.seq }
            syncState.value = syncByCard.values.toList()
        }
}


class StatementRepositoryTest {

    private val cardA = "card_black"
    private val cardB = "card_white"
    private val cardC = "card_platinum"
    private val twoCards = listOf(cardA, cardB)
    private val threeCards = listOf(cardA, cardB, cardC)

    /**
     * Verifies Invariant I1 across all synced cards:
     * For every card in `card_sync`, `tx` contains the exact set of ground-truth transactions
     * whose `seq` lies in `[oldestSeq .. newestSeq]`, ordered by `seq DESC`, with no holes or duplicates.
     */
    private suspend fun assertConsistent(dao: StatementDao, fake: FakeStatementApi) {
        val syncs = dao.allSync()
        for (s in syncs) {
            val truth = fake.items(s.cardId)
                .map { it.seq }
                .filter { it in s.oldestSeq..s.newestSeq }
                .sortedDescending()
            val actualRows = dao.rowsOf(s.cardId)
            val actualSeqs = actualRows.map { it.seq }
            assertEquals(
                "Invariant I1 violated for card=${s.cardId} window=[${s.oldestSeq}..${s.newestSeq}]",
                truth,
                actualSeqs
            )
            assertEquals(
                "Duplicate IDs found for card=${s.cardId}",
                actualRows.map { it.id }.distinct(),
                actualRows.map { it.id }
            )
        }
    }

    // Scenario 1: Первое открытие карты -> один вызов older, одна страница в БД
    @Test
    fun test01_firstOpen_loadsSingleOlderPage() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 1L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)

        assertEquals(1, fake.olderCalls)
        assertEquals(0, fake.newerCalls)
        assertEquals(20, dao.rowsOf(cardA).size)
        val syncA = dao.sync(cardA)!!
        assertFalse(syncA.reachedEnd)
        assertEquals(fake.currentAsOfSeq(), syncA.newestSeq)
        assertConsistent(dao, fake)
    }

    // Scenario 2: loadMore -> добавлена ровно одна страница, порядок по seq, дублей нет
    @Test
    fun test02_loadMore_appendsNextOlderPageInOrderWithoutDuplicates() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 2L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        val firstOldest = dao.sync(cardA)!!.oldestSeq

        repo.loadMore(cardA, expectedOldest = firstOldest)

        assertEquals(2, fake.olderCalls)
        assertEquals(40, dao.rowsOf(cardA).size)
        assertFalse(dao.sync(cardA)!!.reachedEnd)
        assertTrue(dao.sync(cardA)!!.oldestSeq < firstOldest)
        assertConsistent(dao, fake)
    }

    // Scenario 3: Пауза: +55 при странице 20 -> refresh делает 3 вызова newer (20+20+15), окно доходит до asOfSeq
    @Test
    fun test03_pauseWith55NewItems_refreshFetchesThreeNewerPagesAndClosesGap() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 25, latencyMs = 10L, seed = 3L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        assertEquals(20, dao.rowsOf(cardA).size)

        fake.appendNew(cardA, count = 55)
        repo.refresh(cardA)

        assertEquals(3, fake.newerCalls)
        assertEquals(75, dao.rowsOf(cardA).size)
        assertEquals(fake.currentAsOfSeq(), dao.sync(cardA)!!.newestSeq)
        assertTrue(repo.gapCards.value.isEmpty())
        assertConsistent(dao, fake)
    }

    // Scenario 4: Карта A глубже карты B -> общая лента не содержит операций глубже окна B; loadMoreAll грузит только B
    @Test
    fun test04_cardADeeperThanCardB_unifiedTimelineExcludesDeepTailAndLoadMoreAllLoadsShallowest() =
        runTest {
            val fake =
                FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 4L)
            val dao = InMemoryStatementDao()
            val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

            repo.refreshAll()
            // Make cardA deeper by 1 additional page (40 items total on A, 20 on B)
            val oldestA1 = dao.sync(cardA)!!.oldestSeq
            repo.loadMore(cardA, expectedOldest = oldestA1)

            val syncA = dao.sync(cardA)!!
            val syncB = dao.sync(cardB)!!
            assertTrue(
                "cardA should be deeper (smaller oldestSeq) than cardB",
                syncA.oldestSeq < syncB.oldestSeq
            )

            val allFeedBefore = repo.observeAll().first()
            // No transaction in unified feed may have seq < syncB.oldestSeq
            assertTrue(allFeedBefore.all { it.seq >= syncB.oldestSeq })

            val callsBefore = fake.olderCalls
            repo.loadMoreAll()

            // Only shallowest card (cardB) was loaded
            assertEquals(callsBefore + 1, fake.olderCalls)
            assertEquals(40, dao.rowsOf(cardB).size)
            assertConsistent(dao, fake)
        }

    // Scenario 5: Карта A обновлена, B нет -> общая лента не показывает новое A, пока не обновлена B
    @Test
    fun test05_cardARefreshedButCardBNot_unifiedTimelineHidesNewItemsUntilCardBRefreshed() =
        runTest {
            val fake =
                FakeStatementApi(cards = twoCards, initialPerCard = 20, latencyMs = 10L, seed = 5L)
            val dao = InMemoryStatementDao()
            val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

            repo.refreshAll()
            val initialNewestB = dao.sync(cardB)!!.newestSeq

            val addedToA = fake.appendNew(cardA, count = 5)
            repo.refresh(cardA)

            // Card A has the new items in its own timeline
            val cardAFeed = repo.observeCard(cardA).first()
            assertTrue(cardAFeed.map { it.seq }.containsAll(addedToA.map { it.seq }))

            // Unified timeline still bounded by cardB.newestSeq (initialNewestB)
            val unifiedBeforeBRefresh = repo.observeAll().first()
            assertTrue(unifiedBeforeBRefresh.all { it.seq <= initialNewestB })
            assertTrue(unifiedBeforeBRefresh.none {
                it.seq in addedToA.map { tx -> tx.seq }.toSet()
            })

            // Now refresh Card B (even though B has 0 new items, its newestSeq advances to asOfSeq)
            repo.refresh(cardB)
            val unifiedAfterBRefresh = repo.observeAll().first()
            assertTrue(unifiedAfterBRefresh.map { it.seq }.containsAll(addedToA.map { it.seq }))
            assertConsistent(dao, fake)
        }

    // Scenario 6: Ошибка на 2-м вызове newer, потом повтор -> после сбоя I1 выполнен; после повтора окно полное
    @Test
    fun test06_errorOnSecondNewerPage_preservesInvariantI1AndRecoversOnRetry() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 20, latencyMs = 10L, seed = 6L)
        val dao = InMemoryStatementDao()
        // Custom wrapper that fails specifically on the 2nd newer call across retries
        var newerAttempt = 0
        var shouldFailSecondPage = true
        val faultyApi = object : StatementApi by fake {
            override suspend fun newer(cardId: String, after: Long, limit: Int): Page {
                newerAttempt++
                // 1st call succeeds (20 items, hasMore = true); calls 2, 3, 4 (retries of 2nd page) fail
                if (shouldFailSecondPage && newerAttempt >= 2) {
                    throw IOException("Network failure on 2nd newer page")
                }
                return fake.newer(cardId, after, limit)
            }
        }
        val repo = StatementRepository(api = faultyApi, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        fake.appendNew(cardA, count = 45)

        try {
            repo.refresh(cardA)
            fail("Expected IOException on 2nd newer page")
        } catch (_: IOException) {
            // Expected
        }

        // Page 1 (20 new items -> 40 total) was atomically committed; Invariant I1 MUST hold!
        assertEquals(40, dao.rowsOf(cardA).size)
        assertTrue("Gap indicator must remain active on error", cardA in repo.gapCards.value)
        assertConsistent(dao, fake)

        // Retry after network recovers
        shouldFailSecondPage = false
        repo.refresh(cardA)

        assertEquals(65, dao.rowsOf(cardA).size)
        assertEquals(fake.currentAsOfSeq(), dao.sync(cardA)!!.newestSeq)
        assertFalse("Gap indicator must clear after completion", cardA in repo.gapCards.value)
        assertConsistent(dao, fake)
    }

    // Scenario 7: Два refresh параллельно -> дублей нет, данные верны
    @Test
    fun test07_twoParallelRefreshes_produceNoDuplicatesAndConsistentWindow() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 20, latencyMs = 20L, seed = 7L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        fake.appendNew(cardA, count = 35)

        val d1 = async { repo.refresh(cardA) }
        val d2 = async { repo.refresh(cardA) }
        d1.await()
        d2.await()

        assertEquals(55, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)
    }

    // Scenario 8: Два loadMore с одним expectedOldest -> один вызов older
    @Test
    fun test08_twoParallelLoadMoreWithSameExpectedOldest_executesOnlyOneOlderCall() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 50, latencyMs = 25L, seed = 8L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        val expectedOldest = dao.sync(cardA)!!.oldestSeq
        val olderCallsBefore = fake.olderCalls

        val d1 = async { repo.loadMore(cardA, expectedOldest) }
        val d2 = async { repo.loadMore(cardA, expectedOldest) }
        d1.await()
        d2.await()

        assertEquals(
            "Only one older API call should be executed",
            olderCallsBefore + 1,
            fake.olderCalls
        )
        assertEquals(40, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)
    }

    // Scenario 9: Конец истории -> reachedEnd = true, следующий loadMore без запроса
    @Test
    fun test09_reachedEndOfHistory_setsReachedEndTrueAndSkipsSubsequentLoadMore() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 30, latencyMs = 10L, seed = 9L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        assertFalse(dao.sync(cardA)!!.reachedEnd)

        val firstOldest = dao.sync(cardA)!!.oldestSeq
        repo.loadMore(cardA, firstOldest)

        val syncAfterEnd = dao.sync(cardA)!!
        assertTrue(syncAfterEnd.reachedEnd)
        assertEquals(30, dao.rowsOf(cardA).size)

        val callsAtEnd = fake.olderCalls
        repo.loadMore(cardA, syncAfterEnd.oldestSeq)
        assertEquals(
            "No network request should be made when reachedEnd = true",
            callsAtEnd,
            fake.olderCalls
        )
        assertConsistent(dao, fake)
    }

    // Scenario 10: Пустая карта и карта короче страницы -> корректные окна, общая лента не «застревает»
    @Test
    fun test10_emptyCardAndShortCard_doNotBlockUnifiedTimeline() = runTest {
        val fake = FakeStatementApi(
            cards = threeCards,
            initialCountsByCard = mapOf(
                cardA to 25,
                cardB to 5,   // shorter than pageSize (20)
                cardC to 0    // completely empty card
            ),
            latencyMs = 10L,
            seed = 10L
        )
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = threeCards, pageSize = 20)

        repo.refreshAll()

        val syncA = dao.sync(cardA)!!
        val syncB = dao.sync(cardB)!!
        val syncC = dao.sync(cardC)!!

        assertFalse(syncA.reachedEnd)
        assertTrue(syncB.reachedEnd)
        assertTrue(syncC.reachedEnd)
        assertEquals(fake.currentAsOfSeq(), syncC.oldestSeq)
        assertEquals(fake.currentAsOfSeq(), syncC.newestSeq)

        val allFeed = repo.observeAll().first()
        // Unified feed should contain all 20 loaded items of cardA plus any of cardB's 5 items that have seq >= syncA.oldestSeq
        val expectedAllSeqs = (fake.items(cardA) + fake.items(cardB) + fake.items(cardC))
            .map { it.seq }
            .filter { it in syncA.oldestSeq..syncA.newestSeq }
            .sortedDescending()
        assertEquals(expectedAllSeqs, allFeed.map { it.seq })
        assertConsistent(dao, fake)
    }

    // Scenario 11: Одинаковое time на границе страницы -> ничего не теряется и не дублируется
    @Test
    fun test11_identicalTimestampsOnPageBoundary_noLossOrDuplicates() = runTest {
        val fake = FakeStatementApi(
            cards = listOf(cardA),
            initialCountsByCard = mapOf(cardA to 0),
            latencyMs = 5L,
            seed = 11L
        )
        val fixedTime = 1_729_600_000L
        fake.appendNew(cardA, count = 40, customTimeSec = fixedTime)

        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

        repo.refresh(cardA)
        assertEquals(20, dao.rowsOf(cardA).size)

        val oldest1 = dao.sync(cardA)!!.oldestSeq
        repo.loadMore(cardA, oldest1)

        val allRows = dao.rowsOf(cardA)
        assertEquals(40, allRows.size)
        assertTrue(allRows.all { it.time == fixedTime })
        assertConsistent(dao, fake)

        // Verify date grouping mapper works properly with Unix seconds
        val groups = mapTransactionsToGroups(allRows, nowMillis = fixedTime * 1000L)
        assertEquals(1, groups.size)
        assertEquals("Сьогодні", groups.first().label)
        assertEquals(40, groups.first().operations.size)
    }

    // Scenario 12: Сервер вернул пересекающуюся страницу -> IGNORE защищает от дублей
    @Test
    fun test12_overlappingServerPage_deduplicatedByIgnoreStrategy() = runTest {
        val fake =
            FakeStatementApi(cards = listOf(cardA), initialPerCard = 20, latencyMs = 5L, seed = 12L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

        repo.refresh(cardA)
        val initialRows = dao.rowsOf(cardA)
        assertEquals(20, initialRows.size)

        // Re-inserting overlapping entities directly through savePage / insert is ignored cleanly
        val duplicateSubset = initialRows.take(5)
        dao.insert(duplicateSubset)
        assertEquals(20, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)
    }

    // Scenario 13: Случайная последовательность операций -> I1 и I3 после каждого шага
    @Test
    fun test13_randomizedFuzzOperations_maintainsInvariantAfterEveryStep() = runTest {
        val fake =
            FakeStatementApi(cards = twoCards, initialPerCard = 35, latencyMs = 2L, seed = 13L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 10)
        val rng = Random(2026L)

        repo.refreshAll()
        assertConsistent(dao, fake)

        repeat(30) {
            val prevSync = dao.allSync().associateBy { it.cardId }
            val targetCard = if (rng.nextBoolean()) cardA else cardB

            // Occasionally inject a single transient network failure (recovered by retry())
            if (rng.nextInt(5) == 0) {
                fake.failNextRequest(times = 1)
            }

            when (rng.nextInt(5)) {
                0 -> repo.refresh(targetCard)
                1 -> {
                    val oldest = dao.sync(targetCard)?.oldestSeq
                    if (oldest != null) repo.loadMore(targetCard, oldest)
                }

                2 -> fake.appendNew(targetCard, count = rng.nextInt(1, 25))
                3 -> repo.refreshAll()
                4 -> repo.loadMoreAll()
            }

            // Check Invariant I1
            assertConsistent(dao, fake)

            // Check Invariant I3: oldestSeq only decreases, newestSeq only increases
            for ((cardId, oldS) in prevSync) {
                val newS = dao.sync(cardId)!!
                assertTrue("oldestSeq must not increase", newS.oldestSeq <= oldS.oldestSeq)
                assertTrue("newestSeq must not decrease", newS.newestSeq >= oldS.newestSeq)
            }
        }
    }

    // Scenario 14: Отмена refresh посреди заполнения дыры -> после отмены I1 выполнен; повторный refresh достраивает окно
    @Test
    fun test14_cancellationDuringMultiPageRefresh_keepsCommittedPagesValidAndResumesCleanly() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val fake = FakeStatementApi(
                cards = listOf(cardA),
                initialPerCard = 20,
                latencyMs = 100L,
                seed = 14L
            )
            val dao = InMemoryStatementDao()
            val repo =
                StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

            repo.refresh(cardA)
            assertEquals(20, dao.rowsOf(cardA).size)

            // Create a 3-page gap (60 new items)
            fake.appendNew(cardA, count = 60)

            val job = launch(testDispatcher) {
                repo.refresh(cardA)
            }

            // Advance time by 150ms: 1st newer page (100ms) completes and commits, 2nd page is in flight
            advanceTimeBy(150L)
            job.cancel(CancellationException("User navigated away"))
            advanceUntilIdle()

            // After cancellation mid-gap, page 1 (20 items -> 40 total) is committed and I1 holds!
            assertEquals(40, dao.rowsOf(cardA).size)
            assertConsistent(dao, fake)

            // Subsequent refresh resumes from newestSeq and loads the remaining 40 items (2 pages)
            repo.refresh(cardA)
            assertEquals(80, dao.rowsOf(cardA).size)
            assertEquals(fake.currentAsOfSeq(), dao.sync(cardA)!!.newestSeq)
            assertConsistent(dao, fake)
        }
}

