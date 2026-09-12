from pathlib import Path

path = Path("app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt")
text = path.read_text()
old = '''    val displayState = remember(tab, state.rows, state.mixedRows, state.heroRow, state.history, state.seriesNextUp) {
        if (tab != DashboardTab.HOME) {
            state
        } else {
            val hiddenIds = buildSet {
                state.history
                    .asSequence()
                    .filter { it.watched && it.type == "movie" }
                    .mapTo(this) { it.id }
                state.seriesNextUp
                    .asSequence()
                    .filter { it.isComplete }
                    .mapTo(this) { it.seriesId }
            }
'''
new = '''    val displayState = remember(tab, state.rows, state.mixedRows, state.heroRow, state.watchedIds) {
        if (tab != DashboardTab.HOME) {
            state
        } else {
            // Home-only rule: once a movie or any episode of a series is watched,
            // remove that title from Home catalog/hero lists immediately. The
            // Continue Watching data remains untouched and manages itself from history.
            val hiddenIds = state.watchedIds
'''
count = text.count(old)
if count != 1:
    raise RuntimeError(f"Expected one Home filtering block, found {count}")
path.write_text(text.replace(old, new, 1))
