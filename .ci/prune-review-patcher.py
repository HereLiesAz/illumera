from pathlib import Path

path = Path('.ci/apply-recent-review-fixes.py')
text = path.read_text()
marker = '# Final guard: none of the Node-20 action SHAs that motivated this cleanup may remain.'
pos = text.find(marker)
if pos < 0:
    raise SystemExit('Final workflow guard marker not found')
start = text.rfind('# ---------------------------------------------------------------------------', 0, pos)
if start < 0:
    start = pos
path.write_text(text[:start].rstrip() + '\n')
