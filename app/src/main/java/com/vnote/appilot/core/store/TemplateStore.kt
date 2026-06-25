package com.vnote.appilot.core.store

import android.content.Context
import java.io.File

/**
 * Byte-identical blob persistence for the read/launch layers.
 *
 * Two kinds of opaque blobs live outside the structured [ConfigStore] JSON
 * because they are large and/or binary:
 *
 * - **Digit templates** — the per-glyph PNG bytes the read layer matches
 *   against. Persisted under `filesDir/templates/<key>.png`.
 * - **Captured-intent URIs** — the raw `Intent.toUri(...)` strings for
 *   captured shortcuts, kept as files under `filesDir/intents/<key>.uri`
 *   so very large captures never bloat the preferences store.
 *
 * Every round-trip is byte-for-byte exact: [saveTemplate] then [loadTemplate]
 * returns the same bytes, with no encoding transform.
 */
class TemplateStore(context: Context) {
    private val templatesDir: File = File(context.filesDir, TEMPLATES_DIR)
    private val intentsDir: File = File(context.filesDir, INTENTS_DIR)

    /** Writes [bytes] for [key] verbatim, overwriting any existing template. */
    fun saveTemplate(key: String, bytes: ByteArray) {
        write(fileFor(templatesDir, key, TEMPLATE_EXT), bytes)
    }

    /** Reads the template bytes for [key], or `null` when none is stored. */
    fun loadTemplate(key: String): ByteArray? =
        read(fileFor(templatesDir, key, TEMPLATE_EXT))

    /** Lists the keys of all currently persisted templates. */
    fun templateKeys(): List<String> = keysIn(templatesDir, TEMPLATE_EXT)

    /** Persists a captured-intent [uri] string for [key] as UTF-8 bytes. */
    fun saveIntentUri(key: String, uri: String) {
        write(fileFor(intentsDir, key, INTENT_EXT), uri.toByteArray(Charsets.UTF_8))
    }

    /** Reads the captured-intent uri for [key], or `null` when absent. */
    fun loadIntentUri(key: String): String? =
        read(fileFor(intentsDir, key, INTENT_EXT))?.toString(Charsets.UTF_8)

    /** Deletes every persisted template and captured-intent uri. */
    fun clear() {
        templatesDir.deleteRecursively()
        intentsDir.deleteRecursively()
    }

    private fun fileFor(dir: File, key: String, ext: String): File {
        require(key.isNotBlank()) { "key must not be blank" }
        require(key.none { it == '/' || it == '\\' || it == File.separatorChar }) {
            "key ($key) must not contain path separators"
        }
        return File(dir, "$key.$ext")
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun read(file: File): ByteArray? =
        if (file.exists()) file.readBytes() else null

    private fun keysIn(dir: File, ext: String): List<String> {
        val suffix = ".$ext"
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(suffix) }
            ?.map { it.name.removeSuffix(suffix) }
            ?.sorted()
            ?: emptyList()
    }

    private companion object {
        const val TEMPLATES_DIR = "templates"
        const val INTENTS_DIR = "intents"
        const val TEMPLATE_EXT = "png"
        const val INTENT_EXT = "uri"
    }
}
