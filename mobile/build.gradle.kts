import java.io.File
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
        versionCode = 46
        versionName = "1.45"
        // pack ships as native libs; limit ABIs to what you actually ship
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildFeatures { compose = true }
    // Java and Kotlin must agree, or the build fails with "Inconsistent
    // JVM-target compatibility" -- the scaffolds set Kotlin to 17 and left Java
    // at the 1.8 default, which only shows up once there is Kotlin to compile.
    /**
     * Release signing, for the Play upload key.
     *
     * The password is NEVER in this file, in gradle.properties, or in the repo.
     * It comes from the environment, and the only place it is stored is
     * 1Password (vault BFG, "BFG Watch Faces — Play upload keystore"). Build a
     * release with:
     *
     *     eval "$(op-login)"
     *     export BFG_KEYSTORE_PASSWORD="$(op item get za3jiqmw75cajm7s24hveksl5q \
     *         --vault=BFG --fields label=password --reveal)"
     *     ./gradlew :mobile:bundleRelease
     *
     * The keystore lives OUTSIDE the repo (~/.keystores) so it cannot be
     * committed by accident, and this is only the UPLOAD key -- Play App Signing
     * holds the real one, so losing this is recoverable through Play support.
     * Losing the app signing key would not be.
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

    // BouncyCastle ships LICENSE.md and NOTICE.md in all three of its jars, and
    // the merger will not pick between identical files. Excluding them keeps the
    // licences out of the APK, which is why they are reproduced in
    // docs/THIRD-PARTY.md instead of being silently dropped.
    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md",
                                    "META-INF/LICENSE", "META-INF/NOTICE",
                                    "META-INF/DEPENDENCIES", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    buildTypes {
        release {
            // Fail loudly rather than emit an unsigned bundle that Play rejects
            // with a message about the upload key rather than about this.
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":generator"))
    implementation(project(":appcore"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.wearable)   // Data Layer: Channel/Message/Capability
    implementation(libs.wfp.validator.android)    // local token generation, no network

    // Google sign-in, used for ONE thing: publishing a face to the community
    // catalog. Browsing, installing and reporting stay anonymous, so nothing
    // here may become a gate on any of those. Credential Manager rather than
    // the deprecated GoogleSignInClient.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // google/pack builds the watch face APK on the device. The native library
    // is NOT a dependency and NOT Androidify's prebuilt one: it is built from
    // the same pinned, patched pack the desktop CLI uses, by
    // scripts/build-pack-android.sh, into src/main/jniLibs/<abi>/. Gitignored --
    // build output, not source. PackBridge.isAvailable is how the app finds out
    // whether this build has it.
    implementation(libs.apksig)             // pack compiles; it does not sign
    implementation(libs.bouncycastle.pkix)  // the self-signed cert for the Keystore key
}
