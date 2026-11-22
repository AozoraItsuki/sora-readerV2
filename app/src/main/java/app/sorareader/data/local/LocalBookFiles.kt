package app.sorareader.data.local

import app.sorareader.domain.model.ChapterContent
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.safety.Safelist
import java.io.File

/**
 * Shared helpers for the "local" source. Chapter content is stored as small,
 * sanitized HTML files under filesDir/local/<novelId>/<chapterId>.html so the
 * reader works fully offline. Cover art lives next to them as cover.<ext>.
 */
object LocalBookFiles {
    const val SOURCE_ID = "local"

    private val CHAPTER_URL = Regex("""local://chapter/(\d+)/(\d+)""")

    /** Safelist used both on import (strip scripts) and on export (valid XHTML body). */
    val epubSafelist: Safelist = Safelist.relaxed()
        .addTags(
            "img", "hr", "br", "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li",
            "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td",
            "figure", "figcaption", "div", "span",
        )
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addProtocols("img", "src", "data", "http", "https")
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https", "mailto")
        .preserveRelativeLinks(false)

    fun root(filesDir: File): File = File(filesDir, "local")

    fun novelDir(filesDir: File, novelId: Long): File = File(root(filesDir), novelId.toString())

    fun chapterFile(filesDir: File, novelId: Long, chapterId: Long): File =
        File(novelDir(filesDir, novelId), "$chapterId.html")

    fun coverFile(filesDir: File, novelId: Long, extension: String = "jpg"): File =
        File(novelDir(filesDir, novelId), "cover.$extension")

    fun chapterUrl(novelId: Long, index: Int): String = "local://chapter/$novelId/$index"

    fun parseChapterUrl(url: String): Pair<Long, Int>? {
        val match = CHAPTER_URL.matchEntire(url) ?: return null
        val novelId = match.groupValues[1].toLongOrNull() ?: return null
        val index = match.groupValues[2].toIntOrNull() ?: return null
        return novelId to index
    }

    /** Read a stored chapter file. Accepts both our sanitized HTML and legacy plain-text files. */
    fun readContent(path: String, fallbackTitle: String = ""): ChapterContent? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val looksLikeHtml = path.endsWith(".html", true) || path.endsWith(".htm", true) ||
                path.endsWith(".xhtml", true) || text.trimStart().startsWith("<")
            if (!looksLikeHtml) {
                return ChapterContent(
                    chapterId = 0,
                    title = fallbackTitle,
                    paragraphs = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                )
            }
            val doc = Jsoup.parse(text)
            doc.select("script, style, noscript, iframe, object, embed, form").remove()
            val safeBody = Jsoup.clean(doc.body()?.html().orEmpty(), epubSafelist)
            val cleaned = Jsoup.parseBodyFragment(safeBody)
            val paragraphs = cleaned.body().select("p, h1, h2, h3, h4, li, blockquote")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }
                .ifEmpty { listOf(cleaned.body().text().trim()).filter { it.isNotEmpty() } }
            val title = doc.title().trim().ifBlank {
                doc.selectFirst("h1, h2")?.text()?.trim().orEmpty()
            }.ifBlank { fallbackTitle }
            ChapterContent(chapterId = 0, title = title, paragraphs = paragraphs, html = safeBody)
        }.getOrNull()
    }

    /** Re-serialize a sanitized HTML fragment as an XHTML 1.0 compliant body fragment. */
    fun toXhtmlBody(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        doc.outputSettings().escapeMode(Entities.EscapeMode.xhtml)
        doc.outputSettings().prettyPrint(false)
        return doc.body().html()
    }

    fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }

    fun extensionFor(mime: String): String = when {
        mime.contains("png", true) -> "png"
        mime.contains("gif", true) -> "gif"
        mime.contains("webp", true) -> "webp"
        mime.contains("svg", true) -> "svg"
        mime.contains("jpeg", true) || mime.contains("jpg", true) -> "jpg"
        else -> "jpg"
    }

    fun deleteRecursively(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteRecursively(child) else runCatching { child.delete() }
        }
        runCatching { dir.delete() }
    }
}
