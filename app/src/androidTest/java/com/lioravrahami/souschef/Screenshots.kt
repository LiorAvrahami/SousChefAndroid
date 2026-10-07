package com.lioravrahami.souschef

import android.graphics.Bitmap
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

    fun take(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
