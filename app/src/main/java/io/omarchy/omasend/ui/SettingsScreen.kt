package io.omarchy.omasend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.omarchy.omasend.BuildConfig
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.crypto.OmaIdentity
import io.omarchy.omasend.model.DiscoveryMode
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.service.OmaSendForegroundService
import io.omarchy.omasend.ui.theme.JetBrainsMonoFontFamily

/** Visible = look for nearby devices and answer them; hidden stops discovery and the service. */
fun setVisibility(context: Context, app: OmaSendApp, visible: Boolean) {
    if (visible) {
        app.discoveryManager.setMode(DiscoveryMode.EVERYONE)
        OmaSendForegroundService.startService(context)
    } else {
        app.discoveryManager.setMode(DiscoveryMode.OFF)
        OmaSendForegroundService.stopService(context)
    }
}

/** Copies [text]; [sensitive] keeps it out of clipboard previews and the clipboard sync (Android 13+). */
private fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, OmaI18n.t("copied"), Toast.LENGTH_SHORT).show()
}

/** `1234 5678 9012 3456`, or only the last group when hidden: `•••• •••• •••• 3456`. */
fun displayOmaId(formattedId: String, revealed: Boolean): String {
    val groups = OmaIdentity.unformat(formattedId).chunked(4)
    if (groups.isEmpty()) return ""
    return if (revealed) groups.joinToString(" ")
    else (List(groups.size - 1) { "••••" } + groups.last()).joinToString(" ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: OmaSendApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val discoveryMode by app.discoveryManager.discoveryMode.collectAsState()

    var omaId by remember { mutableStateOf(app.omaIdentity.formattedId) }
    var revealed by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(NetworkUtils.getDeviceName(context)) }
    var soundOn by remember { mutableStateOf(app.soundEngine.isSoundEnabled()) }
    val deviceId = remember { DeviceKey.identity(context).deviceId }

    var showOmaIdEntry by remember { mutableStateOf(false) }
    var showOmaIdReset by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showWebPortal by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(OmaI18n.t("settings"), fontWeight = FontWeight.SemiBold) },
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ---- OmaID: the account number, Mullvad style
            SectionLabel(OmaI18n.t("omaid"))
            SettingsGroup {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayOmaId(omaId, revealed),
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
                        IconButton(onClick = { copyToClipboard(context, "OmaID", OmaIdentity.unformat(omaId), sensitive = true) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = OmaI18n.t("copy"))
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(OmaI18n.t("omaid_body"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { showOmaIdEntry = true }, modifier = Modifier.fillMaxWidth()) { Text(OmaI18n.t("omaid_use_existing")) }
                    TextButton(onClick = { showOmaIdReset = true }, modifier = Modifier.fillMaxWidth()) { Text(OmaI18n.t("omaid_new")) }
                }
            }

            // ---- this device
            SectionLabel(OmaI18n.t("this_device"))
            SettingsGroup {
                SettingsRow(title = OmaI18n.t("device_name"), value = deviceName, onClick = { showRename = true })
                GroupDivider()
                SettingsRow(
                    title = OmaI18n.t("device_id"),
                    value = deviceId,
                    mono = true,
                    onClick = { copyToClipboard(context, OmaI18n.t("device_id"), deviceId) }
                )
            }

            // ---- preferences
            SectionLabel(OmaI18n.t("preferences"))
            SettingsGroup {
                SwitchRow(
                    title = OmaI18n.t("visibility"),
                    subtitle = OmaI18n.t("visibility_body"),
                    checked = discoveryMode != DiscoveryMode.OFF,
                    onChange = { setVisibility(context, app, it) }
                )
                GroupDivider()
                SwitchRow(
                    title = OmaI18n.t("sounds"),
                    subtitle = OmaI18n.t("sounds_body"),
                    checked = soundOn,
                    onChange = {
                        soundOn = it
                        app.soundEngine.setSoundEnabled(it)
                    }
                )
                GroupDivider()
                SettingsRow(title = OmaI18n.t("web_portal"), subtitle = OmaI18n.t("web_portal_body"), onClick = { showWebPortal = true })
            }

            // ---- about
            SectionLabel(OmaI18n.t("about"))
            SettingsGroup {
                Text(
                    OmaI18n.t("about_body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
                GroupDivider()
                SettingsRow(title = OmaI18n.t("version"), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", mono = true)
                GroupDivider()
                SettingsRow(title = OmaI18n.t("source_code"), value = "GitHub", onClick = { openUrl("https://github.com/ozdil/omasend-android") })
                GroupDivider()
                SettingsRow(title = OmaI18n.t("support"), value = "Buy Me a Coffee", onClick = { openUrl("https://buymeacoffee.com/ozdil") })
                GroupDivider()
                SettingsRow(title = OmaI18n.t("license"))
            }
        }
    }

    if (showOmaIdEntry) {
        OmaIdEntryDialog(
            onDismiss = { showOmaIdEntry = false },
            onConfirm = { id ->
                omaId = app.setCustomOmaIdentity(id).formattedId
                showOmaIdEntry = false
                Toast.makeText(context, OmaI18n.t("omaid_changed"), Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showOmaIdReset) {
        ConfirmDialog(
            title = OmaI18n.t("omaid_new"),
            body = OmaI18n.t("omaid_new_confirm"),
            confirmLabel = OmaI18n.t("omaid_new"),
            onConfirm = {
                omaId = app.resetOmaIdentity().formattedId
                revealed = true
                showOmaIdReset = false
            },
            onDismiss = { showOmaIdReset = false }
        )
    }

    if (showRename) {
        RenameDeviceDialog(
            currentName = deviceName,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                NetworkUtils.setDeviceName(context, name)
                deviceName = name
                showRename = false
            }
        )
    }

    if (showWebPortal) {
        WebPortalDialog(
            onDismiss = { showWebPortal = false },
            onCopy = { url -> copyToClipboard(context, OmaI18n.t("web_portal"), url) }
        )
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val body: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (value != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (mono) JetBrainsMonoFontFamily else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) { body() }
    } else {
        body()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
