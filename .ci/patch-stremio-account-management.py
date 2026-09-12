from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1))


service = Path("app/src/main/java/com/hereliesaz/illumera/data/remote/StremioAuthService.kt")
replace_once(
    service,
    '''data class StremioUser(\n    @SerializedName("_id") val id: String? = null,\n    val email: String? = null,\n    val avatar: String? = null\n)\n\n/** Result of a successful [StremioAuthService.login] call. */''',
    '''data class StremioUser(\n    @SerializedName("_id") val id: String? = null,\n    val email: String? = null,\n    val avatar: String? = null\n)\n\ndata class StremioDataExportResult(\n    @SerializedName("exportId") val exportId: String\n)\n\n/** Result of a successful [StremioAuthService.login] call. */''',
    "data export model",
)
replace_once(
    service,
    '''        private const val APPLE_AUTH_ENDPOINT = "$STREMIO_API_BASE/authWithApple"\n        private const val LOGOUT_ENDPOINT = "$STREMIO_API_BASE/logout"''',
    '''        private const val APPLE_AUTH_ENDPOINT = "$STREMIO_API_BASE/authWithApple"\n        private const val GET_USER_ENDPOINT = "$STREMIO_API_BASE/getUser"\n        private const val DATA_EXPORT_ENDPOINT = "$STREMIO_API_BASE/dataExport"\n        private const val LOGOUT_ENDPOINT = "$STREMIO_API_BASE/logout"''',
    "account management endpoints",
)
replace_once(
    service,
    '''    /**\n     * Fetches the user's addon collection using their authKey.\n     */\n    suspend fun getAddonCollection''',
    '''    /** Fetches the connected Stremio account record. */\n    suspend fun getUser(authKey: String): StremioUser = withContext(Dispatchers.IO) {\n        try {\n            val json = postJson(\n                GET_USER_ENDPOINT,\n                gson.toJson(mapOf("type" to "GetUser", "authKey" to authKey))\n            )\n            json.getAsJsonObject("error")?.let { apiError ->\n                throw StremioAuthError.UnknownError(\n                    apiError.get("message")?.asString?.takeIf { it.isNotBlank() }\n                        ?: "Could not load Stremio account"\n                )\n            }\n            val result = json.getAsJsonObject("result")\n                ?: throw StremioAuthError.UnknownError("Could not load Stremio account")\n            gson.fromJson(result, StremioUser::class.java)\n        } catch (e: StremioAuthError) {\n            throw e\n        } catch (e: Exception) {\n            throw StremioAuthError.NetworkError(e.message ?: "Network error")\n        }\n    }\n\n    /**\n     * Requests the same user-data export Stremio Web exposes and returns its\n     * download URL. The generated export id is encoded as a single path segment.\n     */\n    suspend fun requestDataExport(authKey: String): String = withContext(Dispatchers.IO) {\n        try {\n            val json = postJson(\n                DATA_EXPORT_ENDPOINT,\n                gson.toJson(mapOf("type" to "DataExport", "authKey" to authKey))\n            )\n            json.getAsJsonObject("error")?.let { apiError ->\n                throw StremioAuthError.UnknownError(\n                    apiError.get("message")?.asString?.takeIf { it.isNotBlank() }\n                        ?: "Could not export Stremio data"\n                )\n            }\n            val result = json.getAsJsonObject("result")\n                ?: throw StremioAuthError.UnknownError("Could not export Stremio data")\n            val export = gson.fromJson(result, StremioDataExportResult::class.java)\n            if (export.exportId.isBlank()) {\n                throw StremioAuthError.UnknownError("Stremio returned an empty export id")\n            }\n            "https://api.strem.io/data-export/${android.net.Uri.encode(export.exportId)}/export.json"\n        } catch (e: StremioAuthError) {\n            throw e\n        } catch (e: Exception) {\n            throw StremioAuthError.NetworkError(e.message ?: "Network error")\n        }\n    }\n\n    /**\n     * Fetches the user's addon collection using their authKey.\n     */\n    suspend fun getAddonCollection''',
    "account management service methods",
)

manager = Path("app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt")
replace_once(
    manager,
    '''    fun getStoredAvatarUrl(): String? {\n        return encryptedPrefs.getString(KEY_AVATAR, null)\n    }\n\n    private fun profileScopedAuthKey''',
    '''    fun getStoredAvatarUrl(): String? {\n        return encryptedPrefs.getString(KEY_AVATAR, null)\n    }\n\n    suspend fun getDataExportUrl(): Result<String> = withContext(Dispatchers.IO) {\n        val authKey = getStoredAuthKey()\n            ?: return@withContext Result.failure(StremioAuthError.UnknownError("Not connected to Stremio"))\n        runCatching { stremioAuthService.requestDataExport(authKey) }\n    }\n\n    suspend fun getCalendarUrl(): Result<String> = withContext(Dispatchers.IO) {\n        val authKey = getStoredAuthKey()\n            ?: return@withContext Result.failure(StremioAuthError.UnknownError("Not connected to Stremio"))\n        runCatching {\n            val user = stremioAuthService.getUser(authKey)\n            val userId = user.id?.takeIf { it.isNotBlank() }\n                ?: throw StremioAuthError.UnknownError("Stremio account id is unavailable")\n            "https://www.strem.io/calendar/${android.net.Uri.encode(userId)}.ics"\n        }\n    }\n\n    private fun profileScopedAuthKey''',
    "manager external account links",
)

vm = Path("app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsViewModel.kt")
replace_once(
    vm,
    '''    data class DebridConnected(val provider: DebridProvider) : IntegrationsEvent()\n    data class DebridError(val message: String) : IntegrationsEvent()\n}''',
    '''    data class DebridConnected(val provider: DebridProvider) : IntegrationsEvent()\n    data class DebridError(val message: String) : IntegrationsEvent()\n    data class ExternalUrlReady(val title: String, val url: String) : IntegrationsEvent()\n}''',
    "external url event",
)
replace_once(
    vm,
    '''    fun resetAppleLoginState() {\n        appleLoginJob?.cancel()\n        appleLoginJob = null\n        _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Idle)\n    }\n\n    /**\n     * Starts the user-visible foreground refresh''',
    '''    fun resetAppleLoginState() {\n        appleLoginJob?.cancel()\n        appleLoginJob = null\n        _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Idle)\n    }\n\n    fun exportStremioData() {\n        viewModelScope.launch {\n            _uiState.value = _uiState.value.copy(isLoading = true)\n            stremioAuthManager.getDataExportUrl().fold(\n                onSuccess = { url ->\n                    _uiState.value = _uiState.value.copy(isLoading = false)\n                    _events.send(IntegrationsEvent.ExternalUrlReady("Stremio Data Export", url))\n                },\n                onFailure = { error ->\n                    _uiState.value = _uiState.value.copy(isLoading = false)\n                    _events.send(IntegrationsEvent.LoginError(error.message ?: "Could not export Stremio data"))\n                }\n            )\n        }\n    }\n\n    fun openStremioCalendar() {\n        viewModelScope.launch {\n            _uiState.value = _uiState.value.copy(isLoading = true)\n            stremioAuthManager.getCalendarUrl().fold(\n                onSuccess = { url ->\n                    _uiState.value = _uiState.value.copy(isLoading = false)\n                    _events.send(IntegrationsEvent.ExternalUrlReady("Stremio Calendar", url))\n                },\n                onFailure = { error ->\n                    _uiState.value = _uiState.value.copy(isLoading = false)\n                    _events.send(IntegrationsEvent.LoginError(error.message ?: "Could not open Stremio calendar"))\n                }\n            )\n        }\n    }\n\n    /**\n     * Starts the user-visible foreground refresh''',
    "account management viewmodel actions",
)

screen = Path("app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt")
replace_once(
    screen,
    '''import androidx.compose.material.icons.filled.Sync\nimport androidx.compose.material.icons.filled.Logout''',
    '''import androidx.compose.material.icons.filled.Sync\nimport androidx.compose.material.icons.filled.Logout\nimport androidx.compose.material.icons.filled.Download\nimport androidx.compose.material.icons.filled.CalendarMonth\nimport androidx.compose.material.icons.filled.Key\nimport androidx.compose.material.icons.filled.DeleteForever''',
    "account action icons",
)
replace_once(
    screen,
    '''import kotlinx.coroutines.launch\n\n@Composable''',
    '''import kotlinx.coroutines.launch\n\ndata class ExternalLinkOperation(val title: String, val url: String)\n\n@Composable''',
    "external link operation model",
)
replace_once(
    screen,
    '''    var showConnectDialog by remember { mutableStateOf(false) }\n    var showManagementDialog by remember { mutableStateOf(false) }\n    var showDisconnectConfirm by remember { mutableStateOf(false) }''',
    '''    var showConnectDialog by remember { mutableStateOf(false) }\n    var showManagementDialog by remember { mutableStateOf(false) }\n    var showDisconnectConfirm by remember { mutableStateOf(false) }\n    var externalLink by remember { mutableStateOf<ExternalLinkOperation?>(null) }''',
    "external link screen state",
)
replace_once(
    screen,
    '''                is IntegrationsEvent.DebridError -> {\n                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()\n                }\n            }''',
    '''                is IntegrationsEvent.DebridError -> {\n                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()\n                }\n                is IntegrationsEvent.ExternalUrlReady -> {\n                    externalLink = ExternalLinkOperation(event.title, event.url)\n                }\n            }''',
    "external link event handling",
)
replace_once(
    screen,
    '''            onPushAddons = {\n                showManagementDialog = false\n                viewModel.pushAddonsToStremio()\n            },\n            onDisconnect = {''',
    '''            onPushAddons = {\n                showManagementDialog = false\n                viewModel.pushAddonsToStremio()\n            },\n            onExportData = {\n                showManagementDialog = false\n                viewModel.exportStremioData()\n            },\n            onSubscribeCalendar = {\n                showManagementDialog = false\n                viewModel.openStremioCalendar()\n            },\n            onChangePassword = {\n                showManagementDialog = false\n                externalLink = ExternalLinkOperation(\n                    "Change Stremio Password",\n                    "https://www.strem.io/reset-password/${android.net.Uri.encode(stremioEmail.orEmpty())}"\n                )\n            },\n            onDeleteAccount = {\n                showManagementDialog = false\n                externalLink = ExternalLinkOperation(\n                    "Delete Stremio Account",\n                    "https://stremio.zendesk.com/hc/en-us/articles/360021428911-How-to-delete-my-account"\n                )\n            },\n            onDisconnect = {''',
    "management callbacks",
)
replace_once(
    screen,
    '''    // Disconnect Confirmation\n    if (showDisconnectConfirm) {''',
    '''    externalLink?.let { link ->\n        StremioExternalLinkDialog(\n            title = link.title,\n            url = link.url,\n            deviceFormFactor = deviceFormFactor,\n            onDismiss = { externalLink = null }\n        )\n    }\n\n    // Disconnect Confirmation\n    if (showDisconnectConfirm) {''',
    "external link dialog invocation",
)
replace_once(
    screen,
    '''    onSyncLibrary: () -> Unit,\n    onPushAddons: () -> Unit,\n    onDisconnect: () -> Unit\n) {''',
    '''    onSyncLibrary: () -> Unit,\n    onPushAddons: () -> Unit,\n    onExportData: () -> Unit,\n    onSubscribeCalendar: () -> Unit,\n    onChangePassword: () -> Unit,\n    onDeleteAccount: () -> Unit,\n    onDisconnect: () -> Unit\n) {''',
    "management signature",
)
replace_once(
    screen,
    '''            Column {\n                Text(\n                    "Stremio Account",''',
    '''            Column(\n                modifier = Modifier\n                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)\n                    .verticalScroll(rememberScrollState())\n            ) {\n                Text(\n                    "Stremio Account",''',
    "management scrollability",
)
replace_once(
    screen,
    '''                // Disconnect\n                ManagementMenuItem(\n                    icon = Icons.Default.Logout,''',
    '''                ManagementMenuItem(\n                    icon = Icons.Default.Download,\n                    title = "Export Stremio Data",\n                    subtitle = "Request and download your Stremio account export",\n                    onClick = onExportData\n                )\n\n                Spacer(Modifier.height(12.dp))\n\n                ManagementMenuItem(\n                    icon = Icons.Default.CalendarMonth,\n                    title = "Subscribe to Calendar",\n                    subtitle = "Open your Stremio release calendar subscription",\n                    onClick = onSubscribeCalendar\n                )\n\n                Spacer(Modifier.height(12.dp))\n\n                ManagementMenuItem(\n                    icon = Icons.Default.Key,\n                    title = "Change Password",\n                    subtitle = "Open Stremio's password reset flow",\n                    onClick = onChangePassword\n                )\n\n                Spacer(Modifier.height(12.dp))\n\n                ManagementMenuItem(\n                    icon = Icons.Default.DeleteForever,\n                    title = "Delete Stremio Account",\n                    subtitle = "Open Stremio's official account deletion instructions",\n                    onClick = onDeleteAccount,\n                    isDestructive = true\n                )\n\n                Spacer(Modifier.height(12.dp))\n\n                // Disconnect\n                ManagementMenuItem(\n                    icon = Icons.Default.Logout,''',
    "management account actions",
)
replace_once(
    screen,
    '''// =============================================================================\n// DISCONNECT CONFIRMATION DIALOG\n// =============================================================================\n''',
    '''// =============================================================================\n// EXTERNAL STREMIO ACCOUNT LINK\n// =============================================================================\n\n@Composable\nprivate fun StremioExternalLinkDialog(\n    title: String,\n    url: String,\n    deviceFormFactor: DeviceFormFactor,\n    onDismiss: () -> Unit\n) {\n    val context = LocalContext.current\n    val qrBitmap by produceState<Bitmap?>(initialValue = null, url, deviceFormFactor) {\n        value = if (deviceFormFactor == DeviceFormFactor.TV) generateQrCodeBitmap(url) else null\n    }\n\n    LaunchedEffect(url, deviceFormFactor) {\n        if (deviceFormFactor != DeviceFormFactor.TV) openExternalUrl(context, url)\n    }\n\n    Dialog(onDismissRequest = onDismiss) {\n        Column(\n            modifier = Modifier\n                .width(rememberDialogWidth(440))\n                .clip(RoundedCornerShape(16.dp))\n                .background(MaterialTheme.colorScheme.background)\n                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))\n                .padding(28.dp),\n            horizontalAlignment = Alignment.CenterHorizontally\n        ) {\n            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)\n            Spacer(Modifier.height(10.dp))\n\n            if (deviceFormFactor == DeviceFormFactor.TV) {\n                Text(\n                    "Scan with your phone to continue on Stremio's website.",\n                    color = Color.Gray,\n                    style = MaterialTheme.typography.bodyMedium,\n                    textAlign = TextAlign.Center\n                )\n                Spacer(Modifier.height(20.dp))\n                if (qrBitmap != null) {\n                    Box(\n                        modifier = Modifier.size(190.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(8.dp)\n                    ) {\n                        Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "$title QR code", modifier = Modifier.fillMaxSize())\n                    }\n                } else {\n                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)\n                }\n                Spacer(Modifier.height(12.dp))\n                Text(url, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)\n            } else {\n                Text(\n                    "Opened in your browser.",\n                    color = Color.Gray,\n                    style = MaterialTheme.typography.bodyMedium,\n                    textAlign = TextAlign.Center\n                )\n                Spacer(Modifier.height(16.dp))\n                IntegrationButton(\n                    text = "Open Browser",\n                    onClick = { openExternalUrl(context, url) },\n                    isPrimary = true,\n                    modifier = Modifier.fillMaxWidth()\n                )\n            }\n\n            Spacer(Modifier.height(20.dp))\n            IntegrationButton(text = "Close", onClick = onDismiss, modifier = Modifier.width(130.dp))\n        }\n    }\n}\n\n// =============================================================================\n// DISCONNECT CONFIRMATION DIALOG\n// =============================================================================\n''',
    "external link dialog",
)

print("Stremio account management parity patch applied")
