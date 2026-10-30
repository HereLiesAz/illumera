// Applies Illumera's patches to a vendored submodule before its sources are compiled.
// Usage: extra["vendorPatch"] = listOf("<submodule dir>", "<patch file>"), then apply(from = ...).
// Idempotent: a patch that is already applied is left alone; one that neither applies nor
// reverse-applies (upstream moved under it) fails the build instead of shipping unpatched.
val vendorPatch = (extra["vendorPatch"] as List<*>).map { rootProject.file(it as String) }
val vendorDir = vendorPatch[0]
val patchFile = vendorPatch[1]

fun git(vararg args: String): Int = ProcessBuilder("git", "-C", vendorDir.path, *args)
    .redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor() }

when {
    git("apply", "--reverse", "--check", patchFile.path) == 0 -> Unit // already applied
    git("apply", "--check", patchFile.path) == 0 ->
        check(git("apply", patchFile.path) == 0) { "Failed to apply ${patchFile.name}" }
    else -> error("${patchFile.name} no longer applies to ${vendorDir.name}; update the patch")
}
