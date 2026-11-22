package app.sorareader.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.sorareader.MainActivity
import app.sorareader.R
import app.sorareader.SoraReaderApp
import app.sorareader.domain.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Downloads a single chapter. Long-running downloads are promoted to a WorkManager
 * foreground service (type `dataSync`) so they are not killed while the app is backgrounded.
 *
 * If POST_NOTIFICATIONS is not granted (Android 13+), the work still runs — just without
 * the foreground notification, mirroring the legacy service's graceful degradation.
 */
class DownloadChapterWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        val novelId = inputData.getLong(KEY_NOVEL_ID, -1L)
        if (chapterId <= 0 || novelId <= 0) {
            return Result.failure(errorData("Invalid chapter or novel id"))
        }

        val novelTitle = inputData.getString(KEY_NOVEL_TITLE).orEmpty().ifBlank { "Novel" }
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE).orEmpty().ifBlank { "Chapter $chapterId" }

        val dm = SimpleDownloadManager.instance
        dm?.mark(taskId, DownloadStatus.RUNNING)

        // Promote to a foreground service up front. Permission failures and FGS start
        // restrictions must never prevent the download itself from running.
        if (canPostNotifications()) {
            runCatching { setForeground(createForegroundInfo(novelTitle, chapterTitle, 0f)) }
        }
        reportProgress(novelTitle, chapterTitle, 0.05f)

        return try {
            val app = applicationContext as? SoraReaderApp
                ?: throw IllegalStateException("Application container unavailable")
            val repo = app.container.novelRepository
            val chapter = repo.getChapterById(chapterId) ?: throw IllegalStateException("Chapter missing")
            val novel = repo.getNovelById(novelId) ?: throw IllegalStateException("Novel missing")

            val content = app.container.sourceCatalog.fetchChapterContent(novel.sourceId, chapter.url)
            reportProgress(novelTitle, chapterTitle, 0.6f)

            val dir = File(applicationContext.filesDir, "downloads/$novelId").also { it.mkdirs() }
            val file = File(dir, "$chapterId.txt")
            file.writeText(content.paragraphs.joinToString("\n\n"))
            repo.markChapterDownloaded(chapterId, true, file.absolutePath)

            reportProgress(novelTitle, chapterTitle, 1f)
            dm?.mark(taskId, DownloadStatus.COMPLETED)
            Result.success()
        } catch (e: CancellationException) {
            // User cancelled (or OS stopped the worker) — do not retry.
            dm?.mark(taskId, DownloadStatus.CANCELLED)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "Unknown error"
            if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                // Transient failure: WorkManager re-enqueues with exponential backoff.
                dm?.mark(taskId, DownloadStatus.QUEUED, message)
                Result.retry()
            } else {
                dm?.mark(taskId, DownloadStatus.FAILED, message)
                Result.failure(errorData(message))
            }
        }
    }

    /** Required by WorkManager when setForeground()/expedited work asks for a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE).orEmpty().ifBlank { "Novel" }
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE).orEmpty().ifBlank { "Chapter" }
        return createForegroundInfo(novelTitle, chapterTitle, 0f)
    }

    private suspend fun reportProgress(novelTitle: String, chapterTitle: String, progress: Float) {
        // Persist in WorkInfo so SimpleDownloadManager can mirror it into the queue UI.
        setProgress(Data.Builder().putFloat(KEY_PROGRESS, progress).build())
        if (canPostNotifications()) {
            runCatching { setForeground(createForegroundInfo(novelTitle, chapterTitle, progress)) }
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createForegroundInfo(novelTitle: String, chapterTitle: String, progress: Float): ForegroundInfo {
        createChannel()

        val openApp = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title, novelTitle))
            .setContentText(chapterTitle)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .build()

        // Android 14+ requires the type both in the manifest and at startForeground time.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.download_channel_description)
            },
        )
    }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_ERROR, message).build()

    companion object {
        const val TAG = "chapter-download"
        const val KEY_TASK_ID = "taskId"
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_NOVEL_TITLE = "novelTitle"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        const val CHANNEL_ID = "chapter_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ATTEMPTS = 3
    }
}
