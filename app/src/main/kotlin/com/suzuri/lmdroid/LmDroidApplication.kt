package com.suzuri.lmdroid

import android.app.Application

class LmDroidApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
