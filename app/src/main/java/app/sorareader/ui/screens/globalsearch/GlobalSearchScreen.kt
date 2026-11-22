package app.sorareader.ui.screens.globalsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onOpenNovel: (Long) -> Unit,
    onBack: () -> Unit,
    vm: GlobalSearchViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Global search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search all sources") },
                    singleLine = true,
                )
                if (state.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Sources", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.sources.forEach { source ->
                            FilterChip(
                                selected = source.id in state.selectedSourceIds,
                                onClick = { vm.toggleSource(source.id) },
                                label = { Text(source.name) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = vm::search,
                    enabled = !state.searching && state.selectedSourceIds.isNotEmpty(),
                ) { Text(if (state.searching) "Searching..." else "Search") }
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.selectedSourceIds.isEmpty()) {
                    Text("Select at least one source", color = MaterialTheme.colorScheme.error)
                }
            }

            state.sections.forEach { section ->
                item {
                    Text(
                        "${section.source.name} (${section.results.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (section.loading) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                section.error?.let { error ->
                    item {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(section.results, key = { section.source.id + it.url }) { novel ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.addToLibrary(novel, onOpenNovel) },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(novel.title, style = MaterialTheme.typography.titleMedium)
                            if (novel.description.isNotBlank()) {
                                Text(novel.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            }
                            Text(
                                "Tap to add to library and open",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
