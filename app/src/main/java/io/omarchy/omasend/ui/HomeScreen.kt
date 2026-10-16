package io.omarchy.omasend.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.R
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.crypto.OmaLink
import io.omarchy.omasend.crypto.OmaIdentity
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.model.DiscoveryMode
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferProgressState
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.network.StorageUtils
import io.omarchy.omasend.ui.scanner.OmaQrScannerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { Home, Settings }

/** App shell: the device list, the settings screen, and the incoming-transfer prompt on top. */
@Composable
fun OmaSendRoot(
    app: OmaSendApp,
    incomingPrompt: IncomingTransferPrompt?,
    onAcceptPrompt: (String) -> Unit,
    onDeclinePrompt: (String) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    when (screen) {
        Screen.Home -> HomeScreen(app = app, onOpenSettings = { screen = Screen.Settings })
        Screen.Settings -> {
            BackHandler { screen = Screen.Home }
            SettingsScreen(app = app, onBack = { screen = Screen.Home })
        }
    }
    if (incomingPrompt != null) {
        IncomingTransferDialog(
            prompt = incomingPrompt,
            onAccept = { onAcceptPrompt(incomingPrompt.token) },
            onDecline = { onDeclinePrompt(incomingPrompt.token) }
        )
    }
}

/** The device whose action sheet is open. */
private sealed class SheetTarget {
    data class Paired(val device: PairedDeviceUi) : SheetTarget()
    data class Nearby(val peer: DiscoveredPeer) : SheetTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: OmaSendApp, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveryMode by app.discoveryManager.discoveryMode.collectAsState()
    val visible = discoveryMode != DiscoveryMode.OFF
    val lists = rememberDeviceLists(app)

    var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    var showAddDevice by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showDirectIp by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var keyToRemove by remember { mutableStateOf<DeviceKey.TrustedKey?>(null) }
    var pickFor by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    val incomingMetrics by StorageUtils.incomingTransferMetrics.collectAsState()
    val incomingFileName by StorageUtils.incomingFileName.collectAsState()

    fun refreshRecentFiles() {
        recentFiles = getRecentReceivedFiles(context)
    }
    LaunchedEffect(Unit) { refreshRecentFiles() }
    LaunchedEffect(incomingMetrics) { if (incomingMetrics == null) refreshRecentFiles() }

    fun send(peer: DiscoveredPeer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            sendMultipleUrisToPeer(context, app, peer, uris) { state ->
                transferState = state
                if (state is TransferProgressState.Success) refreshRecentFiles()
            }
        }
    }

    // Files are always sent from a device's sheet, so the target is known.
    fun sendPicked(uris: List<Uri>) {
        val p = pickFor ?: return
        pickFor = null
        send(p, uris)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { sendPicked(it) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { sendPicked(it) }

    fun pickFiles(peer: DiscoveredPeer?) {
        pickFor = peer
        filePicker.launch("*/*")
    }

    fun pickMedia(peer: DiscoveredPeer?) {
        pickFor = peer
        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    fun sendClipboard(peer: DiscoveredPeer) {
        scope.launch { sendClipboardToPeer(context, app, peer) { state -> transferState = state } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.omasend_mark),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("OmaSend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(7.dp).clip(CircleShape)
                                        .background(if (visible) Emerald else MaterialTheme.colorScheme.outline)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val name = NetworkUtils.getDeviceName(context)
                                Text(
                                    text = if (visible) OmaI18n.f("home_visible", name) else OmaI18n.f("home_hidden", name),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDevice = true }) {
                        Icon(Icons.Default.Add, contentDescription = OmaI18n.t("add_device"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = OmaI18n.t("settings"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                app.discoveryManager.forceRefresh()
                refreshRecentFiles()
                scope.launch {
                    delay(800)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
            ) {
                // Sending, or receiving over the legacy path.
                val active: TransferProgressState = when {
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
                if (active !is TransferProgressState.Idle) {
                    item(key = "transfer") {
                        TransferStatusCard(
                            state = active,
                            onCancel = {
                                app.client.cancelActiveTransfer()
                                StorageUtils.cancelIncomingTransfer()
                                transferState = TransferProgressState.Error(OmaI18n.t("cancelled"))
                            },
                            onDismiss = { transferState = TransferProgressState.Idle }
                        )
                    }
                }

                item(key = "h_mine") { SectionLabel(OmaI18n.t("section_my_devices")) }
                if (lists.paired.isEmpty()) {
                    item(key = "add_first") { AddFirstDeviceCard(onAdd = { showAddDevice = true }) }
                } else {
                    items(lists.paired, key = { "k_${it.key.pk}" }) { d ->
                        PairedDeviceRow(d) { sheet = SheetTarget.Paired(d) }
                    }
                }

                item(key = "h_nearby") { SectionLabel(OmaI18n.t("section_nearby")) }
                if (lists.nearby.isEmpty()) {
                    item(key = "nearby_empty") {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (visible) OmaI18n.t("nearby_empty") else OmaI18n.t("nearby_hidden"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (!visible) {
                                TextButton(onClick = { setVisibility(context, app, true) }) { Text(OmaI18n.t("turn_on")) }
                            }
                        }
                    }
                } else {
                    items(lists.nearby, key = { "n_${it.id}" }) { peer ->
                        NearbyDeviceRow(peer) { sheet = SheetTarget.Nearby(peer) }
                    }
                }

                if (recentFiles.isNotEmpty()) {
                    item(key = "h_recent") { SectionLabel(OmaI18n.t("section_recent")) }
                    items(recentFiles, key = { "f_${it.absolutePath}" }) { file ->
                        RecentFileRow(
                            file = file,
                            onOpen = { openFileWithSystem(context, file) },
                            onShare = { shareFileWithSystem(context, file) }
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- sheets and dialogs

    when (val s = sheet) {
        is SheetTarget.Paired -> {
            val handle = s.device.asPeer()
            DeviceSheet(
                name = s.device.key.name,
                status = s.device.statusText(),
                encrypted = true,
                canSend = true,
                deviceId = s.device.key.device_id,
                onDismiss = { sheet = null },
                onSendFiles = { sheet = null; pickFiles(handle) },
                onSendMedia = { sheet = null; pickMedia(handle) },
                onSendClipboard = { sheet = null; sendClipboard(handle) },
                onUnpair = { sheet = null; keyToRemove = s.device.key }
            )
        }
        is SheetTarget.Nearby -> {
            val peer = s.peer
            DeviceSheet(
                name = peer.name,
                status = peer.statusText(),
                encrypted = false,
                canSend = peer.ip.isNotBlank(),
                deviceId = null,
                onDismiss = { sheet = null },
                onSendFiles = { sheet = null; pickFiles(peer) },
                onSendMedia = { sheet = null; pickMedia(peer) },
                onSendClipboard = { sheet = null; sendClipboard(peer) },
                onPair = { sheet = null; showAddDevice = true }
            )
        }
        null -> {}
    }

    if (showAddDevice) {
        AddDeviceSheet(
            app = app,
            onDismiss = { showAddDevice = false },
            onScan = {
                showAddDevice = false
                showScanner = true
            },
            onConnectByIp = {
                showAddDevice = false
                showDirectIp = true
            },
            onLinkWithOmaId = {
                showAddDevice = false
                showLink = true
            },
            onPaired = { name ->
                showAddDevice = false
                Toast.makeText(context, OmaI18n.f("paired_with", name), Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showScanner) {
        OmaQrScannerDialog(
            onDismissRequest = { showScanner = false },
            onPairUriScanned = { uri ->
                showScanner = false
                Toast.makeText(context, OmaI18n.t("pairing_in_progress"), Toast.LENGTH_SHORT).show()
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
                showScanner = false
                app.discoveryManager.addPairedOmaId(OmaIdentity.format(scannedId))
                app.discoveryManager.forceRefresh()
                Toast.makeText(context, OmaI18n.t("legacy_omaid_added"), Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showLink) {
        LinkWithOmaIdDialog(
            app = app,
            onDismiss = { showLink = false },
            onLinked = { names ->
                showLink = false
                Toast.makeText(context, OmaI18n.f("omaid_linked", names.joinToString(", ")), Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showDirectIp) {
        DirectIpDialog(
            onDismiss = { showDirectIp = false },
            onConnect = { ip, port ->
                app.discoveryManager.addManualPeer(ip, port)
                showDirectIp = false
            }
        )
    }

    keyToRemove?.let { k ->
        RemovePairingDialog(
            name = k.name,
            own = k.own_device,
            onConfirm = {
                if (k.own_device) {
                    // Removes it from every linked device and replaces the account number.
                    OmaLink.revokeOwnDevice(context, k.pk)
                    app.client.pushRoster()
                } else {
                    DeviceKey.TrustStore(context).remove(k.pk)
                }
                keyToRemove = null
            },
            onDismiss = { keyToRemove = null }
        )
    }
}
