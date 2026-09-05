import java.io.File

plugins { alias(libs.plugins.kotlin.jvm) }

// Dev tooling. Pure JVM like :generator -- no Android, no emulator, no device.
// It depends on :generator and never the other way round: the generator stays
// the dependency-free definition of the file format.
dependencies {
    implementation(project(":generator"))
    implementation(project(":appcore"))

    // Xerces + XPath2, for live XSD 1.1 validation in the browser. Same jars
    // WffSchemaTest uses. Populated by scripts/bootstrap.sh; not committed.
    implementation(fileTree("../generator/libs") { include("*.jar") })

    // The Watch Face Push validator, JVM build. Lets a candidate APK be checked
    // and tokenised here, so pushing one to a watch by hand does not need a
    // phone app that can mint tokens.
    implementation(libs.wfp.validator.jvm)

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

/** Validate the community catalog and regenerate its index. */
tasks.register<JavaExec>("catalog") {
    group = "bfg"
    description = "Validate catalog/faces/*.json and rewrite catalog/index.json"
    mainClass.set("com.bfg.watchfaces.workbench.Catalog")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** Headless bake, for build.sh and CI. */
tasks.register<JavaExec>("bake") {
    group = "bfg"
    description = "Bake dial_bg.png, preview.png and watchface.xml into watchface-template"
    mainClass.set("com.bfg.watchfaces.workbench.Bake")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/**
 * Turn device captures into images the Play Console accepts.
 *
 * A Pixel capture is 9:20 and Play's limit is 9:16, so a raw screenshot is
 * rejected outright. This scales to fit and pads rather than cropping, because
 * cropping a list of watch faces crops the watch faces.
 */
tasks.register<JavaExec>("storeshots") {
    group = "bfg"
    description = "Pad device captures to Play's aspect ratio, into build/store"
    mainClass.set("com.bfg.watchfaces.workbench.StoreShots")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/**
 * Draw the hands, so geometry is judged on screen rather than on a wrist.
 *
 * Writes each hand, the hub, and a composite at 10:10:30 -- the time that makes
 * an hour hand wrongly the same length as the minute hand obvious at a glance.
 */
tasks.register<JavaExec>("hands") {
    group = "bfg"
    description = "Render the hand styles to build/hands for review"
    mainClass.set("com.bfg.watchfaces.workbench.HandsSheet")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** Regenerate the app icon from BrandMark. Writes checked-in files. */
tasks.register<JavaExec>("brand") {
    group = "bfg"
    description = "Write the launcher icons, the Play store icon and docs/brand from BrandMark"
    mainClass.set("com.bfg.watchfaces.workbench.Brand")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** Regenerate the catalog service's params contract. Writes a checked-in file. */
tasks.register<JavaExec>("contract") {
    group = "bfg"
    description = "Write catalog-service/params-contract.json from CatalogContract"
    mainClass.set("com.bfg.watchfaces.workbench.Contract")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** Work the catalog service's moderation queue. */
tasks.register("prepareModerationRunner") {
    group = "bfg"
    description = "Compile the unattended moderator and record its runtime classpath"
    dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
    val runtime = sourceSets["main"].runtimeClasspath
    val output = layout.buildDirectory.file("moderation-classpath.txt")
    inputs.files(runtime)
    outputs.file(output)
    doLast {
        check(configurations.runtimeClasspath.get().files.all { it.exists() }) {
            "Moderation runtime dependencies are incomplete"
        }
        // Gradle includes an empty Java output directory in this Kotlin-only module.
        val files = runtime.files.filter { it.exists() }
        check(files.any { it.isDirectory && it.resolve("com/bfg/watchfaces/workbench/Moderate.class").isFile }) {
            "The moderation entry point was not compiled"
        }
        output.get().asFile.writeText(files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}

tasks.register<JavaExec>("moderate") {
    group = "bfg"
    description = "Review, publish or reject faces in the catalog service's queue"
    mainClass.set("com.bfg.watchfaces.workbench.Moderate")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** A Watch Face Push validation token for an APK, so it can be pushed by hand. */
tasks.register<JavaExec>("token") {
    group = "bfg"
    description = "Print a Watch Face Push validation token for an APK"
    mainClass.set("com.bfg.watchfaces.workbench.Token")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
