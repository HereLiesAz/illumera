from pathlib import Path

path = Path('.ci/finish-production-hardening.py')
text = path.read_text()

replacements = {
    'start = "    private fun refreshResumeState(meta: MetaItem) {"': 'start = "    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {"',
    'end = "\\n    private suspend fun buildEpisodeProgressMap"': 'end = "\\n    private suspend fun resolveSeriesResumePlaybackId("',
    '"DetailsViewModel.refreshResumeState"': '"DetailsViewModel.refreshResumeStateIfNeeded"',
    'private fun refreshResumeState(meta: MetaItem) {\n    _state.value = _state.value.copy(isResumeStateReady = false)': '''private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
    if (meta == null) {
        if (_state.value.resumePlaybackId != null) {
            _state.value = _state.value.copy(resumePlaybackId = null)
        }
        return
    }

    _state.value = _state.value.copy(isResumeStateReady = false)''',
}

for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected one transformer fragment, found {count}: {old[:80]}')
    text = text.replace(old, new, 1)

path.write_text(text)
print('Hardening transformer repaired for refreshResumeStateIfNeeded.')
