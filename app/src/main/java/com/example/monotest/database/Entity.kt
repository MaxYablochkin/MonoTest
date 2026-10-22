package com.example.monotest.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "tx",
    indices = [
        Index("cardId", "seq"),
        Index("seq", unique = true)
    ]
)
data class TxEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val seq: Long,
    val time: Long,
    val amountKop: Long,
    val title: String,
    val subtitle: String
)

@Entity(tableName = "card_sync")
data class CardSyncEntity(
    @PrimaryKey val cardId: String,
    val oldestSeq: Long,
    val newestSeq: Long,
    val reachedEnd: Boolean
)
