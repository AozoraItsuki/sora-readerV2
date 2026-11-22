package app.sorareader.data.plugin

import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.ChapterContent
import app.sorareader.domain.model.Novel
import app.sorareader.domain.model.NovelStatus
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
                val items = if (query.isBlank()) rt.popularNovels(sourceId, page, false) else rt.searchNovels(sourceId, query, page)
                items.map { item ->
                    Novel(
                        sourceId = sourceId,
                        url = item.path,
                        title = item.name,
                        author = "",
                        coverUrl = item.cover,
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
                    url = ch.path,
                    title = ch.name,
                    number = ch.chapterNumber ?: (idx + 1).toFloat(),
                    releaseDate = ch.releaseTime,
                    position = idx,
                    page = ch.page ?: "1",
                )
            }
            SourceNovel(
                sourceId = sourceId,
                url = novel.path.ifBlank { novelUrl },
                title = novel.name,
                author = novel.author ?: "",
                artist = novel.artist ?: "",
                coverUrl = novel.cover,
                description = novel.summary ?: "",
                status = parsePluginNovelStatus(novel.status),
                genres = splitCsv(novel.genres),
                tags = emptyList(),
                chapters = chapters,
                totalPages = novel.totalPages,
            )
        }
    }

    override suspend fun fetchChapters(sourceId: String, novelUrl: String): List<Chapter> {
        return fetchNovelDetails(sourceId, novelUrl).chapters
    }

    override suspend fun fetchChapterContent(sourceId: String, chapterUrl: String): ChapterContent {
        return pluginManager.withPlugin(sourceId) { rt ->
            val raw = rt.parseChapter(sourceId, chapterUrl)
            val text = raw.trim()
            val paragraphs = if (text.contains("<") && text.contains(">")) {
                htmlToParagraphs(text)
            } else {
                text.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
            ChapterContent(
                chapterId = chapterUrl.hashCode().toLong(),
                title = "",
                paragraphs = paragraphs.ifEmpty { listOf(text.ifBlank { "No content." }) },
                html = if (text.contains("<")) text else null,
            )
        }
    }

    private fun splitCsv(raw: String?): List<String> = raw?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun htmlToParagraphs(html: String): List<String> {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style").remove()
        val blocks = doc.body().select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote")
        val out = blocks.map { it.text().trim() }.filter { it.isNotBlank() }
        if (out.isNotEmpty()) return out
        return doc.body().text().split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }
}
