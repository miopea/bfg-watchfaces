plugins { alias(libs.plugins.kotlin.jvm) }

// Deliberately has NO Android dependency. The generator is the file format:
// it must be testable on the JVM, in CI, without an emulator.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // XSD 1.1 support. The WFF schemas use 1.1 features that the JDK's built-in
    // validator does not implement. These four jars are the exact set Google
    // ships in google/watchface for this purpose -- Maven's xercesImpl alone is
    // missing the XPath2 processor that 1.1 assertions require.
    //
    // Populated by scripts/bootstrap.sh. Not committed: they are Google's and
    // Apache's to distribute, not ours.
    testImplementation(fileTree("libs") { include("*.jar") })
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    doFirst {
        require(file("libs").listFiles()?.any { it.extension == "jar" } == true) {
            "generator/libs is empty. Run scripts/bootstrap.sh first."
        }
    }
}
