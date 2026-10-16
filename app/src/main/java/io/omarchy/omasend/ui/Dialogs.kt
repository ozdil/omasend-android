package io.omarchy.omasend.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.omarchy.omasend.crypto.OmaIdentity
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferProgressState
import io.omarchy.omasend.network.CoreNode
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ------------------------------------------------------------------ transfer status

/** The one card that shows what is being sent or received right now. */
@Composable
fun TransferStatusCard(
    state: TransferProgressState,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    if (state is TransferProgressState.Idle) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val title: String
            val subtitle: String?
            val running: Boolean
            when (state) {
                is TransferProgressState.Requesting -> {
                    title = OmaI18n.f("connecting_to", state.peerName); subtitle = state.fileName; running = true
                }
                is TransferProgressState.WaitingConsent -> {
                    title = OmaI18n.f("waiting_for", state.peerName); subtitle = null; running = true
                }
                is TransferProgressState.Transferring -> {
                    title = state.fileName
                    val speed = if (state.speedMBps > 0.0) String.format(Locale.US, " · %.1f MB/s", state.speedMBps) else ""
                    subtitle = "${OmaI18n.t(if (state.isUploading) "sending" else "receiving")} · " +
                        "${NetworkUtils.formatBytes(state.bytesTransferred)} / ${NetworkUtils.formatBytes(state.totalBytes)}$speed"
                    running = true
                }
                is TransferProgressState.Success -> {
                    title = state.message; subtitle = null; running = false
                }
                is TransferProgressState.Error -> {
                    title = state.message; subtitle = null; running = false
                }
                else -> return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is TransferProgressState.Success -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald, modifier = Modifier.size(22.dp))
                    is TransferProgressState.Error -> Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                    is TransferProgressState.Transferring -> Text(
                        "%${state.percent}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary
                    )
                    else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (state is TransferProgressState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (running && onCancel != null) {
                    TextButton(onClick = onCancel) { Text(OmaI18n.t("cancel"), color = MaterialTheme.colorScheme.error) }
                } else if (!running) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = OmaI18n.t("close"), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (state is TransferProgressState.Transferring) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ------------------------------------------------------------------ received files

fun fileIcon(filename: String): ImageVector = when (filename.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "svg", "heic" -> Icons.Default.Image
    "mp4", "mkv", "mov", "avi", "webm" -> Icons.Default.PlayCircle
    "mp3", "flac", "wav", "m4a", "ogg" -> Icons.Default.MusicNote
    "pdf", "doc", "docx", "txt", "md", "odt" -> Icons.Default.Description
    "zip", "tar", "gz", "7z", "apk", "rar" -> Icons.Default.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Tap opens the file; the icon hands it to another app. */
@Composable
fun RecentFileRow(file: File, onOpen: () -> Unit, onShare: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(12.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(fileIcon(file.name), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(NetworkUtils.formatBytes(file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = OmaI18n.t("share"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun IncomingTransferDialog(
    prompt: IncomingTransferPrompt,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val encrypted = CoreNode.isQuicOffer(prompt.token)
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(OmaI18n.t("incoming_title")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(OmaI18n.f("incoming_request", prompt.senderName), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (encrypted) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (encrypted) OmaI18n.t("incoming_encrypted") else OmaI18n.t("incoming_unencrypted"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (encrypted) Emerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    prompt.files.forEach { file ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(fileIcon(file.name), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(NetworkUtils.formatBytes(file.size_bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text(OmaI18n.t("accept")) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(OmaI18n.t("decline")) } }
    )
}

@Composable
fun RenameDeviceDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("device_name")) },
        text = {
            Column {
                Text(OmaI18n.t("device_name_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text(OmaI18n.t("save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}

@Composable
fun DirectIpDialog(onDismiss: () -> Unit, onConnect: (ip: String, port: Int) -> Unit) {
    var ipText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(NetworkUtils.PORT.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("connect_by_ip")) },
        text = {
            Column {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text(OmaI18n.t("ip_address")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text(OmaI18n.t("port")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(ipText.trim(), portText.toIntOrNull() ?: NetworkUtils.PORT) },
                enabled = ipText.trim().isNotEmpty()
            ) { Text(OmaI18n.t("connect")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}

/** QR for the built-in web page, so a browser on the same network can pick up files. */
@Composable
fun WebPortalDialog(onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val endpointUrl = remember { "http://${NetworkUtils.getLocalIpAddress()}:${NetworkUtils.PORT}" }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(endpointUrl) { bmp = withContext(Dispatchers.Default) { qrBitmap(endpointUrl, 512) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("web_portal")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(OmaI18n.t("web_portal_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.size(208.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                        val b = bmp
                        if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = endpointUrl, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator()
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(endpointUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = JetBrainsMonoFontFamily)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("close")) } },
        dismissButton = { TextButton(onClick = { onCopy(endpointUrl) }) { Text(OmaI18n.t("copy")) } }
    )
}

/** Enter the OmaID from another device. The Luhn check runs as you type. */
@Composable
fun OmaIdEntryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var digits by remember { mutableStateOf("") }
    val valid = OmaIdentity.isValid(digits)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("omaid_use_existing")) },
        text = {
            Column {
                Text(OmaI18n.t("omaid_use_existing_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = digits,
                    onValueChange = { digits = it.filter(Char::isDigit).take(16) },
                    singleLine = true,
                    placeholder = { Text("0000000000000000", fontFamily = JetBrainsMonoFontFamily) },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = digits.length == 16 && !valid,
                    supportingText = { if (digits.length == 16 && !valid) Text(OmaI18n.t("omaid_invalid")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(digits) }, enabled = valid) { Text(OmaI18n.t("save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}
