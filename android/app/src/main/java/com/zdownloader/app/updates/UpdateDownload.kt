package com.zdownloader.app.updates

import com.zdownloader.app.domain.ContentRange
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object UpdateRange {
    /** An updater needs the complete advertised representation, never an unknown/changing total. */
    fun validate(header: String?, offset: Long, total: Long, contentLength: Long): Long {
        val range = ContentRange.parse(header) ?: throw UpdateException(UpdateError.RANGE)
        if (range.start != offset || range.total != total || range.end != total - 1 ||
            contentLength >= 0 && contentLength != total - offset) throw UpdateException(UpdateError.RANGE)
        return total - offset
    }

    fun strongEtag(value: String?): String? = value?.takeIf {
        it.length in 2..512 && it.startsWith('"') && it.endsWith('"') && it.none(Char::isISOControl)
    }
}

/** Blocking IO is run only on the updater's IO scope. The partial's length is the recovery offset. */
class UpdateDownloader(private val http: UpdateHttp) {
    fun download(
        asset: UpdateAsset, file: File, etag: String?,
        isPaused: () -> Boolean = { false },
        checkpoint: (String?) -> Unit,
        progress: (Long) -> Unit
    ) {
        var offset = if (file.isFile) file.length() else 0L
        if (offset > asset.bytes) { file.delete(); offset = 0 }
        if (offset == asset.bytes) return // Caller always performs full verification, including after 416.
        var validator = UpdateRange.strongEtag(etag)
        if (offset > 0 && validator == null) { FileOutputStream(file).use { it.fd.sync() }; offset = 0 }
        requireSpace(file, asset.bytes - offset)
        val headers = mutableMapOf("Accept" to "application/vnd.android.package-archive")
        if (offset > 0) { headers["Range"] = "bytes=$offset-"; headers["If-Range"] = validator!! }
        http.get(asset.url, headers).use { response ->
            if (response.code == 416) {
                if (file.length() == asset.bytes) return
                throw UpdateException(UpdateError.RANGE)
            }
            UpdateHttp.requireSuccess(response)
            if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                throw UpdateException(UpdateError.RANGE)
            val body = response.body ?: throw UpdateException(UpdateError.NETWORK)
            val length = body.contentLength()
            val incomingEtag = UpdateRange.strongEtag(response.header("ETag"))
            when (response.code) {
                206 -> {
                    UpdateRange.validate(response.header("Content-Range"), offset, asset.bytes, length)
                    if (offset > 0 && incomingEtag != validator) throw UpdateException(UpdateError.RANGE)
                }
                200 -> {
                    if (response.header("Content-Range") != null || length >= 0 && length != asset.bytes)
                        throw UpdateException(UpdateError.SIZE)
                    offset = 0 // If-Range mismatch or Range ignored: truncate, never append a 200.
                }
                else -> throw UpdateException(UpdateError.HTTP)
            }
            requireSpace(file, asset.bytes - offset)
            validator = incomingEtag
            // Truncate before changing the persisted representation validator.
            FileOutputStream(file, offset > 0).use { output ->
                output.fd.sync()
                checkpoint(validator)
                var written = offset
                progress(written)
                try {
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (isPaused()) throw java.io.InterruptedIOException("Update paused")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (written + count > asset.bytes) throw UpdateException(UpdateError.SIZE)
                            if (file.parentFile!!.usableSpace < count + 1024L * 1024) throw UpdateException(UpdateError.STORAGE)
                            output.write(buffer, 0, count)
                            written += count
                            progress(written)
                        }
                    }
                    if (written != asset.bytes) throw UpdateException(UpdateError.SIZE)
                } finally { output.fd.sync() }
            }
        }
    }

    private fun requireSpace(file: File, remaining: Long) {
        // Also reserve space for PackageInstaller's private copy and filesystem bookkeeping.
        if (file.parentFile!!.usableSpace < remaining + 16L * 1024 * 1024) throw UpdateException(UpdateError.STORAGE)
    }
}
