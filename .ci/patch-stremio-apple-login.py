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
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay",
    "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay",
    "service cancellation import",
)
replace_once(
    service,
    '''data class StremioRegisterRequest(\n    val type: String = "Register",\n    val email: String,\n    val password: String,\n    @SerializedName("gdpr_consent") val gdprConsent: StremioGdprConsent\n)\n''',
    '''data class StremioRegisterRequest(\n    val type: String = "Register",\n    val email: String,\n    val password: String,\n    @SerializedName("gdpr_consent") val gdprConsent: StremioGdprConsent\n)\n\ndata class StremioAppleCredentials(\n    val token: String,\n    val sub: String,\n    val email: String,\n    val name: String = ""\n)\n\ndata class StremioAppleAuthRequest(\n    val type: String = "Apple",\n    val token: String,\n    val sub: String,\n    val email: String,\n    val name: String\n)\n''',
    "apple request models",
)
replace_once(
    service,
    '''data class StremioUser(\n    @SerializedName("_id") val id: String? = null,\n    val avatar: String? = null\n)''',
    '''data class StremioUser(\n    @SerializedName("_id") val id: String? = null,\n    val email: String? = null,\n    val avatar: String? = null\n)''',
    "user email field",
)
replace_once(
    service,
    '''        private const val REGISTER_ENDPOINT = "$STREMIO_API_BASE/register"\n        private const val LOGOUT_ENDPOINT = "$STREMIO_API_BASE/logout"''',
    '''        private const val REGISTER_ENDPOINT = "$STREMIO_API_BASE/register"\n        private const val APPLE_AUTH_ENDPOINT = "$STREMIO_API_BASE/authWithApple"\n        private const val LOGOUT_ENDPOINT = "$STREMIO_API_BASE/logout"''',
    "apple auth endpoint",
)
replace_once(
    service,
    '''        private const val FB_LOGIN_BASE = "https://www.strem.io/login-fb"\n        private const val FB_LOGIN_POLL_BASE = "https://www.strem.io/login-fb-get-acc"''',
    '''        private const val FB_LOGIN_BASE = "https://www.strem.io/login-fb"\n        private const val FB_LOGIN_POLL_BASE = "https://www.strem.io/login-fb-get-acc"\n        private const val APPLE_LOGIN_BASE = "https://www.strem.io/login-apple"\n        private const val APPLE_LOGIN_POLL_BASE = "https://www.strem.io/login-apple-get-acc"''',
    "apple handoff endpoints",
)
replace_once(
    service,
    '''            } catch (_: Exception) {\n                // Not ready yet / transient — keep polling until maxAttempts.\n            }\n        }\n        null\n    }\n\n    /**\n     * Invalidates the authKey server-side.''',
    '''            } catch (e: CancellationException) {\n                throw e\n            } catch (_: Exception) {\n                // Not ready yet / transient — keep polling until maxAttempts.\n            }\n        }\n        null\n    }\n\n    /** Starts Stremio's Apple browser handoff and returns state + login URL. */\n    fun startAppleLogin(): Pair<String, String> {\n        val state = java.util.UUID.randomUUID().toString().replace("-", "") +\n            java.util.UUID.randomUUID().toString().replace("-", "")\n        return state to "$APPLE_LOGIN_BASE/$state"\n    }\n\n    /**\n     * Polls Stremio's Apple handoff using the same 2-second/25-attempt cadence\n     * as the current Stremio Web client.\n     */\n    suspend fun pollAppleLogin(\n        state: String,\n        maxAttempts: Int = 25\n    ): StremioAppleCredentials? = withContext(Dispatchers.IO) {\n        repeat(maxAttempts) {\n            delay(2000)\n            try {\n                val request = Request.Builder().url("$APPLE_LOGIN_POLL_BASE/$state").get().build()\n                client.newCall(request).execute().use { response ->\n                    val body = response.body?.string()\n                    if (response.isSuccessful && body != null) {\n                        val user = JsonParser.parseString(body).asJsonObject.getAsJsonObject("user")\n                        val token = user?.get("token")?.takeIf { !it.isJsonNull }?.asString\n                        val sub = user?.get("sub")?.takeIf { !it.isJsonNull }?.asString\n                        val email = user?.get("email")?.takeIf { !it.isJsonNull }?.asString\n                        val name = user?.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()\n                        if (!token.isNullOrBlank() && !sub.isNullOrBlank() && !email.isNullOrBlank()) {\n                            return@withContext StremioAppleCredentials(token, sub, email, name)\n                        }\n                    }\n                }\n            } catch (e: CancellationException) {\n                throw e\n            } catch (_: Exception) {\n                // OAuth is not finished yet / transient response.\n            }\n        }\n        null\n    }\n\n    /** Exchanges Stremio's Apple handoff credentials for a normal auth key. */\n    suspend fun loginWithApple(credentials: StremioAppleCredentials): StremioLoginResult = withContext(Dispatchers.IO) {\n        val requestBody = gson.toJson(\n            StremioAppleAuthRequest(\n                token = credentials.token,\n                sub = credentials.sub,\n                email = credentials.email,\n                name = credentials.name\n            )\n        )\n        val request = Request.Builder()\n            .url(APPLE_AUTH_ENDPOINT)\n            .post(requestBody.toRequestBody(jsonMediaType))\n            .header("Content-Type", "application/json")\n            .build()\n\n        try {\n            client.newCall(request).execute().use { response ->\n                val responseBody = response.body?.string()\n                    ?: throw StremioAuthError.NetworkError("Server returned ${response.code}")\n                val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()\n                val apiError = json?.getAsJsonObject("error")\n                if (apiError != null) {\n                    throw StremioAuthError.UnknownError(\n                        apiError.get("message")?.asString?.takeIf { it.isNotBlank() }\n                            ?: "Apple login failed"\n                    )\n                }\n                if (!response.isSuccessful) {\n                    throw StremioAuthError.NetworkError("Server returned ${response.code}")\n                }\n                val authResponse = gson.fromJson(responseBody, StremioLoginResponse::class.java)\n                val result = authResponse.result\n                    ?: throw StremioAuthError.UnknownError("Apple login failed")\n                StremioLoginResult(\n                    authKey = result.authKey,\n                    avatarUrl = result.user?.avatar?.takeIf { it.isNotBlank() }\n                )\n            }\n        } catch (e: CancellationException) {\n            throw e\n        } catch (e: StremioAuthError) {\n            throw e\n        } catch (e: Exception) {\n            throw StremioAuthError.NetworkError(e.message ?: "Network error")\n        }\n    }\n\n    /**\n     * Invalidates the authKey server-side.''',
    "apple service flow and cancellable facebook polling",
)

manager = Path("app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt")
replace_once(
    manager,
    "import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers",
    "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers",
    "manager cancellation import",
)
replace_once(
    manager,
    '''        } catch (e: StremioAuthError) {\n            Result.failure(e)\n        } catch (e: Exception) {\n            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))\n        }\n    }\n\n    /**\n     * Pushes the given addons up to the account's addon collection,''',
    '''        } catch (e: CancellationException) {\n            throw e\n        } catch (e: StremioAuthError) {\n            Result.failure(e)\n        } catch (e: Exception) {\n            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))\n        }\n    }\n\n    /** Starts Stremio's browser-based Sign in with Apple flow. */\n    fun startAppleLogin(): Pair<String, String> = stremioAuthService.startAppleLogin()\n\n    /** Completes Apple OAuth and stores the resulting Stremio credentials. */\n    suspend fun completeAppleLogin(state: String): Result<String> = withContext(Dispatchers.IO) {\n        try {\n            val credentials = stremioAuthService.pollAppleLogin(state)\n                ?: return@withContext Result.failure(\n                    StremioAuthError.NetworkError("Apple login timed out or was not completed")\n                )\n            val loginResult = stremioAuthService.loginWithApple(credentials)\n            val accountLabel = credentials.email.ifBlank { "Apple account" }\n\n            encryptedPrefs.edit().apply {\n                putString(KEY_AUTH_KEY, loginResult.authKey)\n                putString(KEY_EMAIL, accountLabel)\n                if (loginResult.avatarUrl != null) putString(KEY_AVATAR, loginResult.avatarUrl) else remove(KEY_AVATAR)\n            }.apply()\n\n            _connectionState.value = StremioConnectionState.Connected(accountLabel)\n            Result.success(loginResult.authKey)\n        } catch (e: CancellationException) {\n            throw e\n        } catch (e: StremioAuthError) {\n            Result.failure(e)\n        } catch (e: Exception) {\n            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))\n        }\n    }\n\n    /**\n     * Pushes the given addons up to the account's addon collection,''',
    "manager apple flow",
)

vm = Path("app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsViewModel.kt")
replace_once(
    vm,
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.NonCancellable",
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.NonCancellable",
    "viewmodel Job import",
)
replace_once(
    vm,
    '''sealed class FacebookLoginState {\n    object Idle : FacebookLoginState()\n    data class WaitingForUser(val url: String) : FacebookLoginState()\n    object Success : FacebookLoginState()\n    data class Error(val message: String) : FacebookLoginState()\n}\n''',
    '''sealed class FacebookLoginState {\n    object Idle : FacebookLoginState()\n    data class WaitingForUser(val url: String) : FacebookLoginState()\n    object Success : FacebookLoginState()\n    data class Error(val message: String) : FacebookLoginState()\n}\n\nsealed class AppleLoginState {\n    object Idle : AppleLoginState()\n    data class WaitingForUser(val url: String) : AppleLoginState()\n    object Success : AppleLoginState()\n    data class Error(val message: String) : AppleLoginState()\n}\n''',
    "apple state",
)
replace_once(
    vm,
    '''    val pendingAddons: List<StremioAddonItem>? = null,\n    val facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,\n    val tmdbEnabled: Boolean = false,''',
    '''    val pendingAddons: List<StremioAddonItem>? = null,\n    val facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,\n    val appleLoginState: AppleLoginState = AppleLoginState.Idle,\n    val tmdbEnabled: Boolean = false,''',
    "apple ui state",
)
replace_once(
    vm,
    '''    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)\n    val events = _events.receiveAsFlow()\n''',
    '''    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)\n    val events = _events.receiveAsFlow()\n\n    private var facebookLoginJob: Job? = null\n    private var appleLoginJob: Job? = null\n''',
    "social jobs",
)
replace_once(
    vm,
    '''    fun startFacebookLogin() {\n        val (state, url) = stremioAuthManager.startFacebookLogin()\n        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.WaitingForUser(url))\n\n        viewModelScope.launch {\n            val result = stremioAuthManager.completeFacebookLogin(state)''',
    '''    fun startFacebookLogin() {\n        facebookLoginJob?.cancel()\n        val (state, url) = stremioAuthManager.startFacebookLogin()\n        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.WaitingForUser(url))\n\n        facebookLoginJob = viewModelScope.launch {\n            val result = stremioAuthManager.completeFacebookLogin(state)''',
    "cancellable facebook job",
)
replace_once(
    vm,
    '''    fun resetFacebookLoginState() {\n        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Idle)\n    }\n\n    /**\n     * Starts the user-visible foreground refresh''',
    '''    fun resetFacebookLoginState() {\n        facebookLoginJob?.cancel()\n        facebookLoginJob = null\n        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Idle)\n    }\n\n    fun startAppleLogin() {\n        appleLoginJob?.cancel()\n        val (state, url) = stremioAuthManager.startAppleLogin()\n        _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.WaitingForUser(url))\n\n        appleLoginJob = viewModelScope.launch {\n            val result = stremioAuthManager.completeAppleLogin(state)\n            result.fold(\n                onSuccess = {\n                    _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Success)\n                    applyStremioAvatarToProfile()\n                    _events.send(IntegrationsEvent.LoginSuccess)\n                    syncAddons()\n                    syncLibrary()\n                },\n                onFailure = { error ->\n                    val message = error.message ?: "Apple login failed"\n                    _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Error(message))\n                }\n            )\n        }\n    }\n\n    fun resetAppleLoginState() {\n        appleLoginJob?.cancel()\n        appleLoginJob = null\n        _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Idle)\n    }\n\n    /**\n     * Starts the user-visible foreground refresh''',
    "apple viewmodel flow",
)

screen = Path("app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt")
replace_once(
    screen,
    '''            isLoading = state.isLoading,\n            facebookLoginState = state.facebookLoginState,\n            onDismiss = {\n                showConnectDialog = false\n                viewModel.resetFacebookLoginState()\n            },''',
    '''            isLoading = state.isLoading,\n            facebookLoginState = state.facebookLoginState,\n            appleLoginState = state.appleLoginState,\n            onDismiss = {\n                showConnectDialog = false\n                viewModel.resetFacebookLoginState()\n                viewModel.resetAppleLoginState()\n            },''',
    "screen apple state",
)
replace_once(
    screen,
    '''            onLoginWithFacebook = { viewModel.startFacebookLogin() },\n            deviceFormFactor = deviceFormFactor''',
    '''            onLoginWithFacebook = { viewModel.startFacebookLogin() },\n            onLoginWithApple = { viewModel.startAppleLogin() },\n            deviceFormFactor = deviceFormFactor''',
    "screen apple callback",
)
replace_once(
    screen,
    '''    isLoading: Boolean,\n    facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,\n    onDismiss: () -> Unit,''',
    '''    isLoading: Boolean,\n    facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,\n    appleLoginState: AppleLoginState = AppleLoginState.Idle,\n    onDismiss: () -> Unit,''',
    "connect signature apple state",
)
replace_once(
    screen,
    '''    onRegister: (email: String, password: String, marketing: Boolean) -> Unit,\n    onLoginWithFacebook: () -> Unit = {},\n    deviceFormFactor: DeviceFormFactor''',
    '''    onRegister: (email: String, password: String, marketing: Boolean) -> Unit,\n    onLoginWithFacebook: () -> Unit = {},\n    onLoginWithApple: () -> Unit = {},\n    deviceFormFactor: DeviceFormFactor''',
    "connect signature apple callback",
)
replace_once(
    screen,
    '''    if (facebookLoginState is FacebookLoginState.WaitingForUser || facebookLoginState is FacebookLoginState.Error) {\n        FacebookLoginDialog(\n            state = facebookLoginState,\n            onDismiss = onDismiss,\n            onRetry = onLoginWithFacebook,\n            deviceFormFactor = deviceFormFactor\n        )\n        return\n    }\n\n    var email''',
    '''    if (facebookLoginState is FacebookLoginState.WaitingForUser || facebookLoginState is FacebookLoginState.Error) {\n        FacebookLoginDialog(\n            state = facebookLoginState,\n            onDismiss = onDismiss,\n            onRetry = onLoginWithFacebook,\n            deviceFormFactor = deviceFormFactor\n        )\n        return\n    }\n    if (appleLoginState is AppleLoginState.WaitingForUser || appleLoginState is AppleLoginState.Error) {\n        AppleLoginDialog(\n            state = appleLoginState,\n            onDismiss = onDismiss,\n            onRetry = onLoginWithApple,\n            deviceFormFactor = deviceFormFactor\n        )\n        return\n    }\n\n    var email''',
    "connect apple subflow",
)
replace_once(
    screen,
    '''                            IntegrationButton(\n                                text = "Login with Facebook",\n                                onClick = onLoginWithFacebook,\n                                enabled = !isLoading,\n                                isPrimary = false,\n                                modifier = Modifier.width(180.dp)\n                            )\n                        }''',
    '''                            IntegrationButton(\n                                text = "Login with Facebook",\n                                onClick = onLoginWithFacebook,\n                                enabled = !isLoading,\n                                isPrimary = false,\n                                modifier = Modifier.width(180.dp)\n                            )\n                            IntegrationButton(\n                                text = "Sign in with Apple",\n                                onClick = onLoginWithApple,\n                                enabled = !isLoading,\n                                isPrimary = false,\n                                modifier = Modifier.width(170.dp)\n                            )\n                        }''',
    "apple login button",
)
replace_once(
    screen,
    '''                                val url = "https://www.strem.io/reset-password/$cleanEmail"''',
    '''                                val url = "https://www.strem.io/reset-password/${android.net.Uri.encode(cleanEmail)}"''',
    "encode reset email",
)
replace_once(
    screen,
    '''// =============================================================================\n// FACEBOOK LOGIN DIALOG (QR handoff to Stremio's own OAuth page)\n// =============================================================================\n''',
    '''// =============================================================================\n// APPLE LOGIN DIALOG (QR/browser handoff to Stremio's hosted Apple OAuth page)\n// =============================================================================\n\n@Composable\nprivate fun AppleLoginDialog(\n    state: AppleLoginState,\n    onDismiss: () -> Unit,\n    onRetry: () -> Unit,\n    deviceFormFactor: DeviceFormFactor\n) {\n    val context = LocalContext.current\n    val url = (state as? AppleLoginState.WaitingForUser)?.url\n    val qrBitmap by produceState<Bitmap?>(initialValue = null, url, deviceFormFactor) {\n        value = if (deviceFormFactor == DeviceFormFactor.TV) url?.let { generateQrCodeBitmap(it) } else null\n    }\n\n    LaunchedEffect(url, deviceFormFactor) {\n        if (deviceFormFactor != DeviceFormFactor.TV && !url.isNullOrBlank()) openExternalUrl(context, url)\n    }\n\n    Dialog(onDismissRequest = onDismiss) {\n        Box(\n            modifier = Modifier\n                .width(rememberDialogWidth(420))\n                .clip(RoundedCornerShape(16.dp))\n                .background(MaterialTheme.colorScheme.background)\n                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))\n                .padding(32.dp)\n        ) {\n            Column(horizontalAlignment = Alignment.CenterHorizontally) {\n                Text(\n                    "Sign in with Apple",\n                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),\n                    color = Color.White\n                )\n                Spacer(Modifier.height(8.dp))\n\n                when (state) {\n                    is AppleLoginState.WaitingForUser -> {\n                        if (deviceFormFactor == DeviceFormFactor.TV) {\n                            Text(\n                                "Scan this code with your phone and finish Sign in with Apple. Illumera connects automatically when Stremio completes the handoff.",\n                                style = MaterialTheme.typography.bodyMedium,\n                                color = Color.Gray,\n                                textAlign = TextAlign.Center\n                            )\n                            Spacer(Modifier.height(20.dp))\n                            if (qrBitmap != null) {\n                                Box(\n                                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(8.dp)\n                                ) {\n                                    Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "Apple login QR code", modifier = Modifier.fillMaxSize())\n                                }\n                            }\n                        } else {\n                            Text(\n                                "Finish Sign in with Apple in your browser, then return here. Illumera will detect the completed login automatically.",\n                                style = MaterialTheme.typography.bodyMedium,\n                                color = Color.Gray,\n                                textAlign = TextAlign.Center\n                            )\n                            Spacer(Modifier.height(20.dp))\n                            IntegrationButton(\n                                text = "Open Browser",\n                                onClick = { url?.let { openExternalUrl(context, it) } },\n                                isPrimary = true,\n                                modifier = Modifier.fillMaxWidth()\n                            )\n                        }\n                        Spacer(Modifier.height(16.dp))\n                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)\n                        Spacer(Modifier.height(8.dp))\n                        Text("Waiting for Apple login…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)\n                    }\n                    is AppleLoginState.Error -> {\n                        Text(\n                            state.message,\n                            style = MaterialTheme.typography.bodyMedium,\n                            color = Color(0xFFEF4444),\n                            textAlign = TextAlign.Center\n                        )\n                        Spacer(Modifier.height(20.dp))\n                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {\n                            IntegrationButton(text = "Retry", onClick = onRetry, isPrimary = true, modifier = Modifier.width(120.dp))\n                            IntegrationButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.width(120.dp))\n                        }\n                    }\n                    else -> Unit\n                }\n            }\n        }\n    }\n}\n\n// =============================================================================\n// FACEBOOK LOGIN DIALOG (QR handoff to Stremio's own OAuth page)\n// =============================================================================\n''',
    "apple login dialog",
)

print("Stremio Apple login parity patch applied")
