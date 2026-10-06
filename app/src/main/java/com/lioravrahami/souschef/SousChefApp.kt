package com.lioravrahami.souschef

import android.app.Application
import com.lioravrahami.souschef.domain.timer.Notifications

class SousChefApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
    }
}
