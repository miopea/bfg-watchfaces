// SCAFFOLD -- never built or run. Uncomment ":wear" in settings.gradle.kts
// once ANDROID_HOME is configured.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bfg.watchfaces.wear"
    compileSdk = 36  // Watch Face Push 1.0.0 requires 36 or later
    defaultConfig {
        applicationId = "com.bfg.watchfaces"  // MUST match the phone app
        minSdk = 36                           // Wear OS 6 = API 36. Push exists nowhere below it.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    // Java and Kotlin must agree, or the build fails with "Inconsistent
    // JVM-target compatibility" -- the scaffolds set Kotlin to 17 and left Java
    // at the 1.8 default, which only shows up once there is Kotlin to compile.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":appcore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.watchface.push)

    testImplementation("junit:junit:4.13.2")
}
