from pathlib import Path

p = Path('.ci/patch-round2.py')
text = p.read_text()
old = '''replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt",
    "                    state = state,\\n",
    "                    state = displayState,\\n"
)
'''
new = '''replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt",
    "                    infoTopPadding = infoTopPadding,\\n                    startPadding = startPadding,\\n                    isTopNav = isTopNav,\\n                    state = state,\\n",
    "                    infoTopPadding = infoTopPadding,\\n                    startPadding = startPadding,\\n                    isTopNav = isTopNav,\\n                    state = displayState,\\n"
)
'''
if old not in text:
    raise SystemExit('round2 Home replacement target not found')
p.write_text(text.replace(old, new, 1))
