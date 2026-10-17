package io.omarchy.omasend.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.account.AccountDeviceView
import io.omarchy.omasend.account.AccountLogic
import io.omarchy.omasend.account.AccountPhase
import io.omarchy.omasend.account.AccountView
import io.omarchy.omasend.account.JoinView
import io.omarchy.omasend.account.OmaAccount
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.ui.scanner.OmaQrScannerDialog
import io.omarchy.omasend.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short summary for the settings row. */
fun accountSummary(state: OmaAccount.UiState): String {
    val v = state.view ?: return OmaI18n.t("acct_settings_none")
    return when (v.phase) {
        AccountPhase.MEMBER -> OmaI18n.f("acct_settings_member", v.devices.size)
        AccountPhase.WAITING -> OmaI18n.t("acct_waiting_title")
        AccountPhase.RESETTING -> OmaI18n.t("acct_resetting_title")
    }
}

/** Shows the 16 digits as `1234 5678 9012 3456` while the field keeps digits only. */
private object NumberGroups : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        return TransformedText(AnnotatedString(AccountLogic.formatNumber(digits)), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = AccountLogic.groupedOffset(offset, digits.length)
            override fun transformedToOriginal(offset: Int) = AccountLogic.digitOffset(offset, digits.length)
        })
    }
}

/**
 * The OmaID account: create or join (number or QR), wait for approval, approve other
 * devices, invite by QR, remove devices, change the number, leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(app: OmaSendApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ui by app.account.state.collectAsState()
    val pendingInvite by app.account.pendingInvite.collectAsState()
    val myPk = remember { DeviceKey.identityOrNull(app)?.publicB64 }

    val act: (suspend () -> Boolean) -> Unit = { action -> scope.launch { action() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(OmaI18n.t("acct_title"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = OmaI18n.t("back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (ui.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            ui.error?.let { msg ->
                Banner(text = msg, actionLabel = OmaI18n.t("close"), onAction = { app.account.clearError() })
            }

            val view = ui.view
            when {
                !ui.loaded -> Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ui.storageError != null && view == null -> Card {
                    Text(ui.storageError ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                view == null -> NoAccountContent(app, busy = ui.busy, act = act)
                view.phase == AccountPhase.WAITING -> WaitingContent(view, busy = ui.busy, onCancel = { act { app.account.abandon() } })
                view.phase == AccountPhase.RESETTING -> ResettingContent(view, busy = ui.busy, onCancel = { act { app.account.abandon() } })
                else -> MemberContent(app, view, myPk, ui, act = act)
            }

            ui.syncError?.let { msg ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        OmaI18n.f("acct_sync_failed", msg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { act { app.account.sync() } }, enabled = !ui.busy) { Text(OmaI18n.t("acct_retry")) }
                }
            }
        }
    }

    // A link from outside this screen (deep link, home scanner): ask before joining.
    pendingInvite?.let { link ->
        if (ui.view != null) {
            AlertDialog(
                onDismissRequest = { app.account.clearPendingInvite() },
                title = { Text(OmaI18n.t("acct_invite_confirm_title")) },
                text = { Text(OmaI18n.t("acct_already_member"), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = { app.account.clearPendingInvite() }) { Text(OmaI18n.t("close")) } }
            )
        } else {
            ConfirmDialog(
                title = OmaI18n.t("acct_invite_confirm_title"),
                body = OmaI18n.t("acct_invite_confirm"),
                confirmLabel = OmaI18n.t("acct_join"),
                onConfirm = {
                    app.account.clearPendingInvite()
                    act { app.account.join(link) }
                },
                onDismiss = { app.account.clearPendingInvite() }
            )
        }
    }

    LaunchedEffect(Unit) { app.account.requestSync() }
}

// ------------------------------------------------------------------ states

@Composable
private fun NoAccountContent(app: OmaSendApp, busy: Boolean, act: (suspend () -> Boolean) -> Unit) {
    val context = LocalContext.current
    var joinOpen by rememberSaveable { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val valid = AccountLogic.numberValid(digits)

    Card {
        Text(OmaI18n.t("acct_intro"), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { act { app.account.create() } }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(OmaI18n.t("acct_create"), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = { joinOpen = !joinOpen }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(OmaI18n.t("acct_join"), style = MaterialTheme.typography.titleSmall)
        }
    }

    if (joinOpen) {
        Card {
            Text(OmaI18n.t("acct_join"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(OmaI18n.t("acct_join_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = digits,
                onValueChange = { digits = AccountLogic.digits(it).take(AccountLogic.NUMBER_DIGITS) },
                singleLine = true,
                label = { Text(OmaI18n.t("acct_number")) },
                placeholder = { Text("0000 0000 0000 0000", fontFamily = JetBrainsMonoFontFamily) },
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                visualTransformation = NumberGroups,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                // Luhn check before any network round trip.
                isError = digits.length == AccountLogic.NUMBER_DIGITS && !valid,
                supportingText = {
                    if (digits.length == AccountLogic.NUMBER_DIGITS && !valid) Text(OmaI18n.t("acct_number_invalid"))
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { act { app.account.join(digits) } }, enabled = valid && !busy, modifier = Modifier.weight(1f)) {
                    Text(OmaI18n.t("acct_join"))
                }
                OutlinedButton(onClick = { showScanner = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(OmaI18n.t("acct_scan"))
                }
            }
            TextButton(
                onClick = {
                    if (valid) confirmReset = true
                    else Toast.makeText(context, OmaI18n.t("acct_reset_need_number"), Toast.LENGTH_SHORT).show()
                },
                enabled = !busy
            ) {
                Text(OmaI18n.t("acct_no_device_left"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showScanner) {
        OmaQrScannerDialog(
            onDismissRequest = { showScanner = false },
            onOmaIdConnected = { scanned ->
                showScanner = false
                val d = AccountLogic.digits(scanned)
                if (AccountLogic.numberValid(d)) digits = d
                else Toast.makeText(context, OmaI18n.t("acct_not_invite"), Toast.LENGTH_SHORT).show()
            },
            onPairUriScanned = { link ->
                showScanner = false
                // Scanned on purpose from the join screen: join right away.
                if (AccountLogic.isInviteLink(link)) act { app.account.join(link) }
                else Toast.makeText(context, OmaI18n.t("acct_not_invite"), Toast.LENGTH_LONG).show()
            }
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = OmaI18n.t("acct_reset_title"),
            body = OmaI18n.t("acct_reset_confirm"),
            confirmLabel = OmaI18n.t("acct_reset_go"),
            onConfirm = {
                confirmReset = false
                act { app.account.startReset(digits) }
            },
            onDismiss = { confirmReset = false }
        )
    }
}

@Composable
private fun WaitingContent(view: AccountView, busy: Boolean, onCancel: () -> Unit) {
    Card(center = true) {
        Text(OmaI18n.t("acct_waiting_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(16.dp))
        if (view.myCode.isNotBlank()) {
            Text(view.myCode, style = MaterialTheme.typography.displayMedium, fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.SemiBold)
        } else {
            CircularProgressIndicator()
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(OmaI18n.t("acct_waiting_body"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text(OmaI18n.t("cancel")) }
    }
}

@Composable
private fun ResettingContent(view: AccountView, busy: Boolean, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(DeviceKey.nowSecs()) }
    LaunchedEffect(view.updatedAt) {
        while (true) {
            now = DeviceKey.nowSecs()
            delay(30_000)
        }
    }
    val left = if (view.updatedAt > 0) view.resetWait - (now - view.updatedAt) else view.resetWait
    val (h, m) = AccountLogic.hoursMinutes(left)
    Card(center = true) {
        Text(OmaI18n.t("acct_resetting_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            OmaI18n.f("acct_remaining", OmaI18n.f("acct_hm", h, m)),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = JetBrainsMonoFontFamily
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(OmaI18n.t("acct_resetting_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text(OmaI18n.t("cancel")) }
    }
}

@Composable
private fun MemberContent(
    app: OmaSendApp,
    view: AccountView,
    myPk: String?,
    ui: OmaAccount.UiState,
    act: (suspend () -> Boolean) -> Unit
) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<AccountDeviceView?>(null) }
    var confirmChange by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val busy = ui.busy

    if (view.resetPending) {
        Banner(
            text = OmaI18n.t("acct_reset_pending"),
            actionLabel = OmaI18n.t("acct_cancel_reset"),
            onAction = { act { app.account.cancelReset() } },
            enabled = !busy
        )
    }

    // ---- the number
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AccountLogic.maskNumber(view.number, revealed),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = JetBrainsMonoFontFamily,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = OmaI18n.t(if (revealed) "omaid_hide" else "omaid_show")
                )
            }
            IconButton(onClick = { copyToClipboard(context, "OmaID", AccountLogic.digits(view.number), sensitive = true) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = OmaI18n.t("copy"))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(OmaI18n.t("acct_member_body"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // ---- join requests
    view.joins.forEach { j -> JoinCard(j, busy, onApprove = { act { app.account.approve(j.rid) } }, onReject = { act { app.account.reject(j.rid) } }) }

    // ---- devices
    SectionLabel(OmaI18n.t("acct_devices"))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            view.devices.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                val self = d.pk == myPk
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(deviceIcon(d.name), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name.ifBlank { "OmaSend" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = if (self) OmaI18n.t("this_device")
                        else DeviceKey.decodePk(d.pk)?.let { DeviceKey.deviceIdForPk(it) } ?: ""
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (self) null else JetBrainsMonoFontFamily)
                    }
                    if (!self) {
                        IconButton(onClick = { toRemove = d }, enabled = !busy) {
                            Icon(Icons.Default.Delete, contentDescription = OmaI18n.t("acct_remove_device"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Button(onClick = { act { app.account.showInvite() } }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(OmaI18n.t("acct_add_device"), style = MaterialTheme.typography.titleSmall)
    }
    OutlinedButton(onClick = { confirmChange = true }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(OmaI18n.t("acct_change_number"))
    }
    TextButton(onClick = { confirmLeave = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(OmaI18n.t("acct_leave"), color = MaterialTheme.colorScheme.error)
    }

    ui.invite?.let { inv ->
        // Close the QR once the device that scanned it was approved (the device list grew).
        val startCount = remember(inv.uri) { view.devices.size }
        LaunchedEffect(inv.uri, view.devices.size) {
            if (view.devices.size > startCount) app.account.hideInvite()
        }
        InviteDialog(
            invite = inv,
            onDismiss = { app.account.hideInvite() },
            onRenew = { act { app.account.showInvite() } }
        )
    }

    toRemove?.let { d ->
        ConfirmDialog(
            title = OmaI18n.t("acct_remove_device"),
            body = OmaI18n.f("acct_remove_confirm", d.name),
            confirmLabel = OmaI18n.t("acct_remove_device"),
            onConfirm = {
                toRemove = null
                act { app.account.remove(d.pk) }
            },
            onDismiss = { toRemove = null }
        )
    }
    if (confirmChange) {
        ConfirmDialog(
            title = OmaI18n.t("acct_change_number"),
            body = OmaI18n.t("acct_change_number_confirm"),
            confirmLabel = OmaI18n.t("acct_change_number"),
            onConfirm = {
                confirmChange = false
                revealed = true
                act { app.account.changeNumber() }
            },
            onDismiss = { confirmChange = false }
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = OmaI18n.t("acct_leave"),
            body = OmaI18n.t("acct_leave_confirm"),
            confirmLabel = OmaI18n.t("acct_leave"),
            onConfirm = {
                confirmLeave = false
                act { app.account.leave() }
            },
            onDismiss = { confirmLeave = false }
        )
    }
}

@Composable
private fun JoinCard(j: JoinView, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(OmaI18n.f("acct_join_request", j.name.ifBlank { "?" }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(j.code, style = MaterialTheme.typography.headlineMedium, fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            if (j.suspicious) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(OmaI18n.t("acct_suspicious"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(OmaI18n.t("acct_join_compare"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onApprove, enabled = !busy && !j.suspicious, modifier = Modifier.weight(1f)) { Text(OmaI18n.t("acct_approve")) }
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text(OmaI18n.t("acct_reject")) }
            }
        }
    }
}

/** The invite QR with its 10-minute countdown; the account syncs every 5 s while it is shown. */
@Composable
private fun InviteDialog(invite: OmaAccount.Invite, onDismiss: () -> Unit, onRenew: () -> Unit) {
    var bmp by remember(invite.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(invite.uri) { bmp = withContext(Dispatchers.Default) { qrBitmap(invite.uri) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(invite.uri) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((invite.expiresAtMs - now) / 1000).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OmaI18n.t("acct_add_device")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(OmaI18n.t("acct_invite_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.size(232.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                        val b = bmp
                        when {
                            left == 0L -> Text(OmaI18n.t("acct_invite_expired"), color = Color.Black, textAlign = TextAlign.Center)
                            b != null -> Image(bitmap = b.asImageBitmap(), contentDescription = OmaI18n.t("acct_add_device"), modifier = Modifier.fillMaxSize())
                            else -> CircularProgressIndicator()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (left > 0) {
                    Text(
                        OmaI18n.f("acct_invite_left", OmaI18n.f("acct_ms", left / 60, left % 60)),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(OmaI18n.t("close")) } },
        dismissButton = { if (left == 0L) TextButton(onClick = onRenew) { Text(OmaI18n.t("acct_new_qr")) } }
    )
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Card(center: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
            content = content
        )
    }
}

@Composable
private fun Banner(text: String, actionLabel: String, onAction: () -> Unit, enabled: Boolean = true) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
