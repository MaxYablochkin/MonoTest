package com.example.monotest.data.api

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

/**
 * Mock server that stores transactions per card without any global sequence counter (`seq`).
 *
 * - Canonical ordering: `(time, id)` (via [TxCursor]).
 * - Supports multiple operations with identical [TxDto.time] (tie-broken deterministically by unique [TxDto.id]).
 * - Supports late-arriving operations (operations arriving with `time` up to [maxLateArrivalSec] behind
 *   the current server clock `nowSec`, or before a pending hold/settlement watermark).
 * - Guarantees gap-free pagination by advancing `Page.asOf` only up to the safe settlement watermark
 *   `effectiveAsOfCursor(cardId)` (bounded by `serverNowSec - maxLateArrivalSec` and any unsettled hold).
 */
class FakeStatementApi(
    private val cards: List<String>,
    private val initialPerCard: Int = 100,
    private val initialCountsByCard: Map<String, Int> = emptyMap(),
    private val latencyMs: Long = 300L,
    private val seed: Long = 42L,
    private val baseTimeSec: Long = System.currentTimeMillis() / 1000L,
    private val maxLateArrivalSec: Long = 0L
) : StatementApi {

    constructor(cards: List<String>) : this(
        cards = cards,
        initialPerCard = 100,
        initialCountsByCard = emptyMap(),
        latencyMs = 300L,
        seed = 42L,
        baseTimeSec = System.currentTimeMillis() / 1000L,
        maxLateArrivalSec = 0L
    )

    private val lock = Any()
    private val random = Random(seed)
    private val storage = LinkedHashMap<String, MutableList<TxDto>>()
    private val nextCardItemIndex = LinkedHashMap<String, Int>()
    private var serverNowSec: Long = baseTimeSec
    private val pendingWatermarkByCard = LinkedHashMap<String, TxCursor>()

    @Volatile
    var olderCalls = 0
        private set

    @Volatile
    var newerCalls = 0
        private set

    private var failOlderCount = 0
    private var failNewerCount = 0
    private var failAnyCount = 0

    private val sampleMerchants = listOf(
        "АТБ-Маркет" to "Продукти",
        "Сільпо" to "Супермаркет",
        "Rozetka" to "Онлайн-покупка",
        "Uklon" to "Таксі",
        "OKKO" to "АЗС",
        "Нова Пошта" to "Доставка",
        "Idealist Coffee" to "Кав'ярня",
        "Поповнення картки" to "Переказ",
        "Comfy" to "Техніка",
        "Аптека Доброго Дня" to "Здоров'я"
    )

    private val cardNamesUk = mapOf(
        "card_black" to "Чорна",
        "card_white" to "Біла",
        "card_platinum" to "Платинова"
    )

    init {
        resetInitialData()
    }

    fun resetInitialData() = synchronized(lock) {
        storage.clear()
        nextCardItemIndex.clear()
        pendingWatermarkByCard.clear()
        serverNowSec = baseTimeSec
        olderCalls = 0
        newerCalls = 0
        failOlderCount = 0
        failNewerCount = 0
        failAnyCount = 0

        val effectiveCards = cards.ifEmpty {
            listOf("card_black", "card_white", "card_platinum")
        }

        val totalPerCard = effectiveCards.associateWith { cardId ->
            initialCountsByCard[cardId] ?: initialPerCard
        }
        val maxCount = totalPerCard.values.maxOrNull() ?: 0
        val totalExpected = totalPerCard.values.sum().coerceAtLeast(1)
        var stepIndex = 0

        for (i in 0 until maxCount) {
            for ((cardIdx, cardId) in effectiveCards.withIndex()) {
                val countForCard = totalPerCard[cardId] ?: 0
                if (i < countForCard) {
                    stepIndex++
                    val localIdx = (nextCardItemIndex[cardId] ?: 0) + 1
                    nextCardItemIndex[cardId] = localIdx

                    val merchantIdx = (localIdx + cardIdx * 3) % sampleMerchants.size
                    val (merchant, category) = sampleMerchants[merchantIdx]
                    val isIncome = merchant == "Поповнення картки" || (localIdx % 5 == 0)
                    val rawAmount = random.nextLong(15_00L, 1250_00L)
                    val amountKop = if (isIncome) rawAmount else -rawAmount
                    val cardLabel = cardNamesUk[cardId] ?: cardId
                    // Step 15 minutes (900s) per operation; occasional identical timestamps across cards/ops
                    val txTime = baseTimeSec - (totalExpected - stepIndex) * 900L
                    val txId = formatTxId(cardId, localIdx)

                    val list = storage.getOrPut(cardId) { mutableListOf() }
                    list.add(
                        TxDto(
                            id = txId,
                            cardId = cardId,
                            time = txTime,
                            amountKop = amountKop,
                            title = if (isIncome && merchant != "Поповнення картки") {
                                "Від: Олександр М."
                            } else {
                                merchant
                            },
                            subtitle = "$category · $cardLabel"
                        )
                    )
                }
            }
        }
        for (cardId in effectiveCards) {
            storage.getOrPut(cardId) { mutableListOf() }.sortBy { it.cursor }
        }
    }

    private fun formatTxId(cardId: String, localIndex: Int): String =
        "tx_${cardId}_${localIndex.toString().padStart(6, '0')}"

    /**
     * Appends [count] new transactions to [cardId].
     * If [customTimeSec] is provided, all [count] operations receive that exact timestamp (testing
     * multi-page bursts with identical `time`). Otherwise, timestamps advance from `serverNowSec`.
     */
    fun appendNew(
        cardId: String,
        count: Int,
        customTimeSec: Long? = null,
        sameTimestampGroupSize: Int = 1
    ): List<TxDto> = synchronized(lock) {
        val list = storage.getOrPut(cardId) { mutableListOf() }
        val maxExistingTime = storage.values.flatten().maxOfOrNull { it.time } ?: baseTimeSec
        val startTime = customTimeSec ?: maxOf(serverNowSec + 1L, maxExistingTime + 1L)
        val added = mutableListOf<TxDto>()
        val cardLabel = cardNamesUk[cardId] ?: cardId
        val groupSize = sameTimestampGroupSize.coerceAtLeast(1)

        repeat(count) { idx ->
            val localIdx = (nextCardItemIndex[cardId] ?: 0) + 1
            nextCardItemIndex[cardId] = localIdx

            val (merchant, category) = sampleMerchants[localIdx % sampleMerchants.size]
            val isIncome = idx % 3 == 0
            val rawAmount = random.nextLong(25_00L, 950_00L)
            val amountKop = if (isIncome) rawAmount else -rawAmount
            val txTime = if (customTimeSec != null) {
                customTimeSec
            } else {
                startTime + (idx / groupSize) * 60L
            }
            val txId = formatTxId(cardId, localIdx)

            val dto = TxDto(
                id = txId,
                cardId = cardId,
                time = txTime,
                amountKop = amountKop,
                title = if (isIncome) "Поповнення картки" else merchant,
                subtitle = "$category · $cardLabel (нова)"
            )
            list.add(dto)
            added.add(dto)
            if (txTime > serverNowSec) {
                serverNowSec = txTime
            }
        }
        list.sortBy { it.cursor }
        added
    }

    /**
     * Advances the server clock so each subsequent refresh gets a fresh `asOf` timestamp
     * even when no new transactions occurred on that card.
     */
    fun advanceServerClock(deltaSec: Long) = synchronized(lock) {
        require(deltaSec >= 0L)
        serverNowSec += deltaSec
    }

    /**
     * Reserves a settlement watermark for [cardId] so the server does not advance `asOf` past
     * an in-flight / delayed operation at [holdTimeSec] until [injectLate] or [releaseWatermark] is called.
     */
    fun holdWatermark(cardId: String, holdTimeSec: Long) = synchronized(lock) {
        // Hold just before holdTimeSec so operations at >= holdTimeSec are not finalized yet
        pendingWatermarkByCard[cardId] = TxCursor(time = holdTimeSec - 1L, id = "\uFFFF")
    }

    fun releaseWatermark(cardId: String) = synchronized(lock) {
        pendingWatermarkByCard.remove(cardId)
    }

    /**
     * Simulates a late-arriving operation (e.g., offline terminal / delayed clearing) with timestamp [txTimeSec]
     * that is older than the latest operations already on the server, but >= the safe settlement watermark.
     */
    fun injectLate(
        cardId: String,
        txTimeSec: Long,
        customId: String? = null,
        amountKop: Long = -42_00L,
        title: String = "Термінал (із затримкою)"
    ): TxDto = synchronized(lock) {
        val list = storage.getOrPut(cardId) { mutableListOf() }
        val localIdx = (nextCardItemIndex[cardId] ?: 0) + 1
        nextCardItemIndex[cardId] = localIdx
        val cardLabel = cardNamesUk[cardId] ?: cardId
        val txId = customId ?: formatTxId(cardId, localIdx)
        val dto = TxDto(
            id = txId,
            cardId = cardId,
            time = txTimeSec,
            amountKop = amountKop,
            title = title,
            subtitle = "Запізніла проводка · $cardLabel"
        )
        list.add(dto)
        list.sortBy { it.cursor }
        pendingWatermarkByCard.remove(cardId)
        if (txTimeSec > serverNowSec) {
            serverNowSec = txTimeSec
        }
        dto
    }

    /**
     * Injects a custom transaction (useful for testing overlapping pages or specific edge cases).
     */
    fun injectRaw(dto: TxDto) = synchronized(lock) {
        val list = storage.getOrPut(dto.cardId) { mutableListOf() }
        list.add(dto)
        list.sortBy { it.cursor }
        if (dto.time > serverNowSec) {
            serverNowSec = dto.time
        }
    }

    /**
     * Returns ground-truth snapshot of settled transactions for [cardId] ordered by ascending `(time, id)`.
     */
    fun items(cardId: String): List<TxDto> = synchronized(lock) {
        val cap = effectiveAsOfCursorLocked(cardId)
        storage[cardId].orEmpty().filter { it.cursor <= cap }.sortedBy { it.cursor }
    }

    /**
     * Returns all raw transactions in server storage for [cardId] (including unsettled ones if a hold is active).
     */
    fun rawItems(cardId: String): List<TxDto> = synchronized(lock) {
        storage[cardId]?.toList().orEmpty()
    }

    fun currentAsOf(cardId: String? = null): TxCursor = synchronized(lock) {
        if (cardId != null) {
            effectiveAsOfCursorLocked(cardId)
        } else {
            val safeTime = serverNowSec - maxLateArrivalSec
            TxCursor(time = safeTime, id = "\uFFFF")
        }
    }

    private fun effectiveAsOfCursorLocked(cardId: String): TxCursor {
        val safeTime = serverNowSec - maxLateArrivalSec
        val defaultCap = TxCursor(time = safeTime, id = "\uFFFF")
        val holdCap = pendingWatermarkByCard[cardId]
        val cap = if (holdCap != null && holdCap < defaultCap) holdCap else defaultCap
        val latestSettledItem = storage[cardId].orEmpty().lastOrNull { it.cursor <= cap }
        return if (latestSettledItem != null && latestSettledItem.time == cap.time) {
            maxOf(cap, latestSettledItem.cursor)
        } else {
            cap
        }
    }

    fun failNextOlder(times: Int = 1) = synchronized(lock) { failOlderCount = times }

    fun failNextNewer(times: Int = 1) = synchronized(lock) { failNewerCount = times }

    fun failNextRequest(times: Int = 1) = synchronized(lock) { failAnyCount = times }

    override suspend fun older(cardId: String, before: TxCursor?, limit: Int): Page {
        if (latencyMs > 0) delay(latencyMs)
        return synchronized(lock) {
            olderCalls++
            if (failAnyCount > 0) {
                failAnyCount--
                throw IOException("Simulated network error (any)")
            }
            if (failOlderCount > 0) {
                failOlderCount--
                throw IOException("Simulated network error (older)")
            }
            val asOf = effectiveAsOfCursorLocked(cardId)
            val settled = storage[cardId].orEmpty().filter { it.cursor <= asOf }
            val filtered = if (before == null) {
                settled
            } else {
                settled.filter { it.cursor < before }
            }
            val pageItems = filtered.takeLast(limit).sortedByDescending { it.cursor }
            val actualHasMore = filtered.size > pageItems.size
            Page(
                items = pageItems,
                hasMore = actualHasMore,
                asOf = asOf
            )
        }
    }

    override suspend fun newer(cardId: String, after: TxCursor, limit: Int): Page {
        if (latencyMs > 0) delay(latencyMs)
        return synchronized(lock) {
            newerCalls++
            if (failAnyCount > 0) {
                failAnyCount--
                throw IOException("Simulated network error (any)")
            }
            if (failNewerCount > 0) {
                failNewerCount--
                throw IOException("Simulated network error (newer)")
            }
            val asOf = effectiveAsOfCursorLocked(cardId)
            val settled = storage[cardId].orEmpty().filter { it.cursor <= asOf }
            val filtered = settled.filter { it.cursor > after }.sortedBy { it.cursor }
            val pageItems = filtered.take(limit)
            val actualHasMore = filtered.size > pageItems.size
            Page(
                items = pageItems,
                hasMore = actualHasMore,
                asOf = asOf
            )
        }
    }
}

