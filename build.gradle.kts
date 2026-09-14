buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            // Security-only build-tool overrides. These dependencies are pulled by
            // AGP/Jetifier/bundletool, not packaged into the Android application.
            "org.jdom:jdom2:2.0.6.1",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6"
        )
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Apply the new compose plugin
    alias(libs.plugins.kotlin.compose) apply false

    // Hilt (still manual as it is not in the plugin block of toml yet)
    // 2.59.2+ is required for the Hilt Gradle plugin to work with AGP 9.
    alias(libs.plugins.hilt) apply false

    // KSP (Kotlin Symbol Processing) — replaces kapt for Room/Hilt annotation processing.
    // kapt doesn't support Kotlin 2.4.x's metadata format, and JetBrains has been
    // deprecating kapt in favor of KSP anyway.
    alias(libs.plugins.ksp) apply false
}
