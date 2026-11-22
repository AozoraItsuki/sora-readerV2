package app.sorareader.data.backup

import app.sorareader.data.settings.AppPreferences
import app.sorareader.domain.model.Category
import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.Novel
import app.sorareader.domain.model.NovelCategory
import app.sorareader.domain.model.Repository
import kotlinx.serialization.Serializable

/** A raw reading-history row. Chapter ids are re-mapped on import via the chapters list. */
@Serializable
data class HistoryBackup(
    val chapterId: Long,
    val readAt: Long,
    val readDuration: Long = 0L,
)

/** Versioned, self-contained backup document. */
@Serializable
data class BackupPayload(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val novels: List<Novel> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val categories: List<Category> = emptyList(),
    val novelCategories: List<NovelCategory> = emptyList(),
    val repositories: List<Repository> = emptyList(),
    val readingHistory: List<HistoryBackup> = emptyList(),
    val settings: AppPreferences? = null,
) {
    companion object {
        const val FORMAT = "sora-reader-backup"
        const val VERSION = 1
    }
}
