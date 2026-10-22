package com.example.monotest.data.api

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

class FakeStatementApi(
    private val cards: List<String>,
    private val initialPerCard: Int = 100,
    private val initialCountsByCard: Map<String, Int> = emptyMap(),
    private val latencyMs: Long = 300L,
    private val seed: Long = 42L,
    private val baseTimeSec: Long = System.currentTimeMillis() / 1000L
) : StatementApi {

    private val lock = Any()
    private val random = Random(seed)
    private val storage = LinkedHashMap<String, MutableList<TxDto>>()
    private var globalSeq = 0L

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
        globalSeq = 0L
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

        // Interleave initial transactions across cards so global seq and time grow monotonically together
        for (i in 0 until maxCount) {
            for (cardId in effectiveCards) {
                val countForCard = totalPerCard[cardId] ?: 0
                if (i < countForCard) {
                    globalSeq++
                    stepIndex++
                    val (merchant, category) = sampleMerchants[(globalSeq % sampleMerchants.size).toInt()]
                    val isIncome = merchant == "Поповнення картки" || (globalSeq % 5L == 0L)
                    val rawAmount = random.nextLong(15_00L, 1250_00L)
                    val amountKop = if (isIncome) rawAmount else -rawAmount
                    val cardLabel = cardNamesUk[cardId] ?: cardId
                    // Step 15 minutes (900s) per operation so recent ~30 items are Today, previous are Yesterday, etc.
                    val txTime = baseTimeSec - (totalExpected - stepIndex) * 900L

                    val list = storage.getOrPut(cardId) { mutableListOf() }
                    list.add(
                        TxDto(
                            id = "${cardId}_$globalSeq",
                            cardId = cardId,
                            seq = globalSeq,
                            time = txTime,
                            amountKop = amountKop,
                            title = if (isIncome && merchant != "Поповнення картки") {
                                "Від: Олександр М. #$globalSeq"
                            } else {
                                "$merchant #$globalSeq"
                            },
                            subtitle = "$category · $cardLabel"
                        )
                    )
                }
            }
        }
        for (cardId in effectiveCards) {
            storage.putIfAbsent(cardId, mutableListOf())
        }
    }

    /**
     * Appends [count] new transactions to [cardId] with strictly increasing global [seq].
     */
    fun appendNew(cardId: String, count: Int, customTimeSec: Long? = null): List<TxDto> =
        synchronized(lock) {
            val list = storage.getOrPut(cardId) { mutableListOf() }
            val now = customTimeSec ?: (System.currentTimeMillis() / 1000L).coerceAtLeast(
                baseTimeSec + globalSeq
            )
            val added = mutableListOf<TxDto>()
            val cardLabel = cardNamesUk[cardId] ?: cardId

            repeat(count) { idx ->
                globalSeq++
                val (merchant, category) = sampleMerchants[(globalSeq % sampleMerchants.size).toInt()]
                val isIncome = idx % 3 == 0
                val rawAmount = random.nextLong(25_00L, 950_00L)
                val amountKop = if (isIncome) rawAmount else -rawAmount
                val dto = TxDto(
                    id = "${cardId}_$globalSeq",
                    cardId = cardId,
                    seq = globalSeq,
                    time = if (customTimeSec != null) customTimeSec else now + idx,
                    amountKop = amountKop,
                    title = if (isIncome) "Поповнення #$globalSeq" else "$merchant #$globalSeq",
                    subtitle = "$category · $cardLabel (нова)"
                )
                list.add(dto)
                added.add(dto)
            }
            list.sortBy { it.seq }
            added
        }

    /**
     * Injects a custom transaction (useful for testing overlapping pages or specific edge cases).
     */
    fun injectRaw(dto: TxDto) = synchronized(lock) {
        val list = storage.getOrPut(dto.cardId) { mutableListOf() }
        list.add(dto)
        list.sortBy { it.seq }
        if (dto.seq > globalSeq) {
            globalSeq = dto.seq
        }
    }

    /**
     * Returns ground-truth snapshot of all transactions for [cardId] ordered by ascending `seq`.
     */
    fun items(cardId: String): List<TxDto> = synchronized(lock) {
        storage[cardId]?.toList().orEmpty()
    }

    fun currentAsOfSeq(): Long = synchronized(lock) { globalSeq }

    fun failNextOlder(times: Int = 1) = synchronized(lock) { failOlderCount = times }

    fun failNextNewer(times: Int = 1) = synchronized(lock) { failNewerCount = times }

    fun failNextRequest(times: Int = 1) = synchronized(lock) { failAnyCount = times }

    override suspend fun older(cardId: String, before: Long?, limit: Int): Page {
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
            val list = storage[cardId].orEmpty()
            val filtered = if (before == null) list else list.filter { it.seq < before }
            val pageItems = filtered.takeLast(limit).sortedByDescending { it.seq }
            val actualHasMore = filtered.size > pageItems.size
            Page(
                items = pageItems,
                hasMore = actualHasMore,
                asOfSeq = globalSeq
            )
        }
    }

    override suspend fun newer(cardId: String, after: Long, limit: Int): Page {
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
            val list = storage[cardId].orEmpty()
            val filtered = list.filter { it.seq > after }
            val pageItems = filtered.take(limit)
            val actualHasMore = filtered.size > pageItems.size
            Page(
                items = pageItems,
                hasMore = actualHasMore,
                asOfSeq = globalSeq
            )
        }
    }
}
