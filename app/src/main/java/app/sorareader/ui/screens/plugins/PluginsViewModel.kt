package app.sorareader.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sorareader.data.plugin.InstalledPlugin
import app.sorareader.data.plugin.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PluginsUiState(
    val plugins: List<InstalledPlugin> = emptyList(),
    val installing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class PluginsViewModel(private val pluginManager: PluginManager) : ViewModel() {
    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { pluginManager.listInstalled() }
                .onSuccess { _state.value = _state.value.copy(plugins = it, error = null) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun installFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(installing = true, error = null, message = null)
            runCatching { pluginManager.installFromUrl(trimmed) }
                .onSuccess {
                    _state.value = _state.value.copy(installing = false, message = "Installed ${it.name}")
                    refresh()
                }
                .onFailure { _state.value = _state.value.copy(installing = false, error = it.message ?: "Install failed") }
        }
    }

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            runCatching { pluginManager.uninstall(pluginId) }
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null) }
}
