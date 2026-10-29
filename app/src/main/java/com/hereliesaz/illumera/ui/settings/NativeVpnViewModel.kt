package com.hereliesaz.illumera.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hereliesaz.illumera.data.vpn.NativeVpnManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI boundary for explicit user-initiated native VPN operations. */
@HiltViewModel
class NativeVpnViewModel @Inject constructor(private val manager: NativeVpnManager) : ViewModel() {
    val status = manager.status

    fun hasOpenVpnProfile(): Boolean = manager.hasOpenVpnProfile()
    fun stageOpenVpnProfile(text: String) = manager.stageOpenVpnProfile(text)
    fun hasSoftEtherProfile(): Boolean = manager.hasSoftEtherProfile()
    fun stageSoftEtherProfile(
        host: String, port: Int, hub: String, username: String, password: String
    ) = manager.stageSoftEtherProfile(host, port, hub, username, password)
    fun hasWireGuardProfile(): Boolean = manager.hasWireGuardProfile()
    fun hasIkev2Profile(): Boolean = manager.hasIkev2Profile()
    fun hasIkev2Ca(): Boolean = manager.hasIkev2Ca()
    fun saveIkev2Ca(bytes: ByteArray) = manager.saveIkev2Ca(bytes)
    fun clearIkev2Ca() = manager.clearIkev2Ca()
    fun saveWireGuard(text: String) = manager.saveWireGuardProfile(text)
    fun ikev2AuthMode() = manager.ikev2AuthMode()
    fun saveIkev2(server: String, identity: String, username: String, password: String) =
        manager.saveIkev2Profile(server, identity, username, password)
    fun saveIkev2Psk(server: String, identity: String, psk: String) =
        manager.saveIkev2PskProfile(server, identity, psk)
    fun saveIkev2Certificate(server: String, identity: String, bytes: ByteArray, password: String) =
        manager.saveIkev2CertificateProfile(server, identity, bytes, password)

    fun requestWireGuardConsent() = manager.wireGuardConsent()
    suspend fun requestIkev2Consent() = withContext(Dispatchers.IO) {
        // PKCS#12 parsing and Android profile provisioning may be expensive.
        manager.provisionIkev2()
    }
    // Keep VPN operations alive when the Settings dialog closes. An activity-owned VM
    // outlives individual dialog compositions, so consent/connect isn't cancelled mid-tunnel.
    fun connectWireGuard() { viewModelScope.launch { manager.connectWireGuard() } }
    fun connectIkev2() { viewModelScope.launch { manager.connectIkev2() } }
    fun disconnect() { viewModelScope.launch { manager.disconnect() } }
    fun refreshIkev2Status() = manager.refreshIkev2Status()
}
