// SCAFFOLD -- never built or run. Uncomment ":wear" in settings.gradle.kts
// once ANDROID_HOME is configured.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bfg.watchfaces.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bfg.watchfaces"  // MUST match the phone app
        minSdk = 34                           // Watch Face Push is Wear OS 6+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(libs.wear.watchface.push)
}
