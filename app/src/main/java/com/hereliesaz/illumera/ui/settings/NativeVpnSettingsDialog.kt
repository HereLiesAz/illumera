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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.illumera.data.vpn.Ikev2AuthMode
import com.hereliesaz.illumera.data.vpn.NativeVpnPhase
import com.hereliesaz.illumera.data.vpn.NativeVpnProtocol
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.addons.VoidDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val importScope = rememberCoroutineScope()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var protocol by remember { mutableStateOf(NativeVpnProtocol.WIREGUARD) }
    var configText by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var localIdentity by remember { mutableStateOf("") }
    var ikev2AuthMode by remember { mutableStateOf(viewModel.ikev2AuthMode()) }
    var preSharedKey by remember { mutableStateOf("") }
    var pkcs12Password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var savedWireGuard by remember { mutableStateOf(viewModel.hasWireGuardProfile()) }
    var savedIkev2 by remember { mutableStateOf(viewModel.hasIkev2Profile()) }
    var savedIkev2Ca by remember { mutableStateOf(viewModel.hasIkev2Ca()) }
    var openVpnText by remember { mutableStateOf("") }
    var openVpnStaged by remember { mutableStateOf(viewModel.hasOpenVpnProfile()) }
    var softEtherStaged by remember { mutableStateOf(viewModel.hasSoftEtherProfile()) }
    var softEtherHost by remember { mutableStateOf("") }
    var softEtherPort by remember { mutableStateOf("443") }
    var softEtherHub by remember { mutableStateOf("DEFAULT") }
    var softEtherUser by remember { mutableStateOf("") }
    var softEtherPassword by remember { mutableStateOf("") }
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
            importScope.launch {
                // Document providers may be cloud-backed; opening/reading/parsing and
                // encrypted writes must never block the Android UI/D-pad thread.
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = readBoundedProfileBytes(context, uri, 131072)
                        viewModel.saveWireGuard(bytes.toString(Charsets.UTF_8)).getOrThrow()
                    }
                }
                result.onSuccess {
                    savedWireGuard = true
                    feedback = "WireGuard profile saved securely"
                }.onFailure { feedback = it.message ?: "Could not import profile" }
            }
        }
    }

    val importIkev2Ca = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = readBoundedProfileBytes(context, uri, 65536)
                        viewModel.saveIkev2Ca(bytes).getOrThrow()
                    }
                }
                result.onSuccess {
                    savedIkev2Ca = true
                    feedback = "Trusted server CA saved securely"
                }.onFailure { feedback = it.message ?: "Could not import CA certificate" }
            }
        }
    }

    val importIkev2ClientIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Never retain a decrypted client private key in Compose state, logs, or a URL.
            val clientServer = server
            val clientLocalId = localIdentity
            val exportPassword = pkcs12Password
            importScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = readBoundedProfileBytes(context, uri, 262144)
                        try {
                            viewModel.saveIkev2Certificate(
                                clientServer, clientLocalId, bytes, exportPassword
                            ).getOrThrow()
                        } finally {
                            bytes.fill(0)
                        }
                    }
                }
                result.onSuccess {
                    savedIkev2 = true
                    ikev2AuthMode = Ikev2AuthMode.CERTIFICATE
                    pkcs12Password = ""
                    feedback = "Client identity saved securely; VPN profile ready"
                }.onFailure { feedback = it.message ?: "PKCS#12 client import failed" }
            }
        }
    }

    val importOpenVpn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = readBoundedProfileBytes(context, uri, 131072)
                        viewModel.stageOpenVpnProfile(bytes.toString(Charsets.UTF_8)).getOrThrow()
                    }
                }
                result.onSuccess { summary ->
                    openVpnStaged = true
                    feedback = "Staged OpenVPN ${summary.transport.uppercase()} profile for ${summary.hostname}:${summary.port}; native engine is not bundled"
                }.onFailure { feedback = it.message ?: "OpenVPN config rejected" }
            }
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
                        val text = configText
                        importScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                viewModel.saveWireGuard(text)
                            }
                            result.onSuccess {
                                configText = ""
                                savedWireGuard = true
                                feedback = "WireGuard profile saved securely"
                            }.onFailure { feedback = it.message ?: "Invalid WireGuard configuration" }
                        }
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
                        "on Android 11+. Local IKE identity is the client's ID, not the server's. " +
                        "Android derives server identity from the hostname and verifies its certificate. " +
                        "This uses Android's native authenticated IPsec engine, " +
                        "not a bundled strongSwan or Libreswan daemon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
                )
                if (savedIkev2) Text(
                    "IKEv2 profile stored (encrypted). Enter new values only to replace it.",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Authentication", color = MaterialTheme.colorScheme.onBackground)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Ikev2AuthMode.entries.forEach { mode ->
                        VoidButton(
                            text = mode.label,
                            compact = true,
                            isPrimary = mode == ikev2AuthMode,
                            onClick = { ikev2AuthMode = mode; feedback = "" }
                        )
                    }
                }
                if (ikev2AuthMode != Ikev2AuthMode.PSK) {
                    Text(
                        if (savedIkev2Ca) "Custom server CA imported (encrypted)"
                        else "Server CA: system-trusted roots by default",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    VoidButton(
                        text = "Import server CA (PEM / DER)",
                        onClick = { importIkev2Ca.launch(arrayOf("*/*")) },
                        compact = true
                    )
                    if (savedIkev2Ca) VoidButton(
                        text = "Use system CAs instead",
                        onClick = {
                            viewModel.clearIkev2Ca()
                            savedIkev2Ca = false
                            feedback = "Custom CA cleared"
                        }, compact = true
                    )
                } else {
                    Text(
                        "The shared key authenticates both peers; X.509 CA trust is not used in PSK mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                OutlinedTextField(
                    server, { server = it }, label = { Text("VPN server hostname") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    localIdentity, { localIdentity = it },
                    label = { Text("Local IKE identity / client ID") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                when (ikev2AuthMode) {
                    Ikev2AuthMode.EAP -> {
                        OutlinedTextField(username, { username = it }, label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(password, { password = it }, label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Ikev2AuthMode.PSK -> {
                        OutlinedTextField(
                            preSharedKey, { preSharedKey = it },
                            label = { Text("IKEv2 pre-shared key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    Ikev2AuthMode.CERTIFICATE -> {
                        Text(
                            "Select a password-protected PKCS#12 client identity (.p12 or .pfx). " +
                                "It must contain both the user certificate and its private key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        OutlinedTextField(
                            pkcs12Password, { pkcs12Password = it },
                            label = { Text("PKCS#12 export password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        VoidButton(
                            text = "Import and save client identity",
                            onClick = {
                                if (server.isBlank() || localIdentity.isBlank() || pkcs12Password.isBlank()) {
                                    feedback = "Enter server, local identity and export password first"
                                } else importIkev2ClientIdentity.launch(arrayOf("*/*"))
                            }, compact = true
                        )
                    }
                }
                if (ikev2AuthMode != Ikev2AuthMode.CERTIFICATE) {
                    VoidButton(
                        text = "Save IKEv2 profile",
                        onClick = {
                            val selectedMode = ikev2AuthMode
                            val host = server
                            val clientId = localIdentity
                            val user = username
                            val userPassword = password
                            val psk = preSharedKey
                            importScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    when (selectedMode) {
                                        Ikev2AuthMode.EAP ->
                                            viewModel.saveIkev2(host, clientId, user, userPassword)
                                        Ikev2AuthMode.PSK ->
                                            viewModel.saveIkev2Psk(host, clientId, psk)
                                        Ikev2AuthMode.CERTIFICATE ->
                                            Result.failure(IllegalStateException("Import a client certificate"))
                                    }
                                }
                                result.onSuccess {
                                    server = ""; localIdentity = ""; username = ""; password = ""
                                    preSharedKey = ""
                                    savedIkev2 = true
                                    feedback = "IKEv2 profile saved securely"
                                }.onFailure { feedback = it.message ?: "Invalid IKEv2 profile" }
                            }
                        }, compact = true
                    )
                }
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
            } else if (protocol == NativeVpnProtocol.OPENVPN) {
                Text(
                    "OpenVPN profiles can be imported for a future native engine. " +
                        "A valid .ovpn file alone does not establish a tunnel. " +
                        "No OpenVPN C++/JNI engine is bundled in this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (openVpnStaged) Text(
                    "OpenVPN configuration staged securely; connection not available",
                    color = MaterialTheme.colorScheme.onBackground
                )
                VoidButton(
                    text = "Import .ovpn file",
                    onClick = { importOpenVpn.launch(arrayOf("*/*")) },
                    compact = true
                )
                OutlinedTextField(
                    value = openVpnText,
                    onValueChange = { openVpnText = it },
                    label = { Text("OpenVPN client configuration") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7
                )
                VoidButton(
                    text = "Stage OpenVPN profile",
                    onClick = {
                        val text = openVpnText
                        importScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                viewModel.stageOpenVpnProfile(text)
                            }
                            result.onSuccess { summary ->
                                openVpnStaged = true
                                openVpnText = ""
                                feedback = "Staged ${summary.transport.uppercase()} VPN profile for ${summary.hostname}; native engine is not bundled"
                            }.onFailure { feedback = it.message ?: "OpenVPN profile rejected" }
                        }
                    }, compact = true
                )
            } else {
                Text(
                    "SoftEther SSL-VPN requires its native TLS protocol and packet tunnel engine. " +
                        "You can save server information, but the engine is not yet part of Illumera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (softEtherStaged) Text(
                    "SoftEther server staged securely; connection not available",
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedTextField(softEtherHost, { softEtherHost = it },
                    label = { Text("SoftEther server hostname") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(softEtherPort, { softEtherPort = it },
                    label = { Text("Server TLS port") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(softEtherHub, { softEtherHub = it },
                    label = { Text("Virtual hub") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(softEtherUser, { softEtherUser = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(softEtherPassword, { softEtherPassword = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                VoidButton(
                    text = "Stage SoftEther server",
                    onClick = {
                        val host = softEtherHost
                        val port = softEtherPort.toIntOrNull() ?: 0
                        val hub = softEtherHub
                        val user = softEtherUser
                        val passwordValue = softEtherPassword
                        importScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                viewModel.stageSoftEtherProfile(host, port, hub, user, passwordValue)
                            }
                            result.onSuccess {
                                softEtherStaged = true
                                softEtherPassword = ""
                                feedback = "SoftEther profile encrypted and saved; native engine not bundled"
                            }.onFailure { feedback = it.message ?: "Invalid SoftEther settings" }
                        }
                    }, compact = true
                )
            }
            if (!protocol.ready) {
                Text(
                    "No Connect button: this protocol has no native packet engine yet.",
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
