package app.sorareader.ui.screens.globalsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sorareader.domain.model.Novel
import app.sorareader.domain.model.SourceDescriptor
import app.sorareader.domain.repository.NovelRepository
import app.sorareader.domain.repository.SourceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Results (or failure) for a single source. */
data class GlobalSourceSection(
    val source: SourceDescriptor,
    val results: List<Novel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class GlobalSearchUiState(
    val sources: List<SourceDescriptor> = emptyList(),
    /** Empty means "no sources selected"; by default every source is selected. */
    val selectedSourceIds: Set<String> = emptySet(),
    val query: String = "",
    val searching: Boolean = false,
    val sections: List<GlobalSourceSection> = emptyList(),
)

class GlobalSearchViewModel(
    private val novelRepo: NovelRepository,
    private val catalog: SourceCatalog,
) : ViewModel() {
    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    init { refreshSources() }

    fun refreshSources() {
        viewModelScope.launch {
            val sources = runCatching { catalog.availableSourcesAsync() }.getOrElse { catalog.availableSources() }
            _state.value = _state.value.copy(
                sources = sources,
                selectedSourceIds = sources.map { it.id }.toSet(),
                sections = sources.map { GlobalSourceSection(it) },
            )
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun toggleSource(sourceId: String) {
        val current = _state.value.selectedSourceIds
        val updated = if (sourceId in current) current - sourceId else current + sourceId
        _state.value = _state.value.copy(selectedSourceIds = updated)
    }

    /**
     * Queries every selected source in parallel. Each source is isolated with runCatching so a
     * single failing/unreachable source only marks its own section as errored.
     */
    fun search() {
        val snapshot = _state.value
        val targets = snapshot.sources.filter { it.id in snapshot.selectedSourceIds }
        if (targets.isEmpty()) return
        val query = snapshot.query

        viewModelScope.launch {
            _state.value = _state.value.copy(
                searching = true,
                sections = targets.map { GlobalSourceSection(it, loading = true) },
            )

            val outcomes = targets.map { source ->
                async(Dispatchers.IO) {
                    source to runCatching { catalog.browse(source.id, query) }
                }
            }.awaitAll()

            _state.value = _state.value.copy(
                searching = false,
                sections = outcomes.map { (source, result) ->
                    GlobalSourceSection(
                        source = source,
                        results = result.getOrDefault(emptyList()),
                        error = result.exceptionOrNull()?.message ?: "Search failed",
                    )
                },
            )
        }
    }

    fun addToLibrary(novel: Novel, onAdded: (Long) -> Unit) {
        viewModelScope.launch {
            val id = novelRepo.upsertNovel(novel.copy(inLibrary = true, lastUpdatedAt = System.currentTimeMillis()))
            onAdded(id)
        }
    }
}
