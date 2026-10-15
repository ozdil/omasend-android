package io.omarchy.omasend.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.omarchy.omasend.OmaSendApp
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
                val peers by app.discoveryManager.peers.collectAsState()
                var transferState by remember { mutableStateOf<TransferProgressState>(TransferProgressState.Idle) }
                var showDirectIpDialog by remember { mutableStateOf(false) }
                var showSendToAnotherOmaIdDialog by remember { mutableStateOf(false) }

                val myDevices = remember(peers) { peers.filter { it.isSameOwner } }
                val otherPeers = remember(peers) { peers.filter { !it.isSameOwner } }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OmarchyGeometricEmblem(modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "OmaSend ile Paylaş",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            text = if (sharedUris.isNotEmpty()) "${sharedUris.size} dosya seçildi" else "Metin içeriği",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Kapat",
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
                            .padding(16.dp)
                    ) {
                        if (transferState !is TransferProgressState.Idle) {
                            TransferStatusCard(
                                state = transferState,
                                onCancel = {
                                    app.client.cancelActiveTransfer()
                                    transferState = TransferProgressState.Error("Transfer kullanıcı tarafından iptal edildi.")
                                }
                            ) {
                                if (transferState is TransferProgressState.Success) {
                                    finish()
                                } else {
                                    transferState = TransferProgressState.Idle
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (peers.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Aynı OmaID'li ve çevredeki cihazlar aranıyor...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (myDevices.isNotEmpty()) {
                                    item {
                                        SectionHeaderRow(
                                            title = "KENDİ CİHAZLARIM (AYNI OMAID)",
                                            count = myDevices.size,
                                            accentColor = Color(0xFF10B981)
                                        )
                                    }
                                    items(myDevices, key = { "share_my_${it.id}" }) { peer ->
                                        PeerCard(
                                            peer = peer,
                                            isSelected = false,
                                            onClick = {
                                                scope.launch {
                                                    handleSendToPeer(app, peer, sharedUris, sharedText) { state ->
                                                        transferState = state
                                                    }
                                                }
                                            },
                                            onSendFile = {
                                                scope.launch {
                                                    handleSendToPeer(app, peer, sharedUris, sharedText) { state ->
                                                        transferState = state
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }

                                if (otherPeers.isNotEmpty()) {
                                    item {
                                        SectionHeaderRow(
                                            title = "ÇEVREDEKİ CİHAZLAR & FARKLI OMAID",
                                            count = otherPeers.size,
                                            accentColor = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    items(otherPeers, key = { "share_other_${it.id}" }) { peer ->
                                        PeerCard(
                                            peer = peer,
                                            isSelected = false,
                                            onClick = {
                                                scope.launch {
                                                    handleSendToPeer(app, peer, sharedUris, sharedText) { state ->
                                                        transferState = state
                                                    }
                                                }
                                            },
                                            onSendFile = {
                                                scope.launch {
                                                    handleSendToPeer(app, peer, sharedUris, sharedText) { state ->
                                                        transferState = state
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "DİĞER GÖNDERİM SEÇENEKLERİ",
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showSendToAnotherOmaIdDialog = true },
                                modifier = Modifier.weight(1.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Farklı OmaID", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = { showDirectIpDialog = true },
                                modifier = Modifier.weight(0.9f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Manuel IP", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                                Text("Sistem", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (showDirectIpDialog) {
                    DirectIpDialog(
                        onDismiss = { showDirectIpDialog = false },
                        onConnect = { ip, port ->
                            showDirectIpDialog = false
                            val manualPeer = TransferBridge.createManualPeer(ip, port)
                            scope.launch {
                                handleSendToPeer(app, manualPeer, sharedUris, sharedText) { state ->
                                    transferState = state
                                }
                            }
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
                                scope.launch {
                                    handleSendToPeer(app, targetPeer, sharedUris, sharedText) { state ->
                                        transferState = state
                                    }
                                }
                            } else {
                                Toast.makeText(
                                    this@ShareActivity,
                                    "Hedef OmaID ($targetOmaId) için çevrimiçi IP bulunamadı; IP adresini de girerek tekrar deneyebilirsiniz.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
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
                val res = app.client.sendClipboard(peer.ip, peer.port, text, peer.omaId)
                withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        app.soundEngine.playSendSound()
                        app.hapticController.onDropBounce()
                        onState(TransferProgressState.Success("Metin '${peer.name}' cihazına gönderildi"))
                    } else {
                        onState(TransferProgressState.Error(res.exceptionOrNull()?.message ?: "Metin gönderilemedi"))
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
