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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.backup.BackupManager
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/** Largest backup that is offered for sharing as plain text, in UTF-8 bytes. */
private const val SHARE_LIMIT_BYTES = 200_000

/**
 * Export to a file, import from a file (this app's backups or the old web app's
 * `recipes.json`), and share small backups as text.
 */
@Composable
fun BackupSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Bumped after an import so the share preview is rebuilt.
    var dataVersion by remember { mutableIntStateOf(0) }
    var shareText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dataVersion) {
        shareText = try {
            container.backup.exportJson().takeIf { it.toByteArray(Charsets.UTF_8).size < SHARE_LIMIT_BYTES }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun runTask(label: String, task: suspend () -> Pair<String, String>) {
        busy = label
        scope.launch {
            dialog = try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$label failed" to (e.message ?: e.toString())
            } finally {
                busy = null
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runTask("Export") {
                // Build the whole file first so a failure never leaves half a backup behind.
                val text = container.backup.exportJson()
                val bytes = text.toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) { context.contentResolver.writeAll(uri, bytes) }
                "Backup saved" to "All recipes, versions and cookings were written to the file (${formatSize(bytes.size.toLong())})."
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runTask("Import") {
                val result = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.readTextLimited(uri, BackupManager.MAX_IMPORT_BYTES)
                    container.backup.importJson(text)
                }
                dataVersion++
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
                onClick = { context.shareText(text) },
            )
        }
        busy?.let { label ->
            Text("$label in progress…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }

    dialog?.let { (title, message) ->
        MessageDialog(title = title, message = message, onDismiss = { dialog = null })
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
