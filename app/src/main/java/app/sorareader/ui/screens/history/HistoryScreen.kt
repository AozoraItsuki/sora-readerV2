package app.sorareader.ui.screens.history

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.ui.AppViewModelProvider
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenChapter: (Long, Long) -> Unit, vm: HistoryViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val history by vm.history.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        LargeTopAppBar(title = { Text("History") }, actions = {
            if (history.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Default.DeleteSweep, "Clear history") }
        })
    }) { inner ->
        if (history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(inner).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No reading history", style = MaterialTheme.typography.headlineSmall)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history, key = { it.chapterId }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverImage(item.novelCover, item.novelTitle, Modifier.width(48.dp).height(68.dp))
                            Column(
                                Modifier.weight(1f).clickable { onOpenChapter(item.novelId, item.chapterId) },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(item.novelTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(item.chapterTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text(DateFormat.getDateTimeInstance().format(Date(item.readAt)), style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { vm.delete(item.chapterId) }) { Icon(Icons.Default.Delete, "Delete history entry") }
                        }
                    }
                }
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
