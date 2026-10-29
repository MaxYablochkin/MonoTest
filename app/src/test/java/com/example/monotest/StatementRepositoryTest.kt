@file:OptIn(ExperimentalCoroutinesApi::class)

package com.example.monotest

import com.example.monotest.cards.mapper.mapTransactionsToGroups
import com.example.monotest.data.api.FakeStatementApi
import com.example.monotest.data.api.Page
import com.example.monotest.data.api.StatementApi
import com.example.monotest.data.api.TxCursor
import com.example.monotest.database.CardSyncEntity
import com.example.monotest.database.StatementDao
import com.example.monotest.database.TxEntity
import com.example.monotest.domain.StatementRepository
import kotlinx.coroutines.CancellationException
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
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * SQL-accurate in-memory implementation of [StatementDao] that enforces:
 * - `PRIMARY KEY(id)` with `OnConflictStrategy.IGNORE`
 * - `ORDER BY time DESC, id DESC`
 * - Window predicate `(time, id) BETWEEN (loTime, loId) AND (hiTime, hiId)`
 * - Atomic `savePage(items, syncEntity)`
 */
class InMemoryStatementDao : StatementDao {
    private val lock = Any()
    private val txById = LinkedHashMap<String, TxEntity>()
    private val syncByCard = LinkedHashMap<String, CardSyncEntity>()

    private val txState = MutableStateFlow<List<TxEntity>>(emptyList())
    private val syncState = MutableStateFlow<List<CardSyncEntity>>(emptyList())

    override suspend fun insert(items: List<TxEntity>) = synchronized(lock) {
        for (item in items) {
            if (!txById.containsKey(item.id)) {
                txById[item.id] = item
            }
        }
        txState.value = txById.values.sortedByDescending { it.cursor }
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
        txState.map { list -> list.filter { it.cardId == c }.sortedByDescending { it.cursor } }

    override fun observeWindow(
        loTime: Long,
        loId: String,
        hiTime: Long,
        hiId: String
    ): Flow<List<TxEntity>> {
        val lo = TxCursor(time = loTime, id = loId)
        val hi = TxCursor(time = hiTime, id = hiId)
        return txState.map { list ->
            list.filter { it.cursor in lo..hi }.sortedByDescending { it.cursor }
        }
    }

    override suspend fun rowsOf(c: String): List<TxEntity> = synchronized(lock) {
        txById.values.filter { it.cardId == c }.sortedByDescending { it.cursor }
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
            for (item in items) {
                if (!txById.containsKey(item.id)) {
                    txById[item.id] = item
                }
            }
            syncByCard[syncEntity.cardId] = syncEntity
            txState.value = txById.values.sortedByDescending { it.cursor }
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
     * whose composite cursor `(time, id)` lies in `[oldestCursor .. newestCursor]`,
     * ordered by `(time DESC, id DESC)`, with no holes or duplicates.
     */
    private suspend fun assertConsistent(dao: StatementDao, fake: FakeStatementApi) {
        val syncs = dao.allSync()
        for (s in syncs) {
            val lo = s.oldestCursor
            val hi = s.newestCursor
            val truth = fake.items(s.cardId)
                .filter { it.cursor in lo..hi }
                .sortedByDescending { it.cursor }
                .map { it.id }
            val actualRows = dao.rowsOf(s.cardId)
            val actualIds = actualRows.map { it.id }
            assertEquals(
                "Invariant I1 violated for card=${s.cardId} window=[$lo..$hi]",
                truth,
                actualIds
            )
            assertEquals(
                "Duplicate IDs found for card=${s.cardId}",
                actualIds.distinct(),
                actualIds
            )
        }
    }

    // Scenario 1: Первое открытие карты -> один вызов older, одна страница в БД
    @Test
    fun test01_firstOpen_loadsSingleOlderPage() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 1L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)

        assertEquals(1, fake.olderCalls)
        assertEquals(0, fake.newerCalls)
        assertEquals(20, dao.rowsOf(cardA).size)
        val syncA = dao.sync(cardA)!!
        assertFalse(syncA.reachedEnd)
        assertEquals(fake.currentAsOf(cardA), syncA.newestCursor)
        assertConsistent(dao, fake)
    }

    // Scenario 2: loadMore -> добавлена ровно одна страница, порядок по (time, id), дублей нет
    @Test
    fun test02_loadMore_appendsNextOlderPageInOrderWithoutDuplicates() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 2L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        val firstOldest = dao.sync(cardA)!!.oldestCursor

        repo.loadMore(cardA, expectedOldest = firstOldest)

        assertEquals(2, fake.olderCalls)
        assertEquals(40, dao.rowsOf(cardA).size)
        assertFalse(dao.sync(cardA)!!.reachedEnd)
        assertTrue(dao.sync(cardA)!!.oldestCursor < firstOldest)
        assertConsistent(dao, fake)
    }

    // Scenario 3: Пауза: +55 при странице 20 (включая операции с одинаковым временем) -> refresh делает 3 вызова newer (20+20+15)
    @Test
    fun test03_pauseWith55NewItemsIncludingSameTime_refreshFetchesThreeNewerPagesAndClosesGap() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 25, latencyMs = 10L, seed = 3L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        assertEquals(20, dao.rowsOf(cardA).size)

        // Append 55 new operations where groups of 7 share the exact same timestamp across page boundaries
        fake.appendNew(cardA, count = 55, sameTimestampGroupSize = 7)
        repo.refresh(cardA)

        assertEquals(3, fake.newerCalls)
        assertEquals(75, dao.rowsOf(cardA).size)
        assertEquals(fake.currentAsOf(cardA), dao.sync(cardA)!!.newestCursor)
        assertTrue(repo.gapCards.value.isEmpty())
        assertConsistent(dao, fake)
    }

    // Scenario 4: Карты закешированы на разную глубину -> общая лента не содержит операций глубже окна B; loadMoreAll грузит только B
    @Test
    fun test04_cardADeeperThanCardB_unifiedTimelineExcludesDeepTailAndLoadMoreAllLoadsShallowest() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 4L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refreshAll()
        // Make cardA deeper by 1 additional page (40 items total on A, 20 on B)
        val oldestA1 = dao.sync(cardA)!!.oldestCursor
        repo.loadMore(cardA, expectedOldest = oldestA1)

        val syncA = dao.sync(cardA)!!
        val syncB = dao.sync(cardB)!!
        assertTrue("cardA should be deeper (smaller oldestCursor) than cardB", syncA.oldestCursor < syncB.oldestCursor)

        val allFeedBefore = repo.observeAll().first()
        // No transaction in unified feed may be older than syncB.oldestCursor
        assertTrue(allFeedBefore.all { it.cursor >= syncB.oldestCursor })

        val callsBefore = fake.olderCalls
        repo.loadMoreAll()

        // Only shallowest card (cardB) was loaded
        assertEquals(callsBefore + 1, fake.olderCalls)
        assertEquals(40, dao.rowsOf(cardB).size)
        assertConsistent(dao, fake)
    }

    // Scenario 5: Раздельные выписки закешированы на разную глубину и обновлены в разное время
    @Test
    fun test05_cardsCachedToDifferentDepthAndRefreshedAtDifferentTimes_unifiedFeedUsesIntersection() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 45, latencyMs = 10L, seed = 5L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refreshAll()
        // Load 1 extra older page for cardA (cardA has 40 items, cardB has 20 items)
        repo.loadMore(cardA, dao.sync(cardA)!!.oldestCursor)
        val initialNewestB = dao.sync(cardB)!!.newestCursor
        val oldestB = dao.sync(cardB)!!.oldestCursor

        // Advance server time and append new operations to cardA, then refresh ONLY cardA
        fake.advanceServerClock(600L)
        val addedToA = fake.appendNew(cardA, count = 5)
        repo.refresh(cardA)

        // Card A has the new items in its own timeline
        val cardAFeed = repo.observeCard(cardA).first()
        assertTrue(cardAFeed.map { it.id }.containsAll(addedToA.map { it.id }))

        // Unified timeline is bounded below by oldestB and above by initialNewestB
        val unifiedBeforeBRefresh = repo.observeAll().first()
        assertTrue(unifiedBeforeBRefresh.all { it.cursor in oldestB..initialNewestB })
        assertTrue(unifiedBeforeBRefresh.none { it.id in addedToA.map { tx -> tx.id }.toSet() })

        // Now refresh Card B at a later time -> unified upper bound advances and shows cardA's new operations
        fake.advanceServerClock(300L)
        repo.refresh(cardB)
        val unifiedAfterBRefresh = repo.observeAll().first()
        assertTrue(unifiedAfterBRefresh.map { it.id }.containsAll(addedToA.map { it.id }))
        assertConsistent(dao, fake)
    }

    // Scenario 6: Ошибка после нескольких успешных страниц newer -> сохранённые данные целостны, повтор продолжает работу
    @Test
    fun test06_errorAfterMultipleSuccessfulNewerPages_preservesIntegrityAndContinuesOnRetry() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 20, latencyMs = 10L, seed = 6L)
        val dao = InMemoryStatementDao()
        // Custom wrapper that succeeds on the 1st and 2nd newer pages, then fails on the 3rd page across retries
        var newerAttempt = 0
        var shouldFailThirdPage = true
        val faultyApi = object : StatementApi by fake {
            override suspend fun newer(cardId: String, after: TxCursor, limit: Int): Page {
                newerAttempt++
                // Calls 1 and 2 succeed (40 new items committed); calls 3, 4, 5 (retries of 3rd page) fail
                if (shouldFailThirdPage && newerAttempt >= 3) {
                    throw IOException("Network failure on 3rd newer page")
                }
                return fake.newer(cardId, after, limit)
            }
        }
        val repo = StatementRepository(api = faultyApi, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        fake.appendNew(cardA, count = 55)

        try {
            repo.refresh(cardA)
            fail("Expected IOException on 3rd newer page")
        } catch (_: IOException) {
            // Expected
        }

        // Pages 1 and 2 (40 new items -> 60 total) were atomically committed; Invariant I1 MUST hold!
        assertEquals(60, dao.rowsOf(cardA).size)
        assertTrue("Gap indicator must remain active on error", cardA in repo.gapCards.value)
        assertConsistent(dao, fake)

        // Retry after network recovers: continues from the 2nd page cursor and fetches only the remaining 15 items
        val newerCallsBeforeRetry = fake.newerCalls
        shouldFailThirdPage = false
        repo.refresh(cardA)

        assertEquals("Retry should only fetch the remaining 3rd page", newerCallsBeforeRetry + 1, fake.newerCalls)
        assertEquals(75, dao.rowsOf(cardA).size)
        assertEquals(fake.currentAsOf(cardA), dao.sync(cardA)!!.newestCursor)
        assertFalse("Gap indicator must clear after completion", cardA in repo.gapCards.value)
        assertConsistent(dao, fake)
    }

    // Scenario 7: Два refresh параллельно -> дублей нет, данные верны
    @Test
    fun test07_twoParallelRefreshes_produceNoDuplicatesAndConsistentWindow() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 20, latencyMs = 20L, seed = 7L)
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
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 50, latencyMs = 25L, seed = 8L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        val expectedOldest = dao.sync(cardA)!!.oldestCursor
        val olderCallsBefore = fake.olderCalls

        val d1 = async { repo.loadMore(cardA, expectedOldest) }
        val d2 = async { repo.loadMore(cardA, expectedOldest) }
        d1.await()
        d2.await()

        assertEquals("Only one older API call should be executed", olderCallsBefore + 1, fake.olderCalls)
        assertEquals(40, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)
    }

    // Scenario 9: Конец истории -> reachedEnd = true, следующий loadMore без запроса
    @Test
    fun test09_reachedEndOfHistory_setsReachedEndTrueAndSkipsSubsequentLoadMore() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 30, latencyMs = 10L, seed = 9L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        repo.refresh(cardA)
        assertFalse(dao.sync(cardA)!!.reachedEnd)

        val firstOldest = dao.sync(cardA)!!.oldestCursor
        repo.loadMore(cardA, firstOldest)

        val syncAfterEnd = dao.sync(cardA)!!
        assertTrue(syncAfterEnd.reachedEnd)
        assertEquals(30, dao.rowsOf(cardA).size)

        val callsAtEnd = fake.olderCalls
        repo.loadMore(cardA, syncAfterEnd.oldestCursor)
        assertEquals("No network request should be made when reachedEnd = true", callsAtEnd, fake.olderCalls)
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
        assertEquals(fake.currentAsOf(cardC), syncC.oldestCursor)
        assertEquals(fake.currentAsOf(cardC), syncC.newestCursor)

        val allFeed = repo.observeAll().first()
        val expectedAllIds = (fake.items(cardA) + fake.items(cardB) + fake.items(cardC))
            .filter { it.cursor in syncA.oldestCursor..syncA.newestCursor }
            .sortedByDescending { it.cursor }
            .map { it.id }
        assertEquals(expectedAllIds, allFeed.map { it.id })
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
        fake.appendNew(cardA, count = 45, customTimeSec = fixedTime)

        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

        repo.refresh(cardA)
        assertEquals(20, dao.rowsOf(cardA).size)

        val oldest1 = dao.sync(cardA)!!.oldestCursor
        repo.loadMore(cardA, oldest1)
        assertEquals(40, dao.rowsOf(cardA).size)

        val oldest2 = dao.sync(cardA)!!.oldestCursor
        repo.loadMore(cardA, oldest2)

        val allRows = dao.rowsOf(cardA)
        assertEquals(45, allRows.size)
        assertTrue(allRows.all { it.time == fixedTime })
        assertConsistent(dao, fake)

        val groups = mapTransactionsToGroups(allRows, nowMillis = fixedTime * 1000L)
        assertEquals(1, groups.size)
        assertEquals("Сьогодні", groups.first().label)
        assertEquals(45, groups.first().operations.size)
    }

    // Scenario 12: Дубли на уровне БД отсекает IGNORE, а страница, пересекающая окно, отклоняется репозиторием как нарушение контракта
    @Test
    fun test12_overlappingServerPage_dbDeduplicatesWithIgnoreAndRepoRejectsContractViolation() = runTest {
        val fake = FakeStatementApi(cards = listOf(cardA), initialPerCard = 20, latencyMs = 5L, seed = 12L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

        repo.refresh(cardA)
        val initialRows = dao.rowsOf(cardA)
        assertEquals(20, initialRows.size)

        // 1) Duplicate rows at DB level are cleanly ignored by OnConflictStrategy.IGNORE
        dao.insert(initialRows.take(5))
        assertEquals(20, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)

        // 2) A server page overlapping the already committed window is rejected by repository check(...)
        val brokenApi = object : StatementApi by fake {
            override suspend fun newer(cardId: String, after: TxCursor, limit: Int): Page {
                val overlappingItem = fake.items(cardId).last() // cursor <= after
                return Page(items = listOf(overlappingItem), hasMore = false, asOf = after)
            }
        }
        val repoWithBrokenServer = StatementRepository(api = brokenApi, dao = dao, cards = listOf(cardA), pageSize = 20)
        try {
            repoWithBrokenServer.refresh(cardA)
            fail("Expected IllegalStateException when server returns page overlapping committed window")
        } catch (_: IllegalStateException) {
            // Expected contract violation
        }
        assertConsistent(dao, fake)
    }

    // Scenario 13: Случайная последовательность операций -> I1 и монотонность границ после каждого шага
    @Test
    fun test13_randomizedFuzzOperations_maintainsInvariantAfterEveryStep() = runTest {
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 35, latencyMs = 2L, seed = 13L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 10)
        val rng = Random(2026L)

        repo.refreshAll()
        assertConsistent(dao, fake)

        repeat(30) {
            val prevSync = dao.allSync().associateBy { it.cardId }
            val targetCard = if (rng.nextBoolean()) cardA else cardB

            if (rng.nextInt(5) == 0) {
                fake.failNextRequest(times = 1)
            }

            when (rng.nextInt(5)) {
                0 -> repo.refresh(targetCard)
                1 -> {
                    val oldest = dao.sync(targetCard)?.oldestCursor
                    if (oldest != null) repo.loadMore(targetCard, oldest)
                }
                2 -> fake.appendNew(
                    targetCard,
                    count = rng.nextInt(1, 25),
                    sameTimestampGroupSize = rng.nextInt(1, 5)
                )
                3 -> repo.refreshAll()
                4 -> repo.loadMoreAll()
            }

            assertConsistent(dao, fake)

            for ((cardId, oldS) in prevSync) {
                val newS = dao.sync(cardId)!!
                assertTrue("oldestCursor must not increase", newS.oldestCursor <= oldS.oldestCursor)
                assertTrue("newestCursor must not decrease", newS.newestCursor >= oldS.newestCursor)
            }
        }
    }

    // Scenario 14: Отмена refresh посреди заполнения дыры -> после отмены I1 выполнен; повторный refresh достраивает окно
    @Test
    fun test14_cancellationDuringMultiPageRefresh_keepsCommittedPagesValidAndResumesCleanly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeStatementApi(cards = listOf(cardA), initialPerCard = 20, latencyMs = 100L, seed = 14L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

        repo.refresh(cardA)
        assertEquals(20, dao.rowsOf(cardA).size)

        fake.appendNew(cardA, count = 60)

        val job = launch(testDispatcher) {
            repo.refresh(cardA)
        }

        // 1st newer page (100ms) completes and commits, 2nd page is in flight
        advanceTimeBy(150L)
        job.cancel(CancellationException("User navigated away"))
        advanceUntilIdle()

        assertEquals(40, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)

        repo.refresh(cardA)
        assertEquals(80, dao.rowsOf(cardA).size)
        assertEquals(fake.currentAsOf(cardA), dao.sync(cardA)!!.newestCursor)
        assertConsistent(dao, fake)
    }

    // Scenario 15: Перезапуск приложения, одновременное обновление и догрузка, а также Reset во время загрузки
    @Test
    fun test15_restartPreservesCache_concurrentRefreshAndLoadMore_andResetDuringLoad() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeStatementApi(cards = twoCards, initialPerCard = 50, latencyMs = 50L, seed = 15L)
        val dao = InMemoryStatementDao()

        // 1) Initial session loads 2 pages (40 items) on cardA
        val repoSession1 = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)
        repoSession1.refreshAll()
        repoSession1.loadMore(cardA, dao.sync(cardA)!!.oldestCursor)
        assertEquals(40, dao.rowsOf(cardA).size)

        // 2) Simulate app restart: new Repository instance over the SAME persistent Room DAO
        fake.appendNew(cardA, count = 10)
        val repoSession2 = StatementRepository(api = fake, dao = dao, cards = twoCards, pageSize = 20)

        // Concurrently trigger refresh(cardA) and loadMore(cardA) right after restart
        val expectedOldest = dao.sync(cardA)!!.oldestCursor
        val refreshDeferred = async(testDispatcher) { repoSession2.refresh(cardA) }
        val loadMoreDeferred = async(testDispatcher) { repoSession2.loadMore(cardA, expectedOldest) }
        advanceUntilIdle()
        refreshDeferred.await()
        loadMoreDeferred.await()

        // Cache depth (40) + 10 new items + 10 older items = 60 items, no data lost on restart!
        assertEquals(60, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)

        // 3) Trigger Reset (clearLocalStorage) while a multi-page refresh is actively in flight
        fake.appendNew(cardA, count = 60)
        val inFlightRefresh = launch(testDispatcher) {
            repoSession2.refresh(cardA)
        }
        advanceTimeBy(75L) // 1st page committed, 2nd request in flight
        val resetJob = launch(testDispatcher) {
            repoSession2.clearLocalStorage()
        }
        advanceUntilIdle()
        inFlightRefresh.cancel()
        resetJob.join()

        // After Reset completes and we cancel/finish, DB is either cleanly empty or consistent (no orphan rows without sync)
        if (dao.allSync().isEmpty()) {
            assertEquals(0, dao.observeTotalCount().first())
        } else {
            assertConsistent(dao, fake)
        }
    }

    // Scenario 16: Операция появляется на сервере с задержкой (late arrival внутри окна расчётов)
    @Test
    fun test16_lateArrivingOperationWithinWatermark_isPickedUpBySubsequentRefreshWithoutGaps() = runTest {
        val fake = FakeStatementApi(cards = listOf(cardA), initialPerCard = 20, latencyMs = 5L, seed = 16L)
        val dao = InMemoryStatementDao()
        val repo = StatementRepository(api = fake, dao = dao, cards = listOf(cardA), pageSize = 20)

        repo.refresh(cardA)
        val lastInitialTime = dao.sync(cardA)!!.newestTime

        // Hold settlement watermark at T+100 while a newer operation at T+200 is already in storage
        val delayedTxTime = lastInitialTime + 100L
        fake.holdWatermark(cardA, holdTimeSec = delayedTxTime)
        fake.injectRaw(
            com.example.monotest.data.api.TxDto(
                id = "tx_card_black_fast",
                cardId = cardA,
                time = lastInitialTime + 200L,
                amountKop = -150_00L,
                title = "Швидка операція",
                subtitle = "Онлайн"
            )
        )

        // Refresh while hold is active: server only exposes settled state before delayedTxTime
        repo.refresh(cardA)
        assertEquals(20, dao.rowsOf(cardA).size)
        assertConsistent(dao, fake)

        // Now the delayed operation at T+100 finally arrives and releases the watermark
        val lateDto = fake.injectLate(cardA, txTimeSec = delayedTxTime, customId = "tx_card_black_late")
        repo.refresh(cardA)

        // Both the delayed operation (T+100) and the newer operation (T+200) are now in DB in exact order!
        val rows = dao.rowsOf(cardA)
        assertEquals(22, rows.size)
        assertEquals("tx_card_black_fast", rows[0].id)
        assertEquals(lateDto.id, rows[1].id)
        assertConsistent(dao, fake)
    }

    // Scenario 17: История содержит одинаковые день и месяц разных лет -> разные группы с указанием года
    @Test
    fun test17_sameDayAndMonthAcrossDifferentYears_groupedSeparatelyWithYearInLabel() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(2026, Calendar.MAY, 15, 14, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val may15_2026_sec = cal.timeInMillis / 1000L

        cal.set(2025, Calendar.MAY, 15, 14, 0, 0)
        val may15_2025_sec = cal.timeInMillis / 1000L

        cal.set(2026, Calendar.OCTOBER, 28, 12, 0, 0)
        val nowMillis = cal.timeInMillis

        val tx2026 = TxEntity(
            id = "tx_2026",
            cardId = cardA,
            time = may15_2026_sec,
            amountKop = -100_00L,
            title = "Покупка 2026",
            subtitle = "Чорна"
        )
        val tx2025 = TxEntity(
            id = "tx_2025",
            cardId = cardA,
            time = may15_2025_sec,
            amountKop = -200_00L,
            title = "Покупка 2025",
            subtitle = "Чорна"
        )

        val groups = mapTransactionsToGroups(listOf(tx2026, tx2025), nowMillis = nowMillis)

        assertEquals("Operations from May 15 of different years must NOT be merged into one group", 2, groups.size)
        assertFalse("Group labels must be distinct", groups[0].label == groups[1].label)
        assertTrue("2025 group label must contain year '2025'", groups[1].label.contains("2025"))
        assertEquals(listOf("tx_2026"), groups[0].operations.map { it.id })
        assertEquals(listOf("tx_2025"), groups[1].operations.map { it.id })
    }
}