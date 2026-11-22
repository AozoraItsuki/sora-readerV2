package app.sorareader.ui.screens.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.ui.AppViewModelProvider

/** Named JS bridge. We control the loaded HTML, so exposing it is safe. */
@SuppressLint("JavascriptInterface")
private class ReaderJavaScriptBridge(
    private val progressListener: (Float) -> Unit,
    private val saveListener: () -> Unit,
) {
    @JavascriptInterface
    fun onProgress(progress: Float) {
        progressListener(progress)
    }

    @JavascriptInterface
    fun onSave() {
        saveListener()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    novelId: Long,
    chapterId: Long,
    onBack: () -> Unit,
    onOpenChapter: (novelId: Long, chapterId: Long) -> Unit,
    vm: ReaderViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    LaunchedEffect(novelId, chapterId) { vm.load(novelId, chapterId) }
    val state by vm.state.collectAsStateWithLifecycle()

    // Keep-awake + immersive fullscreen, driven by reader settings.
    val view = LocalView.current
    val keepScreenOn = state.keepScreenOn
    val fullscreen = state.fullscreen
    DisposableEffect(view, keepScreenOn, fullscreen) {
        val window = if (!view.isInEditMode) (view.context as? Activity)?.window else null
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
        }
    }

    // Final progress flush when the reader leaves the composition.
    DisposableEffect(Unit) { onDispose { vm.saveProgress() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.chapter?.title ?: "Reader", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = { state.prevChapterId?.let { onOpenChapter(novelId, it) } },
                    enabled = state.prevChapterId != null,
                ) {
                    Icon(Icons.Default.ChevronLeft, "Previous chapter")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.adjustFontSize(-1f) }) { Text("A-", style = MaterialTheme.typography.titleSmall) }
                    IconButton(onClick = { vm.adjustFontSize(1f) }) { Text("A+", style = MaterialTheme.typography.titleSmall) }
                    IconButton(onClick = { vm.adjustLineHeight(-0.1f) }) { Text("L-", style = MaterialTheme.typography.titleSmall) }
                    IconButton(onClick = { vm.adjustLineHeight(0.1f) }) { Text("L+", style = MaterialTheme.typography.titleSmall) }
                }
                IconButton(
                    onClick = { state.nextChapterId?.let { onOpenChapter(novelId, it) } },
                    enabled = state.nextChapterId != null,
                ) {
                    Icon(Icons.Default.ChevronRight, "Next chapter")
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val content = state.content
            if (content != null) {
                val html = remember(content, state.fontSize, state.lineHeight, state.padding) {
                    buildReaderHtml(
                        title = content.title,
                        paragraphs = content.paragraphs,
                        rawHtml = content.html,
                        fontSize = state.fontSize,
                        lineHeight = state.lineHeight,
                        padding = state.padding,
                    )
                }
                // Avoid reloading (and resetting scroll) on unrelated recompositions such as progress updates.
                val lastHtml = remember { mutableStateOf<String?>(null) }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = WebViewClient()
                            addJavascriptInterface(
                                ReaderJavaScriptBridge(
                                    progressListener = { vm.onProgress(it) },
                                    saveListener = { vm.saveProgress() },
                                ),
                                "AndroidBridge",
                            )
                        }
                    },
                    update = { webView ->
                        if (lastHtml.value != html) {
                            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                            lastHtml.value = html
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun buildReaderHtml(
    title: String,
    paragraphs: List<String>,
    rawHtml: String?,
    fontSize: Float,
    lineHeight: Float,
    padding: Int,
): String {
    val body = rawHtml ?: paragraphs.joinToString("") { "<p>${escapeHtml(it)}</p>" }
    return """
    <html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'/>
    <style>
      html,body{margin:0;}
      body{padding:${padding}px;font-size:${fontSize}px;line-height:$lineHeight;background:#121212;color:#e6e6e6;font-family:sans-serif;}
      h1{font-size:${(fontSize * 1.4).toInt()}px;line-height:1.3;} p{margin:0 0 1em 0;} img{max-width:100%;height:auto;}
    </style></head><body><h1>${escapeHtml(title)}</h1>$body
    <script>
    (function () {
      function report() {
        var doc = document.documentElement;
        var scrollable = doc.scrollHeight - window.innerHeight;
        var y = window.pageYOffset || doc.scrollTop || document.body.scrollTop || 0;
        var p = scrollable > 0 ? y / scrollable : 0;
        p = Math.max(0, Math.min(1, p));
        if (window.AndroidBridge && window.AndroidBridge.onProgress) {
          window.AndroidBridge.onProgress(p);
        }
      }
      window.addEventListener('scroll', report, { passive: true });
      window.addEventListener('resize', report);
      window.addEventListener('pagehide', function () {
        if (window.AndroidBridge && window.AndroidBridge.onSave) window.AndroidBridge.onSave();
      });
    })();
    </script>
    </body></html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
