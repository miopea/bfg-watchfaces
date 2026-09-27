import org.gradle.api.tasks.PathSensitivity
plugins { alias(libs.plugins.kotlin.jvm) }

// Rules and words the SHIPPED apps share.
//
// :generator is the file format and stays that. :workbench is dev tooling and is
// deliberately never shipped. Neither is the right home for something both
// :mobile and :wear need at runtime, which is what this module is for -- and it
// exists only because both of those consumers are real, not in anticipation of
// them.
//
// No Android dependency, on purpose: the rules are then testable on the JVM in
// CI, which is where the one-shot activation logic gets its assurance.
dependencies {
    // The stored face format is DialParams, so the rules that read and write it
    // need the type. Still no Android dependency: :generator is plain JVM too.
    api(project(":generator"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }

    // THE WEAR MODULE'S OWN FILES ARE INPUTS TO THESE TESTS, and Gradle has no
    // way to know it.
    //
    // Several tests here assert across the module boundary: that the watch
    // listens on the path prefix the phone opens, that it advertises the
    // capability the phone looks for, and that the cycle complication asks to
    // be refreshed while the pushed one does not. They read those files
    // directly, so nothing in the task graph connects them.
    //
    // Without this the task is UP-TO-DATE after any change confined to :wear,
    // and the guard silently stops running at exactly the moment it is needed.
    // Measured 2026-09-27: reverting the cycle complication's refresh period
    // reported BUILD SUCCESSFUL in one second, and the same revert failed in
    // fourteen under --rerun-tasks. A check that cannot notice the regression
    // it exists for is worse than no check, because it reads as evidence.
    inputs.files(
        "../wear/src/main/AndroidManifest.xml",
        "../wear/src/main/res/values/wear.xml"
    ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("wearModuleContracts")
}
