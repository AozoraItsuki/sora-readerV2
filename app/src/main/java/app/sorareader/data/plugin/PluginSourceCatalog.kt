package app.sorareader.data.plugin

import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.ChapterContent
import app.sorareader.domain.model.Novel
import app.sorareader.domain.model.SourceDescriptor
import app.sorareader.domain.model.SourceNovel
import app.sorareader.domain.repository.SourceCatalog
import org.jsoup.Jsoup

class PluginSourceCatalog(
    private val pluginManager: PluginManager,
) : SourceCatalog {

    override fun availableSources(): List<SourceDescriptor> = emptyList()

    override suspend fun availableSourcesAsync(): List<SourceDescriptor> {
        return runCatching { pluginManager.listInstalled().map { it.toDescriptor() } }.getOrElse { emptyList() }
    }

    override suspend fun browse(sourceId: String, query: String, page: Int): List<Novel> {
        return runCatching {
            pluginManager.withPlugin(sourceId) { rt ->
                val items = if (query.isBlank()) {
                    rt.popularNovels(sourceId, page, false)
                } else {
                    rt.searchNovels(sourceId, query, page)
                }
                items.mapNotNull { item ->
                    val path = rt.resolveUrlSafe(sourceId, item.path, isNovel = true)
                    if (path.isBlank()) return@mapNotNull null
                    Novel(
                        sourceId = sourceId,
                        url = path,
                        title = item.name.ifBlank { return@mapNotNull null },
                        author = "",
                        coverUrl = item.cover?.let { rt.resolveUrlSafe(sourceId, it, isNovel = false) },
                    )
                }
            }
        }.getOrElse { e ->
            println("[PluginSourceCatalog] browse failed for $sourceId: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchNovelDetails(sourceId: String, novelUrl: String): SourceNovel {
        return pluginManager.withPlugin(sourceId) { rt ->
            val novel = rt.parseNovel(sourceId, novelUrl)
            val chapters = novel.chapters.mapIndexed { idx, ch ->
                Chapter(
                    novelId = 0,
                    url = rt.resolveUrlSafe(sourceId, ch.path, isNovel = false).ifBlank { ch.path },
                    title = ch.name.ifBlank { "Chapter ${idx + 1}" },
                    number = ch.chapterNumber ?: (idx + 1).toFloat(),
                    releaseDate = ch.releaseTime,
                    position = idx,
                    page = ch.page ?: "1",
                )
            }
            SourceNovel(
                sourceId = sourceId,
                url = rt.resolveUrlSafe(sourceId, novel.path, isNovel = true).ifBlank { novelUrl },
                title = novel.name.ifBlank { "Untitled" },
                author = novel.author ?: "",
                artist = novel.artist ?: "",
                coverUrl = novel.cover?.let { rt.resolveUrlSafe(sourceId, it, isNovel = false) },
                description = novel.summary ?: "",
                status = parsePluginNovelStatus(novel.status),
                genres = splitList(novel.genres),
                tags = emptyList(),
                chapters = chapters,
                totalPages = novel.totalPages.coerceAtLeast(1),
            )
        }
    }

    override suspend fun fetchChapters(sourceId: String, novelUrl: String): List<Chapter> {
        return fetchNovelDetails(sourceId, novelUrl).chapters
    }

    override suspend fun fetchChapterContent(sourceId: String, chapterUrl: String): ChapterContent {
        return pluginManager.withPlugin(sourceId) { rt ->
            val raw = rt.parseChapter(sourceId, chapterUrl)
            val normalized = raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
            val trimmed = normalized.trim()
            val paragraphs = if (trimmed.contains("<") && trimmed.contains(">")) {
                htmlToParagraphs(trimmed)
            } else {
                trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
            ChapterContent(
                chapterId = chapterUrl.hashCode().toLong(),
                title = "",
                paragraphs = paragraphs.ifEmpty { listOf(trimmed.ifBlank { "No content." }) },
                html = if (trimmed.contains("<")) trimmed else null,
            )
        }
    }

    private fun splitList(raw: String?): List<String> = raw
        ?.split(',', ';', '|')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    private fun htmlToParagraphs(html: String): List<String> {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style").remove()
        val blocks = doc.body().select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote")
        val out = blocks.flatMap { block ->
            block.html().split(Regex("(?i)<br\\s*/?>"))
                .map { Jsoup.parseBodyFragment(it).text().trim() }
                .filter { it.isNotBlank() }
        }.distinct()
        if (out.isNotEmpty()) return out
        return doc.body().html().split(Regex("(?i)<br\\s*/?>"))
            .map { Jsoup.parseBodyFragment(it).text().trim() }
            .filter { it.isNotBlank() }
    }

    /** Legacy plugins lack resolveUrl; keep the original path in that case. */
    private fun PluginRuntime.resolveUrlSafe(sourceId: String, path: String, isNovel: Boolean): String {
        if (path.isBlank()) return path
        return runCatching { resolveUrl(sourceId, path, isNovel) }.getOrDefault(path)
    }
}
