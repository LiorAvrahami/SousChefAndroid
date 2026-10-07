package com.lioravrahami.souschef

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Saves full-screen screenshots (including dialogs and system bars) into the app's
 * private `files/screenshots` folder. The CI script pulls them out with `run-as`
 * and uploads them, so a human (or an AI) can look at what the app really rendered.
 */
object Screenshots {
    private var counter = 0

    /**
     * Pass the test's [rule] when one exists: it waits for Compose animations (screen
     * fades, sheet slides) to finish, so the picture shows the settled screen.
     */
    fun take(name: String, rule: ComposeTestRule? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (rule != null) {
            rule.waitForIdle()
            Thread.sleep(400)
            rule.waitForIdle()
        } else {
            Thread.sleep(400)
        }
        instrumentation.waitForIdleSync()
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull() ?: return
        val dir = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        counter += 1
        val file = File(dir, String.format(Locale.US, "%02d_%s.png", counter, name))
        runCatching {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
}
