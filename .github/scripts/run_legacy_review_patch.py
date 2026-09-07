from pathlib import Path

source_path = Path('.github/scripts/legacy_review_patch.py')
source = source_path.read_text()
start_marker = '''replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsScreen.kt",
    "                        label = if (resumePlaybackId != null)'''
start = source.index(start_marker)
end = source.index('\n\n# PR #58:', start)
replacement = r'''sub(
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsScreen.kt",
    r'(ExpandableIconButton\(\s*label = )if \(resumePlaybackId != null\) "Resume" else "Play Movie"(,\s*icon = Icons\.Default\.PlayArrow,\s*modifier = Modifier\.focusRequester\(firstButtonFocusRequester\),\s*onClick = \{\s*)pendingPlaybackId = streamId',
    r'\1if (!state.isResumeStateReady) "Loading…" else if (resumePlaybackId != null) "Resume" else "Play Movie"\2if (!state.isResumeStateReady) return@ExpandableIconButton\n                            pendingPlaybackId = streamId',
    flags=re.S,
)
'''
exec(compile(source[:start] + replacement + source[end:], str(source_path), 'exec'))
