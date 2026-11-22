package app.sorareader.domain.backup

import android.net.Uri
import app.sorareader.domain.model.BackupOptions

/**
 * Exports the whole library to a versioned JSON document and restores it.
 * Implemented in the data layer and provided through [app.sorareader.di.AppContainer].
 */
interface BackupManager {
    suspend fun exportToUri(uri: Uri, options: BackupOptions = BackupOptions()): BackupResult
    suspend fun importFromUri(uri: Uri): BackupResult

    /** Serialize the current data to JSON text (useful for custom destinations / tests). */
    suspend fun exportToJson(options: BackupOptions = BackupOptions()): String

    /** Restore data from JSON text. */
    suspend fun importFromJson(json: String): BackupResult
}

sealed interface BackupResult {
    data class Success(val summary: String) : BackupResult
    data class Failure(val message: String) : BackupResult
}
