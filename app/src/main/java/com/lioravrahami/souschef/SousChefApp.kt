package com.lioravrahami.souschef

import android.app.Application
import com.lioravrahami.souschef.domain.timer.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SousChefApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        // A force-stop or an app update drops AlarmManager alarms; a running wait timer
        // must get its alarm back (or ring right away if it ended in the meantime).
        container.appScope.launch(Dispatchers.IO) { container.timerScheduler.ensureScheduled() }
    }
}
