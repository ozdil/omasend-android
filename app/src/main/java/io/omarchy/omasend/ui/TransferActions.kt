package io.omarchy.omasend.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.model.TransferProgressState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Sending to a peer: encrypted QUIC for QR-paired devices, legacy HTTP otherwise.

suspend fun sendMultipleUrisToPeer(
    context: Context,
    app: OmaSendApp,
    peer: DiscoveredPeer,
    uris: List<Uri>,
    onState: (TransferProgressState) -> Unit
) {
    if (uris.isEmpty()) return
    // Phase 2: QR-paired devices get the encrypted, resumable QUIC transport.
    if (io.omarchy.omasend.network.CoreNode.trySendUris(context, app, peer, uris, onState)) return
    if (uris.size == 1) {
        sendFileUriToPeer(context, app, peer, uris.first(), onState)
        return
    }
    withContext(Dispatchers.IO) {
        try {
            data class PreparedUri(val uri: Uri, val name: String, val size: Long)
            val prepared = mutableListOf<PreparedUri>()
            for (uri in uris) {
                var fileName = "file"
                var fileSize = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "file"
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
                if (fileSize <= 0) {
                    fileSize = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                }
                prepared.add(PreparedUri(uri, fileName, fileSize))
            }

            val summaryLabel = OmaI18n.f("n_files", prepared.size)
            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Requesting(peer.name, summaryLabel))
            }

            val fileInfos = prepared.map { TransferFileInfo(it.name, it.size) }
            val requestResult = app.client.sendTransferRequestWithStatus(
                targetIp = peer.ip,
                targetPort = peer.port,
                files = fileInfos,
                targetOmaId = peer.omaId
            )

            val (token, initialStatus) = requestResult.getOrElse {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error(it.message ?: OmaI18n.t("err_request_failed")))
                }
                return@withContext
            }

            if (initialStatus != "ACCEPTED") {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.WaitingConsent(peer.name))
                }
                val decisionResult = app.client.pollDecision(peer.ip, peer.port, token)
                if (decisionResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Error(decisionResult.exceptionOrNull()?.message ?: OmaI18n.t("err_rejected")))
                    }
                    return@withContext
                }
            }

            for ((index, item) in prepared.withIndex()) {
                val inputStream = context.contentResolver.openInputStream(item.uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Error(OmaI18n.f("err_open_stream", item.name)))
                    }
                    return@withContext
                }
                val displayLabel = "[${index + 1}/${prepared.size}] ${item.name}"
                var lastProgressUiMs = 0L
                val uploadResult = app.client.uploadFileStreamWithMetrics(
                    targetIp = peer.ip,
                    targetPort = peer.port,
                    token = token,
                    filename = item.name,
                    totalBytes = item.size,
                    inputStream = inputStream
                ) { bytes: Long, total: Long, pct: Int, speedMBps: Double, etaSeconds: Long ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUiMs >= 64L || bytes >= total) {
                        lastProgressUiMs = now
                        scopeLaunchMain {
                            onState(TransferProgressState.Transferring(true, peer.name, displayLabel, bytes, total, pct, speedMBps, etaSeconds))
                        }
                    }
                }
                if (uploadResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Error(uploadResult.exceptionOrNull()?.message ?: OmaI18n.t("err_upload_failed")))
                    }
                    return@withContext
                }
            }

            withContext(Dispatchers.Main) {
                app.soundEngine.playSendSound()
                app.hapticController.onDropBounce()
                onState(TransferProgressState.Success(OmaI18n.f("n_files_sent", prepared.size)))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Error(e.message ?: OmaI18n.t("err_transfer_failed")))
            }
        }
    }
}

suspend fun sendFileUriToPeer(
    context: Context,
    app: OmaSendApp,
    peer: DiscoveredPeer,
    uri: Uri,
    onState: (TransferProgressState) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            var fileName = "file"
            var fileSize = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "file"
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            if (fileSize <= 0) {
                fileSize = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
            }

            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Requesting(peer.name, fileName))
            }

            val fileInfo = TransferFileInfo(fileName, fileSize)
            val requestResult = app.client.sendTransferRequestWithStatus(
                targetIp = peer.ip,
                targetPort = peer.port,
                files = listOf(fileInfo),
                targetOmaId = peer.omaId
            )

            val (token, initialStatus) = requestResult.getOrElse {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error(it.message ?: OmaI18n.t("err_request_failed")))
                }
                return@withContext
            }

            // The receiver may accept immediately (own device); otherwise wait for its decision.
            if (initialStatus != "ACCEPTED") {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.WaitingConsent(peer.name))
                }

                val decisionResult = app.client.pollDecision(peer.ip, peer.port, token)
                if (decisionResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Error(decisionResult.exceptionOrNull()?.message ?: OmaI18n.t("err_rejected")))
                    }
                    return@withContext
                }
            }

            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error(OmaI18n.f("err_open_stream", fileName)))
                }
                return@withContext
            }

            var lastProgressUiMs = 0L
            val uploadResult = app.client.uploadFileStreamWithMetrics(
                targetIp = peer.ip,
                targetPort = peer.port,
                token = token,
                filename = fileName,
                totalBytes = fileSize,
                inputStream = inputStream
            ) { bytes: Long, total: Long, pct: Int, speedMBps: Double, etaSeconds: Long ->
                val now = System.currentTimeMillis()
                if (now - lastProgressUiMs >= 64L || bytes >= total) {
                    lastProgressUiMs = now
                    scopeLaunchMain {
                        onState(TransferProgressState.Transferring(true, peer.name, fileName, bytes, total, pct, speedMBps, etaSeconds))
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (uploadResult.isSuccess) {
                    app.soundEngine.playSendSound()
                    app.hapticController.onDropBounce()
                    onState(TransferProgressState.Success(OmaI18n.f("file_sent", fileName)))
                } else {
                    onState(TransferProgressState.Error(uploadResult.exceptionOrNull()?.message ?: OmaI18n.t("err_upload_failed")))
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Error(e.message ?: OmaI18n.t("err_transfer_failed")))
            }
        }
    }
}

suspend fun sendClipboardToPeer(
    context: Context,
    app: OmaSendApp,
    peer: DiscoveredPeer,
    onState: (TransferProgressState) -> Unit
) {
    if (peer.port <= 0 && peer.pk.isBlank()) return

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip
    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null

    if (text.isNullOrEmpty()) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, OmaI18n.t("toast_clipboard_empty"), Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        // QR-paired devices: encrypted QUIC only. Others: legacy HTTP.
        val key = io.omarchy.omasend.network.CoreNode.trustedKeyFor(context, peer)
        val result = if (key != null) {
            io.omarchy.omasend.network.CoreNode.sendClipboard(key, text, peer.ip)
        } else {
            app.client.sendClipboard(peer.ip, peer.port, text, peer.omaId)
        }
        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                app.soundEngine.playSendSound()
                app.hapticController.onDropBounce()
                onState(TransferProgressState.Success(OmaI18n.f("clipboard_sent", peer.name)))
            } else {
                Toast.makeText(context, OmaI18n.f("clipboard_failed", result.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun scopeLaunchMain(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(block)
}

// ------------------------------------------------------------------ received files

fun getRecentReceivedFiles(context: Context? = null): List<File> {
    return try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmaSend")
        val directFiles = if (dir.exists() && dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5) ?: emptyList()
        } else {
            emptyList()
        }
        if (directFiles.isNotEmpty() || context == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return directFiles
        }
        val mediaStoreFiles = mutableListOf<File>()
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%OmaSend%")
        val sortOrder = "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
        context.contentResolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext() && mediaStoreFiles.size < 5) {
                if (nameCol != -1) {
                    val name = cursor.getString(nameCol)
                    if (!name.isNullOrBlank()) {
                        val candidate = File(dir, name)
                        if (candidate.exists()) {
                            mediaStoreFiles.add(candidate)
                        }
                    }
                }
            }
        }
        mediaStoreFiles
    } catch (_: Exception) {
        emptyList()
    }
}

fun openFileWithSystem(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, OmaI18n.f("err_open_file", file.name), Toast.LENGTH_SHORT).show()
    }
}

fun shareFileWithSystem(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, OmaI18n.t("share")))
    } catch (_: Exception) {
        Toast.makeText(context, OmaI18n.f("err_share_file", file.name), Toast.LENGTH_SHORT).show()
    }
}

