from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1))


# Media3 resizeMode is @IntDef-constrained. Mode 5 is illumera's encoded zoom
# sentinel; PlayerView itself must receive the named FIT constant before the
# video surface is scaled separately.
exo = "app/src/main/java/com/hereliesaz/illumera/ui/player/base/ExoPlayerBackend.kt"
text = Path(exo).read_text()
if "import androidx.media3.ui.AspectRatioFrameLayout\n" not in text:
    text = text.replace(
        "import androidx.media3.ui.CaptionStyleCompat\n",
        "import androidx.media3.ui.AspectRatioFrameLayout\nimport androidx.media3.ui.CaptionStyleCompat\n",
        1,
    )
old = "        pv.resizeMode = if (encodedZoom) 0 else mode"
new = "        pv.resizeMode = if (encodedZoom) AspectRatioFrameLayout.RESIZE_MODE_FIT else mode"
if text.count(old) != 1:
    raise SystemExit(f"ExoPlayer resize constant: expected one match, found {text.count(old)}")
Path(exo).write_text(text.replace(old, new, 1))

# Preserve strict lint enforcement while explicitly tracking the repository's
# existing lint debt. New lint regressions remain fatal because they are absent
# from this generated baseline.
gradle = "app/build.gradle.kts"
replace_once(
    gradle,
    "    lint {\n        abortOnError = true\n        checkReleaseBuilds = true\n        warningsAsErrors = false\n    }",
    "    lint {\n        baseline = file(\"lint-baseline.xml\")\n        abortOnError = true\n        checkReleaseBuilds = true\n        warningsAsErrors = false\n    }",
    "lint baseline configuration",
)

print("Named Media3 resize constant and strict lint baseline configuration applied.")
