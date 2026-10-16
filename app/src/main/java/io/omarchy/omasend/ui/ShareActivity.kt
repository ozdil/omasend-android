package io.omarchy.omasend.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.network.CoreNode
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.model.TransferProgressState
import io.omarchy.omasend.network.TransferBridge
import io.omarchy.omasend.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as OmaSendApp
        val sharedUris = extractUrisFromIntent(intent)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            io.omarchy.omasend.ui.theme.OmaSendTheme {
                val scope = rememberCoroutineScope()
                val lists = rememberDeviceLists(app)
                var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }
                var showDirectIpDialog by remember { mutableStateOf(false) }

                fun sendTo(peer: DiscoveredPeer) {
                    scope.launch {
                        handleSendToPeer(app, peer, sharedUris, sharedText) { state -> transferState = state }
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OmarchyGeometricEmblem(modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = OmaI18n.t("share_title"),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            text = if (sharedUris.isNotEmpty()) OmaI18n.f("n_files", sharedUris.size) else OmaI18n.t("text_content"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.Close, contentDescription = OmaI18n.t("close"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                    ) {
                        if (transferState !is TransferProgressState.Idle) {
                            TransferStatusCard(
                                state = transferState,
                                onCancel = {
                                    app.client.cancelActiveTransfer()
                                    transferState = TransferProgressState.Error(OmaI18n.t("cancelled"))
                                }
                            ) {
                                if (transferState is TransferProgressState.Success) finish() else transferState = TransferProgressState.Idle
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (lists.paired.isEmpty() && lists.nearby.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = OmaI18n.t("searching_devices"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (lists.paired.isNotEmpty()) {
                                    item {
                                        SectionHeaderRow(title = OmaI18n.t("section_my_devices"), count = lists.paired.size, accentColor = Emerald)
                                    }
                                    items(lists.paired, key = { "share_k_${it.key.pk}" }) { d ->
                                        val handle = d.asPeer()
                                        PairedDeviceCard(
                                            device = d,
                                            selected = false,
                                            onSelect = { sendTo(handle) },
                                            onSend = { sendTo(handle) },
                                            onClipboard = { sendTo(handle) },
                                            onRemove = { DeviceKey.TrustStore(this@ShareActivity).remove(d.key.pk) }
                                        )
                                    }
                                }
                                if (lists.nearby.isNotEmpty()) {
                                    item {
                                        SectionHeaderRow(title = OmaI18n.t("section_nearby"), count = lists.nearby.size, accentColor = MaterialTheme.colorScheme.secondary)
                                    }
                                    items(lists.nearby, key = { "share_n_${it.id}" }) { peer ->
                                        NearbyPeerCard(peer = peer, selected = false, onSelect = { sendTo(peer) }, onSend = { sendTo(peer) })
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showDirectIpDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(OmaI18n.t("add_ip"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    TransferBridge.shareViaSystem(this@ShareActivity, sharedUris, sharedText)
                                    finish()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(OmaI18n.t("system_share"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (showDirectIpDialog) {
                    DirectIpDialog(
                        onDismiss = { showDirectIpDialog = false },
                        onConnect = { ip, port ->
                            showDirectIpDialog = false
                            sendTo(TransferBridge.createManualPeer(ip, port))
                        }
                    )
                }
            }
        }
    }

    private suspend fun handleSendToPeer(
        app: OmaSendApp,
        peer: DiscoveredPeer,
        uris: List<Uri>,
        text: String?,
        onState: (TransferProgressState) -> Unit
    ) {
        if (uris.isNotEmpty()) {
            sendMultipleUrisToPeer(this, app, peer, uris, onState)
        } else if (!text.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                // QR-paired devices: encrypted QUIC only. Others: legacy HTTP.
                val key = CoreNode.trustedKeyFor(this@ShareActivity, peer)
                val res = if (key != null) CoreNode.sendClipboard(key, text, peer.ip)
                else app.client.sendClipboard(peer.ip, peer.port, text, peer.omaId)
                withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        app.soundEngine.playSendSound()
                        app.hapticController.onDropBounce()
                        onState(TransferProgressState.Success(OmaI18n.f("clipboard_sent", peer.name)))
                    } else {
                        onState(TransferProgressState.Error(res.exceptionOrNull()?.message ?: OmaI18n.t("err_transfer_failed")))
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUrisFromIntent(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) uris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (list != null) uris.addAll(list)
            }
        }
        return uris
    }
}
