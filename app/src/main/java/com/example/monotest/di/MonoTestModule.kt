package com.example.monotest.di

import com.example.monotest.cards.accounts.AccountViewModel
import com.example.monotest.database.databaseModule
import org.koin.dsl.module
import org.koin.plugin.module.dsl.viewModel

val appModule = module {
    viewModel<AccountViewModel>()
    includes(databaseModule)
}
