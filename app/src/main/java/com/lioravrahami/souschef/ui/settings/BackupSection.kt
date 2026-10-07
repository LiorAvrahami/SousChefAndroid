package com.lioravrahami.souschef.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.backup.BackupManager
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/**
 * Largest backup that is offered for sharing as plain text, in UTF-8 bytes. Kept well below
 * the 1 MB binder limit: a Parcel stores the text as UTF-16 (twice the size) and ACTION_SEND
 * also copies EXTRA_TEXT into the intent's ClipData, so the transaction carries about four
 * times this many bytes. Many receiving apps also truncate long text.
 */
private const val SHARE_LIMIT_BYTES = 50_000

/**
 * Export to a file, import from a file (this app's backups or the old web app's
 * `recipes.json`), and share small backups as text.
 *
 * Import and export run in the app scope through [backupTasks], so rotating the phone,
 * pressing Back or opening the trash does not cancel them; their progress and result live
 * outside this composable and reappear when the screen comes back.
 */
@Composable
fun BackupSection(container: AppContainer) {
    val context = LocalContext.current
    val tasks = remember(container) { container.backupTasks() }
    val taskState by tasks.state.collectAsStateWithLifecycle()
    val busy = taskState.busyLabel
    // The application's resolver: the work may outlive this activity. SAF grants belong to
    // the app, so it can open the chosen documents.
    val resolver = container.appContext.contentResolver
    var shareError by remember { mutableStateOf<String?>(null) }
    var shareText by remember { mutableStateOf<String?>(null) }

    // Rebuilt whenever an import or export finishes.
    LaunchedEffect(taskState.completed) {
        shareText = try {
            container.backup.exportJson().takeIf { it.toByteArray(Charsets.UTF_8).size < SHARE_LIMIT_BYTES }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            tasks.run("Export") {
                // Build the whole file first so a failure never leaves half a backup behind.
                val text = container.backup.exportJson()
                val bytes = text.toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) { resolver.writeAll(uri, bytes) }
                "Backup saved" to "All recipes, versions and cookings were written to the file (${formatSize(bytes.size.toLong())})."
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            tasks.run("Import") {
                val result = withContext(Dispatchers.IO) {
                    val text = resolver.readTextLimited(uri, BackupManager.MAX_IMPORT_BYTES)
                    container.backup.importJson(text)
                }
                "Import finished" to importSummary(result)
            }
        }
    }

    SettingsSection(title = "Backup") {
        HelpText("A backup file holds every recipe, version and cooking, including the trash. Importing only adds what is missing and never changes or deletes anything.")
        BigButton(
            text = "Export backup file",
            enabled = busy == null,
            onClick = { exportLauncher.launch(backupFileName(LocalDate.now())) },
            modifier = Modifier.testTag(TestTags.SETTINGS_EXPORT),
        )
        BigOutlinedButton(
            text = "Import backup file",
            enabled = busy == null,
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            modifier = Modifier.testTag(TestTags.SETTINGS_IMPORT),
        )
        HelpText("Old web-app recipes.json files can be imported too.")
        shareText?.let { text ->
            BigOutlinedButton(
                text = "Share backup",
                enabled = busy == null,
                onClick = {
                    try {
                        context.shareText(text)
                    } catch (e: RuntimeException) {
                        // No app to share with, or the text is too big to hand over.
                        shareError = "Could not share the backup (${e.javaClass.simpleName}). Use \"Export backup file\" instead."
                    }
                },
            )
        }
        busy?.let { label ->
            Text("$label in progress…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }

    taskState.result?.let { (title, message) ->
        MessageDialog(title = title, message = message, onDismiss = { tasks.dismissResult() })
    }
    shareError?.let { message ->
        MessageDialog(title = "Share failed", message = message, onDismiss = { shareError = null })
    }
}

/** Writes [bytes] to [uri], replacing any previous content. */
private fun ContentResolver.writeAll(uri: Uri, bytes: ByteArray) {
    // "wt" truncates an existing file; some providers do not know the flag, and a newly
    // created document is empty anyway, so fall back to plain "w".
    val stream: OutputStream = runCatching { openOutputStream(uri, "wt") }.getOrNull()
        ?: openOutputStream(uri, "w")
        ?: throw IOException("Could not open the file for writing.")
    stream.use {
        it.write(bytes)
        it.flush()
    }
}

/** Reads the whole document as UTF-8, refusing anything larger than [maxBytes]. */
private fun ContentResolver.readTextLimited(uri: Uri, maxBytes: Long): String {
    val input = openInputStream(uri) ?: throw IOException("Could not open the file.")
    val bytes = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("This file is larger than ${formatSize(maxBytes)}, too large to be a Sous Chef backup.")
            }
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    return String(bytes, Charsets.UTF_8)
}

private fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Sous Chef backup")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, "Share backup"))
}

/** 1536 -> "1.5 KB". */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
