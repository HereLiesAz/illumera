package com.hereliesaz.illumera.data.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests staging gates; these cases deliberately do NOT claim a native VPN tunnel exists. */
class VpnProtocolProfilesTest {
    private val safeOpenVpn = """
        client
        dev tun
        proto udp
        remote vpn.example.org 1194
        remote-cert-tls server
        <ca>
        MIICcertdummy
        </ca>
    """.trimIndent()

    @Test
    fun acceptsSafeOpenVpnTlsClientAndPreservesEndpoint() {
        val summary = inspectOpenVpnProfile(safeOpenVpn).getOrThrow()
        assertEquals(OpenVpnProfileSummary("vpn.example.org", 1194, "udp"), summary)
    }

    @Test
    fun rejectsOpenVpnScriptExecutionAndManagementDirectives() {
        for (directive in listOf(
            "script-security 2", "up /tmp/execute.sh", "down /tmp/execute.sh",
            "plugin /tmp/plugin.so", "management 127.0.0.1 7505",
            "route-up /tmp/execute.sh", "tls-verify /tmp/execute.sh"
        )) {
            assertTrue(directive, inspectOpenVpnProfile("$safeOpenVpn\n$directive").isFailure)
        }
    }

    @Test
    fun refusesOpenVpnWithoutTlsServerVerificationOrTunMode() {
        assertTrue(inspectOpenVpnProfile(safeOpenVpn.replace("remote-cert-tls server", "")).isFailure)
        assertTrue(inspectOpenVpnProfile(safeOpenVpn.replace("dev tun", "dev tap")).isFailure)
        assertTrue(inspectOpenVpnProfile(safeOpenVpn.replace("client", "server")).isFailure)
    }

    @Test
    fun refusesInvalidRemoteOrOversizedOpenVpnProfile() {
        assertTrue(inspectOpenVpnProfile(safeOpenVpn.replace("1194", "65536")).isFailure)
        assertTrue(inspectOpenVpnProfile(safeOpenVpn.replace("1194", "not-a-port")).isFailure)
        assertTrue(inspectOpenVpnProfile("$safeOpenVpn\nauth-user-pass /tmp/creds").isFailure)
        assertTrue(inspectOpenVpnProfile(safeOpenVpn.replace("vpn.example.org", "http://example.org")).isFailure)
        assertTrue(inspectOpenVpnProfile(safeOpenVpn + "\n" + "X".repeat(131072)).isFailure)
    }

    @Test
    fun validatesSoftEtherHostHubAndCredentials() {
        assertEquals(null, validateSoftEtherProfile("vpn.example.org", 443, "DEFAULT", "user", "secret"))
        assertEquals("SoftEther server port must be between 1 and 65535",
            validateSoftEtherProfile("vpn.example.org", 0, "DEFAULT", "user", "secret"))
        assertEquals("Enter a valid SoftEther virtual hub name",
            validateSoftEtherProfile("vpn.example.org", 443, "bad/hub", "user", "secret"))
        assertEquals("SoftEther username and password are required",
            validateSoftEtherProfile("vpn.example.org", 443, "DEFAULT", "", "secret"))
        assertEquals("Enter a SoftEther server hostname or IP address",
            validateSoftEtherProfile("https://vpn.example.org", 443, "DEFAULT", "user", "secret"))
    }

    @Test
    fun softEtherPinMustBeOutOfBandSha256NotArbitraryTlsFingerprint() {
        val pin = "0123456789abcdef".repeat(4)
        assertEquals(pin, normalizedSoftEtherCertPin(pin))
        assertEquals(pin, normalizedSoftEtherCertPin(
            pin.chunked(2).joinToString(":").uppercase()
        ))
        assertEquals(null, normalizedSoftEtherCertPin(""))
        assertEquals(null, normalizedSoftEtherCertPin("A".repeat(63)))
        assertEquals(null, normalizedSoftEtherCertPin("G".repeat(64)))
        assertEquals(null, normalizedSoftEtherCertPin("0".repeat(65)))
    }

    @Test
    fun protocolReadinessRequiresRealNativeEngine() {
        assertTrue(NativeVpnProtocol.OPENVPN.ready)
        assertFalse(NativeVpnProtocol.SOFTETHER.ready)
        assertTrue(NativeVpnProtocol.WIREGUARD.ready)
        assertTrue(NativeVpnProtocol.IKEV2.ready)
    }
}
