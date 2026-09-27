package io.omarchy.omasend.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object StorageUtils {

    const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024L // 10 GiB ceiling

    fun sanitizeFilename(raw: String): String {
        // Strip null bytes, slashes, backslashes, and control characters
        val cleanChars = raw.replace("\u0000", "")
            .replace('/', '_')
            .replace('\\', '_')
            .filter { it.isLetterOrDigit() || it in ".-_ " }
            .trim()

        return if (cleanChars.isEmpty() || cleanChars.startsWith(".") || cleanChars.contains("..")) {
            "file_${System.currentTimeMillis()}"
        } else {
            cleanChars.take(180)
        }
    }

    data class ChecksumResult(
        val sha256Hex: String,
        val md5Hex: String,
        val blake3Hex: String? = null
    )

    fun wipeMemory(buffer: ByteArray) {
        buffer.fill(0)
    }

    fun computeChecksums(inputStream: InputStream): ChecksumResult {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(65536)
        var read: Int
        try {
            while (inputStream.read(buffer).also { read = it } != -1) {
                sha256.update(buffer, 0, read)
                md5.update(buffer, 0, read)
            }
        } finally {
            wipeMemory(buffer)
        }
        return ChecksumResult(
            sha256Hex = sha256.digest().joinToString("") { "%02x".format(it) },
            md5Hex = md5.digest().joinToString("") { "%02x".format(it) }
        )
    }

    fun saveIncomingStream(
        context: Context,
        filename: String,
        inputStream: InputStream,
        totalBytes: Long,
        deadlineMs: Long = Long.MAX_VALUE,
        expectedBlake3: String? = null,
        expectedSha256: String? = null,
        expectedMd5: String? = null,
        onProgress: ((bytesRead: Long, total: Long) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (totalBytes <= 0 || totalBytes > MAX_FILE_SIZE) {
            return Pair(false, "Invalid or excessive file size ($totalBytes bytes)")
        }

        // Check available device storage before allocating (require at least file size + 64 MB buffer)
        val usableSpace = context.filesDir.usableSpace
        if (usableSpace > 0 && usableSpace < (totalBytes + 64L * 1024 * 1024)) {
            return Pair(false, "Insufficient storage space on device")
        }

        val cleanName = sanitizeFilename(filename)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmaSend")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Pair(false, "Failed to create MediaStore entry")

                val checksums = try {
                    resolver.openOutputStream(uri)?.use { out ->
                        pipeStreamWithChecksums(inputStream, out, totalBytes, deadlineMs, onProgress)
                    } ?: return Pair(false, "Failed to open MediaStore output stream")
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    throw e
                }

                // Verify cryptographic integrity
                if (expectedSha256 != null && !expectedSha256.equals(checksums.sha256Hex, ignoreCase = true)) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    return Pair(false, "File integrity failure: SHA-256 checksum mismatch")
                }
                if (expectedMd5 != null && !expectedMd5.equals(checksums.md5Hex, ignoreCase = true)) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    return Pair(false, "File integrity failure: MD5 checksum mismatch")
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                Pair(true, cleanName)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmaSend")
                if (!dir.exists()) dir.mkdirs()

                var target = File(dir, cleanName)
                // Strict path traversal defense: target MUST reside within dir
                if (!target.canonicalPath.startsWith(dir.canonicalPath)) {
                    return Pair(false, "Directory traversal detected")
                }

                if (target.exists()) {
                    val dot = cleanName.lastIndexOf('.')
                    val name = if (dot != -1) cleanName.substring(0, dot) else cleanName
                    val ext = if (dot != -1) cleanName.substring(dot) else ""
                    var counter = 1
                    while (target.exists() && counter < 1000) {
                        target = File(dir, "$name ($counter)$ext")
                        if (!target.canonicalPath.startsWith(dir.canonicalPath)) {
                            return Pair(false, "Directory traversal detected")
                        }
                        counter++
                    }
                }

                val checksums = try {
                    FileOutputStream(target).use { out ->
                        pipeStreamWithChecksums(inputStream, out, totalBytes, deadlineMs, onProgress)
                    }
                } catch (e: Exception) {
                    if (target.exists()) target.delete()
                    throw e
                }

                // Verify cryptographic integrity
                if (expectedSha256 != null && !expectedSha256.equals(checksums.sha256Hex, ignoreCase = true)) {
                    if (target.exists()) target.delete()
                    return Pair(false, "File integrity failure: SHA-256 checksum mismatch")
                }
                if (expectedMd5 != null && !expectedMd5.equals(checksums.md5Hex, ignoreCase = true)) {
                    if (target.exists()) target.delete()
                    return Pair(false, "File integrity failure: MD5 checksum mismatch")
                }

                Pair(true, target.name)
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown storage error")
        }
    }

    private fun pipeStreamWithChecksums(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        deadlineMs: Long,
        onProgress: ((bytesRead: Long, total: Long) -> Unit)?
    ): ChecksumResult {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(65536)
        var totalRead = 0L
        var read: Int
        var remaining = totalBytes

        try {
            while (remaining > 0) {
                if (System.currentTimeMillis() > deadlineMs) {
                    throw java.io.InterruptedIOException("File upload exceeded monotonic deadline")
                }
                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                read = input.read(buffer, 0, toRead)
                if (read == -1) break
                output.write(buffer, 0, read)
                sha256.update(buffer, 0, read)
                md5.update(buffer, 0, read)
                totalRead += read
                remaining -= read
                onProgress?.invoke(totalRead, totalBytes)
            }
            output.flush()
        } finally {
            wipeMemory(buffer)
        }
        return ChecksumResult(
            sha256Hex = sha256.digest().joinToString("") { "%02x".format(it) },
            md5Hex = md5.digest().joinToString("") { "%02x".format(it) }
        )
    }
}
