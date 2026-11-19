package com.khereba1.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class RootStatus(
    val checked: Boolean = false,
    val hasSuBinary: Boolean = false,
    val rootGranted: Boolean = false,
    val detail: String = "",
    val suPath: String = ""
)

object RootHelper {
    // Magisk / KernelSU / APatch / SuperSU keep su in different places and often hide it from
    // File.exists() checks, so the only reliable test is to actually try to run it.
    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/product/bin/su",
        "/debug_ramdisk/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    )

    private val markerPaths = listOf(
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/ap",
        "/sbin/.magisk",
        "/system/app/Superuser.apk"
    )

    @Volatile
    private var workingSu: String = "su"

    private class ProcResult(
        val started: Boolean,
        val exit: Int,
        val output: String,
        val timedOut: Boolean,
        val error: String
    )

    private fun exec(args: List<String>, timeoutSec: Long): ProcResult {
        val proc = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return ProcResult(false, -1, "", false, e.message ?: e.javaClass.simpleName)
        }
        return try {
            runCatching { proc.outputStream.close() }
            val out = StringBuilder()
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                ProcResult(true, -1, out.toString().trim(), true, "timeout")
            } else {
                reader.join(1000)
                ProcResult(true, proc.exitValue(), out.toString().trim(), false, "")
            }
        } catch (e: Throwable) {
            runCatching { proc.destroyForcibly() }
            ProcResult(true, -1, "", false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Passive check: looks for su without asking for permission (no prompt is shown). */
    suspend fun checkRoot(): RootStatus = withContext(Dispatchers.IO) {
        val markers = markerPaths.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        val fileHit = suCandidates.drop(1).firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
        val which = exec(listOf("sh", "-c", "command -v su"), 3)
        val whichPath = if (which.started && which.exit == 0) {
            which.output.lines().firstOrNull()?.trim().orEmpty()
        } else ""
        val path = whichPath.ifEmpty { fileHit.orEmpty() }
        val has = path.isNotEmpty() || markers.isNotEmpty()
        RootStatus(
            checked = true,
            hasSuBinary = has,
            rootGranted = false,
            detail = if (has) {
                "su: ${path.ifEmpty { markers.first() }}"
            } else {
                "No su found in PATH or known locations. Tap verify to try anyway."
            },
            suPath = path
        )
    }

    /**
     * Active check: really runs `su -c id`. Waits up to 25 seconds so the user can accept the
     * Magisk / KernelSU / APatch grant dialog. Returns a readable report of what happened.
     */
    suspend fun requestRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        var anyStarted = false
        for (cand in suCandidates) {
            val r = exec(listOf(cand, "-c", "id"), 25)
            if (!r.started) {
                continue
            }
            anyStarted = true
            if (r.timedOut) {
                report.append("$cand: no answer in 25s (approve the root request, then try again)")
                return@withContext RootStatus(true, true, false, report.toString(), cand)
            }
            if (r.output.contains("uid=0")) {
                workingSu = cand
                return@withContext RootStatus(
                    checked = true,
                    hasSuBinary = true,
                    rootGranted = true,
                    detail = r.output.lines().firstOrNull { it.contains("uid=0") }?.trim().orEmpty()
                        .ifEmpty { "uid=0(root)" },
                    suPath = cand
                )
            }
            report.append("$cand: exit=${r.exit} ${r.output.take(160)}")
            // su exists but refused; trying other binaries would only show more prompts.
            break
        }
        if (!anyStarted) {
            report.append("Could not start any su binary. Root is not installed, or this app is denied/hidden by your root manager.")
        }
        RootStatus(
            checked = true,
            hasSuBinary = anyStarted,
            rootGranted = false,
            detail = report.toString().trim()
        )
    }

    private val ipv4Regex = Regex("^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$")

    suspend fun applyGlobalHttpProxy(host: String, port: Int): Result<String> = withContext(Dispatchers.IO) {
        val safeHost = host.trim().ifEmpty { "192.168.49.1" }
        if (!ipv4Regex.matches(safeHost)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid IPv4 address"))
        }
        val safePort = port.coerceIn(1, 65535)
        runSuCommand("settings put global http_proxy $safeHost:$safePort")
    }

    suspend fun clearGlobalHttpProxy(): Result<String> = withContext(Dispatchers.IO) {
        runSuCommand("settings put global http_proxy :0")
    }

    suspend fun enableIpForwarding(): Result<String> = withContext(Dispatchers.IO) {
        runSuCommand("sysctl -w net.ipv4.ip_forward=1")
    }

    private fun runSuCommand(cmd: String): Result<String> {
        var last: ProcResult? = null
        for (su in (listOf(workingSu) + suCandidates).distinct()) {
            val r = exec(listOf(su, "-c", cmd), 15)
            if (!r.started) {
                last = r
                continue
            }
            if (r.timedOut) {
                return Result.failure(IllegalStateException("Root command timed out"))
            }
            if (r.exit != 0) {
                return Result.failure(IllegalStateException(r.output.ifEmpty { "Exit code ${r.exit}" }))
            }
            workingSu = su
            return Result.success(r.output.ifEmpty { "OK" })
        }
        return Result.failure(IllegalStateException("su not available: ${last?.error.orEmpty()}"))
    }
}
