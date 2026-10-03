import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}

fun localOrEnv(property: String, environment: String): String =
    localProperties.getProperty(property) ?: System.getenv(environment) ?: ""

fun buildConfigString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

// Crash/ANR reports go to the HereLiesAz/workflows gateway, which files them as issues
// on this repo. The token ships in a public APK; it only filters stray traffic.
val acraUrl = localOrEnv("acra.url", "ACRA_URL")
    .ifBlank { "https://workflows.hereliesaz.workers.dev/crash-report/illumera" }
val acraToken = localOrEnv("acra.token", "ACRA_TOKEN").ifBlank { "illumera-crash-reports-v1" }
val tmdbApiKey = localOrEnv("tmdb.api_key", "TMDB_API_KEY")
val traktClientId = localOrEnv("TRAKT_CLIENT_ID", "TRAKT_CLIENT_ID")
val traktClientSecret = localOrEnv("TRAKT_CLIENT_SECRET", "TRAKT_CLIENT_SECRET")

fun releaseSigningProp(propKey: String, envKey: String): String = localOrEnv(propKey, envKey)

// Signing arrives from HereLiesAz/workflows android-release as the four standard variables.
val releaseStoreFile = releaseSigningProp("release.storeFile", "KEYSTORE_FILE")
val releaseStorePassword = releaseSigningProp("release.storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSigningProp("release.keyAlias", "KEY_ALIAS")
val releaseKeyPassword = releaseSigningProp("release.keyPassword", "KEY_PASSWORD")
// Release versions come from HereLiesAz/workflows android-release as -PversionCode/-PversionName
// (Play's highest code + 1). Local builds fall back to the last published pair in version.properties.
val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: versionProperties.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("versionName") as String?)
    ?: versionProperties.getProperty("versionName")?.trim() ?: "0.0.0.0"

val hasReleaseKeystore = releaseStoreFile.isNotBlank() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank() &&
    rootProject.file(releaseStoreFile).isFile

android {
    namespace = "com.hereliesaz.illumera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.illumera"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GITHUB_OWNER", buildConfigString("HereLiesAz"))
        buildConfigField("String", "GITHUB_REPO", buildConfigString("illumera"))
        buildConfigField("boolean", "ENABLE_SELF_UPDATE", "true")
        buildConfigField("boolean", "USE_PLAY_UPDATES", "false")
        // Automatic crash/ANR reporting. On by default for the GitHub (release) build
        // until illumera reaches stable production; the user can opt out in Settings.
        buildConfigField("boolean", "CRASH_REPORTING_AVAILABLE", "false")

        buildConfigField("String", "ACRA_URL", buildConfigString(acraUrl))
        buildConfigField("String", "ACRA_TOKEN", buildConfigString(acraToken))
        buildConfigField("String", "TMDB_API_KEY", buildConfigString(tmdbApiKey))
        buildConfigField("String", "TRAKT_CLIENT_ID", buildConfigString(traktClientId))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", buildConfigString(traktClientSecret))
        // Soundtrack addon (HereLiesAz/stremio-soundtrack) serving per-title song lists.
        buildConfigField("String", "SOUNDTRACK_ADDON_URL", buildConfigString("https://stremio-soundtrack.hereliesaz.workers.dev"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            resValue("string", "app_name", "illumera Test")
        }
        release {
            buildConfigField("boolean", "CRASH_REPORTING_AVAILABLE", "true")
            // Bundles native debug symbols into the AAB so Play symbolicates native crashes/ANRs.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ENABLE_SELF_UPDATE", "false")
            buildConfigField("boolean", "USE_PLAY_UPDATES", "true")
            buildConfigField("boolean", "CRASH_REPORTING_AVAILABLE", "false")
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Release/Play artifacts must be installable. Debug builds remain credential-free, but
// assembling or bundling a distributable artifact without the release keystore is an error,
// not a silently-successful unsigned APK/AAB.
val signingRequiredTasks = setOf("assembleRelease", "bundleRelease", "assemblePlay", "bundlePlay")
gradle.taskGraph.whenReady(Action<org.gradle.api.execution.TaskExecutionGraph> {
    val requiresSigning = allTasks.any { task ->
        task.project.path == project.path && task.name in signingRequiredTasks
    }
    if (requiresSigning && !hasReleaseKeystore) {
        throw GradleException(
            "Release signing credentials are required. Configure release.storeFile, " +
                "release.storePassword, release.keyAlias, and release.keyPassword " +
                "(or KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD)."
        )
    }
})

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability_config.conf"))
}

dependencies {
    implementation(project(":assrender"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.coil.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.compose.animation.core)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)

    implementation(project(":playbackcore"))
    implementation(files("../playbackcore/libs/lib-exoplayer-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-av1-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-ffmpeg-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-iamf-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-mpegh-release.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Robolectric 4.16.1 requests bcprov 1.81. Pin to latest patched release.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    // Google Play-distributed builds use Play's native in-app update UI.
    // Kept in the shared artifact so the common update coordinator compiles;
    // BuildConfig.USE_PLAY_UPDATES ensures GitHub builds never invoke it.
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation(libs.androidx.security.crypto)
    implementation(libs.acra.http)
    implementation(libs.acra.toast)
}