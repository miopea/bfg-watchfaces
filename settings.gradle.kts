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

// Dev tooling, also pure JVM. The localhost design loop and the headless bake
// that produces dial_bg.png / preview.png. Depends on :generator, never the
// reverse. Not shipped to a device.
include(":workbench")

// Android modules. Uncomment once you have ANDROID_HOME configured locally.
include(":mobile")
include(":wear")
