package app.sorareader.ui.screens.localbooks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.domain.model.Novel
import app.sorareader.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBooksScreen(
    onBack: () -> Unit,
    vm: LocalBooksViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) vm.importFrom(uri) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri: Uri? -> if (uri != null) vm.exportTo(uri) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("EPUB Import / Export") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Import EPUB files into your library, or export any novel as a standalone EPUB. " +
                    "Imported books are stored locally and can be read offline.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = { importLauncher.launch(arrayOf("application/epub+zip", "application/zip", "*/*")) },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileUpload, null)
                Text("  Import EPUB")
            }

            if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.titleMedium)
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Text("Export a novel", style = MaterialTheme.typography.titleMedium)
            if (state.novels.isEmpty()) {
                Text("No novels in your library yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.novels, key = { it.id }) { novel ->
                        ListItem(
                            headlineContent = { Text(novel.title, maxLines = 1) },
                            supportingContent = { Text("${novel.chapterCountText} • ${novel.sourceId}") },
                            trailingContent = {
                                OutlinedButton(
                                    onClick = {
                                        vm.requestExport(novel)
                                        exportLauncher.launch("${safeFileName(novel.title)}.epub")
                                    },
                                    enabled = !state.isWorking,
                                ) {
                                    Icon(Icons.Default.FileDownload, null)
                                    Text("  EPUB")
                                }
                            },
                            modifier = Modifier.clickable(enabled = false) {},
                        )
                    }
                }
            }
        }
    }
}

private val Novel.chapterCountText: String get() = "${totalChapters} chapters"

private fun safeFileName(title: String): String {
    val cleaned = title.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    return cleaned.ifBlank { "novel" }.take(80)
}
