package app.sorareader.data.source

import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.ChapterContent
import app.sorareader.domain.model.Novel
import app.sorareader.domain.model.SourceDescriptor
import app.sorareader.domain.model.SourceNovel
import app.sorareader.domain.repository.SourceCatalog

class CompositeSourceCatalog(private val delegates: List<SourceCatalog>) : SourceCatalog {
    override fun availableSources(): List<SourceDescriptor> = delegates.flatMap { runCatching { it.availableSources() }.getOrDefault(emptyList()) }.distinctBy { it.id }

    override suspend fun availableSourcesAsync(): List<SourceDescriptor> =
        delegates.flatMap { runCatching { it.availableSourcesAsync() }.getOrDefault(emptyList()) }.distinctBy { it.id }

    override suspend fun browse(sourceId: String, query: String, page: Int): List<Novel> =
        delegateFor(sourceId)?.browse(sourceId, query, page) ?: emptyList()

    override suspend fun fetchNovelDetails(sourceId: String, novelUrl: String): SourceNovel =
        delegateFor(sourceId)?.fetchNovelDetails(sourceId, novelUrl) ?: error("Unknown source: $sourceId")

    override suspend fun fetchChapters(sourceId: String, novelUrl: String): List<Chapter> =
        delegateFor(sourceId)?.fetchChapters(sourceId, novelUrl) ?: emptyList()

    override suspend fun fetchChapterContent(sourceId: String, chapterUrl: String): ChapterContent =
        delegateFor(sourceId)?.fetchChapterContent(sourceId, chapterUrl) ?: error("Unknown source: $sourceId")

    private suspend fun delegateFor(sourceId: String): SourceCatalog? {
        for (d in delegates) {
            val has = runCatching {
                val sync = d.availableSources()
                if (sync.any { it.id == sourceId }) true else d.availableSourcesAsync().any { it.id == sourceId }
            }.getOrDefault(false)
            if (has) return d
        }
        // fallback: if only one delegate can handle via try/catch, let caller fail; but plugin catalog may not list until loaded.
        return delegates.firstOrNull { d ->
            runCatching { d.availableSourcesAsync().any { it.id == sourceId } }.getOrDefault(false)
        }
    }
}
