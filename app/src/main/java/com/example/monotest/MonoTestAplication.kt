package com.example.monotest

import android.app.Application
import com.example.monotest.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin

class MonoTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MonoTestApplication)
            modules(appModule)
        }
    }
}