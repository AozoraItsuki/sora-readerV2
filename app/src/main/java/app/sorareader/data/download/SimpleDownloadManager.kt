package app.sorareader.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.sorareader.data.settings.AppSettingsStore
import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.DownloadStatus
import app.sorareader.domain.model.DownloadTask
import app.sorareader.domain.model.Novel
import app.sorareader.domain.repository.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class SimpleDownloadManager(
    private val context: Context,
    private val settings: AppSettingsStore,
) : DownloadManager {
    private val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val queueFile = File(context.applicationContext.filesDir, QUEUE_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val persistLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        loadQueue()
        // WorkManager keeps its own queue. Observing its WorkInfo flow both reconciles
        // persisted statuses after a process restart and keeps progress live while running.
        // Grab the instance on the main thread, then collect on IO.
        val workManager = WorkManager.getInstance(context)
        scope.launch { observeWorkManager(workManager) }
    }

    override fun observeQueue(): Flow<List<DownloadTask>> = tasks.asStateFlow()

    override suspend fun enqueue(novel: Novel, chapters: List<Chapter>) {
        // Read the current preference at enqueue time so WorkManager waits for an
        // unmetered (Wi-Fi) connection when the user requested Wi-Fi-only downloads.
        val wifiOnly = runCatching { settings.preferences.first().wifiOnlyDownloads }.getOrDefault(true)
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val newTasks = chapters.map { chapter ->
            DownloadTask(
                id = UUID.randomUUID().toString(),
                chapterId = chapter.id,
                novelId = novel.id,
                novelTitle = novel.title,
                chapterTitle = chapter.title,
                status = DownloadStatus.QUEUED,
            )
        }
        // Persist first so the queue survives a crash between persisting and enqueueing.
        updateTasks { it + newTasks }

        val wm = WorkManager.getInstance(context)
        newTasks.forEach { task ->
            val req = OneTimeWorkRequestBuilder<DownloadChapterWorker>()
                .addTag(DownloadChapterWorker.TAG)
                .addTag("$TAG_TASK_PREFIX${task.id}")
                .setInputData(
                    Data.Builder()
                        .putLong(DownloadChapterWorker.KEY_CHAPTER_ID, task.chapterId)
                        .putLong(DownloadChapterWorker.KEY_NOVEL_ID, task.novelId)
                        .putString(DownloadChapterWorker.KEY_TASK_ID, task.id)
                        .putString(DownloadChapterWorker.KEY_NOVEL_TITLE, task.novelTitle)
                        .putString(DownloadChapterWorker.KEY_CHAPTER_TITLE, task.chapterTitle)
                        .build()
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                // Worker returns Result.retry() on transient errors; back off exponentially.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_MS, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniqueWork("download-${task.chapterId}", ExistingWorkPolicy.KEEP, req)
        }
    }

    override suspend fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("$TAG_TASK_PREFIX$taskId")
        updateTasks { list -> list.map { if (it.id == taskId) it.copy(status = DownloadStatus.CANCELLED) else it } }
    }

    override suspend fun cancelAll() {
        // Cancels queued and currently running WorkManager jobs, not just the local list.
        WorkManager.getInstance(context).cancelAllWorkByTag(DownloadChapterWorker.TAG)
        updateTasks { list ->
            list.map { task ->
                if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.RUNNING) {
                    task.copy(status = DownloadStatus.CANCELLED)
                } else {
                    task
                }
            }
        }
    }

    internal fun mark(taskId: String, status: DownloadStatus, error: String? = null) {
        updateTasks { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = status,
                        error = error,
                        progress = if (status == DownloadStatus.COMPLETED) 1f else task.progress,
                    )
                } else {
                    task
                }
            }
        }
    }

    private fun updateTasks(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        synchronized(persistLock) {
            val updated = transform(tasks.value)
            tasks.value = updated
            runCatching { queueFile.writeText(json.encodeToString(updated)) }
        }
    }

    private fun loadQueue() {
        runCatching {
            if (queueFile.exists()) {
                tasks.value = json.decodeFromString<List<DownloadTask>>(queueFile.readText())
            }
        }
    }

    private suspend fun observeWorkManager(workManager: WorkManager) = withContext(Dispatchers.IO) {
        workManager.getWorkInfosByTagFlow(DownloadChapterWorker.TAG).collect { infos ->
            val byTask = infos.mapNotNull { info ->
                val taskId = info.tags.firstOrNull { it.startsWith(TAG_TASK_PREFIX) }
                    ?.removePrefix(TAG_TASK_PREFIX)
                taskId?.let { it to info }
            }.toMap()

            updateTasks { list ->
                list.map { task ->
                    val info = byTask[task.id] ?: return@map task
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> task.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 1f,
                            error = null,
                        )
                        WorkInfo.State.FAILED -> task.copy(
                            status = DownloadStatus.FAILED,
                            error = info.outputData.getString(DownloadChapterWorker.KEY_ERROR) ?: task.error,
                        )
                        WorkInfo.State.CANCELLED -> task.copy(status = DownloadStatus.CANCELLED, error = null)
                        WorkInfo.State.RUNNING -> task.copy(
                            status = DownloadStatus.RUNNING,
                            progress = maxOf(
                                task.progress,
                                info.progress.getFloat(DownloadChapterWorker.KEY_PROGRESS, 0f),
                            ),
                        )
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> task.copy(status = DownloadStatus.QUEUED)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG_TASK_PREFIX = "download-task-"
        private const val QUEUE_FILE_NAME = "download_queue.json"
        private const val INITIAL_BACKOFF_MS = 10_000L

        @Volatile
        var instance: SimpleDownloadManager? = null
    }
}
