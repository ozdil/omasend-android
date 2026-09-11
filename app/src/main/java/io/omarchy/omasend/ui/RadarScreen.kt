package io.omarchy.omasend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import io.omarchy.omasend.model.DiscoveryMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.model.TransferProgressState
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.network.TransferBridge
import io.omarchy.omasend.service.OmaSendForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var selectedPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showDirectIpDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && selectedPeer != null) {
            val peer = selectedPeer!!
            scope.launch {
                sendFileUriToPeer(context, app, peer, uri) { state ->
                    transferState = state
                }
            }
        }
    }

    val bluetoothFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            TransferBridge.sendViaBluetooth(context, uris)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "OmaSend",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.clickable { showInfoDialog = true }
                        ) {
                            Text(
                                text = "v1.0.3",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Hakkında & Sürüm Bilgisi",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showQrDialog = true }) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = "QR / Barkod",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showDirectIpDialog = true }) {
                        Icon(
                            Icons.Default.AddLink,
                            contentDescription = "Doğrudan IP",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        app.discoveryManager.setMode(DiscoveryMode.EVERYONE)
                        OmaSendForegroundService.startService(context)
                        Toast.makeText(context, "Scanning local network...", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Scan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Local Device Status Banner
            LocalDeviceHeroCard(context = context)

            Spacer(modifier = Modifier.height(10.dp))

            // Airdrop & Peer Discovery Segmented Card (Google UI Material 3)
            AirDropDiscoveryCard(
                currentMode = discoveryMode,
                onModeSelected = { newMode ->
                    app.discoveryManager.setMode(newMode)
                    if (newMode == DiscoveryMode.OFF) {
                        OmaSendForegroundService.stopService(context)
                        Toast.makeText(context, "Radar kapatıldı. Ağ taranmıyor.", Toast.LENGTH_SHORT).show()
                    } else {
                        OmaSendForegroundService.startService(context)
                        val msg = if (newMode == DiscoveryMode.KNOWN_PEERS) {
                            "Yalnızca bilinen eşler taranıyor"
                        } else {
                            "Ağ taraması aktif (Herkes)"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Active Transfer Status Indicator
            AnimatedVisibility(visible = transferState !is TransferProgressState.Idle) {
                Column {
                    TransferStatusCard(
                        state = transferState,
                        onDismiss = { transferState = TransferProgressState.Idle }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Radar Scan Header with Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NEARBY PEERS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = if (peers.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${peers.size}",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (peers.isNotEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (discoveryMode != DiscoveryMode.OFF) {
                    PulseBeaconIndicator()
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "PAUSED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (peers.isEmpty()) {
                ModernRadarEmptyState(
                    isOff = discoveryMode == DiscoveryMode.OFF,
                    onStartScan = {
                        app.discoveryManager.setMode(DiscoveryMode.EVERYONE)
                        OmaSendForegroundService.startService(context)
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(peers, key = { it.id }) { peer ->
                        val isSelected = selectedPeer?.id == peer.id
                        PeerCard(
                            peer = peer,
                            isSelected = isSelected,
                            onClick = { selectedPeer = if (isSelected) null else peer },
                            onSendFile = {
                                selectedPeer = peer
                                filePickerLauncher.launch("*/*")
                            },
                            onSendClipboard = {
                                selectedPeer = peer
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

            // Selected Peer Quick Actions Sheet
            AnimatedVisibility(visible = selectedPeer != null) {
                selectedPeer?.let { peer ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Hedef: ${peer.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Gönderilecek işlem türünü seçin:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { selectedPeer = null }) {
                                    Text("Kapat", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Dosya Seç", fontWeight = FontWeight.SemiBold)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            sendClipboardToPeer(context, app, peer) { state ->
                                                transferState = state
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Pano Gönder", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // Alternative Transfer Channels Row (Bluetooth, QR Code, Direct IP)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { bluetoothFilePicker.launch("*/*") }
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Bluetooth",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(
                        onClick = { showQrDialog = true }
                    ) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "QR / Barkod",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(
                        onClick = { showDirectIpDialog = true }
                    ) {
                        Icon(
                            Icons.Default.AddLink,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Manuel IP",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Incoming Transfer Consent Dialog
        if (incomingPrompt != null) {
            AlertDialog(
                onDismissRequest = { onDeclinePrompt(incomingPrompt.token) },
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                icon = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = "Incoming Transfer",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "${incomingPrompt.senderName} wants to share files with you:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                incomingPrompt.files.forEach { file ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = NetworkUtils.formatBytes(file.size_bytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Total Payload: ${NetworkUtils.formatBytes(incomingPrompt.totalSizeBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onAcceptPrompt(incomingPrompt.token) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Accept", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { onDeclinePrompt(incomingPrompt.token) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Decline", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }

        // Settings Dialog
        if (showSettingsDialog) {
            var newName by remember { mutableStateOf(NetworkUtils.getDeviceName(context)) }
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = {
                    Text(
                        text = "Cihaz Ayarları",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Cihaz Adı (Ağda ve radarda görünen isim):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                showSettingsDialog = false
                                showInfoDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uygulama Bilgisi & Sürüm (v1.0.3)")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            NetworkUtils.setDeviceName(context, newName)
                            showSettingsDialog = false
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Kaydet")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Vazgeç")
                    }
                }
            )
        }

        // Info / About Dialog
        if (showInfoDialog) {
            InfoDialog(
                context = context,
                onDismiss = { showInfoDialog = false }
            )
        }

        if (showQrDialog) {
            QrCodeDialog(
                onDismiss = { showQrDialog = false },
                onConnectEndpoint = { endpoint ->
                    val parsed = TransferBridge.parseConnectionEndpoint(endpoint)
                    if (parsed != null) {
                        app.discoveryManager.addManualPeer(parsed.first, parsed.second)
                        Toast.makeText(context, "Cihaz eklendi: ${parsed.first}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Girdi geçersiz IPv4 veya endpoint", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        if (showDirectIpDialog) {
            DirectIpDialog(
                onDismiss = { showDirectIpDialog = false },
                onConnect = { ip, port ->
                    app.discoveryManager.addManualPeer(ip, port)
                    Toast.makeText(context, "Doğrudan bağlantı eklendi: $ip:$port", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}


@Composable
fun LocalDeviceHeroCard(context: Context) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = NetworkUtils.getDeviceName(context),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${NetworkUtils.getLocalIpAddress()}:53317",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun AirDropDiscoveryCard(
    currentMode: DiscoveryMode,
    onModeSelected: (DiscoveryMode) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Title + [BT READY] Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = if (currentMode != DiscoveryMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AIRDROP & PEER DISCOVERY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "BT READY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Google UI Material 3 Segmented Control Row
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // OFF Button
                    val isOff = currentMode == DiscoveryMode.OFF
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onModeSelected(DiscoveryMode.OFF) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOff) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f) else Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "OFF",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isOff) FontWeight.Bold else FontWeight.Medium,
                                color = if (isOff) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // KNOWN PEERS Button
                    val isKnown = currentMode == DiscoveryMode.KNOWN_PEERS
                    Surface(
                        modifier = Modifier
                            .weight(1.3f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onModeSelected(DiscoveryMode.KNOWN_PEERS) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isKnown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "KNOWN PEERS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isKnown) FontWeight.Bold else FontWeight.Medium,
                                color = if (isKnown) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // EVERYONE Button
                    val isEveryone = currentMode == DiscoveryMode.EVERYONE
                    Surface(
                        modifier = Modifier
                            .weight(1.3f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onModeSelected(DiscoveryMode.EVERYONE) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isEveryone) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "EVERYONE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isEveryone) FontWeight.Bold else FontWeight.Medium,
                                color = if (isEveryone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val statusText = when (currentMode) {
                DiscoveryMode.OFF -> "Radar duraklatıldı. Tarama kapalı, pil tüketimi yok..."
                DiscoveryMode.KNOWN_PEERS -> "Yalnızca doğrudan eklenen veya bilinen eşler taranıyor..."
                DiscoveryMode.EVERYONE -> "Scanning for nearby Omarchy devices on local network & Bluetooth..."
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (currentMode == DiscoveryMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp
            )
        }
    }
}

@Composable
fun PeerCard(
    peer: DiscoveredPeer,
    isSelected: Boolean,
    onClick: () -> Unit,
    onSendFile: () -> Unit,
    onSendClipboard: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                else Modifier
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Device Information Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
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
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${peer.ip}:${peer.port} • ${peer.transport}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "BAĞLI",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // Clear, Explicit Google UI Buttons: [Dosya Gönder] and [Pano Gönder]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button 1: Dosya Gönder
                Button(
                    onClick = onSendFile,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Dosya Gönder",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Button 2: Pano Gönder
                FilledTonalButton(
                    onClick = onSendClipboard,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pano Gönder",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ModernRadarEmptyState(
    isOff: Boolean,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.elevatedCardColors(
            containerColor = surfaceColor
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isOff) {
                // Dormant / Standby Radar View
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(20.dp)
                ) {
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val maxRadius = size.minDimension / 2f
                            val centerOffset = center
                            // Dimmed rings
                            for (ratio in listOf(0.3f, 0.6f, 0.9f)) {
                                drawCircle(
                                    color = Color.Gray.copy(alpha = 0.15f),
                                    radius = maxRadius * ratio,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                            // Crosshairs
                            drawLine(
                                color = Color.Gray.copy(alpha = 0.15f),
                                start = Offset(centerOffset.x, centerOffset.y - maxRadius * 0.9f),
                                end = Offset(centerOffset.x, centerOffset.y + maxRadius * 0.9f),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.Gray.copy(alpha = 0.15f),
                                start = Offset(centerOffset.x - maxRadius * 0.9f, centerOffset.y),
                                end = Offset(centerOffset.x + maxRadius * 0.9f, centerOffset.y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(60.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Radar Duraklatıldı",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Arka plan taraması kapalı. Pil tasarrufu devrede. Çevredeki cihazları görmek için radarı başlatın.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onStartScan,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            Icons.Default.WifiTethering,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Radarı Başlat (Herkes)", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Real Sci-Fi Tactical Sweep Radar
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Tactical Telemetry Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "PORT: 53317 / UDP",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "SWEEP: 360° CW",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor.copy(alpha = 0.7f)
                        )
                    }

                    // Tactical Radar Canvas
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val maxRadius = size.minDimension / 2f
                            val centerOffset = center

                            // 1. Tactical Circular Bezel
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.08f),
                                radius = maxRadius
                            )

                            // 2. Concentric Range Rings (25%, 50%, 75%, 100%)
                            for (ratio in listOf(0.25f, 0.5f, 0.75f, 1.0f)) {
                                drawCircle(
                                    color = primaryColor.copy(alpha = if (ratio == 1.0f) 0.35f else 0.18f),
                                    radius = maxRadius * ratio,
                                    style = Stroke(width = if (ratio == 1.0f) 2.dp.toPx() else 1.dp.toPx())
                                )
                            }

                            // 3. Crosshair Reticle Axes (X, Y)
                            drawLine(
                                color = primaryColor.copy(alpha = 0.22f),
                                start = Offset(centerOffset.x, centerOffset.y - maxRadius),
                                end = Offset(centerOffset.x, centerOffset.y + maxRadius),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = primaryColor.copy(alpha = 0.22f),
                                start = Offset(centerOffset.x - maxRadius, centerOffset.y),
                                end = Offset(centerOffset.x + maxRadius, centerOffset.y),
                                strokeWidth = 1.dp.toPx()
                            )

                            // 4. Diagonal 45-degree angle guides
                            val diagOffset = (maxRadius * 0.7071f)
                            drawLine(
                                color = primaryColor.copy(alpha = 0.12f),
                                start = Offset(centerOffset.x - diagOffset, centerOffset.y - diagOffset),
                                end = Offset(centerOffset.x + diagOffset, centerOffset.y + diagOffset),
                                strokeWidth = 0.8.dp.toPx()
                            )
                            drawLine(
                                color = primaryColor.copy(alpha = 0.12f),
                                start = Offset(centerOffset.x - diagOffset, centerOffset.y + diagOffset),
                                end = Offset(centerOffset.x + diagOffset, centerOffset.y - diagOffset),
                                strokeWidth = 0.8.dp.toPx()
                            )

                            // 5. Cardinal Ticks (0, 90, 180, 270)
                            val tickLen = 8.dp.toPx()
                            drawLine(
                                color = primaryColor.copy(alpha = 0.6f),
                                start = Offset(centerOffset.x, centerOffset.y - maxRadius),
                                end = Offset(centerOffset.x, centerOffset.y - maxRadius + tickLen),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = primaryColor.copy(alpha = 0.6f),
                                start = Offset(centerOffset.x, centerOffset.y + maxRadius),
                                end = Offset(centerOffset.x, centerOffset.y + maxRadius - tickLen),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = primaryColor.copy(alpha = 0.6f),
                                start = Offset(centerOffset.x - maxRadius, centerOffset.y),
                                end = Offset(centerOffset.x - maxRadius + tickLen, centerOffset.y),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = primaryColor.copy(alpha = 0.6f),
                                start = Offset(centerOffset.x + maxRadius, centerOffset.y),
                                end = Offset(centerOffset.x + maxRadius - tickLen, centerOffset.y),
                                strokeWidth = 2.dp.toPx()
                            )

                            // 6. 360-degree Rotating Sweep Beam and Phosphor Glow Trail
                            rotate(degrees = sweepAngle, pivot = centerOffset) {
                                // Phosphor sweep gradient trail wedge (fading out over 60 degrees)
                                val sweepBrush = Brush.sweepGradient(
                                    0.0f to Color.Transparent,
                                    ((360f - 60f) / 360f) to Color.Transparent,
                                    1.0f to primaryColor.copy(alpha = 0.28f),
                                    center = centerOffset
                                )
                                drawCircle(
                                    brush = sweepBrush,
                                    radius = maxRadius
                                )

                                // Main sharp sweep beam line
                                drawLine(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(primaryColor.copy(alpha = 0.2f), primaryColor),
                                        startX = centerOffset.x,
                                        endX = centerOffset.x + maxRadius
                                    ),
                                    start = centerOffset,
                                    end = Offset(centerOffset.x + maxRadius, centerOffset.y),
                                    strokeWidth = 2.5.dp.toPx()
                                )
                            }

                            // 7. Dynamic Ambient Target Blips (illuminated when sweep passes)
                            val blips = listOf(
                                Triple(0.55f, 45f, "Omarchy PC"),
                                Triple(0.80f, 195f, "AirBridge"),
                                Triple(0.38f, 290f, "Peer")
                            )

                            for ((distRatio, angleDeg, _) in blips) {
                                val rad = (angleDeg * PI / 180.0)
                                val bx = centerOffset.x + (maxRadius * distRatio * cos(rad)).toFloat()
                                val by = centerOffset.y + (maxRadius * distRatio * sin(rad)).toFloat()

                                // Calculate angle difference for CRT phosphor persistence
                                val angleDiff = (sweepAngle - angleDeg + 360f) % 360f
                                val blipAlpha = if (angleDiff < 90f) {
                                    ((90f - angleDiff) / 90f) * 0.9f
                                } else {
                                    0.15f
                                }

                                if (blipAlpha > 0.15f) {
                                    // Blip beacon glow ring
                                    drawCircle(
                                        color = primaryColor.copy(alpha = blipAlpha * 0.4f),
                                        radius = 7.dp.toPx(),
                                        center = Offset(bx, by)
                                    )
                                }
                                // Blip core dot
                                drawCircle(
                                    color = primaryColor.copy(alpha = blipAlpha),
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(bx, by)
                                )
                            }

                            // 8. Host Center Beacon (Your Device)
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.35f),
                                radius = 8.dp.toPx() * pulseScale,
                                center = centerOffset
                            )
                            drawCircle(
                                color = primaryColor,
                                radius = 4.5.dp.toPx(),
                                center = centerOffset
                            )
                        }
                    }

                    // Tactical Status Footer
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "AĞ TARANIYOR...",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Yakındaki Omarchy & Android cihazlar taranıyor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransferStatusCard(
    state: TransferProgressState,
    onDismiss: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                is TransferProgressState.Requesting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Requesting transfer to '${state.peerName}'...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is TransferProgressState.WaitingConsent -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Waiting for '${state.peerName}' to accept...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is TransferProgressState.Transferring -> {
                    Text(
                        text = if (state.isUploading) "Uploading to '${state.peerName}'" else "Receiving from '${state.peerName}'",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${state.fileName} (${NetworkUtils.formatBytes(state.bytesTransferred)} / ${NetworkUtils.formatBytes(state.totalBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                is TransferProgressState.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text("OK", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                is TransferProgressState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun PulseBeaconIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val color = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(10.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
    )
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
            val requestResult = app.client.sendTransferRequest(peer.ip, peer.port, listOf(fileInfo))

            val token = requestResult.getOrElse {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error(it.message ?: "Transfer request failed"))
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                onState(TransferProgressState.WaitingConsent(peer.name))
            }

            val decisionResult = app.client.pollDecision(peer.ip, peer.port, token)
            if (decisionResult.isFailure) {
                withContext(Dispatchers.Main) {
                    onState(TransferProgressState.Error(decisionResult.exceptionOrNull()?.message ?: "Transfer rejected"))
                }
                return@withContext
            }

            val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Failed to open file stream")

            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Transferring(true, peer.name, fileName, 0, fileSize, 0))
            }

            val uploadResult = app.client.uploadFileStream(
                targetIp = peer.ip,
                targetPort = peer.port,
                token = token,
                filename = fileName,
                totalBytes = fileSize,
                inputStream = inputStream
            ) { bytes, total, pct ->
                scopeLaunchMain {
                    onState(TransferProgressState.Transferring(true, peer.name, fileName, bytes, total, pct))
                }
            }

            withContext(Dispatchers.Main) {
                if (uploadResult.isSuccess) {
                    onState(TransferProgressState.Success("Sent '$fileName' successfully!"))
                } else {
                    onState(TransferProgressState.Error(uploadResult.exceptionOrNull()?.message ?: "Upload failed"))
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(TransferProgressState.Error(e.message ?: "Transfer error"))
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
        val res = app.client.sendClipboard(peer.ip, peer.port, text)
        withContext(Dispatchers.Main) {
            if (res.isSuccess) {
                Toast.makeText(context, "Pano '${peer.name}' cihazına iletildi", Toast.LENGTH_SHORT).show()
                onState(TransferProgressState.Success("Pano '${peer.name}' cihazına iletildi"))
            } else {
                onState(TransferProgressState.Error(res.exceptionOrNull()?.message ?: "Pano aktarımı başarısız oldu"))
            }
        }
    }
}

private fun scopeLaunchMain(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(block)
}

@Composable
fun QrCodeDialog(
    onDismiss: () -> Unit,
    onConnectEndpoint: (String) -> Unit
) {
    val context = LocalContext.current
    val myIp = NetworkUtils.getLocalIpAddress()
    val endpointUrl = "http://$myIp:${NetworkUtils.PORT}"
    val qrBitmap = remember(endpointUrl) {
        TransferBridge.generateQrCode(endpointUrl, 512)
    }
    var manualInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.QrCode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "QR / Barkod ile Bağlan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Aynı ağdaki başka bir telefon veya PC tarayıcısından bu kodu okutarak bağlanabilir:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                // White surface container for high-contrast QR scannability in all themes
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    modifier = Modifier.size(200.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
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
                    color = MaterialTheme.colorScheme.surfaceContainer,
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
                                Toast.makeText(context, "URL panoya kopyalandı", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Veya taranan QR metnini / IP adresini girin:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = {
                        manualInput = it
                        inputError = null
                    },
                    placeholder = { Text("Örn: 192.168.1.50:53317", fontSize = 13.sp) },
                    singleLine = true,
                    isError = inputError != null,
                    supportingText = inputError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (manualInput.isNotBlank()) {
                        val endpoint = TransferBridge.parseConnectionEndpoint(manualInput)
                        if (endpoint != null) {
                            onConnectEndpoint(manualInput)
                            onDismiss()
                        } else {
                            inputError = "Geçersiz IP adresi veya format (IPv4 zorunlu)"
                        }
                    } else {
                        onDismiss()
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (manualInput.isNotBlank()) "Bağlan" else "Tamam")
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
fun DirectIpDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit
) {
    var ipText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("53317") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AddLink,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Doğrudan IP ile Bağlan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "UDP yayın paketlerini engelleyen kısıtlı veya misafir ağlarında cihazın IP adresini girerek doğrudan bağlanabilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = ipText,
                    onValueChange = {
                        ipText = it
                        errorMessage = null
                    },
                    label = { Text("Hedef IP Adresi") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedIp = ipText.trim()
                    val port = portText.trim().toIntOrNull() ?: 53317
                    if (!TransferBridge.isValidIpv4(trimmedIp)) {
                        errorMessage = "Lütfen geçerli bir IPv4 adresi girin (örn: 192.168.1.50)"
                        return@Button
                    }
                    if (port !in 1024..65535) {
                        errorMessage = "Port numarası 1024 ile 65535 arasında olmalıdır"
                        return@Button
                    }
                    onConnect(trimmedIp, port)
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cihazı Ekle")
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
fun InfoDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val packageInfo = remember(context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "1.0.3"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode ?: 4L
    } else {
        @Suppress("DEPRECATION")
        packageInfo?.versionCode?.toLong() ?: 4L
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "OmaSend Mobile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Omarchy AirBridge P2P Ecosystem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedCard(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        InfoRow(label = "Yüklü Sürüm", value = "v$versionName (Derleme: $versionCode)", highlight = true)
                        Spacer(modifier = Modifier.height(10.dp))
                        InfoRow(label = "Protokol", value = "Omarchy P2P v1.0")
                        Spacer(modifier = Modifier.height(10.dp))
                        InfoRow(label = "Port & Ağ", value = "53317 / UDP & TCP")
                        Spacer(modifier = Modifier.height(10.dp))
                        InfoRow(label = "Şifreleme", value = "AES-256-GCM / TLS E2EE")
                        Spacer(modifier = Modifier.height(10.dp))
                        InfoRow(label = "Geliştirici", value = "Ozan Özdil (Omarchy Linux)")
                        Spacer(modifier = Modifier.height(10.dp))
                        InfoRow(label = "Lisans", value = "GPL-3.0 (Açık Kaynak)")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "OmaSend, yerel ağda internete ihtiyaç duymadan cihazlar arasında yüksek hızlı dosya ve pano aktarımı sağlayan güvenli bir Omarchy uygulamasıdır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Tamam")
            }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (highlight) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = value,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}


