@file:Suppress("DEPRECATION")

package com.hereliesaz.illumera.data.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.net.VpnService
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The engine is intentionally explicit. strongSwan and Libreswan are IPsec implementations,
 * not separate wire protocols; their IKEv2 servers use Android's built-in IKEv2/IPsec stack.
 * OpenVPN and SoftEther require independently integrated native packet engines. A VpnService
 * declaration or a parsed .ovpn file alone does NOT establish a secure tunnel.
 */
enum class NativeVpnProtocol(val label: String, val ready: Boolean) {
    WIREGUARD("WireGuard", true),
    IKEV2("IKEv2/IPsec · strongSwan / Libreswan", true),
    OPENVPN("OpenVPN", false),
    SOFTETHER("SoftEther SSL-VPN", false)
}

enum class NativeVpnPhase { DISCONNECTED, CONNECTING, ACTIVE, ERROR }

data class NativeVpnStatus(
    val protocol: NativeVpnProtocol? = null,
    val phase: NativeVpnPhase = NativeVpnPhase.DISCONNECTED,
    val message: String = ""
)

/** Validate remote IKEv2 configuration before provisioning the device VPN profile. */
internal fun validateIkev2Inputs(server: String, identity: String, username: String, password: String): String? {
    if (server.isBlank() || identity.isBlank()) return "Server and remote identity are required"
    if (server.contains("://") || server.any { it.isWhitespace() || it == '/' || it == '@' }) {
        return "Enter a hostname or IP address, not a URL"
    }
    if (username.isBlank() || password.isBlank()) return "Username and password are required"
    return null
}

/**
 * Device-wide, user-initiated VPN connections. Config and credentials are encrypted at rest.
 * Never pretends to be connected after an API request: WireGuard reports the backend's state,
 * while platform IKEv2 remains CONNECTING until the OS establishes the session.
 *
 * Only one VPN can own Android's VPN slot at a time. No background auto-connect or
 * unconsented interception is performed.
 */
@Singleton
class NativeVpnManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val secrets by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "illumera_native_vpn", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val backend by lazy { GoBackend(context) }
    private val tunnel = object : Tunnel {
        override fun getName() = "Illumera"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _status.value.protocol == NativeVpnProtocol.WIREGUARD) {
                _status.value = NativeVpnStatus()
            }
        }
    }

    private val _status = MutableStateFlow(NativeVpnStatus())
    val status: StateFlow<NativeVpnStatus> = _status

    fun hasWireGuardProfile(): Boolean =
        runCatching { !secrets.getString(WG_PROFILE, null).isNullOrBlank() }.getOrDefault(false)

    fun hasIkev2Profile(): Boolean =
        runCatching { !secrets.getString(IKEV2_SERVER, null).isNullOrBlank() }.getOrDefault(false)

    /** Config.parse checks WireGuard key formats, interface addresses and peers before saving. */
    fun saveWireGuardProfile(text: String): Result<Unit> = runCatching {
        require(text.isNotBlank()) { "Paste or import a WireGuard .conf first" }
        Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
        check(secrets.edit().putString(WG_PROFILE, text).commit()) { "Could not store encrypted VPN profile" }
    }

    fun saveIkev2Profile(server: String, identity: String, username: String, password: String): Result<Unit> =
        runCatching {
            require(validateIkev2Inputs(server, identity, username, password) == null) {
                validateIkev2Inputs(server, identity, username, password).orEmpty()
            }
            check(secrets.edit()
                .putString(IKEV2_SERVER, server.trim())
                .putString(IKEV2_IDENTITY, identity.trim())
                .putString(IKEV2_USER, username.trim())
                .putString(IKEV2_PASSWORD, password)
                .commit()) { "Could not store encrypted IKEv2 credentials" }
        }

    /**
     * The caller must launch the returned consent Intent and call connectWireGuard()
     * only after RESULT_OK. A null Intent means the package already holds permission.
     */
    fun wireGuardConsent(): Intent? = VpnService.prepare(context)

    /** Android 11+ provides native IKEv2 EAP-MSCHAPv2 with certificate-verified servers. */
    fun provisionIkev2(): Intent? {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "IKEv2 requires Android 11 or newer" }
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)) {
            "This Android device does not support IPsec tunnels"
        }
        val prefs = secrets
        val server = prefs.getString(IKEV2_SERVER, null).orEmpty()
        val identity = prefs.getString(IKEV2_IDENTITY, null).orEmpty()
        val user = prefs.getString(IKEV2_USER, null).orEmpty()
        val password = prefs.getString(IKEV2_PASSWORD, null).orEmpty()
        validateIkev2Inputs(server, identity, user, password)?.let { error(error = it) }
        val profile = Ikev2VpnProfile.Builder(server, identity)
            .setAuthUsernamePassword(user, password, null)
            .build()
        return context.getSystemService(VpnManager::class.java).provisionVpnProfile(profile)
    }

    suspend fun connectWireGuard(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(VpnService.prepare(context) == null) { "VPN permission was not granted" }
            val text = secrets.getString(WG_PROFILE, null)
                ?: error("Import a WireGuard configuration first")
            val config = Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
            stopIkev2IfRunning()
            _status.value = NativeVpnStatus(NativeVpnProtocol.WIREGUARD, NativeVpnPhase.CONNECTING)
            check(backend.setState(tunnel, Tunnel.State.UP, config) == Tunnel.State.UP) {
                "WireGuard could not establish its VPN interface"
            }
            _status.value = NativeVpnStatus(NativeVpnProtocol.WIREGUARD, NativeVpnPhase.ACTIVE,
                "WireGuard tunnel interface active")
        }.onFailure {
            _status.value = NativeVpnStatus(NativeVpnProtocol.WIREGUARD, NativeVpnPhase.ERROR,
                it.message ?: "WireGuard connection failed")
        }
    }

    /** Invoke only after provisionIkev2 consent has completed. */
    suspend fun connectIkev2(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "IKEv2 requires Android 11+" }
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            val manager = context.getSystemService(VpnManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.startProvisionedVpnProfileSession()
            } else {
                @Suppress("DEPRECATION")
                manager.startProvisionedVpnProfile()
            }
            _status.value = NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.CONNECTING,
                "IKEv2 session requested; Android manages authentication and connection")
        }.onFailure {
            _status.value = NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.ERROR,
                it.message ?: "IKEv2 connection failed")
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (_status.value.protocol) {
                NativeVpnProtocol.WIREGUARD -> backend.setState(tunnel, Tunnel.State.DOWN, null)
                NativeVpnProtocol.IKEV2 -> stopIkev2IfRunning()
                else -> Unit
            }
            _status.value = NativeVpnStatus()
        }.onFailure {
            _status.value = NativeVpnStatus(_status.value.protocol, NativeVpnPhase.ERROR,
                it.message ?: "VPN disconnect failed")
        }
    }

    private fun stopIkev2IfRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.getSystemService(VpnManager::class.java).stopProvisionedVpnProfile() }
        }
    }

    private companion object {
        const val WG_PROFILE = "wireguard_profile"
        const val IKEV2_SERVER = "ikev2_server"
        const val IKEV2_IDENTITY = "ikev2_remote_identity"
        const val IKEV2_USER = "ikev2_username"
        const val IKEV2_PASSWORD = "ikev2_password"
    }
}
