package com.example.monotest.database

import com.example.monotest.cards.accounts.CardType
import com.example.monotest.data.api.FakeStatementApi
import com.example.monotest.data.api.StatementApi
import com.example.monotest.domain.StatementRepository
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

val databaseModule = module {
    single { create(::provideDatabase) }
    single { create(::provideStatementDao) }

    single {
        FakeStatementApi(cards = CardType.cardIds)
    }

    single<FakeStatementApi>() bind StatementApi::class

    single {
        StatementRepository(
            api = get(),
            dao = get(),
            cards = CardType.cardIds
        )
    }
}
