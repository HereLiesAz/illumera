package com.hereliesaz.illumera.data.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.system.Os
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.AuthMethod
import vn.unlimit.softether.model.ConnectionConfig
import java.io.StringReader

/**
 * Actual embedded protocol runtimes. The upstream OpenVPN library ships its native executables
 * for our two ABIs; the pinned SoftEther library builds its C/JNI engine and establishes its own
 * Android VpnService/TUN. Neither service is faked by an Illumera-only state flag.
 *
 * Importantly: OpenVPN's upstream ProfileManager has an encryption fallback to plaintext.
 * The temporary profile is only permitted to run if the encrypted .cp file exists after saving;
 * its plaintext .vp fallback is immediately deleted and connection is refused.
 */
internal class EmbeddedVpnController(
    private val context: Context,
    private val onStatus: (NativeVpnProtocol, NativeVpnPhase, String) -> Unit
) {
    private val openVpnStateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?, logmessage: String?, localizedResId: Int,
            level: ConnectionStatus, intent: Intent?
        ) {
            val phase = when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> NativeVpnPhase.ACTIVE
                ConnectionStatus.LEVEL_AUTH_FAILED -> NativeVpnPhase.ERROR
                ConnectionStatus.LEVEL_NOTCONNECTED -> NativeVpnPhase.DISCONNECTED
                else -> NativeVpnPhase.CONNECTING
            }
            onStatus(NativeVpnProtocol.OPENVPN, phase, state ?: level.name)
        }
        override fun setConnectedVPN(uuid: String?) = Unit
    }

    private val softEtherStateListener = object : SoftEtherVpnService.StateListener {
        override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
            val phase = when (state) {
                SoftEtherVpnService.STATE_CONNECTED -> NativeVpnPhase.ACTIVE
                SoftEtherVpnService.STATE_ERROR -> NativeVpnPhase.ERROR
                SoftEtherVpnService.STATE_DISCONNECTED -> NativeVpnPhase.DISCONNECTED
                else -> NativeVpnPhase.CONNECTING
            }
            onStatus(
                NativeVpnProtocol.SOFTETHER, phase,
                if (phase == NativeVpnPhase.ACTIVE && assignedIp.isNotBlank())
                    "SoftEther tunnel active ($assignedIp)" else "SoftEther $state"
            )
        }
    }

    init {
        VpnStatus.addStateListener(openVpnStateListener)
        SoftEtherVpnService.addStateListener(softEtherStateListener)
    }

    fun connectOpenVpn(text: String) {
        check(VpnService.prepare(context) == null) { "Android VPN permission required" }
        inspectOpenVpnProfile(text).getOrThrow()
        val parser = ConfigParser()
        parser.parseConfig(StringReader(text))
        val profile = parser.convertProfile()
        profile.mName = "Illumera OpenVPN"
        val encrypted = context.getFileStreamPath("temporary-vpn-profile.cp")
        val plaintext = context.getFileStreamPath("temporary-vpn-profile.vp")
        encrypted.delete()
        plaintext.delete()
        try {
            ProfileManager.setTemporaryProfile(context, profile)
            val plaintextWritten = plaintext.exists()
            if (plaintextWritten) plaintext.delete()
            check(!plaintextWritten && encrypted.isFile && encrypted.length() > 0L) {
                "OpenVPN profile encryption unavailable; refusing unsafe plaintext credentials"
            }
            VPNLaunchHelper.startOpenVpn(profile, context, "Illumera user connection", true)
        } catch (error: Exception) {
            plaintext.delete()
            encrypted.delete()
            throw error
        }
    }

    fun connectSoftEther(
        host: String, port: Int, hub: String, user: String, password: String,
        trustedServerCertSha256: String
    ) {
        check(VpnService.prepare(context) == null) { "Android VPN permission required" }
        validateSoftEtherProfile(host, port, hub, user, password)?.let { error(it) }
        val pin = normalizedSoftEtherCertPin(trustedServerCertSha256)
            ?: error("A trusted SoftEther certificate SHA-256 fingerprint is required")
        // The upstream VPN service runs in this app process. Its native OpenSSL
        // handshake obtains the exact expected pin from this process environment.
        // Never accept TOFU or learn the fingerprint from the untrusted connection.
        Os.setenv("ILLUMERA_SOFTETHER_CERT_SHA256", pin, true)
        val config = ConnectionConfig(
            serverHost = host, serverPort = port,
            username = user, password = password, virtualHub = hub,
            sessionName = "Illumera SoftEther",
            clientProductName = "Illumera", authMethod = AuthMethod.PASSWORD,
            useTcp = true, useUdp = false
        )
        // A plain started service: the VPN only runs while Illumera is in the foreground.
        context.startService(Intent(context, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        })
    }

    fun disconnect(protocol: NativeVpnProtocol) {
        when (protocol) {
            NativeVpnProtocol.OPENVPN -> {
                context.startService(Intent(context, OpenVPNService::class.java).apply {
                    action = OpenVPNService.DISCONNECT_VPN
                })
                context.deleteFile("temporary-vpn-profile.cp")
                context.deleteFile("temporary-vpn-profile.vp")
            }
            NativeVpnProtocol.SOFTETHER -> {
                context.startService(Intent(context, SoftEtherVpnService::class.java).apply {
                    action = SoftEtherVpnService.ACTION_DISCONNECT
                })
                // A stale pin must not become a default trust choice for another server.
                runCatching { Os.unsetenv("ILLUMERA_SOFTETHER_CERT_SHA256") }
            }
            else -> Unit
        }
    }
}
