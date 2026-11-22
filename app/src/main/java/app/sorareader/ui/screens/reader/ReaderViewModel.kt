package app.sorareader.ui.screens.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sorareader.data.local.LocalBookFiles
import app.sorareader.data.settings.AppSettingsStore
import app.sorareader.data.settings.ReaderSettings
import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.ChapterContent
import app.sorareader.domain.repository.NovelRepository
import app.sorareader.domain.repository.SourceCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val chapter: Chapter? = null,
    val content: ChapterContent? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fontSize: Float = 16f,
    val lineHeight: Float = 1.6f,
    val padding: Int = 16,
    val keepScreenOn: Boolean = true,
    val fullscreen: Boolean = true,
    val incognito: Boolean = false,
    val prevChapterId: Long? = null,
    val nextChapterId: Long? = null,
)

class ReaderViewModel(
    private val repo: NovelRepository,
    private val catalog: SourceCatalog,
    private val appContext: Context,
    private val settings: AppSettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var readerSettings = ReaderSettings()
    private var incognito = false

    /** Last progress reported by the WebView; flushed on a debounce and on dispose. */
    private var pendingProgress: Float? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            settings.observe().collect { prefs ->
                readerSettings = prefs.reader
                incognito = prefs.incognito
                _state.value = _state.value.copy(
                    fontSize = prefs.reader.fontSize,
                    lineHeight = prefs.reader.lineHeight,
                    padding = prefs.reader.padding,
                    keepScreenOn = prefs.reader.keepScreenOn,
                    fullscreen = prefs.reader.fullscreen,
                    incognito = prefs.incognito,
                )
            }
        }
    }

    fun load(novelId: Long, chapterId: Long) {
        // Never lose progress from the chapter that was open before a switch.
        saveProgress()
        pendingProgress = null
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                error = null,
                chapter = null,
                content = null,
                prevChapterId = null,
                nextChapterId = null,
            )
            runCatching {
                val chapter = repo.getChapterById(chapterId) ?: error("Chapter not found")
                val novel = repo.getNovelById(novelId) ?: error("Novel not found")
                val content = loadContent(chapter, novel.sourceId)

                val chapters = repo.getChapters(novelId).sortedWith(
                    compareBy<Chapter> { it.position }.thenBy { it.number }.thenBy { it.id }
                )
                val index = chapters.indexOfFirst { it.id == chapter.id }
                val prev = if (index > 0) chapters[index - 1].id else null
                val next = if (index >= 0 && index < chapters.size - 1) chapters[index + 1].id else null

                // Seed progress from what was previously saved; do NOT force 100% on load.
                pendingProgress = chapter.readProgress.coerceIn(0f, 1f)

                if (!incognito) repo.recordHistory(chapter.id)
                _state.value = _state.value.copy(
                    chapter = chapter,
                    content = content,
                    loading = false,
                    prevChapterId = prev,
                    nextChapterId = next,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load chapter")
            }
        }
    }

    private suspend fun loadContent(chapter: Chapter, sourceId: String): ChapterContent {
        chapter.contentPath?.takeIf { it.isNotBlank() }?.let { path ->
            // Local EPUB chapters are stored as sanitized HTML; legacy downloads are plain text.
            LocalBookFiles.readContent(path, chapter.title)?.let { local ->
                return local.copy(chapterId = chapter.id)
            }
        }
        val remote = catalog.fetchChapterContent(sourceId, chapter.url)
        val dir = File(appContext.filesDir, "downloads/${chapter.novelId}").also { it.mkdirs() }
        val file = File(dir, "${chapter.id}.txt")
        file.writeText(remote.paragraphs.joinToString("\n\n"))
        repo.markChapterDownloaded(chapter.id, true, file.absolutePath)
        return remote.copy(chapterId = chapter.id)
    }

    /** Called from the WebView JS bridge on scroll/resize. Cheap: only stores + schedules a debounced save. */
    fun onProgress(progress: Float) {
        val chapterId = _state.value.chapter?.id ?: return
        val clamped = progress.coerceIn(0f, 1f)
        pendingProgress = clamped
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val p = pendingProgress ?: return@launch
            pendingProgress = null
            flushProgress(chapterId, p, incognito)
        }
    }

    /** Flush immediately (called from the screen's DisposableEffect and before switching chapters). */
    fun saveProgress() {
        saveJob?.cancel()
        val chapterId = _state.value.chapter?.id ?: return
        val progress = pendingProgress ?: return
        pendingProgress = null
        val isIncognito = incognito
        viewModelScope.launch { flushProgress(chapterId, progress, isIncognito) }
    }

    private suspend fun flushProgress(chapterId: Long, progress: Float, isIncognito: Boolean) {
        val isRead = progress >= READ_THRESHOLD
        runCatching {
            repo.updateChapterProgress(chapterId, progress, isRead, recordHistory = !isIncognito)
        }
        val current = _state.value.chapter
        if (current?.id == chapterId) {
            _state.value = _state.value.copy(chapter = current.copy(readProgress = progress, isRead = isRead))
        }
    }

    fun adjustFontSize(delta: Float) {
        val next = (readerSettings.fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        readerSettings = readerSettings.copy(fontSize = next)
        _state.value = _state.value.copy(fontSize = next)
        viewModelScope.launch { settings.setFontSize(next) }
    }

    fun adjustLineHeight(delta: Float) {
        val next = (readerSettings.lineHeight + delta).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        readerSettings = readerSettings.copy(lineHeight = next)
        _state.value = _state.value.copy(lineHeight = next)
        viewModelScope.launch { settings.setLineHeight(next) }
    }

    override fun onCleared() {
        saveJob?.cancel()
        val chapterId = _state.value.chapter?.id
        val progress = pendingProgress
        val isIncognito = incognito
        pendingProgress = null
        if (chapterId != null && progress != null) {
            // viewModelScope is cancelled at this point, so use a short-lived IO scope.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    repo.updateChapterProgress(chapterId, progress, progress >= READ_THRESHOLD, recordHistory = !isIncognito)
                }
            }
        }
        super.onCleared()
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 500L
        private const val READ_THRESHOLD = 0.95f
        private const val MIN_FONT_SIZE = 10f
        private const val MAX_FONT_SIZE = 40f
        private const val MIN_LINE_HEIGHT = 1.0f
        private const val MAX_LINE_HEIGHT = 2.5f
    }
}
