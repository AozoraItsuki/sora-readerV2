package app.sorareader.ui.screens.localbooks

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sorareader.domain.local.EpubExportResult
import app.sorareader.domain.local.EpubImportResult
import app.sorareader.domain.local.LocalBookManager
import app.sorareader.domain.model.Novel
import app.sorareader.domain.repository.NovelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalBooksUiState(
    val novels: List<Novel> = emptyList(),
    val isWorking: Boolean = false,
    val status: String = "",
    val message: String? = null,
    val error: String? = null,
    val pendingExport: Novel? = null,
)

class LocalBooksViewModel(
    private val repo: NovelRepository,
    private val localBookManager: LocalBookManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocalBooksUiState())
    val uiState: StateFlow<LocalBooksUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repo.observeLibrary().collectLatest { novels -> _uiState.update { it.copy(novels = novels) } } }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        _uiState.update {
            it.copy(isWorking = true, status = "Importing EPUB…", message = null, error = null, pendingExport = null)
        }
        when (val result = localBookManager.importEpub(uri)) {
            is EpubImportResult.Success -> _uiState.update {
                it.copy(
                    isWorking = false,
                    status = "Import complete",
                    message = "Imported \"${result.title}\" (${result.chapterCount} chapters)",
                )
            }
            is EpubImportResult.Failure -> _uiState.update {
                it.copy(isWorking = false, status = "Import failed", error = result.message)
            }
        }
    }

    fun requestExport(novel: Novel) {
        _uiState.update { it.copy(pendingExport = novel, message = null, error = null) }
    }

    fun exportTo(uri: Uri) {
        val novel = _uiState.value.pendingExport ?: return
        _uiState.update { it.copy(pendingExport = null, isWorking = true, status = "Exporting EPUB…", message = null, error = null) }
        viewModelScope.launch {
            when (val result = localBookManager.exportEpub(novel.id, uri)) {
                is EpubExportResult.Success -> _uiState.update {
                    it.copy(isWorking = false, status = "Export complete", message = result.summary)
                }
                is EpubExportResult.Failure -> _uiState.update {
                    it.copy(isWorking = false, status = "Export failed", error = result.message)
                }
            }
        }
    }
}
