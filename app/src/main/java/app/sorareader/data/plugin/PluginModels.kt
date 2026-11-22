package app.sorareader.data.plugin

import app.sorareader.domain.model.NovelStatus
import app.sorareader.domain.model.SourceDescriptor
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class InstalledPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String = "en",
    val version: String = "1.0.0",
    val iconUrl: String? = null,
    /** Optional repository-provided custom script/stylesheet URLs. */
    val customJS: String? = null,
    val customCSS: String? = null,
    val sourceUrl: String? = null,
    val hasSettings: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
) {
    fun toDescriptor() = SourceDescriptor(
        id = id,
        name = name,
        lang = lang,
        version = version,
        site = site,
        iconUrl = iconUrl,
        hasSettings = hasSettings,
        isInstalled = true,
    )
}

@Serializable
data class PluginIndex(val plugins: List<InstalledPlugin> = emptyList())

data class PluginNovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
)

data class PluginChapterItem(
    val name: String,
    val path: String,
    val chapterNumber: Float? = null,
    val releaseTime: String? = null,
    val page: String? = null,
)

data class PluginSourceNovel(
    val name: String,
    val path: String,
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val chapters: List<PluginChapterItem> = emptyList(),
    val totalPages: Int = 1,
)

/**
 * Tolerant mapping of the legacy `@libs/novelStatus` string values (and the
 * common spelling variants plugins use) onto the app's status enum.
 */
fun parsePluginNovelStatus(raw: String?): NovelStatus {
    if (raw.isNullOrBlank()) return NovelStatus.UNKNOWN
    val key = raw.trim()
        // Some plugins stringify the enum: "NovelStatus.Completed".
        .substringAfterLast('.')
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_\\-]+"), "")
    return when (key) {
        "ongoing", "ongoingseries", "publishing", "inprogress" -> NovelStatus.ONGOING
        "completed", "complete", "finished" -> NovelStatus.COMPLETED
        "licensed" -> NovelStatus.LICENSED
        "publishingfinished" -> NovelStatus.PUBLISHING_FINISHED
        "cancelled", "canceled" -> NovelStatus.CANCELLED
        "onhiatus", "hiatus" -> NovelStatus.ON_HIATUS
        else -> NovelStatus.UNKNOWN
    }
}
