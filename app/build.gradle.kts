import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // Apply the Compose Compiler plugin
    alias(libs.plugins.kotlin.compose)

    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)

}

// Read ACRA config from local.properties (keeps secrets out of version control)
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}
// Falls back to environment variables (CI, see .github/workflows/release.yml) so
// release builds still submit crash reports even without local.properties.
val acraUrl: String = localProperties.getProperty("acra.url")
    ?: System.getenv("ACRA_URL") ?: ""
val acraToken: String = localProperties.getProperty("acra.token")
    ?: System.getenv("ACRA_TOKEN") ?: ""
val tmdbApiKey: String = localProperties.getProperty("tmdb.api_key", "")
// Falls back to environment variables (CI, see .github/workflows/release.yml) so
// release builds still get real Trakt credentials even without local.properties.
val traktClientId: String = localProperties.getProperty("TRAKT_CLIENT_ID")
    ?: System.getenv("TRAKT_CLIENT_ID") ?: ""
val traktClientSecret: String = localProperties.getProperty("TRAKT_CLIENT_SECRET")
    ?: System.getenv("TRAKT_CLIENT_SECRET") ?: ""

// Release signing. Prefers local.properties (local dev) then falls back to environment
// variables (CI, see .github/workflows/release.yml) — never committed either way.
fun releaseSigningProp(propKey: String, envKey: String): String =
    localProperties.getProperty(propKey) ?: System.getenv(envKey) ?: ""

val releaseStoreFile = releaseSigningProp("release.storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProp("release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProp("release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProp("release.keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseKeystore = releaseStoreFile.isNotBlank() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank() &&
    rootProject.file(releaseStoreFile).exists()

// Fallback used only when no real release keystore is configured (see ci/README.md).
// Deliberately NOT AGP's implicit `debug` signingConfig: that keystore is generated
// per-machine on first use, so every ephemeral CI runner would get a different one —
// breaking Android's same-signer upgrade requirement (and AppUpdateManager's signature
// check) between consecutive releases. This fixed, checked-in keystore keeps every
// debug-signed CI build on the same signing identity until a real keystore is added.
val ciDebugKeystore = rootProject.file("ci/ci-debug.keystore")
val ciDebugKeystorePassword = "illumera-ci-debug"
val ciDebugKeystoreAlias = "illumera-ci-debug"

android {
    namespace = "com.hereliesaz.illumera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.illumera"
        minSdk = 26
        targetSdk = 37
        // The release workflow overrides these from the pushed tag (-PversionNameOverride)
        // and the GitHub Actions run number (-PversionCodeOverride) so a published release
        // actually reports the version it was tagged as, and versionCode keeps increasing —
        // Android's package installer rejects an upgrade whose versionCode doesn't increase.
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 12
        versionName = (project.findProperty("versionNameOverride") as String?) ?: "0.6.0"

        // GitHub repository for auto-update system
        buildConfigField("String", "GITHUB_OWNER", "\"HereLiesAz\"")
        buildConfigField("String", "GITHUB_REPO", "\"illumera\"")

        // ACRA crash reporting (loaded from local.properties)
        buildConfigField("String", "ACRA_URL", "\"$acraUrl\"")
        buildConfigField("String", "ACRA_TOKEN", "\"$acraToken\"")

        // TMDB API (loaded from local.properties)
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")

        // Trakt API (loaded from local.properties)
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktClientSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else {
                storeFile = ciDebugKeystore
                storePassword = ciDebugKeystorePassword
                keyAlias = ciDebugKeystoreAlias
                keyPassword = ciDebugKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            resValue("string", "app_name", "illumera Test")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real keystore when RELEASE_STORE_FILE etc. are configured (see
            // ci/README.md), otherwise the fixed ci/ci-debug.keystore fallback so
            // `assembleRelease` still produces an installable, consistently-signed
            // APK without secrets.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
    }
}

// Compose Compiler configuration for optimal performance.
// Strong skipping mode and intrinsic remember are enabled by default in current
// Compose Compiler versions, so those flags (removed from the DSL) no longer apply.
composeCompiler {
    // Stability configuration: tells the compiler which classes are effectively immutable
    // so it can skip recomposition when their instances haven't changed
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability_config.conf"))
}

dependencies {
    // 0. ASS/SSA subtitle renderer
    implementation(project(":assrender"))

    // 1. Android TV UI (Compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)


    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // 2. Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    // Pin explicitly: older converter-gson releases transitively pulled a very old Gson
    // (2.8.5, which predates JsonParser.parseString and other APIs this project uses
    // directly) since nothing else in the graph forced a newer version.
    implementation(libs.gson)
    implementation(libs.okhttp.logging.interceptor)

    // 4. Image Loading
    implementation(libs.coil.compose)

    // 5. Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.compose.animation.core)
    ksp(libs.room.compiler)

    // 6. Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // 7. Video Player
    implementation(project(":playbackcore"))
    implementation(files("../playbackcore/libs/lib-exoplayer-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-av1-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-ffmpeg-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-iamf-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-mpegh-release.aar"))

    // 8. Testing & Debugging
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // OkHttp is already available via Retrofit, but declare explicitly for TorrServer API
    implementation(libs.okhttp)

    // --- LOCAL WEB SERVER (used by remote input hub) ---
    implementation(libs.nanohttpd)

    // --- QR CODE GENERATION ---
    implementation(libs.zxing.core)

    // --- ENCRYPTED SHARED PREFERENCES ---
    implementation(libs.androidx.security.crypto)

    // --- CRASH REPORTING (ACRA) ---
    implementation(libs.acra.http)
    implementation(libs.acra.toast)

}
