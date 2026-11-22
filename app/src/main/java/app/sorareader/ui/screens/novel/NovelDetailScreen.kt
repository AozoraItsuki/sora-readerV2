package app.sorareader.ui.screens.novel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.ui.AppViewModelProvider
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(novelId: Long, onBack: () -> Unit, onOpenChapter: (Long) -> Unit, vm: NovelDetailViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    LaunchedEffect(novelId) { vm.load(novelId) }
    LaunchedEffect(onOpenChapter) { vm.continueChapter.collect { onOpenChapter(it) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val novel = state.novel
    var moreExpanded by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri: Uri? -> if (uri != null) vm.exportEpub(uri) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(novel?.title ?: "Novel", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { vm.refresh(novelId) }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = vm::downloadUnread) { Icon(Icons.Default.Download, "Download unread") }
                IconButton(onClick = vm::openCategories) { Icon(Icons.Default.Category, "Categories") }
                if (novel != null) IconButton(onClick = vm::toggleLibrary) { Icon(if (novel.inLibrary) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Library") }
                Box {
                    IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        DropdownMenuItem(text = { Text("Mark all read") }, onClick = { vm.markAllRead(true); moreExpanded = false })
                        DropdownMenuItem(text = { Text("Mark all unread") }, onClick = { vm.markAllUnread(); moreExpanded = false })
                        DropdownMenuItem(
                            text = { Text("Export as EPUB") },
                            enabled = novel != null,
                            onClick = {
                                moreExpanded = false
                                novel?.let { exportLauncher.launch(safeEpubName(it.title)) }
                            },
                        )
                    }
                }
            },
        )
    }) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.primary) } }
            if (novel != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CoverImage(novel.coverUrl, novel.title, Modifier.width(96.dp).height(144.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(novel.title, style = MaterialTheme.typography.titleLarge)
                            if (novel.author.isNotBlank()) {
                                Text(novel.author, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = {}, label = { Text(novel.status.name.lowercase().replace('_', ' ')) })
                                AssistChip(onClick = {}, label = { Text("${state.chapters.size} chapters") })
                            }
                            Button(onClick = vm::continueReading, enabled = state.chapters.isNotEmpty()) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text(" Continue Reading", Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
                item {
                    if (novel.description.isNotBlank()) Text(novel.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.chapters, key = { if (it.id != 0L) it.id else it.url.hashCode().toLong() }) { chapter ->
                Card(Modifier.fillMaxWidth().clickable(enabled = chapter.id != 0L) { onOpenChapter(chapter.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chapter.title, style = MaterialTheme.typography.bodyLarge)
                            val readStatus = when {
                                chapter.isRead -> "Read"
                                chapter.readProgress > 0f -> "${(chapter.readProgress * 100).toInt()}%"
                                else -> "Unread"
                            }
                            val status = buildList {
                                add(readStatus)
                                if (chapter.isDownloaded) add("Downloaded")
                                if (chapter.isBookmarked) add("Bookmarked")
                            }.joinToString(" • ")
                            Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (chapter.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { vm.toggleBookmark(chapter.id, !chapter.isBookmarked) }, enabled = chapter.id != 0L) {
                            Icon(
                                if (chapter.isBookmarked) Icons.Filled.Bookmark else Icons.Default.BookmarkBorder,
                                if (chapter.isBookmarked) "Remove bookmark" else "Bookmark",
                            )
                        }
                        IconButton(onClick = { vm.downloadChapter(chapter.id) }, enabled = chapter.id != 0L && !chapter.isDownloaded) {
                            Icon(
                                Icons.Default.Download,
                                if (chapter.isDownloaded) "Downloaded" else "Download chapter",
                                tint = if (chapter.isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (state.showCategoryDialog) {
        CategoryPickerDialog(categories = state.categories, selected = state.selectedCategoryIds, onToggle = vm::toggleCategory, onDismiss = vm::dismissCategories, onConfirm = vm::saveCategories)
    }
}

private fun safeEpubName(title: String): String {
    val cleaned = title.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    return cleaned.ifBlank { "novel" }.take(80) + ".epub"
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
