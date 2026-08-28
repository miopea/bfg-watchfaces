// SCAFFOLD -- never built or run. Uncomment ":mobile" in settings.gradle.kts
// once ANDROID_HOME is configured.
//
// "mobile", not "phone": this is the app people design faces in, and it runs
// on a tablet just as well. Android's own Wear project layout pairs mobile/
// with wear/ for the same reason.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bfg.watchfaces.mobile"
    compileSdk = 36  // Watch Face Push 1.0.0 requires 36 or later
    defaultConfig {
        // FROZEN AT FIRST PLAY RELEASE. applicationId can never be changed
        // afterwards -- a new one is a new app with zero installs and zero
        // reviews. Watch Face Push also derives every pushed face's package
        // from it: <applicationId>.watchfacepush.<slug>.
        applicationId = "com.bfg.watchfaces"
        // 28, not 26: the Watch Face Push validator library declares minSdk 28,
        // so anything lower fails the manifest merge. Android 9 and up.
        minSdk = 28
        targetSdk = 36
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
