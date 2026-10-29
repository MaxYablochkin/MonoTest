package com.example.monotest.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.example.monotest.data.api.TxCursor

@Entity(
    tableName = "tx",
    indices = [
        Index(value = ["cardId", "time", "id"]),
        Index(value = ["time", "id"])
    ]
)
data class TxEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val time: Long,
    val amountKop: Long,
    val title: String,
    val subtitle: String
) {
    val cursor: TxCursor get() = TxCursor(time = time, id = id)
}

@Entity(tableName = "card_sync")
data class CardSyncEntity(
    @PrimaryKey val cardId: String,
    val oldestTime: Long,
    val oldestId: String,
    val newestTime: Long,
    val newestId: String,
    val reachedEnd: Boolean
) {
    val oldestCursor: TxCursor get() = TxCursor(time = oldestTime, id = oldestId)
    val newestCursor: TxCursor get() = TxCursor(time = newestTime, id = newestId)
}

