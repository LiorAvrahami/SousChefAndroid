package com.lioravrahami.souschef

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lioravrahami.souschef.ui.nav.SousChefNavGraph
import com.lioravrahami.souschef.ui.theme.SousChefTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Trial to jump to, set when the app is opened from a timer notification. */
    private val pendingTrialId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SousChefApp).container

        val launchTrialId = intent?.getStringExtra(EXTRA_TRIAL_ID)
        pendingTrialId.value = launchTrialId
        // Opened by a ringing timer: show over the lock screen right away.
        if (launchTrialId != null) setLockScreenAccess(true)

        // While a cooking session is in progress the app may show over the lock screen and
        // wake the display (hands are busy, the timer alarm must be visible). At any other
        // time the app stays behind the lock screen like every other app.
        lifecycleScope.launch {
            container.repository.observeInProgressTrial()
                .map { it != null }
                .distinctUntilChanged()
                .collect { cooking -> setLockScreenAccess(cooking) }
        }

        setContent {
            SousChefTheme {
                val pending by pendingTrialId.collectAsStateWithLifecycle()
                SousChefNavGraph(
                    container = container,
                    pendingTrialId = pending,
                    onPendingTrialConsumed = { pendingTrialId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TRIAL_ID)?.let {
            setLockScreenAccess(true)
            pendingTrialId.value = it
        }
    }

    private fun setLockScreenAccess(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (enabled) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    companion object {
        /** Intent extra carrying a trial id; used by timer notifications to open the cooking screen. */
        const val EXTRA_TRIAL_ID = "trialId"
    }
}
