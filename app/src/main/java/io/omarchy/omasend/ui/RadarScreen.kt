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
    val peers by app.discoveryManager.peers.collectAsState()
    val discoveryMode by app.discoveryManager.discoveryMode.collectAsState()
    var omaIdentity by remember { mutableStateOf(app.omaIdentity) }
    val wanStatus by app.wanDiscoveryEngine.status.collectAsState()

    var selectedPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showDirectIpDialog by remember { mutableStateOf(false) }
    var showSendToAnotherOmaIdDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTargetSelectorDialog by remember { mutableStateOf(false) }
    var showOmaIdQrDialog by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var showEditOmaIdDialog by remember { mutableStateOf(false) }
    var showResetOmaIdConfirmDialog by remember { mutableStateOf(false) }
    var showNetworkStatusDialog by remember { mutableStateOf(false) }
    var pendingUrisToSend by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }

    val myDevices = remember(peers) { peers.filter { it.isSameOwner } }
    val otherPeers = remember(peers) { peers.filter { !it.isSameOwner } }

    val incomingMetrics by io.omarchy.omasend.network.StorageUtils.incomingTransferMetrics.collectAsState()
    val incomingFileName by io.omarchy.omasend.network.StorageUtils.incomingFileName.collectAsState()

    fun refreshRecentFiles() {
        recentFiles = getRecentReceivedFiles(context)
    }

    LaunchedEffect(Unit) {
        refreshRecentFiles()
    }

    LaunchedEffect(incomingMetrics) {
        if (incomingMetrics == null) {
            refreshRecentFiles()
        }
    }

    val refreshRotation = remember { Animatable(0f) }

    // Multi-file picker (Documents / Any file)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val currentTarget = selectedPeer ?: myDevices.firstOrNull { it.lastSeen > 0L && it.ip.isNotBlank() }
            if (currentTarget != null) {
                selectedPeer = currentTarget
                scope.launch {
                    sendMultipleUrisToPeer(context, app, currentTarget, uris) { state ->
                        transferState = state
                        if (state is TransferProgressState.Success) refreshRecentFiles()
                    }
                }
            } else {
                pendingUrisToSend = uris
                showTargetDeviceSheetOrFallback(peers, onSelectTarget = { peer ->
                    selectedPeer = peer
                    scope.launch {
                        sendMultipleUrisToPeer(context, app, peer, uris) { state ->
                            transferState = state
                            if (state is TransferProgressState.Success) refreshRecentFiles()
                        }
                    }
                }, onNoTarget = {
                    showTargetSelectorDialog = true
                })
            }
        }
    }

    // Media Picker (Images & Videos)
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val currentTarget = selectedPeer ?: myDevices.firstOrNull { it.lastSeen > 0L && it.ip.isNotBlank() }
            if (currentTarget != null) {
                selectedPeer = currentTarget
                scope.launch {
                    sendMultipleUrisToPeer(context, app, currentTarget, uris) { state ->
                        transferState = state
                        if (state is TransferProgressState.Success) refreshRecentFiles()
                    }
                }
            } else {
                pendingUrisToSend = uris
                showTargetDeviceSheetOrFallback(peers, onSelectTarget = { peer ->
                    selectedPeer = peer
                    scope.launch {
                        sendMultipleUrisToPeer(context, app, peer, uris) { state ->
                            transferState = state
                            if (state is TransferProgressState.Success) refreshRecentFiles()
                        }
                    }
                }, onNoTarget = {
                    showTargetSelectorDialog = true
                })
            }
        }
    }

    // Direct single/multi file picker for specific peer click
    val singlePeerFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty() && selectedPeer != null) {
            val peer = selectedPeer!!
            scope.launch {
                sendMultipleUrisToPeer(context, app, peer, uris) { state ->
                    transferState = state
                    if (state is TransferProgressState.Success) refreshRecentFiles()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OmarchyGeometricEmblem(
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "OmaSend",
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(
                                        1.dp,
                                        if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981).copy(alpha = 0.4f) else Color.Transparent
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981) else Color.Gray)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (discoveryMode != DiscoveryMode.OFF) OmaI18n.t("status_active") else OmaI18n.t("status_off"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${NetworkUtils.getDeviceName(context)} • ${NetworkUtils.getLocalIpAddress()}:53317",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { showRenameDialog = true }
                            )
                        }
                    }
                },
                actions = {
                    NetworkStatusBadge(
                        wanStatus = wanStatus,
                        onClick = { showNetworkStatusDialog = true }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                refreshRotation.animateTo(
                                    targetValue = refreshRotation.value + 360f,
                                    animationSpec = tween(600, easing = FastOutSlowInEasing)
                                )
                                app.discoveryManager.forceRefresh()
                                refreshRecentFiles()
                                isRefreshing = false
                                Toast.makeText(context, OmaI18n.t("toast_refreshed"), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            modifier = Modifier.rotate(refreshRotation.value).size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Bilgi",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            StealthActionDock(
                selectedPeer = selectedPeer ?: myDevices.firstOrNull { it.lastSeen > 0L && it.ip.isNotBlank() },
                onSendFiles = {
                    filePickerLauncher.launch("*/*")
                },
                onSendMedia = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onSendToAnotherOmaId = {
                    showSendToAnotherOmaIdDialog = true
                },
                onQuickDrop = {
                    showQrDialog = true
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // 1. Anonim Numaralı Kullanıcı Kimlik Kartı (Kendi OmaID'm)
            item {
                AnonymousNumberedIdentityCard(
                    identity = omaIdentity,
                    discoveryMode = discoveryMode,
                    onCopyId = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("OmaID", omaIdentity.formattedId)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, OmaI18n.t("toast_omaid_copied"), Toast.LENGTH_SHORT).show()
                    },
                    onShowQr = { showOmaIdQrDialog = true },
                    onScanQr = { showQrScannerDialog = true },
                    onEditId = { showEditOmaIdDialog = true },
                    onCycleMode = {
                        val nextMode = when (discoveryMode) {
                            DiscoveryMode.OFF -> DiscoveryMode.EVERYONE
                            DiscoveryMode.EVERYONE -> DiscoveryMode.KNOWN_PEERS
                            DiscoveryMode.KNOWN_PEERS -> DiscoveryMode.OFF
                        }
                        app.discoveryManager.setMode(nextMode)
                        if (nextMode == DiscoveryMode.OFF) {
                            OmaSendForegroundService.stopService(context)
                            Toast.makeText(context, OmaI18n.t("status_off"), Toast.LENGTH_SHORT).show()
                        } else {
                            OmaSendForegroundService.startService(context)
                            Toast.makeText(context, "${OmaI18n.t("status_active")}: ${nextMode.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // 2. Aktif Transfer Durum Bildirimi (Gönderim veya Alım)
            val activeTransferState: TransferProgressState = when {
                transferState !is TransferProgressState.Idle -> transferState
                incomingMetrics != null -> {
                    val m = incomingMetrics!!
                    TransferProgressState.Transferring(
                        isUploading = false,
                        peerName = "Gelen Aktarım",
                        fileName = incomingFileName ?: "Alınıyor...",
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
                item {
                    TransferStatusCard(
                        state = activeTransferState,
                        onCancel = {
                            app.client.cancelActiveTransfer()
                            io.omarchy.omasend.network.StorageUtils.cancelIncomingTransfer()
                            transferState = TransferProgressState.Error("Transfer kullanıcı tarafından iptal edildi.")
                        },
                        onDismiss = { transferState = TransferProgressState.Idle }
                    )
                }
            }

            // 3. BÖLÜM 1: KENDİ CİHAZLARIM (Aynı OmaID - Zero-Click Otomatik Kabul)
            item {
                SectionHeaderRow(
                    title = OmaI18n.t("section_my_devices"),
                    count = myDevices.size,
                    accentColor = Color(0xFF10B981)
                )
            }

            if (myDevices.isEmpty()) {
                item {
                    SameOwnerEmptyHintCard(
                        onSyncOmaId = { showEditOmaIdDialog = true },
                        onScanQr = { showQrScannerDialog = true }
                    )
                }
            } else {
                items(myDevices, key = { "my_${it.id}" }) { peer ->
                    ModernPeerCard(
                        peer = peer,
                        isSelected = selectedPeer?.id == peer.id,
                        onCardClick = {
                            selectedPeer = if (selectedPeer?.id == peer.id) null else peer
                        },
                        onToggleTrust = {
                            app.discoveryManager.toggleTrust(peer.id)
                        },
                        onSendClick = {
                            selectedPeer = peer
                            singlePeerFilePicker.launch("*/*")
                        },
                        onClipboardClick = {
                            scope.launch {
                                sendClipboardToPeer(context, app, peer) { state ->
                                    transferState = state
                                }
                            }
                        }
                    )
                }
            }

            // 4. BÖLÜM 2: ÇEVREDEKİ CİHAZLAR & FARKLI OMAID'YE GÖNDER
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeaderRow(
                        title = OmaI18n.t("section_other_devices"),
                        count = otherPeers.size,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { showSendToAnotherOmaIdDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = OmaI18n.t("btn_send_other_omaid"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (otherPeers.isEmpty()) {
                item {
                    CleanEmptyStateCard(
                        discoveryMode = discoveryMode,
                        onEnableScan = {
                            app.discoveryManager.setMode(DiscoveryMode.EVERYONE)
                            OmaSendForegroundService.startService(context)
                        },
                        onSwitchToEveryone = {
                            app.discoveryManager.setMode(DiscoveryMode.EVERYONE)
                        },
                        onSendToAnotherOmaId = { showSendToAnotherOmaIdDialog = true },
                        onAddDirectIp = { showDirectIpDialog = true }
                    )
                }
            } else {
                items(otherPeers, key = { "other_${it.id}" }) { peer ->
                    ModernPeerCard(
                        peer = peer,
                        isSelected = selectedPeer?.id == peer.id,
                        onCardClick = {
                            selectedPeer = if (selectedPeer?.id == peer.id) null else peer
                        },
                        onToggleTrust = {
                            app.discoveryManager.toggleTrust(peer.id)
                        },
                        onSendClick = {
                            selectedPeer = peer
                            singlePeerFilePicker.launch("*/*")
                        },
                        onClipboardClick = {
                            scope.launch {
                                sendClipboardToPeer(context, app, peer) { state ->
                                    transferState = state
                                }
                            }
                        }
                    )
                }
            }

            // 5. Son Alınan Dosyalar
            if (recentFiles.isNotEmpty()) {
                item {
                    RecentReceivedFilesCard(
                        files = recentFiles,
                        onOpenFile = { file -> openFileWithSystem(context, file) },
                        onShareFile = { file -> shareFileWithSystem(context, file) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    // Dialogs
    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = NetworkUtils.getDeviceName(context),
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                NetworkUtils.setDeviceName(context, newName)
                showRenameDialog = false
                Toast.makeText(context, "Cihaz adı güncellendi: $newName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showQrDialog) {
        QrCodeDialog(
            context = context,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showDirectIpDialog) {
        DirectIpDialog(
            onDismiss = { showDirectIpDialog = false },
            onConnect = { ip, port ->
                app.discoveryManager.addManualPeer(ip, port)
                showDirectIpDialog = false
                Toast.makeText(context, "$ip:$port eklendi", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSendToAnotherOmaIdDialog) {
        SendToAnotherOmaIdDialog(
            onDismiss = { showSendToAnotherOmaIdDialog = false },
            onConfirmSend = { targetOmaId, optionalIp, optionalPort ->
                showSendToAnotherOmaIdDialog = false
                val targetPeer = app.discoveryManager.resolveOrCreatePeerForOmaId(
                    targetOmaId = targetOmaId,
                    fallbackIp = optionalIp,
                    fallbackPort = optionalPort
                )
                if (targetPeer != null && targetPeer.ip.isNotBlank()) {
                    selectedPeer = targetPeer
                    singlePeerFilePicker.launch("*/*")
                } else {
                    scope.launch {
                        app.discoveryManager.forceRefresh()
                    }
                    Toast.makeText(
                        context,
                        "Hedef OmaID ($targetOmaId) eş listesine eklendi ve WAN/LAN üzerinde aranıyor. IP biliyorsanız doğrudan girebilirsiniz.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    if (showInfoDialog) {
        InfoDialog(onDismiss = { showInfoDialog = false })
    }

    if (showOmaIdQrDialog) {
        OmaIdQrDialog(
            identity = omaIdentity,
            onDismiss = { showOmaIdQrDialog = false }
        )
    }

    if (showQrScannerDialog) {
        OmaQrScannerDialog(
            onDismissRequest = { showQrScannerDialog = false },
            onPairUriScanned = { uri ->
                showQrScannerDialog = false
                scope.launch {
                    val result = kotlinx.coroutines.withContext(Dispatchers.IO) { app.client.pairWithUri(uri) }
                    result.onSuccess { k ->
                        Toast.makeText(
                            context,
                            "Cihaz eşleşti: ${k.name} (${k.device_id})" + if (k.own_device) " • Kendi cihazın" else "",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { e ->
                        Toast.makeText(context, "Eşleşme başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onOmaIdConnected = { scannedId ->
                showQrScannerDialog = false
                scope.launch {
                    val formatted = OmaIdentity.format(scannedId)
                    app.discoveryManager.addPairedOmaId(formatted)
                    app.discoveryManager.forceRefresh()
                    Toast.makeText(context, "OmaID eşleşti: $formatted", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showEditOmaIdDialog) {
        EditOmaIdDialog(
            currentId = omaIdentity.formattedId,
            onDismiss = { showEditOmaIdDialog = false },
            onSave = { newId ->
                try {
                    omaIdentity = app.setCustomOmaIdentity(newId)
                    showEditOmaIdDialog = false
                    Toast.makeText(context, "OmaID eşitlendi: ${omaIdentity.formattedId}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showResetOmaIdConfirmDialog) {
        ResetOmaIdConfirmDialog(
            onDismiss = { showResetOmaIdConfirmDialog = false },
            onConfirm = {
                omaIdentity = app.resetOmaIdentity()
                showResetOmaIdConfirmDialog = false
                Toast.makeText(context, "Yeni OmaID üretildi", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showNetworkStatusDialog) {
        NetworkStatusDialog(
            wanStatus = wanStatus,
            identity = omaIdentity,
            onDismiss = { showNetworkStatusDialog = false },
            onSwitchMode = { newMode ->
                app.wanDiscoveryEngine.setNetworkMode(newMode)
                Toast.makeText(context, "Ağ Modu: ${newMode.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showTargetSelectorDialog) {
        TargetDeviceSelectionDialog(
            peers = peers,
            onDismiss = { showTargetSelectorDialog = false },
            onSelect = { peer ->
                showTargetSelectorDialog = false
                selectedPeer = peer
                if (pendingUrisToSend.isNotEmpty()) {
                    scope.launch {
                        sendMultipleUrisToPeer(context, app, peer, pendingUrisToSend) { state ->
                            transferState = state
                            if (state is TransferProgressState.Success) refreshRecentFiles()
                        }
                        pendingUrisToSend = emptyList()
                    }
                }
            },
            onSendToAnotherOmaId = {
                showTargetSelectorDialog = false
                showSendToAnotherOmaIdDialog = true
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

private fun showTargetDeviceSheetOrFallback(
    peers: List<DiscoveredPeer>,
    onSelectTarget: (DiscoveredPeer) -> Unit,
    onNoTarget: () -> Unit
) {
    val onlineSameOwner = peers.filter { it.isSameOwner && it.lastSeen > 0L && it.ip.isNotBlank() }
    if (onlineSameOwner.size == 1) {
        onSelectTarget(onlineSameOwner.first())
    } else if (peers.size == 1 && peers.first().ip.isNotBlank()) {
        onSelectTarget(peers.first())
    } else {
        onNoTarget()
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
fun AnonymousNumberedIdentityCard(
    identity: OmaIdentity,
    discoveryMode: DiscoveryMode,
    onCopyId: () -> Unit,
    onShowQr: () -> Unit,
    onScanQr: () -> Unit,
    onEditId: () -> Unit,
    onCycleMode: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Color(0xFF10B981).copy(alpha = 0.38f),
                RoundedCornerShape(18.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ANONİM NUMARALI KİMLİK (SIFIR KAYIT • OMAID)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }

                Surface(
                    onClick = onCycleMode,
                    shape = RoundedCornerShape(8.dp),
                    color = if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981).copy(alpha = 0.35f) else Color.Transparent
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (discoveryMode) {
                                DiscoveryMode.EVERYONE -> "Mod: Herkes"
                                DiscoveryMode.KNOWN_PEERS -> "Mod: Bilinenler"
                                DiscoveryMode.OFF -> "Mod: Kapalı"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 16-Digit Anonymous Numbered Identity Display
            Surface(
                onClick = onCopyId,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = identity.formattedId,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Aynı OmaID'ye sahip cihazlarınız burada gruplanır; her transfer onay ister",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "OmaID Kopyala",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons: OmaID Değiştir / Eşitle, QR Göster, QR Tara
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onEditId,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(36.dp)
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "OmaID Değiştir / Eşitle",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onShowQr,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = "QR Göster",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "QR",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onScanQr,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "QR Tara",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Tara",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SameOwnerEmptyHintCard(
    onSyncOmaId: () -> Unit,
    onScanQr: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Devices,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Aynı OmaID'ye Sahip Cihazınız Henüz Görünmüyor",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Bilgisayarınızdaki 16 haneli OmaID'yi telefonunuzda 'OmaID Değiştir / Eşitle' düğmesiyle girdiğinizde kendi cihazlarınız burada listelenir. Güvenlik nedeniyle her transfer onay ister.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSyncOmaId,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Bilgisayar OmaID'sini Gir",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onScanQr,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "QR ile Eşle",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun OptimizedRadarPulseAvatar(
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    if (isScanning) {
        val infiniteTransition = rememberInfiniteTransition(label = "SingleNodeWaterRippleRadar")
        val waveProgress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "WaterWaveProgress"
        )
        val primaryColor = MaterialTheme.colorScheme.primary

        Box(
            modifier = modifier
                .size(56.dp)
                .drawWithCache {
                    onDrawBehind {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = size.minDimension * 0.96f
                        val baseRadius = size.minDimension * 0.38f

                        val numWaves = 4
                        for (i in 0 until numWaves) {
                            val phase = (waveProgress + i.toFloat() / numWaves) % 1.0f
                            val easedPhase = 1f - (1f - phase) * (1f - phase)
                            val currentRadius = baseRadius + (maxRadius - baseRadius) * easedPhase
                            val alpha = ((1f - phase) * (1f - phase) * 0.85f).coerceIn(0f, 1f)
                            val strokeWidth = (2.2f * (1f - phase * 0.6f)).dp.toPx()

                            drawCircle(
                                color = primaryColor.copy(alpha = alpha * 0.12f),
                                radius = currentRadius,
                                center = center
                            )
                            drawCircle(
                                color = primaryColor.copy(alpha = alpha * 0.85f),
                                radius = currentRadius,
                                center = center,
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun RadarPulseAvatar(
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    OptimizedRadarPulseAvatar(isScanning = isScanning, modifier = modifier)
}

@Composable
fun ModernPeerCard(
    peer: DiscoveredPeer,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onToggleTrust: () -> Unit = {},
    onSendClick: () -> Unit,
    onClipboardClick: (() -> Unit)? = null
) {
    val isSameOwner = peer.isSameOwner
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isSameOwner -> Color(0xFF10B981).copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
    }

    ElevatedCard(
        onClick = onCardClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                if (isSelected || isSameOwner) 1.5.dp else 1.dp,
                borderColor,
                RoundedCornerShape(18.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isSameOwner -> Color(0xFF10B981).copy(alpha = 0.18f)
                    peer.transport == "WAN" -> MaterialTheme.colorScheme.secondaryContainer
                    peer.transport == "DIRECT" -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = if (peer.name.lowercase().contains("phone") || peer.name.lowercase().contains("android")) {
                        Icons.Default.PhoneAndroid
                    } else if (peer.name.lowercase().contains("laptop") || peer.name.lowercase().contains("thinkpad")) {
                        Icons.Default.Laptop
                    } else {
                        Icons.Default.Computer
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = when {
                            isSameOwner -> Color(0xFF10B981)
                            peer.transport == "WAN" -> MaterialTheme.colorScheme.onSecondaryContainer
                            peer.transport == "DIRECT" -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (isSameOwner) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "AYNI OMAID",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onToggleTrust,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                if (peer.isTrusted) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (peer.isTrusted) "Güvenilen Cihaz" else "Güvenilir Olarak İşaretle",
                                tint = if (peer.isTrusted) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val isPaired = peer.isTrusted || peer.omaId.isNotEmpty()
                    val transportLabel = when {
                        peer.transport == "WAN" -> "WAN"
                        peer.transport == "DIRECT" -> "DİREKT"
                        isPaired -> "OMAID"
                        else -> "LAN"
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (transportLabel) {
                            "WAN" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            "DİREKT" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            "OMAID" -> Color(0xFF10B981).copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        }
                    ) {
                        Text(
                            text = transportLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = when (transportLabel) {
                                "WAN" -> MaterialTheme.colorScheme.secondary
                                "DİREKT" -> MaterialTheme.colorScheme.tertiary
                                "OMAID" -> Color(0xFF10B981)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (peer.omaId.isNotEmpty()) OmaIdentity.format(peer.omaId) else "${peer.ip}:${peer.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = JetBrainsMonoFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val isOnline = peer.lastSeen > 0L && peer.ip.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onClipboardClick != null && isOnline) {
                    IconButton(
                        onClick = onClipboardClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = "Pano Gönder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                FilledTonalButton(
                    onClick = onSendClick,
                    enabled = isOnline,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isOnline) "GÖNDER" else "BEKLİYOR",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun CleanEmptyStateCard(
    discoveryMode: DiscoveryMode,
    onEnableScan: () -> Unit,
    onSwitchToEveryone: () -> Unit,
    onSendToAnotherOmaId: () -> Unit = {},
    onAddDirectIp: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (discoveryMode) {
                            DiscoveryMode.OFF -> Icons.Default.WifiOff
                            DiscoveryMode.KNOWN_PEERS -> Icons.Default.Security
                            DiscoveryMode.EVERYONE -> Icons.Default.WifiTethering
                        },
                        contentDescription = null,
                        tint = if (discoveryMode == DiscoveryMode.OFF) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = when (discoveryMode) {
                    DiscoveryMode.OFF -> "Ağ Taraması Duraklatıldı"
                    DiscoveryMode.KNOWN_PEERS -> "Çevrede Farklı Cihaz Yok"
                    DiscoveryMode.EVERYONE -> "Çevredeki Cihazlar Aranıyor..."
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Başka bir kullanıcının 16 haneli OmaID'sine veya IP adresine doğrudan dosya gönderebilirsiniz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (discoveryMode == DiscoveryMode.OFF) {
                    Button(
                        onClick = onEnableScan,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Taramayı Başlat")
                    }
                } else {
                    Button(
                        onClick = onSendToAnotherOmaId,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Farklı OmaID'ye Gönder")
                    }
                    OutlinedButton(
                        onClick = onAddDirectIp,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("IP Ekle")
                    }
                }
            }
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
fun SendToAnotherOmaIdDialog(
    onDismiss: () -> Unit,
    onConfirmSend: (targetOmaId: String, optionalIp: String, optionalPort: Int) -> Unit
) {
    var omaIdInput by remember { mutableStateOf("") }
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("53317") }

    val cleanOmaId = omaIdInput.replace("-", "").replace(" ", "")
    val isValidOmaId = OmaIdentity.isValid(omaIdInput)
    val canSubmit = isValidOmaId || ipInput.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Farklı OmaID'ye Gönder",
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFontFamily
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Dosya göndermek istediğiniz alıcının 16 haneli OmaID kimliğini (veya biliyorsanız doğrudan IP adresini) girin. Karşı cihaza onay istemi iletilecektir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = omaIdInput,
                    onValueChange = { raw ->
                        val clean = raw.replace("-", "").replace(" ", "").uppercase().filter { it.isDigit() }.take(16)
                        omaIdInput = if (clean.isEmpty()) "" else clean.chunked(4).joinToString("-")
                    },
                    label = { Text("Alıcı 16 Haneli OmaID") },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    isError = cleanOmaId.length == 16 && !isValidOmaId,
                    shape = RoundedCornerShape(12.dp)
                )
                if (cleanOmaId.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isValidOmaId) "[Geçerli OmaID - Luhn Doğrulandı]" else "[16 Haneli Luhn OmaID Girin]",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (isValidOmaId) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("Alıcı IP Adresi (İsteğe Bağlı)") },
                    placeholder = { Text("Örn: 192.168.1.42") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("Port (Varsayılan: 53317)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portInput.toIntOrNull() ?: NetworkUtils.PORT
                    onConfirmSend(omaIdInput.trim(), ipInput.trim(), port)
                },
                enabled = canSubmit
            ) {
                Text("Dosya Seç & Gönder")
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
fun TargetDeviceSelectionDialog(
    peers: List<DiscoveredPeer>,
    onDismiss: () -> Unit,
    onSelect: (DiscoveredPeer) -> Unit,
    onSendToAnotherOmaId: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Gönderilecek Cihazı Seçin",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (peers.isEmpty()) {
                    Text(
                        text = "Şu anda yakında aktif cihaz bulunamadı. Karşı cihazın OmaSend'i açık tuttuğundan emin olun veya doğrudan 16 haneli OmaID / IP girerek gönderin.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(peers) { peer ->
                            Surface(
                                onClick = { onSelect(peer) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (peer.name.lowercase().contains("phone") || peer.name.lowercase().contains("android")) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                                        contentDescription = null,
                                        tint = if (peer.isSameOwner) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = peer.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (peer.isSameOwner) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "[KENDİ CİHAZIM]",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    color = Color(0xFF10B981),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (peer.omaId.isNotEmpty()) "${peer.ip} • OmaID: ${OmaIdentity.format(peer.omaId)}" else "${peer.ip} • ${peer.transport}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSendToAnotherOmaId,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Farklı OmaID veya IP Girerek Gönder")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

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
                    text = "'${prompt.senderName}' cihazından dosya gönderilmek isteniyor:",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
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
                Text("Kabul Et")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Text("Reddet")
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
                    text = "Omarchy Linux & Android Hibrit Güvenli Transfer Sistemi. Kayıtsız (Zero-Log) 16 haneli anonim numaralı OmaID kimliğiyle cihazlarınız arasında onaylı P2P AirBridge aktarımı sunar.",
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
                        InfoDialogRow("Ağ Protokolü", "P2P AirBridge (53317)")
                        InfoDialogRow("Kendi Cihazlarım", "Aynı OmaID • Onaylı")
                        InfoDialogRow("Kimlik Modeli", "Anonim Numaralı OmaID (Sıfır Kayıt)")
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
    if (peer.port <= 0) return

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip
    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null

    if (text.isNullOrEmpty()) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Telefon panosu boş! Önce bir metin kopyalayın.", Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val result = app.client.sendClipboard(peer.ip, peer.port, text, peer.omaId)
        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                app.soundEngine.playSendSound()
                app.hapticController.onDropBounce()
                onState(TransferProgressState.Success("Pano metni '${peer.name}' cihazına aktarıldı"))
                Toast.makeText(context, "Pano metni '${peer.name}' cihazına aktarıldı!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Pano aktarılamadı: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
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

@Composable
fun PeerCard(
    peer: DiscoveredPeer,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onSendFile: () -> Unit = {}
) {
    ModernPeerCard(
        peer = peer,
        isSelected = isSelected,
        onCardClick = onClick,
        onSendClick = onSendFile
    )
}

fun getDeviceTypeBadge(senderName: String): String {
    val lower = senderName.lowercase()
    return when {
        lower.contains("pc") || lower.contains("desktop") || lower.contains("laptop") || lower.contains("arch") || lower.contains("linux") || lower.contains("omarchy") -> "Masaüstü"
        lower.contains("phone") || lower.contains("android") || lower.contains("mobile") || lower.contains("pixel") || lower.contains("samsung") || lower.contains("xiaomi") -> "Telefon"
        else -> senderName
    }
}

@Composable
fun StealthActionDock(
    selectedPeer: DiscoveredPeer?,
    onSendFiles: () -> Unit,
    onSendMedia: () -> Unit,
    onSendToAnotherOmaId: () -> Unit = {},
    onQuickDrop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onSendFiles,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .weight(1.25f)
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedPeer != null) "Gönder (${selectedPeer.name.take(9)})" else "Dosya Gönder",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FilledTonalButton(
                onClick = onSendMedia,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .weight(0.9f)
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Galeri",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Surface(
                onClick = onSendToAnotherOmaId,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "Farklı OmaID'ye Gönder",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Surface(
                onClick = onQuickDrop,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = "Web QR",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkStatusBadge(
    wanStatus: WanStatus,
    onClick: () -> Unit
) {
    val (badgeText, dotColor) = when (wanStatus.mode) {
        NetworkTransportMode.LAN -> Pair("LAN", Color(0xFF10B981))
        NetworkTransportMode.WAN -> Pair("WAN", Color(0xFF38BDF8))
        NetworkTransportMode.P2P_MESH -> Pair("MESH", Color(0xFF00E5FF))
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = dotColor.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = dotColor
            )
        }
    }
}

@Composable
fun OmaIdQrDialog(
    identity: OmaIdentity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(identity.formattedId) {
        withContext(Dispatchers.Default) {
            try {
                val writer = QRCodeWriter()
                val qrContent = "omasend://identity/${identity.rawId}"
                val bitMatrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 512, 512)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bmp.setPixel(
                            x,
                            y,
                            if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        )
                    }
                }
                qrBitmap = bmp
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "OmaID QR Paylaşım",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "OmaID QR",
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(8.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = identity.formattedId,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Bu QR kodu diğer cihazınızdan taratarak veya 16 haneli kimliği girerek kör buluşma ile doğrudan dosya aktarabilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("OmaID", identity.formattedId)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(context, "OmaID panoya kopyalandı", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Kopyala")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
fun EditOmaIdDialog(
    currentId: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember { mutableStateOf(currentId) }
    val isValid = OmaIdentity.isValid(input)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Kendi OmaID Kimliğimi Değiştir / Eşitle",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ANONİM NUMARALI KİMLİK • KENDİ CİHAZLARIM",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Bilgisayarınızdaki 16 haneli OmaID'yi buraya girdiğinizde telefonunuz ve bilgisayarınız aynı kullanıcı kimliğini paylaşır. Aynı OmaID'ye sahip cihazlar 'Kendi Cihazlarım' altında gruplanır; güvenlik nedeniyle her transfer onay ister.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { rawNewValue ->
                        val clean = rawNewValue.replace("-", "").replace(" ", "").uppercase().filter { it.isDigit() }.take(16)
                        input = if (clean.isEmpty()) "" else clean.chunked(4).joinToString("-")
                    },
                    label = { Text("16 Haneli Kullanıcı OmaID") },
                    placeholder = { Text("4829-1048-5729-1104") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    isError = input.isNotBlank() && !isValid
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { input = OmaIdentity.generate() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Rastgele Yeni Kimlik",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (input.isNotBlank()) {
                        if (isValid) {
                            Text(
                                text = "[Luhn mod 10 Doğrulandı]",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = Color(0xFF10B981)
                            )
                        } else {
                            Text(
                                text = "[Geçersiz Checksum]",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(input) },
                enabled = isValid
            ) {
                Text("Kaydet & Eşitle")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
fun ResetOmaIdConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Yeni OmaID Oluşturulsun mu?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Yeni bir 16 haneli hesapsız kimlik üretildiğinde kör buluşma konunuz değişir. Eski kimliğinizi kullanan eşler yeni kimliğinizi ekleyene kadar doğrudan WAN üzerinden bağlanamaz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Yeni Kimlik Üret")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Vazgeç")
            }
        }
    )
}

@Composable
fun NetworkStatusDialog(
    wanStatus: WanStatus,
    identity: OmaIdentity,
    onDismiss: () -> Unit,
    onSwitchMode: (NetworkTransportMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ağ ve Kör Buluşma Durumu",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Ağ Taşıma Modu Seçimi",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                NetworkTransportMode.LAN to "LAN",
                                NetworkTransportMode.WAN to "WAN",
                                NetworkTransportMode.P2P_MESH to "P2P Mesh"
                            ).forEach { (mode, label) ->
                                val isSelected = wanStatus.mode == mode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSwitchMode(mode) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "STUN ve Uç Nokta Bilgisi",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Yerel IP: ${NetworkUtils.getLocalIpAddress()}:53317",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (wanStatus.publicIp != null) "Genel WAN IP: ${wanStatus.publicIp}:${wanStatus.publicPort}" else "STUN Keşfi: Aranıyor...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (wanStatus.publicIp != null) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Kör Konu (HMAC):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = identity.blindTopic,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
