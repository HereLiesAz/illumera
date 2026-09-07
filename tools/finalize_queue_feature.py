from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing anchor: {label}")
    return text.replace(old, new, 1)

root = Path(__file__).resolve().parents[1]

# Add an actual per-episode queue action to the episode browser.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/details/GlassSidebar.kt"
s = p.read_text()
s = replace_once(s,
    'import androidx.compose.material.icons.filled.ArrowDropDown\n',
    'import androidx.compose.material.icons.filled.ArrowDropDown\nimport androidx.compose.material.icons.filled.Add\n',
    'add icon import')
s = replace_once(s,
    '    onToggleWatched: (MetaVideo) -> Unit = {},\n    onEpisodeSelected: (MetaVideo) -> Unit,',
    '    onToggleWatched: (MetaVideo) -> Unit = {},\n    onQueueEpisode: (MetaVideo) -> Unit = {},\n    onEpisodeSelected: (MetaVideo) -> Unit,',
    'sidebar callback')
s = replace_once(s,
    '                    onToggleWatched = onToggleWatched,\n                    focusRequester = focusRequester,',
    '                    onToggleWatched = onToggleWatched,\n                    onQueueEpisode = onQueueEpisode,\n                    focusRequester = focusRequester,',
    'episodes callback pass')
s = replace_once(s,
    '    onToggleWatched: (MetaVideo) -> Unit = {},\n    focusRequester: FocusRequester,',
    '    onToggleWatched: (MetaVideo) -> Unit = {},\n    onQueueEpisode: (MetaVideo) -> Unit = {},\n    focusRequester: FocusRequester,',
    'episodes content callback')
s = replace_once(s,
    '                        onToggleWatched = { onToggleWatched(ep) },\n                        thumbnailModifier = mod,',
    '                        onToggleWatched = { onToggleWatched(ep) },\n                        onQueue = { onQueueEpisode(ep) },\n                        thumbnailModifier = mod,',
    'episode item queue pass')
s = replace_once(s,
    '    onToggleWatched: () -> Unit = {},\n    thumbnailModifier: Modifier = Modifier,',
    '    onToggleWatched: () -> Unit = {},\n    onQueue: () -> Unit = {},\n    thumbnailModifier: Modifier = Modifier,',
    'episode item callback')
s = replace_once(s,
    '    val buttonRequester = remember { FocusRequester() }\n',
    '    val buttonRequester = remember { FocusRequester() }\n    val queueRequester = remember { FocusRequester() }\n',
    'queue focus requester')
s = replace_once(s,
    '.focusProperties { left = FocusRequester.Cancel; right = buttonRequester }',
    '.focusProperties { left = FocusRequester.Cancel; right = queueRequester }',
    'thumbnail right focus')
watched = '''            WatchedToggleButton(
                isWatched = isWatched,
                isFocused = buttonFocused,
                focusRequester = buttonRequester,
                thumbnailRequester = thumbnailRequester,
                onFocusChanged = { buttonFocused = it },
                onClick = onToggleWatched,
                modifier = Modifier.align(Alignment.TopEnd)
            )
'''
buttons = '''            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onQueue,
                    modifier = Modifier
                        .size(28.dp)
                        .focusRequester(queueRequester)
                        .focusProperties { left = thumbnailRequester; right = buttonRequester }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add episode to queue", tint = Color.White)
                }
                WatchedToggleButton(
                    isWatched = isWatched,
                    isFocused = buttonFocused,
                    focusRequester = buttonRequester,
                    thumbnailRequester = queueRequester,
                    onFocusChanged = { buttonFocused = it },
                    onClick = onToggleWatched
                )
            }
'''
s = replace_once(s, watched, buttons, 'episode action row')
p.write_text(s)

# Wire the per-episode callback and resume-aware queue autoplay from DetailsScreen.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsScreen.kt"
s = p.read_text()
s = replace_once(s,
    '                        } ?: firstEpisode\n                        if (requestedEpisode != null) {',
    '                        } ?: resumeEpisode ?: firstEpisode\n                        if (requestedEpisode != null) {',
    'resume-aware queue autoplay')
sidebar_anchor = '''            onToggleWatched = { episode -> viewModel.toggleEpisodeWatched(episode) },
            onDismiss = { viewModel.closeSidebar() },
            onBack = { viewModel.goBackInSidebar() },
            onEpisodeSelected = { episode ->
'''
sidebar_insert = '''            onToggleWatched = { episode -> viewModel.toggleEpisodeWatched(episode) },
            onQueueEpisode = { episode ->
                onAddToQueue(
                    QueueItem(
                        id = episodePlaybackId(streamId, episode),
                        type = "episode",
                        title = episodeDisplayTitle(episode),
                        poster = movie?.poster,
                        seriesId = streamId,
                        season = episode.season,
                        episode = episode.episode
                    )
                )
            },
            onDismiss = { viewModel.closeSidebar() },
            onBack = { viewModel.goBackInSidebar() },
            onEpisodeSelected = { episode ->
'''
s = replace_once(s, sidebar_anchor, sidebar_insert, 'details episode queue callback')
p.write_text(s)

print('final queue integration applied')
