from pathlib import Path

path = Path("app/src/main/java/com/hereliesaz/illumera/ui/addons/AddonsScreen.kt")
text = path.read_text()

old = '''    var selectedAddon by remember { mutableStateOf<AddonEntity?>(null) }
    var showRemotePaste by remember { mutableStateOf(false) }
    var reorderingAddon by remember { mutableStateOf<AddonEntity?>(null) }'''
new = '''    var selectedAddon by remember { mutableStateOf<AddonEntity?>(null) }
    var showRemotePaste by remember { mutableStateOf(false) }
    var showCatalogBrowser by remember { mutableStateOf(false) }
    var remoteConfigurationUrl by remember { mutableStateOf<String?>(null) }
    var remoteConfigurationName by remember { mutableStateOf<String?>(null) }
    var reorderingAddon by remember { mutableStateOf<AddonEntity?>(null) }'''
if text.count(old) != 1:
    raise SystemExit("state anchor mismatch")
text = text.replace(old, new)

old = '''        // 1. INSTALLATION BAR
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {'''
new = '''        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            VoidButton(
                text = "Browse Stremio Addons",
                onClick = {
                    showCatalogBrowser = true
                    viewModel.loadCatalog()
                },
                modifier = Modifier.width(240.dp).then(goBackModifier).then(upBlockModifier)
            )
            Text(
                "Official and Community addon catalogs",
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 1. INSTALLATION BAR
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {'''
if text.count(old) != 1:
    raise SystemExit("installation anchor mismatch")
text = text.replace(old, new)

old = '''                onClick = { showRemotePaste = true },'''
new = '''                onClick = {
                    remoteConfigurationUrl = null
                    remoteConfigurationName = null
                    showRemotePaste = true
                },'''
if text.count(old) != 1:
    raise SystemExit("remote paste button anchor mismatch")
text = text.replace(old, new)

old = '''    // 0. REMOTE PASTE DIALOG
    if (showRemotePaste) {
        RemotePasteDialog(
            onDismissRequest = { showRemotePaste = false },
            onUrlReceived = { url ->
                urlInput = url
                showRemotePaste = false
                kotlinx.coroutines.MainScope().launch {
                    delay(150)
                    installButtonFocus.requestFocus()
                }
            }
        )
    }
'''
new = '''    if (showCatalogBrowser) {
        AddonCatalogDialog(
            state = state,
            onDismissRequest = { showCatalogBrowser = false },
            onLoad = { viewModel.loadCatalog() },
            onSourceSelected = viewModel::selectCatalogSource,
            onRetry = viewModel::retryCatalog,
            onInstall = { item ->
                showCatalogBrowser = false
                viewModel.prepareInstall(item.transportUrl)
            },
            onConfigure = { item ->
                val configureUrl = item.configureUrl
                if (configureUrl != null) {
                    showCatalogBrowser = false
                    remoteConfigurationUrl = configureUrl
                    remoteConfigurationName = item.manifest.name
                    showRemotePaste = true
                }
            }
        )
    }

    // 0. REMOTE PASTE / ADDON CONFIGURATION DIALOG
    if (showRemotePaste) {
        RemotePasteDialog(
            onDismissRequest = {
                showRemotePaste = false
                remoteConfigurationUrl = null
                remoteConfigurationName = null
            },
            onUrlReceived = { url ->
                urlInput = url
                showRemotePaste = false
                remoteConfigurationUrl = null
                remoteConfigurationName = null
                kotlinx.coroutines.MainScope().launch {
                    delay(150)
                    installButtonFocus.requestFocus()
                }
            },
            configurationUrl = remoteConfigurationUrl,
            addonName = remoteConfigurationName
        )
    }
'''
if text.count(old) != 1:
    raise SystemExit("remote paste dialog anchor mismatch")
text = text.replace(old, new)

path.write_text(text)
