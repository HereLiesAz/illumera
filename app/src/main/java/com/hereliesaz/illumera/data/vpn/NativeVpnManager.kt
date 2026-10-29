@file:Suppress("DEPRECATION")

package com.hereliesaz.illumera.data.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.net.VpnProfileState
import android.net.VpnService
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    OPENVPN("OpenVPN", true),
    SOFTETHER("SoftEther SSL-VPN", true)
}

enum class NativeVpnPhase { DISCONNECTED, CONNECTING, ACTIVE, ERROR }

/** Ignore stale, immediately replayed disconnect when attaching a VPN state listener. */
internal fun shouldAcceptEmbeddedVpnStatus(
    current: NativeVpnStatus,
    protocol: NativeVpnProtocol,
    incoming: NativeVpnPhase
): Boolean = current.protocol == protocol &&
    !(current.phase == NativeVpnPhase.CONNECTING && incoming == NativeVpnPhase.DISCONNECTED)

/** Reaffirm disclosure for each user-initiated VPN session, never implied by an import. */
internal fun mayRequestNativeVpnConnection(
    protocol: NativeVpnProtocol,
    disclosureAccepted: Boolean
): Boolean = protocol.ready && disclosureAccepted

/** Native Android IKEv2 authentication modes (API 30+). */
enum class Ikev2AuthMode(val label: String) {
    EAP("Username / Password (EAP)"),
    PSK("Pre-shared key (PSK)"),
    CERTIFICATE("Client certificate (PKCS#12)")
}

internal fun validateIkev2Server(server: String, clientIdentity: String): String? {
    if (server.isBlank() || clientIdentity.isBlank()) return "Server and local IKE identity are required"
    if (server.contains("://") || server.any { it.isWhitespace() || it == '/' || it == '@' }) {
        return "Enter a hostname or IP address, not a URL"
    }
    return null
}

data class NativeVpnStatus(
    val protocol: NativeVpnProtocol? = null,
    val phase: NativeVpnPhase = NativeVpnPhase.DISCONNECTED,
    val message: String = ""
)

/** EAP-MSCHAPv2 uses the provided *local* client IKE identity, not a remote server ID. */
internal fun validateIkev2Inputs(server: String, identity: String, username: String, password: String): String? =
    validateIkev2Server(server, identity)
        ?: if (username.isBlank() || password.isBlank()) "Username and password are required" else null

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
    // Android owns a single VPN slot. Serialize user taps and protocol switches.
    private val connectionMutex = Mutex()
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
    private val embedded by lazy {
        EmbeddedVpnController(context) { protocol, phase, message ->
            // Ignore stale callbacks from a previously stopped engine.
            if (shouldAcceptEmbeddedVpnStatus(_status.value, protocol, phase)) {
                _status.value = NativeVpnStatus(protocol, phase, message)
            }
        }
    }

    init {
        // The platform IPsec session survives the UI/activity process. Recover its real
        // state so a reopened app can disconnect an already established device-wide VPN.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(VpnManager::class.java).provisionedVpnProfileState
            }.getOrNull()?.let { platformState ->
                if (platformState.state == VpnProfileState.STATE_CONNECTED ||
                    platformState.state == VpnProfileState.STATE_CONNECTING
                ) {
                    _status.value = NativeVpnStatus(
                        NativeVpnProtocol.IKEV2,
                        if (platformState.state == VpnProfileState.STATE_CONNECTED)
                            NativeVpnPhase.ACTIVE else NativeVpnPhase.CONNECTING,
                        "Restored Android IKEv2 session state"
                    )
                }
            }
        }
    }

    /** Staging is intentionally separate from a connection; native engines are not shipped. */
    fun hasOpenVpnProfile(): Boolean =
        runCatching { !secrets.getString(OPENVPN_PROFILE, null).isNullOrBlank() }.getOrDefault(false)

    fun stageOpenVpnProfile(text: String): Result<OpenVpnProfileSummary> = runCatching {
        val summary = inspectOpenVpnProfile(text).getOrThrow()
        check(secrets.edit().putString(OPENVPN_PROFILE, text).commit()) {
            "Could not encrypt staged OpenVPN profile"
        }
        summary
    }

    fun hasSoftEtherProfile(): Boolean =
        runCatching { !secrets.getString(SOFTETHER_HOST, null).isNullOrBlank() }.getOrDefault(false)

    fun stageSoftEtherProfile(
        host: String, port: Int, hub: String, username: String, password: String
    ): Result<Unit> = runCatching {
        validateSoftEtherProfile(host, port, hub, username, password)?.let { error(it) }
        check(secrets.edit()
            .putString(SOFTETHER_HOST, host.trim())
            .putInt(SOFTETHER_PORT, port)
            .putString(SOFTETHER_HUB, hub.trim())
            .putString(SOFTETHER_USER, username)
            .putString(SOFTETHER_PASSWORD, password)
            .commit()) { "Could not encrypt staged SoftEther profile" }
    }

    fun hasWireGuardProfile(): Boolean =
        runCatching { !secrets.getString(WG_PROFILE, null).isNullOrBlank() }.getOrDefault(false)

    fun hasIkev2Profile(): Boolean =
        runCatching { !secrets.getString(IKEV2_SERVER, null).isNullOrBlank() }.getOrDefault(false)

    fun ikev2AuthMode(): Ikev2AuthMode =
        runCatching {
            Ikev2AuthMode.entries.firstOrNull {
                it.name == secrets.getString(IKEV2_AUTH_MODE, Ikev2AuthMode.EAP.name)
            } ?: Ikev2AuthMode.EAP
        }.getOrDefault(Ikev2AuthMode.EAP)

    fun hasIkev2Ca(): Boolean =
        runCatching { !secrets.getString(IKEV2_CA, null).isNullOrBlank() }.getOrDefault(false)

    /**
     * Accept PEM or DER X.509 server CA certificates. A private CA is common on self-hosted
     * strongSwan/Libreswan servers. Never disable server identity/certificate validation.
     */
    fun saveIkev2Ca(bytes: ByteArray): Result<Unit> = runCatching {
        require(bytes.isNotEmpty() && bytes.size <= 65536) { "CA certificate must be 1–64 KiB" }
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        require(cert.basicConstraints >= 0) { "Expected an X.509 CA certificate" }
        cert.checkValidity()
        check(secrets.edit().putString(IKEV2_CA, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .commit()) { "Unable to store encrypted CA certificate" }
    }

    fun clearIkev2Ca() {
        runCatching { secrets.edit().remove(IKEV2_CA).commit() }
    }

    /** Config.parse checks WireGuard key formats, interface addresses and peers before saving. */
    fun saveWireGuardProfile(text: String): Result<Unit> = runCatching {
        require(text.isNotBlank()) { "Paste or import a WireGuard .conf first" }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= 131072) {
            "WireGuard configuration exceeds 128 KiB"
        }
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
                .putString(IKEV2_AUTH_MODE, Ikev2AuthMode.EAP.name)
                .remove(IKEV2_PSK)
                .remove(IKEV2_PKCS12)
                .remove(IKEV2_PKCS12_PASSWORD)
                .commit()) { "Could not store encrypted IKEv2 credentials" }
        }

    /** PSK authenticates both IKE peers using the shared secret, not an X.509 server CA. */
    fun saveIkev2PskProfile(server: String, clientIdentity: String, psk: String): Result<Unit> =
        runCatching {
            validateIkev2Server(server, clientIdentity)?.let { error(it) }
            require(psk.length >= 12 && psk.toByteArray(StandardCharsets.UTF_8).size <= 4096) {
                "Use a strong PSK of at least 12 characters (maximum 4 KiB)"
            }
            check(secrets.edit()
                .putString(IKEV2_SERVER, server.trim())
                .putString(IKEV2_IDENTITY, clientIdentity.trim())
                .putString(IKEV2_AUTH_MODE, Ikev2AuthMode.PSK.name)
                .putString(IKEV2_PSK, psk)
                .remove(IKEV2_USER).remove(IKEV2_PASSWORD)
                .remove(IKEV2_PKCS12).remove(IKEV2_PKCS12_PASSWORD)
                .commit()) { "Could not store encrypted PSK profile" }
        }

    /** PKCS#12 client keys are parsed locally and encrypted; passwords never leave the device. */
    fun saveIkev2CertificateProfile(
        server: String, clientIdentity: String, pkcs12Bytes: ByteArray, password: String
    ): Result<Unit> = runCatching {
        validateIkev2Server(server, clientIdentity)?.let { error(it) }
        require(pkcs12Bytes.size in 1..262144) { "PKCS#12 client identity must be 1–256 KiB" }
        require(password.isNotBlank()) { "PKCS#12 export password is required" }
        parseClientIdentity(pkcs12Bytes, password)
        check(secrets.edit()
            .putString(IKEV2_SERVER, server.trim())
            .putString(IKEV2_IDENTITY, clientIdentity.trim())
            .putString(IKEV2_AUTH_MODE, Ikev2AuthMode.CERTIFICATE.name)
            .putString(IKEV2_PKCS12, Base64.encodeToString(pkcs12Bytes, Base64.NO_WRAP))
            .putString(IKEV2_PKCS12_PASSWORD, password)
            .remove(IKEV2_USER).remove(IKEV2_PASSWORD).remove(IKEV2_PSK)
            .commit()) { "Could not store encrypted client certificate profile" }
    }

    private fun parseClientIdentity(bytes: ByteArray, password: String): Pair<X509Certificate, PrivateKey> {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(bytes), password.toCharArray())
        val alias = store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) }
            ?: error("No client private key in the PKCS#12 bundle")
        val certificate = store.getCertificate(alias) as? X509Certificate
            ?: error("No X.509 client certificate in the PKCS#12 bundle")
        certificate.checkValidity()
        val key = store.getKey(alias, password.toCharArray()) as? PrivateKey
            ?: error("Could not unlock the client private key")
        // Android's platform Ikev2VpnProfile.setAuthDigitalSignature requires RSA keys.
        // Reject unsupported EC identities before replacing any previously saved profile.
        require(key is RSAPrivateKey && certificate.publicKey is RSAPublicKey) {
            "Android platform IKEv2 requires an RSA client certificate and private key"
        }
        return certificate to key
    }

    /**
     * The caller must launch the returned consent Intent and call connectWireGuard()
     * only after RESULT_OK. A null Intent means the package already holds permission.
     */
    fun wireGuardConsent(): Intent? = VpnService.prepare(context)

    /** Guard Android 11's platform VPN APIs on devices with minSdk 26. */
    fun provisionIkev2(): Intent? {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "IKEv2 requires Android 11 or newer" }
        return provisionIkev2Api30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun provisionIkev2Api30(): Intent? {
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)) {
            "This Android device does not support IPsec tunnels"
        }
        val prefs = secrets
        val server = prefs.getString(IKEV2_SERVER, null).orEmpty()
        val identity = prefs.getString(IKEV2_IDENTITY, null).orEmpty()
        validateIkev2Server(server, identity)?.let { error(it) }
        val mode = ikev2AuthMode()
        // PSK authentication does not use X.509 roots. Do not let an expired staged
        // CA prevent a perfectly valid PSK profile from being provisioned.
        val serverRootCa = prefs.getString(IKEV2_CA, null)
            ?.takeIf { mode != Ikev2AuthMode.PSK }
            ?.let { encoded ->
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            require(cert.basicConstraints >= 0) { "Stored server certificate is not a CA" }
            cert.checkValidity()
            cert
        }
        val builder = Ikev2VpnProfile.Builder(server, identity)
        when (mode) {
            Ikev2AuthMode.EAP -> {
                val user = prefs.getString(IKEV2_USER, null).orEmpty()
                val password = prefs.getString(IKEV2_PASSWORD, null).orEmpty()
                validateIkev2Inputs(server, identity, user, password)?.let { error(it) }
                builder.setAuthUsernamePassword(user, password, serverRootCa)
            }
            Ikev2AuthMode.PSK -> {
                val psk = prefs.getString(IKEV2_PSK, null)?.takeIf { it.isNotBlank() }
                    ?: error("Save an IKEv2 pre-shared key before connecting")
                builder.setAuthPsk(psk.toByteArray(StandardCharsets.UTF_8))
            }
            Ikev2AuthMode.CERTIFICATE -> {
                val encoded = prefs.getString(IKEV2_PKCS12, null)
                    ?: error("Import a PKCS#12 client certificate first")
                val password = prefs.getString(IKEV2_PKCS12_PASSWORD, null)
                    ?: error("PKCS#12 password is missing")
                val (cert, privateKey) = parseClientIdentity(Base64.decode(encoded, Base64.DEFAULT), password)
                builder.setAuthDigitalSignature(cert, privateKey, serverRootCa)
            }
        }
        val profile = builder.build()
        return context.getSystemService(VpnManager::class.java).provisionVpnProfile(profile)
    }

    suspend fun connectWireGuard(): Result<Unit> = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            runCatching {
                check(VpnService.prepare(context) == null) { "VPN permission was not granted" }
                val text = secrets.getString(WG_PROFILE, null)
                    ?: error("Import a WireGuard configuration first")
                val config = Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
                stopIkev2IfRunning()
                stopEmbeddedIfRunning()
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
    }

    /** Invoke only after provisionIkev2 consent has completed. */
    suspend fun connectIkev2(): Result<Unit> = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            runCatching {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "IKEv2 requires Android 11+" }
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                stopEmbeddedIfRunning()
                startIkev2Api30()
                _status.value = NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.CONNECTING,
                    "IKEv2 session requested; Android manages authentication and connection")
            }.onFailure {
                _status.value = NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.ERROR,
                    it.message ?: "IKEv2 connection failed")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startIkev2Api30() {
        val manager = context.getSystemService(VpnManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.startProvisionedVpnProfileSession()
        } else {
            @Suppress("DEPRECATION")
            manager.startProvisionedVpnProfile()
        }
    }

    /** Only Android 13+ reports actual platform IKEv2 state to an ordinary app. */
    fun refreshIkev2Status() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (_status.value.protocol != NativeVpnProtocol.IKEV2) return
        runCatching {
            context.getSystemService(VpnManager::class.java).provisionedVpnProfileState
        }.getOrNull()?.let { platformState ->
            _status.value = when (platformState.state) {
                VpnProfileState.STATE_CONNECTED ->
                    NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.ACTIVE,
                        "Android reports an established IPsec tunnel")
                VpnProfileState.STATE_CONNECTING ->
                    NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.CONNECTING,
                        "Android is negotiating the IKEv2 session")
                VpnProfileState.STATE_FAILED ->
                    NativeVpnStatus(NativeVpnProtocol.IKEV2, NativeVpnPhase.ERROR,
                        "Android reports that IPsec negotiation failed")
                else -> NativeVpnStatus()
            }
        }
    }

    /** OpenVPN owns a real Android VpnService with native packet I/O. */
    suspend fun connectOpenVpn(): Result<Unit> = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            runCatching {
                val profile = secrets.getString(OPENVPN_PROFILE, null)
                    ?: error("Import and save an OpenVPN profile first")
                check(VpnService.prepare(context) == null) { "Android VPN permission required" }
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                stopIkev2IfRunning()
                stopEmbeddedIfRunning()
                _status.value = NativeVpnStatus(NativeVpnProtocol.OPENVPN, NativeVpnPhase.CONNECTING,
                    "Starting embedded OpenVPN tunnel")
                embedded.connectOpenVpn(profile)
            }.onFailure {
                _status.value = NativeVpnStatus(NativeVpnProtocol.OPENVPN, NativeVpnPhase.ERROR,
                    it.message ?: "OpenVPN connection failed")
            }
        }
    }

    /** SoftEther uses its own foreground VpnService and native TLS/JNI TUN bridge. */
    suspend fun connectSoftEther(): Result<Unit> = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            runCatching {
                val prefs = secrets
                val host = prefs.getString(SOFTETHER_HOST, null)
                    ?: error("Save a SoftEther server first")
                val port = prefs.getInt(SOFTETHER_PORT, 0)
                val hub = prefs.getString(SOFTETHER_HUB, null).orEmpty()
                val user = prefs.getString(SOFTETHER_USER, null).orEmpty()
                val password = prefs.getString(SOFTETHER_PASSWORD, null).orEmpty()
                check(VpnService.prepare(context) == null) { "Android VPN permission required" }
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                stopIkev2IfRunning()
                stopEmbeddedIfRunning()
                _status.value = NativeVpnStatus(NativeVpnProtocol.SOFTETHER, NativeVpnPhase.CONNECTING,
                    "Starting native SoftEther TLS tunnel")
                embedded.connectSoftEther(host, port, hub, user, password)
            }.onFailure {
                _status.value = NativeVpnStatus(NativeVpnProtocol.SOFTETHER, NativeVpnPhase.ERROR,
                    it.message ?: "SoftEther connection failed")
            }
        }
    }

    private fun stopEmbeddedIfRunning() {
        val protocol = _status.value.protocol
        if (protocol == NativeVpnProtocol.OPENVPN || protocol == NativeVpnProtocol.SOFTETHER) {
            embedded.disconnect(protocol)
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            runCatching {
                when (_status.value.protocol) {
                    NativeVpnProtocol.WIREGUARD -> backend.setState(tunnel, Tunnel.State.DOWN, null)
                    NativeVpnProtocol.IKEV2 -> stopIkev2IfRunning()
                    NativeVpnProtocol.OPENVPN, NativeVpnProtocol.SOFTETHER ->
                        embedded.disconnect(_status.value.protocol!!)
                    else -> Unit
                }
                _status.value = NativeVpnStatus()
            }.onFailure {
                _status.value = NativeVpnStatus(_status.value.protocol, NativeVpnPhase.ERROR,
                    it.message ?: "VPN disconnect failed")
            }
        }
    }

    private fun stopIkev2IfRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            _status.value.protocol == NativeVpnProtocol.IKEV2
        ) {
            // Do not report a successful disconnect when Android rejected the request.
            context.getSystemService(VpnManager::class.java).stopProvisionedVpnProfile()
        }
    }

    private companion object {
        const val WG_PROFILE = "wireguard_profile"
        const val OPENVPN_PROFILE = "openvpn_profile_staged"
        const val SOFTETHER_HOST = "softether_host_staged"
        const val SOFTETHER_PORT = "softether_port_staged"
        const val SOFTETHER_HUB = "softether_hub_staged"
        const val SOFTETHER_USER = "softether_user_staged"
        const val SOFTETHER_PASSWORD = "softether_password_staged"
        const val IKEV2_SERVER = "ikev2_server"
        const val IKEV2_IDENTITY = "ikev2_remote_identity"
        const val IKEV2_USER = "ikev2_username"
        const val IKEV2_PASSWORD = "ikev2_password"
        const val IKEV2_CA = "ikev2_root_ca"
        const val IKEV2_AUTH_MODE = "ikev2_auth_mode"
        const val IKEV2_PSK = "ikev2_psk"
        const val IKEV2_PKCS12 = "ikev2_pkcs12"
        const val IKEV2_PKCS12_PASSWORD = "ikev2_pkcs12_password"
    }
}
