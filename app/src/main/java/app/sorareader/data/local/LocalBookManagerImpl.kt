package app.sorareader.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import app.sorareader.domain.local.EpubExportResult
import app.sorareader.domain.local.EpubImportResult
import app.sorareader.domain.local.LocalBookManager
import app.sorareader.domain.model.Chapter
import app.sorareader.domain.model.Novel
import app.sorareader.domain.repository.NovelRepository
import app.sorareader.domain.repository.SourceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pragmatic EPUB import/export MVP.
 *
 * Import copies the SAF document to a temp file, reads it with [ZipFile] (random access, so
 * large books don't have to be held in memory), follows container.xml -> OPF -> spine and
 * stores each chapter as a sanitized HTML file in filesDir/local/<novelId>/<chapterId>.html.
 * Inline images are embedded as data URIs so chapters render offline.
 *
 * Export writes a self-contained EPUB 3 archive (with an EPUB 2 NCX for compatibility)
 * straight to the SAF CreateDocument output stream.
 */
class LocalBookManagerImpl(
    private val context: Context,
    private val repo: NovelRepository,
    private val sourceCatalog: SourceCatalog,
) : LocalBookManager {

    override suspend fun importEpub(uri: Uri): EpubImportResult = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            temp = File(context.cacheDir, "epub-import-${UUID.randomUUID()}.epub")
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext EpubImportResult.Failure("Cannot open selected file")
                temp!!.outputStream().use { output -> input.copyTo(output) }
            }
            val book = parseEpub(temp!!)
            if (book.chapters.isEmpty()) {
                return@withContext EpubImportResult.Failure("No readable chapters found in this EPUB")
            }

            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            val title = book.title.ifBlank { displayName?.substringBeforeLast('.')?.trim().orEmpty() }
                .ifBlank { "Imported EPUB" }
            val novelUrl = uri.toString()
            val novel = Novel(
                sourceId = LocalBookFiles.SOURCE_ID,
                url = novelUrl,
                title = title,
                author = book.author,
                description = book.description,
                genres = book.subjects.take(10),
                inLibrary = true,
                isLocal = true,
                lastUpdatedAt = System.currentTimeMillis(),
            )

            // Re-importing the same document updates the existing row (unique sourceId+url).
            val novelId = repo.upsertNovel(novel)
            val dir = LocalBookFiles.novelDir(context.filesDir, novelId)
            LocalBookFiles.deleteRecursively(dir)
            dir.mkdirs()

            var coverUrl: String? = null
            if (book.coverBytes != null) {
                val ext = LocalBookFiles.extensionFor(book.coverMime)
                val coverFile = LocalBookFiles.coverFile(context.filesDir, novelId, ext)
                runCatching { coverFile.writeBytes(book.coverBytes) }
                    .onSuccess { coverUrl = "file://${coverFile.absolutePath}" }
            }
            val previousCover = repo.getNovelById(novelId)?.coverUrl
            repo.upsertNovel(novel.copy(id = novelId, coverUrl = coverUrl ?: previousCover))

            val existing = repo.getChapters(novelId).associateBy { it.url }
            val draft = book.chapters.mapIndexed { index, chapter ->
                val url = LocalBookFiles.chapterUrl(novelId, index)
                val old = existing[url]
                Chapter(
                    id = old?.id ?: 0L,
                    novelId = novelId,
                    url = url,
                    title = chapter.title,
                    number = index + 1f,
                    position = index,
                    readProgress = old?.readProgress ?: 0f,
                    isRead = old?.isRead ?: false,
                    isBookmarked = old?.isBookmarked ?: false,
                )
            }
            repo.upsertChapters(draft)

            // Room assigns real chapter ids on first import; re-read to resolve them.
            val idsByUrl = repo.getChapters(novelId).associateBy { it.url }
            val finalChapters = draft.map { chapter ->
                val id = idsByUrl[chapter.url]?.id ?: 0L
                val file = File(dir, "$id.html")
                file.writeText(book.chapters[chapter.position].html, Charsets.UTF_8)
                chapter.copy(id = id, isDownloaded = true, contentPath = file.absolutePath)
            }
            repo.upsertChapters(finalChapters)

            EpubImportResult.Success(novelId, title, finalChapters.size)
        } catch (t: Throwable) {
            EpubImportResult.Failure(t.message?.takeIf { it.isNotBlank() } ?: "Invalid or unreadable EPUB")
        } finally {
            temp?.delete()
        }
    }

    override suspend fun exportEpub(novelId: Long, uri: Uri): EpubExportResult = withContext(Dispatchers.IO) {
        try {
            val novel = repo.getNovelById(novelId) ?: return@withContext EpubExportResult.Failure("Novel not found")
            val chapters = repo.getChapters(novelId).sortedWith(
                compareBy<Chapter> { it.position }.thenBy { it.number }.thenBy { it.id },
            )
            if (chapters.isEmpty()) return@withContext EpubExportResult.Failure("This novel has no chapters to export")
            val out = context.contentResolver.openOutputStream(uri)
                ?: return@withContext EpubExportResult.Failure("Cannot open destination file")
            val cover = coverFileOf(novel.coverUrl)
            out.use { stream ->
                ZipOutputStream(stream.buffered()).use { zip -> writeEpub(zip, novel, chapters, cover) }
            }
            EpubExportResult.Success("Exported ${chapters.size} chapter${if (chapters.size == 1) "" else "s"} as EPUB")
        } catch (t: Throwable) {
            EpubExportResult.Failure(t.message?.takeIf { it.isNotBlank() } ?: "Export failed")
        }
    }

    // ---------------------------------------------------------------- import helpers

    private data class ParsedChapter(val title: String, val html: String)

    private data class ParsedEpub(
        val title: String,
        val author: String,
        val description: String,
        val subjects: List<String>,
        val coverBytes: ByteArray?,
        val coverMime: String,
        val chapters: List<ParsedChapter>,
    )

    private data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

    private fun parseEpub(file: File): ParsedEpub {
        val zip = ZipFile(file)
        try {
            val entries = HashMap<String, ZipEntry>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                if (!entry.isDirectory) entries[normalizePath(entry.name)] = entry
            }
            fun read(path: String): ByteArray? {
                val entry = entries[normalizePath(path)] ?: return null
                if (entry.size > MAX_ENTRY_BYTES) return null
                return zip.getInputStream(entry).use { it.readBytes() }
            }

            val container = read("META-INF/container.xml")?.let(::parseXml)
            val opfPath = container?.let { doc ->
                elementsNamed(doc, "rootfile").firstNotNullOfOrNull { node ->
                    node.getAttribute("full-path").takeIf { it.isNotBlank() }
                }
            } ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
                ?: throw IllegalArgumentException("EPUB package file not found")

            val opf = parseXml(read(opfPath) ?: throw IllegalArgumentException("Cannot read EPUB package"))
                ?: throw IllegalArgumentException("Malformed EPUB package")

            val manifest = LinkedHashMap<String, ManifestItem>()
            for (element in elementsNamed(opf, "item")) {
                val id = element.getAttribute("id")
                val href = element.getAttribute("href")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = ManifestItem(
                        id = id,
                        href = href,
                        mediaType = element.getAttribute("media-type"),
                        properties = element.getAttribute("properties"),
                    )
                }
            }

            val spine = ArrayList<ManifestItem>()
            for (element in elementsNamed(opf, "itemref")) {
                if (element.getAttribute("linear").equals("no", true)) continue
                val item = manifest[element.getAttribute("idref")] ?: continue
                if (isXhtml(item)) spine += item
            }
            val chapterItems = (if (spine.isNotEmpty()) spine else manifest.values.filter(::isXhtml))
                .distinctBy { it.href }

            var coverItem = manifest.values.firstOrNull { item ->
                item.properties.split(' ', ',').any { it.equals("cover-image", true) }
            }
            if (coverItem == null) {
                val coverId = elementsNamed(opf, "meta").firstOrNull {
                    it.getAttribute("name").equals("cover", true)
                }?.getAttribute("content")?.takeIf { it.isNotBlank() }
                coverItem = coverId?.let { manifest[it] }
            }
            if (coverItem == null) {
                coverItem = manifest.values.firstOrNull {
                    it.id.contains("cover", true) && it.mediaType.startsWith("image/", true)
                }
            }
            val coverBytes = coverItem?.let { read(resolvePath(opfPath, it.href)) }
            val coverMime = coverItem?.mediaType?.takeIf { it.startsWith("image/", true) }
                ?: LocalBookFiles.mimeFor(coverItem?.href ?: "cover.jpg")

            val chapters = ArrayList<ParsedChapter>()
            for (item in chapterItems) {
                val chapterPath = resolvePath(opfPath, item.href)
                val raw = read(chapterPath)?.toString(Charsets.UTF_8) ?: continue
                val document = runCatching { Jsoup.parse(raw, chapterPath) }.getOrNull() ?: continue
                document.select("script, style, noscript, iframe, object, embed, form, link").remove()
                document.select("img[src]").forEach { image ->
                    val src = image.attr("src")
                    if (src.isBlank() || src.startsWith("data:", true) || src.startsWith("http", true)) return@forEach
                    val bytes = read(resolvePath(chapterPath, src))
                    if (bytes != null && bytes.size <= MAX_IMAGE_BYTES) {
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        image.attr("src", "data:${LocalBookFiles.mimeFor(src)};base64,$encoded")
                        image.removeAttr("srcset")
                    } else {
                        image.remove() // don't leave broken/remote references
                    }
                }
                val title = document.title().trim().ifBlank {
                    document.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
                }
                val body = runCatching {
                    Jsoup.clean(document.body()?.html().orEmpty(), LocalBookFiles.epubSafelist)
                }.getOrDefault("")
                if (body.isBlank() && title.isBlank()) continue
                chapters += ParsedChapter(title.ifBlank { "Chapter ${chapters.size + 1}" }, body)
            }

            val rawDescription = textOf(opf, "dc:description", "description")
            return ParsedEpub(
                title = textOf(opf, "dc:title", "title"),
                author = textOf(opf, "dc:creator", "creator"),
                description = rawDescription.let { runCatching { Jsoup.clean(it, org.jsoup.safety.Safelist.none()) }.getOrDefault(it) },
                subjects = textsOf(opf, "dc:subject", "subject"),
                coverBytes = coverBytes,
                coverMime = coverMime,
                chapters = chapters,
            )
        } finally {
            zip.close()
        }
    }

    private fun isXhtml(item: ManifestItem): Boolean =
        item.mediaType.contains("xhtml", true) || item.mediaType.equals("text/html", true) ||
            item.href.endsWith(".xhtml", true) || item.href.endsWith(".html", true)

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.getOrNull()

    /** Match elements by local name so any namespace prefix (dc:, opf:, ...) works. */
    private fun elementsNamed(document: org.w3c.dom.Document, localName: String): List<org.w3c.dom.Element> {
        val all = document.getElementsByTagName("*")
        return (0 until all.length).mapNotNull { node ->
            (node as? org.w3c.dom.Element)?.takeIf {
                it.tagName == localName || it.tagName.substringAfter(':') == localName
            }
        }
    }

    private fun textOf(document: org.w3c.dom.Document, vararg tags: String): String {
        for (tag in tags) {
            for (element in elementsNamed(document, tag)) {
                val value = element.textContent?.trim().orEmpty()
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun textsOf(document: org.w3c.dom.Document, vararg tags: String): List<String> {
        val values = ArrayList<String>()
        for (tag in tags) {
            for (element in elementsNamed(document, tag)) {
                val value = element.textContent?.trim().orEmpty()
                if (value.isNotBlank() && value !in values) values += value
            }
        }
        return values
    }

    private fun normalizePath(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun resolvePath(basePath: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8")
        val base = basePath.substringBeforeLast('/', "")
        return normalizePath(if (base.isEmpty()) clean else "$base/$clean")
    }

    // ---------------------------------------------------------------- export helpers

    private data class ExportChapter(val title: String, val xhtml: String)

    private suspend fun writeEpub(zip: ZipOutputStream, novel: Novel, chapters: List<Chapter>, cover: File?) {
        val uid = "urn:uuid:${UUID.randomUUID()}"
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        // EPUB requires the mimetype to be the first entry, uncompressed and unencrypted.
        val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val mimeEntry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mimetype.size.toLong()
            compressedSize = mimetype.size.toLong()
            crc = CRC32().apply { update(mimetype) }.value
        }
        zip.putNextEntry(mimeEntry)
        zip.write(mimetype)
        zip.closeEntry()

        val bodies = chapters.map { resolveChapterBody(novel, it) }
        val coverName = cover?.let { "cover.${LocalBookFiles.extensionFor(LocalBookFiles.mimeFor(it.name))}" }
        val coverMime = coverName?.let(::mimeTypeForName)

        putText(
            zip, "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".trimIndent(),
        )

        val manifest = buildList {
            add("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            add("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            bodies.indices.forEach { add("""<item id="chap$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>""") }
            if (cover != null && coverName != null) {
                add("""<item id="cover-image" href="$coverName" media-type="$coverMime" properties="cover-image"/>""")
            }
        }.joinToString("\n            ")

        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="en">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${xml(novel.title.ifBlank { "Untitled" })}</dc:title>
    <dc:creator>${xml(novel.author.ifBlank { "Unknown" })}</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="bookid">$uid</dc:identifier>
    <meta property="dcterms:modified">$modified</meta>
    ${if (cover != null) """<meta name="cover" content="cover-image"/>""" else ""}
  </metadata>
  <manifest>
            $manifest
  </manifest>
  <spine toc="ncx">
    ${bodies.indices.joinToString("\n    ") { """<itemref idref="chap$it"/>""" }}
  </spine>
</package>""".trimIndent()
        putText(zip, "OEBPS/content.opf", opf)

        val navItems = bodies.mapIndexed { index, body ->
            """<li><a href="chapter$index.xhtml">${xml(body.title)}</a></li>"""
        }.joinToString("\n")
        putText(
            zip, "OEBPS/nav.xhtml",
            """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol>
$navItems
</ol></nav></body></html>""".trimIndent(),
        )

        val navPoints = bodies.mapIndexed { index, body ->
            """<navPoint id="navpoint-${index + 1}" playOrder="${index + 1}"><navLabel><text>${xml(body.title)}</text></navLabel><content src="chapter$index.xhtml"/></navPoint>"""
        }.joinToString("\n")
        putText(
            zip, "OEBPS/toc.ncx",
            """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="$uid"/></head>
<docTitle><text>${xml(novel.title)}</text></docTitle>
<navMap>
$navPoints
</navMap></ncx>""".trimIndent(),
        )

        bodies.forEachIndexed { index, body ->
            putText(
                zip, "OEBPS/chapter$index.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${xml(body.title)}</title></head><body>${body.xhtml}</body></html>""".trimIndent(),
            )
        }
        if (cover != null && coverName != null) {
            runCatching { putBytes(zip, "OEBPS/$coverName", cover.readBytes()) }
        }
    }

    private suspend fun resolveChapterBody(novel: Novel, chapter: Chapter): ExportChapter {
        val fromFile = chapter.contentPath?.takeIf { it.isNotBlank() }?.let {
            LocalBookFiles.readContent(it, chapter.title)
        }
        val title = fromFile?.title?.takeIf { it.isNotBlank() } ?: chapter.title.ifBlank { "Chapter" }
        val raw: String = when {
            fromFile?.html != null -> fromFile.html!!
            fromFile != null -> fromFile.paragraphs.joinToString("") { "<p>${xml(it)}</p>" }
            else -> runCatching { sourceCatalog.fetchChapterContent(novel.sourceId, chapter.url) }.getOrNull()
                ?.let { remote ->
                    remote.html?.let { Jsoup.clean(it, LocalBookFiles.epubSafelist) }
                        ?: remote.paragraphs.joinToString("") { "<p>${xml(it)}</p>" }
                } ?: "<p>Content unavailable offline.</p>"
        }
        val safe = runCatching { Jsoup.clean(raw, LocalBookFiles.epubSafelist) }.getOrDefault(raw)
        return ExportChapter(title = title, xhtml = LocalBookFiles.toXhtmlBody(safe))
    }

    private fun coverFileOf(url: String?): File? {
        if (url.isNullOrBlank()) return null
        val path = url.removePrefix("file://").removePrefix("file:")
        return File(path).takeIf { it.exists() && it.isFile }
    }

    private fun mimeTypeForName(name: String): String = LocalBookFiles.mimeFor(name)

    private fun putText(zip: ZipOutputStream, path: String, text: String) =
        putBytes(zip, path, text.toByteArray(Charsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    }
}
