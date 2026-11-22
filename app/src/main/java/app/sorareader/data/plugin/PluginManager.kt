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
            val code = client.newCall(
                Request.Builder().url(bypassCache(url))
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("Failed to download plugin: HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty plugin body")
            }
            installFromCode(code, sourceUrl = url)
        }
    }

    suspend fun installFromCode(code: String, sourceUrl: String? = null): InstalledPlugin = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tempId = "temp_${System.nanoTime()}_${code.length}"
            val meta = runCatching {
                runtime.loadPlugin(tempId, code)
                runtime.getMeta(tempId)
            }.getOrElse { e ->
                error("Plugin failed to load: ${e.message ?: "unknown error"}")
            }.also { runtime.unloadPlugin(tempId) }
                ?: error("Plugin did not expose valid metadata")

            val pluginId = meta.id.ifBlank { error("Plugin id is blank") }
            if (pluginId == tempId || pluginId.startsWith("temp_")) {
                error("Plugin did not declare a valid id")
            }
            val index = readIndex()
            val existing = index.plugins.firstOrNull { it.id == pluginId }

            // Avoid downgrades when a version is advertised by both sides.
            if (existing != null && existing.version.isNotBlank() &&
                compareVersions(meta.version, existing.version) < 0
            ) {
                return@withLock existing
            }

            val installed = meta.copy(
                sourceUrl = sourceUrl ?: existing?.sourceUrl,
                customJS = meta.customJS ?: existing?.customJS,
                customCSS = meta.customCSS ?: existing?.customCSS,
                // Preserve install timestamp when reinstalling the same version.
                installedAt = if (existing != null && meta.version == existing.version) {
                    existing.installedAt
                } else {
                    System.currentTimeMillis()
                },
            )

            val dir = File(pluginsDir, sanitize(pluginId)).also { it.mkdirs() }
            val pluginFile = File(dir, "index.js")
            // Reload so a reinstalled/updated plugin never serves stale code.
            runtime.unloadPlugin(pluginId)
            pluginFile.writeText(code)

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
        runtime.loadPlugin(pluginId, file.readText())
    }

    private fun readIndex(): PluginIndex {
        if (!indexFile.exists()) return PluginIndex()
        return runCatching { json.decodeFromString(PluginIndex.serializer(), indexFile.readText()) }
            .getOrElse { PluginIndex() }
    }

    private fun writeIndex(index: PluginIndex) {
        indexFile.writeText(json.encodeToString(PluginIndex.serializer(), index))
    }

    /** Directory-safe plugin id. Never allows traversal, hidden names or empty ids. */
    private fun sanitize(id: String): String {
        val cleaned = id.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("\\.{2,}"), ".")
            .trim('.', '_', '-')
            .take(80)
        return cleaned.ifBlank { "plugin_${(id.hashCode().toLong() and 0x7fffffffL).toString(16)}" }
    }

    private fun bypassCache(url: String): String =
        if (url.contains('?')) "$url&t=${System.currentTimeMillis()}"
        else "$url?t=${System.currentTimeMillis()}"

    /** Numeric dot-version comparison; returns -1, 0 or 1. */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.replace(Regex("[^0-9.]"), "").split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.replace(Regex("[^0-9.]"), "").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l < r) return -1
            if (l > r) return 1
        }
        return 0
    }

    fun close() { runtime.close() }
}
