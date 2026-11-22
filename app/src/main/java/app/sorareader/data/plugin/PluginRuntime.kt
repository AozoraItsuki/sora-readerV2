package app.sorareader.data.plugin

import app.cash.quickjs.QuickJs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.Closeable
import java.net.URLEncoder
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Minimal, failure-tolerant JS runtime for legacy LNReader-style plugins.
 *
 * The DOM shim is backed by JSoup (rather than string regexes) so real world
 * plugin selectors keep working. Everything exposed to plugin code is routed
 * through [Bridge] and guarded so a single bad plugin call cannot crash the
 * reader.
 */
class PluginRuntime(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Closeable {
    private val quickJs: QuickJs = QuickJs.create()
    private var loadedPluginId: String? = null

    // JSoup-backed cheerio state. JS only sees integer handles/ids.
    private val selections = mutableMapOf<Int, List<Element>>()
    private val documentSelections = mutableSetOf<Int>()
    private val elementHandles = mutableMapOf<Int, Element>()
    private val handleByElement = IdentityHashMap<Element, Int>()
    private var nextSelectionId = 1
    private var nextElementId = 1

    interface Bridge {
        fun fetch(url: String, initJson: String?): String
        fun log(msg: String)
        fun userAgent(): String

        // cheerio / DOM
        fun chLoad(html: String): Int
        fun chFromHtml(html: String): Int
        fun chEmpty(): Int
        fun chWrapHandle(handle: Int): Int
        fun chFromHandles(handlesJson: String): Int
        fun chElementHtml(handle: Int): String
        fun chSelect(selId: Int, selector: String): Int
        fun chFind(selId: Int, selector: String): Int
        fun chChildren(selId: Int, selector: String?): Int
        fun chParent(selId: Int): Int
        fun chNext(selId: Int): Int
        fun chPrev(selId: Int): Int
        fun chFirst(selId: Int): Int
        fun chLast(selId: Int): Int
        fun chEq(selId: Int, index: Int): Int
        fun chFilter(selId: Int, selector: String): Int
        fun chIs(selId: Int, selector: String): Boolean
        fun chClone(selId: Int): Int
        fun chRemove(selId: Int)
        fun chSetAttr(selId: Int, name: String, value: String)
        fun chAddClass(selId: Int, cls: String)
        fun chText(selId: Int): String
        fun chHtml(selId: Int): String
        fun chAttr(selId: Int, name: String): String
        fun chHasClass(selId: Int, cls: String): Boolean
        fun chLength(selId: Int): Int
        fun chHandles(selId: Int): String
        fun chGetHandle(selId: Int, index: Int): Int
    }

    init {
        installBootstrap()
    }

    private fun installBootstrap() {
        quickJs.set("__nativeBridge", Bridge::class.java, object : Bridge {
            override fun fetch(url: String, initJson: String?): String = nativeFetch(url, initJson)
            override fun log(msg: String) { println("[PluginRuntime] $msg") }
            override fun userAgent(): String = DEFAULT_UA

            override fun chLoad(html: String): Int = runCatching {
                newSelection(listOf(Jsoup.parse(html)), isDocument = true)
            }.getOrElse { newSelection(emptyList()) }

            override fun chFromHtml(html: String): Int = runCatching {
                val body = Jsoup.parseBodyFragment(html).body()
                val kids = body.children().toList()
                newSelection(if (kids.size == 1) listOf(kids.first()) else kids)
            }.getOrElse { newSelection(emptyList()) }

            override fun chEmpty(): Int = newSelection(emptyList())

            override fun chWrapHandle(handle: Int): Int =
                newSelection(elementHandles[handle]?.let { listOf(it) } ?: emptyList())

            override fun chFromHandles(handlesJson: String): Int = runCatching {
                val arr = json.parseToJsonElement(handlesJson) as? JsonArray ?: JsonArray(emptyList())
                newSelection(arr.mapNotNull { elementHandles[it.asText().toIntOrNull() ?: -1] })
            }.getOrElse { newSelection(emptyList()) }

            override fun chElementHtml(handle: Int): String =
                elementHandles[handle]?.outerHtml() ?: ""

            override fun chSelect(selId: Int, selector: String): Int = selectWithin(selId, selector)
            override fun chFind(selId: Int, selector: String): Int = selectWithin(selId, selector)

            override fun chChildren(selId: Int, selector: String?): Int = runCatching {
                val out = mutableListOf<Element>()
                for (el in selection(selId)) {
                    val kids = el.children()
                    if (selector.isNullOrBlank()) {
                        out.addAll(kids)
                    } else {
                        out.addAll(kids.filter { runCatching { it.is(selector) }.getOrDefault(false) })
                    }
                }
                newSelection(out.distinct())
            }.getOrElse { newSelection(emptyList()) }

            override fun chParent(selId: Int): Int = runCatching {
                newSelection(selection(selId).mapNotNull { it.parent() }.distinct())
            }.getOrElse { newSelection(emptyList()) }

            override fun chNext(selId: Int): Int = runCatching {
                newSelection(selection(selId).mapNotNull { it.nextElementSibling() }.distinct())
            }.getOrElse { newSelection(emptyList()) }

            override fun chPrev(selId: Int): Int = runCatching {
                newSelection(selection(selId).mapNotNull { it.previousElementSibling() }.distinct())
            }.getOrElse { newSelection(emptyList()) }

            override fun chFirst(selId: Int): Int =
                newSelection(selection(selId).firstOrNull()?.let { listOf(it) } ?: emptyList())

            override fun chLast(selId: Int): Int =
                newSelection(selection(selId).lastOrNull()?.let { listOf(it) } ?: emptyList())

            override fun chEq(selId: Int, index: Int): Int = runCatching {
                val els = selection(selId)
                val idx = if (index < 0) els.size + index else index
                newSelection(els.getOrNull(idx)?.let { listOf(it) } ?: emptyList())
            }.getOrElse { newSelection(emptyList()) }

            override fun chFilter(selId: Int, selector: String): Int = runCatching {
                newSelection(selection(selId).filter { runCatching { it.is(selector) }.getOrDefault(false) })
            }.getOrElse { newSelection(emptyList()) }

            override fun chIs(selId: Int, selector: String): Boolean = runCatching {
                selection(selId).firstOrNull()?.let { runCatching { it.is(selector) }.getOrDefault(false) } ?: false
            }.getOrDefault(false)

            override fun chClone(selId: Int): Int = runCatching {
                newSelection(selection(selId).map { it.clone() })
            }.getOrElse { newSelection(emptyList()) }

            override fun chRemove(selId: Int) {
                runCatching { selection(selId).forEach { it.remove() } }
            }

            override fun chSetAttr(selId: Int, name: String, value: String) {
                runCatching { selection(selId).forEach { it.attr(name, value) } }
            }

            override fun chAddClass(selId: Int, cls: String) {
                runCatching { selection(selId).forEach { it.addClass(cls) } }
            }

            override fun chText(selId: Int): String = runCatching {
                selection(selId).joinToString(" ") { it.text() }.trim()
            }.getOrDefault("")

            override fun chHtml(selId: Int): String = runCatching {
                val els = selection(selId)
                when {
                    els.isEmpty() -> ""
                    documentSelections.contains(selId) -> els.first().outerHtml()
                    else -> els.first().html()
                }
            }.getOrDefault("")

            override fun chAttr(selId: Int, name: String): String = runCatching {
                selection(selId).firstOrNull()?.attr(name) ?: ""
            }.getOrDefault("")

            override fun chHasClass(selId: Int, cls: String): Boolean = runCatching {
                selection(selId).firstOrNull()?.hasClass(cls) ?: false
            }.getOrDefault(false)

            override fun chLength(selId: Int): Int = selection(selId).size

            override fun chHandles(selId: Int): String = runCatching {
                JsonArray(selection(selId).map { JsonPrimitive(handleOf(it)) }).toString()
            }.getOrDefault("[]")

            override fun chGetHandle(selId: Int, index: Int): Int = runCatching {
                selection(selId).getOrNull(index)?.let { handleOf(it) } ?: -1
            }.getOrDefault(-1)
        })
        quickJs.evaluate(BOOTSTRAP, "bootstrap.js")
    }

    private fun newSelection(elements: List<Element>, isDocument: Boolean = false): Int {
        val id = nextSelectionId++
        selections[id] = elements
        if (isDocument) documentSelections.add(id)
        return id
    }

    private fun selection(id: Int): List<Element> = selections[id] ?: emptyList()

    private fun handleOf(element: Element): Int = handleByElement[element] ?: run {
        val handle = nextElementId++
        elementHandles[handle] = element
        handleByElement[element] = handle
        handle
    }

    private fun selectWithin(selId: Int, selector: String): Int = runCatching {
        val out = mutableListOf<Element>()
        for (el in selection(selId)) {
            runCatching { out.addAll(el.select(selector)) }
        }
        newSelection(out.distinct())
    }.getOrElse { newSelection(emptyList()) }

    // ------------------------------------------------------------------ fetch

    private fun nativeFetch(url: String, initJson: String?): String {
        return runCatching {
            val init = initJson?.takeIf { it.isNotBlank() }
                ?.let { json.parseToJsonElement(it) as? JsonObject }
            val method = init?.get("method")?.asText()?.trim()?.uppercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() } ?: "GET"

            val headers = LinkedHashMap<String, String>().apply {
                DEFAULT_HEADERS.forEach { (k, v) -> put(k.lowercase(Locale.ROOT), v) }
                init?.get("headers")?.let { parseHeaderElement(it, this) }
            }

            var formEncoded = false
            val bodyStr = when (val body = init?.get("body")) {
                is JsonPrimitive -> body.contentOrNull
                is JsonObject -> {
                    formEncoded = true
                    val pairs = body["_pairs"] as? JsonArray
                    if (pairs != null) {
                        pairs.joinToString("&") { pair ->
                            val arr = pair as? JsonArray
                            if (arr != null && arr.size >= 2) {
                                "${urlEncode(arr[0].asText())}=${urlEncode(arr[1].asText())}"
                            } else ""
                        }
                    } else {
                        body.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v.asText())}" }
                    }
                }
                is JsonArray -> {
                    formEncoded = true
                    body.joinToString("&") { pair ->
                        val arr = pair as? JsonArray
                        if (arr != null && arr.size >= 2) {
                            "${urlEncode(arr[0].asText())}=${urlEncode(arr[1].asText())}"
                        } else ""
                    }
                }
                else -> null
            }
            val mediaType = headers["content-type"]?.toMediaTypeOrNull()
                ?: (if (formEncoded) "application/x-www-form-urlencoded" else "text/plain; charset=utf-8").toMediaTypeOrNull()
            val reqBody = if (bodyStr != null && method != "GET" && method != "HEAD") {
                bodyStr.toRequestBody(mediaType)
            } else null

            val req = Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .method(method, reqBody)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                val respHeaders = runCatching {
                    resp.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                }.getOrDefault(emptyMap())
                JsonObject(mapOf(
                    "ok" to JsonPrimitive(resp.isSuccessful),
                    "status" to JsonPrimitive(resp.code),
                    "url" to JsonPrimitive(resp.request.url.toString()),
                    "body" to JsonPrimitive(body),
                    "headers" to JsonObject(respHeaders.mapValues { JsonPrimitive(it.value) }),
                )).toString()
            }
        }.getOrElse { e ->
            JsonObject(mapOf(
                "ok" to JsonPrimitive(false),
                "status" to JsonPrimitive(0),
                "url" to JsonPrimitive(url),
                "body" to JsonPrimitive(""),
                "error" to JsonPrimitive(e.message ?: "fetch failed"),
                "headers" to JsonObject(emptyMap()),
            )).toString()
        }
    }

    private fun parseHeaderElement(element: JsonElement, out: MutableMap<String, String>) {
        when (element) {
            is JsonObject -> {
                // Support our Headers polyfill ({_map:{...}}) and common variants.
                val nested = element["_map"] ?: element["map"] ?: element["_headers"]
                if (nested is JsonObject) {
                    parseHeaderElement(nested, out)
                    return
                }
                element.forEach { (key, value) ->
                    val k = key.lowercase(Locale.ROOT)
                    when (value) {
                        is JsonPrimitive -> out[k] = value.contentOrNull ?: ""
                        is JsonArray -> out[k] = value.joinToString(", ") { it.asText() }
                        else -> Unit
                    }
                }
            }
            is JsonArray -> element.forEach { pair ->
                when (pair) {
                    is JsonArray -> if (pair.size >= 2) {
                        out[pair[0].asText().lowercase(Locale.ROOT)] = pair[1].asText()
                    }
                    is JsonObject -> {
                        val name = pair["name"]?.asText() ?: pair["key"]?.asText()
                        val value = pair["value"]?.asText()
                        if (!name.isNullOrBlank() && value != null) {
                            out[name.lowercase(Locale.ROOT)] = value
                        }
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: ""
        else -> toString()
    }

    private fun urlEncode(value: String): String = runCatching {
        URLEncoder.encode(value, "UTF-8")
    }.getOrDefault(value)

    // ------------------------------------------------------------ plugin API

    fun loadPlugin(pluginId: String, rawCode: String) {
        if (loadedPluginId == pluginId) return
        quickJs.evaluate(
            "globalThis.__sora_loadPlugin(${jsonString(pluginId)}, ${jsonString(rawCode)});",
            "$pluginId.js",
        )
        loadedPluginId = pluginId
    }

    /** Force a reload even when the same id is already loaded (used on updates). */
    fun reloadPlugin(pluginId: String, rawCode: String) {
        unloadPlugin(pluginId)
        loadPlugin(pluginId, rawCode)
    }

    fun unloadPlugin(pluginId: String) {
        quickJs.evaluate("globalThis.__sora_unloadPlugin(${jsonString(pluginId)});", "unload.js")
        if (loadedPluginId == pluginId) loadedPluginId = null
    }

    fun getMeta(pluginId: String): InstalledPlugin? = runCatching {
        val raw = quickJs.evaluate("globalThis.__sora_meta(${jsonString(pluginId)})", "meta.js") as? String
            ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        InstalledPlugin(
            id = obj.text("id") ?: pluginId,
            name = obj.text("name") ?: pluginId,
            site = obj.text("site") ?: "",
            lang = obj.text("lang") ?: "en",
            version = obj.text("version") ?: "1.0.0",
            iconUrl = obj.text("iconUrl") ?: obj.text("icon"),
            customJS = obj.text("customJS"),
            customCSS = obj.text("customCSS"),
            hasSettings = runCatching { obj["hasSettings"]?.jsonPrimitive?.booleanOrNull }
                .getOrDefault(false) ?: false,
        )
    }.getOrNull()

    fun popularNovels(pluginId: String, page: Int, showLatest: Boolean): List<PluginNovelItem> {
        val filters = runCatching {
            val raw = quickJs.evaluate(
                "globalThis.__sora_defaultFilters(${jsonString(pluginId)})", "filters.js",
            ) as? String
            raw?.let { json.parseToJsonElement(it) } ?: JsonObject(emptyMap())
        }.getOrDefault(JsonObject(emptyMap()))
        val options = JsonObject(mapOf(
            "showLatestNovels" to JsonPrimitive(showLatest),
            "filters" to filters,
        ))
        val raw = callJson(pluginId, "popularNovels", listOf(JsonPrimitive(page), options))
        return parseNovelItems(raw)
    }

    fun searchNovels(pluginId: String, searchTerm: String, page: Int): List<PluginNovelItem> {
        val raw = callJson(pluginId, "searchNovels", listOf(JsonPrimitive(searchTerm), JsonPrimitive(page)))
        return parseNovelItems(raw)
    }

    fun parseNovel(pluginId: String, novelPath: String): PluginSourceNovel {
        val raw = callJson(pluginId, "parseNovel", listOf(JsonPrimitive(novelPath)))
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { return PluginSourceNovel(name = "", path = novelPath) }
        val chapters = obj.firstArray("chapters", "chapterList", "chaptersList")
            ?.mapNotNull { (it as? JsonObject)?.let { chapter -> parseChapterItem(chapter) } }
            ?: emptyList()
        return PluginSourceNovel(
            name = obj.text("name", "title") ?: "",
            path = obj.text("path", "url", "link") ?: novelPath,
            cover = obj.text("cover", "image", "coverUrl", "img"),
            genres = obj.text("genres", "genre"),
            summary = obj.text("summary", "description", "synopsis"),
            author = obj.text("author"),
            artist = obj.text("artist"),
            status = obj.text("status"),
            chapters = chapters,
            totalPages = obj.firstInt("totalPages", "lastPage", "maxPages", "pageCount") ?: 1,
        )
    }

    fun parseChapter(pluginId: String, chapterPath: String): String {
        val raw = callJson(pluginId, "parseChapter", listOf(JsonPrimitive(chapterPath)))
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return ""
        return when (el) {
            is JsonPrimitive -> el.contentOrNull ?: ""
            is JsonObject -> el.text("content", "html", "text", "body", "chapter") ?: el.toString()
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
            else -> ""
        }
    }

    /** Best-effort legacy `resolveUrl(path, isNovel)`; falls back to the input. */
    fun resolveUrl(pluginId: String, path: String, isNovel: Boolean = false): String {
        if (path.isBlank()) return path
        return runCatching {
            val raw = callJson(
                pluginId,
                "resolveUrl",
                listOf(JsonPrimitive(path), JsonPrimitive(isNovel)),
            )
            json.parseToJsonElement(raw).jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: path
        }.getOrDefault(path)
    }

    private fun callJson(pluginId: String, method: String, args: List<JsonElement>): String {
        val argsJson = JsonArray(args).toString()
        val callId = quickJs.evaluate(
            "globalThis.__sora_call(${jsonString(pluginId)}, ${jsonString(method)}, ${jsonString(argsJson)})",
            "call-$method.js",
        ) as? String ?: error("No callId")
        repeat(2000) {
            quickJs.evaluate("globalThis.__sora_drainJobs()", "drain.js")
            val poll = quickJs.evaluate("globalThis.__sora_poll(${jsonString(callId)})", "poll.js") as? String
                ?: return "null"
            val obj = runCatching { json.parseToJsonElement(poll).jsonObject }.getOrElse { return "null" }
            if (obj["done"]?.jsonPrimitive?.booleanOrNull == true) {
                val err = obj.text("error")
                if (!err.isNullOrBlank()) error("Plugin $pluginId.$method failed: $err")
                return (obj["value"] ?: JsonNull).toString()
            }
            Thread.sleep(5)
        }
        error("Plugin $pluginId.$method timed out")
    }

    private fun JsonObject.text(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] ?: continue
            if (value is JsonPrimitive) {
                val content = value.contentOrNull
                if (!content.isNullOrBlank()) return content
            }
        }
        return null
    }

    private fun JsonObject.firstArray(vararg keys: String): JsonArray? {
        for (key in keys) {
            val value = this[key] ?: continue
            if (value is JsonArray) return value
        }
        return null
    }

    private fun JsonObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            val value = this[key] ?: continue
            if (value is JsonPrimitive) {
                value.intOrNull?.let { return it }
                value.contentOrNull?.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JsonObject.firstFloat(vararg keys: String): Float? {
        for (key in keys) {
            val value = this[key] ?: continue
            if (value is JsonPrimitive) {
                value.doubleOrNull?.toFloat()?.let { return it }
                value.contentOrNull?.toFloatOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun parseNovelItems(raw: String): List<PluginNovelItem> {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val arr = when (element) {
            is JsonArray -> element
            is JsonObject -> element.firstArray("novels", "results", "items") ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name", "title") ?: return@mapNotNull null
            val path = obj.text("path", "url", "link") ?: return@mapNotNull null
            PluginNovelItem(name = name, path = path, cover = obj.text("cover", "image", "coverUrl", "img"))
        }
    }

    private fun parseChapterItem(obj: JsonObject): PluginChapterItem = PluginChapterItem(
        name = obj.text("name", "title") ?: "",
        path = obj.text("path", "url", "link") ?: "",
        chapterNumber = obj.firstFloat("chapterNumber", "number"),
        releaseTime = obj.text("releaseTime", "time", "date", "updatedAt"),
        page = obj.text("page"),
    )

    private fun jsonString(s: String): String = JsonPrimitive(s).toString()

    override fun close() {
        runCatching { quickJs.close() }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to DEFAULT_UA,
            "Accept" to "*/*",
            "Accept-Language" to "*",
            "Connection" to "keep-alive",
        )

        private val BOOTSTRAP = """
const __sora_plugins = {};
const __sora_modules = {};
var __sora_currentPluginId = '';

function __sora_defineModule(name, mod) { __sora_modules[name] = mod; }

function __sora_htmlToText(html) {
  if (!html) return '';
  return String(html)
    .replace(/<br\s*\/?>(\s*)/gi, '\n')
    .replace(/<\/(p|div|li|h1|h2|h3|h4|h5|h6|tr)>/gi, '\n')
    .replace(/<li[^>]*>/gi, '\n- ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;|&#160;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
    .replace(/\n{3,}/g, '\n\n').replace(/[ \t]+/g, ' ').trim();
}

// ---------------------------------------------------------------- cheerio
function __SoraElement(id, docId) {
  this._id = id;
  this._docId = (docId === undefined || docId === null) ? id : docId;
  Object.defineProperty(this, 'length', {
    get: function () { return __nativeBridge.chLength(this._id); }, configurable: true
  });
}
function __sora_handles(id) {
  try { return JSON.parse(__nativeBridge.chHandles(id)) || []; } catch (e) { return []; }
}
function __sora_arrayResult(values) {
  var out = { length: values.length };
  for (var i = 0; i < values.length; i++) out[i] = values[i];
  out.get = function (i) { return i === undefined ? values : values[i | 0]; };
  out.toArray = function () { return values; };
  out.each = function (fn) { for (var i = 0; i < values.length; i++) { try { fn(i, values[i]); } catch (e) {} } return out; };
  return out;
}
__SoraElement.prototype.find = function (sel) {
  return new __SoraElement(__nativeBridge.chFind(this._id, String(sel || '*')), this._docId);
};
__SoraElement.prototype.children = function (sel) {
  return new __SoraElement(__nativeBridge.chChildren(this._id, sel == null ? null : String(sel)), this._docId);
};
__SoraElement.prototype.contents = function () {
  return new __SoraElement(__nativeBridge.chChildren(this._id, null), this._docId);
};
__SoraElement.prototype.parent = function () {
  return new __SoraElement(__nativeBridge.chParent(this._id), this._docId);
};
__SoraElement.prototype.next = function () {
  return new __SoraElement(__nativeBridge.chNext(this._id), this._docId);
};
__SoraElement.prototype.prev = function () {
  return new __SoraElement(__nativeBridge.chPrev(this._id), this._docId);
};
__SoraElement.prototype.first = function () {
  return new __SoraElement(__nativeBridge.chFirst(this._id), this._docId);
};
__SoraElement.prototype.last = function () {
  return new __SoraElement(__nativeBridge.chLast(this._id), this._docId);
};
__SoraElement.prototype.eq = function (i) {
  return new __SoraElement(__nativeBridge.chEq(this._id, i | 0), this._docId);
};
__SoraElement.prototype.filter = function (sel) {
  return new __SoraElement(__nativeBridge.chFilter(this._id, String(sel)), this._docId);
};
__SoraElement.prototype.is = function (sel) { return __nativeBridge.chIs(this._id, String(sel)); };
__SoraElement.prototype.clone = function () {
  return new __SoraElement(__nativeBridge.chClone(this._id), this._docId);
};
__SoraElement.prototype.text = function () { return __nativeBridge.chText(this._id); };
__SoraElement.prototype.html = function () { return __nativeBridge.chHtml(this._id); };
__SoraElement.prototype.attr = function (name, value) {
  if (arguments.length > 1) {
    __nativeBridge.chSetAttr(this._id, String(name), String(value));
    return this;
  }
  return __nativeBridge.chAttr(this._id, String(name));
};
__SoraElement.prototype.hasClass = function (cls) { return __nativeBridge.chHasClass(this._id, String(cls)); };
__SoraElement.prototype.addClass = function (cls) { __nativeBridge.chAddClass(this._id, String(cls)); return this; };
__SoraElement.prototype.removeClass = function () { return this; };
__SoraElement.prototype.remove = function () { __nativeBridge.chRemove(this._id); return this; };
__SoraElement.prototype.get = function (i) {
  if (i === undefined || i === null) return __sora_handles(this._id);
  var h = __nativeBridge.chGetHandle(this._id, i | 0);
  return h < 0 ? undefined : h;
};
__SoraElement.prototype.toArray = function () { return __sora_handles(this._id); };
__SoraElement.prototype.each = function (fn) {
  var hs = __sora_handles(this._id);
  for (var i = 0; i < hs.length; i++) {
    try { fn(i, hs[i]); } catch (e) { __nativeBridge.log('each: ' + e); }
  }
  return this;
};
__SoraElement.prototype.map = function (fn) {
  var hs = __sora_handles(this._id);
  var out = [];
  for (var i = 0; i < hs.length; i++) {
    try { out.push(fn(i, hs[i])); } catch (e) { out.push(''); }
  }
  return __sora_arrayResult(out);
};
__SoraElement.prototype.end = function () { return this; };

function __sora_makeQuery(docId) {
  var $ = function (input, context) {
    if (input === undefined || input === null) return new __SoraElement(__nativeBridge.chEmpty(), docId);
    if (input instanceof __SoraElement) return input;
    if (typeof input === 'number') return new __SoraElement(__nativeBridge.chWrapHandle(input | 0), docId);
    if (typeof input === 'string') {
      var s = input.replace(/^\s+|\s+$/g, '');
      if (s.charAt(0) === '<') return new __SoraElement(__nativeBridge.chFromHtml(input), docId);
      if (context) {
        var ctxId = docId;
        if (context instanceof __SoraElement) ctxId = context._id;
        else if (typeof context === 'number') ctxId = __nativeBridge.chWrapHandle(context);
        return new __SoraElement(__nativeBridge.chSelect(ctxId, input), docId);
      }
      return new __SoraElement(__nativeBridge.chSelect(docId, input), docId);
    }
    if (typeof input === 'object' && typeof input.length === 'number') {
      var hs = [];
      for (var i = 0; i < input.length; i++) {
        var item = input[i];
        if (typeof item === 'number') hs.push(item);
        else if (item instanceof __SoraElement) hs = hs.concat(__sora_handles(item._id));
      }
      return new __SoraElement(__nativeBridge.chFromHandles(JSON.stringify(hs)), docId);
    }
    return new __SoraElement(__nativeBridge.chEmpty(), docId);
  };
  $.text = function (el) {
    if (el === undefined || el === null) return __nativeBridge.chText(docId);
    if (el instanceof __SoraElement) return el.text();
    if (typeof el === 'number') return __nativeBridge.chText(__nativeBridge.chWrapHandle(el | 0));
    return __sora_htmlToText(String(el));
  };
  $.html = function (el) {
    if (el === undefined || el === null) return __nativeBridge.chHtml(docId);
    if (el instanceof __SoraElement) return el.html();
    if (typeof el === 'number') return __nativeBridge.chElementHtml(el | 0);
    return String(el);
  };
  $.load = function (html) { return __sora_makeQuery(__nativeBridge.chLoad(String(html || ''))); };
  $.root = function () { return new __SoraElement(docId, docId); };
  return $;
}
__sora_defineModule('cheerio', {
  load: function (html) { return __sora_makeQuery(__nativeBridge.chLoad(String(html || ''))); }
});
__sora_defineModule('htmlparser2', { Parser: function () {} });

// ------------------------------------------------------------------- dayjs
function __sora_pad(n, len) {
  var s = String(Math.abs(n));
  while (s.length < len) s = '0' + s;
  return s;
}
function __sora_dayjs(input) {
  var d;
  try {
    if (input === undefined || input === null) d = new Date();
    else if (input instanceof Date) d = new Date(input.getTime());
    else d = new Date(typeof input === 'number' ? input : String(input));
  } catch (e) { d = new Date(); }
  if (isNaN(d.getTime())) d = new Date();
  var inst = {
    __sora_dayjs: true,
    format: function (fmt) {
      fmt = fmt || 'YYYY-MM-DDTHH:mm:ssZ';
      return String(fmt)
        .replace(/YYYY/g, String(d.getFullYear()))
        .replace(/YY/g, __sora_pad(d.getFullYear() % 100, 2))
        .replace(/MM/g, __sora_pad(d.getMonth() + 1, 2))
        .replace(/DD/g, __sora_pad(d.getDate(), 2))
        .replace(/HH/g, __sora_pad(d.getHours(), 2))
        .replace(/hh/g, __sora_pad(d.getHours() % 12 || 12, 2))
        .replace(/mm/g, __sora_pad(d.getMinutes(), 2))
        .replace(/ss/g, __sora_pad(d.getSeconds(), 2))
        .replace(/SSS/g, __sora_pad(d.getMilliseconds(), 3))
        .replace(/A/g, d.getHours() < 12 ? 'AM' : 'PM')
        .replace(/Z/g, '+00:00');
    },
    valueOf: function () { return d.getTime(); },
    toISOString: function () { return d.toISOString(); },
    isValid: function () { return true; },
    unix: function () { return Math.floor(d.getTime() / 1000); },
    fromNow: function () { return ''; },
    locale: function () { return inst; },
    utc: function () { return inst; },
    add: function () { return inst; },
    subtract: function () { return inst; },
    diff: function () { return 0; },
    isBefore: function () { return false; },
    isAfter: function () { return false; },
    startOf: function () { return inst; },
    endOf: function () { return inst; },
    toDate: function () { return new Date(d.getTime()); }
  };
  return inst;
}
__sora_dayjs.extend = function () { return __sora_dayjs; };
__sora_dayjs.locale = function () { return __sora_dayjs; };
__sora_dayjs.utc = function () { return __sora_dayjs(); };
__sora_dayjs.unix = function (t) { return __sora_dayjs((t | 0) * 1000); };
__sora_dayjs.isDayjs = function (x) { return !!(x && x.__sora_dayjs); };
__sora_defineModule('dayjs', __sora_dayjs);

// ------------------------------------------------------------- url helpers
var __sora_urlencode = {
  encode: function (s) { try { return encodeURIComponent(String(s)); } catch (e) { return String(s); } },
  decode: function (s) { try { return decodeURIComponent(String(s)); } catch (e) { return String(s); } }
};
__sora_urlencode.default = __sora_urlencode;
__sora_defineModule('urlencode', __sora_urlencode);
globalThis.urlencode = __sora_urlencode;
if (typeof globalThis.encodeURI === 'undefined') globalThis.encodeURI = function (s) { return encodeURIComponent(String(s)).replace(/%3A/g, ':').replace(/%2F/g, '/'); };

// ------------------------------------------------------------------ modules
var __sora_novelStatus = {
  Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed',
  PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus',
  Complete: 'Completed', Finished: 'Completed', Hiatus: 'On Hiatus',
  Publishing_Finished: 'Publishing Finished', On_Hiatus: 'On Hiatus'
};
__sora_defineModule('@libs/novelStatus', { NovelStatus: __sora_novelStatus, default: __sora_novelStatus });
__sora_defineModule('@libs/filterInputs', {
  FilterTypes: { TextInput: 'Text', Picker: 'Picker', CheckboxGroup: 'Checkbox', Switch: 'Switch', ExcludableCheckboxGroup: 'XCheckbox' },
  Filters: {}
});
function __sora_isAbsoluteUrl(url) {
  if (!url) return false;
  if (url.indexOf('//') === 0) return true;
  if (url.indexOf('://') === -1) return false;
  if (url.indexOf('.') === -1) return false;
  if (url.indexOf('/') === -1) return false;
  if (url.indexOf(':') > url.indexOf('/')) return false;
  return url.indexOf('://') < url.indexOf('.');
}
__sora_isAbsoluteUrl.isUrlAbsolute = __sora_isAbsoluteUrl;
__sora_isAbsoluteUrl.default = __sora_isAbsoluteUrl;
__sora_defineModule('@libs/isAbsoluteUrl', __sora_isAbsoluteUrl);
__sora_defineModule('@libs/defaultCover', {
  defaultCover: 'https://github.com/AozoraItsuki/sorareader-plugins/blob/main/public/static/coverNotAvailable.webp?raw=true'
});
__sora_defineModule('@libs/aes', {});
__sora_defineModule('@libs/cookie', { set: function () {}, setFromResponse: function () {}, get: function () {}, flush: function () {}, removeSessionCookies: function () {} });
__sora_defineModule('@libs/webview', { solveCloudflare: function () { return Promise.resolve(''); }, solveCloudflareTurnstile: function () { return Promise.resolve(''); } });
__sora_defineModule('@libs/utils', {
  utf8ToBytes: function (s) { var out = []; for (var i = 0; i < String(s).length; i++) out.push(String(s).charCodeAt(i) & 255); return out; },
  bytesToUtf8: function (b) { var s = ''; for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i] & 255); return s; },
  encodeHtmlEntities: function (s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); },
  decodeHtmlEntities: function (s) { return __sora_htmlToText(String(s)); },
  getUserAgent: function () { return __nativeBridge.userAgent(); },
  Buffer: globalThis.Buffer || function () {}
});

// -------------------------------------------------------------- fetch shim
function __sora_unwrap(v) {
  var guard = 0;
  while (v && v.__sora_thenable && v.__sora_value !== undefined && guard++ < 32) v = v.__sora_value;
  return v;
}
function __sora_makeResponse(r) {
  var body = (r && r.body) || '';
  // Plain value: never resolves into itself when awaited.
  var plain = {
    ok: !!(r && r.ok), status: (r && r.status) || 0, url: (r && r.url) || '',
    headers: (r && r.headers) || {}, body: body,
    text: function () { return body; },
    json: function () { try { return JSON.parse(body); } catch (e) { return null; } },
    blob: function () { return body; },
    arrayBuffer: function () { return body; }
  };
  var res = {
    ok: plain.ok, status: plain.status, url: plain.url, headers: plain.headers, body: body,
    text: plain.text, json: plain.json, blob: plain.blob, arrayBuffer: plain.arrayBuffer,
    __sora_thenable: true,
    __sora_value: plain,
    then: function (onFulfilled, onRejected) {
      var out;
      try { out = typeof onFulfilled === 'function' ? onFulfilled(plain) : plain; }
      catch (e) { if (typeof onRejected === 'function') out = onRejected(e); else throw e; }
      return __sora_thenable(out);
    },
    catch: function () { return __sora_thenable(plain); }
  };
  return res;
}
function __sora_thenable(v) {
  v = __sora_unwrap(v);
  return {
    __sora_thenable: true,
    __sora_value: v,
    value: v,
    then: function (onFulfilled, onRejected) {
      var out;
      try { out = typeof onFulfilled === 'function' ? onFulfilled(v) : v; }
      catch (e) { if (typeof onRejected === 'function') out = onRejected(e); else throw e; }
      return __sora_thenable(out);
    },
    catch: function () { return __sora_thenable(v); }
  };
}
function __sora_fetchApi(url, init) {
  var initJson = null;
  if (init !== undefined && init !== null) {
    try { initJson = JSON.stringify(init); } catch (e) { initJson = null; }
  }
  var r;
  try { r = JSON.parse(__nativeBridge.fetch(String(url), initJson)); }
  catch (e) { r = { ok: false, status: 0, url: String(url), body: '', headers: {}, error: String(e) }; }
  return __sora_makeResponse(r);
}
function __sora_fetchText(url, init, encoding) { return __sora_fetchApi(url, init).text(); }
function __sora_fetchProto() { return null; }
__sora_defineModule('@libs/fetch', {
  fetchApi: __sora_fetchApi, fetchText: __sora_fetchText, fetchProto: __sora_fetchProto,
  downloadFile: function () { return Promise.resolve(); }, default: __sora_fetchApi
});
globalThis.fetch = __sora_fetchApi;
globalThis.fetchApi = __sora_fetchApi;
globalThis.fetchText = __sora_fetchText;

// ------------------------------------------------------------ storage shim
var __sora_storageData = {};
function __sora_makeStorage(id) {
  var prefix = id + '::';
  return {
    set: function (k, v) { __sora_storageData[prefix + k] = v; },
    get: function (k) {
      var key = prefix + k;
      return Object.prototype.hasOwnProperty.call(__sora_storageData, key) ? __sora_storageData[key] : undefined;
    },
    delete: function (k) { delete __sora_storageData[prefix + k]; },
    clearAll: function () { for (var key in __sora_storageData) { if (key.indexOf(prefix) === 0) delete __sora_storageData[key]; } },
    getAllKeys: function () {
      var out = [];
      for (var key in __sora_storageData) { if (key.indexOf(prefix) === 0) out.push(key.slice(prefix.length)); }
      return out;
    }
  };
}
function __sora_storageModule() {
  var s = __sora_makeStorage(__sora_currentPluginId);
  return { storage: s, localStorage: s, sessionStorage: s };
}
__sora_defineModule('@libs/storage', __sora_storageModule());

// ----------------------------------------------------------- misc globals
if (typeof globalThis.console === 'undefined') {
  globalThis.console = {
    log: function () { __nativeBridge.log('[plugin] ' + Array.prototype.join.call(arguments, ' ')); },
    warn: function () { __nativeBridge.log('[plugin warn] ' + Array.prototype.join.call(arguments, ' ')); },
    error: function () { __nativeBridge.log('[plugin error] ' + Array.prototype.join.call(arguments, ' ')); },
    debug: function () {}
  };
}
if (typeof globalThis.setTimeout === 'undefined') {
  globalThis.setTimeout = function (fn) { globalThis.__sora_jobs.push(fn); return 0; };
  globalThis.clearTimeout = function () {};
  globalThis.setInterval = function () { return 0; };
  globalThis.clearInterval = function () {};
}
if (typeof globalThis.Headers === 'undefined') {
  globalThis.Headers = function (init) {
    this._map = {};
    if (init) {
      if (typeof init.length === 'number') {
        for (var i = 0; i < init.length; i++) this._map[init[i][0]] = String(init[i][1]);
      } else { for (var k in init) this._map[k] = String(init[k]); }
    }
  };
  globalThis.Headers.prototype.set = function (k, v) { this._map[k] = String(v); };
  globalThis.Headers.prototype.append = globalThis.Headers.prototype.set;
  globalThis.Headers.prototype.get = function (k) { return this._map[k] === undefined ? null : this._map[k]; };
  globalThis.Headers.prototype.has = function (k) { return this._map[k] !== undefined; };
  globalThis.Headers.prototype.entries = function () {
    var self = this, out = [];
    for (var k in self._map) out.push([k, self._map[k]]);
    return out;
  };
  globalThis.Headers.prototype.toJSON = function () { return { _map: this._map }; };
}
if (typeof globalThis.URLSearchParams === 'undefined') {
  globalThis.URLSearchParams = function (init) {
    this._pairs = [];
    if (typeof init === 'string') {
      var parts = init.replace(/^\?/, '').split('&');
      for (var i = 0; i < parts.length; i++) {
        if (!parts[i]) continue;
        var idx = parts[i].indexOf('=');
        var k = idx < 0 ? parts[i] : parts[i].slice(0, idx);
        var v = idx < 0 ? '' : parts[i].slice(idx + 1);
        try { k = decodeURIComponent(k); v = decodeURIComponent(v); } catch (e) {}
        this._pairs.push([k, v]);
      }
    } else if (init) {
      for (var key in init) this._pairs.push([key, String(init[key])]);
    }
  };
  globalThis.URLSearchParams.prototype.append = function (k, v) { this._pairs.push([String(k), String(v)]); };
  globalThis.URLSearchParams.prototype.set = function (k, v) {
    var key = String(k);
    for (var i = this._pairs.length - 1; i >= 0; i--) if (this._pairs[i][0] === key) this._pairs.splice(i, 1);
    this._pairs.push([key, String(v)]);
  };
  globalThis.URLSearchParams.prototype.get = function (k) {
    for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === String(k)) return this._pairs[i][1];
    return null;
  };
  globalThis.URLSearchParams.prototype.toString = function () {
    var out = [];
    for (var i = 0; i < this._pairs.length; i++) {
      out.push(encodeURIComponent(this._pairs[i][0]) + '=' + encodeURIComponent(this._pairs[i][1]));
    }
    return out.join('&');
  };
  globalThis.URLSearchParams.prototype.toJSON = function () { return { _pairs: this._pairs }; };
}
if (typeof globalThis.URL === 'undefined') {
  globalThis.URL = function (url, base) {
    var u = String(url || '');
    var b = base ? String(base) : '';
    if (b && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(u) && u.indexOf('//') !== 0) {
      u = b.replace(/\/+$/, '') + '/' + u.replace(/^\/+/, '');
    }
    this.href = u;
    var q = u.indexOf('?');
    this.search = q < 0 ? '' : u.slice(q);
    this.searchParams = new globalThis.URLSearchParams(this.search);
    var hash = u.indexOf('#');
    var withoutHash = hash < 0 ? u : u.slice(0, hash);
    var schemeEnd = withoutHash.indexOf('://');
    var afterScheme = schemeEnd < 0 ? withoutHash : withoutHash.slice(schemeEnd + 3);
    var slash = afterScheme.indexOf('/');
    this.origin = schemeEnd < 0 ? '' : withoutHash.slice(0, schemeEnd + 3) + (slash < 0 ? afterScheme : afterScheme.slice(0, slash));
    this.pathname = slash < 0 ? '/' : afterScheme.slice(slash);
  };
  globalThis.URL.prototype.toString = function () { return this.href; };
}
if (typeof globalThis.TextDecoder === 'undefined') {
  globalThis.TextDecoder = function () {
    this.decode = function (v) { return v === undefined || v === null ? '' : String(v); };
  };
}
if (typeof globalThis.TextEncoder === 'undefined') {
  globalThis.TextEncoder = function () {
    this.encode = function (v) { return String(v === undefined || v === null ? '' : v); };
  };
}
if (typeof globalThis.AbortController === 'undefined') {
  globalThis.AbortController = function () {
    this.signal = { aborted: false };
    this.abort = function () { this.signal.aborted = true; };
  };
}
if (typeof globalThis.btoa === 'undefined') {
  var __sora_b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  globalThis.btoa = function (s) {
    var input = String(s), out = '', i = 0;
    while (i < input.length) {
      var a = input.charCodeAt(i++), b = input.charCodeAt(i++), c = input.charCodeAt(i++);
      out += __sora_b64.charAt(a >> 2);
      out += __sora_b64.charAt(((a & 3) << 4) | ((b || 0) >> 4));
      out += isNaN(b) ? '=' : __sora_b64.charAt(((b & 15) << 2) | ((c || 0) >> 6));
      out += isNaN(c) ? '=' : __sora_b64.charAt(c & 63);
    }
    return out;
  };
  globalThis.atob = function (s) {
    var input = String(s).replace(/=+$/, ''), out = '', i = 0;
    while (i < input.length) {
      var a = __sora_b64.indexOf(input.charAt(i++));
      var b = __sora_b64.indexOf(input.charAt(i++));
      var c = __sora_b64.indexOf(input.charAt(i++));
      var d = __sora_b64.indexOf(input.charAt(i++));
      out += String.fromCharCode((a << 2) | (b >> 4));
      if (!isNaN(c)) out += String.fromCharCode(((b & 15) << 4) | (c >> 2));
      if (!isNaN(d)) out += String.fromCharCode(((c & 3) << 6) | d);
    }
    return out;
  };
}

globalThis.require = function (name) {
  if (name === '@libs/storage') return __sora_storageModule();
  if (Object.prototype.hasOwnProperty.call(__sora_modules, name)) return __sora_modules[name];
  __nativeBridge.log('Missing module: ' + name);
  return {};
};

globalThis.module = { exports: {} };
globalThis.exports = globalThis.module.exports;

globalThis.__sora_loadPlugin = function (id, code) {
  var module = { exports: {} };
  var exports = module.exports;
  __sora_currentPluginId = String(id);
  try {
    var fn = new Function('require', 'module', 'exports', code + '\n;return module.exports.default || module.exports;');
    var plugin = fn(globalThis.require, module, exports);
    if (!plugin || typeof plugin !== 'object') throw new Error('Plugin did not export an object');
    if (!plugin.id) plugin.id = id;
    __sora_plugins[id] = plugin;
    return 'ok';
  } catch (e) {
    __nativeBridge.log('Load failed ' + id + ': ' + (e && e.stack ? e.stack : String(e)));
    throw e;
  }
};
globalThis.__sora_unloadPlugin = function (id) { delete __sora_plugins[id]; };

globalThis.__sora_meta = function (id) {
  var p = __sora_plugins[id];
  if (!p) return null;
  return JSON.stringify({
    id: p.id, name: p.name, site: p.site, lang: p.lang, version: p.version,
    iconUrl: p.iconUrl || p.icon || '', customJS: p.customJS || '', customCSS: p.customCSS || '',
    hasSettings: !!(p.hasSettings || p.pluginSettings)
  });
};
globalThis.__sora_defaultFilters = function (id) {
  var p = __sora_plugins[id];
  var filters = (p && p.filters) || {};
  var out = {};
  for (var key in filters) {
    try {
      var f = filters[key] || {};
      out[key] = { type: f.type, value: f.value };
    } catch (e) {}
  }
  return JSON.stringify(out);
};

globalThis.__sora_jobs = [];
globalThis.__sora_settled = {};
globalThis.queueMicrotask = function (fn) { globalThis.__sora_jobs.push(fn); };
if (typeof Promise !== 'undefined') {
  var _P = Promise;
  var _then = _P.prototype.then;
  _P.prototype.then = function (onFulfilled, onRejected) {
    return _then.call(this,
      typeof onFulfilled === 'function' ? function (v) {
        return new _P(function (res, rej) {
          globalThis.__sora_jobs.push(function () { try { res(onFulfilled(v)); } catch (e) { rej(e); } });
        });
      } : onFulfilled,
      typeof onRejected === 'function' ? function (e) {
        return new _P(function (res, rej) {
          globalThis.__sora_jobs.push(function () { try { res(onRejected(e)); } catch (err) { rej(err); } });
        });
      } : onRejected
    );
  };
}
globalThis.__sora_drainJobs = function () {
  var ran = 0;
  while (globalThis.__sora_jobs.length > 0 && ran < 5000) {
    var job = globalThis.__sora_jobs.shift();
    try { job(); } catch (e) { __nativeBridge.log('job: ' + e); }
    ran++;
  }
  return globalThis.__sora_jobs.length;
};

globalThis.__sora_call = function (id, method, argsJson) {
  var p = __sora_plugins[id];
  if (!p) throw new Error('Plugin not loaded: ' + id);
  var fn = p[method];
  if (typeof fn !== 'function') throw new Error('Missing method: ' + method);
  var args = JSON.parse(argsJson || '[]');
  var callId = id + ':' + method + ':' + Date.now() + ':' + Math.random();
  globalThis.__sora_settled[callId] = { done: false, value: null, error: null };
  try {
    var res = fn.apply(p, args);
    if (res && typeof res.then === 'function') {
      res.then(
        function (v) { globalThis.__sora_settled[callId] = { done: true, value: v, error: null }; },
        function (e) { globalThis.__sora_settled[callId] = { done: true, value: null, error: String(e && e.stack ? e.stack : e) }; }
      );
    } else {
      globalThis.__sora_settled[callId] = { done: true, value: res, error: null };
    }
  } catch (e) {
    globalThis.__sora_settled[callId] = { done: true, value: null, error: String(e && e.stack ? e.stack : e) };
  }
  return callId;
};
globalThis.__sora_poll = function (callId) {
  var s = globalThis.__sora_settled[callId];
  if (!s) return JSON.stringify({ done: true, error: 'missing call' });
  if (s.done) {
    delete globalThis.__sora_settled[callId];
    if (s.error) return JSON.stringify({ done: true, error: s.error });
    return JSON.stringify({ done: true, value: s.value });
  }
  return JSON.stringify({ done: false });
};
""".trimIndent()
    }
}
