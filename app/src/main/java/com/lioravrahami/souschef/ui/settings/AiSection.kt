package com.lioravrahami.souschef.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.settings.AppSettings
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OpenRouter key and model. The stored key is never shown again: the field always
 * starts empty and only the last four characters appear in the status line.
 *
 * @param settingsTick value of `AppSettings.changes`, so stored values are re-read after writes.
 */
@Composable
fun AiSection(container: AppContainer, settingsTick: Int) {
    val settings = container.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val storedKey = remember(settingsTick) { settings.openRouterApiKey }
    val storedModel = remember(settingsTick) { settings.openRouterModel }

    var keyInput by remember { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var modelInput by rememberSaveable(storedModel) { mutableStateOf(storedModel) }
    var testing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    SettingsSection(title = "AI suggestions (OpenRouter)") {
        HelpText("Optional. Get a key at openrouter.ai; the AI optimizer costs a fraction of a cent per suggestion.")

        Text(
            apiKeyStatus(storedKey),
            style = MaterialTheme.typography.titleMedium,
            color = if (storedKey.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (storedKey.isBlank()) "API key" else "New API key") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.SETTINGS_API_KEY),
        )
        BigButton(
            text = "Save key",
            enabled = keyInput.isNotBlank(),
            onClick = {
                settings.openRouterApiKey = keyInput.trim()
                keyInput = ""
                showKey = false
                if (settings.hasApiKey) {
                    Toast.makeText(context, "Key saved", Toast.LENGTH_SHORT).show()
                } else {
                    dialog = "Key not saved" to "The phone's secure storage refused to store the key. Please try again."
                }
            },
        )
        if (storedKey.isNotBlank()) {
            BigOutlinedButton(text = "Remove key", onClick = { confirmRemove = true })
        }

        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            label = { Text("Model") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        BigButton(
            text = "Save model",
            enabled = modelInput.isNotBlank() && modelInput.trim() != storedModel,
            onClick = {
                settings.openRouterModel = modelInput.trim()
                Toast.makeText(context, "Model saved", Toast.LENGTH_SHORT).show()
            },
        )
        BigOutlinedButton(
            text = "Reset to default (${AppSettings.DEFAULT_MODEL})",
            enabled = storedModel != AppSettings.DEFAULT_MODEL || modelInput.trim() != AppSettings.DEFAULT_MODEL,
            onClick = {
                settings.openRouterModel = AppSettings.DEFAULT_MODEL
                modelInput = AppSettings.DEFAULT_MODEL
            },
        )

        if (testing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                Text("Asking the AI…", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            BigOutlinedButton(
                text = "Test connection",
                enabled = storedKey.isNotBlank(),
                onClick = {
                    testing = true
                    scope.launch {
                        dialog = try {
                            val reply = withContext(Dispatchers.IO) { container.openRouter.testConnection() }
                            "Connection works" to "The AI replied:\n\n${reply.trim()}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Connection failed" to (e.message ?: e.toString())
                        } finally {
                            testing = false
                        }
                    }
                },
            )
            if (storedKey.isBlank()) HelpText("Save a key to test the connection.")
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the API key?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("AI suggestions stop working until you save a key again.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                Button(
                    onClick = {
                        settings.openRouterApiKey = ""
                        confirmRemove = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Remove", style = MaterialTheme.typography.titleMedium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text("Cancel", style = MaterialTheme.typography.titleMedium)
                }
            },
        )
    }

    dialog?.let { (title, message) ->
        MessageDialog(title = title, message = message, onDismiss = { dialog = null })
    }
}
