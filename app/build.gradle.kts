import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

val acraUrl = localOrEnv("acra.url", "ACRA_URL")
val acraToken = localOrEnv("acra.token", "ACRA_TOKEN")
val tmdbApiKey = localOrEnv("tmdb.api_key", "TMDB_API_KEY")
val traktClientId = localOrEnv("TRAKT_CLIENT_ID", "TRAKT_CLIENT_ID")
val traktClientSecret = localOrEnv("TRAKT_CLIENT_SECRET", "TRAKT_CLIENT_SECRET")

fun releaseSigningProp(propKey: String, envKey: String): String = localOrEnv(propKey, envKey)

val releaseStoreFile = releaseSigningProp("release.storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProp("release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProp("release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProp("release.keyPassword", "RELEASE_KEY_PASSWORD")
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
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 12
        versionName = (project.findProperty("versionNameOverride") as String?) ?: "0.6.0"

        buildConfigField("String", "GITHUB_OWNER", buildConfigString("HereLiesAz"))
        buildConfigField("String", "GITHUB_REPO", buildConfigString("illumera"))
        buildConfigField("boolean", "ENABLE_SELF_UPDATE", "true")

        buildConfigField("String", "ACRA_URL", buildConfigString(acraUrl))
        buildConfigField("String", "ACRA_TOKEN", buildConfigString(acraToken))
        buildConfigField("String", "TMDB_API_KEY", buildConfigString(tmdbApiKey))
        buildConfigField("String", "TRAKT_CLIENT_ID", buildConfigString(traktClientId))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", buildConfigString(traktClientSecret))

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

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.androidx.security.crypto)
    implementation(libs.acra.http)
    implementation(libs.acra.toast)
}