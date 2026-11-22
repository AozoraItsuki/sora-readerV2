package app.sorareader.data.plugin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class PluginManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    private val mutex = Mutex()
    private val runtime = PluginRuntime(client = client, json = json)
    private val pluginsDir: File get() = File(context.filesDir, "plugins").also { it.mkdirs() }
    private val indexFile: File get() = File(pluginsDir, "index.json")

    suspend fun listInstalled(): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex().plugins }
    }

    suspend fun installFromUrl(url: String): InstalledPlugin = withContext(Dispatchers.IO) {
        mutex.withLock {
            val code = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Failed to download plugin: HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty plugin body")
            }
            installFromCode(code, sourceUrl = url)
        }
    }

    suspend fun installFromCode(code: String, sourceUrl: String? = null): InstalledPlugin = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tempId = "temp_${System.currentTimeMillis()}"
            runtime.loadPlugin(tempId, code)
            val meta = runtime.getMeta(tempId) ?: error("Plugin did not expose valid metadata")
            runtime.unloadPlugin(tempId)
            val pluginId = meta.id.ifBlank { error("Plugin id is blank") }
            val dir = File(pluginsDir, sanitize(pluginId)).also { it.mkdirs() }
            File(dir, "index.js").writeText(code)
            val installed = meta.copy(sourceUrl = sourceUrl, installedAt = System.currentTimeMillis())
            val index = readIndex()
            val updated = index.plugins.filterNot { it.id == pluginId } + installed
            writeIndex(PluginIndex(updated.sortedBy { it.name.lowercase() }))
            installed
        }
    }

    suspend fun uninstall(pluginId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runtime.unloadPlugin(pluginId)
            File(pluginsDir, sanitize(pluginId)).deleteRecursively()
            val index = readIndex()
            writeIndex(PluginIndex(index.plugins.filterNot { it.id == pluginId }))
        }
    }

    suspend fun getRuntime(pluginId: String): PluginRuntime = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(pluginId)
            runtime
        }
    }

    suspend fun <T> withPlugin(pluginId: String, block: (PluginRuntime) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(pluginId)
            block(runtime)
        }
    }

    private fun ensureLoaded(pluginId: String) {
        val dir = File(pluginsDir, sanitize(pluginId))
        val file = File(dir, "index.js")
        if (!file.exists()) error("Plugin not installed: $pluginId")
        val code = file.readText()
        runtime.loadPlugin(pluginId, code)
    }

    private fun readIndex(): PluginIndex {
        if (!indexFile.exists()) return PluginIndex()
        return runCatching { json.decodeFromString(PluginIndex.serializer(), indexFile.readText()) }.getOrElse { PluginIndex() }
    }

    private fun writeIndex(index: PluginIndex) {
        indexFile.writeText(json.encodeToString(PluginIndex.serializer(), index))
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    fun close() { runtime.close() }
}
