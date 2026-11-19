package com.khereba1.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class RootStatus(
    val checked: Boolean = false,
    val hasSuBinary: Boolean = false,
    val rootGranted: Boolean = false,
    val detail: String = ""
)

object RootHelper {
    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/adb/magisk",
        "/data/adb/ksu"
    )

    suspend fun checkRoot(): RootStatus = withContext(Dispatchers.IO) {
        val found = suPaths.any { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }
        RootStatus(
            checked = true,
            hasSuBinary = found,
            rootGranted = false,
            detail = if (found) "su binary found" else "No root binary"
        )
    }

    suspend fun requestRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        val res = runSuCommand("id")
        res.fold(
            onSuccess = { out ->
                val granted = out.contains("uid=0")
                RootStatus(
                    checked = true,
                    hasSuBinary = true,
                    rootGranted = granted,
                    detail = out.trim().ifEmpty { if (granted) "uid=0(root)" else "Denied" }
                )
            },
            onFailure = { e ->
                RootStatus(
                    checked = true,
                    hasSuBinary = true,
                    rootGranted = false,
                    detail = e.message ?: "Root denied"
                )
            }
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

    private fun runSuCommand(cmd: String): Result<String> = runCatching {
        val proc = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            error("Root command timed out")
        }
        val output = proc.inputStream.bufferedReader().readText().trim()
        if (proc.exitValue() != 0) {
            error(output.ifEmpty { "Exit code ${proc.exitValue()}" })
        }
        output.ifEmpty { "OK" }
    }
}
