package com.hereliesaz.illumera.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.illumera.data.vpn.NativeVpnPhase
import com.hereliesaz.illumera.data.vpn.NativeVpnProtocol
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.addons.VoidDialog
import kotlinx.coroutines.delay

/**
 * One Settings surface for supported native VPN engines. Never advertises an unimplemented
 * engine as connected. The user's configuration stays on device, in encrypted preferences.
 * VPN consent is launched by the user, not requested at startup or during ordinary playback.
 */
@Composable
fun NativeVpnSettingsDialog(
    onDismiss: () -> Unit,
    viewModel: NativeVpnViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    var protocol by remember { mutableStateOf(NativeVpnProtocol.WIREGUARD) }
    var configText by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var savedWireGuard by remember { mutableStateOf(viewModel.hasWireGuardProfile()) }
    var savedIkev2 by remember { mutableStateOf(viewModel.hasIkev2Profile()) }
    var savedIkev2Ca by remember { mutableStateOf(viewModel.hasIkev2Ca()) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 120.dp).coerceAtLeast(210.dp)

    LaunchedEffect(Unit) { viewModel.refreshIkev2Status() }

    // The OS, not our button callback, decides whether a platform IKEv2 tunnel is
    // actually connected. On Android 13+ poll its real state while the dialog is open.
    LaunchedEffect(status.protocol, status.phase) {
        if (status.protocol == NativeVpnProtocol.IKEV2 &&
            status.phase in setOf(NativeVpnPhase.CONNECTING, NativeVpnPhase.ACTIVE)
        ) {
            while (true) {
                delay(2000)
                viewModel.refreshIkev2Status()
            }
        }
    }

    val wireGuardConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            viewModel.connectWireGuard()
        } else feedback = "Android VPN permission denied; no tunnel started"
    }
    val ikev2Consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            viewModel.connectIkev2()
        } else feedback = "Android VPN permission denied; no tunnel started"
    }
    val importWireGuard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                // A VPN profile is a small text config, not an arbitrary multi-megabyte upload.
                val bytes = readBoundedProfileBytes(context, uri, 131072)
                val imported = bytes.toString(Charsets.UTF_8)
                viewModel.saveWireGuard(imported).getOrThrow()
                savedWireGuard = true
                feedback = "WireGuard profile saved securely"
            }.onFailure { feedback = it.message ?: "Could not import profile" }
        }
    }

    val importIkev2Ca = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = readBoundedProfileBytes(context, uri, 65536)
                viewModel.saveIkev2Ca(bytes).getOrThrow()
                savedIkev2Ca = true
                feedback = "Trusted server CA saved securely"
            }.onFailure { feedback = it.message ?: "Could not import CA certificate" }
        }
    }

    VoidDialog(onDismissRequest = onDismiss, title = "Native VPN") {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Device-wide VPN · opt-in only. Android may show a system permission prompt. " +
                    "Only one VPN can be active; confirm your provider's server and certificates.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeVpnProtocol.entries.forEach { option ->
                    VoidButton(
                        text = option.label,
                        onClick = { protocol = option; feedback = "" },
                        isPrimary = option == protocol,
                        compact = true
                    )
                }
            }
            if (protocol == NativeVpnProtocol.WIREGUARD) {
                Text(
                    if (savedWireGuard) "WireGuard configuration stored (encrypted)"
                    else "Import a WireGuard .conf or paste its text below",
                    color = MaterialTheme.colorScheme.onBackground
                )
                VoidButton(
                    text = "Import .conf file",
                    onClick = { importWireGuard.launch(arrayOf("*/*")) },
                    compact = true
                )
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    label = { Text("WireGuard configuration") },
                    placeholder = { Text("[Interface] … [Peer] …") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 7
                )
                VoidButton(
                    text = "Save WireGuard profile",
                    onClick = {
                        viewModel.saveWireGuard(configText).onSuccess {
                            configText = ""
                            savedWireGuard = true
                            feedback = "WireGuard profile saved securely"
                        }.onFailure { feedback = it.message ?: "Invalid WireGuard configuration" }
                    },
                    compact = true
                )
                VoidButton(
                    text = "Connect WireGuard",
                    onClick = {
                        runCatching {
                            check(savedWireGuard) { "Import a WireGuard profile first" }
                            val consent = viewModel.requestWireGuardConsent()
                            if (consent == null) {
                                viewModel.connectWireGuard()
                            } else wireGuardConsent.launch(consent)
                        }.onFailure { feedback = it.message ?: "Could not request VPN permission" }
                    },
                    compact = true
                )
            } else if (protocol == NativeVpnProtocol.IKEV2) {
                Text(
                    "IKEv2/IPsec works with correctly configured strongSwan and Libreswan servers " +
                        "on Android 11+. This uses Android's native authenticated IPsec engine, " +
                        "not a bundled strongSwan or Libreswan daemon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
                )
                if (savedIkev2) Text(
                    "IKEv2 profile stored (encrypted). Enter new values only to replace it.",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (savedIkev2Ca) "Custom server CA imported (encrypted)"
                    else "Server CA: system-trusted roots by default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                VoidButton(text = "Import server CA (PEM / DER)",
                    onClick = { importIkev2Ca.launch(arrayOf("*/*")) }, compact = true)
                if (savedIkev2Ca) VoidButton(
                    text = "Use system CAs instead",
                    onClick = {
                        viewModel.clearIkev2Ca()
                        savedIkev2Ca = false
                        feedback = "Custom CA cleared"
                    }, compact = true
                )
                OutlinedTextField(server, { server = it }, label = { Text("VPN server hostname") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(identity, { identity = it }, label = { Text("Remote ID / server identity") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                VoidButton(
                    text = "Save IKEv2 profile",
                    onClick = {
                        viewModel.saveIkev2(server, identity, username, password).onSuccess {
                            server = ""; identity = ""; username = ""; password = ""
                            savedIkev2 = true
                            feedback = "IKEv2 profile saved securely"
                        }.onFailure { feedback = it.message ?: "Invalid IKEv2 profile" }
                    }, compact = true
                )
                VoidButton(
                    text = "Connect IKEv2",
                    onClick = {
                        runCatching {
                            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                "IKEv2 requires Android 11 or newer"
                            }
                            check(savedIkev2) { "Save your IKEv2 profile first" }
                            val consent = viewModel.requestIkev2Consent()
                            if (consent == null) {
                                viewModel.connectIkev2()
                            } else ikev2Consent.launch(consent)
                        }.onFailure { feedback = it.message ?: "IKEv2 provisioning failed" }
                    }, compact = true
                )
            } else {
                Text(
                    if (protocol == NativeVpnProtocol.OPENVPN)
                        "OpenVPN 3 requires its native C++ core, JNI tunnel bridge, and protocol-specific certificate import. " +
                            "That engine is not bundled in this build; a .ovpn file alone cannot create a tunnel."
                    else
                        "SoftEther SSL-VPN requires its native TLS and packet-tunnel engine. " +
                            "That engine is not bundled in this build; Android's VpnService cannot negotiate SoftEther by itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "No connect button is offered until the actual protocol engine is integrated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            val phase = when (status.phase) {
                NativeVpnPhase.DISCONNECTED -> "Disconnected"
                NativeVpnPhase.CONNECTING -> "Connecting / requested"
                NativeVpnPhase.ACTIVE -> "VPN interface active"
                NativeVpnPhase.ERROR -> "Connection error"
            }
            Text(
                "Status: $phase" + (status.protocol?.let { " · ${it.label}" } ?: ""),
                color = MaterialTheme.colorScheme.onBackground
            )
            if (status.message.isNotBlank()) Text(status.message, style = MaterialTheme.typography.bodySmall)
            if (feedback.isNotBlank()) Text(
                feedback,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodySmall
            )
            if (status.phase != NativeVpnPhase.DISCONNECTED) {
                VoidButton(text = "Disconnect VPN", onClick = {
                    viewModel.disconnect()
                }, compact = true)
            }
            Spacer(Modifier.height(4.dp))
            VoidButton(text = "Done", onClick = onDismiss, compact = true)
        }
    }
}

/** Read selected VPN files without keeping large or unknown content in app memory. */
private fun readBoundedProfileBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "VPN profile file exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Unable to read VPN profile")
