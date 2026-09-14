from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt"
text = PATH.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match, found {count}: {old[:140]!r}")
    text = text.replace(old, new, 1)


# The Stremio account-management surface should open Stremio's own addon UI,
# not run Illumera's old import/push workflows.
replace_once(
    '''            onSyncAddons = {
                showManagementDialog = false
                viewModel.syncAddons()
            },''',
    '''            onManageAddons = {
                showManagementDialog = false
                externalLink = ExternalLinkOperation(
                    "Manage Stremio Addons",
                    "https://web.stremio.com/#/addons"
                )
            },'''
)

replace_once(
    '''            onPushAddons = {
                showManagementDialog = false
                viewModel.pushAddonsToStremio()
            },
''',
    ''
)

# The old pending-addon import dialog was the second Illumera addon manager.
# It must not remain reachable once Stremio owns addon management.
replace_once(
    '''    // Addon Import Dialog
    state.pendingAddons?.let { addons ->
        com.hereliesaz.illumera.ui.addons.AddonImportDialog(
            addons = addons,
            onDismissRequest = { viewModel.dismissImportDialog() },
            onConfirmImport = { selectedAddons ->
                viewModel.importAddons(selectedAddons)
            }
        )
    }

''',
    ''
)

replace_once(
    '''    onSyncAddons: () -> Unit,
    onSyncLibrary: () -> Unit,
    onPushAddons: () -> Unit,''',
    '''    onManageAddons: () -> Unit,
    onSyncLibrary: () -> Unit,'''
)

replace_once(
    '''                // Sync Addons
                ManagementMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Add New Addons",
                    subtitle = "Import addons from your Stremio account",
                    onClick = onSyncAddons,
                    focusRequester = syncFocusRequester
                )''',
    '''                ManagementMenuItem(
                    icon = Icons.Default.Cloud,
                    title = "Manage Addons in Stremio",
                    subtitle = "Install, configure, reorder, and remove addons in Stremio's official interface",
                    onClick = onManageAddons,
                    focusRequester = syncFocusRequester
                )'''
)

replace_once(
    '''                // Push addon collection back to the account
                ManagementMenuItem(
                    icon = Icons.Default.Cloud,
                    title = "Push Addons to Stremio",
                    subtitle = "Replace your account's addon collection with this device's",
                    onClick = onPushAddons
                )

                Spacer(Modifier.height(12.dp))

''',
    ''
)

PATH.write_text(text)
