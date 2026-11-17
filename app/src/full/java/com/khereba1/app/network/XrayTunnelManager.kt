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

    /**
     * Collects physical network interfaces primarily via Android ConnectivityManager + LinkProperties
     * (compatible with Android 11–15 SELinux restrictions where NetworkInterface.getNetworkInterfaces()
     * fails or omits cellular rmnet/ccmni interfaces), ensuring every interface has a positive index >= 1.
     */
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
                    flags = 1 or 2 or 16 // FlagUp | FlagRunning | FlagMulticast
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
            // Keep the core's own outbound sockets outside the VPN (no routing loop).
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
                lastCoreError = message
                AppLog.log("SINGBOX", message)
            }
        }
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

    /**
     * Selects the best NekoBox-style sing-box JSON config that passes Libbox.checkConfig().
     * Never strips DNS or routing unless every enhanced candidate fails.
     */
    private fun selectValidatedConfig(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides,
        listenPort: Int = LOCAL_SOCKS_PORT,
        forTest: Boolean = false
    ): String {
        val candidates = buildNekoBoxConfigCandidates(context, rawInput.trim(), overrides, listenPort, forTest)
        var lastErr: Throwable? = null
        for ((label, cfg) in candidates) {
            val res = runCatching {
                Libbox.checkConfig(cfg)
                cfg
            }
            if (res.isSuccess) {
                AppLog.log("SINGBOX", "Using config variant: $label")
                return res.getOrThrow()
            } else {
                lastErr = res.exceptionOrNull()
                AppLog.log("SINGBOX", "Config variant '$label' rejected: ${lastErr?.message}")
            }
        }
        throw lastErr ?: IllegalStateException("Invalid sing-box configuration")
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
            if (!wasRunning) {
                lastCoreError = ""
                val cfg = selectValidatedConfig(context, trimmed, overrides, LOCAL_SOCKS_PORT, forTest = true)
                cs.startOrReloadService(cfg, OverrideOptions())
                if (!waitForPort(LOCAL_SOCKS_PORT, 4000L)) {
                    error(lastCoreError.ifBlank { "sing-box did not open port $LOCAL_SOCKS_PORT" })
                }
            }
            try {
                testProxyLatency(LOCAL_SOCKS_PORT, timeoutMs = 6000)
            } finally {
                if (!wasRunning) {
                    runCatching { cs.closeService() }
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
                val candidates = buildNekoBoxConfigCandidates(context, trimmed, overrides, LOCAL_SOCKS_PORT, forTest = false)
                var up = false
                var activeJson: String? = null

                for ((label, candidate) in candidates) {
                    if (myGen != generation) return@Thread
                    val checkOk = runCatching { Libbox.checkConfig(candidate) }.onFailure {
                        AppLog.log("SINGBOX", "Variant '$label' checkConfig failed: ${it.message}")
                    }.isSuccess
                    if (!checkOk) continue

                    lastCoreError = ""
                    runCatching {
                        cs.startOrReloadService(candidate, OverrideOptions())
                    }.onFailure { e ->
                        AppLog.log("SINGBOX", "Variant '$label' start failed: ${e.message}")
                    }
                    up = waitForPort(LOCAL_SOCKS_PORT, 3500L)
                    if (up) {
                        activeJson = candidate
                        AppLog.log("SINGBOX", "Started sing-box with variant '$label'")
                        break
                    } else {
                        AppLog.log("SINGBOX", "Variant '$label' did not open port ($lastCoreError), trying next")
                        runCatching { cs.closeService() }
                    }
                }

                if (myGen != generation) return@Thread
                if (up && activeJson != null) {
                    val bindHost = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
                    activeConfig = activeJson
                    lastIface = appCtx?.let { PhysicalNetwork.current(it)?.interfaceName }
                    startNetworkWatch()
                    // NekoBox behavior: mark running & start VPN immediately as soon as sing-box opens the port,
                    // never blocking VPN startup on an upfront HTTP probe.
                    _state.value = XrayState(true, "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Starting VPN…")
                    onReady?.invoke()

                    val latencyMs = probeServerLatency(myGen)
                    if (myGen == generation && _state.value.isRunning) {
                        if (latencyMs != null && latencyMs >= 0L) {
                            _state.value = XrayState(
                                true,
                                "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Connected (${latencyMs} ms)"
                            )
                        } else {
                            _state.value = XrayState(
                                true,
                                "sing-box active ($bindHost:$LOCAL_SOCKS_PORT) • Tunnel & VPN running"
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

    /**
     * NekoBox-style URL test (http://cp.cloudflare.com/ & http://connectivitycheck.gstatic.com/generate_204)
     * sent directly through the local proxy using SOCKS5 ATYP=0x03 (Domain Name) and HTTP Proxy GET so
     * Android NEVER attempts local DNS resolution outside the tunnel.
     */
    private fun testProxyLatency(port: Int, timeoutMs: Int = 5500): Long {
        val targets = listOf(
            Triple("cp.cloudflare.com", 80, "/generate_204"),
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
        throw lastError ?: IllegalStateException("Connection test timed out")
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
            // SOCKS5 greeting (no auth)
            outS.write(byteArrayOf(0x05, 0x01, 0x00))
            outS.flush()
            val greet = ByteArray(2)
            readExact(inS, greet)
            if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                error("SOCKS5 handshake rejected")
            }

            // SOCKS5 CONNECT with ATYP = 0x03 (domain name resolved inside sing-box)
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
                error("SOCKS5 connect failed (code ${hdr[1].toInt() and 0xFF})")
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

            val httpReq = "GET $path HTTP/1.1\r\nHost: $targetHost\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
            outS.write(httpReq.toByteArray(StandardCharsets.UTF_8))
            outS.flush()

            val statusLine = readHttpLine(inS)
            if (!statusLine.startsWith("HTTP/1.")) {
                error("Invalid HTTP response: ${statusLine.take(40)}")
            }
            val parts = statusLine.split(" ")
            val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..499) {
                error("HTTP $code")
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
                "User-Agent: Mozilla/5.0\r\n" +
                "Proxy-Connection: close\r\n" +
                "Connection: close\r\n\r\n"
            outS.write(req.toByteArray(StandardCharsets.UTF_8))
            outS.flush()

            val statusLine = readHttpLine(inS)
            if (!statusLine.startsWith("HTTP/1.")) {
                error("Invalid HTTP proxy response")
            }
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..499) {
                error("HTTP $code")
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

    private fun probeServerLatency(gen: Int): Long? {
        for (attempt in 0 until 2) {
            if (gen != generation) return null
            val res = runCatching { testProxyLatency(LOCAL_SOCKS_PORT, timeoutMs = 4500) }
            if (res.isSuccess) return res.getOrNull()
            Thread.sleep(500)
        }
        return null
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

    /** Wi-Fi <-> mobile data switch: tell the core the new default interface and reload so stale sockets die. */
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

    private fun resolveHostWithTimeout(context: Context?, host: String, timeoutMs: Long = 1500L): String {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty() || isIpLiteral(h)) return h
        return try {
            val fut = dnsExec.submit<String?> { PhysicalNetwork.resolveIpv4(context, h) }
            val ipv4 = fut.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ipv4.isNullOrBlank()) ipv4 else h
        } catch (_: Throwable) {
            h
        }
    }

    /**
     * Builds ordered candidate sing-box JSON configurations modeled after NekoBox's ConfigBuilder.kt:
     * 1. Full NekoBox config with `dns-out` outbound + DNS hijack rules + `outbound: ["any"] -> dns-direct` (sing-box 1.8–1.10 & 1.11)
     * 2. Full NekoBox config with `action: "hijack-dns"` (sing-box 1.11+)
     * 3. NekoBox config with non-loopback `dns` + `route` (`auto_detect_interface: true`)
     * 4. Minimal `dns` + `route` (`auto_detect_interface: true`)
     * 5. Base inbound/outbound config
     */
    private fun buildNekoBoxConfigCandidates(
        context: Context,
        input: String,
        overrides: XrayOverrides,
        listenPort: Int,
        forTest: Boolean
    ): List<Pair<String, String>> {
        val normalizedInput = extractProxyInput(input)
        val baseRoot = buildBaseConfigJsonObject(context, normalizedInput, overrides, listenPort)
        val baseJsonString = baseRoot.toString()

        val outbounds = baseRoot.optJSONArray("outbounds") ?: JSONArray()
        val mainOb = outbounds.optJSONObject(0) ?: JSONObject()
        val mainTag = mainOb.optString("tag", "proxy").ifBlank { "proxy" }
        mainOb.put("tag", mainTag)
        val rawServerDomain = mainOb.optString("_raw_server_domain", "").also {
            mainOb.remove("_raw_server_domain")
        }

        val physDns = PhysicalNetwork.current(context)?.dnsServers
            ?.firstOrNull { !it.contains(":") }
            ?: "8.8.8.8"

        val candidates = mutableListOf<Pair<String, String>>()

        // Variant 1: NekoBox standard config with dns-out outbound (works on sing-box 1.8, 1.9, 1.10, 1.11)
        runCatching {
            val root = JSONObject(baseRoot.toString())
            mainOb.remove("_raw_server_domain")
            val obs = root.getJSONArray("outbounds")
            obs.getJSONObject(0).apply {
                remove("_raw_server_domain")
                if (!has("domain_strategy")) put("domain_strategy", "prefer_ipv4")
            }
            ensureOutboundTag(obs, "direct", "direct")
            ensureOutboundTag(obs, "bypass", "direct")
            ensureOutboundTag(obs, "block", "block")
            ensureOutboundTag(obs, "dns-out", "dns")

            if (!root.has("dns")) {
                root.put("dns", buildNekoDnsObject(mainTag, physDns, rawServerDomain, forTest))
            }
            if (!root.has("route")) {
                val rules = JSONArray()
                    .put(JSONObject().put("protocol", JSONArray().put("dns")).put("outbound", "dns-out"))
                    .put(JSONObject().put("port", JSONArray().put(53)).put("outbound", "dns-out"))
                    .put(
                        JSONObject()
                            .put("ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
                            .put("outbound", "block")
                    )
                root.put(
                    "route",
                    JSONObject()
                        .put("auto_detect_interface", true)
                        .put("final", mainTag)
                        .put("rules", rules)
                )
            }
            candidates.add("nekobox-dns-out" to root.toString())
        }

        // Variant 2: NekoBox 1.11+ rule action syntax ("action": "hijack-dns" / "reject")
        runCatching {
            val root = JSONObject(baseRoot.toString())
            val obs = root.getJSONArray("outbounds")
            obs.getJSONObject(0).apply {
                remove("_raw_server_domain")
                if (!has("domain_strategy")) put("domain_strategy", "prefer_ipv4")
            }
            ensureOutboundTag(obs, "direct", "direct")
            ensureOutboundTag(obs, "bypass", "direct")

            if (!root.has("dns")) {
                root.put("dns", buildNekoDnsObject(mainTag, physDns, rawServerDomain, forTest))
            }
            if (!root.has("route")) {
                val rules = JSONArray()
                    .put(JSONObject().put("protocol", JSONArray().put("dns")).put("action", "hijack-dns"))
                    .put(JSONObject().put("port", JSONArray().put(53)).put("action", "hijack-dns"))
                    .put(
                        JSONObject()
                            .put("ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
                            .put("action", "reject")
                    )
                root.put(
                    "route",
                    JSONObject()
                        .put("auto_detect_interface", true)
                        .put("final", mainTag)
                        .put("rules", rules)
                )
            }
            candidates.add("nekobox-action-hijack" to root.toString())
        }

        // Variant 3: NekoBox DNS + auto_detect_interface route (no hijack rule)
        runCatching {
            val root = JSONObject(baseRoot.toString())
            val obs = root.getJSONArray("outbounds")
            obs.getJSONObject(0).remove("_raw_server_domain")
            ensureOutboundTag(obs, "direct", "direct")
            if (!root.has("dns")) {
                root.put("dns", buildNekoDnsObject(mainTag, physDns, rawServerDomain, forTest))
            }
            if (!root.has("route")) {
                root.put(
                    "route",
                    JSONObject()
                        .put("auto_detect_interface", true)
                        .put("final", mainTag)
                )
            }
            candidates.add("nekobox-route-dns" to root.toString())
        }

        // Variant 4: Minimal route with auto_detect_interface
        runCatching {
            val root = JSONObject(baseRoot.toString())
            root.optJSONArray("outbounds")?.optJSONObject(0)?.remove("_raw_server_domain")
            if (!root.has("route")) {
                root.put(
                    "route",
                    JSONObject()
                        .put("auto_detect_interface", true)
                        .put("final", mainTag)
                )
            }
            candidates.add("minimal-route" to root.toString())
        }

        // Variant 5: Plain base config
        val cleanBase = JSONObject(baseJsonString).apply {
            optJSONArray("outbounds")?.optJSONObject(0)?.remove("_raw_server_domain")
        }
        candidates.add("base" to cleanBase.toString())
        return candidates
    }

    private fun ensureOutboundTag(outbounds: JSONArray, tag: String, type: String) {
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == tag) return
        }
        outbounds.put(JSONObject().put("type", type).put("tag", tag))
    }

    /**
     * NekoBox DNS configuration:
     * - `dns-remote`: TCP DNS (`tcp://1.1.1.1`) detoured through `proxy` (works even when UDP is blocked on the server,
     *   and needs zero bootstrap hostname resolution because `1.1.1.1` is an IP literal).
     * - `dns-direct`: Direct UDP DNS (`udp://<physDns>`) detoured through `direct`.
     * - `rules`: `{"outbound": ["any"], "server": "dns-direct"}` (NekoBox's `avoid loopback` rule so outbound server
     *   domain resolution never loops back into `proxy`).
     */
    private fun buildNekoDnsObject(
        proxyTag: String,
        physDnsIp: String,
        rawServerDomain: String,
        forTest: Boolean
    ): JSONObject {
        val servers = JSONArray()
            .put(
                JSONObject()
                    .put("tag", "dns-remote")
                    .put("address", "tcp://1.1.1.1")
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
            .put("final", if (forTest) "dns-remote" else "dns-remote")
            .put("strategy", "ipv4_only")
            .put("independent_cache", true)
    }

    /**
     * Extracts the first supported proxy link or JSON object from raw user input
     * (handles multi-line text or Base64-encoded subscription payloads like NekoBox's RawUpdater).
     */
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
        val clean = input.trim().replace("\r", "").replace("\n", "")
        if (clean.isEmpty()) return null
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP, Base64.NO_WRAP)) {
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
        context: Context,
        input: String,
        overrides: XrayOverrides,
        listenPort: Int = LOCAL_SOCKS_PORT
    ): JSONObject {
        if (input.startsWith("{")) {
            val root = JSONObject(input)
            return convertOrPatchJson(context, root, overrides, listenPort)
        }

        val outbound = when {
            input.startsWith("vless://", ignoreCase = true) -> parseVless(context, input, overrides)
            input.startsWith("vmess://", ignoreCase = true) -> parseVmess(context, input, overrides)
            input.startsWith("trojan://", ignoreCase = true) -> parseTrojan(context, input, overrides)
            input.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(context, input)
            input.startsWith("socks://", ignoreCase = true) ||
                input.startsWith("socks5://", ignoreCase = true) ||
                input.startsWith("socks4://", ignoreCase = true) ||
                input.startsWith("socks4a://", ignoreCase = true) -> parseSocksUri(context, input)
            input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) -> parseHttpUri(context, input, overrides)
            input.startsWith("hy2://", ignoreCase = true) ||
                input.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(context, input, overrides)
            input.startsWith("tuic://", ignoreCase = true) -> parseTuic(context, input, overrides)
            else -> error("Unsupported scheme (use vless://, vmess://, trojan://, ss://, socks://, http(s)://, hy2://, tuic://, or JSON)")
        }

        val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "mixed-in")
            .put("listen", listenAddr)
            .put("listen_port", listenPort)
            .put("sniff", true)
            .put("sniff_override_destination", true)
            .put("domain_strategy", "ipv4_only")

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
    }

    private fun convertOrPatchJson(
        context: Context,
        root: JSONObject,
        overrides: XrayOverrides,
        listenPort: Int
    ): JSONObject {
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val firstOb = outbounds.optJSONObject(0)
        // If user pasted an Xray-format JSON ("protocol" instead of "type"), convert outbound to sing-box
        if (firstOb != null && firstOb.has("protocol") && !firstOb.has("type")) {
            val sbOutbounds = JSONArray()
            for (i in 0 until outbounds.length()) {
                val xOb = outbounds.optJSONObject(i) ?: continue
                val proto = xOb.optString("protocol", "").lowercase()
                if (proto == "vless" || proto == "vmess" || proto == "trojan" || proto == "shadowsocks" || proto == "socks") {
                    sbOutbounds.put(convertXrayOutboundToSingBox(context, xOb, overrides))
                }
            }
            if (sbOutbounds.length() == 0) error("No supported outbound found in JSON")
            val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
            val inbound = JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", listenAddr)
                .put("listen_port", listenPort)
                .put("sniff", true)
                .put("sniff_override_destination", true)
                .put("domain_strategy", "ipv4_only")
            return JSONObject()
                .put("log", JSONObject().put("level", "warn"))
                .put("inbounds", JSONArray().put(inbound))
                .put("outbounds", sbOutbounds)
        }

        // Native sing-box JSON: update inbound listen/port and apply overrides
        val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null && inbounds.length() > 0) {
            inbounds.optJSONObject(0)?.apply {
                put("listen", listenAddr)
                put("listen_port", listenPort)
            }
        } else {
            val inbound = JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", listenAddr)
                .put("listen_port", listenPort)
                .put("sniff", true)
                .put("sniff_override_destination", true)
                .put("domain_strategy", "ipv4_only")
            root.put("inbounds", JSONArray().put(inbound))
        }
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            applyOverridesToSingBoxOutbound(context, ob, overrides)
        }
        return root
    }

    private fun convertXrayOutboundToSingBox(
        context: Context,
        xOb: JSONObject,
        overrides: XrayOverrides
    ): JSONObject {
        val proto = xOb.optString("protocol", "vless").lowercase()
        val settings = xOb.optJSONObject("settings") ?: JSONObject()
        val stream = xOb.optJSONObject("streamSettings") ?: JSONObject()
        val ob = JSONObject().put("type", proto).put("tag", "proxy")

        var originalHost = ""
        if (proto == "vless" || proto == "vmess") {
            val v0 = settings.optJSONArray("vnext")?.optJSONObject(0) ?: JSONObject()
            val u0 = v0.optJSONArray("users")?.optJSONObject(0) ?: JSONObject()
            originalHost = v0.optString("address", "").trim()
            ob.put("server", resolveHostWithTimeout(context, originalHost))
            ob.put("_raw_server_domain", originalHost)
            ob.put("server_port", v0.optInt("port", 443))
            ob.put("uuid", u0.optString("id", ""))
            val flow = u0.optString("flow", "").removeSuffix("-udp443")
            if (flow.isNotBlank() && flow != "auto") ob.put("flow", flow)
            ob.put("packet_encoding", "xudp")
            if (proto == "vmess") {
                ob.put("security", u0.optString("security", "auto").ifBlank { "auto" })
                ob.put("alter_id", u0.optInt("alterId", 0))
            }
        } else if (proto == "trojan") {
            val s0 = settings.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
            originalHost = s0.optString("address", "").trim()
            ob.put("server", resolveHostWithTimeout(context, originalHost))
            ob.put("_raw_server_domain", originalHost)
            ob.put("server_port", s0.optInt("port", 443))
            ob.put("password", s0.optString("password", ""))
        } else if (proto == "shadowsocks") {
            val s0 = settings.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
            originalHost = s0.optString("address", "").trim()
            ob.put("server", resolveHostWithTimeout(context, originalHost))
            ob.put("_raw_server_domain", originalHost)
            ob.put("server_port", s0.optInt("port", 443))
            ob.put("method", s0.optString("method", "aes-256-gcm"))
            ob.put("password", s0.optString("password", ""))
        }

        val security = stream.optString("security", "none").lowercase()
        val net = stream.optString("network", "tcp").lowercase()

        if (security == "tls" || security == "xtls") {
            val xTls = stream.optJSONObject("tlsSettings") ?: JSONObject()
            val sni = overrides.sniOverride.trim()
                .ifEmpty { xTls.optString("serverName", "").ifBlank { overrides.hostOverride.trim().ifEmpty { originalHost } } }
            val insecure = overrides.allowInsecure || xTls.optBoolean("allowInsecure", false)
            val fp = xTls.optString("fingerprint", "").trim()
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", sni)
                .put("insecure", insecure)
            val alpnArr = xTls.optJSONArray("alpn")
            if (alpnArr != null && alpnArr.length() > 0) {
                tls.put("alpn", alpnArr)
            } else if (net == "ws" && fp.isNotEmpty()) {
                tls.put("alpn", JSONArray().put("http/1.1"))
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
                val xWs = stream.optJSONObject("wsSettings") ?: JSONObject()
                val xHost = xWs.optJSONObject("headers")?.optString("Host", "")
                    ?: xWs.optString("host", "")
                val host = overrides.hostOverride.trim().ifEmpty { xHost }
                val rawPath = xWs.optString("path", "/").ifBlank { "/" }
                ob.put("transport", buildWsTransport(rawPath, host, 0, ""))
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
                    val hdrHost = req?.optJSONObject("headers")?.optJSONArray("Host")?.optString(0, "") ?: ""
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
                }
            }
        }

        return ob
    }

    private fun applyOverridesToSingBoxOutbound(
        context: Context,
        ob: JSONObject,
        overrides: XrayOverrides
    ) {
        val rawServer = ob.optString("server", "").trim()
        if (rawServer.isNotEmpty() && !isIpLiteral(rawServer)) {
            ob.put("_raw_server_domain", rawServer)
            ob.put("server", resolveHostWithTimeout(context, rawServer))
        }
        val hostOv = overrides.hostOverride.trim()
        if (hostOv.isNotEmpty()) {
            val tr = ob.optJSONObject("transport")
            if (tr != null) {
                when (tr.optString("type", "")) {
                    "ws" -> {
                        val hdrs = tr.optJSONObject("headers") ?: JSONObject().also { tr.put("headers", it) }
                        hdrs.put("Host", hostOv)
                    }
                    "httpupgrade" -> tr.put("host", hostOv)
                    "http" -> tr.put("host", JSONArray(hostOv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                }
            }
        }
        val tls = ob.optJSONObject("tls")
        if (tls != null) {
            val sniOv = overrides.sniOverride.trim()
            if (sniOv.isNotEmpty()) tls.put("server_name", sniOv)
            if (overrides.allowInsecure) tls.put("insecure", true)
        }
    }

    /**
     * Builds sing-box WebSocket transport exactly like NekoBox's V2RayFmt.kt:
     * strips `?ed=2048` from `path` and populates `max_early_data` + `early_data_header_name`.
     */
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

    /**
     * Fault-tolerant URI parser modeled on NekoBox's parseDuckSoft / HttpUrl handling:
     * never fails on spaces, Arabic characters, emojis, pipes, or unescaped query/fragment strings.
     */
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

    /**
     * Ported from NekoBox's V2RayFmt.kt (`parseDuckSoft`, `buildSingBoxOutboundStreamSettings`, `buildSingBoxOutboundTLS`).
     */
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
        // NekoBox V2RayFmt.kt: if (type == "h2" || headerType == "http") type = "http"
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

        val effectiveSni = overrides.sniOverride.trim()
            .ifEmpty {
                (q["sni"] ?: q["peer"] ?: q["tlsServerName"] ?: "").trim()
                    .ifEmpty { effectiveHost.ifEmpty { defaultHost } }
            }

        val linkInsecure = (q["allowInsecure"] ?: q["insecure"] ?: "").lowercase()
        val effectiveInsecure = overrides.allowInsecure || linkInsecure == "1" || linkInsecure == "true"

        val rawPath = (q["path"] ?: urlPath).ifBlank { "/" }
        val ed = (q["ed"] ?: "").toIntOrNull() ?: 0
        val eh = (q["eh"] ?: "").trim()

        // If plain TCP (no TLS) has an explicit Host override, use NekoBox's HTTP GET header transport
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
                } else if (network == "ws" && fp.isNotEmpty()) {
                    // Prevent uTLS chrome fingerprint from negotiating HTTP/2 ALPN on WebSocket upgrade
                    tls.put("alpn", JSONArray().put("http/1.1"))
                }
                // NekoBox rule: only enable uTLS on standard TLS when fp is explicitly present in the link
                if (fp.isNotEmpty()) {
                    tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                }
                ob.put("tls", tls)
            }
            "reality" -> {
                val fp = (q["fp"] ?: q["fingerprint"] ?: "").trim().ifBlank { "chrome" }
                val tls = JSONObject()
                    .put("enabled", true)
                    .put("server_name", effectiveSni)
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
            "none", "0" -> ""
            else -> "xudp"
        }
    }

    private fun parseVless(context: Context, raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val uuid = parsed.userInfo.ifBlank { error("Missing UUID in VLESS link") }
        val host = parsed.host.ifBlank { error("Missing host in VLESS link") }
        val q = parsed.query

        val ob = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
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

    private fun parseVmess(context: Context, raw: String, overrides: XrayOverrides): JSONObject {
        val body = raw.substringAfter("://").trim()
        // Format 1: Standard v2rayN Base64 JSON (no '?' in link, or Base64 JSON before '#')
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
                .put("server", resolveHostWithTimeout(context, host))
                .put("_raw_server_domain", host)
                .put("server_port", port)
                .put("uuid", uuid)
                .put("alter_id", aid)
                .put("security", scy)
                .put("packet_encoding", "xudp")
            applyStreamToSingBox(ob, q, pathVal, host, overrides)
            return ob
        }

        // Format 2: Kitsunebi (vmess://base64(security:uuid@host:port)?remarks=...&obfs=...)
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
                .put("server", resolveHostWithTimeout(context, parsed.host))
                .put("_raw_server_domain", parsed.host)
                .put("server_port", parsed.port)
                .put("uuid", parsed.password.ifBlank { parsed.username })
                .put("alter_id", (q["alterId"] ?: "0").toIntOrNull() ?: 0)
                .put("security", parsed.username.takeIf { parsed.password.isNotBlank() } ?: "auto")
                .put("packet_encoding", "xudp")
            applyStreamToSingBox(ob, q, parsed.path, parsed.host, overrides)
            return ob
        }

        // Format 3: DuckSoft standard URI (vmess://uuid@host:port?type=ws&security=tls...)
        val parsed = parseProxyUri(raw, 443)
        val ob = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", resolveHostWithTimeout(context, parsed.host))
            .put("_raw_server_domain", parsed.host)
            .put("server_port", parsed.port)
            .put("uuid", parsed.userInfo)
            .put("alter_id", (parsed.query["alterId"] ?: "0").toIntOrNull() ?: 0)
            .put("security", (parsed.query["encryption"] ?: "auto").ifBlank { "auto" })
            .put("packet_encoding", parsePacketEncoding(parsed.query))
        applyStreamToSingBox(ob, parsed.query, parsed.path, parsed.host, overrides)
        return ob
    }

    private fun parseTrojan(context: Context, raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val pass = parsed.userInfo.ifBlank { error("Missing password in Trojan link") }
        val host = parsed.host.ifBlank { error("Missing host in Trojan link") }

        val ob = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
            .put("server_port", parsed.port)
            .put("password", pass)
        applyStreamToSingBox(ob, parsed.query, parsed.path, host, overrides, defaultSecurity = "tls")
        return ob
    }

    private fun parseShadowsocks(context: Context, raw: String): JSONObject {
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
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
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

    private fun parseSocksUri(context: Context, raw: String): JSONObject {
        val body = raw.substringAfter("://").substringBefore("#").trim()
        // Handle v2rayN full-base64 socks:// link if no ':' or '@' in host part
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
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
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

    private fun parseHttpUri(context: Context, raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, if (raw.startsWith("https://", true)) 443 else 8080)
        val host = parsed.host.ifBlank { error("Missing host in HTTP proxy link") }
        val ob = JSONObject()
            .put("type", "http")
            .put("tag", "proxy")
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
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

    private fun parseHysteria2(context: Context, raw: String, overrides: XrayOverrides): JSONObject {
        val parsed = parseProxyUri(raw, 443)
        val host = parsed.host.ifBlank { error("Missing host in Hysteria2 link") }
        val q = parsed.query
        val sni = overrides.sniOverride.trim()
            .ifEmpty { q["sni"] ?: overrides.hostOverride.trim().ifEmpty { host } }
        val insecure = overrides.allowInsecure || q["insecure"] == "1" || q["insecure"] == "true" || q["allowInsecure"] == "1"
        val ob = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
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

    private fun parseTuic(context: Context, raw: String, overrides: XrayOverrides): JSONObject {
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
            .put("server", resolveHostWithTimeout(context, host))
            .put("_raw_server_domain", host)
            .put("server_port", parsed.port)
            .put("uuid", parsed.username)
            .put("password", parsed.password)
            .put("congestion_control", (q["congestion_control"] ?: "bbr").ifBlank { "bbr" })
            .put("udp_relay_mode", (q["udp_relay_mode"] ?: "native").ifBlank { "native" })
            .put("tls", tls)
    }
}
