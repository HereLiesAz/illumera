plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.HereLiesAz"
                artifactId = "assrender"
                version = "1.0.0"
            }
        }
    }
}

android {
    namespace = "io.github.assrender"
    compileSdk = 37

    defaultConfig {
        minSdk = 21

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    compileOnly(libs.media3.exoplayer)
    compileOnly(libs.media3.common)
    compileOnly(libs.media3.ui)
    implementation(libs.androidx.annotation)
}
