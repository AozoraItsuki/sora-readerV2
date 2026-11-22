package app.sorareader.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sorareader.domain.model.Category
import app.sorareader.domain.model.Novel
import app.sorareader.domain.repository.NovelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibrarySort(val label: String) {
    LAST_READ("Last Read"),
    LAST_UPDATED("Last Updated"),
    TITLE("Title"),
    UNREAD("Unread"),
}

class LibraryViewModel(private val repo: NovelRepository) : ViewModel() {
    val query = MutableStateFlow("")
    private val categoryFilter = MutableStateFlow<Long?>(null)
    private val sort = MutableStateFlow(LibrarySort.LAST_READ)

    val categories: StateFlow<List<Category>> = repo.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCategory: StateFlow<Long?> = categoryFilter
    val currentSort: StateFlow<LibrarySort> = sort

    val novels: StateFlow<List<Novel>> = combine(
        query.flatMapLatest { repo.observeLibrary(it) },
        repo.observeNovelCategories(),
        categoryFilter,
        sort,
    ) { list, links, categoryId, sortBy ->
        val filtered = if (categoryId == null) {
            list
        } else {
            val ids = links.filter { it.categoryId == categoryId }.map { it.novelId }.toSet()
            list.filter { it.id in ids }
        }
        when (sortBy) {
            LibrarySort.LAST_READ -> filtered.sortedByDescending { it.lastReadAt ?: it.lastUpdatedAt ?: 0L }
            LibrarySort.LAST_UPDATED -> filtered.sortedByDescending { it.lastUpdatedAt ?: 0L }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.trim().lowercase() }
            LibrarySort.UNREAD -> filtered.sortedByDescending { it.chaptersUnread }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCategoryFilter(id: Long?) { categoryFilter.value = id }
    fun setSort(value: LibrarySort) { sort.value = value }

    fun removeFromLibrary(novelId: Long) = viewModelScope.launch { repo.setInLibrary(novelId, false) }
    fun restoreToLibrary(novelId: Long) = viewModelScope.launch { repo.setInLibrary(novelId, true) }
}
