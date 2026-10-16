package io.omarchy.omasend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.crypto.OmaIdentity
import io.omarchy.omasend.model.*
import io.omarchy.omasend.network.NetworkTransportMode
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.network.WanStatus
import io.omarchy.omasend.service.OmaSendForegroundService
import io.omarchy.omasend.ui.scanner.OmaQrScannerDialog
import io.omarchy.omasend.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    app: OmaSendApp,
    incomingPrompt: IncomingTransferPrompt?,
    onAcceptPrompt: (String) -> Unit,
    onDeclinePrompt: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveryMode by app.discoveryManager.discoveryMode.collectAsState()
    val lists = rememberDeviceLists(app)
    val scanning = discoveryMode != DiscoveryMode.OFF

    // Explicitly chosen target; otherwise the single online device of your own.
    var selected by remember { mutableStateOf<DiscoveredPeer?>(null) }
    val target: DiscoveredPeer? = selected ?: lists.defaultOwn?.asPeer()
    var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showWebQrDialog by remember { mutableStateOf(false) }
    var showDirectIpDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var urisAwaitingTarget by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var keyToRemove by remember { mutableStateOf<DeviceKey.TrustedKey?>(null) }
    var pickFor by remember { mutableStateOf<DiscoveredPeer?>(null) }

    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    val incomingMetrics by io.omarchy.omasend.network.StorageUtils.incomingTransferMetrics.collectAsState()
    val incomingFileName by io.omarchy.omasend.network.StorageUtils.incomingFileName.collectAsState()

    fun refreshRecentFiles() {
        recentFiles = getRecentReceivedFiles(context)
    }

    LaunchedEffect(Unit) { refreshRecentFiles() }
    LaunchedEffect(incomingMetrics) { if (incomingMetrics == null) refreshRecentFiles() }

    fun isSelected(p: DiscoveredPeer): Boolean = selected?.let { s ->
        if (s.pk.isNotBlank() && p.pk.isNotBlank()) s.pk == p.pk else s.id == p.id
    } ?: false

    fun send(peer: DiscoveredPeer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        selected = peer
        scope.launch {
            sendMultipleUrisToPeer(context, app, peer, uris) { state ->
                transferState = state
                if (state is TransferProgressState.Success) refreshRecentFiles()
            }
        }
    }

    fun sendToTargetOrAsk(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val t = target
        if (t != null) send(t, uris) else urisAwaitingTarget = uris
    }

    fun toggleService() {
        if (scanning) {
            app.discoveryManager.setMode(DiscoveryMode.OFF)
            OmaSendForegroundService.stopService(context)
        } else {
            app.discoveryManager.setMode(DiscoveryMode.EVERYONE)
            OmaSendForegroundService.startService(context)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val p = pickFor
        pickFor = null
        if (p != null) send(p, uris) else sendToTargetOrAsk(uris)
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        sendToTargetOrAsk(uris)
    }

    val refreshRotation = remember { Animatable(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OmarchyGeometricEmblem(modifier = Modifier.size(34.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "OmaSend",
                                fontWeight = FontWeight.Bold,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.titleLarge
                            )
                            StatusLine(
                                active = scanning,
                                deviceLabel = NetworkUtils.getDeviceName(context),
                                onToggle = { toggleService() },
                                onRename = { showRenameDialog = true }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showPairDialog = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = OmaI18n.t("pair_device"), modifier = Modifier.size(21.dp), tint = Emerald)
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshRotation.animateTo(refreshRotation.value + 360f, tween(600, easing = FastOutSlowInEasing))
                            }
                            app.discoveryManager.forceRefresh()
                            refreshRecentFiles()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = OmaI18n.t("refresh"),
                            modifier = Modifier.rotate(refreshRotation.value).size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Info, contentDescription = OmaI18n.t("about"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            SendDock(
                targetName = target?.name,
                onSendFiles = {
                    pickFor = null
                    filePicker.launch("*/*")
                },
                onSendMedia = {
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onWebQr = { showWebQrDialog = true }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Active transfer (sending, or receiving over the legacy path)
            val activeTransferState: TransferProgressState = when {
                transferState !is TransferProgressState.Idle -> transferState
                incomingMetrics != null -> {
                    val m = incomingMetrics!!
                    TransferProgressState.Transferring(
                        isUploading = false,
                        peerName = OmaI18n.t("incoming"),
                        fileName = incomingFileName ?: "…",
                        bytesTransferred = m.bytesTransferred,
                        totalBytes = m.totalBytes,
                        percent = m.percent,
                        speedMBps = m.speedMBps,
                        etaSeconds = m.etaSeconds
                    )
                }
                else -> TransferProgressState.Idle
            }
            if (activeTransferState !is TransferProgressState.Idle) {
                item(key = "transfer") {
                    TransferStatusCard(
                        state = activeTransferState,
                        onCancel = {
                            app.client.cancelActiveTransfer()
                            io.omarchy.omasend.network.StorageUtils.cancelIncomingTransfer()
                            transferState = TransferProgressState.Error(OmaI18n.t("cancelled"))
                        },
                        onDismiss = { transferState = TransferProgressState.Idle }
                    )
                }
            }

            // MY DEVICES: QR-paired keys, end-to-end encrypted
            item(key = "h_mine") {
                SectionHeaderRow(title = OmaI18n.t("section_my_devices"), count = lists.paired.size, accentColor = Emerald)
            }
            if (lists.paired.isEmpty()) {
                item(key = "pair_hint") { PairHintCard(onPair = { showPairDialog = true }) }
            } else {
                items(lists.paired, key = { "k_${it.key.pk}" }) { d ->
                    val handle = d.asPeer()
                    PairedDeviceCard(
                        device = d,
                        selected = isSelected(handle),
                        onSelect = { selected = if (isSelected(handle)) null else handle },
                        onSend = {
                            pickFor = handle
                            filePicker.launch("*/*")
                        },
                        onClipboard = {
                            scope.launch { sendClipboardToPeer(context, app, handle) { state -> transferState = state } }
                        },
                        onRemove = { keyToRemove = d.key }
                    )
                }
                item(key = "f_mine") { SectionFootnote(OmaI18n.t("paired_footnote"), Icons.Default.Lock) }
            }

            // NEARBY: discovered but not QR-paired (legacy transport, receiver approves)
            item(key = "h_nearby") {
                SectionHeaderRow(
                    title = OmaI18n.t("section_nearby"),
                    count = lists.nearby.size,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (lists.nearby.isEmpty()) {
                item(key = "nearby_empty") {
                    NearbyEmptyCard(
                        scanning = scanning,
                        onStartScan = { toggleService() },
                        onAddIp = { showDirectIpDialog = true }
                    )
                }
            } else {
                items(lists.nearby, key = { "n_${it.id}" }) { peer ->
                    NearbyPeerCard(
                        peer = peer,
                        selected = isSelected(peer),
                        onSelect = { selected = if (isSelected(peer)) null else peer },
                        onSend = {
                            pickFor = peer
                            filePicker.launch("*/*")
                        }
                    )
                }
                item(key = "f_nearby") { SectionFootnote(OmaI18n.t("nearby_footnote"), Icons.Default.LockOpen) }
            }

            if (recentFiles.isNotEmpty()) {
                item(key = "recent") {
                    Spacer(modifier = Modifier.height(4.dp))
                    RecentReceivedFilesCard(
                        files = recentFiles,
                        onOpenFile = { file -> openFileWithSystem(context, file) },
                        onShareFile = { file -> shareFileWithSystem(context, file) }
                    )
                }
            }

            item(key = "bottom_space") { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    // ---------------------------------------------------------------- dialogs

    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = NetworkUtils.getDeviceName(context),
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                NetworkUtils.setDeviceName(context, newName)
                showRenameDialog = false
            }
        )
    }

    if (showWebQrDialog) {
        QrCodeDialog(context = context, onDismiss = { showWebQrDialog = false })
    }

    if (showDirectIpDialog) {
        DirectIpDialog(
            onDismiss = { showDirectIpDialog = false },
            onConnect = { ip, port ->
                app.discoveryManager.addManualPeer(ip, port)
                showDirectIpDialog = false
            }
        )
    }

    if (showInfoDialog) {
        InfoDialog(onDismiss = { showInfoDialog = false })
    }

    if (showPairDialog) {
        PairDeviceDialog(
            app = app,
            onDismiss = { showPairDialog = false },
            onScan = {
                showPairDialog = false
                showQrScannerDialog = true
            },
            onPaired = { name ->
                showPairDialog = false
                Toast.makeText(context, OmaI18n.f("paired_with", name), Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showQrScannerDialog) {
        OmaQrScannerDialog(
            onDismissRequest = { showQrScannerDialog = false },
            onPairUriScanned = { uri ->
                showQrScannerDialog = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) { app.client.pairWithUri(uri) }
                    result.onSuccess { k ->
                        app.hapticController.onClipboardReceived()
                        Toast.makeText(context, OmaI18n.f("paired_with", k.name), Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(context, OmaI18n.f("pair_failed", e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onOmaIdConnected = { scannedId ->
                // Legacy OmaID QR codes (older versions): remembered as a legacy paired peer.
                showQrScannerDialog = false
                app.discoveryManager.addPairedOmaId(OmaIdentity.format(scannedId))
                app.discoveryManager.forceRefresh()
                Toast.makeText(context, OmaI18n.t("legacy_omaid_added"), Toast.LENGTH_LONG).show()
            }
        )
    }

    keyToRemove?.let { k ->
        RemovePairingDialog(
            name = k.name,
            onConfirm = {
                DeviceKey.TrustStore(context).remove(k.pk)
                if (selected?.pk == k.pk) selected = null
                keyToRemove = null
            },
            onDismiss = { keyToRemove = null }
        )
    }

    if (urisAwaitingTarget.isNotEmpty()) {
        ChooseTargetDialog(
            lists = lists,
            onDismiss = { urisAwaitingTarget = emptyList() },
            onSelect = { peer ->
                val uris = urisAwaitingTarget
                urisAwaitingTarget = emptyList()
                send(peer, uris)
            }
        )
    }

    if (incomingPrompt != null) {
        IncomingTransferDialog(
            prompt = incomingPrompt,
            onAccept = { onAcceptPrompt(incomingPrompt.token) },
            onDecline = { onDeclinePrompt(incomingPrompt.token) }
        )
    }
}

// ------------------- OMARCHY KURUMSAL AMBLEM VE BİLEŞENLER -------------------

@Composable
fun OmarchyGeometricEmblem(modifier: Modifier = Modifier) {
    val emerald = Color(0xFF10B981)
    val cyan = Color(0xFF00E5FF)
    val darkSurface = Color(0xFF161B22)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = darkSurface,
        border = BorderStroke(1.2.dp, emerald.copy(alpha = 0.7f)),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val radius = size.minDimension / 2f

            // Outer hexagon / geometric shield
            val hexPath = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius * 0.866f, center.y - radius * 0.5f)
                lineTo(center.x + radius * 0.866f, center.y + radius * 0.5f)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius * 0.866f, center.y + radius * 0.5f)
                lineTo(center.x - radius * 0.866f, center.y - radius * 0.5f)
                close()
            }
            drawPath(
                path = hexPath,
                color = emerald,
                style = Stroke(width = 1.8.dp.toPx())
            )

            // Inner directional node core
            drawCircle(
                color = cyan,
                radius = radius * 0.32f,
                center = center
            )
        }
    }
}

@Composable
fun SectionHeaderRow(
    title: String,
    count: Int,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            letterSpacing = 0.6.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = CircleShape,
            color = accentColor.copy(alpha = 0.16f)
        ) {
            Text(
                text = "$count",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

@Composable
fun RecentReceivedFilesCard(
    files: List<File>,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "SON ALINAN DOSYALAR",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f), RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                files.forEachIndexed { index, file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFile(file) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = getFileIcon(file.name)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = NetworkUtils.formatBytes(file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { onShareFile(file) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Paylaş",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        FilledTonalButton(
                            onClick = { onOpenFile(file) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Aç", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (index < files.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getFileIcon(filename: String): ImageVector {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "svg" -> Icons.Default.Image
        "mp4", "mkv", "mov", "avi" -> Icons.Default.PlayCircle
        "mp3", "flac", "wav", "m4a" -> Icons.Default.MusicNote
        "pdf", "doc", "docx", "txt", "md" -> Icons.Default.Description
        "zip", "tar", "gz", "7z", "apk" -> Icons.Default.FolderZip
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

// ------------------- DİALOGLAR -------------------

@Composable
fun RenameDeviceDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Cihaz Adını Değiştir",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Bu ad yakındaki cihazların ekranında görüntülenecektir:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Cihaz Adı") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@Composable
fun IncomingTransferDialog(
    prompt: IncomingTransferPrompt,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = {
            Icon(
                Icons.Default.Share,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Dosya Aktarım İsteği",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = OmaI18n.f("incoming_request", prompt.senderName),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                val encrypted = io.omarchy.omasend.network.CoreNode.isQuicOffer(prompt.token)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (encrypted) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (encrypted) OmaI18n.t("incoming_encrypted") else OmaI18n.t("incoming_unencrypted"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (encrypted) Emerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        prompt.files.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = NetworkUtils.formatBytes(file.size_bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text(OmaI18n.t("accept"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Text(OmaI18n.t("decline"))
            }
        }
    )
}

@Composable
fun TransferStatusCard(
    state: TransferProgressState,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            when (state) {
                is TransferProgressState.Requesting -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "'${state.peerName}' cihazına bağlanılıyor...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (onCancel != null) {
                            TextButton(onClick = onCancel) {
                                Text("İptal", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                is TransferProgressState.WaitingConsent -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "'${state.peerName}' onayı bekleniyor...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (onCancel != null) {
                            TextButton(onClick = onCancel) {
                                Text("İptal", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                is TransferProgressState.Transferring -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = (if (state.isUploading) "Gönderiliyor: " else "Alınıyor: ") + state.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "%${state.percent}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (onCancel != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Transferi İptal Et",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${NetworkUtils.formatBytes(state.bytesTransferred)} / ${NetworkUtils.formatBytes(state.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val speedText = if (state.speedMBps > 0.0) {
                                String.format(java.util.Locale.US, "%.1f MB/s", state.speedMBps)
                            } else ""
                            val etaText = if (state.etaSeconds > 0L) {
                                " • ETA: ${state.etaSeconds}s"
                            } else ""
                            if (speedText.isNotEmpty() || etaText.isNotEmpty()) {
                                Text(
                                    text = "$speedText$etaText",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                is TransferProgressState.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                is TransferProgressState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun QrCodeDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val endpointUrl = "http://${NetworkUtils.getLocalIpAddress()}:53317"
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(endpointUrl) {
        withContext(Dispatchers.Default) {
            try {
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(endpointUrl, BarcodeFormat.QR_CODE, 512, 512)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                qrBitmap = bmp
            } catch (_: Exception) {
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Web İndirme Portalı (QR)", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Aynı ağdaki herhangi bir cihaz (iPhone, Mac, Windows veya PC) tarayıcıyla bu kodu okutarak bağlanabilir:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    modifier = Modifier.size(200.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "OmaSend Connection QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = endpointUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("OmaSend Endpoint", endpointUrl))
                                Toast.makeText(context, "URL kopyalandı", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Tamam")
            }
        }
    )
}

@Composable
fun DirectIpDialog(
    onDismiss: () -> Unit,
    onConnect: (ip: String, port: Int) -> Unit
) {
    var ipText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("53317") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Doğrudan IP ile Bağlan", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Hedef cihazın IP adresini girin:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP Adresi (örn. 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: NetworkUtils.PORT
                    if (ipText.trim().isNotEmpty()) {
                        onConnect(ipText.trim(), port)
                    }
                },
                enabled = ipText.trim().isNotEmpty()
            ) {
                Text("Bağlan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmarchyGeometricEmblem(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "OmaSend",
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "v${io.omarchy.omasend.BuildConfig.VERSION_NAME} (${io.omarchy.omasend.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = OmaI18n.t("about_body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        InfoDialogRow(OmaI18n.t("device_id"), DeviceKey.identity(context).deviceId)
                        InfoDialogRow(OmaI18n.t("about_paired_row"), "QUIC · TLS 1.3 · UDP ${io.omarchy.omasend.network.CoreNode.QUIC_PORT}")
                        InfoDialogRow(OmaI18n.t("about_nearby_row"), "HTTP ${NetworkUtils.PORT} · ${OmaI18n.t("needs_approval")}")
                        InfoDialogRow("Geliştirici", "Ozan Özdil (@ozdil)")
                        InfoDialogRow("Lisans", "MIT • Ücretsiz & Açık Kaynak")
                    }
                }

                val soundEngine = remember { (context.applicationContext as? OmaSendApp)?.soundEngine }
                var soundEnabled by remember { mutableStateOf(soundEngine?.isSoundEnabled() ?: true) }

                Surface(
                    onClick = {
                        val next = !soundEnabled
                        soundEnabled = next
                        soundEngine?.setSoundEnabled(next)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ses Efektleri (Gönderim / Alım)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (soundEnabled) "AÇIK" else "KAPALI",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (soundEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ozdil"))
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/ozdil"))
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFDD00),
                            contentColor = Color(0xFF000000)
                        ),
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF000000)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Kahve Ismarla",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Kapat")
            }
        }
    )
}

@Composable
fun InfoDialogRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold)
    }
}

// ------------------- AKTARIM FONKSİYONLARI -------------------

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

            val summaryLabel = "${prepared.size} dosya (${prepared.first().name}...)"
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
                    onState(TransferProgressState.Error(it.message ?: "Transfer isteği başarısız oldu"))
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
                        onState(TransferProgressState.Error(decisionResult.exceptionOrNull()?.message ?: "Transfer reddedildi"))
                    }
                    return@withContext
                }
            }

            for ((index, item) in prepared.withIndex()) {
                val inputStream = context.contentResolver.openInputStream(item.uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Error("'${item.name}' dosya akışı açılamadı"))
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
                        onState(TransferProgressState.Error(uploadResult.exceptionOrNull()?.message ?: "Yükleme başarısız"))
                    }
                    return@withContext
                }
            }

            withContext(Dispatchers.Main) {
                app.soundEngine.playSendSound()
                app.hapticController.onDropBounce()
                onState(TransferProgressState.Success("${prepared.size} dosya başarıyla gönderildi!"))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Error(e.message ?: "Transfer hatası"))
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
                    onState(TransferProgressState.Error(it.message ?: "Transfer isteği başarısız oldu"))
                }
                return@withContext
            }

            // Anonim Numaralı OmaID Zero-Click: Eğer karşı cihaz aynı OmaID'ye sahipse ve doğrudan ACCEPTED döndüyse
            // onay bekleme (pollDecision) aşamasını tamamen atla!
            if (initialStatus != "ACCEPTED") {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.WaitingConsent(peer.name))
                }

                val decisionResult = app.client.pollDecision(peer.ip, peer.port, token)
                if (decisionResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Error(decisionResult.exceptionOrNull()?.message ?: "Transfer reddedildi"))
                    }
                    return@withContext
                }
            }

            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error("Dosya akışı açılamadı"))
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
                    onState(TransferProgressState.Success("'$fileName' başarıyla gönderildi!"))
                } else {
                    onState(TransferProgressState.Error(uploadResult.exceptionOrNull()?.message ?: "Yükleme başarısız"))
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Error(e.message ?: "Transfer hatası"))
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

// ------------------- DOSYA YARDIMCILARI -------------------

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
    } catch (e: Exception) {
        Toast.makeText(context, "Dosya açılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
        context.startActivity(Intent.createChooser(intent, "Dosyayı Paylaş"))
    } catch (e: Exception) {
        Toast.makeText(context, "Paylaşılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

