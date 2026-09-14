from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Profile onboarding UI
# ---------------------------------------------------------------------------
profile_screen = "app/src/main/java/com/hereliesaz/illumera/ui/profiles/ProfileScreen.kt"

replace_once(
    profile_screen,
    "package com.hereliesaz.illumera.ui.profiles\n\nimport androidx.activity.compose.BackHandler",
    "package com.hereliesaz.illumera.ui.profiles\n\nimport android.graphics.Bitmap\nimport androidx.activity.compose.BackHandler"
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
    "import androidx.compose.material3.Checkbox\nimport androidx.compose.material3.Icon\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Text\n"
)
replace_once(
    profile_screen,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.asImageBitmap\n"
)
replace_once(
    profile_screen,
    "import com.hereliesaz.illumera.ui.util.rememberDialogWidth\nimport com.hereliesaz.illumera.ui.util.rememberIsTvDevice\n",
    "import com.hereliesaz.illumera.remote_input.IntegrationServerManager\nimport com.hereliesaz.illumera.remote_input.ServerInfo\nimport com.hereliesaz.illumera.ui.util.generateQrCodeBitmap\nimport com.hereliesaz.illumera.ui.util.rememberDialogWidth\nimport com.hereliesaz.illumera.ui.util.rememberIsTvDevice\n"
)
replace_once(profile_screen, '"WELCOME TO LUMERA"', '"WELCOME TO ILLUMERA"')

replace_once(
    profile_screen,
    "            onConnect = { email, password, onError ->\n                viewModel.initializeProfileFromScratchWithStremio(setupTarget.id, email, password) { success, message ->",
    "            onConnect = { email, password, useAccountAvatar, onError ->\n                viewModel.initializeProfileFromScratchWithStremio(\n                    setupTarget.id,\n                    email,\n                    password,\n                    useAccountAvatar\n                ) { success, message ->"
)

replace_once(
    profile_screen,
    "    onSkip: () -> Unit,\n    onConnect: (email: String, password: String, onError: (String) -> Unit) -> Unit,\n    onDismiss: () -> Unit\n) {\n    var email by remember { mutableStateOf(\"\") }\n    var password by remember { mutableStateOf(\"\") }\n    var errorMessage by remember { mutableStateOf<String?>(null) }\n",
    "    onSkip: () -> Unit,\n    onConnect: (email: String, password: String, useAccountAvatar: Boolean, onError: (String) -> Unit) -> Unit,\n    onDismiss: () -> Unit\n) {\n    var email by remember { mutableStateOf(\"\") }\n    var password by remember { mutableStateOf(\"\") }\n    var useAccountAvatar by remember { mutableStateOf(false) }\n    var errorMessage by remember { mutableStateOf<String?>(null) }\n    val isTvDevice = rememberIsTvDevice()\n    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }\n    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }\n    val serverManager = remember { IntegrationServerManager() }\n    val scope = rememberCoroutineScope()\n    val currentOnConnect by rememberUpdatedState(onConnect)\n    val currentUseAccountAvatar by rememberUpdatedState(useAccountAvatar)\n\n    LaunchedEffect(isTvDevice) {\n        if (isTvDevice) {\n            val info = serverManager.startServer { receivedEmail, receivedPassword ->\n                scope.launch {\n                    currentOnConnect(\n                        receivedEmail,\n                        receivedPassword,\n                        currentUseAccountAvatar\n                    ) { errorMessage = it }\n                }\n            }\n            if (info != null) {\n                serverInfo = info\n                qrBitmap = generateQrCodeBitmap(info.url)\n            }\n        }\n    }\n\n    DisposableEffect(Unit) {\n        onDispose { serverManager.stopServer() }\n    }\n"
)

replace_once(
    profile_screen,
    "                            onConnect(email, password) { errorMessage = it }",
    "                            onConnect(email, password, useAccountAvatar) { errorMessage = it }"
)
replace_once(
    profile_screen,
    "                    text = if (isLoading) \"Connecting…\" else \"Connect\",\n                    onClick = { onConnect(email, password) { errorMessage = it } },",
    "                    text = if (isLoading) \"Connecting…\" else \"Connect\",\n                    onClick = { onConnect(email, password, useAccountAvatar) { errorMessage = it } },"
)

replace_once(
    profile_screen,
    "                if (errorMessage != null) {\n",
    "                Spacer(Modifier.height(16.dp))\n                Row(\n                    modifier = Modifier\n                        .fillMaxWidth()\n                        .clip(RoundedCornerShape(8.dp))\n                        .clickable(enabled = !isLoading) { useAccountAvatar = !useAccountAvatar }\n                        .padding(vertical = 6.dp),\n                    verticalAlignment = Alignment.CenterVertically\n                ) {\n                    Checkbox(\n                        checked = useAccountAvatar,\n                        onCheckedChange = { if (!isLoading) useAccountAvatar = it },\n                        enabled = !isLoading\n                    )\n                    Spacer(Modifier.width(8.dp))\n                    Column {\n                        Text(\n                            text = \"Use my Stremio account avatar if available\",\n                            style = MaterialTheme.typography.bodyMedium,\n                            color = Color.White\n                        )\n                        Text(\n                            text = \"This also uses a linked Facebook photo when Stremio provides one.\",\n                            style = MaterialTheme.typography.bodySmall,\n                            color = Color.Gray\n                        )\n                    }\n                }\n\n                if (errorMessage != null) {\n"
)

replace_once(
    profile_screen,
    "                Spacer(Modifier.height(12.dp))\n                VoidButton(\n                    text = \"Skip for now\",",
    "                if (isTvDevice) {\n                    Spacer(Modifier.height(20.dp))\n                    Text(\n                        text = \"Or sign in with your phone\",\n                        style = MaterialTheme.typography.titleMedium,\n                        color = Color.White\n                    )\n                    Spacer(Modifier.height(10.dp))\n                    val info = serverInfo\n                    val qr = qrBitmap\n                    if (info != null && qr != null) {\n                        Box(\n                            modifier = Modifier\n                                .size(150.dp)\n                                .clip(RoundedCornerShape(6.dp))\n                                .background(Color.White)\n                                .padding(4.dp)\n                        ) {\n                            Image(\n                                bitmap = qr.asImageBitmap(),\n                                contentDescription = \"Open Stremio sign-in on phone\",\n                                modifier = Modifier.fillMaxSize()\n                            )\n                        }\n                        Spacer(Modifier.height(8.dp))\n                        Text(\n                            text = \"Scan the QR code and enter your Stremio email and password on your phone.\",\n                            style = MaterialTheme.typography.bodySmall,\n                            color = Color.Gray\n                        )\n                    } else {\n                        Text(\n                            text = \"Starting phone sign-in…\",\n                            style = MaterialTheme.typography.bodySmall,\n                            color = Color.Gray\n                        )\n                    }\n                }\n\n                Spacer(Modifier.height(12.dp))\n                VoidButton(\n                    text = \"Skip for now\","
)

# ---------------------------------------------------------------------------
# Profile setup behavior: optionally adopt the account avatar after Stremio login.
# ---------------------------------------------------------------------------
profile_vm = "app/src/main/java/com/hereliesaz/illumera/ui/profiles/ProfileViewModel.kt"
replace_once(
    profile_vm,
    "import com.hereliesaz.illumera.data.debrid.DebridManager\n",
    "import com.hereliesaz.illumera.data.auth.StremioAuthManager\nimport com.hereliesaz.illumera.data.debrid.DebridManager\n"
)
replace_once(
    profile_vm,
    "    private val profileMutationCoordinator: ProfileMutationCoordinator,\n    private val debridManager: DebridManager,",
    "    private val profileMutationCoordinator: ProfileMutationCoordinator,\n    private val stremioAuthManager: StremioAuthManager,\n    private val debridManager: DebridManager,"
)
replace_once(
    profile_vm,
    "        email: String,\n        password: String,\n        onResult: (success: Boolean, errorMessage: String?) -> Unit\n",
    "        email: String,\n        password: String,\n        useAccountAvatar: Boolean,\n        onResult: (success: Boolean, errorMessage: String?) -> Unit\n"
)
replace_once(
    profile_vm,
    "                result.fold(\n                    onSuccess = { onResult(true, null) },",
    "                result.fold(\n                    onSuccess = {\n                        resolveSetupAccountAvatarRef(\n                            enabled = useAccountAvatar,\n                            stremioAvatarUrl = stremioAuthManager.getStoredAvatarUrl()\n                        )?.let { avatarRef ->\n                            profileMutationCoordinator.update(profileId) { current ->\n                                current.copy(avatarRef = avatarRef)\n                            }\n                        }\n                        onResult(true, null)\n                    },"
)

# Pure policy helper makes the account-avatar behavior unit-testable without Android UI.
policy_file = ROOT / "app/src/main/java/com/hereliesaz/illumera/ui/profiles/ProfileSetupPolicy.kt"
policy_file.write_text('''package com.hereliesaz.illumera.ui.profiles\n\nimport java.net.URI\n\ninternal fun resolveSetupAccountAvatarRef(\n    enabled: Boolean,\n    stremioAvatarUrl: String?\n): String? {\n    if (!enabled) return null\n    val url = stremioAvatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null\n    val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()\n    if (scheme != \"http\" && scheme != \"https\") return null\n    return ProfileAssets.urlAvatarRef(url)\n}\n''')

test_file = ROOT / "app/src/test/java/com/hereliesaz/illumera/ui/profiles/ProfileSetupPolicyTest.kt"
test_file.parent.mkdir(parents=True, exist_ok=True)
test_file.write_text('''package com.hereliesaz.illumera.ui.profiles\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\n\nclass ProfileSetupPolicyTest {\n    @Test\n    fun disabled_keeps_existing_avatar_choice() {\n        assertNull(resolveSetupAccountAvatarRef(false, \"https://example.com/avatar.jpg\"))\n    }\n\n    @Test\n    fun enabled_wraps_stremio_avatar_as_remote_profile_avatar() {\n        assertEquals(\n            \"url:https://example.com/avatar.jpg\",\n            resolveSetupAccountAvatarRef(true, \"  https://example.com/avatar.jpg  \")\n        )\n    }\n\n    @Test\n    fun missing_avatar_keeps_existing_avatar_choice() {\n        assertNull(resolveSetupAccountAvatarRef(true, null))\n        assertNull(resolveSetupAccountAvatarRef(true, \"   \"))\n    }\n\n    @Test\n    fun non_web_avatar_url_is_rejected() {\n        assertNull(resolveSetupAccountAvatarRef(true, \"file:///tmp/avatar.jpg\"))\n        assertNull(resolveSetupAccountAvatarRef(true, \"javascript:alert(1)\"))\n    }\n}\n''')

# ---------------------------------------------------------------------------
# Keep the regular integrations UI consistent: credentials or phone, no social-login UI.
# ---------------------------------------------------------------------------
integrations = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt"
replace_once(
    integrations,
    '''                            IntegrationButton(\n                                text = "Login with Facebook",\n                                onClick = onLoginWithFacebook,\n                                enabled = !isLoading,\n                                isPrimary = false,\n                                modifier = Modifier.width(180.dp)\n                            )\n                            IntegrationButton(\n                                text = "Sign in with Apple",\n                                onClick = onLoginWithApple,\n                                enabled = !isLoading,\n                                isPrimary = false,\n                                modifier = Modifier.width(170.dp)\n                            )\n''',
    ""
)

# ---------------------------------------------------------------------------
# Branding in both onboarding surfaces.
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/remote_input/IntegrationServer.kt",
    "<h1>Connect Stremio to Lumera</h1>",
    "<h1>Connect Stremio to illumera</h1>"
)

# Documentation should describe the login paths the UI actually exposes.
readme = ROOT / "README.md"
readme_text = readme.read_text()
readme_text = readme_text.replace(
    "Connect your Stremio account — with email/password or Facebook — to instantly",
    "Connect your Stremio account — with email/password on the TV or the phone handoff — to instantly"
)
readme.write_text(readme_text)

arch = ROOT / "docs/ARCHITECTURE.md"
arch_text = arch.read_text()
arch_text = arch_text.replace(
    "Stremio account API — email/password or Facebook login via\nStremio's own hosted OAuth handoff, addon-collection get/set, and two-way",
    "Stremio account API — email/password entered on-device or through the TV-to-phone\nhandoff, addon-collection get/set, and two-way"
)
arch.write_text(arch_text)
