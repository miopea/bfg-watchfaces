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
}
