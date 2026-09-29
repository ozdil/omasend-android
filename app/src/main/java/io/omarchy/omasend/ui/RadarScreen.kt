package io.omarchy.omasend.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.model.*
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.network.StorageUtils
import io.omarchy.omasend.network.TransferBridge
import io.omarchy.omasend.service.OmaSendForegroundService
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

    var activeTargetPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showDirectIpDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRecentFilesSheet by remember { mutableStateOf(false) }

    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var clipboardText by remember { mutableStateOf<String?>(null) }
    val refreshRotation = remember { Animatable(0f) }

    fun refreshRecentFiles() {
        recentFiles = getRecentReceivedFiles()
    }

    fun checkClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val isSensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
            } else {
                false
            }
            if (!isSensitive) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrBlank() && text.length <= 1048576) {
                    clipboardText = text
                } else {
                    clipboardText = null
                }
            } else {
                clipboardText = null
            }
        } else {
            clipboardText = null
        }
    }

    LaunchedEffect(Unit) {
        refreshRecentFiles()
        checkClipboard()
    }

    // Dosya Secici
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty() && activeTargetPeer != null) {
            val peer = activeTargetPeer!!
            scope.launch {
                sendMultipleUrisToPeer(context, app, peer, uris) { state ->
                    transferState = state
                    if (state is TransferProgressState.Success) refreshRecentFiles()
                }
            }
        }
    }

    // Medya Secici (Fotograf / Video)
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty() && activeTargetPeer != null) {
            val peer = activeTargetPeer!!
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "OMASEND",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    letterSpacing = 1.2.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (discoveryMode != DiscoveryMode.OFF) Color(0xFF10B981)
                                            else Color(0xFF6B7280)
                                        )
                                )
                            }
                            Text(
                                text = "${NetworkUtils.getDeviceName(context)} (${NetworkUtils.getLocalIpAddress()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshRotation.animateTo(
                                    targetValue = refreshRotation.value + 360f,
                                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                                )
                                app.discoveryManager.forceRefresh()
                                refreshRecentFiles()
                                checkClipboard()
                            }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            modifier = Modifier.rotate(refreshRotation.value).size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showDirectIpDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.AddLink,
                            contentDescription = "IP ile Baglan",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showQrDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = "Web Portali QR",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Hakkinda",
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
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Gorunurluk Secici (Segmented Selector)
                ZenVisibilityBar(
                    currentMode = discoveryMode,
                    onModeSelected = { newMode ->
                        app.discoveryManager.setMode(newMode)
                        if (newMode == DiscoveryMode.OFF) {
                            OmaSendForegroundService.stopService(context)
                            Toast.makeText(context, "Gorunurluk kapatildi", Toast.LENGTH_SHORT).show()
                        } else {
                            OmaSendForegroundService.startService(context)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Aktif Transfer Durumu
                if (transferState !is TransferProgressState.Idle) {
                    TransferStatusCard(
                        state = transferState,
                        onDismiss = { transferState = TransferProgressState.Idle }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // 3. Ana Merkez: Cihaz Listesi / Radar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (peers.isEmpty()) {
                    EmptyDiscoveryRadar(
                        isScanning = discoveryMode != DiscoveryMode.OFF,
                        localIp = NetworkUtils.getLocalIpAddress(),
                        onDirectIpClick = { showDirectIpDialog = true }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "YAKINDAKI CIHAZLAR (${peers.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "Sifir Guven • BLAKE3",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        items(peers, key = { it.id }) { peer ->
                            PeerActionCard(
                                peer = peer,
                                onTrustToggle = { app.discoveryManager.toggleTrust(peer.id) },
                                onSendFile = {
                                    activeTargetPeer = peer
                                    filePickerLauncher.launch("*/*")
                                },
                                onSendMedia = {
                                    activeTargetPeer = peer
                                    mediaPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                onSendClipboard = {
                                    OmaSendHaptics.performTransferStart(context)
                                    scope.launch {
                                        sendClipboardToPeer(context, app, peer) { state ->
                                            transferState = state
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 4. Alt Akilli Alan: Pano Kartı + Alt Islemler
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!clipboardText.isNullOrBlank()) {
                    val preview = clipboardText!!.trim()
                    val targetPeer = peers.firstOrNull()

                    LiveClipboardCard(
                        preview = preview,
                        targetPeer = targetPeer,
                        onSend = {
                            if (targetPeer == null) {
                                Toast.makeText(context, "Pano iletimi icin yakinda cihaz bulunamadi", Toast.LENGTH_SHORT).show()
                            } else {
                                OmaSendHaptics.performTransferStart(context)
                                scope.launch {
                                    sendClipboardToPeer(context, app, targetPeer) { state ->
                                        transferState = state
                                    }
                                }
                            }
                        }
                    )
                }

                CleanBottomBar(
                    recentFilesCount = recentFiles.size,
                    onRenameClick = { showRenameDialog = true },
                    onRecentFilesClick = { showRecentFilesSheet = true }
                )
            }
        }
    }

    // Modal Sheet: Son Alinan Dosyalar
    if (showRecentFilesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRecentFilesSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SON ALINAN DOSYALAR",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Downloads/OmaSend",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))

                if (recentFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Henuz alinan bir dosya bulunmuyor",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentFiles) { file ->
                            RecentFileRow(
                                file = file,
                                onOpenFile = { openFileWithSystem(context, file) },
                                onShareFile = { shareFileWithSystem(context, file) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Diyaloglar
    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = NetworkUtils.getDeviceName(context),
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                NetworkUtils.setDeviceName(context, newName)
                showRenameDialog = false
                Toast.makeText(context, "Cihaz adi kaydedildi: $newName", Toast.LENGTH_SHORT).show()
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

    if (showInfoDialog) {
        InfoDialog(onDismiss = { showInfoDialog = false })
    }

    // Gelen Transfer Onay Istemi (Sifir Guven Dogrulamasi)
    if (incomingPrompt != null) {
        IncomingTransferDialog(
            prompt = incomingPrompt,
            onAccept = { onAcceptPrompt(incomingPrompt.token) },
            onDecline = { onDeclinePrompt(incomingPrompt.token) }
        )
    }
}

// ------------------- KART VE BILESEN TANIMLARI -------------------

@Composable
fun ZenVisibilityBar(
    currentMode: DiscoveryMode,
    onModeSelected: (DiscoveryMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                Triple(DiscoveryMode.EVERYONE, "Herkes", Icons.Default.Public),
                Triple(DiscoveryMode.KNOWN_PEERS, "Bilinenler", Icons.Default.VerifiedUser),
                Triple(DiscoveryMode.OFF, "Görünmez", Icons.Default.Lock)
            ).forEach { (mode, label, icon) ->
                val isSelected = currentMode == mode
                Surface(
                    onClick = { onModeSelected(mode) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyDiscoveryRadar(
    isScanning: Boolean,
    localIp: String,
    onDirectIpClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSonar")
    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse1"
    )
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha1"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isScanning) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .scale(pulse1)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha1), CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(200.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), CircleShape)
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isScanning) Icons.Default.WifiTethering else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isScanning) "Cihazlar Bekleniyor" else "Tarama Duraklatildi",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isScanning)
                        "Omarchy Linux panelini acin veya ayni yerel Wi-Fi agina baglanin ($localIp:53317)."
                    else
                        "Cihazlari aramak icin gorunurluk modunu acin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onDirectIpClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dogrudan IP Ekle", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun PeerActionCard(
    peer: DiscoveredPeer,
    onTrustToggle: () -> Unit,
    onSendFile: () -> Unit,
    onSendMedia: () -> Unit,
    onSendClipboard: () -> Unit
) {
    val isOmarchyLinux = peer.name.lowercase().contains("omarchy") || peer.name.lowercase().contains("arch") || peer.name.lowercase().contains("linux")
    val isDesktop = isOmarchyLinux || peer.name.lowercase().contains("pc") || peer.name.lowercase().contains("laptop")

    val deviceIcon = when {
        peer.transport == "BT" -> Icons.Default.Bluetooth
        isDesktop -> Icons.Default.Computer
        else -> Icons.Default.PhoneAndroid
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (peer.isTrusted) Color(0xFF10B981).copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Ust Baslik Satiri
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isOmarchyLinux) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                deviceIcon,
                                contentDescription = null,
                                tint = if (isOmarchyLinux) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = peer.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (peer.isTrusted) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF10B981).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "GUVENILEN",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981),
                                        fontSize = 8.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${peer.ip}:${peer.port} • ${peer.transport}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                IconButton(
                    onClick = onTrustToggle,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (peer.isTrusted) Icons.Default.Star else Icons.Default.StarOutline,
                        contentDescription = "Guven Durumu",
                        tint = if (peer.isTrusted) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))

            // Hizli Eylem Butonlari
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendFile,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dosya", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = onSendMedia,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Medya", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onSendClipboard,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(Icons.Default.ContentPasteGo, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pano", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LiveClipboardCard(
    preview: String,
    targetPeer: DiscoveredPeer?,
    onSend: () -> Unit
) {
    Surface(
        onClick = onSend,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "PANODAKI METIN HAZIR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (targetPeer != null) "${targetPeer.name}'a Isinla" else "Isinla",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CleanBottomBar(
    recentFilesCount: Int,
    onRenameClick: () -> Unit,
    onRecentFilesClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onRenameClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cihaz Adi", style = MaterialTheme.typography.labelSmall)
            }

            TextButton(
                onClick = onRecentFilesClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Son Alinanlar ($recentFilesCount)", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun TransferStatusCard(
    state: TransferProgressState,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            when (state) {
                is TransferProgressState.Success -> Color(0xFF10B981)
                is TransferProgressState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
                is TransferProgressState.Requesting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "'${state.peerName}' cihazina transfer istegi iletiliyor...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is TransferProgressState.WaitingConsent -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "'${state.peerName}' cihazindan onay bekleniyor...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is TransferProgressState.Transferring -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.isUploading) "Gonderiliyor: ${state.fileName}" else "Aliniyor: ${state.fileName}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "%${state.percent}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${NetworkUtils.formatBytes(state.bytesTransferred)} / ${NetworkUtils.formatBytes(state.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is TransferProgressState.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
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
fun RecentFileRow(
    file: File,
    onOpenFile: () -> Unit,
    onShareFile: () -> Unit
) {
    Surface(
        onClick = onOpenFile,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        getFileIcon(file.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = NetworkUtils.formatBytes(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShareFile, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Paylas",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
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
        "zip", "tar", "gz", "7z", "apk", "aab" -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

// ------------------- DİYALOGLAR -------------------

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
            Text(text = "Cihaz Adini Duzenle", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Bu ad yakindaki Omarchy ve OmaSend cihazlarina gorunecektir:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Cihaz Adi") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
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
                Text("Iptal")
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
                Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Dosya Aktarim Istegi",
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
                    text = "'${prompt.senderName}' (${prompt.senderIp}) cihazi size dosya gondermek istiyor:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        prompt.files.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = NetworkUtils.formatBytes(file.size_bytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Toplam Boyut",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = NetworkUtils.formatBytes(prompt.totalSizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("Kabul Et", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDecline,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Reddet")
            }
        }
    )
}

@Composable
fun QrCodeDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val localIp = NetworkUtils.getLocalIpAddress()
    val endpointUrl = "http://$localIp:${NetworkUtils.PORT}"

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(endpointUrl) {
        withContext(Dispatchers.IO) {
            qrBitmap = TransferBridge.generateQrCode(endpointUrl, 512)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Web Indirme Portali (QR)", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Ayni agdaki herhangi bir tarayicidan bu kodu okutarak dogrudan baglanabilirsiniz:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    modifier = Modifier.size(200.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
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
                    shape = RoundedCornerShape(8.dp),
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
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("OmaSend Endpoint", endpointUrl))
                                Toast.makeText(context, "URL kopyalandi", Toast.LENGTH_SHORT).show()
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
                Text("Kapat")
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
            Text(text = "Dogrudan IP ile Baglan", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Hedef Omarchy veya OmaSend cihazinin IP adresini girin:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP Adresi (orn. 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port (Varsayilan: 53317)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
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
                Text("Baglan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Iptal")
            }
        }
    )
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "OmaSend Guvenlik & Bilgi", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "OmaSend v1.3.1",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Omarchy Linux & Android Hibrit Paylasim Ekosistemi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                InfoDialogRow("Ag Protokolu", "P2P AirBridge + UDP Beacon (53317)")
                InfoDialogRow("Kriptografi", "BLAKE3 + SHA-256 + Ephemeral Token")
                InfoDialogRow("Bellek Guvenligi", "RAM Anti-Forensics Zeroization")
                InfoDialogRow("Cevrimdisi", "Bluetooth OBEX Push (OPP)")
                InfoDialogRow("Gelistirici", "Ozan Ozdil (Omarchy Ecosystem)")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
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
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

// ------------------- AKTARIM VE DOSYA ISLEMLERI -------------------

suspend fun sendMultipleUrisToPeer(
    context: Context,
    app: OmaSendApp,
    peer: DiscoveredPeer,
    uris: List<Uri>,
    onState: (TransferProgressState) -> Unit
) {
    if (uris.isEmpty()) return
    for (uri in uris) {
        sendFileUriToPeer(context, app, peer, uri, onState)
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

            // Bluetooth dogrudan aktarim
            if (peer.transport == "BT" || peer.ip.startsWith("bt:")) {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Transferring(
                        isUploading = true,
                        peerName = peer.name,
                        fileName = fileName,
                        bytesTransferred = 0L,
                        totalBytes = fileSize,
                        percent = 0
                    ))
                }
                val btMac = peer.fingerprint.ifEmpty { peer.ip }
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    val obexResult = stream.use { s ->
                        TransferBridge.sendViaBluetoothDirectObex(
                            context = context,
                            targetMac = btMac,
                            fileName = fileName,
                            fileSize = fileSize,
                            inputStream = s
                        ) { sent, total, pct ->
                            withContext(Dispatchers.Main) {
                                onState(TransferProgressState.Transferring(
                                    isUploading = true,
                                    peerName = peer.name,
                                    fileName = fileName,
                                    bytesTransferred = sent,
                                    totalBytes = total,
                                    percent = pct
                                ))
                            }
                        }
                    }
                    if (obexResult.isSuccess) {
                        withContext(Dispatchers.Main) {
                            onState(TransferProgressState.Success("'$fileName' Bluetooth ile aktarildi!"))
                        }
                        return@withContext
                    }
                }
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Idle)
                    TransferBridge.sendViaBluetooth(context, listOf(uri), targetMac = btMac)
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                OmaSendHaptics.performTransferStart(context)
                onState(TransferProgressState.Requesting(peer.name, fileName))
            }

            val checksums = try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    StorageUtils.computeChecksums(s)
                }
            } catch (_: Exception) { null }

            val fileInfo = TransferFileInfo(
                name = fileName,
                size_bytes = fileSize,
                sha256 = checksums?.sha256Hex,
                md5 = checksums?.md5Hex
            )
            val requestResult = app.client.sendTransferRequest(peer.ip, peer.port, listOf(fileInfo))

            val token = requestResult.getOrElse {
                if (peer.transport == "HYBRID" || peer.fingerprint.isNotEmpty() || peer.ip.startsWith("bt:")) {
                    val btMac = peer.fingerprint.ifEmpty { peer.ip }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Wi-Fi yanit vermedi. Bluetooth ile aktariliyor...", Toast.LENGTH_SHORT).show()
                        onState(TransferProgressState.Transferring(
                            isUploading = true,
                            peerName = peer.name,
                            fileName = fileName,
                            bytesTransferred = 0L,
                            totalBytes = fileSize,
                            percent = 0
                        ))
                    }
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val obexResult = stream.use { s ->
                            TransferBridge.sendViaBluetoothDirectObex(
                                context = context,
                                targetMac = btMac,
                                fileName = fileName,
                                fileSize = fileSize,
                                inputStream = s
                            ) { sent, total, pct ->
                                withContext(Dispatchers.Main) {
                                    onState(TransferProgressState.Transferring(
                                        isUploading = true,
                                        peerName = peer.name,
                                        fileName = fileName,
                                        bytesTransferred = sent,
                                        totalBytes = total,
                                        percent = pct
                                    ))
                                }
                            }
                        }
                        if (obexResult.isSuccess) {
                            withContext(Dispatchers.Main) {
                                onState(TransferProgressState.Success("'$fileName' Bluetooth ile aktarildi!"))
                            }
                            return@withContext
                        }
                    }
                    withContext(Dispatchers.Main) {
                        onState(TransferProgressState.Idle)
                        TransferBridge.sendViaBluetooth(context, listOf(uri), targetMac = btMac)
                    }
                    return@withContext
                }
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error(it.message ?: "Transfer istegi basarisiz oldu"))
                }
                return@withContext
            }

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

            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error("Dosya akisi acilamadi"))
                }
                return@withContext
            }

            val uploadResult = app.client.uploadFileStream(
                targetIp = peer.ip,
                targetPort = peer.port,
                token = token,
                filename = fileName,
                totalBytes = fileSize,
                inputStream = inputStream,
                sha256 = checksums?.sha256Hex,
                md5 = checksums?.md5Hex
            ) { bytes: Long, total: Long, pct: Int ->
                scopeLaunchMain {
                    onState(TransferProgressState.Transferring(true, peer.name, fileName, bytes, total, pct))
                }
            }

            withContext(Dispatchers.Main) {
                if (uploadResult.isSuccess) {
                    OmaSendHaptics.performTransferSuccess(context)
                    onState(TransferProgressState.Success("'$fileName' basariyla gonderildi!"))
                } else {
                    OmaSendHaptics.performTransferError(context)
                    onState(TransferProgressState.Error(uploadResult.exceptionOrNull()?.message ?: "Yukleme basarisiz"))
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                OmaSendHaptics.performTransferError(context)
                onState(TransferProgressState.Error(e.message ?: "Transfer hatasi"))
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
    if (peer.transport == "BT" || peer.ip.startsWith("bt:")) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Pano senkronizasyonu yalnizca Wi-Fi (LAN) baglantisiyla desteklenir.", Toast.LENGTH_SHORT).show()
        }
        return
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip

    val description = clip?.description
    val isSensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
    } else {
        false
    }
    if (isSensitive) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Hassas veri kalkani: Parola veya ozel icerikler aga aktarilmaz.", Toast.LENGTH_LONG).show()
        }
        return
    }

    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null

    if (text.isNullOrEmpty()) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Telefon panosu bos! Once bir metin kopyalayin.", Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val result = app.client.sendClipboard(peer.ip, peer.port, text)
        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                Toast.makeText(context, "Pano metni '${peer.name}' cihazina aktarildi!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Pano aktarilamadi: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun scopeLaunchMain(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(block)
}

fun getRecentReceivedFiles(): List<File> {
    return try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmaSend")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5) ?: emptyList()
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun openFileWithSystem(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
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
        Toast.makeText(context, "Dosya acilamadi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun shareFileWithSystem(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Dosyayi Paylas"))
    } catch (e: Exception) {
        Toast.makeText(context, "Paylasilamadi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PeerCard(
    peer: DiscoveredPeer,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onSendFile: () -> Unit = {},
    onSendClipboard: () -> Unit = {}
) {
    PeerActionCard(
        peer = peer,
        onTrustToggle = {},
        onSendFile = onSendFile,
        onSendMedia = onSendFile,
        onSendClipboard = onSendClipboard
    )
}
