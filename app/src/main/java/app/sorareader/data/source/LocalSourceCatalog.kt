package app.sorareader.data.source

import android.content.Context
import app.sorareader.data.local.LocalBookFiles
import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.ChapterContent
import app.sorareader.domain.model.Novel
import app.sorareader.domain.model.SourceDescriptor
import app.sorareader.domain.model.SourceNovel
import app.sorareader.domain.repository.NovelRepository
import app.sorareader.domain.repository.SourceCatalog
import kotlinx.coroutines.flow.first

/** Serves imported EPUBs as a regular source so refresh/reader paths need no special cases. */
class LocalSourceCatalog(
    private val context: Context,
    private val repo: NovelRepository,
) : SourceCatalog {

    override fun availableSources(): List<SourceDescriptor> = listOf(
        SourceDescriptor(
            id = LocalBookFiles.SOURCE_ID,
            name = "Local books",
            lang = "multi",
            version = "1.0.0",
            site = "local://",
            isInstalled = true,
        ),
    )

    override suspend fun browse(sourceId: String, query: String, page: Int): List<Novel> = emptyList()

    override suspend fun fetchNovelDetails(sourceId: String, novelUrl: String): SourceNovel {
        require(sourceId == LocalBookFiles.SOURCE_ID) { "Not a local source: $sourceId" }
        val novel = repo.getNovelBySourceUrl(LocalBookFiles.SOURCE_ID, novelUrl)
            ?: error("Local novel not found")
        return SourceNovel(
            sourceId = novel.sourceId,
            url = novel.url,
            title = novel.title,
            author = novel.author,
            artist = novel.artist,
            coverUrl = novel.coverUrl,
            description = novel.description,
            status = novel.status,
            genres = novel.genres,
            tags = novel.tags,
            chapters = repo.getChapters(novel.id),
            totalPages = 1,
        )
    }

    override suspend fun fetchChapters(sourceId: String, novelUrl: String): List<Chapter> {
        if (sourceId != LocalBookFiles.SOURCE_ID) return emptyList()
        val novel = repo.getNovelBySourceUrl(LocalBookFiles.SOURCE_ID, novelUrl) ?: return emptyList()
        return repo.getChapters(novel.id)
    }

    override suspend fun fetchChapterContent(sourceId: String, chapterUrl: String): ChapterContent {
        require(sourceId == LocalBookFiles.SOURCE_ID) { "Not a local source: $sourceId" }
        val novelId = LocalBookFiles.parseChapterUrl(chapterUrl)?.first
        val chapter = novelId?.let { id ->
            repo.getChapters(id).firstOrNull { it.url == chapterUrl }
        } ?: repo.observeLibrary().first().flatMap { novel -> repo.getChapters(novel.id) }
            .firstOrNull { it.url == chapterUrl }
            ?: error("Local chapter not found")

        val path = chapter.contentPath?.takeIf { it.isNotBlank() }
            ?: LocalBookFiles.chapterFile(context.filesDir, chapter.novelId, chapter.id).absolutePath
        return LocalBookFiles.readContent(path, chapter.title)
            ?.copy(chapterId = chapter.id)
            ?: error("Local chapter file is missing")
    }
}
