from pathlib import Path

p = Path('app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt')
text = p.read_text()

def rep(old: str, new: str, count: int = 1):
    global text
    if text.count(old) < count:
        raise SystemExit(f'missing pattern: {old[:180]!r}')
    text = text.replace(old, new, count)

rep('import android.graphics.Bitmap\nimport android.widget.Toast\n',
    'import android.content.Intent\nimport android.graphics.Bitmap\nimport android.net.Uri\nimport android.widget.Toast\n')
rep('import com.hereliesaz.illumera.ui.util.generateQrCodeBitmap\n',
    'import com.hereliesaz.illumera.ui.util.DeviceFormFactor\nimport com.hereliesaz.illumera.ui.util.detectDeviceFormFactor\nimport com.hereliesaz.illumera.ui.util.generateQrCodeBitmap\n')
rep('import com.hereliesaz.illumera.remote_input.ServerInfo\n',
    'import com.hereliesaz.illumera.remote_input.DebridPairingServerManager\nimport com.hereliesaz.illumera.remote_input.ServerInfo\n')
rep('    val state by viewModel.uiState.collectAsState()\n    val context = LocalContext.current\n',
    '    val state by viewModel.uiState.collectAsState()\n    val context = LocalContext.current\n    val deviceFormFactor = remember(context) { detectDeviceFormFactor(context) }\n')
rep('            onLoginWithFacebook = { viewModel.startFacebookLogin() }\n        )\n',
    '            onLoginWithFacebook = { viewModel.startFacebookLogin() },\n            deviceFormFactor = deviceFormFactor\n        )\n')
rep('            onDisconnect = { viewModel.disconnectDebrid() },\n            onDismiss = { showDebridDialog = false }\n',
    '            onDisconnect = { viewModel.disconnectDebrid() },\n            onDismiss = { showDebridDialog = false },\n            deviceFormFactor = deviceFormFactor\n')
rep('    onDismiss: () -> Unit,\n    onLogin: (email: String, password: String) -> Unit,\n    onLoginWithFacebook: () -> Unit = {}\n) {\n',
    '    onDismiss: () -> Unit,\n    onLogin: (email: String, password: String) -> Unit,\n    onLoginWithFacebook: () -> Unit = {},\n    deviceFormFactor: DeviceFormFactor\n) {\n')
rep('            state = facebookLoginState,\n            onDismiss = onDismiss,\n            onRetry = onLoginWithFacebook\n',
    '            state = facebookLoginState,\n            onDismiss = onDismiss,\n            onRetry = onLoginWithFacebook,\n            deviceFormFactor = deviceFormFactor\n')
rep('''    // Start server for QR code login
    LaunchedEffect(Unit) {
        delay(100)
        emailFocusRequester.requestFocus()
        
        val info = serverManager.startServer { receivedEmail, receivedPassword ->
            // Login with credentials received from phone
            onLogin(receivedEmail, receivedPassword)
        }
        
        if (info != null) {
            serverInfo = info
            qrBitmap = generateQrCodeBitmap(info.url)
        }
    }
''', '''    // TVs get a local QR handoff so credentials can be entered on a phone.
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
''')
rep('                        modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.65f)\n',
    '                        modifier = if (deviceFormFactor != DeviceFormFactor.TV || isCompact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.65f)\n')
rep('''                    // Divider — vertical between the two side-by-side panels, or a
                    // full-width horizontal rule when stacked on a narrow screen.
                    Box(
                        modifier = if (isCompact) {
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                                .height(1.dp)
                                .background(Color.White.copy(0.1f))
                        } else {
                            Modifier
                                .width(1.dp)
                                .height(160.dp)
                                .background(Color.White.copy(0.1f))
                        }
                    )

                    // RIGHT: QR Code (compact)
                    Column(
                        modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.35f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
''', '''                    if (deviceFormFactor == DeviceFormFactor.TV) {
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
''')
rep('''                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }

                if (isCompact) {
''', '''                        } else {
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
''')
rep('private fun FacebookLoginDialog(\n    state: FacebookLoginState,\n    onDismiss: () -> Unit,\n    onRetry: () -> Unit\n) {\n',
    'private fun FacebookLoginDialog(\n    state: FacebookLoginState,\n    onDismiss: () -> Unit,\n    onRetry: () -> Unit,\n    deviceFormFactor: DeviceFormFactor\n) {\n    val context = LocalContext.current\n')
rep('    val qrBitmap by produceState<Bitmap?>(initialValue = null, url) {\n        value = url?.let { generateQrCodeBitmap(it) }\n    }\n',
    '    val qrBitmap by produceState<Bitmap?>(initialValue = null, url, deviceFormFactor) {\n        value = if (deviceFormFactor == DeviceFormFactor.TV) url?.let { generateQrCodeBitmap(it) } else null\n    }\n\n    LaunchedEffect(url, deviceFormFactor) {\n        if (deviceFormFactor != DeviceFormFactor.TV && !url.isNullOrBlank()) openExternalUrl(context, url)\n    }\n')
rep('''                    is FacebookLoginState.WaitingForUser -> {
                        Text(
                            "Scan this code with your phone, sign in with Facebook, then come back — this closes automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                                    .padding(8.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap!!.asImageBitmap(),
                                    contentDescription = "Facebook login QR code",
                                    modifier = Modifier.fillMaxSize()
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
                        Text("Waiting for Facebook login…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
''', '''                    is FacebookLoginState.WaitingForUser -> {
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
''')
rep('    onConnect: (DebridProvider, String) -> Unit,\n    onDisconnect: () -> Unit,\n    onDismiss: () -> Unit\n) {\n',
    '    onConnect: (DebridProvider, String) -> Unit,\n    onDisconnect: () -> Unit,\n    onDismiss: () -> Unit,\n    deviceFormFactor: DeviceFormFactor\n) {\n')
rep('    var apiKey by remember { mutableStateOf("") }\n    val focusRequester = remember { FocusRequester() }\n    val accentColor = MaterialTheme.colorScheme.primary\n',
    '    var apiKey by remember { mutableStateOf("") }\n    var pairingServerInfo by remember { mutableStateOf<ServerInfo?>(null) }\n    var pairingQrBitmap by remember { mutableStateOf<Bitmap?>(null) }\n    val focusRequester = remember { FocusRequester() }\n    val accentColor = MaterialTheme.colorScheme.primary\n    val context = LocalContext.current\n    val pairingServerManager = remember { DebridPairingServerManager() }\n')
rep('    LaunchedEffect(Unit) {\n        delay(150)\n        runCatching { focusRequester.requestFocus() }\n    }\n\n    Dialog(\n',
    '    LaunchedEffect(Unit) {\n        delay(150)\n        runCatching { focusRequester.requestFocus() }\n    }\n\n    LaunchedEffect(deviceFormFactor, connectedProvider, selectedProvider) {\n        pairingServerManager.stopServer()\n        pairingServerInfo = null\n        pairingQrBitmap = null\n        if (deviceFormFactor == DeviceFormFactor.TV && connectedProvider == null) {\n            val info = pairingServerManager.startServer(selectedProvider) { receivedApiKey -> onConnect(selectedProvider, receivedApiKey) }\n            if (info != null) {\n                pairingServerInfo = info\n                pairingQrBitmap = generateQrCodeBitmap(info.url)\n            }\n        }\n    }\n\n    DisposableEffect(Unit) { onDispose { pairingServerManager.stopServer() } }\n\n    Dialog(\n', 1)
rep('''                        Spacer(Modifier.height(16.dp))

                        Text(
                            "API Key",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(0.8f)
                        )
                        Spacer(Modifier.height(8.dp))

                        IntegrationTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = "Paste your ${selectedProvider.displayName} API key",
                            isPassword = true,
                            focusRequester = focusRequester,
                            modifier = Modifier.fillMaxWidth(),
                            onDone = {
                                if (apiKey.isNotBlank() && !isConnecting) onConnect(selectedProvider, apiKey)
                            }
                        )

                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            IntegrationButton(
                                text = if (isConnecting) "Connecting..." else "Connect",
                                onClick = { onConnect(selectedProvider, apiKey) },
                                enabled = apiKey.isNotBlank() && !isConnecting,
                                isPrimary = true,
                                modifier = Modifier.width(130.dp)
                            )
                            IntegrationButton(
                                text = "Close",
                                onClick = onDismiss,
                                modifier = Modifier.width(100.dp)
                            )
                        }
''', '''                        Spacer(Modifier.height(16.dp))

                        if (deviceFormFactor == DeviceFormFactor.TV) {
                            Text("Scan with your phone", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(0.8f))
                            Text(
                                "The phone page opens ${selectedProvider.displayName}, lets you copy the API key, and sends it directly back to this TV.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (pairingQrBitmap != null && pairingServerInfo != null) {
                                    Box(modifier = Modifier.size(180.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(8.dp)) {
                                        Image(bitmap = pairingQrBitmap!!.asImageBitmap(), contentDescription = "${selectedProvider.displayName} pairing QR code", modifier = Modifier.fillMaxSize())
                                    }
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = accentColor, strokeWidth = 3.dp)
                                }
                            }
                            pairingServerInfo?.let { info ->
                                Text(info.url, style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                            Spacer(Modifier.height(20.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IntegrationButton(text = if (isConnecting) "Connecting..." else "Close", onClick = onDismiss, enabled = !isConnecting, modifier = Modifier.width(120.dp), focusRequester = focusRequester)
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
''')
text += '''\n\nprivate fun openExternalUrl(context: android.content.Context, url: String) {\n    runCatching {\n        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))\n    }\n}\n'''
p.write_text(text)
