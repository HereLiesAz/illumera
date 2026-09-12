from pathlib import Path

path = Path("app/src/main/java/com/hereliesaz/illumera/ui/addons/AddonsScreen.kt")
text = path.read_text()

old = '''            Text(
                "Official and Community addon catalogs",
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.bodySmall
            )'''
new = '''            Text(
                "Official, Community, and saved addon collections",
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.bodySmall
            )'''
if text.count(old) != 1:
    raise SystemExit("catalog description anchor mismatch")
text = text.replace(old, new)

old = '''            onLoad = { viewModel.loadCatalog() },
            onSourceSelected = viewModel::selectCatalogSource,
            onRetry = viewModel::retryCatalog,'''
new = '''            onLoad = { viewModel.loadCatalog() },
            onSourceSelected = viewModel::selectCatalogSource,
            onAddCollection = viewModel::addCollection,
            onCollectionSelected = viewModel::selectCollection,
            onCollectionRemoved = viewModel::removeCollection,
            onRetry = viewModel::retryCatalog,'''
if text.count(old) != 1:
    raise SystemExit("catalog callback anchor mismatch")
text = text.replace(old, new)

path.write_text(text)
