package app.sorareader.ui.screens.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.data.local.LocalBookFiles
import app.sorareader.data.settings.ThemeMode
import app.sorareader.ui.AppViewModelProvider
import org.jsoup.Jsoup
import java.util.Locale

/** Callbacks the embedded reader page can reach through the `AndroidReader` bridge. */
private data class ReaderBridgeCallbacks(
    val onProgress: (Float) -> Unit,
    val onSaveProgress: (Float, Int) -> Unit,
    val onSave: () -> Unit,
    val onScroll: (Float) -> Unit,
    val onPage: (Int, Int) -> Unit,
    val onNextChapter: () -> Unit,
    val onPrevChapter: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onToggleMenu: () -> Unit,
)

/**
 * Named JS bridge. The page is generated locally (no remote scripts), so exposing it is safe.
 * WebView invokes these methods off the main thread; dispatch every callback back to main.
 */
@SuppressLint("JavascriptInterface")
private class ReaderJavaScriptBridge(private val callbacks: ReaderBridgeCallbacks) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun emit(block: () -> Unit) {
        mainHandler.post(block)
    }

    @JavascriptInterface
    fun onProgress(progress: Float) = emit { callbacks.onProgress(progress) }

    @JavascriptInterface
    fun onScroll(progress: Float) = emit { callbacks.onScroll(progress) }

    @JavascriptInterface
    fun onPage(index: Int, total: Int) = emit { callbacks.onPage(index, total) }

    @JavascriptInterface
    fun onSaveProgress(progress: Float, charOffset: Int) = emit {
        callbacks.onSaveProgress(progress, charOffset)
    }

    @JavascriptInterface
    fun onSave() = emit { callbacks.onSave() }

    @JavascriptInterface
    fun onNextChapter() = emit { callbacks.onNextChapter() }

    @JavascriptInterface
    fun onPrevChapter() = emit { callbacks.onPrevChapter() }

    @JavascriptInterface
    fun onToggleBookmark() = emit { callbacks.onToggleBookmark() }

    @JavascriptInterface
    fun onToggleMenu() = emit { callbacks.onToggleMenu() }

    /** Alias kept for pages derived from the React Native reader's `hide` event. */
    @JavascriptInterface
    fun onMenu() = emit { callbacks.onToggleMenu() }
}

private data class ReaderStyle(
    val fontSize: Float,
    val lineHeight: Float,
    val padding: Int,
    val theme: String,
)

/** Stable handle shared by recompositions and WebView callbacks. */
private class ReaderViewController {
    var webView: WebView? = null
    var pageLoaded = false
    var lastHtml: String? = null
    var appliedStyle: ReaderStyle? = null
    var pendingRestore: Float? = null
    var restoreProgress: Float? = null
    var style: ReaderStyle? = null
    var prevChapterId: Long? = null
    var nextChapterId: Long? = null
    var openChapter: ((Long) -> Unit)? = null
    var toggleBookmark: (() -> Unit)? = null
}

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
    val context = LocalContext.current

    val menuVisible = remember { mutableStateOf(false) }
    val displayedProgress = remember { mutableFloatStateOf(0f) }
    val controller = remember { ReaderViewController() }

    val style = ReaderStyle(
        fontSize = state.fontSize,
        lineHeight = state.lineHeight,
        padding = state.padding,
        theme = readerThemeToken(state.readerTheme, context),
    )
    controller.style = style
    controller.restoreProgress = state.chapter?.readProgress?.coerceIn(0f, 1f)
    controller.prevChapterId = state.prevChapterId
    controller.nextChapterId = state.nextChapterId
    controller.openChapter = { id -> onOpenChapter(novelId, id) }
    controller.toggleBookmark = { vm.toggleBookmark() }

    // Style changes are pushed into the live document instead of rebuilding the HTML.
    LaunchedEffect(style) { syncStyle(controller) }

    val menuShown = menuVisible.value

    // Keep-awake + immersive fullscreen, driven by reader settings and menu visibility.
    val view = LocalView.current
    val keepScreenOn = state.keepScreenOn
    val fullscreen = state.fullscreen
    DisposableEffect(view, keepScreenOn, fullscreen, menuShown) {
        val window = if (!view.isInEditMode) (view.context as? Activity)?.window else null
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (fullscreen && !menuShown) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
        }
    }

    // Final progress flush when the reader leaves the composition.
    DisposableEffect(Unit) { onDispose { vm.saveProgress() } }

    val content = state.content
    val html = remember(content) {
        content?.let {
            buildReaderHtml(
                chapterId = it.chapterId,
                title = it.title,
                paragraphs = it.paragraphs,
                rawHtml = it.html,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (html != null) {
            // Content changes rebuild the document; settings changes only call applySettings().
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                controller.pageLoaded = true
                                controller.appliedStyle = null
                                syncStyle(controller)
                                restoreProgress(controller)
                            }
                        }
                        val bridge = ReaderJavaScriptBridge(
                            ReaderBridgeCallbacks(
                                onProgress = { p ->
                                    displayedProgress.value = p
                                    vm.onProgress(p)
                                },
                                onSaveProgress = { p, offset ->
                                    displayedProgress.value = p
                                    vm.onSaveProgress(p, offset)
                                },
                                onSave = { vm.saveProgress() },
                                onScroll = { p -> displayedProgress.value = p },
                                onPage = { index, total ->
                                    if (total > 0) {
                                        displayedProgress.value =
                                            ((index + 1).toFloat() / total).coerceIn(0f, 1f)
                                    }
                                    vm.onPage(index, total)
                                },
                                onNextChapter = {
                                    controller.nextChapterId?.let { controller.openChapter?.invoke(it) }
                                },
                                onPrevChapter = {
                                    controller.prevChapterId?.let { controller.openChapter?.invoke(it) }
                                },
                                onToggleBookmark = { controller.toggleBookmark?.invoke() },
                                onToggleMenu = { menuVisible.value = !menuVisible.value },
                            ),
                        )
                        addJavascriptInterface(bridge, "AndroidReader")
                        // Older generated pages used AndroidBridge; keep them working.
                        addJavascriptInterface(bridge, "AndroidBridge")
                    }
                },
                update = { webView ->
                    controller.webView = webView
                    val document = html ?: return@AndroidView
                    if (controller.lastHtml != document) {
                        controller.lastHtml = document
                        controller.pageLoaded = false
                        controller.appliedStyle = null
                        controller.pendingRestore = controller.restoreProgress
                        displayedProgress.value = controller.restoreProgress ?: 0f
                        webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }

        if (menuShown) {
            Column(Modifier.fillMaxSize()) {
                ReaderTopOverlay(
                    title = state.chapter?.title,
                    bookmarked = state.chapter?.isBookmarked == true,
                    onBack = onBack,
                    onToggleBookmark = { vm.toggleBookmark() },
                    onClose = { menuVisible.value = false },
                )
                // Transparent spacer: taps pass through to the page's center tap zone.
                Spacer(Modifier.weight(1f))
                ReaderBottomOverlay(
                    progress = displayedProgress.value,
                    canPrev = state.prevChapterId != null,
                    canNext = state.nextChapterId != null,
                    themeLabel = readerThemeLabel(state.readerTheme),
                    onSeek = { p ->
                        displayedProgress.value = p
                        controller.webView?.evaluateJavascript(scrollScript(p), null)
                    },
                    onPrev = { state.prevChapterId?.let { onOpenChapter(novelId, it) } },
                    onNext = { state.nextChapterId?.let { onOpenChapter(novelId, it) } },
                    onFontSize = { vm.adjustFontSize(it) },
                    onLineHeight = { vm.adjustLineHeight(it) },
                    onPadding = { vm.adjustPadding(it) },
                    onToggleTheme = { vm.toggleReaderTheme() },
                )
            }
        }
    }
}

@Composable
private fun ReaderTopOverlay(
    title: String?,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { }, // consume taps so they don't reach the page
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                text = title ?: "Reader",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark",
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close menu") }
        }
    }
}

@Composable
private fun ReaderBottomOverlay(
    progress: Float,
    canPrev: Boolean,
    canNext: Boolean,
    themeLabel: String,
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFontSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onPadding: (Int) -> Unit,
    onToggleTheme: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { }, // consume taps so they don't reach the page
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(Icons.Default.ChevronLeft, "Previous chapter")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onFontSize(-1f) }) { Text("A-") }
                    TextButton(onClick = { onFontSize(1f) }) { Text("A+") }
                    TextButton(onClick = { onLineHeight(-0.1f) }) { Text("L-") }
                    TextButton(onClick = { onLineHeight(0.1f) }) { Text("L+") }
                    TextButton(onClick = { onPadding(-4) }) { Text("P-") }
                    TextButton(onClick = { onPadding(4) }) { Text("P+") }
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(Icons.Default.ChevronRight, "Next chapter")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onToggleTheme) { Text("Theme: $themeLabel") }
            }
        }
    }
}

/** Push the current reader style into the live document (no reload, no scroll reset). */
private fun syncStyle(controller: ReaderViewController) {
    val webView = controller.webView ?: return
    val style = controller.style ?: return
    if (!controller.pageLoaded || controller.appliedStyle == style) return
    webView.post {
        webView.evaluateJavascript(applyStyleScript(style), null)
        controller.appliedStyle = style
    }
}

private fun restoreProgress(controller: ReaderViewController) {
    val webView = controller.webView ?: return
    val progress = controller.pendingRestore ?: return
    controller.pendingRestore = null
    webView.post { webView.evaluateJavascript(scrollScript(progress), null) }
}

private fun applyStyleScript(style: ReaderStyle): String =
    "if(window.SoraReader){window.SoraReader.applySettings(" +
        "${jsNumber(style.fontSize)},${jsNumber(style.lineHeight)},${style.padding},'${style.theme}');}"

private fun scrollScript(progress: Float): String =
    "if(window.SoraReader){window.SoraReader.scrollToProgress(${jsNumber(progress)});}"

/** Locale-independent JS numeric literal (a comma decimal separator would break the script). */
private fun jsNumber(value: Float): String = String.format(Locale.US, "%.3f", value)

private fun readerThemeToken(theme: String, context: Context): String = when {
    theme.equals(ThemeMode.LIGHT, ignoreCase = true) -> "light"
    theme.equals(ThemeMode.DARK, ignoreCase = true) -> "dark"
    else -> if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    ) "dark" else "light"
}

private fun readerThemeLabel(theme: String): String = when {
    theme.equals(ThemeMode.LIGHT, ignoreCase = true) -> "Light"
    theme.equals(ThemeMode.DARK, ignoreCase = true) -> "Dark"
    else -> "System"
}

private fun buildReaderHtml(
    chapterId: Long,
    title: String,
    paragraphs: List<String>,
    rawHtml: String?,
): String {
    // Plugin HTML is untrusted and the page can reach the bridge, so sanitize with the
    // same safelist used for local books before embedding it. Remote-only sources supply
    // escaped paragraphs and take the fallback path.
    val sanitized = rawHtml?.takeIf { it.isNotBlank() }?.let {
        runCatching { Jsoup.clean(it, LocalBookFiles.epubSafelist) }.getOrNull()
    }?.takeIf { it.isNotBlank() }
    val body = sanitized ?: paragraphs.joinToString("") { "<p>${escapeHtml(it)}</p>" }
    return """
    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <style>
      :root {
        --reader-bg: #121212;
        --reader-fg: #e6e6e6;
        --reader-accent: #8ab4f8;
        --reader-font-size: 16px;
        --reader-line-height: 1.6;
        --reader-padding: 16px;
      }
      html,body{margin:0;padding:0;}
      body{
        background:var(--reader-bg);color:var(--reader-fg);
        font-family:sans-serif;font-size:var(--reader-font-size);
        line-height:var(--reader-line-height);
        padding:var(--reader-padding);
        padding-bottom:calc(var(--reader-padding) + 24px);
        transition:background-color .15s ease,color .15s ease;
      }
      #reader-progress{
        position:fixed;top:0;left:0;height:2px;width:100%;
        background:var(--reader-accent);transform:scaleX(0);transform-origin:0 50%;
        z-index:10;pointer-events:none;
      }
      #SoraReader-chapter{max-width:40em;margin:0 auto;}
      h1{font-size:1.4em;line-height:1.3;} p{margin:0 0 1em 0;} img{max-width:100%;height:auto;}
      a{color:var(--reader-accent);} ::selection{background:rgba(138,180,248,.3);}
    </style></head>
    <body>
      <div id="reader-progress"></div>
      <div id="SoraReader-chapter" class="sorareader-chapter-block" data-chapter-id="$chapterId"><h1>${escapeHtml(title)}</h1>$body</div>
    <script>
    (function () {
      var bridge = window.AndroidReader || window.AndroidBridge;
      var bar = document.getElementById('reader-progress');
      var last = -1;
      var frame = 0;

      function ratio() {
        var doc = document.documentElement;
        var max = (doc.scrollHeight || document.body.scrollHeight || 0) - (window.innerHeight || 0);
        var y = window.pageYOffset || doc.scrollTop || document.body.scrollTop || 0;
        var p = max > 0 ? y / max : 0;
        return p < 0 ? 0 : (p > 1 ? 1 : p);
      }

      // Character offset at the viewport anchor, matching the legacy reader's save payload.
      function charOffset() {
        try {
          var host = document.getElementById('SoraReader-chapter') || document.body;
          var anchor = (window.pageYOffset || 0) + (window.innerHeight || 0) * 0.25;
          var walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT, null);
          var count = 0, node, parent, rect;
          while ((node = walker.nextNode())) {
            parent = node.parentElement;
            if (!parent) { count += (node.nodeValue || '').length; continue; }
            rect = parent.getBoundingClientRect();
            if (rect.bottom + (window.pageYOffset || 0) < anchor) {
              count += (node.nodeValue || '').length;
            } else { break; }
          }
          return count;
        } catch (e) { return 0; }
      }

      function report() {
        frame = 0;
        var p = ratio();
        if (bar) bar.style.transform = 'scaleX(' + p.toFixed(4) + ')';
        if (Math.abs(p - last) > 0.001) {
          last = p;
          if (bridge) {
            if (bridge.onProgress) bridge.onProgress(p);
            if (bridge.onScroll) bridge.onScroll(p);
          }
        }
      }

      function schedule() {
        if (frame) return;
        if (window.requestAnimationFrame) frame = window.requestAnimationFrame(report);
        else frame = setTimeout(report, 16);
      }

      function saveNow() {
        if (bridge && bridge.onSaveProgress) bridge.onSaveProgress(ratio(), charOffset());
        else if (bridge && bridge.onSave) bridge.onSave();
      }

      // Left / right thirds navigate chapters, centre toggles the Compose overlay.
      function onTap(e) {
        if (!bridge || e.defaultPrevented) return;
        var node = e.target;
        while (node && node !== document.body) {
          if (node.tagName === 'A' || node.tagName === 'BUTTON') return;
          node = node.parentElement;
        }
        if (window.getSelection && String(window.getSelection()).length > 0) return;
        var x = e.clientX || 0;
        var w = window.innerWidth || 1;
        if (x < w * 0.28) {
          if (bridge.onPrevChapter) bridge.onPrevChapter();
        } else if (x > w * 0.72) {
          if (bridge.onNextChapter) bridge.onNextChapter();
        } else if (bridge.onToggleMenu) {
          bridge.onToggleMenu();
        }
      }

      window.SoraReader = {
        applySettings: function (fontSize, lineHeight, padding, theme) {
          var root = document.documentElement;
          root.style.setProperty('--reader-font-size', fontSize + 'px');
          root.style.setProperty('--reader-line-height', String(lineHeight));
          root.style.setProperty('--reader-padding', padding + 'px');
          var palettes = {
            light: ['#f7f7f7', '#1a1a1a', '#1a73e8'],
            dark: ['#121212', '#e6e6e6', '#8ab4f8'],
            black: ['#000000', '#e6e6e6', '#8ab4f8']
          };
          var pal = palettes[theme] || palettes.dark;
          root.style.setProperty('--reader-bg', pal[0]);
          root.style.setProperty('--reader-fg', pal[1]);
          root.style.setProperty('--reader-accent', pal[2]);
          report();
        },
        scrollToProgress: function (p) {
          var doc = document.documentElement;
          var max = (doc.scrollHeight || document.body.scrollHeight || 0) - (window.innerHeight || 0);
          var y = Math.max(0, Math.min(1, Number(p) || 0)) * Math.max(0, max);
          window.scrollTo(0, y);
        },
        getProgress: function () { return ratio(); },
        getCharOffset: function () { return charOffset(); }
      };

      window.addEventListener('scroll', schedule, { passive: true });
      window.addEventListener('resize', schedule);
      window.addEventListener('pagehide', saveNow);
      document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') saveNow();
      });
      document.addEventListener('click', onTap);
      report();
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
