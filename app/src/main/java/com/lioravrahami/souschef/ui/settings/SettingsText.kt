package com.lioravrahami.souschef.ui.settings

import com.lioravrahami.souschef.data.backup.BackupManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The word the user must type before anything is deleted forever. */
const val CONFIRM_WORD = "delete"

/** True when [typed] is exactly "delete" (ignoring case and surrounding spaces). */
fun isDeleteConfirmed(typed: String): Boolean = typed.trim().equals(CONFIRM_WORD, ignoreCase = true)

/** "1 recipe", "3 recipes". */
internal fun plural(count: Int, singular: String, pluralForm: String = singular + "s"): String =
    "$count ${if (count == 1) singular else pluralForm}"

/** Human summary of an import, e.g. "Added 3 recipes, 5 versions, 12 cookings; 2 recipes already existed". */
fun importSummary(result: BackupManager.ImportResult): String {
    val added = result.recipesAdded + result.versionsAdded + result.trialsAdded
    val existed = if (result.recipesSkipped > 0) {
        "${plural(result.recipesSkipped, "recipe")} already existed"
    } else {
        null
    }
    if (added == 0) {
        return if (existed != null) "Nothing new to add; $existed." else "The file holds no recipes to add."
    }
    val main = "Added ${plural(result.recipesAdded, "recipe")}, ${plural(result.versionsAdded, "version")}, " +
        plural(result.trialsAdded, "cooking")
    return if (existed != null) "$main; $existed." else "$main."
}

/** Status line for the stored OpenRouter key, showing at most its last four characters. */
fun apiKeyStatus(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return "No key saved"
    return "Key saved (ends with …${trimmed.takeLast(4)})"
}

/** 0.15 -> "15%". */
fun percentText(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

/** Suggested name of an exported backup file, e.g. "souschef-backup-2026-10-07.json". */
fun backupFileName(date: LocalDate): String = "souschef-backup-${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"

/**
 * Short relative description of a past moment: "just now", "5 minutes ago", "2 hours ago",
 * "yesterday", "3 days ago", or a date such as "on 12 Mar 2026" when older than a week.
 */
fun relativeTime(thenMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = (nowMillis - thenMillis).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = minutes / 60
    val thenDate = Instant.ofEpochMilli(thenMillis).atZone(zone).toLocalDate()
    val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = (nowDate.toEpochDay() - thenDate.toEpochDay()).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "${plural(minutes.toInt(), "minute")} ago"
        days == 0L -> "${plural(hours.toInt(), "hour")} ago"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        else -> "on " + thenDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }
}
