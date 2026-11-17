package com.khereba1.app.network

import android.content.Context
import android.util.Base64
import com.khereba1.app.AppLog
import com.khereba1.app.PREFS
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

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
        val preferred = appCtx?.let { PhysicalNetwork.current(it)?.interfaceName }
        runCatching {
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                val name = ni.name ?: continue
                if (!ni.isUp || ni.isLoopback || isIgnoredInterface(name)) continue
                val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address ?: return@mapNotNull null
                    val rawHost = a.hostAddress ?: return@mapNotNull null
                    val cleanHost = rawHost.substringBefore('%')
                    "$cleanHost/${ia.networkPrefixLength}"
                }
                if (addrs.isEmpty()) continue
                val boxNi = io.nekohasekai.libbox.NetworkInterface().apply {
                    index = ni.index
                    mtu = runCatching { ni.mtu }.getOrDefault(1500).let { if (it > 0) it else 1500 }
                    this.name = name
                    flags = 1 or 2 or 16 // FlagUp | FlagRunning | FlagMulticast
                    addresses = toStringIterator(addrs)
                    dnsServer = toStringIterator(listOf("8.8.8.8", "1.1.1.1"))
                }
                list.add(boxNi)
            }
        }
        // Put the real default network first so "first interface" is never dummy0 / a stale one.
        if (preferred != null) {
            val head = list.filter { it.name == preferred }
            if (head.isNotEmpty()) {
                val tail = list.filter { it.name != preferred }
                list.clear()
                list.addAll(head)
                list.addAll(tail)
            }
        }
        return list
    }

    private fun pushDefaultInterface(listener: InterfaceUpdateListener) {
        val phys = appCtx?.let { PhysicalNetwork.current(it) }
        if (phys != null) {
            val idx = runCatching { java.net.NetworkInterface.getByName(phys.interfaceName)?.index }
                .getOrNull() ?: 0
            lastIface = phys.interfaceName
            runCatching { listener.updateDefaultInterface(phys.interfaceName, idx, false, false) }
            return
        }
        val first = collectBoxInterfaces().firstOrNull()
        if (first != null) {
            runCatching { listener.updateDefaultInterface(first.name, first.index, false, false) }
        } else {
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
        java.net.Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 250)
            true
        }
    }.getOrDefault(false)

    private fun waitForPort(port: Int, timeoutMs: Long = 2500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port)) return true
            Thread.sleep(150)
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
            val wasRunning = _state.value.isRunning
            val cs = getOrCreateServer(context)
            if (!wasRunning) {
                lastCoreError = ""
                val cfg = buildConfigJson(rawInput.trim(), overrides, listenPort = LOCAL_SOCKS_PORT)
                Libbox.checkConfig(cfg)
                cs.startOrReloadService(cfg, OverrideOptions())
                if (!waitForPort(LOCAL_SOCKS_PORT, 3500L)) {
                    error(lastCoreError.ifBlank { "sing-box did not open port $LOCAL_SOCKS_PORT" })
                }
            }
            try {
                val t0 = System.currentTimeMillis()
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT))
                val conn = (URL("https://www.gstatic.com/generate_204").openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..399) error("HTTP $code")
                System.currentTimeMillis() - t0
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
                val baseJson = buildConfigJson(trimmed, overrides, listenPort = LOCAL_SOCKS_PORT)
                // Preferred config: DNS hijack + auto-detect interface. If this libbox rejects it,
                // fall back to the plain inbound/outbound config so Start never gets worse than before.
                val enhancedJson: String? = runCatching {
                    enhanceConfig(baseJson).also { Libbox.checkConfig(it) }
                }.onFailure {
                    AppLog.log("SINGBOX", "Enhanced config rejected, using basic: ${it.message}")
                }.getOrNull()
                var activeJson: String = enhancedJson ?: baseJson.also { Libbox.checkConfig(it) }
                cs.startOrReloadService(activeJson, OverrideOptions())
                var up = waitForPort(LOCAL_SOCKS_PORT, 3500L)
                if (!up && enhancedJson != null) {
                    AppLog.log("SINGBOX", "Core failed with enhanced config (${lastCoreError}); retrying basic")
                    runCatching { cs.closeService() }
                    lastCoreError = ""
                    activeJson = baseJson
                    Libbox.checkConfig(activeJson)
                    cs.startOrReloadService(activeJson, OverrideOptions())
                    up = waitForPort(LOCAL_SOCKS_PORT, 3500L)
                }
                if (myGen != generation) return@Thread
                if (up) {
                    val bindHost = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
                    activeConfig = activeJson
                    lastIface = appCtx?.let { PhysicalNetwork.current(it)?.interfaceName }
                    startNetworkWatch()
                    _state.value = XrayState(true, "sing-box active ($bindHost:$LOCAL_SOCKS_PORT), checking server…")
                    val reachable = probeServer(myGen)
                    if (myGen == generation) {
                        if (reachable) {
                            _state.value = XrayState(true, "sing-box active ($bindHost:$LOCAL_SOCKS_PORT), server OK")
                            onReady?.invoke()
                        } else {
                            _state.value = XrayState(
                                true,
                                "Tunnel is up but the server is NOT reachable, so the VPN was not started. " +
                                    "Check the config/server, then Stop and Start again."
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

    /** True only if a real request succeeds THROUGH the proxy (the local port being open proves nothing). */
    private fun probeServer(gen: Int): Boolean {
        val urls = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204"
        )
        for (attempt in 0 until 2) {
            for (u in urls) {
                if (gen != generation) return false
                val ok = runCatching {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT))
                    val conn = (URL(u).openConnection(proxy) as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        instanceFollowRedirects = false
                    }
                    try {
                        conn.responseCode in 200..399
                    } finally {
                        conn.disconnect()
                    }
                }.getOrDefault(false)
                if (ok) return true
            }
            Thread.sleep(800)
        }
        return false
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

    /**
     * Adds what a VPN-fed core needs: DNS answered inside the core over the proxy (DoH, works even if the
     * server has no UDP) and auto-detected outbound interface. Only touches configs without their own route/dns.
     */
    private fun enhanceConfig(json: String): String {
        val root = JSONObject(json)
        val tag = root.optJSONArray("outbounds")?.optJSONObject(0)?.optString("tag", "").orEmpty()
        val hasDns = root.has("dns")
        if (!hasDns && tag.isNotBlank()) {
            root.put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("tag", "dns-remote")
                                .put("address", "https://1.1.1.1/dns-query")
                                .put("detour", tag)
                        )
                    )
                    .put("final", "dns-remote")
                    .put("strategy", "ipv4_only")
            )
        }
        if (!root.has("route")) {
            val route = JSONObject().put("auto_detect_interface", true)
            if (tag.isNotBlank()) route.put("final", tag)
            if (!hasDns && tag.isNotBlank()) {
                route.put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("port", JSONArray().put(53))
                            .put("action", "hijack-dns")
                    )
                )
            }
            root.put("route", route)
        }
        return root.toString()
    }

    private fun buildConfigJson(
        input: String,
        overrides: XrayOverrides,
        listenPort: Int = LOCAL_SOCKS_PORT
    ): String {
        if (input.startsWith("{")) {
            val root = JSONObject(input)
            return convertOrPatchJson(root, overrides, listenPort).toString()
        }

        val outbound = when {
            input.startsWith("vless://", ignoreCase = true) -> parseVless(input, overrides)
            input.startsWith("vmess://", ignoreCase = true) -> parseVmess(input, overrides)
            input.startsWith("trojan://", ignoreCase = true) -> parseTrojan(input, overrides)
            input.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(input)
            input.startsWith("socks://", ignoreCase = true) -> parseSocksUri(input)
            else -> error("Unsupported scheme (use vless://, vmess://, trojan://, ss://, socks://, or JSON)")
        }

        val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "socks-in")
            .put("listen", listenAddr)
            .put("listen_port", listenPort)

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }


    private fun resolveHostIfPossible(host: String): String {
        val h = host.trim()
        if (h.isEmpty()) return h
        return runCatching {
            java.net.InetAddress.getByName(h).hostAddress ?: h
        }.getOrDefault(h)
    }

    private fun convertOrPatchJson(
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
                val proto = xOb.optString("protocol", "")
                if (proto == "vless" || proto == "vmess" || proto == "trojan") {
                    sbOutbounds.put(convertXrayOutboundToSingBox(xOb, overrides))
                }
            }
            if (sbOutbounds.length() == 0) error("No supported outbound found in JSON")
            val listenAddr = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
            val inbound = JSONObject()
                .put("type", "mixed")
                .put("tag", "socks-in")
                .put("listen", listenAddr)
                .put("listen_port", listenPort)
            return JSONObject()
                .put("log", JSONObject().put("level", "warn"))
                .put("inbounds", JSONArray().put(inbound))
                .put("outbounds", sbOutbounds)
        }

        // Native sing-box JSON: update inbound port if socks exists and apply overrides
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null && inbounds.length() > 0) {
            inbounds.optJSONObject(0)?.put("listen_port", listenPort)
        }
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            applyOverridesToSingBoxOutbound(ob, overrides)
        }
        return root
    }

    private fun convertXrayOutboundToSingBox(xOb: JSONObject, overrides: XrayOverrides): JSONObject {
        val proto = xOb.optString("protocol", "vless")
        val settings = xOb.optJSONObject("settings") ?: JSONObject()
        val stream = xOb.optJSONObject("streamSettings") ?: JSONObject()
        val ob = JSONObject().put("type", proto).put("tag", "proxy")

        if (proto == "vless" || proto == "vmess") {
            val v0 = settings.optJSONArray("vnext")?.optJSONObject(0) ?: JSONObject()
            val u0 = v0.optJSONArray("users")?.optJSONObject(0) ?: JSONObject()
            ob.put("server", resolveHostIfPossible(v0.optString("address", "")))
            ob.put("server_port", v0.optInt("port", 443))
            ob.put("uuid", u0.optString("id", ""))
            val flow = u0.optString("flow", "")
            if (flow.isNotBlank()) ob.put("flow", flow)
            if (proto == "vmess") {
                ob.put("security", u0.optString("security", "auto"))
                ob.put("alter_id", u0.optInt("alterId", 0))
            }
        } else if (proto == "trojan") {
            val s0 = settings.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
            ob.put("server", resolveHostIfPossible(s0.optString("address", "")))
            ob.put("server_port", s0.optInt("port", 443))
            ob.put("password", s0.optString("password", ""))
        }

        val security = stream.optString("security", "none").lowercase()
        if (security == "tls") {
            val xTls = stream.optJSONObject("tlsSettings") ?: JSONObject()
            val sni = overrides.sniOverride.trim().ifEmpty { xTls.optString("serverName", ob.optString("server")) }
            val insecure = overrides.allowInsecure || xTls.optBoolean("allowInsecure", false)
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", sni)
                .put("insecure", insecure)
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", xTls.optString("fingerprint", "chrome").ifBlank { "chrome" }))
            ob.put("tls", tls)
        }

        val net = stream.optString("network", "tcp").lowercase()
        if (net == "ws") {
            val xWs = stream.optJSONObject("wsSettings") ?: JSONObject()
            val xHost = xWs.optJSONObject("headers")?.optString("Host", "") ?: ""
            val host = overrides.hostOverride.trim().ifEmpty { xHost }
            val ws = JSONObject()
                .put("type", "ws")
                .put("path", xWs.optString("path", "/").ifBlank { "/" })
            if (host.isNotBlank()) {
                ws.put("headers", JSONObject().put("Host", host))
            }
            ob.put("transport", ws)
        } else if (net == "grpc") {
            val xGrpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
            ob.put(
                "transport",
                JSONObject().put("type", "grpc").put("service_name", xGrpc.optString("serviceName", ""))
            )
        } else if (net == "httpupgrade") {
            val xHu = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
            val host = overrides.hostOverride.trim().ifEmpty { xHu.optString("host", "") }
            val hu = JSONObject().put("type", "httpupgrade").put("path", xHu.optString("path", "/"))
            if (host.isNotBlank()) hu.put("host", host)
            ob.put("transport", hu)
        }

        return ob
    }

    private fun applyOverridesToSingBoxOutbound(ob: JSONObject, overrides: XrayOverrides) {
        val hostOv = overrides.hostOverride.trim()
        if (hostOv.isNotEmpty()) {
            val tr = ob.optJSONObject("transport")
            if (tr != null) {
                val type = tr.optString("type", "")
                if (type == "ws") {
                    val hdrs = tr.optJSONObject("headers") ?: JSONObject().also { tr.put("headers", it) }
                    hdrs.put("Host", hostOv)
                } else if (type == "httpupgrade") {
                    tr.put("host", hostOv)
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

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun applyStreamToSingBox(
        ob: JSONObject,
        q: Map<String, String>,
        defaultHost: String,
        overrides: XrayOverrides
    ) {
        val network = (q["type"] ?: "tcp").lowercase()
        val security = (q["security"] ?: "none").lowercase()
        val effectiveHost = overrides.hostOverride.trim().ifEmpty { q["host"] ?: "" }
        val effectiveSni = overrides.sniOverride.trim()
            .ifEmpty { q["sni"] ?: effectiveHost.ifEmpty { defaultHost } }
        val linkInsecure = (q["allowInsecure"] ?: q["insecure"] ?: "").lowercase()
        val effectiveInsecure = overrides.allowInsecure || linkInsecure == "1" || linkInsecure == "true"

        when (network) {
            "ws" -> {
                val ws = JSONObject()
                    .put("type", "ws")
                    .put("path", q["path"] ?: "/")
                if (effectiveHost.isNotBlank()) {
                    ws.put("headers", JSONObject().put("Host", effectiveHost))
                }
                ob.put("transport", ws)
            }
            "grpc" -> {
                ob.put(
                    "transport",
                    JSONObject().put("type", "grpc").put("service_name", q["serviceName"] ?: q["path"] ?: "")
                )
            }
            "httpupgrade" -> {
                val hu = JSONObject().put("type", "httpupgrade").put("path", q["path"] ?: "/")
                if (effectiveHost.isNotBlank()) hu.put("host", effectiveHost)
                ob.put("transport", hu)
            }
        }

        when (security) {
            "tls" -> {
                val fp = (q["fp"] ?: "").ifBlank { "chrome" }
                val tls = JSONObject()
                    .put("enabled", true)
                    .put("server_name", effectiveSni)
                    .put("insecure", effectiveInsecure)
                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                val alpn = q["alpn"]
                if (!alpn.isNullOrBlank()) {
                    tls.put("alpn", JSONArray(alpn.split(",")))
                }
                ob.put("tls", tls)
            }
            "reality" -> {
                val fp = (q["fp"] ?: "").ifBlank { "chrome" }
                val tls = JSONObject()
                    .put("enabled", true)
                    .put("server_name", effectiveSni)
                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                    .put(
                        "reality",
                        JSONObject()
                            .put("enabled", true)
                            .put("public_key", q["pbk"] ?: "")
                            .put("short_id", q["sid"] ?: "")
                    )
                ob.put("tls", tls)
            }
        }
    }

    private fun parseVless(raw: String, overrides: XrayOverrides): JSONObject {
        val uri = URI(raw)
        val uuid = uri.userInfo ?: error("Missing UUID")
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQuery(uri.rawQuery)

        val ob = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", resolveHostIfPossible(host))
            .put("server_port", port)
            .put("uuid", uuid)
        val flow = q["flow"]
        if (!flow.isNullOrBlank()) ob.put("flow", flow)
        applyStreamToSingBox(ob, q, host, overrides)
        return ob
    }

    private fun parseVmess(raw: String, overrides: XrayOverrides): JSONObject {
        val b64 = raw.removePrefix("vmess://").trim()
        val jsonText = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
        val obj = JSONObject(jsonText)
        val host = obj.getString("add")
        val port = obj.optString("port", "443").toIntOrNull() ?: obj.optInt("port", 443)
        val uuid = obj.getString("id")
        val aid = obj.optString("aid", "0").toIntOrNull() ?: 0

        val q = mutableMapOf<String, String>()
        q["type"] = obj.optString("net", "tcp")
        q["security"] = if (obj.optString("tls", "").equals("tls", ignoreCase = true)) "tls" else "none"
        if (obj.has("host")) q["host"] = obj.optString("host")
        if (obj.has("path")) q["path"] = obj.optString("path")
        if (obj.has("sni")) q["sni"] = obj.optString("sni")
        if (obj.has("fp")) q["fp"] = obj.optString("fp")
        if (obj.optBoolean("allowInsecure", false)) q["allowInsecure"] = "1"

        val ob = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", resolveHostIfPossible(host))
            .put("server_port", port)
            .put("uuid", uuid)
            .put("alter_id", aid)
            .put("security", obj.optString("scy", "auto"))
        applyStreamToSingBox(ob, q, host, overrides)
        return ob
    }

    private fun parseTrojan(raw: String, overrides: XrayOverrides): JSONObject {
        val uri = URI(raw)
        val pass = uri.userInfo ?: error("Missing password")
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQuery(uri.rawQuery).toMutableMap()
        if (!q.containsKey("security")) q["security"] = "tls"

        val ob = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", resolveHostIfPossible(host))
            .put("server_port", port)
            .put("password", pass)
        applyStreamToSingBox(ob, q, host, overrides)
        return ob
    }

    private fun parseShadowsocks(raw: String): JSONObject {
        val withoutScheme = raw.removePrefix("ss://").substringBefore("#")
        val mainPart = withoutScheme.substringBefore("?")
        val (method, pass, host, port) = if (mainPart.contains("@")) {
            val left = mainPart.substringBefore("@")
            val right = mainPart.substringAfter("@")
            val decoded = if (left.contains(":")) left else String(Base64.decode(left, Base64.DEFAULT), StandardCharsets.UTF_8)
            val m = decoded.substringBefore(":")
            val p = decoded.substringAfter(":")
            val h = right.substringBeforeLast(":")
            val pt = right.substringAfterLast(":").toInt()
            listOf(m, p, h, pt.toString())
        } else {
            val decoded = String(Base64.decode(mainPart, Base64.DEFAULT), StandardCharsets.UTF_8)
            val m = decoded.substringBefore(":")
            val rest = decoded.substringAfter(":")
            val p = rest.substringBeforeLast("@")
            val right = rest.substringAfterLast("@")
            val h = right.substringBeforeLast(":")
            val pt = right.substringAfterLast(":").toInt()
            listOf(m, p, h, pt.toString())
        }

        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", resolveHostIfPossible(host))
            .put("server_port", port.toInt())
            .put("method", method)
            .put("password", pass)
    }

    private fun parseSocksUri(raw: String): JSONObject {
        val uri = URI(raw)
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else 1080
        return JSONObject()
            .put("type", "socks")
            .put("tag", "proxy")
            .put("server", resolveHostIfPossible(host))
            .put("server_port", port)
    }
}
