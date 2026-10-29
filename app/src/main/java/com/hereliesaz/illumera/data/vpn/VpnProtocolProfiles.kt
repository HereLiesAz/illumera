package com.hereliesaz.illumera.data.vpn

/**
 * Static import inspection for protocol engines that are not yet bundled in Illumera.
 *
 * These checks help avoid accepting clearly dangerous legacy configuration, but are NOT
 * a substitute for the protocol engine's own parser and cryptographic validation. Never
 * label a staged profile "connected" before a real native packet engine runs.
 */
data class OpenVpnProfileSummary(
    val hostname: String,
    val port: Int,
    val transport: String
)

private val hostPattern = Regex("^[A-Za-z0-9_.:-]{1,253}$")
private val deniedOpenVpnOptions = setOf(
    "script-security", "up", "down", "route-up", "route-pre-down", "ipchange",
    "learn-address", "client-connect", "client-disconnect", "auth-user-pass-verify",
    "plugin", "management", "management-client", "management-query-passwords",
    "tls-verify", "setenv", "setenv-safe", "status", "log", "log-append"
)

/** Validates an import for staging, without executing directives or credentials. */
internal fun inspectOpenVpnProfile(text: String): Result<OpenVpnProfileSummary> = runCatching {
    require(text.toByteArray(Charsets.UTF_8).size in 1..131072) {
        "OpenVPN profile must be 1–128 KiB"
    }
    val lines = text.lineSequence().map(String::trim).filter { it.isNotBlank() }
        .filterNot { it.startsWith("#") || it.startsWith(";") }.toList()
    require(lines.size <= 4096) { "OpenVPN profile has too many directives" }
    val directives = lines.filterNot { it.startsWith("<") || it.startsWith("</") }
    val optionNames = directives.map { it.substringBefore(' ').substringBefore('\t').lowercase() }
    require(optionNames.none { it in deniedOpenVpnOptions }) {
        "OpenVPN profile contains unsupported executable or management directives"
    }
    require(directives.none {
        it.startsWith("auth-user-pass ", ignoreCase = true)
    }) { "External credential-file paths are not allowed in imported OpenVPN profiles" }
    require(lines.none { it.matches(Regex("^<(script|plugin|management)[^>]*>.*", RegexOption.IGNORE_CASE)) }) {
        "OpenVPN profile contains an executable block"
    }
    require(optionNames.contains("client") || optionNames.contains("tls-client")) {
        "A client TLS configuration is required"
    }
    require(optionNames.contains("remote-cert-tls") && directives.any {
        it.equals("remote-cert-tls server", ignoreCase = true)
    }) { "OpenVPN profiles must verify the remote server certificate" }
    require(directives.any { it.equals("dev tun", ignoreCase = true) }) {
        "Only OpenVPN layer-3 TUN profiles are supported"
    }
    val remoteLine = directives.firstOrNull {
        it.substringBefore(' ').equals("remote", ignoreCase = true)
    } ?: error("OpenVPN profile is missing a remote server")
    val remote = remoteLine.split(Regex("\\s+"))
    require(remote.size in 2..4) { "Malformed OpenVPN remote directive" }
    val host = remote[1]
    require(hostPattern.matches(host) && !host.contains("..") && !host.contains("://")) {
        "OpenVPN remote must be a hostname or IP address"
    }
    val port = when (val portArg = remote.getOrNull(2)) {
        null -> 1194
        else -> portArg.toIntOrNull() ?: error("Invalid OpenVPN remote port")
    }
    require(port in 1..65535) { "Invalid OpenVPN remote port" }
    val proto = directives.firstOrNull { it.startsWith("proto ", ignoreCase = true) }
        ?.substringAfter(" ")?.lowercase() ?: "udp"
    require(proto in setOf("udp", "udp4", "udp6", "tcp", "tcp-client", "tcp4-client", "tcp6-client")) {
        "Only OpenVPN client TCP/UDP transport is supported"
    }
    OpenVpnProfileSummary(host, port, proto)
}

/** SoftEther native SSL-VPN requires a hub name in addition to the TLS endpoint. */
internal fun validateSoftEtherProfile(
    host: String, port: Int, hub: String, username: String, password: String
): String? {
    if (!hostPattern.matches(host.trim()) || host.contains("..") || host.contains("://")) {
        return "Enter a SoftEther server hostname or IP address"
    }
    if (port !in 1..65535) return "SoftEther server port must be between 1 and 65535"
    if (hub.isBlank() || hub.length > 128 || hub.any { it == '/' || it == '\\' || it.isISOControl() }) {
        return "Enter a valid SoftEther virtual hub name"
    }
    if (username.isBlank() || password.isBlank()) return "SoftEther username and password are required"
    return null
}
