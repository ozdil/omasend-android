package io.omarchy.omasend.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.network.CoreNode
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val Emerald = Color(0xFF10B981)

// ------------------------------------------------------------------ model

/** A QR-paired device: its key plus where discovery currently sees it (null = not seen). */
data class PairedDeviceUi(val key: DeviceKey.TrustedKey, val seen: DiscoveredPeer?) {
    val online: Boolean get() = seen != null
    val ip: String get() = seen?.ip?.takeIf { it.isNotBlank() } ?: key.ip

    /** Handle for the shared send functions; resolves back to [key] through its public key. */
    fun asPeer(): DiscoveredPeer = DiscoveredPeer(
        id = "key_${key.device_id}",
        name = key.name,
        ip = ip,
        port = seen?.port ?: (if (key.port > 0) key.port else NetworkUtils.PORT),
        transport = "LAN",
        omaId = seen?.omaId ?: "",
        fingerprint = seen?.fingerprint ?: "",
        isTrusted = true,
        lastSeen = seen?.lastSeen ?: 0L,
        pk = key.pk
    )
}

data class DeviceLists(val paired: List<PairedDeviceUi>, val nearby: List<DiscoveredPeer>) {
    /** The single online device of your own, used as the default send target. */
    val defaultOwn: PairedDeviceUi? get() = paired.filter { it.key.own_device && it.online }.singleOrNull()
}

@Composable
fun rememberDeviceLists(app: OmaSendApp): DeviceLists {
    val peers by app.discoveryManager.peers.collectAsState()
    val version by DeviceKey.trustVersion.collectAsState()
    val keys = remember(version) {
        DeviceKey.TrustStore(app).load()
            .sortedWith(compareByDescending<DeviceKey.TrustedKey> { it.own_device }.thenBy { it.name.lowercase() })
    }
    return remember(keys, peers) {
        DeviceLists(
            paired = keys.map { k -> PairedDeviceUi(k, peers.firstOrNull { CoreNode.keyFor(listOf(k), it) != null }) },
            nearby = peers.filter { CoreNode.keyFor(keys, it) == null }
        )
    }
}

fun deviceIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        listOf("phone", "android", "pixel", "galaxy", "samsung", "xiaomi", "redmi", "oneplus", "sdk_gphone").any { n.contains(it) } -> Icons.Default.PhoneAndroid
        listOf("laptop", "thinkpad", "book").any { n.contains(it) } -> Icons.Default.Laptop
        else -> Icons.Default.Computer
    }
}

// ------------------------------------------------------------------ small pieces

/** Service state + this device's name in one line; tapping the dot toggles the service. */
@Composable
fun StatusLine(active: Boolean, deviceLabel: String, onToggle: () -> Unit, onRename: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onToggle,
            shape = RoundedCornerShape(6.dp),
            color = if (active) Emerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (active) Emerald else Color.Gray))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (active) OmaI18n.t("status_active") else OmaI18n.t("status_off"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = if (active) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = deviceLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onRename)
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DeviceAvatar(icon: ImageVector, tint: Color) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SectionFootnote(text: String, icon: ImageVector) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp).padding(top = 2.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

// ------------------------------------------------------------------ cards

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PairedDeviceCard(
    device: PairedDeviceUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onSend: () -> Unit,
    onClipboard: () -> Unit,
    onRemove: () -> Unit
) {
    val accent = if (device.key.own_device) Emerald else MaterialTheme.colorScheme.primary
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onSelect, onLongClick = { menu = true })
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(deviceIcon(device.key.name), if (device.online) accent else MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.key.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Badge(if (device.key.own_device) OmaI18n.t("badge_own") else OmaI18n.t("badge_paired"), accent)
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Emerald, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier.size(6.dp).clip(CircleShape)
                            .background(if (device.online) Emerald else MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (device.online) "${OmaI18n.t("online")} · ${device.ip}" else OmaI18n.t("offline"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onClipboard, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentPaste, contentDescription = OmaI18n.t("send_clipboard"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            FilledTonalIconButton(onClick = onSend, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = OmaI18n.t("send"), modifier = Modifier.size(18.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("${OmaI18n.t("device_id")}: ${device.key.device_id}", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) },
                        onClick = { menu = false },
                        enabled = false
                    )
                    DropdownMenuItem(
                        text = { Text(OmaI18n.t("unpair"), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() }
                    )
                }
            }
        }
    }
}

@Composable
fun NearbyPeerCard(peer: DiscoveredPeer, selected: Boolean, onSelect: () -> Unit, onSend: () -> Unit) {
    val online = peer.lastSeen > 0L && peer.ip.isNotBlank()
    ElevatedCard(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(deviceIcon(peer.name), MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${peer.ip} · ${OmaI18n.t("needs_approval")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            FilledTonalIconButton(onClick = onSend, enabled = online, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = OmaI18n.t("send"), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun PairHintCard(onPair: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, Emerald.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = Emerald, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(OmaI18n.t("pair_hint_title"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(OmaI18n.t("pair_hint_body"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onPair, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(OmaI18n.t("pair_device"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun NearbyEmptyCard(scanning: Boolean, onStartScan: () -> Unit, onAddIp: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (scanning) OmaI18n.t("nearby_empty") else OmaI18n.t("scan_paused"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (scanning) {
                TextButton(onClick = onAddIp) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(OmaI18n.t("add_ip"))
                }
            } else {
                TextButton(onClick = onStartScan) { Text(OmaI18n.t("start_scan")) }
            }
        }
    }
}

// ------------------------------------------------------------------ dock

@Composable
fun SendDock(targetName: String?, onSendFiles: () -> Unit, onSendMedia: () -> Unit, onWebQr: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onSendFiles,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (targetName != null) "${OmaI18n.t("send")} → ${targetName.take(12)}" else OmaI18n.t("dock_send_file"),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onSendMedia,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Image, contentDescription = OmaI18n.t("gallery"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            Surface(
                onClick = onWebQr,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.QrCode, contentDescription = OmaI18n.t("web_portal"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

fun qrBitmap(content: String, size: Int = 640): Bitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(m.width * m.height) { i ->
        if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.RGB_565)
} catch (_: Exception) {
    null
}

/**
 * Pair a device: scan the other device's QR (desktop panel or another phone), or show this
 * phone's one-time pairing QR. Closes itself when the other side completes the pairing.
 */
@Composable
fun PairDeviceDialog(app: OmaSendApp, onDismiss: () -> Unit, onScan: () -> Unit, onPaired: (String) -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    var ownDevice by remember { mutableStateOf(true) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    val version by DeviceKey.trustVersion.collectAsState()
    val startVersion = remember { version }
    val startKeys = remember { DeviceKey.TrustStore(app).load().map { it.pk }.toSet() }

    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(showQr, ownDevice, refreshTick) {
        if (!showQr) return@LaunchedEffect
        qr = null
        qr = withContext(Dispatchers.Default) {
            val id = DeviceKey.identity(app)
            val uri = DeviceKey.createPairOffer(
                id,
                NetworkUtils.getDeviceName(app),
                NetworkUtils.getLocalIpAddress(),
                NetworkUtils.PORT,
                ownDevice,
                CoreNode.node?.boundPort()?.toInt() ?: CoreNode.QUIC_PORT
            )
            qrBitmap(uri)
        }
        // The offer is single-use and valid for 5 minutes: show a fresh one before it expires.
        delay((DeviceKey.PAIR_OFFER_TTL_SECS - 20) * 1000)
        refreshTick++
    }
    LaunchedEffect(version) {
        if (version != startVersion) {
            DeviceKey.TrustStore(app).load().firstOrNull { it.pk !in startKeys }?.let { onPaired(it.name) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("pair_device"), fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!showQr) {
                    Text(OmaI18n.t("pair_intro"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = onScan, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OmaI18n.t("scan_qr"), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showQr = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OmaI18n.t("show_my_qr"))
                    }
                } else {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.size(232.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                            val bmp = qr
                            if (bmp != null) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = OmaI18n.t("show_my_qr"), modifier = Modifier.fillMaxSize())
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${NetworkUtils.getDeviceName(app)} · ${DeviceKey.identity(app).deviceId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(OmaI18n.t("qr_validity"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(OmaI18n.t("own_device_toggle"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ownDevice) OmaI18n.t("own_device_on") else OmaI18n.t("own_device_off"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Switch(checked = ownDevice, onCheckedChange = { ownDevice = it })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("close")) } }
    )
}

@Composable
fun RemovePairingDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("unpair"), fontWeight = FontWeight.Bold) },
        text = { Text(OmaI18n.f("unpair_confirm", name), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(OmaI18n.t("unpair"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}

@Composable
fun ChooseTargetDialog(lists: DeviceLists, onDismiss: () -> Unit, onSelect: (DiscoveredPeer) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("choose_target"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) },
        text = {
            if (lists.paired.isEmpty() && lists.nearby.isEmpty()) {
                Text(OmaI18n.t("no_targets"), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lists.paired, key = { "t_${it.key.pk}" }) { d ->
                        TargetRow(d.key.name, if (d.online) "${OmaI18n.t("encrypted")} · ${d.ip}" else "${OmaI18n.t("encrypted")} · ${OmaI18n.t("offline")}", true) {
                            onSelect(d.asPeer())
                        }
                    }
                    items(lists.nearby, key = { "n_${it.id}" }) { p ->
                        TargetRow(p.name, "${p.ip} · ${OmaI18n.t("needs_approval")}", false) { onSelect(p) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}

@Composable
private fun TargetRow(name: String, subtitle: String, encrypted: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(deviceIcon(name), contentDescription = null, tint = if (encrypted) Emerald else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (encrypted) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}
