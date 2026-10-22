package com.example.monotest.cards.accounts

import com.example.monotest.cards.transactions.Operation
import com.example.monotest.cards.transactions.OperationsGroup
import com.example.monotest.database.CardSyncEntity

enum class SyncStatus {
    Idle,
    Refreshing,
    LoadingMore,
    Error
}

data class DebugStats(
    val dbTotalRows: Int = 0,
    val olderCalls: Int = 0,
    val newerCalls: Int = 0,
    val serverAsOfSeq: Long = 0L,
    val syncByCard: Map<String, CardSyncEntity> = emptyMap(),
    val pendingNewByCard: Map<String, Int> = emptyMap(),
    val statusMessage: String? = null
)

data class AccountState(
    val cards: List<CardType> = CardType.entries,
    val selectedCardIndex: Int = 0,

    // Per-card recent operations (for BankCard preview) and grouped timelines
    val recentByCard: Map<String, List<Operation>> = emptyMap(),
    val cardGroupsByCard: Map<String, List<OperationsGroup>> = emptyMap(),
    val oldestSeqByCard: Map<String, Long> = emptyMap(),
    val reachedEndByCard: Map<String, Boolean> = emptyMap(),

    // Unified "All cards" timeline (intersection of all card windows)
    val allOperationsGroups: List<OperationsGroup> = emptyList(),
    val allReachedEnd: Boolean = false,

    // Backwards-compatible alias for the currently active timeline in AccountTransactionSharedScreen
    val operationsGroups: List<OperationsGroup> = emptyList(),

    // Navigation & mode state
    val isAllOperationsOpen: Boolean = false,
    val showAllCardsInSheet: Boolean = false,

    // Sync, gap marker, and error states
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val gapCards: Set<String> = emptySet(),
    val hasGapInCurrentFeed: Boolean = false,
    val hasGapInAllFeed: Boolean = false,

    // Demo / Debug panel
    val isDebugPanelOpen: Boolean = false,
    val debugStats: DebugStats = DebugStats()
) {
    val selectedCard: CardType get() = cards.getOrElse(selectedCardIndex) { CardType.BLACK }
}
