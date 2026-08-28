// Plugins are declared here with `apply false` and applied in the modules that
// need them. Declaring the version once at the root is not style: a subproject
// that names a version for a plugin already on the classpath fails with
// "already on the classpath with an unknown version, so compatibility cannot be
// checked", which is what happened the first time :wear was switched on.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
