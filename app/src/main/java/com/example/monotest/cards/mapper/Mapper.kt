package com.example.monotest.cards.mapper

import com.example.monotest.cards.transactions.Operation
import com.example.monotest.cards.transactions.OperationsGroup
import com.example.monotest.database.TxEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DayKey(val dateCode: String, val label: String)

fun mapTransactionsToGroups(
    txList: List<TxEntity>,
    nowMillis: Long = System.currentTimeMillis()
): List<OperationsGroup> {
    if (txList.isEmpty()) return emptyList()

    return txList
        .groupBy { tx -> resolveDayKey(tx.time, nowMillis) }
        .map { (dayKey, items) ->
            OperationsGroup(
                label = dayKey.label,
                operations = items.map { tx -> tx.toOperation() }
            )
        }
}

fun TxEntity.toOperation(): Operation = Operation(
    id = id,
    time = time,
    cardId = cardId,
    title = title,
    subtitle = subtitle,
    amountKop = amountKop,
    amount = amountKop / 100.0
)

private fun resolveDayKey(timestampSec: Long, nowMillis: Long): DayKey {
    // Convert Unix seconds (or milliseconds if already > 10^11) to milliseconds
    val millis = if (timestampSec < 100_000_000_000L) timestampSec * 1000L else timestampSec
    val netDate = Date(millis)
    val today = Date(nowMillis)

    val fmtCode = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val txDateStr = fmtCode.format(netDate)
    val todayStr = fmtCode.format(today)

    val todayCal = Calendar.getInstance().apply { time = today }
    val txCal = Calendar.getInstance().apply { time = netDate }
    val yesterdayCal = Calendar.getInstance().apply {
        time = today
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val yesterdayStr = fmtCode.format(yesterdayCal.time)

    val label = when {
        txDateStr == todayStr -> "Сьогодні"
        txDateStr == yesterdayStr -> "Вчора"
        txCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) -> {
            SimpleDateFormat("d MMMM", Locale.forLanguageTag("uk")).format(netDate)
        }

        else -> {
            SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("uk")).format(netDate)
        }
    }

    return DayKey(dateCode = txDateStr, label = label)
}

