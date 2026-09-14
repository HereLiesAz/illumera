from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1))


def replace_between(path: str, start_marker: str, end_marker: str, replacement: str) -> None:
    file = ROOT / path
    text = file.read_text()
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"Start marker not found in {path}: {start_marker[:120]!r}")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"End marker not found in {path}: {end_marker[:120]!r}")
    file.write_text(text[:start] + replacement + text[end:])


# ---------------------------------------------------------------------------
# Profile onboarding UI
# ---------------------------------------------------------------------------
profile_screen = "app/src/main/java/com/hereliesaz/illumera/ui/profiles/ProfileScreen.kt"

replace_once(
    profile_screen,
    "package com.hereliesaz.illumera.ui.profiles\n\nimport androidx.activity.compose.BackHandler",
    "package com.hereliesaz.illumera.ui.profiles\n\nimport android.content.Intent\nimport android.graphics.Bitmap\nimport android.net.Uri\nimport androidx.activity.compose.BackHandler"
)
replace_once(
    profile_screen,
    "import androidx.compose.foundation.background\n",
    "import androidx.compose.foundation.Image\nimport androidx.compose.foundation.background\n"
)
replace_once(
    profile_screen,
    "import androidx.compose.foundation.horizontalScroll\n",
    "import androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.verticalScroll\n"
)
replace_once(
    profile_screen,
    "import androidx.compose.material3.Icon\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Text\n",
    "import androidx.compose.material3.Checkbox\nimport androidx.compose.material3.CircularProgressIndicator\nimport androidx.compose.material3.Icon\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Text\n"
)
replace_once(
    profile_screen,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.asImageBitmap\n"
)
replace_once(
    profile_screen,
    "import androidx.compose.ui.text.input.VisualTransformation\n",
    "import androidx.compose.ui.text.input.VisualTransformation\nimport androidx.compose.ui.text.style.TextAlign\n"
)
replace_once(
    profile_screen,
    "import com.hereliesaz.illumera.ui.util.rememberDialogWidth\nimport com.hereliesaz.illumera.ui.util.rememberIsTvDevice\n",
    "import com.hereliesaz.illumera.remote_input.IntegrationServerManager\nimport com.hereliesaz.illumera.remote_input.ServerInfo\nimport com.hereliesaz.illumera.ui.util.generateQrCodeBitmap\nimport com.hereliesaz.illumera.ui.util.rememberDialogWidth\nimport com.hereliesaz.illumera.ui.util.rememberIsTvDevice\n"
)
replace_once(profile_screen, '"WELCOME TO LUMERA"', '"WELCOME TO ILLUMERA"')
replace_once(
    profile_screen,
    "    val wizardStep by viewModel.wizardStep.collectAsState()\n    val isLoading by viewModel.isLoading.collectAsState()\n",
    "    val wizardStep by viewModel.wizardStep.collectAsState()\n    val isLoading by viewModel.isLoading.collectAsState()\n    val setupSocialLoginState by viewModel.setupSocialLoginState.collectAsState()\n"
)

setup_call = '''    if (showStremioConnectDialog && setupTarget != null) {
        StremioConnectDialog(
            profileName = setupTarget.name,
            isLoading = isInitializingProfile,
            socialLoginState = setupSocialLoginState,
            onSkip = {
                viewModel.resetSetupSocialLoginState()
                viewModel.initializeProfileFromScratch(setupTarget.id) {
                    showStremioConnectDialog = false
                    setupTargetProfile = null
                    onSelect(setupTarget)
                }
            },
            onConnect = { email, password, useAccountAvatar, onError ->
                viewModel.initializeProfileFromScratchWithStremio(
                    setupTarget.id,
                    email,
                    password,
                    useAccountAvatar
                ) { success, message ->
                    if (success) {
                        showStremioConnectDialog = false
                        setupTargetProfile = null
                        onSelect(setupTarget)
                    } else {
                        onError(message ?: "Couldn't connect to Stremio.")
                    }
                }
            },
            onFacebookLogin = { useAccountAvatar ->
                viewModel.initializeProfileFromScratchWithFacebook(
                    setupTarget.id,
                    useAccountAvatar
                ) { success, _ ->
                    if (success) {
                        showStremioConnectDialog = false
                        setupTargetProfile = null
                        onSelect(setupTarget)
                    }
                }
            },
            onAppleLogin = { useAccountAvatar ->
                viewModel.initializeProfileFromScratchWithApple(
                    setupTarget.id,
                    useAccountAvatar
                ) { success, _ ->
                    if (success) {
                        showStremioConnectDialog = false
                        setupTargetProfile = null
                        onSelect(setupTarget)
                    }
                }
            },
            onResetSocialLogin = { viewModel.resetSetupSocialLoginState() },
            onDismiss = {
                viewModel.resetSetupSocialLoginState()
                viewModel.initializeProfileFromScratch(setupTarget.id) {
                    showStremioConnectDialog = false
                    setupTargetProfile = null
                    onSelect(setupTarget)
                }
            }
        )
    }
}

'''
replace_between(
    profile_screen,
    "    if (showStremioConnectDialog && setupTarget != null) {",
    "@Composable\nprivate fun ProfileInitialSetupDialog(",
    setup_call
)

new_stremio_dialog = r'''@Composable
private fun StremioConnectDialog(
    profileName: String,
    isLoading: Boolean,
    socialLoginState: SetupSocialLoginState,
    onSkip: () -> Unit,
    onConnect: (email: String, password: String, useAccountAvatar: Boolean, onError: (String) -> Unit) -> Unit,
    onFacebookLogin: (useAccountAvatar: Boolean) -> Unit,
    onAppleLogin: (useAccountAvatar: Boolean) -> Unit,
    onResetSocialLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useAccountAvatar by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isTvDevice = rememberIsTvDevice()
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val serverManager = remember { IntegrationServerManager() }
    val scope = rememberCoroutineScope()
    val currentOnConnect by rememberUpdatedState(onConnect)
    val currentUseAccountAvatar by rememberUpdatedState(useAccountAvatar)

    LaunchedEffect(isTvDevice, socialLoginState) {
        if (isTvDevice && socialLoginState is SetupSocialLoginState.Idle) {
            val info = serverManager.startServer { receivedEmail, receivedPassword ->
                scope.launch {
                    currentOnConnect(
                        receivedEmail,
                        receivedPassword,
                        currentUseAccountAvatar
                    ) { errorMessage = it }
                }
            }
            if (info != null) {
                serverInfo = info
                qrBitmap = generateQrCodeBitmap(info.url)
            }
        } else {
            serverManager.stopServer()
            serverInfo = null
            qrBitmap = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { serverManager.stopServer() }
    }

    when (val socialState = socialLoginState) {
        is SetupSocialLoginState.WaitingForUser -> {
            SetupSocialLoginDialog(
                provider = socialState.provider,
                url = socialState.url,
                errorMessage = null,
                onRetry = {
                    when (socialState.provider) {
                        SetupSocialProvider.FACEBOOK -> onFacebookLogin(useAccountAvatar)
                        SetupSocialProvider.APPLE -> onAppleLogin(useAccountAvatar)
                    }
                },
                onBack = onResetSocialLogin
            )
            return
        }
        is SetupSocialLoginState.Error -> {
            SetupSocialLoginDialog(
                provider = socialState.provider,
                url = null,
                errorMessage = socialState.message,
                onRetry = {
                    when (socialState.provider) {
                        SetupSocialProvider.FACEBOOK -> onFacebookLogin(useAccountAvatar)
                        SetupSocialProvider.APPLE -> onAppleLogin(useAccountAvatar)
                    }
                },
                onBack = onResetSocialLogin
            )
            return
        }
        SetupSocialLoginState.Idle -> Unit
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(560))
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
                .border(2.dp, Color(0xFF333333), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Connect Stremio",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Log in to automatically pull \"$profileName\"'s addons and library from your Stremio account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))

                ProfileWizardTextField(
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    placeholder = "Email",
                    keyboardType = KeyboardType.Email,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                ProfileWizardTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    placeholder = "Password",
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    modifier = Modifier.fillMaxWidth(),
                    onDone = {
                        if (email.isNotBlank() && password.isNotBlank() && !isLoading) {
                            onConnect(email, password, useAccountAvatar) { errorMessage = it }
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isLoading) { useAccountAvatar = !useAccountAvatar }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useAccountAvatar,
                        onCheckedChange = { if (!isLoading) useAccountAvatar = it },
                        enabled = !isLoading
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Use my connected account avatar if available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Uses the Stremio avatar returned after email, Facebook, or Apple sign-in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF6B6B)
                    )
                }

                Spacer(Modifier.height(20.dp))
                VoidButton(
                    text = if (isLoading) "Connecting…" else "Connect with Email & Password",
                    onClick = { onConnect(email, password, useAccountAvatar) { errorMessage = it } },
                    enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                VoidButton(
                    text = "Continue with Facebook",
                    onClick = { onFacebookLogin(useAccountAvatar) },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                VoidButton(
                    text = "Sign in with Apple",
                    onClick = { onAppleLogin(useAccountAvatar) },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isTvDevice) {
                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = "Or enter your Stremio credentials on your phone",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    val info = serverInfo
                    val qr = qrBitmap
                    if (info != null && qr != null) {
                        Box(
                            modifier = Modifier
                                .size(150.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .padding(4.dp)
                        ) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "Open Stremio credential sign-in on phone",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Scan the QR code and enter your Stremio email and password on your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                VoidButton(
                    text = "Skip for now",
                    onClick = onSkip,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SetupSocialLoginDialog(
    provider: SetupSocialProvider,
    url: String?,
    errorMessage: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val isTvDevice = rememberIsTvDevice()
    val context = LocalContext.current
    val qrBitmap by produceState<Bitmap?>(initialValue = null, url, isTvDevice) {
        value = if (isTvDevice) url?.let { generateQrCodeBitmap(it) } else null
    }

    LaunchedEffect(url, isTvDevice) {
        if (!isTvDevice && !url.isNullOrBlank()) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    Dialog(onDismissRequest = onBack) {
        Column(
            modifier = Modifier
                .width(rememberDialogWidth(440))
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
                .border(2.dp, Color(0xFF333333), RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (provider == SetupSocialProvider.APPLE) "Sign in with Apple" else "Continue with Facebook",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(Modifier.height(12.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                VoidButton(
                    text = "Retry",
                    onClick = onRetry,
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                VoidButton(
                    text = "Back",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }

            if (isTvDevice) {
                Text(
                    text = "Scan this code with your phone and complete the ${provider.displayName} sign-in. illumera will continue automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .padding(6.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "${provider.displayName} sign-in QR code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(
                    text = "Finish the ${provider.displayName} sign-in in your browser, then return to illumera.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                if (!url.isNullOrBlank()) {
                    VoidButton(
                        text = "Open Browser",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        isPrimary = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Waiting for ${provider.displayName} sign-in…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(Modifier.height(16.dp))
            VoidButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

'''
replace_between(
    profile_screen,
    "@Composable\nprivate fun StremioConnectDialog(",
    "@Composable\nprivate fun ProfileWizardTextField(",
    new_stremio_dialog
)

# ---------------------------------------------------------------------------
# Profile setup behavior, including Facebook/Apple first-run authentication.
# ---------------------------------------------------------------------------
profile_vm = "app/src/main/java/com/hereliesaz/illumera/ui/profiles/ProfileViewModel.kt"
replace_once(
    profile_vm,
    "import com.hereliesaz.illumera.data.debrid.DebridManager\n",
    "import com.hereliesaz.illumera.data.auth.StremioAuthManager\nimport com.hereliesaz.illumera.data.debrid.DebridManager\n"
)
replace_once(
    profile_vm,
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.NonCancellable\n",
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.NonCancellable\n"
)
replace_once(
    profile_vm,
    "import kotlinx.coroutines.launch\n",
    "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withTimeoutOrNull\n"
)
replace_once(
    profile_vm,
    "@HiltViewModel\nclass ProfileViewModel",
    '''enum class SetupSocialProvider(val displayName: String) {
    FACEBOOK("Facebook"),
    APPLE("Apple")
}

sealed class SetupSocialLoginState {
    object Idle : SetupSocialLoginState()
    data class WaitingForUser(val provider: SetupSocialProvider, val url: String) : SetupSocialLoginState()
    data class Error(val provider: SetupSocialProvider, val message: String) : SetupSocialLoginState()
}

@HiltViewModel
class ProfileViewModel'''
)
replace_once(
    profile_vm,
    "    private val profileMutationCoordinator: ProfileMutationCoordinator,\n    private val debridManager: DebridManager,",
    "    private val profileMutationCoordinator: ProfileMutationCoordinator,\n    private val stremioAuthManager: StremioAuthManager,\n    private val debridManager: DebridManager,"
)
replace_once(
    profile_vm,
    "    private val _isInitializingProfile = MutableStateFlow(false)\n    val isInitializingProfile: StateFlow<Boolean> = _isInitializingProfile\n",
    "    private val _isInitializingProfile = MutableStateFlow(false)\n    val isInitializingProfile: StateFlow<Boolean> = _isInitializingProfile\n\n    private val _setupSocialLoginState = MutableStateFlow<SetupSocialLoginState>(SetupSocialLoginState.Idle)\n    val setupSocialLoginState: StateFlow<SetupSocialLoginState> = _setupSocialLoginState\n    private var setupSocialLoginJob: Job? = null\n"
)

new_profile_login_methods = r'''    fun initializeProfileFromScratchWithStremio(
        profileId: Int,
        email: String,
        password: String,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                val result = profileConfigurationManager.initializeFromScratchWithStremio(profileId, email, password)
                result.fold(
                    onSuccess = {
                        applyConnectedAccountAvatar(profileId, useAccountAvatar)
                        onResult(true, null)
                    },
                    onFailure = { error ->
                        onResult(false, setupErrorMessage(error))
                    }
                )
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    fun initializeProfileFromScratchWithFacebook(
        profileId: Int,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        startSetupSocialLogin(
            provider = SetupSocialProvider.FACEBOOK,
            profileId = profileId,
            useAccountAvatar = useAccountAvatar,
            onResult = onResult
        )
    }

    fun initializeProfileFromScratchWithApple(
        profileId: Int,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        startSetupSocialLogin(
            provider = SetupSocialProvider.APPLE,
            profileId = profileId,
            useAccountAvatar = useAccountAvatar,
            onResult = onResult
        )
    }

    private fun startSetupSocialLogin(
        provider: SetupSocialProvider,
        profileId: Int,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        setupSocialLoginJob?.cancel()

        val handoff = runCatching {
            when (provider) {
                SetupSocialProvider.FACEBOOK -> stremioAuthManager.startFacebookLogin()
                SetupSocialProvider.APPLE -> stremioAuthManager.startAppleLogin()
            }
        }.getOrElse { error ->
            val message = error.message ?: "Could not start ${provider.displayName} sign-in."
            _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
            onResult(false, message)
            return
        }

        val (state, url) = handoff
        _setupSocialLoginState.value = SetupSocialLoginState.WaitingForUser(provider, url)
        _isInitializingProfile.value = true

        setupSocialLoginJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val authResult = withTimeoutOrNull(5 * 60_000L) {
                    when (provider) {
                        SetupSocialProvider.FACEBOOK -> stremioAuthManager.completeFacebookLogin(state)
                        SetupSocialProvider.APPLE -> stremioAuthManager.completeAppleLogin(state)
                    }
                }

                if (authResult == null) {
                    val message = "${provider.displayName} sign-in timed out. Please try again."
                    _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
                    onResult(false, message)
                    return@launch
                }

                authResult.fold(
                    onSuccess = {
                        val setupResult = profileConfigurationManager.initializeFromScratchWithConnectedStremio(profileId)
                        setupResult.fold(
                            onSuccess = {
                                applyConnectedAccountAvatar(profileId, useAccountAvatar)
                                _setupSocialLoginState.value = SetupSocialLoginState.Idle
                                onResult(true, null)
                            },
                            onFailure = { error ->
                                val message = setupErrorMessage(error)
                                _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
                                onResult(false, message)
                            }
                        )
                    },
                    onFailure = { error ->
                        val message = setupErrorMessage(error)
                        _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
                        onResult(false, message)
                    }
                )
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    fun resetSetupSocialLoginState() {
        setupSocialLoginJob?.cancel()
        setupSocialLoginJob = null
        _setupSocialLoginState.value = SetupSocialLoginState.Idle
        _isInitializingProfile.value = false
    }

    private suspend fun applyConnectedAccountAvatar(profileId: Int, enabled: Boolean) {
        resolveSetupAccountAvatarRef(
            enabled = enabled,
            stremioAvatarUrl = stremioAuthManager.getStoredAvatarUrl()
        )?.let { avatarRef ->
            profileMutationCoordinator.update(profileId) { current ->
                current.copy(avatarRef = avatarRef)
            }
        }
    }

    private fun setupErrorMessage(error: Throwable): String = when (error) {
        is StremioAuthError.InvalidCredentials -> "Invalid email or password."
        is StremioAuthError.NetworkError -> "Network error. Check your connection and try again."
        else -> error.message ?: "Couldn't connect to Stremio."
    }

'''
replace_between(
    profile_vm,
    "    fun initializeProfileFromScratchWithStremio(",
    "    fun initializeProfileByCopy(",
    new_profile_login_methods
)

# ---------------------------------------------------------------------------
# Profile configuration: finalize setup after any already-authenticated login path.
# ---------------------------------------------------------------------------
profile_config = "app/src/main/java/com/hereliesaz/illumera/data/profile/ProfileConfigurationManager.kt"
new_profile_config_login = r'''    suspend fun initializeFromScratchWithStremio(profileId: Int, email: String, password: String): Result<Int> {
        val loginResult = stremioAuthManager.login(email, password)

        return loginResult.fold(
            onSuccess = { initializeFromScratchWithConnectedStremio(profileId) },
            onFailure = { error ->
                val defaultSnapshot = createDefaultRuntimeSnapshot()
                stremioAuthManager.clearCredentialsForProfile(profileId)
                writeSnapshot(profileId, defaultSnapshot)
                clearPendingSetup(profileId)
                Result.failure(error)
            }
        )
    }

    /**
     * Completes fresh-profile initialization when Stremio authentication was already
     * completed through a hosted flow such as Facebook or Apple.
     */
    suspend fun initializeFromScratchWithConnectedStremio(profileId: Int): Result<Int> {
        val defaultSnapshot = createDefaultRuntimeSnapshot()
        stremioAuthManager.saveCredentialsForProfile(profileId)
        val addonsResult = stremioAuthManager.fetchAddons()

        return addonsResult.fold(
            onSuccess = { entries ->
                val fetched = buildSnapshotFromStremioAddons(entries)
                writeSnapshot(
                    profileId,
                    defaultSnapshot.copy(
                        addons = defaultSnapshot.addons + fetched.addons,
                        catalogConfigs = defaultSnapshot.catalogConfigs + fetched.catalogConfigs
                    )
                )
                clearPendingSetup(profileId)
                Result.success(fetched.addons.size)
            },
            onFailure = { error ->
                stremioAuthManager.clearCredentialsForProfile(profileId)
                writeSnapshot(profileId, defaultSnapshot)
                clearPendingSetup(profileId)
                Result.failure(error)
            }
        )
    }

'''
replace_between(
    profile_config,
    "    suspend fun initializeFromScratchWithStremio(",
    "    /** Fetches the full manifest for each Stremio addon entry and builds installable snapshot rows. */",
    new_profile_config_login
)

# Pure policy helper makes account-avatar behavior unit-testable without Android UI.
policy_file = ROOT / "app/src/main/java/com/hereliesaz/illumera/ui/profiles/ProfileSetupPolicy.kt"
policy_file.write_text('''package com.hereliesaz.illumera.ui.profiles\n\nimport java.net.URI\n\ninternal fun resolveSetupAccountAvatarRef(\n    enabled: Boolean,\n    stremioAvatarUrl: String?\n): String? {\n    if (!enabled) return null\n    val url = stremioAvatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null\n    val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()\n    if (scheme != \"http\" && scheme != \"https\") return null\n    return ProfileAssets.urlAvatarRef(url)\n}\n''')

test_file = ROOT / "app/src/test/java/com/hereliesaz/illumera/ui/profiles/ProfileSetupPolicyTest.kt"
test_file.parent.mkdir(parents=True, exist_ok=True)
test_file.write_text('''package com.hereliesaz.illumera.ui.profiles\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\n\nclass ProfileSetupPolicyTest {\n    @Test\n    fun disabled_keeps_existing_avatar_choice() {\n        assertNull(resolveSetupAccountAvatarRef(false, \"https://example.com/avatar.jpg\"))\n    }\n\n    @Test\n    fun enabled_wraps_stremio_avatar_as_remote_profile_avatar() {\n        assertEquals(\n            \"url:https://example.com/avatar.jpg\",\n            resolveSetupAccountAvatarRef(true, \"  https://example.com/avatar.jpg  \")\n        )\n    }\n\n    @Test\n    fun missing_avatar_keeps_existing_avatar_choice() {\n        assertNull(resolveSetupAccountAvatarRef(true, null))\n        assertNull(resolveSetupAccountAvatarRef(true, \"   \"))\n    }\n\n    @Test\n    fun non_web_avatar_url_is_rejected() {\n        assertNull(resolveSetupAccountAvatarRef(true, \"file:///tmp/avatar.jpg\"))\n        assertNull(resolveSetupAccountAvatarRef(true, \"javascript:alert(1)\"))\n    }\n}\n''')

# ---------------------------------------------------------------------------
# Account/logout discoverability.
# ---------------------------------------------------------------------------
integrations = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt"
replace_once(integrations, 'title = "Disconnect Account"', 'title = "Log Out of Stremio"')
replace_once(integrations, 'subtitle = "Remove Stremio connection"', 'subtitle = "Sign this profile out of its Stremio account"')
replace_once(integrations, '"Disconnect Stremio?"', '"Log out of Stremio?"')
replace_once(
    integrations,
    '"Your installed addons will remain, but you won\'t be able to sync new addons until you reconnect."',
    '"This profile will be signed out of Stremio. Installed addons remain, but account sync stops until you sign in again."'
)
replace_once(integrations, 'text = "Disconnect",\n                        onClick = onConfirm,', 'text = "Log Out",\n                        onClick = onConfirm,')

nav_drawer = "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt"
replace_once(nav_drawer, 'Profile(R.drawable.profile_icon, "Profile", iconSize = 18.dp)', 'Profile(R.drawable.profile_icon, "Log Out", iconSize = 18.dp)')
replace_once(nav_drawer, 'text = "Change Profile",', 'text = "Log Out / Switch Profile",')

top_nav = "app/src/main/java/com/hereliesaz/illumera/ui/navigation/TopNavigationBar.kt"
replace_once(top_nav, 'text = "Change Profile",', 'text = "Log Out / Switch Profile",')

# ---------------------------------------------------------------------------
# Branding in both onboarding surfaces.
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/remote_input/IntegrationServer.kt",
    "<h1>Connect Stremio to Lumera</h1>",
    "<h1>Connect Stremio to illumera</h1>"
)

# Documentation should describe every supported authentication path.
readme = ROOT / "README.md"
readme_text = readme.read_text()
readme_text = readme_text.replace(
    "Connect your Stremio account — with email/password or Facebook — to instantly",
    "Connect your Stremio account — with email/password, phone handoff, Facebook, or Apple — to instantly"
)
readme.write_text(readme_text)

arch = ROOT / "docs/ARCHITECTURE.md"
arch_text = arch.read_text()
arch_text = arch_text.replace(
    "Stremio account API — email/password or Facebook login via\nStremio's own hosted OAuth handoff, addon-collection get/set, and two-way",
    "Stremio account API — email/password, TV-to-phone credential handoff, and hosted\nFacebook/Apple OAuth, addon-collection get/set, and two-way"
)
arch.write_text(arch_text)
