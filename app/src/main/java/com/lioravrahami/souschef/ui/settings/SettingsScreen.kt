package com.lioravrahami.souschef.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold

/** Where the source code of this app lives (shown as plain text). */
const val GITHUB_URL = "github.com/LiorAvrahami/SousChefAndroid"

/**
 * All app settings in one scrollable page of cards: AI key and model, optimizer
 * boldness, cooking and alarm options, backup export/import, the trash, and about.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val settingsTick by container.settings.changes.collectAsStateWithLifecycle()
    val trash by container.repository.observeTrash().collectAsStateWithLifecycle(initialValue = emptyList())

    ScreenScaffold(title = "Settings", onBack = onBack) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AiSection(container, settingsTick)
            OptimizerSection(container, settingsTick)
            CookingSection(container, settingsTick)
            BackupSection(container)
            SettingsSection(title = "Data") {
                HelpText("Deleted recipes wait in the trash until you delete them forever.")
                BigOutlinedButton(
                    text = if (trash.isEmpty()) "Trash (empty)" else "Trash (${trash.size})",
                    onClick = onOpenTrash,
                    modifier = Modifier.testTag(TestTags.SETTINGS_TRASH),
                )
            }
            AboutSection()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember { context.appVersionName() }
    SettingsSection(title = "About") {
        Text("Sous Chef $version", style = MaterialTheme.typography.titleLarge)
        Text("How parameters work", style = MaterialTheme.typography.titleMedium)
        HelpText(
            "Write tweakable amounts and times as a number with its unit in square brackets: " +
                "\"Add 1.75[cups] water\", \"Bake 20[min]\", \"1/2[tsp] salt\". " +
                "The optimizer may change these numbers; lock any of them in the editor to keep it fixed. " +
                "Wait steps are timers, and their time is a parameter too.",
        )
        HelpText(
            "A version is a set of steps; change wording, order or steps and you get a new version. " +
                "A cooking is one time you cooked a version with concrete numbers, and its rating " +
                "teaches the optimizer what to try next.",
        )
        Text("Source code: $GITHUB_URL", style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Context.appVersionName(): String = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    info.versionName ?: "?"
} catch (e: PackageManager.NameNotFoundException) {
    "?"
}
