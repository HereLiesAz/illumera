// Source and ABI binaries are pinned at vendor/openvpn-android (.gitmodules).
// Keep the library separate to avoid coupling Illumera's app package to the upstream app UI.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // Preserve native entry points and serialized VPN profiles under release R8.
        consumerProguardFiles("../vendor/openvpn-android/proguard-rules.pro")
    }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets.getByName("main") {
        manifest.srcFile("../native-openvpn/AndroidManifest.xml")
        java.srcDirs("../vendor/openvpn-android/src/main/java")
        res.srcDirs("../vendor/openvpn-android/src/main/res")
        assets.srcDirs("../vendor/openvpn-android/src/main/assets")
        jniLibs.srcDirs("../vendor/openvpn-android/src/main/jniLibs")
        aidl.srcDirs("../vendor/openvpn-android/src/main/aidl")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
}
