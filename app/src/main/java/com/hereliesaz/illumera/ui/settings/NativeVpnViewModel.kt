package com.hereliesaz.illumera.ui.settings

import androidx.lifecycle.ViewModel
import com.hereliesaz.illumera.data.vpn.NativeVpnManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI boundary for explicit user-initiated native VPN operations. */
@HiltViewModel
class NativeVpnViewModel @Inject constructor(private val manager: NativeVpnManager) : ViewModel() {
    val status = manager.status

    fun hasWireGuardProfile(): Boolean = manager.hasWireGuardProfile()
    fun hasIkev2Profile(): Boolean = manager.hasIkev2Profile()
    fun saveWireGuard(text: String) = manager.saveWireGuardProfile(text)
    fun saveIkev2(server: String, identity: String, username: String, password: String) =
        manager.saveIkev2Profile(server, identity, username, password)

    fun requestWireGuardConsent() = manager.wireGuardConsent()
    fun requestIkev2Consent() = manager.provisionIkev2()
    suspend fun connectWireGuard() = manager.connectWireGuard()
    suspend fun connectIkev2() = manager.connectIkev2()
    suspend fun disconnect() = manager.disconnect()
    fun refreshIkev2Status() = manager.refreshIkev2Status()
}
