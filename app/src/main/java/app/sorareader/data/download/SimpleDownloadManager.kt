package app.sorareader.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class SimpleDownloadManager(private val context: Context) : DownloadManager {
    private val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val queueFile = File(context.applicationContext.filesDir, QUEUE_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val persistLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        loadQueue()
        // WorkManager keeps its own queue; align persisted statuses with it after a process restart.
        // Grab the instance on the main thread, then do the blocking read on IO.
        val workManager = WorkManager.getInstance(context)
        scope.launch { reconcileWithWorkManager(workManager) }
    }

    override fun observeQueue(): Flow<List<DownloadTask>> = tasks.asStateFlow()

    override suspend fun enqueue(novel: Novel, chapters: List<Chapter>) {
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
        newTasks.forEachIndexed { index, task ->
            val req = OneTimeWorkRequestBuilder<DownloadChapterWorker>()
                .addTag(TAG_CHAPTER_DOWNLOAD)
                .addTag("$TAG_TASK_PREFIX${task.id}")
                .setInputData(
                    Data.Builder()
                        .putLong("chapterId", task.chapterId)
                        .putLong("novelId", task.novelId)
                        .putString("taskId", task.id)
                        .build()
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork("download-${chapters[index].id}", ExistingWorkPolicy.KEEP, req)
        }
    }

    override suspend fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("$TAG_TASK_PREFIX$taskId")
        updateTasks { list -> list.map { if (it.id == taskId) it.copy(status = DownloadStatus.CANCELLED) else it } }
    }

    override suspend fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_CHAPTER_DOWNLOAD)
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

    private suspend fun reconcileWithWorkManager(workManager: WorkManager) = withContext(Dispatchers.IO) {
        val infos = runCatching {
            workManager.getWorkInfosByTag(TAG_CHAPTER_DOWNLOAD).get()
        }.getOrNull() ?: return@withContext

        val stateByTask = infos.associate { info ->
            val taskId = info.tags.firstOrNull { it.startsWith(TAG_TASK_PREFIX) }?.removePrefix(TAG_TASK_PREFIX)
            taskId to info.state
        }

        updateTasks { list ->
            list.map { task ->
                if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.RUNNING) {
                    when (stateByTask[task.id]) {
                        WorkInfo.State.CANCELLED -> task.copy(status = DownloadStatus.CANCELLED)
                        WorkInfo.State.SUCCEEDED -> task.copy(status = DownloadStatus.COMPLETED, progress = 1f)
                        WorkInfo.State.FAILED -> task.copy(status = DownloadStatus.FAILED)
                        else -> task
                    }
                } else {
                    task
                }
            }
        }
    }

    companion object {
        private const val TAG_CHAPTER_DOWNLOAD = "chapter-download"
        private const val TAG_TASK_PREFIX = "download-task-"
        private const val QUEUE_FILE_NAME = "download_queue.json"

        @Volatile
        var instance: SimpleDownloadManager? = null
    }
}
