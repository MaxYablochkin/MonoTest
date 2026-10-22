package com.example.monotest.designsystem

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.iconColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monotest.cards.accounts.CardType
import com.example.monotest.cards.transactions.Operation

@Composable
fun BankCard(
    page: Int,
    pagerState: PagerState,
    cardType: CardType,
    sharedTransitionScope: SharedTransitionScope,
    isAllOperationsOpen: Boolean,
    onAllOperationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    recentOperations: List<Operation> = emptyList(),
) {
    val brush = remember(cardType) { Brush.verticalGradient(cardType.colors) }

    Text(
        text = cardType.balance,
        fontSize = 50.sp,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        color = Color.White,
        modifier = Modifier
            .pagerCardAccountTransition(page, pagerState)
            .fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(25.dp))

    Box(
        modifier = modifier
            .pagerCardTransition(page, pagerState)
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = cardType.title,
                color = cardType.contentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(30.dp))

    CardTools(iconColor = cardType.iconColor)

    Spacer(modifier = Modifier.height(25.dp))

    OperationsCard(
        operations = recentOperations,
        sharedTransitionScope = sharedTransitionScope,
        sharedBoundsKey = operationsSharedBoundsKey(cardType.id),
        visible = !isAllOperationsOpen,
        onAllClick = onAllOperationsClick,
        modifier = Modifier.pagerCardOperationsTransition(page, pagerState)
    )
}


data class CardAction(val icon: ImageVector, val label: String)

private val DefaultCardActions = listOf(
    CardAction(MonoTestIcons.person, "Переказати"),
    CardAction(MonoTestIcons.info, "Информація"),
    CardAction(MonoTestIcons.lock, "Заблокувати"),
)

private val DimmedBlack = Color.Black.copy(alpha = 0.8f)

@Composable
fun CardTools(
    iconColor: Color,
    modifier: Modifier = Modifier,
    actions: List<CardAction> = DefaultCardActions,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            ActionTool(
                action = action,
                iconColor = iconColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun ActionTool(
    action: CardAction,
    iconColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = false),
            onClick = { }
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(DimmedBlack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                modifier = Modifier.size(30.dp),
                imageVector = action.icon,
                contentDescription = null,
                tint = iconColor.copy(alpha = 0.8f),
            )
        }
        Text(
            text = action.label,
            fontWeight = FontWeight.W400,
            color = DimmedBlack
        )
    }
}

@Composable
fun OperationsCard(
    operations: List<Operation>,
    sharedTransitionScope: SharedTransitionScope,
    sharedBoundsKey: Any,
    visible: Boolean,
    onAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    with(sharedTransitionScope) {
        Card(
            modifier = modifier.sharedElementWithCallerManagedVisibility(
                sharedContentState = rememberSharedContentState(key = sharedBoundsKey),
                visible = visible,
            ),
            shape = RoundedCornerShape(25.dp),
            colors = CardDefaults.cardColors(Color.White)
        ) {
            OperationsHeader(onAllClick = onAllClick)
            if (operations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(horizontal = 19.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Завантаження операцій…",
                        color = Color(0xFF8B91AC),
                        fontSize = 14.sp
                    )
                }
            } else {
                operations.forEach { op ->
                    OperationPreviewItem(operation = op, onClick = onAllClick)
                }
            }
        }
    }
}

@Composable
private fun OperationsHeader(onAllClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 19.dp, top = 20.dp, end = 19.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Операції",
            fontWeight = FontWeight.W600,
            fontSize = 17.sp,
            color = Color.Black
        )
        Button(
            onClick = onAllClick,
            modifier = Modifier.size(height = 28.dp, width = 62.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFB5BACC), contentColor = Color(0xFF1E338C)
            ),
        ) {
            Text(text = "Усі", fontSize = 13.sp)
            Icon(
                modifier = Modifier.size(17.dp),
                imageVector = MonoTestIcons.keyboard_arrow_right,
                contentDescription = null
            )
        }
    }
}

@Composable
fun OperationPreviewItem(
    operation: Operation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isNegative = operation.amountKop < 0L

    Row(
        modifier = modifier
            .height(70.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 19.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OperationAvatar(isNegative = isNegative)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = operation.title,
                color = Color.Black,
                fontWeight = FontWeight.W500,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = operation.subtitle,
                color = Color(0xFF8B91AC),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatAmountKop(operation.amountKop),
            color = if (isNegative) Color(0xFFFF5B5B) else Color(0xFF2E9B47),
            fontWeight = FontWeight.W600,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun OperationAvatar(isNegative: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            modifier = Modifier.size(26.dp),
            imageVector = if (isNegative) MonoTestIcons.keyboard_arrow_up else MonoTestIcons.keyboard_arrow_down,
            contentDescription = null,
            tint = Color.White
        )
    }
}

@Composable
fun ItemInfo(person: PersonInfo, modifier: Modifier = Modifier) {
    val isNegative = person.price < 0f

    Row(
        modifier = modifier
            .height(70.dp)
            .clickable { }
            .padding(horizontal = 19.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonAvatar(isNegative = isNegative)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = "${person.name} ${person.surname}",
            color = Color.Black
        )
        Text(
            text = "${person.price} $",
            color = if (isNegative) Color.Red else Color.Green
        )
    }

}

@Composable
private fun PersonAvatar(isNegative: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            modifier = Modifier.size(30.dp),
            imageVector = if (isNegative) MonoTestIcons.keyboard_arrow_up else MonoTestIcons.keyboard_arrow_down,
            contentDescription = null,
            tint = Color.White
        )
    }
}

data class PersonInfo(val name: String, val surname: String, val price: Float)
