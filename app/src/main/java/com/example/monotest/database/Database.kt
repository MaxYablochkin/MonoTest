package com.example.monotest.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

@Database(entities = [TxEntity::class, CardSyncEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): StatementDao
}

private const val DATABASE_NAME = "statement_database"

fun provideDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        DATABASE_NAME
    ).fallbackToDestructiveMigration(true).build()

fun provideStatementDao(db: AppDatabase): StatementDao = db.dao()

