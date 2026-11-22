package app.sorareader.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.domain.model.Novel
import app.sorareader.ui.AppViewModelProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpenNovel: (Long) -> Unit, vm: LibraryViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val novels by vm.novels.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val sort by vm.currentSort.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun remove(novel: Novel) {
        vm.removeFromLibrary(novel.id)
        scope.launch {
            val result = snackbar.showSnackbar("Removed \"${novel.title}\" from library", "Undo")
            if (result == SnackbarResult.ActionPerformed) vm.restoreToLibrary(novel.id)
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Library") },
                actions = {
                    Box {
                        IconButton(onClick = { sortExpanded = true }) { Icon(Icons.Default.Sort, "Sort") }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            LibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = { vm.setSort(option); sortExpanded = false },
                                    trailingIcon = { if (sort == option) Icon(Icons.Default.Check, null) },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            DockedSearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { vm.query.value = it },
                        onSearch = { expanded = false },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text("Search library") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                novels.take(5).forEach { n ->
                    ListItem(headlineContent = { Text(n.title) }, modifier = Modifier.clickable { expanded = false; onOpenNovel(n.id) })
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { vm.setCategoryFilter(null) },
                        label = { Text("All") },
                    )
                }
                items(categories, key = { it.id }) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat.id,
                        onClick = { vm.setCategoryFilter(if (selectedCategory == cat.id) null else cat.id) },
                        label = { Text(cat.name) },
                    )
                }
            }
            if (novels.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        if (query.isBlank()) "Your library is empty" else "No results",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Browse sources and add a novel to start reading.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(novels, key = { it.id }) { novel ->
                        NovelCard(novel, onClick = { onOpenNovel(novel.id) }, onRemove = { remove(novel) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelCard(novel: Novel, onClick: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onRemove)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoverImage(novel.coverUrl, novel.title, Modifier.width(56.dp).height(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(novel.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (novel.author.isNotBlank()) {
                    Text(novel.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    "${novel.chaptersUnread} unread • ${novel.status.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CoverImage(url: String?, title: String, modifier: Modifier = Modifier) {
    val clipped = modifier.clip(RoundedCornerShape(8.dp))
    if (url.isNullOrBlank()) {
        Surface(clipped, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = "Cover of $title",
            modifier = clipped,
            contentScale = ContentScale.Crop,
        )
    }
}
