plugins { alias(libs.plugins.kotlin.jvm) }

// Dev tooling. Pure JVM like :generator -- no Android, no emulator, no device.
// It depends on :generator and never the other way round: the generator stays
// the dependency-free definition of the file format.
dependencies {
    implementation(project(":generator"))

    // Xerces + XPath2, for live XSD 1.1 validation in the browser. Same jars
    // WffSchemaTest uses. Populated by scripts/bootstrap.sh; not committed.
    implementation(fileTree("../generator/libs") { include("*.jar") })

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

kotlin { jvmToolchain(21) }

/** The localhost design loop. */
tasks.register<JavaExec>("workbench") {
    group = "bfg"
    description = "Run the watch face workbench at http://localhost:7777"
    mainClass.set("com.bfg.watchfaces.workbench.Workbench")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    // Ctrl-C should stop the server, not drop into a Gradle stack trace.
    standardInput = System.`in`
}

/** Headless bake, for build.sh and CI. */
tasks.register<JavaExec>("bake") {
    group = "bfg"
    description = "Bake dial_bg.png, preview.png and watchface.xml into watchface-template"
    mainClass.set("com.bfg.watchfaces.workbench.Bake")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
