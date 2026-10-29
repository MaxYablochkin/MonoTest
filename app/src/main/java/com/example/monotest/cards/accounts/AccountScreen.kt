package com.example.monotest.cards.accounts

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monotest.R
import com.example.monotest.cards.transactions.AccountTransactionSharedScreen
import com.example.monotest.cards.transactions.AllTransactionScreen
import com.example.monotest.cards.transactions.sampleOperationsGroups
import com.example.monotest.data.api.TxCursor
import com.example.monotest.designsystem.BankCard
import com.example.monotest.designsystem.operationsRevealBlur
import com.example.monotest.designsystem.operationsSharedBoundsKey
import com.example.monotest.designsystem.rememberOperationsRevealState
import com.example.monotest.designsystem.rememberRevealConnection
import com.example.monotest.designsystem.scaleHorizontalPagerContent
import com.example.monotest.designsystem.topAppBarBlur
import com.example.monotest.designsystem.translationXOperationsScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    state: AccountState,
    onAction: (AccountAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()
    val revealHazeState = rememberHazeState()
    val listState = rememberLazyListState()

    val pagerState = rememberPagerState { state.cards.size }

    LaunchedEffect(pagerState.currentPage) {
        onAction(AccountAction.OnSelectCardIndex(pagerState.currentPage))
    }

    val density = LocalDensity.current
    val maxDragPx = remember(density) { with(density) { 280.dp.toPx() } }
    val revealState = rememberOperationsRevealState(maxDragPx)
    val revealConnection = rememberRevealConnection(pagerState, revealState)
    val isRevealed by remember { derivedStateOf { revealState.progress > 0f } }

    val gradientColors = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color(0xFF001743),
                0.19f to Color(0xFF062568),
                0.36f to Color(0xFF0A3991),
                0.51f to Color(0xFF408DE5),
                0.81f to Color(0xFFFFFFFF)
            )
        )
    }

    val startOffsetPx = remember(density) { with(density) { 70.dp.toPx() } }
    val rampLengthPx = remember(density) { with(density) { 550.dp.toPx() } }

    val scrollProgress by remember {
        derivedStateOf {
            val rawProgress = if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val currentOffset = listState.firstVisibleItemScrollOffset
                if (currentOffset <= startOffsetPx) 0f
                else ((currentOffset - startOffsetPx) / rampLengthPx).coerceIn(0f, 1f)
            }
            rawProgress * rawProgress
        }
    }

    BackHandler(enabled = isRevealed) { revealState.close() }
    BackHandler(enabled = state.isAllOperationsOpen) { onAction(AccountAction.OnCloseAllOperations) }

    PredictiveBackHandler(enabled = isRevealed) { progressFlow ->
        try {
            progressFlow.collect { backEvent -> revealState.updateProgress(backEvent.progress) }
            revealState.close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            scope.launch { revealState.updateProgress(0f) }
        }
    }

    val currentCard = state.cards.getOrElse(pagerState.currentPage) { CardType.BLACK }

    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.hazeSource(revealHazeState),
            containerColor = Color.White,
            topBar = {
                TopAppBar(
                    windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top),
                    modifier = Modifier.topAppBarBlur(scrollProgress, hazeState),
                    title = {},
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .size(45.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { onAction(AccountAction.OnToggleDebugPanel) },
                                    onLongClick = { onAction(AccountAction.OnToggleDebugPanel) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = ImageBitmap.imageResource(R.drawable.photo_max),
                                contentDescription = "avatar_max"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(Transparent, Transparent),
                    expandedHeight = 55.dp
                )
            }
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onAction(AccountAction.OnRefreshCard(currentCard.cardId)) }
            ) {
                LazyColumn(state = listState) {
                    item {
                        Box(modifier = Modifier.height(1000.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hazeSource(hazeState)
                                    .drawBehind { drawRect(gradientColors) }
                            )
                            HorizontalPager(
                                verticalAlignment = Alignment.Top,
                                contentPadding = PaddingValues(
                                    top = 60.dp,
                                    start = 35.dp,
                                    end = 35.dp
                                ),
                                state = pagerState,
                                key = { state.cards[it].id },
                                flingBehavior = PagerDefaults.flingBehavior(
                                    state = pagerState,
                                    snapPositionalThreshold = 0.25f
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .nestedScroll(revealConnection)
                                    .scaleHorizontalPagerContent(revealState.progress),
                            ) { currentPage ->
                                val card = state.cards[currentPage]
                                Column {
                                    BankCard(
                                        page = currentPage,
                                        pagerState = pagerState,
                                        cardType = card,
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        isAllOperationsOpen = state.isAllOperationsOpen,
                                        onAllOperationsClick = { onAction(AccountAction.OnAllOperationsClick) },
                                        recentOperations = state.recentByCard[card.cardId].orEmpty()
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(500.dp))
                    }
                }
            }
        }

        if (isRevealed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .operationsRevealBlur(revealState.progress, revealHazeState)
            )
            AllTransactionScreen(
                groups = state.allOperationsGroups,
                revealState = revealState,
                isRefreshing = state.isRefreshing,
                isLoadingMore = state.isLoadingMore,
                reachedEnd = state.allReachedEnd,
                hasGap = state.hasGapInAllFeed,
                errorMessage = state.errorMessage,
                onRefresh = { onAction(AccountAction.OnRefreshAll) },
                onEndReached = { onAction(AccountAction.OnLoadMoreAll) },
                onRetry = { onAction(AccountAction.OnRetry) },
                modifier = Modifier
                    .fillMaxSize()
                    .translationXOperationsScreen(revealState.progress),
            )
        }

        AnimatedVisibility(
            visible = state.isAllOperationsOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val isAllMode = state.showAllCardsInSheet
            val reachedEnd = if (isAllMode) {
                state.allReachedEnd
            } else {
                state.reachedEndByCard[currentCard.cardId] == true
            }

            AccountTransactionSharedScreen(
                groups = state.operationsGroups,
                onClose = { onAction(AccountAction.OnCloseAllOperations) },
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedVisibilityScope = this@AnimatedVisibility,
                sharedBoundsKey = operationsSharedBoundsKey(currentCard.id),
                cardTitle = currentCard.title,
                showAllCards = isAllMode,
                onToggleMode = { showAll -> onAction(AccountAction.OnToggleStatementMode(showAll)) },
                isRefreshing = state.isRefreshing,
                isLoadingMore = state.isLoadingMore,
                reachedEnd = reachedEnd,
                hasGap = state.hasGapInCurrentFeed,
                errorMessage = state.errorMessage,
                onRefresh = {
                    if (isAllMode) {
                        onAction(AccountAction.OnRefreshAll)
                    } else {
                        onAction(AccountAction.OnRefreshCard(currentCard.cardId))
                    }
                },
                onEndReached = {
                    if (isAllMode) {
                        onAction(AccountAction.OnLoadMoreAll)
                    } else {
                        val expectedOldest: TxCursor? = state.oldestCursorByCard[currentCard.cardId]
                        if (expectedOldest != null) {
                            onAction(
                                AccountAction.OnLoadMoreCard(
                                    currentCard.cardId,
                                    expectedOldest
                                )
                            )
                        }
                    }
                },
                onRetry = { onAction(AccountAction.OnRetry) }
            )
        }

        if (state.isDebugPanelOpen) {
            DebugPanelDialog(
                state = state,
                currentCard = currentCard,
                onAction = onAction
            )
        }
    }
}

@Composable
private fun DebugPanelDialog(
    state: AccountState,
    currentCard: CardType,
    onAction: (AccountAction) -> Unit
) {
    val stats = state.debugStats
    AlertDialog(
        onDismissRequest = { onAction(AccountAction.OnToggleDebugPanel) },
        title = {
            Text(
                text = "Демо-панель (Statement Gaps)",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Рядків у БД (tx): ${stats.dbTotalRows} | Запитів older: ${stats.olderCalls}, newer: ${stats.newerCalls}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600
                )

                state.cards.forEach { card ->
                    val sync = stats.syncByCard[card.cardId]
                    val pending = stats.pendingNewByCard[card.cardId] ?: 0
                    val windowText = if (sync != null) {
                        val gapSuffix = if (pending > 0) " (+$pending нових)" else ""
                        "[t=${sync.oldestTime} .. ${sync.newestTime}] end=${sync.reachedEnd}$gapSuffix"
                    } else {
                        "не завантажено"
                    }
                    Text(
                        text = "• ${card.title}: $windowText",
                        fontSize = 12.sp,
                        color = if (pending > 0) Color(0xFFD32F2F) else Color(0xFF4A4E69),
                        fontWeight = if (pending > 0) FontWeight.W600 else FontWeight.Normal
                    )
                }

                if (stats.statusMessage != null) {
                    Text(
                        text = stats.statusMessage,
                        fontSize = 12.sp,
                        color = Color(0xFF1E338C),
                        fontWeight = FontWeight.W500,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFE8EAF6))
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = { onAction(AccountAction.OnDemoAppendNew(currentCard.cardId, 55)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+55 нових операцій (${currentCard.title})")
                }

                Button(
                    onClick = { onAction(AccountAction.OnDemoMakeBlackDeeper) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Чорна глибше на 3 сторінки")
                }

                Button(
                    onClick = { onAction(AccountAction.OnDemoFailNext(3)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Наступний запит впаде (IOException)")
                }

                OutlinedButton(
                    onClick = { onAction(AccountAction.OnDemoResetAll) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Скинути БД та демо-дані (Reset)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AccountAction.OnToggleDebugPanel) }) {
                Text("Закрити")
            }
        }
    )
}

@Preview
@Composable
private fun BankcardPreview() = AccountScreen(
    state = AccountState(operationsGroups = sampleOperationsGroups),
    onAction = {},
)
