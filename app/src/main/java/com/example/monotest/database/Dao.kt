package com.example.monotest.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StatementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<TxEntity>)

    @Upsert
    suspend fun upsert(s: CardSyncEntity)

    @Query("SELECT * FROM card_sync WHERE cardId = :c")
    suspend fun sync(c: String): CardSyncEntity?

    @Query("SELECT * FROM card_sync")
    suspend fun allSync(): List<CardSyncEntity>

    @Query("SELECT * FROM card_sync")
    fun observeSync(): Flow<List<CardSyncEntity>>

    @Query("SELECT * FROM tx WHERE cardId = :c ORDER BY time DESC, id DESC")
    fun observeCard(c: String): Flow<List<TxEntity>>

    @Query(
        """
        SELECT * FROM tx
        WHERE (time > :loTime OR (time = :loTime AND id >= :loId))
          AND (time < :hiTime OR (time = :hiTime AND id <= :hiId))
        ORDER BY time DESC, id DESC
        """
    )
    fun observeWindow(
        loTime: Long,
        loId: String,
        hiTime: Long,
        hiId: String
    ): Flow<List<TxEntity>>

    @Query("SELECT * FROM tx WHERE cardId = :c ORDER BY time DESC, id DESC")
    suspend fun rowsOf(c: String): List<TxEntity>

    @Query("SELECT COUNT(*) FROM tx")
    fun observeTotalCount(): Flow<Int>

    @Query("DELETE FROM tx")
    suspend fun clearTx()

    @Query("DELETE FROM card_sync")
    suspend fun clearSync()

    @Transaction
    suspend fun clearAll() {
        clearTx()
        clearSync()
    }

    @Transaction
    suspend fun savePage(items: List<TxEntity>, syncEntity: CardSyncEntity) {
        insert(items)
        upsert(syncEntity)
    }
}
