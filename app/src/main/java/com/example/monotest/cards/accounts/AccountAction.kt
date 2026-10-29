package com.example.monotest.cards.accounts

import com.example.monotest.data.api.TxCursor

sealed interface AccountAction {
    data class OnSelectCardIndex(val index: Int) : AccountAction
    data object OnAllOperationsClick : AccountAction
    data object OnCloseAllOperations : AccountAction
    data class OnToggleStatementMode(val showAllCards: Boolean) : AccountAction

    // Refresh & pagination actions
    data class OnRefreshCard(val cardId: String) : AccountAction
    data object OnRefreshAll : AccountAction
    data class OnLoadMoreCard(val cardId: String, val expectedOldest: TxCursor) : AccountAction
    data object OnLoadMoreAll : AccountAction
    data object OnRetry : AccountAction

    // Demo / Debug panel actions
    data object OnToggleDebugPanel : AccountAction
    data class OnDemoAppendNew(val cardId: String, val count: Int = 55) : AccountAction
    data object OnDemoMakeBlackDeeper : AccountAction
    data class OnDemoFailNext(val times: Int = 3) : AccountAction
    data object OnDemoResetAll : AccountAction
}
