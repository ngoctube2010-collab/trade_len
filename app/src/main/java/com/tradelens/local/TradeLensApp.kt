package com.tradelens.local

import android.app.Application
import androidx.room.Room
import com.tradelens.local.ai.LlmEngine
import com.tradelens.local.data.AppDatabase
import com.tradelens.local.data.SettingsRepository

class TradeLensApp : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "tradelens-local.db"
        ).build()
        settingsRepository = SettingsRepository(applicationContext)
    }

    override fun onTerminate() {
        LlmEngine.releaseImmediately()
        super.onTerminate()
    }
}
