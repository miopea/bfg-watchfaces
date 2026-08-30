package com.bfg.watchfaces.workbench

import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import java.io.File

/**
 * A Watch Face Push validation token, from the desktop.
 *
 *     ./gradlew :workbench:token --args="path/to/face.apk"
 *
 * The phone mints these for the faces it builds. This exists so a candidate APK
 * can be pushed to a watch by hand, which is the only way to answer "does Watch
 * Face Push accept THIS artefact" without a full Play round trip. Tokens do not
 * expire, so one generated here stays usable.
 *
 * The caller package matters: a token is issued for a specific APK AND the
 * package that will call `addWatchFace`, which is the watch app.
 */
object Token {

    @JvmStatic
    fun main(args: Array<String>) {
        val apk = File(args.firstOrNull() ?: run {
            System.err.println("usage: token <apk> [callerPackage]")
            kotlin.system.exitProcess(2)
        })
        val caller = args.getOrNull(1) ?: "com.bfg.watchfaces"
        if (!apk.isFile) {
            System.err.println("no such file: ${apk.absolutePath}")
            kotlin.system.exitProcess(2)
        }

        val result = DwfValidatorFactory.create().validate(apk, caller)
        val failures = result.failures()
        println("apk    : ${apk.absolutePath} (${apk.length()} bytes)")
        println("caller : $caller")
        if (failures.isNotEmpty()) {
            println("INVALID -- the validator itself rejects this:")
            failures.forEach { println("  $it") }
            kotlin.system.exitProcess(1)
        }
        println("valid  : yes")
        println("TOKEN=${result.validationToken()}")
    }
}
