// Native SoftEther SSL-VPN client code is pinned at vendor/softether-android.
plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "vn.unlimit.softether"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("../vendor/softether-android/consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("-O2", "-fPIC", "-std=c++17")
                cFlags("-O2", "-fPIC")
                arguments("-DANDROID_STL=c++_shared")
                targets("softether")
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    externalNativeBuild {
        cmake { path = file("CMakeLists.txt") }
    }
    // AGP 9.2–9.4: the getter exposes a legacy AndroidLibrarySourceSet type
    // which the runtime DefaultAndroidLibrarySourceSet does not implement.
    // The sourceSets block overload uses the corrected DSL type (Issue #550220873).
    sourceSets {
        named("main").configure {
        manifest.srcFile("../vendor/softether-android/src/main/AndroidManifest.xml")
        java.srcDirs("../vendor/softether-android/src/main/java")
        res.srcDirs("../vendor/softether-android/src/main/res")
        jniLibs.srcDirs("../vendor/softether-android/src/main/jniLibs")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
