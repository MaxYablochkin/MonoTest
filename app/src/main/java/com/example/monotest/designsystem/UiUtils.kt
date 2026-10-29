package com.example.monotest.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun rememberRevealConnection(
    pagerState: PagerState,
    revealState: OperationsRevealState,
): NestedScrollConnection {
    return remember(pagerState, revealState) { operationsRevealConnection(pagerState, revealState) }
}

fun Modifier.scaleHorizontalPagerContent(progress: Float) = graphicsLayer {
    scaleX = 1f - 0.10f * progress
    scaleY = 1f - 0.10f * progress
    translationY = 40.dp.toPx() * progress
}

fun Modifier.translationXOperationsScreen(progress: Float) = graphicsLayer {
    translationX = -size.width * (1f - progress)
}

fun Modifier.pagerCardTransition(page: Int, pagerState: PagerState) = graphicsLayer {
    val pageOffset = pagerState.getOffsetDistanceInPages(page)

    rotationX = 40f

    scaleY = lerp(0.70f, 0.60f, 1f - pageOffset.absoluteValue)
    scaleX = lerp(0.95f, 0.90f, 1f - pageOffset.absoluteValue)

    if (pageOffset <= 0f) {
        rotationY = 14f * pageOffset.absoluteValue
        rotationZ = -12f * pageOffset.absoluteValue
    } else {
        rotationY = -14f * pageOffset.absoluteValue
        rotationZ = 12f * pageOffset.absoluteValue
    }

    alpha = lerp(0.5f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
}

fun Modifier.pagerCardAccountTransition(page: Int, pagerState: PagerState) = graphicsLayer {
    val pageOffset = pagerState.getOffsetDistanceInPages(page)
    val scale = lerp(0.7f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))

    scaleX = scale
    scaleY = scale
    alpha = lerp(0.5f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))

    translationX = EaseOutQuad.transform(pageOffset * size.width / 2f)
}

fun Modifier.pagerCardOperationsTransition(page: Int, pagerState: PagerState) = graphicsLayer {
    val pageOffset = pagerState.getOffsetDistanceInPages(page)
    translationX = EaseOutQuad.transform(pageOffset * size.width / 2f)
}

fun Modifier.topAppBarBlur(
    progress: Float,
    hazeState: HazeState,
): Modifier {
    return this.hazeBlur(
        input = HazeInput.Sources(state = hazeState),
        performanceMode = HazePerformanceMode.Quality,
        style = HazeBlurStyle.then {
            blurEnabled(progress > 0f)
            blurRadius(progress * 10.dp)
            blurredEdgeTreatment(BlurredEdgeTreatment.Rectangle)
            colorEffects(
                listOf(
                    HazeColorEffect.tint(Color.Black.copy(alpha = 0.40f * progress))
                )
            )
            noiseFactor(0f)
        }
    )
}

fun Modifier.operationsRevealBlur(
    progress: Float,
    hazeState: HazeState,
): Modifier = this.hazeBlur(
    input = HazeInput.Sources(state = hazeState),
    performanceMode = HazePerformanceMode.Quality,
    style = HazeBlurStyle.then {
        blurEnabled(true)
        blurRadius(progress * 60.dp)
        blurredEdgeTreatment(BlurredEdgeTreatment.Rectangle)
        colorEffects(
            listOf(
                HazeColorEffect.tint(Color.Black.copy(alpha = 0.7f * progress))
            )
        )
        noiseFactor(0f)
    }
)

@Stable
class OperationsRevealState(
    private val maxDragPx: Float,
    private val scope: CoroutineScope,
    initialOffset: Float = 0f,
) {
    private val offset = Animatable(initialOffset)

    var isOpen = initialOffset != 0f
        private set

    val rawOffsetPx: Float get() = offset.value
    val progress: Float get() = (abs(offset.value) / maxDragPx).coerceIn(0f, 1f)

    fun dragBy(delta: Float) {
        val current = offset.value
        var next = current + delta
        if (current > 0f && next < 0f) next = 0f
        if (current < 0f && next > 0f) next = 0f
        val clamped = next.coerceIn(-maxDragPx, maxDragPx)
        scope.launch { offset.snapTo(clamped) }
    }

    fun close() = scope.launch {
        isOpen = false
        offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
    }

    suspend fun updateProgress(backProgress: Float) {
        val currentSign = if (offset.value < 0f) -1f else 1f
        val targetOffset = (1f - backProgress) * maxDragPx * currentSign
        offset.snapTo(targetOffset)
    }

    fun settle(flingVelocityPx: Float) = scope.launch {
        val isFlingingToClose = (offset.value >= 0f && flingVelocityPx < -FLING_THRESHOLD) ||
                (offset.value < 0f && flingVelocityPx > FLING_THRESHOLD)

        val shouldOpen = if (isFlingingToClose) {
            false
        } else if (!isOpen) {
            progress > OPEN_THRESHOLD || abs(flingVelocityPx) > FLING_THRESHOLD
        } else {
            progress > CLOSE_THRESHOLD
        }

        val target = when {
            !shouldOpen -> 0f
            offset.value >= 0f -> maxDragPx
            else -> -maxDragPx
        }

        isOpen = (target != 0f)
        offset.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
    }

    companion object {
        private const val OPEN_THRESHOLD = 0.3f
        private const val CLOSE_THRESHOLD = 0.5f
        private const val FLING_THRESHOLD = 1000f

        fun Saver(maxDragPx: Float, scope: CoroutineScope): Saver<OperationsRevealState, Float> =
            Saver(
                save = { it.rawOffsetPx },
                restore = { savedOffset -> OperationsRevealState(maxDragPx, scope, savedOffset) }
            )
    }
}

@Composable
fun rememberOperationsRevealState(maxDragPx: Float): OperationsRevealState {
    val scope = rememberCoroutineScope()
    return rememberSaveable(maxDragPx, saver = OperationsRevealState.Saver(maxDragPx, scope)) {
        OperationsRevealState(maxDragPx, scope)
    }
}

fun operationsRevealConnection(
    pagerState: PagerState,
    revealState: OperationsRevealState,
): NestedScrollConnection {
    return object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val raw = revealState.rawOffsetPx
            if (raw == 0f) return Offset.Zero
            val closingDelta =
                if (raw > 0f) available.x.coerceAtMost(0f) else available.x.coerceAtLeast(0f)
            if (closingDelta == 0f) return Offset.Zero
            revealState.dragBy(closingDelta)
            return Offset(x = closingDelta, y = 0f)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (pagerState.currentPage == 0 && pagerState.currentPageOffsetFraction == 0f && available.x != 0f) {
                revealState.dragBy(available.x)
                return available
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (revealState.rawOffsetPx != 0f) revealState.settle(consumed.x)
            return Velocity.Zero
        }
    }
}

fun formatAmountKop(amountKop: Long): String {
    val absKop = abs(amountKop)
    val whole = absKop / 100L
    val fraction = absKop % 100L
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    val sign = if (amountKop < 0) "-" else ""
    return "$sign$grouped.${fraction.toString().padStart(2, '0')} ₴"
}

fun formatAmount(amount: Double): String {
    return formatAmountKop((amount * 100.0).roundToLong())
}

fun operationsSharedBoundsKey(cardId: Long): Any = "operations_bounds_$cardId"

fun operationsSharedBoundsKey(cardId: String): Any = "operations_bounds_$cardId"

