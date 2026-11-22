package app.sorareader.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sorareader.domain.backup.BackupManager
import app.sorareader.domain.backup.BackupResult
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val isWorking: Boolean = false,
    val status: String = "",
    val message: String? = null,
    val error: String? = null,
)

class BackupViewModel(private val backupManager: BackupManager) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun exportTo(uri: Uri) = viewModelScope.launch {
        _uiState.update { BackupUiState(isWorking = true, status = "Exporting backup…") }
        when (val result = backupManager.exportToUri(uri)) {
            is BackupResult.Success -> _uiState.update {
                BackupUiState(status = "Export complete", message = result.summary)
            }
            is BackupResult.Failure -> _uiState.update {
                BackupUiState(status = "Export failed", error = result.message)
            }
        }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        _uiState.update { BackupUiState(isWorking = true, status = "Importing backup…") }
        when (val result = backupManager.importFromUri(uri)) {
            is BackupResult.Success -> _uiState.update {
                BackupUiState(status = "Import complete", message = result.summary)
            }
            is BackupResult.Failure -> _uiState.update {
                BackupUiState(status = "Import failed", error = result.message)
            }
        }
    }

    fun clearStatus() = _uiState.update { BackupUiState() }
}
