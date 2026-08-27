pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Required by the Watch Face Push validator
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "bfg-watchfaces"

// Pure JVM. No Android. Compiles and tests without an SDK or a device.
include(":generator")

// Android modules. Uncomment once you have ANDROID_HOME configured locally.
// include(":phone")
// include(":wear")
