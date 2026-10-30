buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            // Security-only build-tool overrides. These dependencies are pulled by
            // AGP/Jetifier/bundletool, not packaged into the Android application.
            "org.jdom:jdom2:2.0.6.1",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86"
        )
        // Same Guava rule as patchGuava() below: buildscript can't call script functions.
        resolutionStrategy.eachDependency {
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                val version = requested.version ?: return@eachDependency
                val flavor = version.substringAfter('-', "jre")
                val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
                val (major, minor, patch) = (parts + listOf(0, 0, 0)).take(3)
                val old = major < 33 || (major == 33 && (minor < 7 || (minor == 7 && patch < 2)))
                if (old) {
                    useVersion("33.7.2-$flavor")
                    because("CVE-2026-102554")
                }
            }
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Apply the new compose plugin
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    // Hilt (still manual as it is not in the plugin block of toml yet)
    // 2.59.2+ is required for the Hilt Gradle plugin to work with AGP 9.
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false

    // KSP (Kotlin Symbol Processing) — replaces kapt for Room/Hilt annotation processing.
    // kapt doesn't support Kotlin 2.4.x's metadata format, and JetBrains has been
    // deprecating kapt in favor of KSP anyway.
    alias(libs.plugins.ksp) apply false
}

// Guava < 33.7.2 allocates unbounded memory when deserializing CompactHashMap/
// CompactHashSet/MapMakerInternalMap (CVE-2026-102554). Raise any older Guava, keeping
// its -android/-jre flavor so app code keeps the Android build.
fun ResolutionStrategy.patchGuava() = eachDependency {
    if (requested.group == "com.google.guava" && requested.name == "guava") {
        val version = requested.version ?: return@eachDependency
        val flavor = version.substringAfter('-', "jre")
        val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val (major, minor, patch) = (parts + listOf(0, 0, 0)).take(3)
        val old = major < 33 || (major == 33 && (minor < 7 || (minor == 7 && patch < 2)))
        if (old) {
            useVersion("33.7.2-$flavor")
            because("CVE-2026-102554")
        }
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            // Android SDK/AGP tooling resolves these again through internal project
            // configurations, so enforce the patched versions there as well.
            "org.jdom:jdom2:2.0.6.1",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86"
        )
        resolutionStrategy.patchGuava()
    }
}
