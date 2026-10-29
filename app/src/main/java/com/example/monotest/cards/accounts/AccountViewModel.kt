package com.example.monotest.cards.accounts

import androidx.lifecycle.viewModelScope
import com.example.monotest.cards.mapper.mapTransactionsToGroups
import com.example.monotest.cards.mapper.toOperation
import com.example.monotest.core.BaseViewModel
import com.example.monotest.data.api.FakeStatementApi
import com.example.monotest.data.api.TxCursor
import com.example.monotest.database.CardSyncEntity
import com.example.monotest.database.TxEntity
import com.example.monotest.domain.StatementRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private data class UiControlState(
    val selectedCardIndex: Int = 0,
    val isAllOperationsOpen: Boolean = false,
    val showAllCardsInSheet: Boolean = false,
    val refreshingCount: Int = 0,
    val loadingMoreCount: Int = 0,
    val errorMessage: String? = null,
    val isDebugPanelOpen: Boolean = false,
    val debugStatusMessage: String? = null,
    val demoTick: Int = 0,
    val lastFailedAction: (() -> Unit)? = null
) {
    val syncStatus: SyncStatus
        get() = when {
            refreshingCount > 0 -> SyncStatus.Refreshing
            loadingMoreCount > 0 -> SyncStatus.LoadingMore
            errorMessage != null -> SyncStatus.Error
            else -> SyncStatus.Idle
        }
}


class AccountViewModel(
    private val repository: StatementRepository,
    private val fakeApi: FakeStatementApi
) : BaseViewModel<AccountState, AccountAction, Nothing, Nothing>(
    initialState = AccountState()
) {
    private val controlFlow = MutableStateFlow(UiControlState())
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null
    private var resetJob: Job? = null

    init {
        // Preserve existing Room cache on cold start / restart and refresh on top of it
        refreshAll()
    }

    private val perCardFlows = combine(
        CardType.entries.map { card ->
            repository.observeCard(card.cardId)
        }
    ) { arrays: Array<List<TxEntity>> ->
        CardType.entries.mapIndexed { index, card ->
            card.cardId to arrays[index]
        }.toMap()
    }

    private val dataSnapshotFlow = combine(
        perCardFlows,
        repository.observeAll(),
        repository.observeSync(),
        repository.observeTotalCount(),
        repository.gapCards
    ) { byCard, allTx, syncList, totalCount, gaps ->
        DataSnapshot(
            byCard = byCard,
            allTx = allTx,
            syncByCard = syncList.associateBy { it.cardId },
            totalCount = totalCount,
            gapCards = gaps
        )
    }

    override val state: StateFlow<AccountState> = combine(
        dataSnapshotFlow,
        controlFlow
    ) { data, ctrl ->
        val cards = CardType.entries
        val activeCard = cards.getOrElse(ctrl.selectedCardIndex) { CardType.BLACK }
        val activeCardId = activeCard.cardId

        val recentByCard = data.byCard.mapValues { (_, list) ->
            list.take(3).map { it.toOperation() }
        }
        val cardGroupsByCard = data.byCard.mapValues { (_, list) ->
            mapTransactionsToGroups(list)
        }
        val oldestCursorByCard = data.syncByCard.mapValues { (_, sync) -> sync.oldestCursor }
        val reachedEndByCard = data.syncByCard.mapValues { (_, sync) -> sync.reachedEnd }

        val allGroups = mapTransactionsToGroups(data.allTx)
        val allSynced = data.syncByCard.keys.containsAll(CardType.cardIds)
        val allReachedEnd =
            allSynced && CardType.cardIds.all { data.syncByCard[it]?.reachedEnd == true }

        val activeGroups = if (ctrl.showAllCardsInSheet) {
            allGroups
        } else {
            cardGroupsByCard[activeCardId].orEmpty()
        }

        val hasGapInAll = data.gapCards.isNotEmpty()
        val hasGapInCurrent = if (ctrl.showAllCardsInSheet) {
            hasGapInAll
        } else {
            activeCardId in data.gapCards
        }

        val pendingNewByCard = CardType.cardIds.associateWith { cardId ->
            val syncedNewest = data.syncByCard[cardId]?.newestCursor
            if (syncedNewest == null) {
                0
            } else {
                fakeApi.items(cardId).count { it.cursor > syncedNewest }
            }
        }

        AccountState(
            cards = cards,
            selectedCardIndex = ctrl.selectedCardIndex,
            recentByCard = recentByCard,
            cardGroupsByCard = cardGroupsByCard,
            oldestCursorByCard = oldestCursorByCard,
            reachedEndByCard = reachedEndByCard,
            allOperationsGroups = allGroups,
            allReachedEnd = allReachedEnd,
            operationsGroups = activeGroups,
            isAllOperationsOpen = ctrl.isAllOperationsOpen,
            showAllCardsInSheet = ctrl.showAllCardsInSheet,
            syncStatus = ctrl.syncStatus,
            isRefreshing = ctrl.refreshingCount > 0,
            isLoadingMore = ctrl.loadingMoreCount > 0,
            errorMessage = ctrl.errorMessage,
            gapCards = data.gapCards,
            hasGapInCurrentFeed = hasGapInCurrent,
            hasGapInAllFeed = hasGapInAll,
            isDebugPanelOpen = ctrl.isDebugPanelOpen,
            debugStats = DebugStats(
                dbTotalRows = data.totalCount,
                olderCalls = fakeApi.olderCalls,
                newerCalls = fakeApi.newerCalls,
                serverAsOfTime = fakeApi.currentAsOf().time,
                syncByCard = data.syncByCard,
                pendingNewByCard = pendingNewByCard,
                statusMessage = ctrl.debugStatusMessage
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountState()
    )

    override fun onAction(action: AccountAction) {
        when (action) {
            is AccountAction.OnSelectCardIndex -> {
                controlFlow.update { it.copy(selectedCardIndex = action.index) }
            }

            AccountAction.OnAllOperationsClick -> {
                val activeCardId = CardType.entries
                    .getOrElse(controlFlow.value.selectedCardIndex) { CardType.BLACK }
                    .cardId
                controlFlow.update {
                    it.copy(isAllOperationsOpen = true, showAllCardsInSheet = false)
                }
                refreshCard(activeCardId)
            }

            AccountAction.OnCloseAllOperations -> {
                controlFlow.update { it.copy(isAllOperationsOpen = false) }
            }

            is AccountAction.OnToggleStatementMode -> {
                controlFlow.update { it.copy(showAllCardsInSheet = action.showAllCards) }
            }

            is AccountAction.OnRefreshCard -> refreshCard(action.cardId)
            AccountAction.OnRefreshAll -> refreshAll()
            is AccountAction.OnLoadMoreCard -> loadMoreCard(action.cardId, action.expectedOldest)
            AccountAction.OnLoadMoreAll -> loadMoreAll()
            AccountAction.OnRetry -> {
                val retryBlock = controlFlow.value.lastFailedAction ?: { refreshAll() }
                retryBlock.invoke()
            }

            AccountAction.OnToggleDebugPanel -> {
                controlFlow.update { it.copy(isDebugPanelOpen = !it.isDebugPanelOpen) }
            }

            is AccountAction.OnDemoAppendNew -> {
                fakeApi.appendNew(action.cardId, action.count)
                controlFlow.update {
                    it.copy(
                        demoTick = it.demoTick + 1,
                        debugStatusMessage = "На сервері згенеровано +${action.count} нових операцій для ${action.cardId}. Натисніть зелену кнопку нижче або відкрийте виписку картки."
                    )
                }
            }

            AccountAction.OnDemoMakeBlackDeeper -> {
                makeBlackCardDeeper()
            }

            is AccountAction.OnDemoFailNext -> {
                fakeApi.failNextRequest(action.times)
                controlFlow.update {
                    it.copy(
                        demoTick = it.demoTick + 1,
                        debugStatusMessage = "Наступні ${action.times} запити до API впадуть з IOException."
                    )
                }
            }

            AccountAction.OnDemoResetAll -> {
                resetDemoData()
            }
        }
    }

    private fun refreshCard(cardId: String) {
        val previous = refreshJob
        refreshJob = viewModelScope.launch {
            previous?.cancel()
            controlFlow.update {
                it.copy(
                    refreshingCount = it.refreshingCount + 1,
                    errorMessage = null,
                    lastFailedAction = { refreshCard(cardId) }
                )
            }
            try {
                repository.refresh(cardId)
                controlFlow.update {
                    it.copy(
                        errorMessage = null,
                        demoTick = it.demoTick + 1
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controlFlow.update {
                    it.copy(
                        errorMessage = "Помилка мережі при оновленні картки (${e.message ?: "IOException"})",
                        demoTick = it.demoTick + 1
                    )
                }
            } finally {
                controlFlow.update {
                    it.copy(refreshingCount = (it.refreshingCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun refreshAll() {
        val previous = refreshJob
        refreshJob = viewModelScope.launch {
            previous?.cancel()
            controlFlow.update {
                it.copy(
                    refreshingCount = it.refreshingCount + 1,
                    errorMessage = null,
                    lastFailedAction = { refreshAll() }
                )
            }
            try {
                repository.refreshAll()
                controlFlow.update {
                    it.copy(
                        errorMessage = null,
                        demoTick = it.demoTick + 1
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controlFlow.update {
                    it.copy(
                        errorMessage = "Не вдалося оновити виписку (${e.message ?: "IOException"})",
                        demoTick = it.demoTick + 1
                    )
                }
            } finally {
                controlFlow.update {
                    it.copy(refreshingCount = (it.refreshingCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun loadMoreCard(cardId: String, expectedOldest: TxCursor) {
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            controlFlow.update {
                it.copy(
                    loadingMoreCount = it.loadingMoreCount + 1,
                    errorMessage = null,
                    lastFailedAction = { loadMoreCard(cardId, expectedOldest) }
                )
            }
            try {
                repository.loadMore(cardId, expectedOldest)
                controlFlow.update {
                    it.copy(
                        errorMessage = null,
                        demoTick = it.demoTick + 1
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controlFlow.update {
                    it.copy(
                        errorMessage = "Не вдалося довантажити історію (${e.message ?: "IOException"})",
                        demoTick = it.demoTick + 1
                    )
                }
            } finally {
                controlFlow.update {
                    it.copy(loadingMoreCount = (it.loadingMoreCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun loadMoreAll() {
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            controlFlow.update {
                it.copy(
                    loadingMoreCount = it.loadingMoreCount + 1,
                    errorMessage = null,
                    lastFailedAction = { loadMoreAll() }
                )
            }
            try {
                repository.loadMoreAll()
                controlFlow.update {
                    it.copy(
                        errorMessage = null,
                        demoTick = it.demoTick + 1
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controlFlow.update {
                    it.copy(
                        errorMessage = "Не вдалося довантажити загальну стрічку (${e.message ?: "IOException"})",
                        demoTick = it.demoTick + 1
                    )
                }
            } finally {
                controlFlow.update {
                    it.copy(loadingMoreCount = (it.loadingMoreCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun makeBlackCardDeeper() {
        val previous = loadMoreJob
        loadMoreJob = viewModelScope.launch {
            previous?.cancel()
            controlFlow.update {
                it.copy(
                    loadingMoreCount = it.loadingMoreCount + 1,
                    errorMessage = null,
                    lastFailedAction = { makeBlackCardDeeper() }
                )
            }
            try {
                repository.loadMorePages(CardType.BLACK.cardId, pages = 3)
                controlFlow.update {
                    it.copy(
                        demoTick = it.demoTick + 1,
                        debugStatusMessage = "Чорна картка завантажена на 3 сторінки (60 операцій) глибше за інші."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controlFlow.update {
                    it.copy(
                        demoTick = it.demoTick + 1,
                        errorMessage = "Помилка демо-сценарію (${e.message ?: "IOException"})"
                    )
                }
            } finally {
                controlFlow.update {
                    it.copy(loadingMoreCount = (it.loadingMoreCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun resetDemoData() {
        val prevRefresh = refreshJob
        val prevLoadMore = loadMoreJob
        val prevReset = resetJob
        resetJob = viewModelScope.launch {
            prevRefresh?.cancel()
            prevLoadMore?.cancel()
            prevReset?.cancel()
            prevRefresh?.join()
            prevLoadMore?.join()
            prevReset?.join()

            controlFlow.update {
                it.copy(
                    refreshingCount = it.refreshingCount + 1,
                    errorMessage = null,
                    demoTick = it.demoTick + 1,
                    debugStatusMessage = "БД та моковий сервер скинуто до початкового стану (по 100 операцій на картку)."
                )
            }
            try {
                fakeApi.resetInitialData()
                repository.clearLocalStorage()
                repository.refreshAll()
                controlFlow.update {
                    it.copy(demoTick = it.demoTick + 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controlFlow.update {
                    it.copy(
                        errorMessage = "Не вдалося оновити після скидання (${e.message ?: "IOException"})",
                        demoTick = it.demoTick + 1
                    )
                }
            } finally {
                controlFlow.update {
                    it.copy(refreshingCount = (it.refreshingCount - 1).coerceAtLeast(0))
                }
            }
        }
    }
}

private data class DataSnapshot(
    val byCard: Map<String, List<TxEntity>>,
    val allTx: List<TxEntity>,
    val syncByCard: Map<String, CardSyncEntity>,
    val totalCount: Int,
    val gapCards: Set<String>
)