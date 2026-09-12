from pathlib import Path

p = Path('app/src/main/java/com/hereliesaz/illumera/ui/home/InfiniteLoopRow.kt')
text = p.read_text()
text = text.replace('mediaItem = enriched ?: item,', 'mediaItem = item,')
p.write_text(text)

p = Path('app/src/main/java/com/hereliesaz/illumera/ui/player/base/BasePlayerScaffold.kt')
text = p.read_text()
text = text.replace(
    '''        onToggleSourceExcluded = { stream ->
            val sourceId = stream.addonTransportUrl ?: stream.url ?: return@GlassSidebar
            onToggleSourceExcluded(sourceId)
        },''',
    '''        onToggleSourceExcluded = { stream ->
            val sourceId = stream.addonTransportUrl ?: stream.url
            if (sourceId != null) onToggleSourceExcluded(sourceId)
        },'''
)
p.write_text(text)
