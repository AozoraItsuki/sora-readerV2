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
import java.util.Locale
import java.util.concurrent.TimeUnit

class PluginRuntime(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Closeable {
    private val quickJs: QuickJs = QuickJs.create()
    private var loadedPluginId: String? = null

    interface Bridge {
        fun fetch(url: String, initJson: String?): String
        fun log(msg: String)
    }

    init {
        installBootstrap()
    }

    private fun installBootstrap() {
        quickJs.set("__nativeBridge", Bridge::class.java, object : Bridge {
            override fun fetch(url: String, initJson: String?): String = nativeFetch(url, initJson)
            override fun log(msg: String) { println("[PluginRuntime] $msg") }
        })
        quickJs.evaluate(BOOTSTRAP, "bootstrap.js")
    }

    private fun nativeFetch(url: String, initJson: String?): String {
        return runCatching {
            val init = initJson?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it).jsonObject }
            val method = init?.get("method")?.jsonPrimitive?.contentOrNull?.uppercase(Locale.ROOT) ?: "GET"
            val headers = init?.get("headers")?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
            val bodyStr = init?.get("body")?.jsonPrimitive?.contentOrNull
            val reqBody = if (bodyStr != null && method != "GET" && method != "HEAD") {
                bodyStr.toRequestBody((headers["Content-Type"] ?: headers["content-type"] ?: "application/json").toMediaTypeOrNull())
            } else null
            val req = Request.Builder().url(url).method(method, reqBody).headers(headers.toHeaders()).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val respHeaders = resp.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                val obj = JsonObject(mapOf(
                    "ok" to JsonPrimitive(resp.isSuccessful),
                    "status" to JsonPrimitive(resp.code),
                    "url" to JsonPrimitive(resp.request.url.toString()),
                    "body" to JsonPrimitive(body),
                    "headers" to JsonObject(respHeaders.mapValues { JsonPrimitive(it.value) }),
                ))
                obj.toString()
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

    fun loadPlugin(pluginId: String, rawCode: String) {
        if (loadedPluginId == pluginId) return
        val escapedId = pluginId.replace("\\", "\\\\").replace("'", "\\'")
        quickJs.evaluate("globalThis.__sora_loadPlugin('$escapedId', ${jsonString(rawCode)});", "$pluginId.js")
        loadedPluginId = pluginId
    }

    fun unloadPlugin(pluginId: String) {
        val escapedId = pluginId.replace("\\", "\\\\").replace("'", "\\'")
        quickJs.evaluate("globalThis.__sora_unloadPlugin('$escapedId');", "unload.js")
        if (loadedPluginId == pluginId) loadedPluginId = null
    }

    fun getMeta(pluginId: String): InstalledPlugin? = runCatching {
        val raw = quickJs.evaluate("globalThis.__sora_meta('${esc(pluginId)}')", "meta.js") as? String ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        InstalledPlugin(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: pluginId,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: pluginId,
            site = obj["site"]?.jsonPrimitive?.contentOrNull ?: "",
            lang = obj["lang"]?.jsonPrimitive?.contentOrNull ?: "en",
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "1.0.0",
            iconUrl = obj["iconUrl"]?.jsonPrimitive?.contentOrNull ?: obj["icon"]?.jsonPrimitive?.contentOrNull,
            hasSettings = obj["hasSettings"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }.getOrNull()

    fun popularNovels(pluginId: String, page: Int, showLatest: Boolean): List<PluginNovelItem> {
        val raw = callJson(pluginId, "popularNovels", listOf(JsonPrimitive(page), JsonObject(mapOf("showLatestNovels" to JsonPrimitive(showLatest), "filters" to JsonObject(emptyMap())))))
        return parseNovelItems(raw)
    }

    fun searchNovels(pluginId: String, searchTerm: String, page: Int): List<PluginNovelItem> {
        val raw = callJson(pluginId, "searchNovels", listOf(JsonPrimitive(searchTerm), JsonPrimitive(page)))
        return parseNovelItems(raw)
    }

    fun parseNovel(pluginId: String, novelPath: String): PluginSourceNovel {
        val raw = callJson(pluginId, "parseNovel", listOf(JsonPrimitive(novelPath)))
        val obj = json.parseToJsonElement(raw).jsonObject
        return PluginSourceNovel(
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            path = obj["path"]?.jsonPrimitive?.contentOrNull ?: novelPath,
            cover = obj["cover"]?.jsonPrimitive?.contentOrNull,
            genres = obj["genres"]?.let { elementToString(it) },
            summary = obj["summary"]?.jsonPrimitive?.contentOrNull,
            author = obj["author"]?.jsonPrimitive?.contentOrNull,
            artist = obj["artist"]?.jsonPrimitive?.contentOrNull,
            status = obj["status"]?.jsonPrimitive?.contentOrNull,
            chapters = obj["chapters"]?.jsonArray?.map { parseChapterItem(it.jsonObject) } ?: emptyList(),
            totalPages = obj["totalPages"]?.jsonPrimitive?.intOrNull ?: 1,
        )
    }

    fun parseChapter(pluginId: String, chapterPath: String): String {
        val raw = callJson(pluginId, "parseChapter", listOf(JsonPrimitive(chapterPath)))
        val el = json.parseToJsonElement(raw)
        return when {
            el is JsonPrimitive -> el.contentOrNull ?: ""
            else -> el.toString()
        }
    }

    private fun callJson(pluginId: String, method: String, args: List<JsonElement>): String {
        val argsJson = JsonArray(args).toString()
        val callId = quickJs.evaluate("globalThis.__sora_call('${esc(pluginId)}', '${esc(method)}', ${jsonString(argsJson)})", "call-$method.js") as? String
            ?: error("No callId")
        repeat(2000) {
            quickJs.evaluate("globalThis.__sora_drainJobs()", "drain.js")
            val poll = quickJs.evaluate("globalThis.__sora_poll('${esc(callId)}')", "poll.js") as? String ?: return "null"
            val obj = json.parseToJsonElement(poll).jsonObject
            if (obj["done"]?.jsonPrimitive?.booleanOrNull == true) {
                val err = obj["error"]?.jsonPrimitive?.contentOrNull
                if (err != null) error("Plugin $pluginId.$method failed: $err")
                val v = obj["value"] ?: JsonNull
                return v.toString()
            }
            Thread.sleep(5)
        }
        error("Plugin $pluginId.$method timed out")
    }

    private fun parseNovelItems(raw: String): List<PluginNovelItem> {
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse { return emptyList() }
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PluginNovelItem(name = name, path = path, cover = obj["cover"]?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun parseChapterItem(obj: JsonObject): PluginChapterItem = PluginChapterItem(
        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
        path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "",
        chapterNumber = obj["chapterNumber"]?.jsonPrimitive?.doubleOrNull?.toFloat()
            ?: obj["chapterNumber"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        releaseTime = obj["releaseTime"]?.jsonPrimitive?.contentOrNull,
        page = obj["page"]?.jsonPrimitive?.contentOrNull,
    )

    private fun elementToString(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.contentOrNull ?: ""
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        else -> ""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    private fun jsonString(s: String): String = JsonPrimitive(s).toString()

    override fun close() {
        runCatching { quickJs.close() }
    }

    companion object {
        private val BOOTSTRAP = """
const __sora_plugins = {};
const __sora_modules = {};

function __sora_defineModule(name, mod) { __sora_modules[name] = mod; }

function __sora_htmlToText(html) {
  if (!html) return '';
  return String(html)
    .replace(/<br\s*\/?>(\s*)/gi, '\n')
    .replace(/<\/(p|div|li|h1|h2|h3|h4|h5|h6|tr)>/gi, '\n')
    .replace(/<li[^>]*>/gi, '\n- ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\n{3,}/g, '\n\n').replace(/[ \t]+/g, ' ').trim();
}

class __SoraElement {
  constructor(html, selector, doc) {
    this._html = html || '';
    this._selector = selector || '';
    this._doc = doc || this._html;
  }
  _all() { return __sora_select(this._doc || this._html, this._selector); }
  first() { const a = this._all(); return new __SoraElement(a[0] || '', this._selector, this._doc); }
  last() { const a = this._all(); return new __SoraElement(a[a.length-1] || '', this._selector, this._doc); }
  eq(i) { const a = this._all(); const idx = i < 0 ? a.length + i : i; return new __SoraElement(a[idx] || '', this._selector, this._doc); }
  find(sel) { return new __SoraElement(this._html, sel, this._html); }
  text() {
    if (!this._selector) return __sora_htmlToText(this._html);
    return this._all().map(h => __sora_htmlToText(h)).join(' ').trim();
  }
  html() {
    if (!this._selector) return this._html;
    const a = this._all(); return a[0] || '';
  }
  attr(name) {
    const h = this._selector ? (this._all()[0] || '') : this._html;
    const m = h.match(new RegExp(name + '\\s*=\\s*(?:\"([^\"]*)\"|\'([^\']*)\'|([^\s>]+))', 'i'));
    return m ? (m[1] || m[2] || m[3] || '') : undefined;
  }
  hasClass(cls) { const c = this.attr('class') || ''; return (' ' + c + ' ').indexOf(' ' + cls + ' ') >= 0; }
  next() { return new __SoraElement('', this._selector, this._doc); }
  prev() { return new __SoraElement('', this._selector, this._doc); }
  parent() { return new __SoraElement('', this._selector, this._doc); }
  children(sel) { return sel ? this.find(sel) : this; }
  contents() { return this; }
  remove() { return this; }
  clone() { return new __SoraElement(this._html, this._selector, this._doc); }
  get(i) { const a = this._all(); return a[i]; }
  toArray() { return this._all(); }
  map(fn) { const a = this._all(); const out = []; for (let i=0;i<a.length;i++){ out.push(fn(i, new __SoraElement(a[i], '', a[i]))); } return out; }
  each(fn) { const a = this._all(); for (let i=0;i<a.length;i++){ fn(i, new __SoraElement(a[i], '', a[i])); } return this; }
}

function __sora_select(html, selector) {
  if (!selector) return [html];
  const parts = selector.split(',').map(s => s.trim()).filter(Boolean);
  let out = [];
  for (const part of parts) out = out.concat(__sora_selectOne(html, part));
  return out;
}

function __sora_selectOne(html, sel) {
  // very small selector subset: tag, .class, #id, tag.class, [attr=value], descendant space
  const chain = sel.split(/\s+/).filter(Boolean);
  let contexts = [html];
  for (const token of chain) {
    let next = [];
    for (const ctx of contexts) next = next.concat(__sora_matchToken(ctx, token));
    contexts = next;
  }
  return contexts;
}

function __sora_matchToken(html, token) {
  let m;
  let tag = '*';
  let cls = null, id = null, attrName = null, attrVal = null;
  m = token.match(/^([a-zA-Z0-9_-]+|\*)/); if (m) tag = m[1].toLowerCase();
  m = token.match(/\.([a-zA-Z0-9_-]+)/); if (m) cls = m[1];
  m = token.match(/#([a-zA-Z0-9_-]+)/); if (m) id = m[1];
  m = token.match(/\[([a-zA-Z0-9_-]+)(?:=["']?([^"'\]]+)["']?)?\]/); if (m) { attrName = m[1]; attrVal = m[2]; }
  const tagPattern = tag === '*' ? '[a-zA-Z0-9]+' : tag.replace(/[.*+?^${}()|[\]\\]/g, '\\${'$'}&');
  const re = new RegExp('<' + tagPattern + '\\b([^>]*)>([\\s\\S]*?)<\\/' + tagPattern + '>', 'gi');
  const out = [];
  let match;
  while ((match = re.exec(html)) !== null) {
    const attrs = match[1] || '';
    const full = match[0];
    if (cls && !(new RegExp('class\\s*=\\s*(?:["\'][^"\']*\\b' + cls + '\\b[^"\']*["\']|[^\\s>]*\\b' + cls + '\\b[^\\s>]*)', 'i').test(attrs))) continue;
    if (id && !(new RegExp('id\\s*=\\s*(?:["\']' + id + '["\']|' + id + '\\b)', 'i').test(attrs))) continue;
    if (attrName) {
      const am = attrs.match(new RegExp(attrName + '\\s*=\\s*(?:\"([^\"]*)\"|\'([^\']*)\'|([^\s>]+))', 'i'));
      if (!am) continue;
      if (attrVal != null && (am[1]||am[2]||am[3]) != attrVal) continue;
    }
    out.push(full);
  }
  // self-closing/img/input/meta
  const selfRe = new RegExp('<' + tagPattern + '\\b([^>]*)\/?>', 'gi');
  while ((match = selfRe.exec(html)) !== null) {
    const attrs = match[1] || '';
    const full = match[0];
    if (/<\/[a-zA-Z]/.test(full)) continue;
    if (cls && !(new RegExp('class\\s*=\\s*(?:["\'][^"\']*\\b' + cls + '\\b[^"\']*["\']|[^\\s>]*\\b' + cls + '\\b[^\\s>]*)', 'i').test(attrs))) continue;
    if (id && !(new RegExp('id\\s*=\\s*(?:["\']' + id + '["\']|' + id + '\\b)', 'i').test(attrs))) continue;
    if (attrName) {
      const am = attrs.match(new RegExp(attrName + '\\s*=\\s*(?:\"([^\"]*)\"|\'([^\']*)\'|([^\s>]+))', 'i'));
      if (!am) continue;
      if (attrVal != null && (am[1]||am[2]||am[3]) != attrVal) continue;
    }
    if (out.indexOf(full) < 0) out.push(full);
  }
  return out;
}

function __sora_cheerio_load(html) {
  const doc = String(html || '');
  const $ = function(sel) {
    if (typeof sel === 'string' && sel.trim().startsWith('<')) return new __SoraElement(sel, '', sel);
    return new __SoraElement(doc, sel, doc);
  };
  $.text = (el) => new __SoraElement(el || '', '', el || '').text();
  $.html = (el) => el ? String(el) : doc;
  $.load = __sora_cheerio_load;
  return $;
}

__sora_defineModule('cheerio', { load: __sora_cheerio_load });
__sora_defineModule('htmlparser2', { Parser: function(){} });
__sora_defineModule('@libs/novelStatus', { NovelStatus: { Unknown:'Unknown', Ongoing:'Ongoing', Completed:'Completed', Licensed:'Licensed', PublishingFinished:'Publishing Finished', Cancelled:'Cancelled', OnHiatus:'On Hiatus' } });
__sora_defineModule('@libs/filterInputs', { FilterTypes: { TextInput:'Text', Picker:'Picker', CheckboxGroup:'Checkbox', Switch:'Switch', ExcludableCheckboxGroup:'XCheckbox' } });
__sora_defineModule('@libs/isAbsoluteUrl', { isUrlAbsolute: function(url){ if(!url) return false; if(url.indexOf('//')===0) return true; if(url.indexOf('://')===-1) return false; if(url.indexOf('.')===-1) return false; if(url.indexOf('/')===-1) return false; if(url.indexOf(':')>url.indexOf('/')) return false; if(url.indexOf('://')<url.indexOf('.')) return true; return false; }, default: function(url){ return this.isUrlAbsolute(url); } });
__sora_defineModule('@libs/defaultCover', { defaultCover: '' });
__sora_defineModule('dayjs', function(){ return { format: function(){ return ''; } }; });
__sora_defineModule('urlencode', { encode: function(s){ return encodeURIComponent(String(s)); }, decode: function(s){ try{return decodeURIComponent(String(s));}catch(e){return String(s);} } });
__sora_defineModule('@libs/storage', { storage: { get:()=>undefined, set:()=>{}, delete:()=>{}, clearAll:()=>{}, getAllKeys:()=>[] }, localStorage: { get:()=>undefined }, sessionStorage: { get:()=>undefined } });

function __sora_makeResponse(r) {
  const body = r.body || '';
  return {
    ok: !!r.ok, status: r.status || 0, url: r.url || '', headers: r.headers || {},
    text: function() { return body; },
    json: function() { try { return JSON.parse(body); } catch(e) { return null; } },
    body: body,
  };
}

function __sora_fetchApi(url, init) {
  const r = JSON.parse(__nativeBridge.fetch(String(url), init ? JSON.stringify(init) : null));
  const resp = __sora_makeResponse(r);
  // Sync API for MVP runtime; await on non-Promise values is harmless.
  resp.text = function() { return r.body || ''; };
  resp.json = function() { try { return JSON.parse(r.body || 'null'); } catch(e) { return null; } };
  return resp;
}
function __sora_fetchText(url, init, encoding) {
  const r = JSON.parse(__nativeBridge.fetch(String(url), init ? JSON.stringify(init) : null));
  return r.body || '';
}
function __sora_fetchProto(protoInit, url, init) { return null; }
__sora_defineModule('@libs/fetch', { fetchApi: __sora_fetchApi, fetchText: __sora_fetchText, fetchProto: __sora_fetchProto });

globalThis.require = function(name) {
  if (__sora_modules[name]) return __sora_modules[name];
  if (name === '@libs/storage') return { storage: { get:()=>undefined, set:()=>{}, delete:()=>{}, clearAll:()=>{}, getAllKeys:()=>[] }, localStorage:{get:()=>undefined}, sessionStorage:{get:()=>undefined} };
  __nativeBridge.log('Missing module: ' + name);
  return {};
};

globalThis.module = { exports: {} };
globalThis.exports = globalThis.module.exports;

globalThis.__sora_loadPlugin = function(id, code) {
  const module = { exports: {} };
  const exports = module.exports;
  try {
    const fn = new Function('require', 'module', 'exports', code + '\n;return module.exports.default || module.exports;');
    const plugin = fn(globalThis.require, module, exports);
    if (plugin && !plugin.id) plugin.id = id;
    __sora_plugins[id] = plugin;
    return 'ok';
  } catch(e) {
    __nativeBridge.log('Load failed ' + id + ': ' + (e && e.stack ? e.stack : String(e)));
    throw e;
  }
};

globalThis.__sora_unloadPlugin = function(id) { delete __sora_plugins[id]; };

globalThis.__sora_meta = function(id) {
  const p = __sora_plugins[id];
  if (!p) return null;
  return JSON.stringify({ id: p.id, name: p.name, site: p.site, lang: p.lang, version: p.version, iconUrl: p.iconUrl || p.icon, hasSettings: !!p.hasSettings });
};

globalThis.__sora_jobs = [];
globalThis.__sora_settled = {};
globalThis.queueMicrotask = function(fn){ globalThis.__sora_jobs.push(fn); };
// Patch Promise to route callbacks through our drainable queue (MVP: cash-quickjs has no job pump API).
if (typeof Promise !== 'undefined') {
  const _P = Promise;
  const _then = _P.prototype.then;
  _P.prototype.then = function(onFulfilled, onRejected) {
    return _then.call(this,
      typeof onFulfilled === 'function' ? function(v){ const args=arguments; return new _P(function(res,rej){ globalThis.__sora_jobs.push(function(){ try{ res(onFulfilled(v)); }catch(e){ rej(e); } }); }); } : onFulfilled,
      typeof onRejected === 'function' ? function(e){ return new _P(function(res,rej){ globalThis.__sora_jobs.push(function(){ try{ res(onRejected(e)); }catch(err){ rej(err); } }); }); } : onRejected
    );
  };
}
globalThis.__sora_drainJobs = function() {
  let ran = 0;
  while (globalThis.__sora_jobs.length > 0 && ran < 5000) {
    const job = globalThis.__sora_jobs.shift();
    try { job(); } catch(e) { __nativeBridge.log('job err: ' + e); }
    ran++;
  }
  return globalThis.__sora_jobs.length;
};

globalThis.__sora_call = function(id, method, argsJson) {
  const p = __sora_plugins[id];
  if (!p) throw new Error('Plugin not loaded: ' + id);
  const fn = p[method];
  if (typeof fn !== 'function') throw new Error('Missing method: ' + method);
  const args = JSON.parse(argsJson || '[]');
  const callId = id + ':' + method + ':' + Date.now() + ':' + Math.random();
  globalThis.__sora_settled[callId] = { done: false, value: null, error: null };
  try {
    const res = fn.apply(p, args);
    if (res && typeof res.then === 'function') {
      res.then(function(v){ globalThis.__sora_settled[callId] = { done: true, value: v, error: null }; },
               function(e){ globalThis.__sora_settled[callId] = { done: true, value: null, error: String(e && e.stack ? e.stack : e) }; });
    } else {
      globalThis.__sora_settled[callId] = { done: true, value: res, error: null };
    }
  } catch(e) {
    globalThis.__sora_settled[callId] = { done: true, value: null, error: String(e && e.stack ? e.stack : e) };
  }
  return callId;
};

globalThis.__sora_poll = function(callId) {
  const s = globalThis.__sora_settled[callId];
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
