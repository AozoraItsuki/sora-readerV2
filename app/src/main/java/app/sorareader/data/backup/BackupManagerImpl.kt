package app.sorareader.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.sorareader.data.local.db.SoraDatabase
import app.sorareader.data.local.entity.CategoryEntity
import app.sorareader.data.local.entity.ChapterEntity
import app.sorareader.data.local.entity.NovelCategoryEntity
import app.sorareader.data.local.entity.ReadingHistoryEntity
import app.sorareader.data.local.entity.RepositoryEntity
import app.sorareader.data.local.entity.toDomain
import app.sorareader.data.settings.AppSettingsStore
import app.sorareader.domain.backup.BackupManager
import app.sorareader.domain.backup.BackupResult
import app.sorareader.domain.model.BackupOptions
import app.sorareader.domain.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Real JSON backup/restore.
 *
 * Export collects every table into a [BackupPayload] and writes it to a SAF URI.
 * Import is transactional (for the Room part) and re-maps all local ids:
 *  - novels are upserted by (sourceId, url)
 *  - chapters are keyed by (novel sourceId+url, chapter url) and re-pointed at restored novels
 *  - reading history's chapter ids are re-mapped through the restored chapters
 */
class BackupManagerImpl(
    private val context: Context,
    private val db: SoraDatabase,
    private val novelRepository: NovelRepository,
    private val settingsStore: AppSettingsStore,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) : BackupManager {

    private val novelDao = db.novelDao()
    private val chapterDao = db.chapterDao()
    private val categoryDao = db.categoryDao()
    private val repositoryDao = db.repositoryDao()
    private val historyDao = db.historyDao()

    override suspend fun exportToUri(uri: Uri, options: BackupOptions): BackupResult = withContext(Dispatchers.IO) {
        try {
            val text = buildJson(options)
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                BackupResult.Failure("Cannot open destination file")
            } else {
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                BackupResult.Success("Backup exported (${text.length / 1024} KB)")
            }
        } catch (t: Throwable) {
            BackupResult.Failure(t.message ?: "Export failed")
        }
    }

    override suspend fun exportToJson(options: BackupOptions): String = withContext(Dispatchers.IO) {
        buildJson(options)
    }

    private suspend fun buildJson(options: BackupOptions): String {
        val payload = BackupPayload(
            novels = if (options.novels) novelDao.getAll().map { it.toDomain() } else emptyList(),
            chapters = if (options.novels) chapterDao.getAll().map { it.toDomain() } else emptyList(),
            categories = if (options.categories) categoryDao.getAll().map { it.toDomain() } else emptyList(),
            novelCategories = if (options.categories) categoryDao.getAllNovelCategories().map { it.toDomain() } else emptyList(),
            repositories = if (options.repositories) repositoryDao.getAll().map { it.toDomain() } else emptyList(),
            readingHistory = historyDao.getAll().map { HistoryBackup(it.chapterId, it.readAt, it.readDuration) },
            settings = if (options.settings) settingsStore.preferences.firstOrNull() else null,
        )
        return json.encodeToString(BackupPayload.serializer(), payload)
    }

    override suspend fun importFromUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                BackupResult.Failure("Cannot open backup file")
            } else {
                val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
                doImport(text)
            }
        } catch (t: Throwable) {
            BackupResult.Failure(t.message ?: "Import failed")
        }
    }

    override suspend fun importFromJson(json: String): BackupResult = withContext(Dispatchers.IO) {
        doImport(json)
    }

    private suspend fun doImport(text: String): BackupResult {
        val payload = try {
            json.decodeFromString<BackupPayload>(text)
        } catch (e: SerializationException) {
            return BackupResult.Failure("Invalid backup file: ${e.message}")
        } catch (t: Throwable) {
            return BackupResult.Failure("Cannot read backup: ${t.message}")
        }
        if (payload.format != BackupPayload.FORMAT) return BackupResult.Failure("Unrecognized backup format")
        if (payload.version > BackupPayload.VERSION) {
            return BackupResult.Failure("Backup version ${payload.version} is newer than supported (${BackupPayload.VERSION})")
        }

        // Settings live in DataStore, outside the Room transaction (restore is idempotent).
        payload.settings?.let { settingsStore.restore(it) }

        return try {
            db.withTransaction {
                // Repositories: additive, keyed by id/url (both have unique constraints).
                payload.repositories.forEach { repo ->
                    repositoryDao.insert(RepositoryEntity(id = repo.id, url = repo.url, enabled = repo.enabled))
                }

                // Categories: keep ids stable where possible; build old -> new id map via unique name.
                val categoryMap = HashMap<Long, Long>()
                payload.categories.forEach { cat ->
                    categoryDao.insert(CategoryEntity(id = cat.id, name = cat.name, sort = cat.sort))
                    categoryDao.getByName(cat.name)?.let { categoryMap[cat.id] = it.id }
                }

                // Novels: upsert by (sourceId, url). Pass id = 0 to force source/url resolution.
                val novelMap = HashMap<Long, Long>()
                payload.novels.forEach { novel ->
                    novelMap[novel.id] = novelRepository.upsertNovel(novel.copy(id = 0L))
                }

                // Chapters: re-point at restored novels, key by (novelKey, chapterUrl),
                // then map old chapter id -> new chapter id for history.
                val chapterMap = HashMap<Long, Long>()
                payload.chapters.forEach { chapter ->
                    novelMap[chapter.novelId]?.let { newNovelId ->
                        val newId = chapterDao.upsert(
                            ChapterEntity(
                                id = 0L,
                                novelId = newNovelId,
                                url = chapter.url,
                                title = chapter.title,
                                number = chapter.number,
                                releaseDate = chapter.releaseDate,
                                readProgress = chapter.readProgress,
                                isRead = chapter.isRead,
                                isDownloaded = chapter.isDownloaded,
                                isBookmarked = chapter.isBookmarked,
                                contentPath = chapter.contentPath,
                                position = chapter.position,
                                page = chapter.page,
                                updatedTime = chapter.updatedTime,
                            )
                        )
                        // Authoritative id after the unique (novelId, url) conflict was resolved.
                        chapterMap[chapter.id] = chapterDao.getByNovelUrl(newNovelId, chapter.url)?.id ?: newId
                    }
                }

                // Novel <-> category links (additive).
                payload.novelCategories.forEach { nc ->
                    val nId = novelMap[nc.novelId]
                    val cId = categoryMap[nc.categoryId]
                    if (nId != null && cId != null) {
                        categoryDao.insertNovelCategory(NovelCategoryEntity(nId, cId))
                    }
                }

                // Reading history: remap chapter ids.
                payload.readingHistory.forEach { h ->
                    chapterMap[h.chapterId]?.let { cId ->
                        historyDao.upsert(ReadingHistoryEntity(cId, h.readAt, h.readDuration))
                    }
                }

                // Recompute derived counts after chapters were restored.
                novelMap.values.distinct().forEach { novelRepository.refreshNovelStats(it) }

                BackupResult.Success(
                    "Restored ${novelMap.size} novels, ${chapterMap.size} chapters, " +
                        "${categoryMap.size} categories, ${payload.repositories.size} repositories, " +
                        "${payload.readingHistory.size} history entries"
                )
            }
        } catch (t: Throwable) {
            BackupResult.Failure(t.message ?: "Import failed")
        }
    }
}
