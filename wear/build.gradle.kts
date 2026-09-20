import java.io.File
// SCAFFOLD -- never built or run. Uncomment ":wear" in settings.gradle.kts
// once ANDROID_HOME is configured.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Required from Kotlin 2.0 whenever buildFeatures.compose is on.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bfg.watchfaces.wear"
    compileSdk = 36  // Watch Face Push 1.0.0 requires 36 or later
    defaultConfig {
        applicationId = "com.bfg.watchfaces"  // MUST match the phone app
        minSdk = 36                           // Wear OS 6 = API 36. Push exists nowhere below it.
        targetSdk = 36
        // Play requires a UNIQUE versionCode for every artefact in a release, and
        // the phone and watch apps ship as two artefacts under one listing. The
        // scheme is "wear = phone + 1000", so the two never collide and it stays
        // obvious which is which in the console.
        versionCode = 1041
        versionName = "1.48"
    }
    /**
     * Release signing. Identical to `:mobile`'s and deliberately so: both
     * artefacts go into one Play listing, and Play rejects a release whose APKs
     * are signed by different keys.
     *
     * The password is never in this file or the repo -- see mobile/build.gradle.kts.
     */
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("BFG_KEYSTORE")
                ?: "${System.getProperty("user.home")}/.keystores/bfg-watchfaces-upload.jks"
            val ksPassword = System.getenv("BFG_KEYSTORE_PASSWORD")
            if (ksPassword != null && File(ksPath).exists()) {
                storeFile = File(ksPath)
                storePassword = ksPassword
                keyAlias = System.getenv("BFG_KEY_ALIAS") ?: "bfg-upload"
                keyPassword = ksPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    // Java and Kotlin must agree, or the build fails with "Inconsistent
    // JVM-target compatibility" -- the scaffolds set Kotlin to 17 and left Java
    // at the 1.8 default, which only shows up once there is Kotlin to compile.
    // buildConfig for VERSION_NAME/VERSION_CODE: the watch screen shows them,
    // because the version is the first thing anyone is asked for when a send
    // goes wrong and the watch is the far end of that.
    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":appcore"))
    implementation(libs.androidx.core.ktx)
    // The swipe carousel. TileService still serves it on the operator's watch
    // (Android 17 / SDK 37) alongside Fitbit's and Maps' tiles; Wear OS 7
    // renames the surface to widgets and migrates tiles into it, and the APIs
    // stay backward compatible. See CycleTileService.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.protolayout.expression)
    implementation(libs.androidx.protolayout.material3)
    // Wear's branded launch, and NOT optional despite minSdk 36.
    //
    // The first two attempts set the platform splash attributes directly and
    // argued the library "buys nothing above API 31". Google's Wear splash
    // guide prescribes it anyway, and says 1.0.1+ carries "support for default
    // Wear OS dimensions" -- a Wear-specific behaviour the bare platform
    // attributes do not have. Two policy rejections came out of reasoning past
    // that page instead of following it. See themes.xml.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity)
    // Explicit, not transitive. activity-ktx drags in fragment 1.1.0, and
    // registerForActivityResult needs 1.3.0+: below that, FragmentActivity does
    // not call super.onRequestPermissionsResult() and uses invalid request
    // codes, so a permission result can be silently lost. ActivationRequestActivity
    // is exactly that case, and its permission cannot be requested a second time.
    implementation(libs.androidx.fragment)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.watchface.push)
    // Lets THIS app answer a complication, so a value can reach the wrist
    // without rebuilding and re-sending a face. See PhoneNoteService.
    implementation(libs.wear.complications.data.source)

    // The watch app had no launcher activity at all: installed from Play it was
    // invisible, which makes it untestable by anyone in a testing ring. Wear
    // Compose rather than Views because the screen is ROUND -- ScreenScaffold
    // and TransformingLazyColumn handle the curve and the rotary crown, and
    // hand-rolling that in a FrameLayout is how a watch app ends up with its
    // buttons under the bezel.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material3)
    // "Open on phone". RemoteActivityHelper is the only supported way for a
    // watch to start something on the paired phone -- an app cannot reach
    // across the Data Layer and start an activity itself.
    implementation(libs.wear.remote.interactions)
    implementation(libs.wear.compose.foundation)
}
