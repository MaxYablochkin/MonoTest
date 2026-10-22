package com.example.monotest.cards.mapper

import com.example.monotest.cards.transactions.Operation
import com.example.monotest.cards.transactions.OperationsGroup
import com.example.monotest.database.TxEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun mapTransactionsToGroups(
    txList: List<TxEntity>,
    nowMillis: Long = System.currentTimeMillis()
): List<OperationsGroup> {
    if (txList.isEmpty()) return emptyList()

    return txList
        .groupBy { tx -> formatDateLabel(tx.time, nowMillis) }
        .map { (label, items) ->
            OperationsGroup(
                label = label,
                operations = items.map { tx -> tx.toOperation() }
            )
        }
}

fun TxEntity.toOperation(): Operation = Operation(
    id = id,
    seq = seq,
    cardId = cardId,
    title = title,
    subtitle = subtitle,
    amountKop = amountKop,
    amount = amountKop / 100.0
)

private fun formatDateLabel(timestampSec: Long, nowMillis: Long): String {
    // Convert Unix seconds (or milliseconds if already > 10^11) to milliseconds
    val millis = if (timestampSec < 100_000_000_000L) timestampSec * 1000L else timestampSec
    val netDate = Date(millis)
    val today = Date(nowMillis)

    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val txDateStr = fmt.format(netDate)
    val todayStr = fmt.format(today)

    val calendar = Calendar.getInstance().apply {
        time = today
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val yesterdayStr = fmt.format(calendar.time)

    return when (txDateStr) {
        todayStr -> "Сьогодні"
        yesterdayStr -> "Вчора"
        else -> SimpleDateFormat("d MMMM", Locale.forLanguageTag("uk")).format(netDate)
    }
}
