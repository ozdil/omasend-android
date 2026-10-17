package io.omarchy.omasend.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** "Encrypted / online" mark. Monochrome: brighter than the outline, no hue. */
/** The theme's ink; "encrypted", "online" and "done" are shown by icon and weight, not by hue. */
val Ink: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

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

fun PairedDeviceUi.statusText(): String =
    if (online) OmaI18n.t("status_paired_online") else OmaI18n.t("status_paired_offline")

fun DiscoveredPeer.statusText(): String =
    if (isSameOwner) OmaI18n.t("status_same_omaid") else OmaI18n.t("status_nearby")

// ------------------------------------------------------------------ rows

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun DeviceAvatar(icon: ImageVector, active: Boolean) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.14f), modifier = Modifier.size(42.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

/** One device: avatar, name and a single status line. Tapping opens its actions. */
@Composable
fun DeviceRow(
    name: String,
    status: String,
    active: Boolean,
    encrypted: Boolean,
    own: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(deviceIcon(name), active)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (own) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OmaI18n.t("badge_own"), style = MaterialTheme.typography.labelSmall, color = Ink)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (encrypted && active) Ink else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PairedDeviceRow(device: PairedDeviceUi, onClick: () -> Unit) = DeviceRow(
    name = device.key.name,
    status = device.statusText(),
    active = device.online,
    encrypted = true,
    own = device.key.own_device,
    onClick = onClick
)

@Composable
fun NearbyDeviceRow(peer: DiscoveredPeer, onClick: () -> Unit) = DeviceRow(
    name = peer.name,
    status = peer.statusText(),
    active = peer.ip.isNotBlank(),
    encrypted = false,
    onClick = onClick
)

/** Shown instead of the "My devices" list until something is paired: one clear next step. */
@Composable
fun AddFirstDeviceCard(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text(OmaI18n.t("add_device_empty_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(OmaI18n.t("add_device_empty_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onAdd, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(OmaI18n.t("add_device"))
            }
        }
    }
}

// ------------------------------------------------------------------ sheets

@Composable
private fun SheetAction(icon: ImageVector, label: String, color: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (enabled) color else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(18.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * Everything you can do with one device. [deviceId] and [onUnpair] are set for paired
 * devices; [onPair] for nearby ones, which can be upgraded to an encrypted pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(
    name: String,
    status: String,
    encrypted: Boolean,
    canSend: Boolean,
    deviceId: String?,
    onDismiss: () -> Unit,
    onSendFiles: () -> Unit,
    onSendMedia: () -> Unit,
    onSendClipboard: () -> Unit,
    onUnpair: (() -> Unit)? = null,
    onPair: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(modifier = Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(deviceIcon(name), canSend)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = if (encrypted) OmaI18n.t("sheet_paired_note") else OmaI18n.t("sheet_nearby_note"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SheetAction(Icons.Default.Description, OmaI18n.t("send_files"), enabled = canSend, onClick = onSendFiles)
            SheetAction(Icons.Default.Image, OmaI18n.t("send_media"), enabled = canSend, onClick = onSendMedia)
            SheetAction(Icons.Default.ContentPaste, OmaI18n.t("send_clipboard"), enabled = canSend, onClick = onSendClipboard)
            if (onPair != null) {
                SheetAction(Icons.Default.QrCodeScanner, OmaI18n.t("pair_for_encryption"), color = MaterialTheme.colorScheme.primary, onClick = onPair)
            }
            if (deviceId != null || onUnpair != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (deviceId != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Text(OmaI18n.t("device_id"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(deviceId, style = MaterialTheme.typography.bodyMedium, fontFamily = JetBrainsMonoFontFamily)
                }
            }
            if (onUnpair != null) {
                SheetAction(Icons.Default.LinkOff, OmaI18n.t("unpair"), color = MaterialTheme.colorScheme.error, onClick = onUnpair)
            }
        }
    }
}

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
 * Add a device: scan the other device's QR (desktop panel or another phone), show this
 * phone's one-time pairing QR, or connect by IP. Closes itself when the other side
 * completes a pairing started from this phone's QR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceSheet(
    app: OmaSendApp,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConnectByIp: () -> Unit,
    onLinkWithOmaId: () -> Unit,
    onPaired: (String) -> Unit
) {
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
        // IO: the device key needs the Keystore and the QUIC port may still be binding.
        qr = withContext(Dispatchers.IO) {
            val id = DeviceKey.identityOrNull(app) ?: return@withContext null
            val uri = DeviceKey.createPairOffer(
                id,
                NetworkUtils.getDeviceName(app),
                NetworkUtils.getLocalIpAddress(),
                NetworkUtils.PORT,
                ownDevice,
                CoreNode.advertisedQuicPort()
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(OmaI18n.t("add_device"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            if (!showQr) {
                Text(OmaI18n.t("pair_intro"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onScan, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(OmaI18n.t("scan_qr"), style = MaterialTheme.typography.titleSmall)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = { showQr = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(OmaI18n.t("show_my_qr"), style = MaterialTheme.typography.titleSmall)
                }
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = onLinkWithOmaId) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(OmaI18n.t("omaid_link"))
                }
                TextButton(onClick = onConnectByIp) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(OmaI18n.t("connect_by_ip"))
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.size(248.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                        val bmp = qr
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = OmaI18n.t("show_my_qr"), modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(NetworkUtils.getDeviceName(app), style = MaterialTheme.typography.titleSmall)
                Text(
                    OmaI18n.t("qr_validity"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(OmaI18n.t("own_device_toggle"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (ownDevice) OmaI18n.t("own_device_on") else OmaI18n.t("own_device_off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(checked = ownDevice, onCheckedChange = { ownDevice = it })
                }
            }
        }
    }
}

@Composable
fun RemovePairingDialog(name: String, own: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("unpair")) },
        text = {
            Text(OmaI18n.f(if (own) "unpair_own_confirm" else "unpair_confirm", name), style = MaterialTheme.typography.bodyMedium)
        },
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
        title = { Text(OmaI18n.t("choose_target")) },
        text = {
            if (lists.paired.isEmpty() && lists.nearby.isEmpty()) {
                Text(OmaI18n.t("no_targets"), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(lists.paired, key = { "t_${it.key.pk}" }) { d ->
                        PairedDeviceRow(d) { onSelect(d.asPeer()) }
                    }
                    items(lists.nearby, key = { "n_${it.id}" }) { p ->
                        NearbyDeviceRow(p) { onSelect(p) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("cancel")) } }
    )
}
