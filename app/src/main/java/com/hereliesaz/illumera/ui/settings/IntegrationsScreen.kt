package com.hereliesaz.illumera.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.hereliesaz.illumera.ui.util.rememberDialogWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.ui.util.DeviceFormFactor
import com.hereliesaz.illumera.ui.util.detectDeviceFormFactor
import com.hereliesaz.illumera.ui.util.generateQrCodeBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.hereliesaz.illumera.data.auth.StremioConnectionState
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.trakt.DeviceAuthState
import com.hereliesaz.illumera.remote_input.ServerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    viewModel: IntegrationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val deviceFormFactor = remember(context) { detectDeviceFormFactor(context) }
    // Dialog state
    var showConnectDialog by remember { mutableStateOf(false) }
    var showManagementDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is IntegrationsEvent.LoginSuccess -> {
                    Toast.makeText(context, "Connected to Stremio!", Toast.LENGTH_SHORT).show()
                    showConnectDialog = false
                }
                is IntegrationsEvent.LoginError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                is IntegrationsEvent.SyncComplete -> {
                    Toast.makeText(context, "Imported ${event.count} addon(s)", Toast.LENGTH_SHORT).show()
                }
                is IntegrationsEvent.Disconnected -> {
                    Toast.makeText(context, "Disconnected from Stremio", Toast.LENGTH_SHORT).show()
                }
                is IntegrationsEvent.DebridConnected -> {
                    Toast.makeText(context, "Connected to ${event.provider.displayName}", Toast.LENGTH_SHORT).show()
                }
                is IntegrationsEvent.DebridError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val goBackModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
            onBack()
            true
        } else false
    }
    
    // Block Up navigation when top nav is active
    val upBlockModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
            true // Consume the event to block focus escape
        } else false
    }

    // Extract connection state for use in dialogs
    val stremioConnected = state.connectionState is StremioConnectionState.Connected
    val stremioEmail = (state.connectionState as? StremioConnectionState.Connected)?.email

    var showTmdbSettings by remember { mutableStateOf(false) }
    var showTraktDialog by remember { mutableStateOf(false) }
    var showDebridDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Top
    ) {
        // Header
        Text(
            "Integrations",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            color = Color.White
        )
        Text(
            "Connect external services to enhance your experience.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color.White.copy(0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(32.dp))

        // Stremio Integration Item
        IntegrationItem(
            title = "Stremio",
            subtitle = if (stremioConnected) stremioEmail ?: "Connected" else "Not Connected",
            isConnected = stremioConnected,
            onClick = {
                if (stremioConnected) {
                    showManagementDialog = true
                } else {
                    showConnectDialog = true
                }
            },
            modifier = goBackModifier.then(upBlockModifier)
        )

        Spacer(Modifier.height(12.dp))

        // TMDB Integration Item
        IntegrationItem(
            title = "TMDB",
            subtitle = if (state.tmdbEnabled) {
                val langName = TMDB_LANGUAGE_OPTIONS
                    .firstOrNull { it.second == state.tmdbLanguage }?.first
                    ?: "Device Language"
                "Enabled · $langName"
            } else "Disabled",
            isConnected = state.tmdbEnabled,
            onClick = { showTmdbSettings = true },
            modifier = goBackModifier
        )

        Spacer(Modifier.height(12.dp))

        // Trakt Integration Item
        IntegrationItem(
            title = "Trakt",
            subtitle = if (state.traktConnected) "Connected" else "Not Connected",
            isConnected = state.traktConnected,
            onClick = { showTraktDialog = true },
            modifier = goBackModifier
        )

        Spacer(Modifier.height(12.dp))

        // Debrid Integration Item
        IntegrationItem(
            title = "Debrid Service",
            subtitle = state.debridProvider?.let { provider ->
                "${provider.displayName}${state.debridUsername?.let { " · $it" } ?: ""}"
            } ?: "Not Connected",
            isConnected = state.debridProvider != null,
            onClick = { showDebridDialog = true },
            modifier = goBackModifier
        )
    }

    // Connect Dialog
    if (showConnectDialog) {
        ConnectStremioDialog(
            isLoading = state.isLoading,
            facebookLoginState = state.facebookLoginState,
            onDismiss = {
                showConnectDialog = false
                viewModel.resetFacebookLoginState()
            },
            onLogin = { email, password ->
                viewModel.login(email, password)
            },
            onRegister = { email, password, marketing ->
                viewModel.register(email, password, marketing)
            },
            onLoginWithFacebook = { viewModel.startFacebookLogin() },
            deviceFormFactor = deviceFormFactor
        )
    }

    // Management Dialog (when connected)
    if (showManagementDialog) {
        StremioManagementDialog(
            email = stremioEmail ?: "",
            onDismiss = { showManagementDialog = false },
            onSyncAddons = {
                showManagementDialog = false
                viewModel.syncAddons()
            },
            onSyncLibrary = {
                showManagementDialog = false
                viewModel.syncLibrary()
            },
            onPushAddons = {
                showManagementDialog = false
                viewModel.pushAddonsToStremio()
            },
            onDisconnect = {
                showManagementDialog = false
                showDisconnectConfirm = true
            }
        )
    }

    // Disconnect Confirmation
    if (showDisconnectConfirm) {
        DisconnectConfirmDialog(
            onDismiss = { showDisconnectConfirm = false },
            onConfirm = {
                showDisconnectConfirm = false
                viewModel.disconnect()
            }
        )
    }

    // Addon Import Dialog
    state.pendingAddons?.let { addons ->
        com.hereliesaz.illumera.ui.addons.AddonImportDialog(
            addons = addons,
            onDismissRequest = { viewModel.dismissImportDialog() },
            onConfirmImport = { selectedAddons ->
                viewModel.importAddons(selectedAddons)
            }
        )
    }

    // TMDB Settings Dialog
    if (showTmdbSettings) {
        TmdbSettingsDialog(
            enabled = state.tmdbEnabled,
            language = state.tmdbLanguage,
            onEnabledChange = { viewModel.updateTmdbEnabled(it) },
            onLanguageChange = { viewModel.updateTmdbLanguage(it) },
            onDismiss = { showTmdbSettings = false }
        )
    }

    // Trakt Auth Dialog
    if (showTraktDialog) {
        TraktAuthDialog(
            isConnected = state.traktConnected,
            authState = state.traktAuthState,
            onConnect = { viewModel.startTraktAuth() },
            onDisconnect = { viewModel.disconnectTrakt() },
            onDismiss = {
                showTraktDialog = false
                viewModel.resetTraktAuthState()
            }
        )
    }

    // Debrid Dialog
    if (showDebridDialog) {
        DebridDialog(
            connectedProvider = state.debridProvider,
            connectedUsername = state.debridUsername,
            isConnecting = state.debridConnecting,
            onConnect = { provider, apiKey -> viewModel.connectDebrid(provider, apiKey) },
            onDisconnect = { viewModel.disconnectDebrid() },
            onDismiss = { showDebridDialog = false },
            deviceFormFactor = deviceFormFactor
        )
    }
}

@Composable
private fun IntegrationItem(
    title: String,
    subtitle: String,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)
    val borderColor by animateColorAsState(
        if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent
    )
    val bgColor = Color.White.copy(0.05f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 0.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
            .padding(16.dp)
    ) {
        // Icon
        Icon(
            imageVector = if (isConnected) Icons.Default.Cloud else Icons.Default.CloudOff,
            contentDescription = null,
            tint = if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(32.dp)
        )

        Spacer(Modifier.width(16.dp))

        // Text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Status indicator
        if (isFocused) {
            Text(
                if (isConnected) "Manage" else "Connect",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

// =============================================================================
// CONNECT STREMIO DIALOG (Split Layout: Manual + QR Code)
// =============================================================================

@Composable
private fun ConnectStremioDialog(
    isLoading: Boolean,
    facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,
    onDismiss: () -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String, marketing: Boolean) -> Unit,
    onLoginWithFacebook: () -> Unit = {},
    deviceFormFactor: DeviceFormFactor
) {
    if (facebookLoginState is FacebookLoginState.WaitingForUser || facebookLoginState is FacebookLoginState.Error) {
        FacebookLoginDialog(
            state = facebookLoginState,
            onDismiss = onDismiss,
            onRetry = onLoginWithFacebook,
            deviceFormFactor = deviceFormFactor
        )
        return
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showSignup by remember { mutableStateOf(false) }
    var showPasswordReset by remember { mutableStateOf(false) }

    if (showSignup) {
        StremioSignupDialog(
            isLoading = isLoading,
            onDismiss = { showSignup = false },
            onRegister = onRegister
        )
        return
    }
    if (showPasswordReset) {
        StremioPasswordResetDialog(
            initialEmail = email,
            deviceFormFactor = deviceFormFactor,
            onDismiss = { showPasswordReset = false }
        )
        return
    }

    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val emailFocusRequester = remember { FocusRequester() }
    val serverManager = remember { com.hereliesaz.illumera.remote_input.IntegrationServerManager() }

    // TVs get a local QR handoff so credentials can be entered on a phone.
    // Touch/keyboard devices keep the login entirely on-device.
    LaunchedEffect(deviceFormFactor) {
        delay(100)
        runCatching { emailFocusRequester.requestFocus() }
        if (deviceFormFactor == DeviceFormFactor.TV) {
            val info = serverManager.startServer { receivedEmail, receivedPassword ->
                onLogin(receivedEmail, receivedPassword)
            }
            if (info != null) {
                serverInfo = info
                qrBitmap = generateQrCodeBitmap(info.url)
            }
        }
    }

    // Stop server when dialog closes
    DisposableEffect(Unit) {
        onDispose {
            serverManager.stopServer()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            val isCompact = LocalConfiguration.current.screenWidthDp < 600
            Box(
                modifier = Modifier
                    .padding(top = 65.dp)
                    .width(if (isCompact) rememberDialogWidth(900) else 900.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    .imePadding()
                    .padding(horizontal = 32.dp, vertical = 24.dp)
                    .then(if (isCompact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            ) {
                // Below 600dp, the email/password form and the QR panel can't sit
                // side by side without both being crushed — stack them instead.
                val formAndQr = @Composable {
                    // LEFT: Header + Manual Input. Uses fillMaxWidth(fraction) rather than
                    // RowScope/ColumnScope.weight since this content is shared between a
                    // Row (wide screens) and a Column (narrow screens) below via a plain
                    // (no-receiver) lambda, where weight() wouldn't resolve.
                    Column(
                        modifier = if (deviceFormFactor != DeviceFormFactor.TV || isCompact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.65f)
                    ) {
                        // Header
                        Text(
                            "Connect your Stremio account",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false
                        )
                        Text(
                            "Import your existing addons from Stremio",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false
                        )

                        // Email & Password in a column for vertical layout
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            IntegrationTextField(
                                value = email,
                                onValueChange = { email = it },
                                placeholder = "Email",
                                keyboardType = KeyboardType.Email,
                                focusRequester = emailFocusRequester,
                                modifier = Modifier.fillMaxWidth()
                            )

                            IntegrationTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = "Password",
                                isPassword = true,
                                keyboardType = KeyboardType.Password,
                                modifier = Modifier.fillMaxWidth(),
                                onDone = {
                                    if (email.isNotBlank() && password.isNotBlank() && !isLoading) {
                                        onLogin(email, password)
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Buttons row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            IntegrationButton(
                                text = if (isLoading) "Connecting..." else "Connect",
                                onClick = { onLogin(email, password) },
                                enabled = email.isNotBlank() && password.isNotBlank() && !isLoading,
                                isPrimary = true,
                                modifier = Modifier.width(140.dp)
                            )

                            IntegrationButton(
                                text = "Login with Facebook",
                                onClick = onLoginWithFacebook,
                                enabled = !isLoading,
                                isPrimary = false,
                                modifier = Modifier.width(180.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IntegrationButton(
                                text = "Create account",
                                onClick = { showSignup = true },
                                enabled = !isLoading,
                                modifier = Modifier.width(160.dp)
                            )
                            IntegrationButton(
                                text = "Forgot password",
                                onClick = { showPasswordReset = true },
                                enabled = !isLoading,
                                modifier = Modifier.width(170.dp)
                            )
                        }
                    }

                    if (deviceFormFactor == DeviceFormFactor.TV) {
                        Box(
                            modifier = if (isCompact) {
                                Modifier.fillMaxWidth().padding(vertical = 16.dp).height(1.dp).background(Color.White.copy(0.1f))
                            } else {
                                Modifier.width(1.dp).height(160.dp).background(Color.White.copy(0.1f))
                            }
                        )
                        Column(
                            modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.35f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                        Text(
                            "Or Scan with Phone",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(16.dp))

                        if (qrBitmap != null && serverInfo != null) {
                            Box(
                                modifier = Modifier
                                    .size(150.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                                    .padding(4.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap!!.asImageBitmap(),
                                    contentDescription = "QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Text(
                                serverInfo!!.url,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Visible,
                                softWrap = false,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                    }
                }

                if (deviceFormFactor != DeviceFormFactor.TV || isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        formAndQr()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        formAndQr()
                    }
                }

                // Loading overlay
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// =============================================================================
// STREMIO ACCOUNT SIGNUP / PASSWORD RESET
// =============================================================================

@Composable
private fun StremioSignupDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRegister: (email: String, password: String, marketing: Boolean) -> Unit
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }
    var privacyAccepted by remember { mutableStateOf(false) }
    var marketingAccepted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val emailFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { emailFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(rememberDialogWidth(520))
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(28.dp)
        ) {
            Text(
                "Create Stremio account",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                "Create the same account you can use in Stremio, then connect it to Illumera automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
            )

            IntegrationTextField(
                value = email,
                onValueChange = { email = it; error = null },
                placeholder = "Email",
                keyboardType = KeyboardType.Email,
                focusRequester = emailFocusRequester,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            IntegrationTextField(
                value = password,
                onValueChange = { password = it; error = null },
                placeholder = "Password",
                isPassword = true,
                keyboardType = KeyboardType.Password,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            IntegrationTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; error = null },
                placeholder = "Confirm password",
                isPassword = true,
                keyboardType = KeyboardType.Password,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))
            StremioConsentRow(
                checked = termsAccepted,
                onCheckedChange = { termsAccepted = it; error = null },
                label = "I agree to the Stremio Terms of Service",
                linkLabel = "View terms",
                onOpenLink = { openExternalUrl(context, "https://www.stremio.com/tos") }
            )
            StremioConsentRow(
                checked = privacyAccepted,
                onCheckedChange = { privacyAccepted = it; error = null },
                label = "I agree to the Stremio Privacy Policy",
                linkLabel = "View privacy policy",
                onOpenLink = { openExternalUrl(context, "https://www.stremio.com/privacy") }
            )
            StremioConsentRow(
                checked = marketingAccepted,
                onCheckedChange = { marketingAccepted = it },
                label = "Receive Stremio updates by email (optional)"
            )

            if (error != null) {
                Text(
                    error!!,
                    color = Color(0xFFEF4444),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntegrationButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                )
                IntegrationButton(
                    text = if (isLoading) "Creating..." else "Create account",
                    onClick = {
                        val cleanEmail = email.trim()
                        error = when {
                            !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches() -> "Enter a valid email address"
                            password.isBlank() -> "Enter a password"
                            password != confirmPassword -> "Passwords do not match"
                            !termsAccepted -> "Accept the Terms of Service to continue"
                            !privacyAccepted -> "Accept the Privacy Policy to continue"
                            else -> null
                        }
                        if (error == null) onRegister(cleanEmail, password, marketingAccepted)
                    },
                    enabled = !isLoading,
                    isPrimary = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StremioConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    linkLabel: String? = null,
    onOpenLink: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White.copy(0.82f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        if (linkLabel != null && onOpenLink != null) {
            Text(
                linkLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 48.dp, top = 2.dp).clickable(onClick = onOpenLink)
            )
        }
    }
}

@Composable
private fun StremioPasswordResetDialog(
    initialEmail: String,
    deviceFormFactor: DeviceFormFactor,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var error by remember { mutableStateOf<String?>(null) }
    var resetUrl by remember { mutableStateOf<String?>(null) }
    val qrBitmap by produceState<Bitmap?>(initialValue = null, resetUrl, deviceFormFactor) {
        value = if (deviceFormFactor == DeviceFormFactor.TV) resetUrl?.let { generateQrCodeBitmap(it) } else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(rememberDialogWidth(460))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Reset Stremio password",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))

            if (resetUrl == null) {
                Text(
                    "Enter the email for your Stremio account. Stremio will handle the reset in its browser flow.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                IntegrationTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    placeholder = "Email",
                    keyboardType = KeyboardType.Email,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = Color(0xFFEF4444), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IntegrationButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
                    IntegrationButton(
                        text = "Continue",
                        onClick = {
                            val cleanEmail = email.trim()
                            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                                error = "Enter a valid email address"
                            } else {
                                val url = "https://www.strem.io/reset-password/$cleanEmail"
                                if (deviceFormFactor == DeviceFormFactor.TV) {
                                    resetUrl = url
                                } else {
                                    openExternalUrl(context, url)
                                    onDismiss()
                                }
                            }
                        },
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    "Scan with your phone to open Stremio's password reset page.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier.size(190.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(8.dp)
                    ) {
                        Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "Stremio password reset QR code", modifier = Modifier.fillMaxSize())
                    }
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(18.dp))
                IntegrationButton(text = "Close", onClick = onDismiss, modifier = Modifier.width(140.dp))
            }
        }
    }
}

// =============================================================================
// FACEBOOK LOGIN DIALOG (QR handoff to Stremio's own OAuth page)
// =============================================================================

@Composable
private fun FacebookLoginDialog(
    state: FacebookLoginState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    deviceFormFactor: DeviceFormFactor
) {
    val context = LocalContext.current
    val url = (state as? FacebookLoginState.WaitingForUser)?.url
    val qrBitmap by produceState<Bitmap?>(initialValue = null, url, deviceFormFactor) {
        value = if (deviceFormFactor == DeviceFormFactor.TV) url?.let { generateQrCodeBitmap(it) } else null
    }

    LaunchedEffect(url, deviceFormFactor) {
        if (deviceFormFactor != DeviceFormFactor.TV && !url.isNullOrBlank()) openExternalUrl(context, url)
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(420))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Login with Facebook",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))

                when (state) {
                    is FacebookLoginState.WaitingForUser -> {
                        if (deviceFormFactor == DeviceFormFactor.TV) {
                            Text(
                                "Scan this code with your phone, sign in with Facebook, then come back — this closes automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(8.dp)
                                ) {
                                    Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "Facebook login QR code", modifier = Modifier.fillMaxSize())
                                }
                            }
                        } else {
                            Text(
                                "Finish signing in with Facebook in your browser, then return here. Illumera will detect the completed login automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            IntegrationButton(
                                text = "Open Browser",
                                onClick = { url?.let { openExternalUrl(context, it) } },
                                isPrimary = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("Waiting for Facebook login…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    is FacebookLoginState.Error -> {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFEF4444),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IntegrationButton(text = "Retry", onClick = onRetry, isPrimary = true, modifier = Modifier.width(120.dp))
                            IntegrationButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.width(120.dp))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

// =============================================================================
// STREMIO MANAGEMENT DIALOG
// =============================================================================

@Composable
private fun StremioManagementDialog(
    email: String,
    onDismiss: () -> Unit,
    onSyncAddons: () -> Unit,
    onSyncLibrary: () -> Unit,
    onPushAddons: () -> Unit,
    onDisconnect: () -> Unit
) {
    val syncFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        syncFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(400))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    "Stremio Account",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Text(
                    email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(24.dp))

                // Sync Addons
                ManagementMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Add New Addons",
                    subtitle = "Import addons from your Stremio account",
                    onClick = onSyncAddons,
                    focusRequester = syncFocusRequester
                )

                Spacer(Modifier.height(12.dp))

                // Sync Continue Watching
                ManagementMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Sync Continue Watching",
                    subtitle = "Pull and push watch progress with your Stremio library",
                    onClick = onSyncLibrary
                )

                Spacer(Modifier.height(12.dp))

                // Push addon collection back to the account
                ManagementMenuItem(
                    icon = Icons.Default.Cloud,
                    title = "Push Addons to Stremio",
                    subtitle = "Replace your account's addon collection with this device's",
                    onClick = onPushAddons
                )

                Spacer(Modifier.height(12.dp))

                // Disconnect
                ManagementMenuItem(
                    icon = Icons.Default.Logout,
                    title = "Disconnect Account",
                    subtitle = "Remove Stremio connection",
                    onClick = onDisconnect,
                    isDestructive = true
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IntegrationButton(
                        text = "Close",
                        onClick = onDismiss,
                        modifier = Modifier.width(100.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagementMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    isDestructive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)
    val bgColor by animateColorAsState(if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f))
    val iconColor = if (isDestructive) Color.Red else if (isFocused) MaterialTheme.colorScheme.primary else Color.Gray

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
            .padding(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (isDestructive) Color.Red else Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

// =============================================================================
// DISCONNECT CONFIRMATION DIALOG
// =============================================================================

@Composable
private fun DisconnectConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        confirmFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(350))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    "Disconnect Stremio?",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Your installed addons will remain, but you won't be able to sync new addons until you reconnect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IntegrationButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )

                    IntegrationButton(
                        text = "Disconnect",
                        onClick = onConfirm,
                        isDestructive = true,
                        modifier = Modifier.weight(1f),
                        focusRequester = confirmFocusRequester
                    )
                }
            }
        }
    }
}

// =============================================================================
// TMDB SETTINGS DIALOG
// =============================================================================

private val TMDB_LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
    "Device Language" to "",
    "English" to "en",
    "Spanish" to "es",
    "Spanish (Latin America)" to "es-419",
    "French" to "fr",
    "German" to "de",
    "Italian" to "it",
    "Portuguese" to "pt",
    "Portuguese (Brazil)" to "pt-BR",
    "Russian" to "ru",
    "Japanese" to "ja",
    "Korean" to "ko",
    "Chinese" to "zh",
    "Chinese (Simplified)" to "zh-CN",
    "Chinese (Traditional)" to "zh-TW",
    "Arabic" to "ar",
    "Hindi" to "hi",
    "Turkish" to "tr",
    "Polish" to "pl",
    "Dutch" to "nl",
    "Swedish" to "sv",
    "Norwegian" to "no",
    "Danish" to "da",
    "Finnish" to "fi",
    "Czech" to "cs",
    "Hungarian" to "hu",
    "Romanian" to "ro",
    "Thai" to "th",
    "Vietnamese" to "vi",
    "Indonesian" to "id",
    "Ukrainian" to "uk",
    "Greek" to "el",
    "Hebrew" to "he",
    "Malay" to "ms",
    "Croatian" to "hr",
    "Bulgarian" to "bg",
    "Slovak" to "sk",
    "Serbian" to "sr",
    "Filipino" to "tl",
    "Persian" to "fa",
    "Bengali" to "bn",
    "Tamil" to "ta",
    "Telugu" to "te",
    "Afrikaans" to "af",
    "Albanian" to "sq",
    "Armenian" to "hy",
    "Azerbaijani" to "az",
    "Basque" to "eu",
    "Belarusian" to "be",
    "Bosnian" to "bs",
    "Catalan" to "ca",
    "Estonian" to "et",
    "Georgian" to "ka",
    "Icelandic" to "is",
    "Irish" to "ga",
    "Kannada" to "kn",
    "Kazakh" to "kk",
    "Latvian" to "lv",
    "Lithuanian" to "lt",
    "Macedonian" to "mk",
    "Malayalam" to "ml",
    "Mongolian" to "mn",
    "Slovenian" to "sl",
    "Swahili" to "sw",
    "Urdu" to "ur"
)

@Composable
private fun TmdbSettingsDialog(
    enabled: Boolean,
    language: String,
    onEnabledChange: (Boolean) -> Unit,
    onLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val toggleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        toggleFocusRequester.requestFocus()
    }

    val selectedIndex = TMDB_LANGUAGE_OPTIONS.indexOfFirst { it.second == language }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val accentColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(selectedIndex) {
        if (selectedIndex > 0) runCatching { listState.scrollToItem(selectedIndex) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 65.dp)
                    .width(rememberDialogWidth(460))
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column {
                    Text(
                        "TMDB Settings",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    Text(
                        "Enrich metadata with localized info, cast, ratings, and more.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(24.dp))

                    // Enable/Disable toggle
                    TmdbToggleItem(
                        title = "Enable TMDB",
                        subtitle = "Fetch enhanced metadata from The Movie Database",
                        isEnabled = enabled,
                        onToggle = { onEnabledChange(!enabled) },
                        focusRequester = toggleFocusRequester
                    )

                    Spacer(Modifier.height(20.dp))

                    // Language section header
                    Text(
                        "Metadata Language",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = if (enabled) Color.White else Color.White.copy(0.4f)
                    )
                    Text(
                        "Titles, descriptions, and logos will use this language when available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) Color.Gray else Color.Gray.copy(0.5f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    // Scrollable language list
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    ) {
                        itemsIndexed(TMDB_LANGUAGE_OPTIONS) { _, (displayName, code) ->
                            val isSelected = code == language
                            val interactionSource = remember { MutableInteractionSource() }
                            val isFocused by interactionSource.collectIsFocusedAsState()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isFocused -> Color.White.copy(0.1f)
                                            isSelected -> accentColor.copy(0.1f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .border(
                                        width = if (isFocused) 1.dp else 0.dp,
                                        color = if (isFocused) accentColor else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .then(
                                        if (enabled) Modifier
                                            .clickable(interactionSource = interactionSource, indication = null) {
                                                onLanguageChange(code)
                                            }
                                            .focusable(interactionSource = interactionSource)
                                        else Modifier
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (enabled) accentColor else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = when {
                                        !enabled -> Color.White.copy(0.3f)
                                        isSelected -> accentColor
                                        isFocused -> Color.White
                                        else -> Color.White.copy(0.7f)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IntegrationButton(
                            text = "Close",
                            onClick = onDismiss,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TmdbToggleItem(
    title: String,
    subtitle: String,
    isEnabled: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)
    val bgColor by animateColorAsState(if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(interactionSource = interactionSource, indication = null) { onToggle() }
            .focusable(interactionSource = interactionSource)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        Spacer(Modifier.width(12.dp))

        // Toggle indicator
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (isEnabled) MaterialTheme.colorScheme.primary.copy(0.8f)
                    else Color.White.copy(0.15f)
                ),
            contentAlignment = if (isEnabled) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White)
            )
        }
    }
}

// =============================================================================
// SHARED UI COMPONENTS
// =============================================================================

@Composable
private fun IntegrationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    onDone: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderBrush = if (isFocused) {
        Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary))
    } else {
        SolidColor(Color.White.copy(0.1f))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(0.5f))
            .border(if (isFocused) 2.dp else 1.dp, borderBrush, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Color.Gray)
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onDone = { onDone?.invoke() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { isFocused = it.isFocused }
        )
    }
}

@Composable
private fun IntegrationButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (isFocused && enabled) 1.05f else 1f)

    val activeColor = when {
        isDestructive -> Color.Red
        else -> MaterialTheme.colorScheme.primary
    }

    val bgColor = if (!enabled) Color.White.copy(0.05f) else Color.White.copy(0.08f)

    // Unfocused is always plain white/dimmed — isDestructive/isPrimary only color the
    // button once it's actually the one focused, so which action needs a deliberate
    // move to reach is never ambiguous.
    val textColor = when {
        !enabled -> Color.White.copy(0.3f)
        isFocused -> activeColor
        else -> Color.White
    }

    val borderColor = when {
        !enabled -> Color.White.copy(0.1f)
        isFocused -> activeColor
        else -> Color.White.copy(0.2f)
    }

    Box(
        modifier = modifier
            .height(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick() } else Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(if (enabled) Modifier.focusable(interactionSource = interactionSource) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}

// =============================================================================
// DEBRID DIALOG
// =============================================================================

@Composable
private fun DebridDialog(
    connectedProvider: DebridProvider?,
    connectedUsername: String?,
    isConnecting: Boolean,
    onConnect: (DebridProvider, String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    deviceFormFactor: DeviceFormFactor
) {
    var selectedProvider by remember { mutableStateOf(connectedProvider ?: DebridProvider.REAL_DEBRID) }
    var apiKey by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focusRequester.requestFocus() }
    }


    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .width(rememberDialogWidth(460))
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    .imePadding()
                    .padding(24.dp)
            ) {
                Column {
                    Text(
                        "Debrid Service",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        "Connect a debrid service to browse and stream your cloud storage from the Library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(20.dp))

                    if (connectedProvider != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Connected to ${connectedProvider.displayName}${connectedUsername?.let { " ($it)" } ?: ""}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            IntegrationButton(
                                text = "Disconnect",
                                onClick = onDisconnect,
                                isDestructive = true,
                                modifier = Modifier.width(130.dp),
                                focusRequester = focusRequester
                            )
                            IntegrationButton(
                                text = "Close",
                                onClick = onDismiss,
                                modifier = Modifier.width(100.dp)
                            )
                        }
                    } else {
                        Text(
                            "Service",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(0.8f)
                        )
                        Spacer(Modifier.height(8.dp))

                        // Provider picker: wrapping chips, one per known debrid service.
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DebridProvider.entries.forEach { provider ->
                                SettingToggleChip(
                                    label = provider.displayName,
                                    isChecked = selectedProvider == provider,
                                    onCheckedChange = { selectedProvider = provider }
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        if (deviceFormFactor == DeviceFormFactor.TV) {
                            Text("Get your API key on your phone", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(0.8f))
                            Text(
                                "Scan the QR code to open ${selectedProvider.displayName}'s HTTPS API-key page. For security, Illumera never sends the raw key over the local network; enter the key on the TV after retrieving it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )
                            val providerQr by produceState<Bitmap?>(initialValue = null, selectedProvider) {
                                value = generateQrCodeBitmap(selectedProvider.apiKeyUrl, 260)
                            }
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (providerQr != null) {
                                    Box(modifier = Modifier.size(180.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(8.dp)) {
                                        Image(bitmap = providerQr!!.asImageBitmap(), contentDescription = "${selectedProvider.displayName} API key QR code", modifier = Modifier.fillMaxSize())
                                    }
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = accentColor, strokeWidth = 3.dp)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("API Key", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(0.8f))
                            Spacer(Modifier.height(8.dp))
                            IntegrationTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                placeholder = "Enter your ${selectedProvider.displayName} API key",
                                isPassword = true,
                                focusRequester = focusRequester,
                                modifier = Modifier.fillMaxWidth(),
                                onDone = { if (apiKey.isNotBlank() && !isConnecting) onConnect(selectedProvider, apiKey) }
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                                IntegrationButton(text = if (isConnecting) "Connecting..." else "Connect", onClick = { onConnect(selectedProvider, apiKey) }, enabled = apiKey.isNotBlank() && !isConnecting, isPrimary = true, modifier = Modifier.width(130.dp))
                                IntegrationButton(text = "Close", onClick = onDismiss, modifier = Modifier.width(100.dp))
                            }
                        } else {
                            Text("API Key", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(0.8f))
                            Spacer(Modifier.height(8.dp))
                            IntegrationTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                placeholder = "Paste your ${selectedProvider.displayName} API key",
                                isPassword = true,
                                focusRequester = focusRequester,
                                modifier = Modifier.fillMaxWidth(),
                                onDone = { if (apiKey.isNotBlank() && !isConnecting) onConnect(selectedProvider, apiKey) }
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IntegrationButton(text = "Get API Key", onClick = { openExternalUrl(context, selectedProvider.apiKeyUrl) }, enabled = !isConnecting, modifier = Modifier.weight(1f))
                                IntegrationButton(text = if (isConnecting) "Connecting..." else "Connect", onClick = { onConnect(selectedProvider, apiKey) }, enabled = apiKey.isNotBlank() && !isConnecting, isPrimary = true, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IntegrationButton(text = "Close", onClick = onDismiss, modifier = Modifier.width(100.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraktAuthDialog(
    isConnected: Boolean,
    authState: DeviceAuthState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(authState) {
        delay(200)
        runCatching { focusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(rememberDialogWidth(460))
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text(
                    "Trakt",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    "Track what you watch automatically across all your apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(24.dp))

                if (isConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connected to Trakt", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        IntegrationButton(
                            text = "Disconnect",
                            onClick = onDisconnect,
                            isDestructive = true,
                            modifier = Modifier.width(130.dp),
                            focusRequester = focusRequester
                        )
                        IntegrationButton(
                            text = "Close",
                            onClick = onDismiss,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                } else {
                    when (authState) {
                        is DeviceAuthState.Idle -> {
                            Text(
                                "Connect your Trakt account to sync your watchlist, watch history, and scrobble playback.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(0.7f)
                            )

                            Spacer(Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                            ) {
                                IntegrationButton(
                                    text = "Connect",
                                    onClick = onConnect,
                                    isPrimary = true,
                                    modifier = Modifier.width(130.dp),
                                    focusRequester = focusRequester
                                )
                                IntegrationButton(
                                    text = "Close",
                                    onClick = onDismiss,
                                    modifier = Modifier.width(100.dp)
                                )
                            }
                        }

                        is DeviceAuthState.WaitingForUser -> {
                            Text(
                                "Go to the URL below and enter the code:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(0.7f)
                            )

                            Spacer(Modifier.height(16.dp))

                            Text(
                                authState.verificationUrl,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(Modifier.height(16.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(0.08f), RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(0.3f), RoundedCornerShape(12.dp))
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    authState.userCode,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 8.sp
                                    ),
                                    color = Color.White
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            val qrBitmap by produceState<Bitmap?>(initialValue = null, authState.verificationUrl) {
                                value = generateQrCodeBitmap(authState.verificationUrl, 200)
                            }
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap!!.asImageBitmap(),
                                        contentDescription = "QR Code",
                                        modifier = Modifier.size(120.dp)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            Text(
                                "Waiting for authorization...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(0.5f)
                            )

                            Spacer(Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IntegrationButton(
                                    text = "Cancel",
                                    onClick = onDismiss,
                                    modifier = Modifier.width(100.dp),
                                    focusRequester = focusRequester
                                )
                            }
                        }

                        is DeviceAuthState.Success -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Successfully connected!", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            }

                            Spacer(Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IntegrationButton(
                                    text = "Done",
                                    onClick = onDismiss,
                                    isPrimary = true,
                                    modifier = Modifier.width(100.dp),
                                    focusRequester = focusRequester
                                )
                            }
                        }

                        is DeviceAuthState.Error -> {
                            Text(authState.message, style = MaterialTheme.typography.bodyMedium, color = Color.Red.copy(0.8f))

                            Spacer(Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                            ) {
                                IntegrationButton(text = "Retry", onClick = onConnect, isPrimary = true, modifier = Modifier.width(100.dp), focusRequester = focusRequester)
                                IntegrationButton(text = "Close", onClick = onDismiss, modifier = Modifier.width(100.dp))
                            }
                        }

                        is DeviceAuthState.NotConfigured -> {
                            Text(
                                "This build has no Trakt connection configured — Trakt now requires " +
                                    "a paid VIP subscription just to register a developer app, so this " +
                                    "isn't something a Retry can fix.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Red.copy(0.8f)
                            )

                            Spacer(Modifier.height(16.dp))

                            Text(
                                "Free alternative: enable Trakt Scrobbling at stremio.com/acc-settings, " +
                                    "then connect your Stremio account here and sync addons — your Trakt " +
                                    "watchlist, history, and recommendations will come in as catalogs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(0.7f)
                            )

                            Spacer(Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IntegrationButton(text = "Close", onClick = onDismiss, isPrimary = true, modifier = Modifier.width(100.dp), focusRequester = focusRequester)
                            }
                        }

                        is DeviceAuthState.Expired -> {
                            Text("The authorization code has expired. Please try again.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.7f))

                            Spacer(Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                            ) {
                                IntegrationButton(text = "Retry", onClick = onConnect, isPrimary = true, modifier = Modifier.width(100.dp), focusRequester = focusRequester)
                                IntegrationButton(text = "Close", onClick = onDismiss, modifier = Modifier.width(100.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}



private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
