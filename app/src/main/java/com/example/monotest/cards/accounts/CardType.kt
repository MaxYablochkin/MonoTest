package com.example.monotest.cards.accounts

import androidx.compose.ui.graphics.Color

enum class CardType(
    val id: Long,
    val cardId: String,
    val title: String,
    val balance: String,
    val colors: List<Color>,
    val contentColor: Color,
    val iconColor: Color,
) {
    BLACK(
        id = 1L,
        cardId = "card_black",
        title = "Чорна картка",
        balance = "24 580.50 ₴",
        colors = listOf(Color(0xFF38383B), Color(0xFF15161A)),
        contentColor = Color.White,
        iconColor = Color(0xFF616EBD),
    ),
    WHITE(
        id = 2L,
        cardId = "card_white",
        title = "Біла картка",
        balance = "8 120.00 ₴",
        colors = listOf(Color.White, Color.White),
        contentColor = Color.Black,
        iconColor = Color(0xFF9C27B0),
    ),
    PLATINUM(
        id = 3L,
        cardId = "card_platinum",
        title = "Платинова картка",
        balance = "1 450.00 ₴",
        colors = listOf(Color(0xFF1E88E5), Color(0xFF68F56D)),
        contentColor = Color.Black,
        iconColor = Color(0xFF72DA75),
    );

    companion object {
        val cardIds: List<String> = entries.map { it.cardId }
    }
}
