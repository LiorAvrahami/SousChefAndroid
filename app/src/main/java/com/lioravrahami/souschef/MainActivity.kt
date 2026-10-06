package com.lioravrahami.souschef

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lioravrahami.souschef.ui.nav.SousChefNavGraph
import com.lioravrahami.souschef.ui.theme.SousChefTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Trial to jump to, set when the app is opened from a timer notification. */
    private val pendingTrialId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingTrialId.value = intent?.getStringExtra(EXTRA_TRIAL_ID)
        val container = (application as SousChefApp).container
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
        intent.getStringExtra(EXTRA_TRIAL_ID)?.let { pendingTrialId.value = it }
    }

    companion object {
        /** Intent extra carrying a trial id; used by timer notifications to open the cooking screen. */
        const val EXTRA_TRIAL_ID = "trialId"
    }
}
