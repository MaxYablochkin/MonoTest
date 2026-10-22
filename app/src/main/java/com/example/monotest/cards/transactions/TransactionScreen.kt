@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.monotest.cards.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monotest.designsystem.MonoTestIcons
import com.example.monotest.designsystem.OperationsRevealState
import com.example.monotest.designsystem.formatAmountKop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun AllTransactionScreen(
    groups: List<OperationsGroup>,
    revealState: OperationsRevealState,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false,
    isLoadingMore: Boolean = false,
    reachedEnd: Boolean = false,
    hasGap: Boolean = false,
    errorMessage: String? = null,
    onRefresh: () -> Unit = {},
    onEndReached: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val closeDragState = rememberDraggableState { delta -> revealState.dragBy(delta) }

    Scaffold(
        modifier = modifier.draggable(
            state = closeDragState,
            orientation = Orientation.Horizontal,
            onDragStopped = { velocity -> revealState.settle(velocity) },
        ),
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Усі операції (всі картки)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                subtitle = {},
                titleHorizontalAlignment = Alignment.CenterHorizontally,
                colors = TopAppBarDefaults.topAppBarColors(Color.Transparent, Color.Transparent)
            )
        }
    ) { innerPadding ->
        TransactionGroupsList(
            groups = groups,
            textColor = Color.White,
            isRefreshing = isRefreshing,
            isLoadingMore = isLoadingMore,
            reachedEnd = reachedEnd,
            hasGap = hasGap,
            errorMessage = errorMessage,
            onRefresh = onRefresh,
            onEndReached = onEndReached,
            onRetry = onRetry,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
fun AccountTransactionSharedScreen(
    groups: List<OperationsGroup>,
    onClose: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedBoundsKey: Any,
    modifier: Modifier = Modifier,
    cardTitle: String = "Картка",
    showAllCards: Boolean = false,
    onToggleMode: (Boolean) -> Unit = {},
    isRefreshing: Boolean = false,
    isLoadingMore: Boolean = false,
    reachedEnd: Boolean = false,
    hasGap: Boolean = false,
    errorMessage: String? = null,
    onRefresh: () -> Unit = {},
    onEndReached: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    BackHandler(enabled = true) { onClose() }

    with(sharedTransitionScope) {
        Scaffold(
            modifier = modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = sharedBoundsKey),
                    animatedVisibilityScope = animatedVisibilityScope
                ),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
            containerColor = Color.White,
            topBar = {
                Column(modifier = Modifier.background(Color.White)) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(MonoTestIcons.close, "close", tint = Color.Black)
                            }
                        },
                        title = {
                            Text(
                                text = if (showAllCards) "Усі операції (всі картки)" else "Виписка: $cardTitle",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                fontSize = 18.sp
                            )
                        },
                        subtitle = {},
                        titleHorizontalAlignment = Alignment.CenterHorizontally,
                        colors = TopAppBarDefaults.topAppBarColors(Color.White, Color.White)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showAllCards,
                            onClick = { onToggleMode(false) },
                            label = { Text(cardTitle) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1E338C),
                                selectedLabelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = showAllCards,
                            onClick = { onToggleMode(true) },
                            label = { Text("Усі картки (перетин вікон)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1E338C),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            TransactionGroupsList(
                groups = groups,
                textColor = Color.Black,
                isRefreshing = isRefreshing,
                isLoadingMore = isLoadingMore,
                reachedEnd = reachedEnd,
                hasGap = hasGap,
                errorMessage = errorMessage,
                onRefresh = onRefresh,
                onEndReached = onEndReached,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
private fun TransactionGroupsList(
    groups: List<OperationsGroup>,
    textColor: Color,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false,
    isLoadingMore: Boolean = false,
    reachedEnd: Boolean = false,
    hasGap: Boolean = false,
    errorMessage: String? = null,
    onRefresh: () -> Unit = {},
    onEndReached: () -> Unit = {},
    onRetry: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    // Keep scroll anchored at the top when new operations are prepended during refresh
    val newestSeq = groups.firstOrNull()?.operations?.firstOrNull()?.seq ?: 0L
    LaunchedEffect(newestSeq, hasGap, errorMessage) {
        if ((newestSeq > 0L || hasGap || errorMessage != null) && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // Trigger loadMore when scrolled near the bottom
    LaunchedEffect(listState, groups, reachedEnd, isLoadingMore, isRefreshing) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 3
        }
            .distinctUntilChanged()
            .filter { it && !reachedEnd && !isLoadingMore && !isRefreshing && groups.isNotEmpty() }
            .collect {
                onEndReached()
            }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Visible gap marker at the top when filling multi-page history gap (or paused on error)
            if (hasGap) {
                item(key = "gap_marker_banner") {
                    GapLoadingBanner()
                }
            }

            // Error banner with Retry button
            if (errorMessage != null) {
                item(key = "error_banner") {
                    ErrorRetryBanner(
                        message = errorMessage,
                        onRetry = onRetry
                    )
                }
            }

            if (groups.isEmpty() && !isRefreshing) {
                item(key = "empty_placeholder") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Операцій немає",
                            color = Color(0xFF8B91AC),
                            fontSize = 15.sp
                        )
                    }
                }
            }

            groups.forEach { group ->
                stickyHeader(key = "header_${group.label}") {
                    val isPinned by remember(listState) {
                        derivedStateOf {
                            val headerItem = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == "header_${group.label}" }
                                ?: return@derivedStateOf false

                            val isScrolled = listState.firstVisibleItemIndex > 0 ||
                                    listState.firstVisibleItemScrollOffset > 0

                            headerItem.offset < 0 || (headerItem.offset <= 0 && isScrolled)
                        }
                    }

                    StickyHeaderItem(
                        title = group.label,
                        isPinned = isPinned,
                    )
                }

                items(
                    items = group.operations,
                    key = { op -> op.id.ifEmpty { "${group.label}_${op.seq}_${op.title}_${op.amountKop}" } }
                ) { op ->
                    OperationRow(op, textColor = textColor)
                }
            }

            // Footer indicator: loading more vs reached end of history
            if (groups.isNotEmpty()) {
                item(key = "footer_status") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isLoadingMore -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF408DE5)
                                    )
                                    Text(
                                        text = "Завантаження історії…",
                                        color = Color(0xFF8B91AC),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            reachedEnd -> {
                                Text(
                                    text = "Це все",
                                    color = Color(0xFF8B91AC),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.W500
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapLoadingBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E338C).copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = Color.White
        )
        Text(
            text = "Завантаження пропущених операцій…",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.W500
        )
    }
}

@Composable
private fun ErrorRetryBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFEBEE))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = message,
            color = Color(0xFFB71C1C),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text = "Повторити", fontSize = 13.sp)
        }
    }
}

@Composable
private fun StickyHeaderItem(
    title: String,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
) {
    val animateColor by animateColorAsState(
        targetValue = if (isPinned) Color.Black else Color.Transparent,
        animationSpec = tween(durationMillis = 300)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (isPinned) {
                    drawRect(color = animateColor)
                }
            }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isPinned) Color.White else Color(0xFF8B91AC),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.W500,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun OperationRow(
    operation: Operation,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick?.invoke() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = operation.title,
                color = textColor,
                fontWeight = FontWeight.W600,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatAmountKop(operation.amountKop),
                color = if (operation.amountKop < 0L) Color(0xFFFF5B5B) else Color(0xFF4CD964),
                fontWeight = FontWeight.W600,
                fontSize = 17.sp,
            )
        }
        Text(operation.subtitle, color = Color(0xFF8B91AC), fontSize = 14.sp)
    }
}

data class Operation(
    val id: String = "",
    val seq: Long = 0L,
    val cardId: String = "",
    val title: String,
    val subtitle: String,
    val amountKop: Long = 0L,
    val amount: Double = amountKop / 100.0
)

data class OperationsGroup(val label: String, val operations: List<Operation>)

val sampleOperationsGroups = listOf(
    OperationsGroup(
        "Сьогодні",
        listOf(
            Operation(
                id = "sample_1",
                seq = 5L,
                cardId = "card_black",
                title = "Біла → Чорна",
                subtitle = "Між своїми картками",
                amountKop = 500L
            ),
            Operation(
                id = "sample_2",
                seq = 4L,
                cardId = "card_white",
                title = "Чорна → Біла",
                subtitle = "Між своїми картками",
                amountKop = 500L
            ),
        )
    ),
    OperationsGroup(
        "Вчора",
        listOf(
            Operation(
                id = "sample_3",
                seq = 3L,
                cardId = "card_black",
                title = "KONFETKI BARANOCHKI",
                subtitle = "З картки mono x АТБ",
                amountKop = -10300L
            ),
            Operation(
                id = "sample_4",
                seq = 2L,
                cardId = "card_black",
                title = "АТБ-Маркет",
                subtitle = "З картки mono x АТБ",
                amountKop = -86489L
            ),
            Operation(
                id = "sample_5",
                seq = 1L,
                cardId = "card_black",
                title = "Від: Олександр М.",
                subtitle = "На мою Чорну",
                amountKop = 100000L
            ),
        )
    ),
)