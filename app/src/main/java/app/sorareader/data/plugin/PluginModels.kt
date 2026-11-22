package app.sorareader.data.plugin

import app.sorareader.domain.model.NovelStatus
import app.sorareader.domain.model.SourceDescriptor
import kotlinx.serialization.Serializable

@Serializable
data class InstalledPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String = "en",
    val version: String = "1.0.0",
    val iconUrl: String? = null,
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

fun parsePluginNovelStatus(raw: String?): NovelStatus = when (raw?.trim()?.lowercase()) {
    "ongoing" -> NovelStatus.ONGOING
    "completed" -> NovelStatus.COMPLETED
    "licensed" -> NovelStatus.LICENSED
    "publishing finished" -> NovelStatus.PUBLISHING_FINISHED
    "cancelled", "canceled" -> NovelStatus.CANCELLED
    "on hiatus", "hiatus" -> NovelStatus.ON_HIATUS
    else -> NovelStatus.UNKNOWN
}
