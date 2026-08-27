// SCAFFOLD -- never built or run. Uncomment ":phone" in settings.gradle.kts
// once ANDROID_HOME is configured.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bfg.watchfaces.phone"
    compileSdk = 35
    defaultConfig {
        // FROZEN AT FIRST PLAY RELEASE. applicationId can never be changed
        // afterwards -- a new one is a new app with zero installs and zero
        // reviews. Watch Face Push also derives every pushed face's package
        // from it: <applicationId>.watchfacepush.<slug>.
        applicationId = "com.bfg.watchfaces"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // pack ships as native libs; limit ABIs to what you actually ship
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":generator"))
    implementation(libs.play.services.wearable)   // Data Layer: Channel/Message/Capability
    implementation(libs.wfp.validator.android)    // local token generation, no network

    // google/pack -- builds and signs the watch face APK on-device.
    // Prebuilt .so files go in src/main/jniLibs/<abi>/. Androidify ships a set;
    // building fresh needs the NDK plus Rust. See docs/SPEC.md.
    // implementation(files("libs/pack-java.aar"))
}
