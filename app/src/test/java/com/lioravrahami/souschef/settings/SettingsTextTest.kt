package com.lioravrahami.souschef.settings

import com.lioravrahami.souschef.data.backup.BackupManager
import com.lioravrahami.souschef.ui.settings.apiKeyStatus
import com.lioravrahami.souschef.ui.settings.backupFileName
import com.lioravrahami.souschef.ui.settings.importSummary
import com.lioravrahami.souschef.ui.settings.isDeleteConfirmed
import com.lioravrahami.souschef.ui.settings.percentText
import com.lioravrahami.souschef.ui.settings.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SettingsTextTest {
    @Test
    fun deleteMustBeTypedExactly() {
        assertTrue(isDeleteConfirmed("delete"))
        assertTrue(isDeleteConfirmed("  DELETE "))
        assertTrue(isDeleteConfirmed("Delete"))
        assertFalse(isDeleteConfirmed(""))
        assertFalse(isDeleteConfirmed("delet"))
        assertFalse(isDeleteConfirmed("deleted"))
        assertFalse(isDeleteConfirmed("delete it"))
    }

    @Test
    fun importSummaryReadsNaturally() {
        assertEquals(
            "Added 3 recipes, 5 versions, 12 cookings; 2 recipes already existed.",
            importSummary(BackupManager.ImportResult(3, 5, 12, 2)),
        )
        assertEquals(
            "Added 1 recipe, 1 version, 0 cookings.",
            importSummary(BackupManager.ImportResult(1, 1, 0, 0)),
        )
        assertEquals(
            "Nothing new to add; 1 recipe already existed.",
            importSummary(BackupManager.ImportResult(0, 0, 0, 1)),
        )
        assertEquals("The file holds no recipes to add.", importSummary(BackupManager.ImportResult(0, 0, 0, 0)))
    }

    @Test
    fun keyStatusShowsOnlyTheLastFourCharacters() {
        assertEquals("No key saved", apiKeyStatus(""))
        assertEquals("No key saved", apiKeyStatus("   "))
        assertEquals("Key saved (ends with …a1b2)", apiKeyStatus("sk-or-v1-secretsecreta1b2"))
        assertEquals("Key saved (ends with …ab)", apiKeyStatus("ab"))
    }

    @Test
    fun percentAndFileName() {
        assertEquals("15%", percentText(0.15f))
        assertEquals("2%", percentText(0.02f))
        assertEquals("souschef-backup-2026-03-04.json", backupFileName(LocalDate.of(2026, 3, 4)))
    }

    @Test
    fun relativeTimes() {
        val zone: ZoneId = ZoneOffset.UTC
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
        val now = at(2026, 10, 7, 18, 0)
        assertEquals("just now", relativeTime(now - 10_000, now, zone))
        assertEquals("just now", relativeTime(now + 10_000, now, zone))
        assertEquals("1 minute ago", relativeTime(at(2026, 10, 7, 17, 59), now, zone))
        assertEquals("45 minutes ago", relativeTime(at(2026, 10, 7, 17, 15), now, zone))
        assertEquals("3 hours ago", relativeTime(at(2026, 10, 7, 15, 0), now, zone))
        assertEquals("yesterday", relativeTime(at(2026, 10, 6, 9, 0), now, zone))
        assertEquals("4 days ago", relativeTime(at(2026, 10, 3, 9, 0), now, zone))
        assertEquals("on 12 Mar 2026", relativeTime(at(2026, 3, 12, 9, 0), now, zone))
    }
}
