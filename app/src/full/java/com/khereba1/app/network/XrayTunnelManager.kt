package com.khereba1.app.network

import android.content.Context
import android.util.Base64
import com.khereba1.app.AppLog
import com.khereba1.app.PREFS
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class XrayState(
    val isRunning: Boolean = false,
    val status: String = ""
)

data class XrayOverrides(
    val hostOverride: String = "",
    val sniOverride: String = "",
    val allowInsecure: Boolean = false,
    val allowLan: Boolean = false
)

data class XrayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: String,
    val hostOverride: String = "",
    val sniOverride: String = "",
    val allowInsecure: Boolean = false
)

object XrayTunnelManager {
    private var commandServer: CommandServer? = null
    private var envInitialized = false
    @Volatile private var lastCoreError: String = ""
    @Volatile private var appCtx: Context? = null
    @Volatile private var defaultListener: InterfaceUpdateListener? = null
    @Volatile private var activeConfig: String? = null
    @Volatile private var lastIface: String? = null
    @Volatile private var generation: Int = 0
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val dnsExec = Executors.newCachedThreadPool()
    private val _state = MutableStateFlow(XrayState())
    val state: StateFlow<XrayState> = _state

    const val LOCAL_SOCKS_PORT = 10808
    private const val PREF_PROFILES = "xray_profiles_json"

    private fun toStringIterator(items: List<String>): StringIterator = object : StringIterator {
        private var idx = 0
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = idx < items.size
        override fun next(): String = items[idx++]
    }

    private fun isIgnoredInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("dummy") ||
            n.startsWith("ifb") || n.startsWith("sit") || n.startsWith("ip6tnl")
    }

    private fun collectBoxInterfaces(): List<io.nekohasekai.libbox.NetworkInterface> {
        val list = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val seenNames = HashSet<String>()
        val usedIndices = HashSet<Int>()

        val ctx = appCtx
        if (ctx != null) {
            val cmIfaces = PhysicalNetwork.allInterfaces(ctx)
            for (info in cmIfaces) {
                if (isIgnoredInterface(info.interfaceName)) continue
                seenNames.add(info.interfaceName)
                usedIndices.add(info.index)
                val boxNi = io.nekohasekai.libbox.NetworkInterface().apply {
                    index = info.index
                    mtu = info.mtu
                    name = info.interfaceName
                    flags = 1 or 2 or 16
                    addresses = toStringIterator(info.cidrAddresses)
                    dnsServer = toStringIterator(info.dnsServers)
                }
                list.add(boxNi)
            }
        }

        runCatching {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            var nextIdx = (usedIndices.maxOrNull() ?: 0) + 1
            while (en != null && en.hasMoreElements()) {
                val ni = en.nextElement()
                val name = ni.name ?: continue
                if (!ni.isUp || ni.isLoopback || isIgnoredInterface(name) || !seenNames.add(name)) continue
                val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address ?: return@mapNotNull null
                    val rawHost = a.hostAddress ?: return@mapNotNull null
                    val cleanHost = rawHost.substringBefore('%')
                    "$cleanHost/${ia.networkPrefixLength}"
                }
                if (addrs.isEmpty()) continue
                var idx = ni.index
                if (idx <= 0 || !usedIndices.add(idx)) {
                    while (usedIndices.contains(nextIdx)) nextIdx++
                    idx = nextIdx
                    usedIndices.add(idx)
                }
                val boxNi = io.nekohasekai.libbox.NetworkInterface().apply {
                    index = idx
                    mtu = runCatching { ni.mtu }.getOrDefault(1500).let { if (it in 1280..9000) it else 1500 }
                    this.name = name
                    flags = 1 or 2 or 16
                    addresses = toStringIterator(addrs)
                    dnsServer = toStringIterator(listOf("8.8.8.8", "1.1.1.1"))
                }
                list.add(boxNi)
            }
        }

        if (list.isEmpty()) {
            val fallback = io.nekohasekai.libbox.NetworkInterface().apply {
                index = 1
                mtu = 1500
                name = "wlan0"
                flags = 1 or 2 or 16
                addresses = toStringIterator(listOf("10.0.0.2/24"))
                dnsServer = toStringIterator(listOf("8.8.8.8", "1.1.1.1"))
            }
            list.add(fallback)
        }
        return list
    }

    private fun pushDefaultInterface(listener: InterfaceUpdateListener) {
        val first = collectBoxInterfaces().firstOrNull()
        if (first != null) {
            lastIface = first.name
            runCatching { listener.updateDefaultInterface(first.name, first.index, false, false) }
        } else {
            lastIface = "wlan0"
            runCatching { listener.updateDefaultInterface("wlan0", 1, false, false) }
        }
    }

    private val platformImpl = object : PlatformInterface {
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun autoDetectInterfaceControl(fd: Int) {
            NearChatVpnService.protectFd(fd)
        }
        override fun openTun(options: TunOptions?): Int = -1
        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): ConnectionOwner = ConnectionOwner()
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            if (listener == null) return
            defaultListener = listener
            pushDefaultInterface(listener)
        }
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            defaultListener = null
        }
        override fun getInterfaces(): NetworkInterfaceIterator {
            val items = collectBoxInterfaces()
            var idx = 0
            return object : NetworkInterfaceIterator {
                override fun hasNext(): Boolean = idx < items.size
                override fun next(): io.nekohasekai.libbox.NetworkInterface = items[idx++]
            }
        }
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")
        override fun systemCertificates(): StringIterator = toStringIterator(emptyList())
        override fun clearDNSCache() {}
        override fun sendNotification(notification: Notification?) {}
    }

    private val commandHandler = object : CommandServerHandler {
        override fun serviceStop() {
            stop()
        }
        override fun serviceReload() {}
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun writeDebugMessage(message: String?) {
            if (!message.isNullOrBlank()) {
                val trimmed = message.trim()
                lastCoreError = trimmed
                AppLog.log("SINGBOX", trimmed)
            }
        }
    }

    private fun readRecentCoreLog(): String {
        val mem = lastCoreError.trim()
        if (mem.isNotEmpty()) return mem.lines().lastOrNull { it.isNotBlank() }?.take(140) ?: mem.take(140)
        val ctx = appCtx ?: return ""
        return runCatching {
            val workDir = File(File(ctx.filesDir, "singbox"), "work")
            val logFiles = workDir.listFiles { f -> f.isFile && (f.name.endsWith(".log") || f.name.contains("box")) }
            val latest = logFiles?.maxByOrNull { it.lastModified() } ?: return@runCatching ""
            latest.readLines().lastOrNull { it.isNotBlank() }?.take(140).orEmpty()
        }.getOrDefault("")
    }

    private fun ensureSetup(context: Context) {
        appCtx = context.applicationContext
        if (envInitialized) return
        val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
        val workDir = File(baseDir, "work").apply { mkdirs() }
        val tmpDir = File(baseDir, "tmp").apply { mkdirs() }
        val opts = SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = tmpDir.absolutePath
            fixAndroidStack = true
        }
        Libbox.setup(opts)
        envInitialized = true
    }

    fun loadProfiles(context: Context): List<XrayProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_PROFILES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                XrayProfile(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Profile"),
                    config = o.optString("config", ""),
                    hostOverride = o.optString("hostOverride", ""),
                    sniOverride = o.optString("sniOverride", ""),
                    allowInsecure = o.optBoolean("allowInsecure", false)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveProfile(
        context: Context,
        name: String,
        config: String,
        overrides: XrayOverrides = XrayOverrides()
    ): List<XrayProfile> {
        val list = loadProfiles(context).toMutableList()
        list.add(
            XrayProfile(
                name = name.trim().ifEmpty { "Profile ${list.size + 1}" },
                config = config.trim(),
                hostOverride = overrides.hostOverride.trim(),
                sniOverride = overrides.sniOverride.trim(),
                allowInsecure = overrides.allowInsecure
            )
        )
        persistProfiles(context, list)
        return list
    }

    fun deleteProfile(context: Context, id: String): List<XrayProfile> {
        val list = loadProfiles(context).filterNot { it.id == id }
        persistProfiles(context, list)
        return list
    }

    private fun persistProfiles(context: Context, list: List<XrayProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("config", p.config)
                    .put("hostOverride", p.hostOverride)
                    .put("sniOverride", p.sniOverride)
                    .put("allowInsecure", p.allowInsecure)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_PROFILES, arr.toString()).apply()
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 250)
            true
        }
    }.getOrDefault(false)

    private fun waitForPort(port: Int, timeoutMs: Long = 3500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port)) return true
            Thread.sleep(120)
        }
        return false
    }

    @Synchronized
    private fun getOrCreateServer(context: Context): CommandServer {
        ensureSetup(context)
        val existing = commandServer
        if (existing != null) return existing
        return Libbox.newCommandServer(commandHandler, platformImpl).apply {
            start()
            commandServer = this
        }
    }

    suspend fun testPing(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides = XrayOverrides()
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) error("Paste a config link or JSON first")
            val wasRunning = _state.value.isRunning
            val cs = getOrCreateServer(context)

            if (wasRunning) {
                val directTry = runCatching { testProxyLatency(LOCAL_SOCKS_PORT, timeoutMs = 4500) }
                if (directTry.isSuccess) return@runCatching directTry.getOrThrow()
            }

            val candidates = buildNekoBoxConfigCandidates(context, trimmed, overrides, LOCAL_SOCKS_PORT)
            var lastCheckErr: Throwable? = null
            var lastTestErr: Throwable? = null
            var chosenCfg: String? = null
            var pingMs: Long? = null
            var anyCandidateChecked = false

            for ((label, cfg) in candidates) {
                val checkRes = runCatching { Libbox.checkConfig(cfg) }
                if (checkRes.isFailure) {
                    lastCheckErr = checkRes.exceptionOrNull()
                    AppLog.log("SINGBOX", "checkConfig failed on '$label': ${lastCheckErr?.message}")
                    continue
                }
                anyCandidateChecked = true
                lastCoreError = ""
                val started = runCatching {
                    cs.startOrReloadService(cfg, OverrideOptions())
                    waitForPort(LOCAL_SOCKS_PORT, 3000L)
                }.getOrDefault(false)
                if (!started) continue

                val res = runCatching { testProxyLatency(LOCAL_SOCKS_PORT, timeoutMs = 4500) }
                if (res.isSuccess) {
                    chosenCfg = cfg
                    pingMs = res.getOrThrow()
                    AppLog.log("SINGBOX", "Ping succeeded with variant '$label': ${pingMs}ms")
                    break
                } else {
                    lastTestErr = res.exceptionOrNull()
                    AppLog.log("SINGBOX", "Ping failed on variant '$label': ${lastTestErr?.message} | core=$lastCoreError")
                }
            }

            if (pingMs != null && chosenCfg != null) {
                if (wasRunning) {
                    activeConfig = chosenCfg
                    val bindHost = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
                    _state.value = XrayState(true, "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Connected (${pingMs} ms)")
                } else {
                    runCatching { cs.closeService() }
                }
                pingMs
            } else {
                if (!wasRunning) {
                    runCatching { cs.closeService() }
                } else if (activeConfig != null) {
                    runCatching { cs.startOrReloadService(activeConfig!!, OverrideOptions()) }
                }
                if (!anyCandidateChecked && lastCheckErr != null) {
                    error("Config error: ${lastCheckErr?.message}")
                }
                val coreDetail = readRecentCoreLog()
                val baseMsg = lastTestErr?.message ?: "Server closed connection or unreachable"
                if (coreDetail.isNotEmpty() && !baseMsg.contains(coreDetail)) {
                    error("$baseMsg — $coreDetail")
                } else {
                    error(baseMsg)
                }
            }
        }
    }

    fun start(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides = XrayOverrides(),
        onReady: (() -> Unit)? = null
    ) {
        stop()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _state.value = XrayState(false, "Paste a config link or JSON first")
            return
        }

        _state.value = XrayState(false, "Starting sing-box…")
        val myGen = generation
        Thread {
            try {
                lastCoreError = ""
                val cs = getOrCreateServer(context)
                val candidates = buildNekoBoxConfigCandidates(context, trimmed, overrides, LOCAL_SOCKS_PORT)
                val validCandidates = mutableListOf<Pair<String, String>>()
                var lastCheckErr: Throwable? = null
                for (pair in candidates) {
                    val res = runCatching { Libbox.checkConfig(pair.second) }
                    if (res.isSuccess) {
                        validCandidates.add(pair)
                    } else {
                        lastCheckErr = res.exceptionOrNull()
                        AppLog.log("SINGBOX", "Variant '${pair.first}' rejected: ${lastCheckErr?.message}")
                    }
                }
                if (validCandidates.isEmpty()) {
                    error("Invalid config: ${lastCheckErr?.message ?: "rejected by sing-box"}")
                }

                var up = false
                var activeJson: String? = null
                for ((label, candidate) in validCandidates) {
                    if (myGen != generation) return@Thread
                    lastCoreError = ""
                    runCatching { cs.startOrReloadService(candidate, OverrideOptions()) }
                    if (waitForPort(LOCAL_SOCKS_PORT, 3000L)) {
                        up = true
                        activeJson = candidate
                        AppLog.log("SINGBOX", "Initial start with '$label'")
                        break
                    } else {
                        runCatching { cs.closeService() }
                    }
                }

                if (myGen != generation) return@Thread
                if (up && activeJson != null) {
                    val bindHost = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
                    activeConfig = activeJson
                    lastIface = appCtx?.let { PhysicalNetwork.current(it)?.interfaceName }
                    startNetworkWatch()
                    _state.value = XrayState(true, "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Connecting…")
                    onReady?.invoke()

                    var workingLatency: Long? = runCatching {
                        testProxyLatency(LOCAL_SOCKS_PORT, timeoutMs = 4500)
                    }.getOrNull()

                    if (workingLatency == null && validCandidates.size > 1) {
                        for ((label, candidate) in validCandidates.drop(1)) {
                            if (myGen != generation || !_state.value.isRunning) return@Thread
                            lastCoreError = ""
                            val reloaded = runCatching {
                                cs.startOrReloadService(candidate, OverrideOptions())
                                waitForPort(LOCAL_SOCKS_PORT, 2500L)
                            }.getOrDefault(false)
                            if (!reloaded) continue
                            val testRes = runCatching { testProxyLatency(LOCAL_SOCKS_PORT, timeoutMs = 4500) }
                            if (testRes.isSuccess) {
                                activeConfig = candidate
                                workingLatency = testRes.getOrNull()
                                AppLog.log("SINGBOX", "Auto-switched to working variant '$label' (${workingLatency}ms)")
                                break
                            }
                        }
                        if (workingLatency == null && activeConfig != validCandidates.first().second) {
                            activeConfig = validCandidates.first().second
                            runCatching { cs.startOrReloadService(activeConfig!!, OverrideOptions()) }
                        }
                    }

                    if (myGen == generation && _state.value.isRunning) {
                        if (workingLatency != null && workingLatency >= 0L) {
                            _state.value = XrayState(
                                true,
                                "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Connected (${workingLatency} ms)"
                            )
                        } else {
                            val coreErr = readRecentCoreLog()
                            val suffix = if (coreErr.isNotEmpty()) " ($coreErr)" else ""
                            _state.value = XrayState(
                                true,
                                "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Tunnel & VPN running$suffix"
                            )
                        }
                    }
                } else {
                    val msg = lastCoreError.ifBlank { "Port $LOCAL_SOCKS_PORT failed to bind" }
                    _state.value = XrayState(false, "Core error: $msg")
                }
            } catch (e: Throwable) {
                AppLog.log("SINGBOX", "Start failed", e)
                _state.value = XrayState(false, "Error: ${e.message}")
                commandServer = null
            }
        }.start()
    }

    fun stop() {
        generation++
        stopNetworkWatch()
        activeConfig = null
        try {
            commandServer?.closeService()
        } catch (_: Throwable) {
        }
        _state.value = XrayState(false, "")
    }

    private fun testProxyLatency(port: Int, timeoutMs: Int = 4500): Long {
        val targets = listOf(
            Triple("cp.cloudflare.com", 80, "/"),
            Triple("connectivitycheck.gstatic.com", 80, "/generate_204"),
            Triple("www.gstatic.com", 80, "/generate_204")
        )
        var lastError: Throwable? = null
        for ((host, targetPort, path) in targets) {
            val resSocks = runCatching {
                socks5HttpGetLatency(port, host, targetPort, path, timeoutMs)
            }
            if (resSocks.isSuccess) return resSocks.getOrThrow()
            lastError = resSocks.exceptionOrNull()

            val resHttp = runCatching {
                httpProxyGetLatency(port, host, targetPort, path, timeoutMs)
            }
            if (resHttp.isSuccess) return resHttp.getOrThrow()
            lastError = resHttp.exceptionOrNull() ?: lastError
        }
        val coreMsg = readRecentCoreLog()
        val errText = lastError?.message ?: "Connection test timed out"
        if (coreMsg.isNotEmpty() && !errText.contains(coreMsg)) {
            error("$errText ($coreMsg)")
        }
        throw lastError ?: IllegalStateException(errText)
    }

    private fun socks5HttpGetLatency(
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        path: String,
        timeoutMs: Int
    ): Long {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), minOf(timeoutMs, 2500))
            val outS = s.getOutputStream()
            val inS = s.getInputStream()

            val t0 = System.currentTimeMillis()
            outS.write(byteArrayOf(0x05, 0x01, 0x00))
            outS.flush()
            val greet = ByteArray(2)
            readExact(inS, greet)
            if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                error("SOCKS5 handshake rejected")
            }

            val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05
            req[1] = 0x01
            req[2] = 0x00
            req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
            req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            outS.write(req)
            outS.flush()

            val hdr = ByteArray(4)
            readExact(inS, hdr)
            if (hdr[1] != 0x00.toByte()) {
                val rep = hdr[1].toInt() and 0xFF
                val coreMsg = readRecentCoreLog()
                val detail = if (coreMsg.isNotEmpty()) ": $coreMsg" else ""
                error("SOCKS5 connect failed (code $rep)$detail")
            }
            when (hdr[3].toInt() and 0xFF) {
                0x01 -> readExact(inS, ByteArray(6))
                0x03 -> {
                    val len = inS.read()
                    if (len < 0) error("Unexpected EOF in SOCKS5 reply")
                    readExact(inS, ByteArray(len + 2))
                }
                0x04 -> readExact(inS, ByteArray(18))
                else -> error("Unknown SOCKS5 address type")
            }

            val httpReq = "GET $path HTTP/1.1\r\nHost: $targetHost\r\nUser-Agent: curl/8.0\r\nConnection: close\r\n\r\n"
            outS.write(httpReq.toByteArray(StandardCharsets.UTF_8))
            outS.flush()

            val statusLine = readHttpLine(inS)
            if (statusLine.isBlank()) {
                val coreMsg = readRecentCoreLog()
                val detail = if (coreMsg.isNotEmpty()) ": $coreMsg" else ""
                error("Remote closed connection (EOF)$detail")
            }
            if (!statusLine.startsWith("HTTP/")) {
                error("Unexpected reply: ${statusLine.take(40)}")
            }
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..499) {
                val coreMsg = readRecentCoreLog()
                val detail = if (coreMsg.isNotEmpty()) " ($coreMsg)" else ""
                error("HTTP $code$detail")
            }
            return (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        }
    }

    private fun httpProxyGetLatency(
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        path: String,
        timeoutMs: Int
    ): Long {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), minOf(timeoutMs, 2500))
            val outS = s.getOutputStream()
            val inS = s.getInputStream()

            val t0 = System.currentTimeMillis()
            val portPart = if (targetPort == 80) "" else ":$targetPort"
            val req = "GET http://$targetHost$portPart$path HTTP/1.1\r\n" +
                "Host: $targetHost$portPart\r\n" +
                "User-Agent: curl/8.0\r\n" +
                "Proxy-Connection: close\r\n" +
                "Connection: close\r\n\r\n"
            outS.write(req.toByteArray(StandardCharsets.UTF_8))
            outS.flush()

            val statusLine = readHttpLine(inS)
            if (statusLine.isBlank()) {
                val coreMsg = readRecentCoreLog()
                val detail = if (coreMsg.isNotEmpty()) ": $coreMsg" else ""
                error("Remote closed connection (EOF)$detail")
            }
            if (!statusLine.startsWith("HTTP/")) {
                error("Unexpected proxy reply: ${statusLine.take(40)}")
            }
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..499) {
                val coreMsg = readRecentCoreLog()
                val detail = if (coreMsg.isNotEmpty()) " ($coreMsg)" else ""
                error("HTTP $code$detail")
            }
            return (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        }
    }

    private fun readExact(inS: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = inS.read(buf, off, buf.size - off)
            if (r <= 0) throw java.io.EOFException("Connection closed by peer")
            off += r
        }
    }

    private fun readHttpLine(inS: InputStream): String {
        val bos = ByteArrayOutputStream()
        while (bos.size() < 512) {
            val b = inS.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
        }
        return String(bos.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun startNetworkWatch() {
        val ctx = appCtx ?: return
        stopNetworkWatch()
        netCallback = PhysicalNetwork.watch(ctx) { onPhysicalNetworkChanged() }
    }

    private fun stopNetworkWatch() {
        val ctx = appCtx ?: return
        PhysicalNetwork.unwatch(ctx, netCallback)
        netCallback = null
    }

    private fun onPhysicalNetworkChanged() {
        val ctx = appCtx ?: return
        if (!_state.value.isRunning) return
        val phys = PhysicalNetwork.current(ctx) ?: return
        if (phys.interfaceName == lastIface) return
        val cfg = activeConfig ?: return
        AppLog.log("SINGBOX", "Network changed ${lastIface} -> ${phys.interfaceName}, reloading core")
        lastIface = phys.interfaceName
        defaultListener?.let { pushDefaultInterface(it) }
        Thread {
            try {
                commandServer?.startOrReloadService(cfg, OverrideOptions())
            } catch (e: Throwable) {
                AppLog.log("SINGBOX", "Reload after network change failed", e)
            }
        }.start()
    }

    // =========================================================================
    // NekoBox ConfigBuilder & Protocol Formatters (ported from NekoBoxForAndroid)
    // =========================================================================

    private fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4.matches(h) || h.contains(":")
    }

    private fun isPrivateOrLoopbackIpv4(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.") || ip.startsWith("0.")) {
            return true
        }
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: 0
            if (second in 16..31) return true
        }
        return false
    }

    private fun resolvePublicIpv4WithTimeout(context: Context?, host: String, timeoutMs: Long = 2000L): String? {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty() || isIpLiteral(h)) return null
        return try {
            val fut = dnsExec.submit<String?> { PhysicalNetwork.resolveIpv4(context, h) }
            val ipv4 = fut.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ipv4.isNullOrBlank() && !isPrivateOrLoopbackIpv4(ipv4)) ipv4 else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildNekoBoxConfigCandidates(
        context: Context,
        input: String,
        overrides: XrayOverrides,
        listenPort: Int
    ): List<Pair<String, String>> {
        val normalizedInput = extractProxyInput(input)
        val baseRoot = buildBaseConfigJsonObject(normalizedInput, overrides, listenPort)

        val outbounds = baseRoot.optJSONArray("outbounds") ?: JSONArray()
        val mainOb = outbounds.optJSONObject(0) ?: error("No proxy outbound found in config")
        val mainTag = mainOb.optString("tag", "proxy").ifBlank { "proxy" }
        mainOb.put("tag", mainTag)

        // Find the first outbound that has a "server" address (in case outbounds[0] is a selector)
        var targetOb: JSONObject = mainOb
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            if (item.optString("server", "").isNotBlank()) {
                targetOb = item
                break
            }
        }
        val rawServerHost = targetOb.optString("server", "").trim()
        val resolvedServerIpv4 = resolvePublicIpv4WithTimeout(context, rawServerHost)

        val physDns = PhysicalNetwork.current(context)?.dnsServers
            ?.firstOrNull { !it.contains(":") }
            ?: "8.8.8.8"

        val candidates = mutableListOf<Pair<String, String>>()

        // Variant 1: NekoBox standard config (OS routing via addDisallowedApplication + dns-out hijack)
        runCatching {
            val root = JSONObject(baseRoot.toString())
            val obs = root.getJSONArray("outbounds")
            if (resolvedServerIpv4 != null) {
                patchServerInOutbounds(obs, rawServerHost, resolvedServerIpv4)
            }
            ensureOutboundTag(obs, "direct", "direct")
            ensureOutboundTag(obs, "bypass", "direct")
            ensureOutboundTag(obs, "block", "block")
            ensureOutboundTag(obs, "dns-out", "dns")

            root.put("dns", buildNekoDnsObject(mainTag, physDns, rawServerHost))
            val rules = JSONArray()
                .put(JSONObject().put("protocol", JSONArray().put("dns")).put("outbound", "dns-out"))
                .put(JSONObject().put("port", JSONArray().put(53)).put("outbound", "dns-out"))
            root.put(
                "route",
                JSONObject()
                    .put("final", mainTag)
                    .put("rules", rules)
            )
            candidates.add("nekobox-direct-os" to root.toString())
        }

        // Variant 2: NekoBox config with uTLS Chrome fingerprint & raw server hostname (for Cloudflare / Bug Host CDNs)
        runCatching {
            val root = JSONObject(baseRoot.toString())
            val obs = root.getJSONArray("outbounds")
            for (i in 0 until obs.length()) {
                val obItem = obs.optJSONObject(i) ?: continue
                val tls = obItem.optJSONObject("tls")
                if (tls != null && tls.optBoolean("enabled", false) && !tls.has("utls")) {
                    tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                }
            }
            ensureOutboundTag(obs, "direct", "direct")
            ensureOutboundTag(obs, "dns-out", "dns")
            root.put("dns", buildNekoDnsObject(mainTag, physDns, rawServerHost))
            val rules = JSONArray()
                .put(JSONObject().put("port", JSONArray().put(53)).put("outbound", "dns-out"))
            root.put(
                "route",
                JSONObject()
                    .put("final", mainTag)
                    .put("rules", rules)
            )
            candidates.add("nekobox-utls-chrome" to root.toString())
        }

        // Variant 3: NekoBox with uTLS Chrome + resolved IPv4
        if (resolvedServerIpv4 != null) {
            runCatching {
                val root = JSONObject(baseRoot.toString())
                val obs = root.getJSONArray("outbounds")
                patchServerInOutbounds(obs, rawServerHost, resolvedServerIpv4)
                for (i in 0 until obs.length()) {
                    val obItem = obs.optJSONObject(i) ?: continue
                    val tls = obItem.optJSONObject("tls")
                    if (tls != null && tls.optBoolean("enabled", false) && !tls.has("utls")) {
                        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                    }
                }
                ensureOutboundTag(obs, "direct", "direct")
                ensureOutboundTag(obs, "dns-out", "dns")
                root.put("dns", buildNekoDnsObject(mainTag, physDns, rawServerHost))
                val rules = JSONArray()
                    .put(JSONObject().put("port", JSONArray().put(53)).put("outbound", "dns-out"))
                root.put(
                    "route",
                    JSONObject()
                        .put("final", mainTag)
                        .put("rules", rules)
                )
                candidates.add("nekobox-utls-ipv4" to root.toString())
            }
        }

        // Variant 4: Plain NekoBox inbound + outbound (zero extra route/dns rules)
        runCatching {
            val root = JSONObject(baseRoot.toString())
            val obs = root.getJSONArray("outbounds")
            if (resolvedServerIpv4 != null) {
                patchServerInOutbounds(obs, rawServerHost, resolvedServerIpv4)
            }
            ensureOutboundTag(obs, "direct", "direct")
            candidates.add("nekobox-plain" to root.toString())
        }

        return candidates
    }

    private fun patchServerInOutbounds(outbounds: JSONArray, rawHost: String, resolvedIp: String) {
        if (rawHost.isBlank()) return
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.optString("server", "").trim() == rawHost) {
                ob.put("server", resolvedIp)
            }
        }
    }

    private fun ensureOutboundTag(outbounds: JSONArray, tag: String, type: String) {
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == tag) return
        }
        outbounds.put(JSONObject().put("type", type).put("tag", tag))
    }

    private fun buildNekoDnsObject(
        proxyTag: String,
        physDnsIp: String,
        rawServerDomain: String
    ): JSONObject {
        val servers = JSONArray()
            .put(
                JSONObject()
                    .put("tag", "dns-remote")
                    .put("address", "tcp://8.8.8.8")
                    .put("detour", proxyTag)
                    .put("strategy", "ipv4_only")
            )
            .put(
                JSONObject()
                    .put("tag", "dns-direct")
                    .put("address", "udp://$physDnsIp")
                    .put("detour", "direct")
                    .put("strategy", "ipv4_only")
            )

        val rules = JSONArray()
        if (rawServerDomain.isNotBlank() && !isIpLiteral(rawServerDomain)) {
            rules.put(
                JSONObject()
                    .put("domain", JSONArray().put(rawServerDomain.lowercase()))
                    .put("server", "dns-direct")
            )
        }
        rules.put(
            JSONObject()
                .put("outbound", JSONArray().put("any"))
                .put("server", "dns-direct")
        )

        return JSONObject()
            .put("servers", servers)
            .put("rules", rules)
            .put("final", "dns-remote")
            .put("strategy", "ipv4_only")
            .put("independent_cache", true)
    }

    private fun extractProxyInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val schemes = listOf(
            "vless://", "vmess://", "trojan://", "ss://",
            "socks://", "socks5://", "socks4://", "socks4a://",
            "http://", "https://", "hy2://", "hysteria2://", "tuic://"
        )
        val firstLineMatch = trimmed.lineSequence()
            .map { it.trim() }
            .firstOrNull { line -> schemes.any { line.startsWith(it, ignoreCase = true) } }
        if (firstLineMatch != null) return firstLineMatch

        val decoded = decodeBase64Flexible(trimmed)
        if (!decoded.isNullOrBlank()) {
            if (decoded.trim().startsWith("{")) return decoded.trim()
            val subMatch = decoded.lineSequence()
                .map { it.trim() }
                .firstOrNull { line -> schemes.any { line.startsWith(it, ignoreCase = true) } }
            if (subMatch != null) return subMatch
        }
        return trimmed
    }

    private fun decodeBase64Flexible(input: String): String? {
        val clean = input.trim().replace("\r", "").replace("\n", "").replace("-", "+").replace("_", "/")
        if (clean.isEmpty()) return null
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        for (flags in intArrayOf(Base64.DEFAULT, Base64.NO_WRAP)) {
            try {
                val bytes = Base64.decode(padded, flags)
                if (bytes.isNotEmpty()) {
                    return String(bytes, StandardCharsets.UTF_8)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun buildBaseConfigJsonObject(
        input: String,
        overrides: XrayOverrides,
        listenPort: Int = LOCAL_SOCKS_PORT
    ): JSONObject {
        if (input.startsWith("{")) {
            val root = JSONObject(input)
            return convertOrPatchJson(root, overrides, listenPort)
        }

        val outbound = when {
            input.startsWith("vless://", ignoreCase = true) -> parseVless(input, overrides)
            input.startsWith("vmess://", ignoreCase = true) -> parseVmess(input, overrides)
            input.startsWith("trojan://", ignoreCase = true) -> parseTrojan(input, overrides)
            input.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(input)
            input.startsWith("socks://", ignoreCase = true) ||
                input.startsWith("socks5://", ignoreCase = true) ||
                input.startsWith("socks4://", ignoreCase = true) ||
                input.startsWith("socks4a://", ignoreCase = true) -> parseSocksUri(input)
            input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) -> parseHttpUri(input, overrides)
            input.startsWith("hy2://", ignoreCase = true) ||
                input.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(input, overrides)
            input.startsWith("tuic://", ignoreCase = true) -> parseTuic(input, overrides)
            else -> error("Unsupported scheme (use vless://, vmess://, trojan://, ss://, socks://, http(s)://, hy2://, tuic://, or JSON)")
        }

        val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "mixed-in")
            .put("listen", listenAddr)
            .put("listen_port", listenPort)
            .put("sniff", true)
            .put("sniff_override_destination", false)

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
    }

    /**
     * Converts or normalizes ANY pasted JSON config:
     * 1. Full NekoBox / sing-box JSON (`outbounds` with `"type"`): strips `tun-in`, `local` DNS, `fakeip`,
     *    and outbound `domain_strategy` (which cause `checkConfig` rejection or EOF in NearChat), keeping
     *    only the real proxy outbounds and wrapping them in a clean `mixed-in` (127.0.0.1:10808) config.
     * 2. Full Xray / v2rayNG JSON (`outbounds` with `"protocol"` / `"settings"`): converts all proxy outbounds
     *    to sing-box format.
     * 3. Single outbound JSON object (`{"type": "vless", ...}` or `{"protocol": "vless", ...}`): wraps into `outbounds`.
     */
    private fun convertOrPatchJson(
        root: JSONObject,
        overrides: XrayOverrides,
        listenPort: Int
    ): JSONObject {
        val rawOutbounds = when {
            root.has("outbounds") -> root.optJSONArray("outbounds") ?: JSONArray()
            root.has("type") || root.has("protocol") -> JSONArray().put(root)
            else -> error("JSON config does not contain 'outbounds'")
        }

        val cleanOutbounds = JSONArray()
        val auxTypes = setOf("direct", "block", "dns", "freedom", "blackhole")

        for (i in 0 until rawOutbounds.length()) {
            val item = rawOutbounds.optJSONObject(i) ?: continue
            val isXray = item.has("protocol") || item.has("settings") || item.has("streamSettings")
            if (isXray) {
                val proto = item.optString("protocol", "").lowercase()
                if (proto in auxTypes || proto.isBlank()) continue
                if (proto in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")) {
                    cleanOutbounds.put(convertXrayOutboundToSingBox(item, overrides))
                }
            } else {
                val type = item.optString("type", "").lowercase()
                if (type in auxTypes || type.isBlank()) continue
                val cloned = JSONObject(item.toString())
                // Remove NekoBox export's domain_strategy on outbound so sing-box forwards domains directly to the proxy server
                cloned.remove("domain_strategy")
                applyOverridesToSingBoxOutbound(cloned, overrides)
                cleanOutbounds.put(cloned)
            }
        }

        if (cleanOutbounds.length() == 0) {
            error("No supported proxy outbound found in JSON config")
        }

        // Ensure the first outbound has a non-empty tag
        val firstOb = cleanOutbounds.getJSONObject(0)
        if (firstOb.optString("tag", "").isBlank()) {
            firstOb.put("tag", "proxy")
        }

        val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "mixed-in")
            .put("listen", listenAddr)
            .put("listen_port", listenPort)
            .put("sniff", true)
            .put("sniff_override_destination", false)

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", cleanOutbounds)
    }

    private fun convertXrayOutboundToSingBox(
        xOb: JSONObject,
        overrides: XrayOverrides
    ): JSONObject {
        val proto = xOb.optString("protocol", "vless").lowercase()
        val settings = xOb.optJSONObject("settings") ?: JSONObject()
        val stream = xOb.optJSONObject("streamSettings") ?: JSONObject()
        val tag = xOb.optString("tag", "proxy").ifBlank { "proxy" }
        val ob = JSONObject().put("type", proto).put("tag", tag)

        var originalHost = ""
        when (proto) {
            "vless", "vmess" -> {
                val v0 = settings.optJSONArray("vnext")?.optJSONObject(0) ?: JSONObject()
                val u0 = v0.optJSONArray("users")?.optJSONObject(0) ?: JSONObject()
                originalHost = v0.optString("address", "").trim()
                ob.put("server", originalHost)
                ob.put("server_port", v0.optInt("port", 443))
                ob.put("uuid", u0.optString("id", ""))
                val flow = u0.optString("flow", "").removeSuffix("-udp443")
                if (flow.isNotBlank() && flow != "auto" && flow != "none") ob.put("flow", flow)
                if (proto == "vmess") {
                    ob.put("security", u0.optString("security", "auto").ifBlank { "auto" })
                    ob.put("alter_id", u0.optInt("alterId", 0))
                }
            }
            "trojan" -> {
                val s0 = settings.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
                originalHost = s0.optString("address", "").trim()
                ob.put("server", originalHost)
                ob.put("server_port", s0.optInt("port", 443))
                ob.put("password", s0.optString("password", ""))
            }
            "shadowsocks" -> {
                val s0 = settings.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
                originalHost = s0.optString("address", "").trim()
                ob.put("server", originalHost)
                ob.put("server_port", s0.optInt("port", 443))
                ob.put("method", s0.optString("method", "aes-256-gcm"))
                ob.put("password", s0.optString("password", ""))
            }
            "socks", "http" -> {
                val s0 = settings.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
                originalHost = s0.optString("address", "").trim()
                ob.put("server", originalHost)
                ob.put("server_port", s0.optInt("port", 1080))
                val u0 = s0.optJSONArray("users")?.optJSONObject(0)
                if (u0 != null) {
                    val user = u0.optString("user", "")
                    val pass = u0.optString("pass", "")
                    if (user.isNotBlank()) ob.put("username", user)
                    if (pass.isNotBlank()) ob.put("password", pass)
                }
            }
        }

        val security = stream.optString("security", "none").lowercase()
        val net = stream.optString("network", "tcp").lowercase()

        // Extract transport host first so TLS SNI can fall back to it if serverName is blank
        val xWs = stream.optJSONObject("wsSettings")
        val wsHdrObj = xWs?.optJSONObject("headers")
        val wsHost = (wsHdrObj?.optString("Host", "")?.ifBlank { wsHdrObj.optString("host", "") } ?: "")
            .ifBlank { xWs?.optString("host", "") ?: "" }

        if (security == "tls" || security == "xtls") {
            val xTls = stream.optJSONObject("tlsSettings") ?: JSONObject()
            val fallbackDomain = originalHost.takeIf { !isIpLiteral(it) } ?: ""
            val sni = overrides.sniOverride.trim()
                .ifEmpty {
                    xTls.optString("serverName", "").trim()
                        .ifEmpty { overrides.hostOverride.trim().ifEmpty { wsHost.ifEmpty { fallbackDomain } } }
                }
            val insecure = overrides.allowInsecure || xTls.optBoolean("allowInsecure", false)
            val fp = xTls.optString("fingerprint", "").trim()
            val tls = JSONObject()
                .put("enabled", true)
                .put("insecure", insecure)
            if (sni.isNotBlank()) tls.put("server_name", sni)
            val alpnArr = xTls.optJSONArray("alpn")
            if (alpnArr != null && alpnArr.length() > 0) {
                tls.put("alpn", alpnArr)
            }
            if (fp.isNotEmpty()) {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
            ob.put("tls", tls)
        } else if (security == "reality") {
            val xReal = stream.optJSONObject("realitySettings") ?: JSONObject()
            val sni = overrides.sniOverride.trim()
                .ifEmpty { xReal.optString("serverName", "").ifBlank { originalHost } }
            val fp = xReal.optString("fingerprint", "chrome").ifBlank { "chrome" }
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", sni)
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                .put(
                    "reality",
                    JSONObject()
                        .put("enabled", true)
                        .put("public_key", xReal.optString("publicKey", ""))
                        .put("short_id", xReal.optString("shortId", ""))
                )
            ob.put("tls", tls)
        }

        when (net) {
            "ws" -> {
                val host = overrides.hostOverride.trim().ifEmpty { wsHost }
                val rawPath = xWs?.optString("path", "/")?.ifBlank { "/" } ?: "/"
                val ed = xWs?.optInt("maxEarlyData", 0) ?: 0
                val eh = xWs?.optString("earlyDataHeaderName", "") ?: ""
                ob.put("transport", buildWsTransport(rawPath, host, ed, eh))
            }
            "grpc" -> {
                val xGrpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                ob.put(
                    "transport",
                    JSONObject()
                        .put("type", "grpc")
                        .put("service_name", xGrpc.optString("serviceName", "").removePrefix("/"))
                )
            }
            "httpupgrade" -> {
                val xHu = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                val host = overrides.hostOverride.trim().ifEmpty { xHu.optString("host", "") }
                val hu = JSONObject()
                    .put("type", "httpupgrade")
                    .put("path", xHu.optString("path", "/").ifBlank { "/" })
                if (host.isNotBlank()) hu.put("host", host)
                ob.put("transport", hu)
            }
            "http", "h2" -> {
                val xHttp = stream.optJSONObject("httpSettings") ?: JSONObject()
                val host = overrides.hostOverride.trim()
                val hosts = if (host.isNotEmpty()) {
                    JSONArray().put(host)
                } else {
                    xHttp.optJSONArray("host") ?: JSONArray()
                }
                val httpTr = JSONObject()
                    .put("type", "http")
                    .put("path", xHttp.optString("path", "/").ifBlank { "/" })
                if (hosts.length() > 0) httpTr.put("host", hosts)
                if (security != "tls" && security != "reality") httpTr.put("method", "GET")
                ob.put("transport", httpTr)
            }
            "tcp" -> {
                val tcpHeader = stream.optJSONObject("tcpSettings")?.optJSONObject("header")
                if (tcpHeader?.optString("type", "")?.lowercase() == "http") {
                    val req = tcpHeader.optJSONObject("request")
                    val hdrs = req?.optJSONObject("headers")
                    val hdrHost = hdrs?.optJSONArray("Host")?.optString(0, "")
                        ?.ifBlank { hdrs.optJSONArray("host")?.optString(0, "") ?: "" }
                        ?: hdrs?.optString("Host", "")?.ifBlank { hdrs.optString("host", "") }
                        ?: ""
                    val effHost = overrides.hostOverride.trim().ifEmpty { hdrHost }
                    val path = req?.optJSONArray("path")?.optString(0, "/") ?: "/"
                    val httpTr = JSONObject()
                        .put("type", "http")
                        .put("method", "GET")
                        .put("path", path.ifBlank { "/" })
                    if (effHost.isNotBlank()) {
                        httpTr.put("host", JSONArray(effHost.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    }
                    ob.put("transport", httpTr)
                } else if (security == "none" && overrides.hostOverride.trim().isNotEmpty()) {
                    val effHost = overrides.hostOverride.trim()
                    val httpTr = JSONObject()
                        .put("type", "http")
                        .put("method", "GET")
                        .put("path", "/")
                        .put("host", JSONArray(effHost.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    ob.put("transport", httpTr)
                }
            }
        }

        return ob
    }

    private fun applyOverridesToSingBoxOutbound(
        ob: JSONObject,
        overrides: XrayOverrides
    ) {
        val hostOv = overrides.hostOverride.trim()
        val tr = ob.optJSONObject("transport")
        if (tr != null) {
            val trType = tr.optString("type", "")
            if (trType == "ws") {
                val rawPath = tr.optString("path", "/")
                if (rawPath.contains("?ed=")) {
                    val after = rawPath.substringAfter("?ed=")
                    tr.put("path", rawPath.substringBefore("?ed=").ifBlank { "/" })
                    if (!tr.has("max_early_data")) {
                        tr.put("max_early_data", after.substringBefore("&").trim().toIntOrNull() ?: 2048)
                    }
                    if (!tr.has("early_data_header_name")) {
                        tr.put("early_data_header_name", "Sec-WebSocket-Protocol")
                    }
                }
                if (hostOv.isNotEmpty()) {
                    val hdrs = tr.optJSONObject("headers") ?: JSONObject().also { tr.put("headers", it) }
                    hdrs.remove("host")
                    hdrs.put("Host", hostOv)
                }
            } else if (trType == "httpupgrade" && hostOv.isNotEmpty()) {
                tr.put("host", hostOv)
            } else if (trType == "http" && hostOv.isNotEmpty()) {
                tr.put("host", JSONArray(hostOv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            }
        }
        val tls = ob.optJSONObject("tls")
        if (tls != null) {
            val sniOv = overrides.sniOverride.trim()
            if (sniOv.isNotEmpty()) tls.put("server_name", sniOv)
            if (overrides.allowInsecure) tls.put("insecure", true)
        }
    }

    private fun buildWsTransport(
        rawPath: String,
        effectiveHost: String,
        queryEd: Int,
        queryEh: String
    ): JSONObject {
        var cleanPath = rawPath.ifBlank { "/" }
        var maxEarlyData = queryEd
        var earlyHeader = queryEh

        if (cleanPath.contains("?ed=")) {
            val after = cleanPath.substringAfter("?ed=")
            cleanPath = cleanPath.substringBefore("?ed=").ifBlank { "/" }
            val parsedEd = after.substringBefore("&").trim().toIntOrNull() ?: 2048
            if (maxEarlyData <= 0) maxEarlyData = parsedEd
            if (earlyHeader.isBlank()) earlyHeader = "Sec-WebSocket-Protocol"
        }
        if (!cleanPath.startsWith("/")) cleanPath = "/$cleanPath"

        val ws = JSONObject()
            .put("type", "ws")
            .put("path", cleanPath)
        if (effectiveHost.isNotBlank()) {
            ws.put("headers", JSONObject().put("Host", effectiveHost))
        }
        if (maxEarlyData > 0) {
            ws.put("max_early_data", maxEarlyData)
            ws.put("early_data_header_name", earlyHeader.ifBlank { "Sec-WebSocket-Protocol" })
        }
        return ws
    }

    private data class ParsedProxyUri(
        val scheme: String,
        val userInfo: String,
        val username: String,
        val password: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: Map<String, String>,
        val fragment: String
    )

    private fun urlDecodeSafe(s: String): String = runCatching {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(s)

    private fun parseProxyUri(raw: String, defaultPort: Int): ParsedProxyUri {
        val trimmed = raw.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        val afterScheme = trimmed.substringAfter("://", "")
        val fragment = if (afterScheme.contains("#")) urlDecodeSafe(afterScheme.substringAfter("#")) else ""
        val withoutFrag = afterScheme.substringBefore("#")
        val rawQuery = if (withoutFrag.contains("?")) withoutFrag.substringAfter("?") else ""
        val authorityAndPath = withoutFrag.substringBefore("?")

        val slashIdx = authorityAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) authorityAndPath.substring(0, slashIdx) else authorityAndPath
        val rawPath = if (slashIdx >= 0) authorityAndPath.substring(slashIdx) else ""

        val atIdx = authority.lastIndexOf('@')
        val rawUserInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority

        val userInfo = urlDecodeSafe(rawUserInfo)
        val username = if (rawUserInfo.contains(":")) urlDecodeSafe(rawUserInfo.substringBefore(":")) else userInfo
        val password = if (rawUserInfo.contains(":")) urlDecodeSafe(rawUserInfo.substringAfter(":")) else ""

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket > 0) {
                host = hostPort.substring(1, closeBracket)
                val portStr = hostPort.substring(closeBracket + 1).removePrefix(":")
                port = portStr.toIntOrNull() ?: defaultPort
            } else {
                host = hostPort.removeSurrounding("[", "]")
                port = defaultPort
            }
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                host = hostPort.substring(0, colonIdx).trim()
                port = hostPort.substring(colonIdx + 1).trim().toIntOrNull() ?: defaultPort
            } else {
                host = hostPort.trim()
                port = defaultPort
            }
        }

        val queryMap = LinkedHashMap<String, String>()
        if (rawQuery.isNotBlank()) {
            for (pair in rawQuery.split("&")) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = urlDecodeSafe(pair.substring(0, eq)).trim()
                    val v = urlDecodeSafe(pair.substring(eq + 1)).trim()
                    queryMap[k] = v
                } else if (eq < 0) {
                    queryMap[urlDecodeSafe(pair).trim()] = ""
                }
            }
        }

        return ParsedProxyUri(
            scheme = scheme,
            userInfo = userInfo,
            username = username,
            password = password,
            host = host,
            port = port,
            path = urlDecodeSafe(rawPath),
            query = queryMap,
            fragment = fragment
        )
    }

    private fun applyStreamToSingBox(
        ob: JSONObject,
        q: Map<String, String>,
        urlPath: String,
        defaultHost: String,
        overrides: XrayOverrides,
        defaultSecurity: String = "none"
    ) {
        var network = (q["type"] ?: q["net"] ?: "tcp").lowercase()
        val headerType = (q["headerType"] ?: q["obfs"] ?: "").lowercase()
        if (network == "h2" || headerType == "http") {
            network = "http"
        } else if (network == "websocket") {
            network = "ws"
        }

        var security = (q["security"] ?: q["tls"] ?: defaultSecurity).lowercase()
        if (security == "1" || security == "true" || security == "xtls") {
            security = "tls"
        } else if (security.isBlank()) {
            security = defaultSecurity
        }
        val pbk = (q["pbk"] ?: "").trim()
        if (pbk.isNotEmpty()) {
            security = "reality"
        }

        val effectiveHost = overrides.hostOverride.trim()
            .ifEmpty { (q["host"] ?: q["obfsParam"] ?: "").trim() }
            .ifEmpty { if (security == "none") overrides.sniOverride.trim() else "" }

        val fallbackDomainHost = defaultHost.takeIf { !isIpLiteral(it) } ?: ""
        val effectiveSni = overrides.sniOverride.trim()
            .ifEmpty {
                (q["sni"] ?: q["peer"] ?: q["tlsServerName"] ?: "").trim()
                    .ifEmpty { effectiveHost.ifEmpty { fallbackDomainHost } }
            }

        val linkInsecure = (q["allowInsecure"] ?: q["insecure"] ?: "").lowercase()
        val effectiveInsecure = overrides.allowInsecure || linkInsecure == "1" || linkInsecure == "true"

        val rawPath = (q["path"] ?: urlPath).ifBlank { "/" }
        val ed = (q["ed"] ?: "").toIntOrNull() ?: 0
        val eh = (q["eh"] ?: "").trim()

        if (network == "tcp" && security == "none" && overrides.hostOverride.trim().isNotEmpty()) {
            network = "http"
        }

        when (network) {
            "ws" -> {
                ob.put("transport", buildWsTransport(rawPath, effectiveHost, ed, eh))
            }
            "http" -> {
                val httpTr = JSONObject()
                    .put("type", "http")
                    .put("path", rawPath.substringBefore("?ed=").ifBlank { "/" })
                if (security != "tls" && security != "reality") {
                    httpTr.put("method", "GET")
                }
                if (effectiveHost.isNotBlank()) {
                    val hosts = effectiveHost.split(",", "|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (hosts.isNotEmpty()) {
                        httpTr.put("host", JSONArray(hosts))
                    }
                }
                ob.put("transport", httpTr)
            }
            "grpc" -> {
                val svc = (q["serviceName"] ?: q["service_name"] ?: q["path"] ?: urlPath.removePrefix("/")).trim()
                ob.put(
                    "transport",
                    JSONObject().put("type", "grpc").put("service_name", svc)
                )
            }
            "httpupgrade" -> {
                val hu = JSONObject()
                    .put("type", "httpupgrade")
                    .put("path", rawPath.substringBefore("?ed=").ifBlank { "/" })
                if (effectiveHost.isNotBlank()) hu.put("host", effectiveHost)
                ob.put("transport", hu)
            }
            "quic" -> {
                ob.put("transport", JSONObject().put("type", "quic"))
            }
        }

        when (security) {
            "tls" -> {
                val fp = (q["fp"] ?: q["fingerprint"] ?: "").trim()
                val tls = JSONObject()
                    .put("enabled", true)
                    .put("insecure", effectiveInsecure)
                if (effectiveSni.isNotBlank()) {
                    tls.put("server_name", effectiveSni)
                }
                val alpn = q["alpn"]
                if (!alpn.isNullOrBlank()) {
                    val alpnList = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (alpnList.isNotEmpty()) tls.put("alpn", JSONArray(alpnList))
                }
                if (fp.isNotEmpty()) {
                    tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                }
                ob.put("tls", tls)
            }
            "reality" -> {
                val fp = (q["fp"] ?: q["fingerprint"] ?: "").trim().ifBlank { "chrome" }
                val tls = JSONObject()
                    .put("enabled", true)
                    .put("server_name", effectiveSni.ifBlank { defaultHost })
                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                    .put(
                        "reality",
                        JSONObject()
                            .put("enabled", true)
                            .put("public_key", pbk)
                            .put("short_id", (q["sid"] ?: "").trim())
                    )
                ob.put("tls", tls)
            }
        }
    }

    private fun parsePacketEncoding(q: Map<String, String>): String {
        return when ((q["packetEncoding"] ?: q["packet_encoding"] ?: "").lowercase()) {
            "packet", "packetaddr" -> "packetaddr"
            "xudp" -> "xudp"
            else -> ""
        }
    }

    private fun parseVless(raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val uuid = parsed.userInfo.ifBlank { error("Missing UUID in VLESS link") }
        val host = parsed.host.ifBlank { error("Missing host in VLESS link") }
        val q = parsed.query

        val ob = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", parsed.port)
            .put("uuid", uuid)

        val flow = (q["flow"] ?: "").trim().removeSuffix("-udp443")
        if (flow.isNotBlank() && flow != "auto" && flow != "none") {
            ob.put("flow", flow)
        }
        val pktEnc = parsePacketEncoding(q)
        if (pktEnc.isNotEmpty()) {
            ob.put("packet_encoding", pktEnc)
        }
        applyStreamToSingBox(ob, q, parsed.path, host, overrides)
        return ob
    }

    private fun parseVmess(raw: String, overrides: XrayOverrides): JSONObject {
        val body = raw.substringAfter("://").trim()
        val b64Candidate = body.substringBefore("#").substringBefore("?").trim()
        val decodedJson = decodeBase64Flexible(b64Candidate)
        if (decodedJson != null && decodedJson.trim().startsWith("{")) {
            val obj = JSONObject(decodedJson.trim())
            val host = obj.optString("add", "").trim().ifBlank { error("Missing server address in VMess JSON") }
            val port = obj.optString("port", "443").trim().toIntOrNull() ?: obj.optInt("port", 443)
            val uuid = obj.optString("id", "").trim().ifBlank { error("Missing UUID in VMess JSON") }
            val aid = obj.optString("aid", "0").trim().toIntOrNull() ?: obj.optInt("aid", 0)
            val scy = obj.optString("scy", "auto").trim().ifBlank { "auto" }

            val q = LinkedHashMap<String, String>()
            val net = obj.optString("net", "tcp").trim().ifBlank { "tcp" }
            val headerType = obj.optString("type", "").trim()
            q["type"] = if (net.equals("tcp", ignoreCase = true) && headerType.equals("http", ignoreCase = true)) {
                "http"
            } else {
                net
            }
            if (headerType.isNotEmpty()) q["headerType"] = headerType
            val tlsVal = obj.optString("tls", "").trim().lowercase()
            q["security"] = if (tlsVal == "tls" || tlsVal == "reality" || tlsVal == "1" || tlsVal == "true") tlsVal else "none"
            val hostHdr = obj.optString("host", "").trim()
            if (hostHdr.isNotEmpty()) q["host"] = hostHdr
            val pathVal = obj.optString("path", "").trim()
            if (pathVal.isNotEmpty()) q["path"] = pathVal
            val sniVal = obj.optString("sni", "").trim().ifEmpty { hostHdr }
            if (sniVal.isNotEmpty()) q["sni"] = sniVal
            val alpnVal = obj.optString("alpn", "").trim()
            if (alpnVal.isNotEmpty()) q["alpn"] = alpnVal
            val fpVal = obj.optString("fp", "").trim()
            if (fpVal.isNotEmpty()) q["fp"] = fpVal
            if (obj.optBoolean("allowInsecure", false) || obj.optString("allowInsecure") == "1") {
                q["allowInsecure"] = "1"
            }

            val ob = JSONObject()
                .put("type", "vmess")
                .put("tag", "proxy")
                .put("server", host)
                .put("server_port", port)
                .put("uuid", uuid)
                .put("alter_id", aid)
                .put("security", scy)
            applyStreamToSingBox(ob, q, pathVal, host, overrides)
            return ob
        }

        if (decodedJson != null && decodedJson.contains("@")) {
            val rewritten = "vmess://${decodedJson.trim()}" +
                (if (body.contains("?")) "?" + body.substringAfter("?") else "")
            val parsed = parseProxyUri(rewritten, 443)
            val q = LinkedHashMap(parsed.query)
            val obfs = (q["obfs"] ?: "").lowercase()
            if (obfs == "websocket" || obfs == "ws") q["type"] = "ws"
            val obfsParam = q["obfsParam"] ?: ""
            if (obfsParam.startsWith("{")) {
                runCatching {
                    val h = JSONObject(obfsParam).optString("Host", "")
                    if (h.isNotBlank()) q["host"] = h
                }
            } else if (obfsParam.isNotBlank() && q["host"].isNullOrBlank()) {
                q["host"] = obfsParam
            }
            val ob = JSONObject()
                .put("type", "vmess")
                .put("tag", "proxy")
                .put("server", parsed.host)
                .put("server_port", parsed.port)
                .put("uuid", parsed.password.ifBlank { parsed.username })
                .put("alter_id", (q["alterId"] ?: "0").toIntOrNull() ?: 0)
                .put("security", parsed.username.takeIf { parsed.password.isNotBlank() } ?: "auto")
            applyStreamToSingBox(ob, q, parsed.path, parsed.host, overrides)
            return ob
        }

        val parsed = parseProxyUri(raw, 443)
        val ob = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", parsed.host)
            .put("server_port", parsed.port)
            .put("uuid", parsed.userInfo)
            .put("alter_id", (parsed.query["alterId"] ?: "0").toIntOrNull() ?: 0)
            .put("security", (parsed.query["encryption"] ?: "auto").ifBlank { "auto" })
        val pktEnc = parsePacketEncoding(parsed.query)
        if (pktEnc.isNotEmpty()) ob.put("packet_encoding", pktEnc)
        applyStreamToSingBox(ob, parsed.query, parsed.path, parsed.host, overrides)
        return ob
    }

    private fun parseTrojan(raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val pass = parsed.userInfo.ifBlank { error("Missing password in Trojan link") }
        val host = parsed.host.ifBlank { error("Missing host in Trojan link") }

        val ob = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", parsed.port)
            .put("password", pass)
        applyStreamToSingBox(ob, parsed.query, parsed.path, host, overrides, defaultSecurity = "tls")
        return ob
    }

    private fun parseShadowsocks(raw: String): JSONObject {
        val withoutScheme = raw.substringAfter("://").substringBefore("#").trim()
        val queryPart = if (withoutScheme.contains("?")) withoutScheme.substringAfter("?") else ""
        val mainPart = withoutScheme.substringBefore("?")

        val method: String
        val pass: String
        val host: String
        val port: Int

        if (mainPart.contains("@")) {
            val left = urlDecodeSafe(mainPart.substringBeforeLast("@"))
            val right = mainPart.substringAfterLast("@")
            val decodedLeft = if (left.contains(":")) {
                left
            } else {
                decodeBase64Flexible(left) ?: left
            }
            method = decodedLeft.substringBefore(":")
            pass = decodedLeft.substringAfter(":")
            host = right.substringBeforeLast(":").removeSurrounding("[", "]")
            port = right.substringAfterLast(":").toIntOrNull() ?: 8388
        } else {
            val decoded = decodeBase64Flexible(mainPart) ?: error("Invalid Shadowsocks base64 link")
            method = decoded.substringBefore(":")
            val rest = decoded.substringAfter(":")
            pass = rest.substringBeforeLast("@")
            val right = rest.substringAfterLast("@")
            host = right.substringBeforeLast(":").removeSurrounding("[", "]")
            port = right.substringAfterLast(":").toIntOrNull() ?: 8388
        }

        val ob = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", pass)

        if (queryPart.isNotBlank()) {
            val parsed = parseProxyUri("ss://dummy@localhost:8388?$queryPart", 8388)
            val rawPlugin = parsed.query["plugin"] ?: ""
            if (rawPlugin.isNotBlank()) {
                val fixed = if (rawPlugin.startsWith("simple-obfs")) {
                    rawPlugin.replaceFirst("simple-obfs", "obfs-local")
                } else {
                    rawPlugin
                }
                val pluginName = fixed.substringBefore(";")
                val pluginOpts = fixed.substringAfter(";", "")
                if (pluginName.isNotBlank() && pluginName != "none") {
                    ob.put("plugin", pluginName)
                    if (pluginOpts.isNotBlank()) ob.put("plugin_opts", pluginOpts)
                }
            }
        }
        return ob
    }

    private fun parseSocksUri(raw: String): JSONObject {
        val body = raw.substringAfter("://").substringBefore("#").trim()
        val normalized = if (!body.contains(":") && !body.contains("@")) {
            val dec = decodeBase64Flexible(body) ?: body
            "${raw.substringBefore("://")}://$dec"
        } else {
            raw
        }
        val parsed = parseProxyUri(normalized, 1080)
        val host = parsed.host.ifBlank { error("Missing host in SOCKS link") }
        val ob = JSONObject()
            .put("type", "socks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", parsed.port)

        var user = parsed.username
        var pass = parsed.password
        if (pass.isEmpty() && user.isNotEmpty() && !raw.substringAfter("://").substringBefore("@").contains(":")) {
            val dec = decodeBase64Flexible(user)
            if (dec != null && dec.contains(":")) {
                user = dec.substringBefore(":")
                pass = dec.substringAfter(":")
            }
        }
        if (user.isNotBlank()) ob.put("username", user)
        if (pass.isNotBlank()) ob.put("password", pass)
        when (parsed.scheme) {
            "socks4" -> ob.put("version", "4")
            "socks4a" -> ob.put("version", "4a")
            else -> ob.put("version", "5")
        }
        return ob
    }

    private fun parseHttpUri(raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, if (raw.startsWith("https://", true)) 443 else 8080)
        val host = parsed.host.ifBlank { error("Missing host in HTTP proxy link") }
        val ob = JSONObject()
            .put("type", "http")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", parsed.port)
        if (parsed.username.isNotBlank()) ob.put("username", parsed.username)
        if (parsed.password.isNotBlank()) ob.put("password", parsed.password)
        if (parsed.scheme == "https") {
            val sni = overrides.sniOverride.trim()
                .ifEmpty { parsed.query["sni"] ?: overrides.hostOverride.trim().ifEmpty { host } }
            ob.put(
                "tls",
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", sni)
                    .put("insecure", overrides.allowInsecure)
            )
        }
        return ob
    }

    private fun parseHysteria2(raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val host = parsed.host.ifBlank { error("Missing host in Hysteria2 link") }
        val q = parsed.query
        val sni = overrides.sniOverride.trim()
            .ifEmpty { q["sni"] ?: overrides.hostOverride.trim().ifEmpty { host } }
        val insecure = overrides.allowInsecure || q["insecure"] == "1" || q["insecure"] == "true" || q["allowInsecure"] == "1"
        val ob = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", parsed.port)
            .put("password", parsed.userInfo)
            .put(
                "tls",
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", sni)
                    .put("insecure", insecure)
            )
        val obfs = (q["obfs"] ?: "").trim()
        if (obfs.isNotBlank() && obfs != "none") {
            ob.put(
                "obfs",
                JSONObject()
                    .put("type", obfs)
                    .put("password", q["obfs-password"] ?: "")
            )
        }
        return ob
    }

    private fun parseTuic(raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val host = parsed.host.ifBlank { error("Missing host in TUIC link") }
        val q = parsed.query
        val sni = overrides.sniOverride.trim()
            .ifEmpty { q["sni"] ?: overrides.hostOverride.trim().ifEmpty { host } }
        val insecure = overrides.allowInsecure || q["insecure"] == "1" || q["allowInsecure"] == "1"
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", sni)
            .put("insecure", insecure)
        val alpn = q["alpn"]
        if (!alpn.isNullOrBlank()) {
            tls.put("alpn", JSONArray(alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
        }
        return JSONObject()
            .put("type", "tuic")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", parsed.port)
            .put("uuid", parsed.username)
            .put("password", parsed.password)
            .put("congestion_control", (q["congestion_control"] ?: "bbr").ifBlank { "bbr" })
            .put("udp_relay_mode", (q["udp_relay_mode"] ?: "native").ifBlank { "native" })
            .put("tls", tls)
    }
}
