package com.zdownloader.app.status

import java.io.File

/** Exact, source-specific locations only; never walk chat folders or the whole volume. */
internal object StatusDirectories {
    fun mediaRoots(source: StatusSource): List<String> = when (source) {
        StatusSource.WHATSAPP -> listOf("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp/Media")
        StatusSource.BUSINESS -> listOf("Android/media/com.whatsapp.w4b/WhatsApp Business/Media", "WhatsApp Business/Media")
        StatusSource.IMPORTS -> emptyList()
    }

    fun relativePaths(source: StatusSource): List<String> =
        mediaRoots(source).flatMap { listOf("$it/.Statuses", "$it/Statuses") }

    fun initialDocumentId(source: StatusSource, exists: (String) -> Boolean = { false }): String? {
        val roots = mediaRoots(source)
        if (roots.isEmpty()) return null
        val preferred = roots.firstOrNull { exists(it) } ?: roots.firstOrNull() ?: return null
        return "primary:$preferred"
    }

    data class Readable(val folder: File, val files: List<File>)

    /** FUSE and scoped-storage rules remain authoritative, including on OEM devices. */
    fun readable(root: File, source: StatusSource): Readable? {
        val volume = root.canonicalFile
        for (path in relativePaths(source)) {
            val expected = File(volume, path)
            val folder = runCatching { expected.canonicalFile }.getOrNull() ?: continue
            if (folder != expected || !folder.isDirectory || !folder.canRead()) continue
            val files = folder.listFiles()?.asSequence()?.filter { file ->
                file.isFile && !file.name.startsWith('.') && runCatching { file.canonicalFile.parentFile == folder }.getOrDefault(false)
            }?.sortedByDescending { it.lastModified() }?.take(StatusRules.MAX_ITEMS + 1)?.toList() ?: continue
            // A listable directory alone is not proof that scoped storage permits its media.
            if (files.isNotEmpty()) {
                if (!runCatching { files.first().inputStream().use { it.read() }; true }.getOrDefault(false)) continue
            } else {
                val nomedia = File(folder, ".nomedia")
                if (nomedia.exists()) {
                    if (!runCatching { nomedia.inputStream().use { it.read() }; true }.getOrDefault(false)) continue
                } else {
                    val probe = File(folder, ".probe_${System.currentTimeMillis()}")
                    if (!runCatching { probe.createNewFile() && probe.delete() }.getOrDefault(false)) continue
                }
            }
            return Readable(folder, files)
        }
        return null
    }
}
