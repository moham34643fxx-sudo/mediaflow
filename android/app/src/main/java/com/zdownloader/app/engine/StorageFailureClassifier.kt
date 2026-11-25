package com.zdownloader.app.engine

import android.system.ErrnoException
import android.system.OsConstants
import com.zdownloader.app.domain.ErrorCode
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** Classifies storage/provider failures without logging provider messages or user paths. */
internal object StorageFailureClassifier {
    private const val MAX_CAUSES = 12
    private val full = Regex("""\b(enospc|edquot|no space left on device|(disk )?quota exceeded)\b""")
    private val denied = Regex("""\b(eacces|eperm|permission denied|operation not permitted)\b""")
    private val unavailable = Regex("""\b(enoent|enodev|enotdir|erofs|estale|no such file or directory|destination unavailable)\b""")
    private val generalIo = Regex("""\b(eio|i/o error)\b""")

    fun classify(error: Throwable): ErrorCode? {
        val chain = causes(error)
        if (chain.any { it is SecurityException }) return ErrorCode.STORAGE_PERMISSION_ERROR

        val typed = chain.filterIsInstance<ErrnoException>()
        if (typed.isNotEmpty()) return typed.firstNotNullOfOrNull {
            when (it.errno) {
                OsConstants.ENOSPC, OsConstants.EDQUOT -> ErrorCode.INSUFFICIENT_STORAGE
                OsConstants.EACCES, OsConstants.EPERM -> ErrorCode.STORAGE_PERMISSION_ERROR
                OsConstants.ENOENT, OsConstants.ENODEV, OsConstants.ENOTDIR, OsConstants.EROFS,
                OsConstants.ESTALE -> ErrorCode.STORAGE_DESTINATION_UNAVAILABLE
                else -> null
            }
        }
        // Binder and some providers expose only the errno text in FileNotFoundException.
        val messages = chain.mapNotNull { it.message?.takeLast(1024)?.lowercase(Locale.ROOT) }
        return when {
            messages.any(full::containsMatchIn) -> ErrorCode.INSUFFICIENT_STORAGE
            messages.any(denied::containsMatchIn) -> ErrorCode.STORAGE_PERMISSION_ERROR
            messages.any(unavailable::containsMatchIn) -> ErrorCode.STORAGE_DESTINATION_UNAVAILABLE
            messages.any(generalIo::containsMatchIn) -> null
            chain.any { it is FileNotFoundException } -> ErrorCode.STORAGE_DESTINATION_UNAVAILABLE
            else -> null
        }
    }

    private fun causes(error: Throwable): List<Throwable> {
        val result = ArrayList<Throwable>(MAX_CAUSES)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && result.size < MAX_CAUSES && seen.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }

    fun terminalCause(error: Throwable): Throwable = causes(error).last()

    /** A small allow-listed type label; never include exception messages or paths. */
    fun safeType(error: Throwable): String = when (error) {
        is ErrnoException -> "ErrnoException"
        is SecurityException -> "SecurityException"
        is FileNotFoundException -> "FileNotFoundException"
        is IOException -> "IOException"
        is UnsupportedOperationException -> "UnsupportedOperationException"
        is IllegalStateException -> "IllegalStateException"
        is IllegalArgumentException -> "IllegalArgumentException"
        else -> "Other"
    }
}

internal object NativeMp3EncoderFailure {
    fun code(result: Int): ErrorCode = when (result) {
        -2 -> ErrorCode.DOWNLOAD_INTERRUPTED
        -3 -> ErrorCode.INSUFFICIENT_STORAGE
        -4 -> ErrorCode.FILE_WRITE_FAILED
        -5 -> ErrorCode.CONVERSION_FAILED
        -6 -> ErrorCode.STORAGE_PERMISSION_ERROR
        -7 -> ErrorCode.STORAGE_DESTINATION_UNAVAILABLE
        else -> ErrorCode.CONVERSION_FAILED
    }
}
