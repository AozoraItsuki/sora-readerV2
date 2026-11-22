package app.sorareader.domain.local

import android.net.Uri

/**
 * Imports EPUB documents from SAF URIs into the local library and exports
 * novels back to EPUB documents at SAF CreateDocument URIs.
 */
interface LocalBookManager {
    suspend fun importEpub(uri: Uri): EpubImportResult
    suspend fun exportEpub(novelId: Long, uri: Uri): EpubExportResult
}

sealed interface EpubImportResult {
    data class Success(val novelId: Long, val title: String, val chapterCount: Int) : EpubImportResult
    data class Failure(val message: String) : EpubImportResult
}

sealed interface EpubExportResult {
    data class Success(val summary: String) : EpubExportResult
    data class Failure(val message: String) : EpubExportResult
}
