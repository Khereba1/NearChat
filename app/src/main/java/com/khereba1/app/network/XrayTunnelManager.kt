package com.khereba1.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.util.Base64
import androidx.annotation.RequiresApi
import com.khereba1.app.AppLog
import com.khereba1.app.PREFS
import go.Seq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import libcore.BoxInstance
import libcore.BoxPlatformInterface
import libcore.ExchangeContext
import libcore.Libcore
import libcore.LocalDNSTransport
import libcore.NB4AInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.UnknownHostException
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
    @Volatile private var activeBox: BoxInstance? = null
    @Volatile private var envInitialized = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var activeRawInput: String? = null
    @Volatile private var activeOverrides: XrayOverrides = XrayOverrides()
    @Volatile private var activeVpnMode: Boolean = false
    @Volatile private var lastIface: String? = null
    @Volatile private var generation: Int = 0
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val _state = MutableStateFlow(XrayState())
    val state: StateFlow<XrayState> = _state

    const val LOCAL_SOCKS_PORT = 10808
    private const val PREF_PROFILES = "xray_profiles_json"
    private const val CONNECTION_TEST_URL = "http://cp.cloudflare.com/"

    /**
     * Exact port of NekoBox's `moe.matsuri.nb4a.net.LocalResolverImpl`.
     * Uses Android's native `libandroid.so` (`android_res_nsend` via `networkHandle()`) and `DnsResolver`.
     */
    private val localResolver = object : LocalDNSTransport {
        private val RCODE_NXDOMAIN = 3

        override fun raw(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }

        override fun networkHandle(): Long {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ctx = appCtx ?: return 0L
                return PhysicalNetwork.current(ctx)?.network?.networkHandle ?: 0L
            }
            return 0L
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            val signal = CancellationSignal()
            ctx.onCancel { signal.cancel() }
            val physNet = appCtx?.let { PhysicalNetwork.current(it)?.network }
            val callback = object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    ctx.rawSuccess(answer)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        ctx.errnoCode(cause.errno)
                    } else {
                        ctx.errnoCode(114514)
                    }
                }
            }
            DnsResolver.getInstance().rawQuery(
                physNet,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
        }

        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            val physNet = appCtx?.let { PhysicalNetwork.current(it)?.network }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val signal = CancellationSignal()
                ctx.onCancel { signal.cancel() }
                val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                    override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                        try {
                            if (rcode == 0) {
                                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                            } else {
                                ctx.errorCode(rcode)
                            }
                        } catch (_: Exception) {
                            ctx.errnoCode(114514)
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            ctx.errnoCode(cause.errno)
                        } else {
                            ctx.errnoCode(114514)
                        }
                    }
                }
                val type = when {
                    network.endsWith("4") -> DnsResolver.TYPE_A
                    network.endsWith("6") -> DnsResolver.TYPE_AAAA
                    else -> null
                }
                if (type != null) {
                    DnsResolver.getInstance().query(
                        physNet,
                        domain,
                        type,
                        DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(),
                        signal,
                        callback
                    )
                } else {
                    DnsResolver.getInstance().query(
                        physNet,
                        domain,
                        DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(),
                        signal,
                        callback
                    )
                }
            } else {
                Thread {
                    try {
                        val answer = try {
                            physNet?.getAllByName(domain)
                        } catch (_: UnknownHostException) {
                            null
                        } ?: InetAddress.getAllByName(domain)
                        if (answer != null) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errnoCode(114514)
                        }
                    } catch (_: UnknownHostException) {
                        ctx.errorCode(RCODE_NXDOMAIN)
                    } catch (_: Exception) {
                        ctx.errnoCode(114514)
                    }
                }.start()
            }
        }
    }

    /**
     * Exact port of NekoBox's `moe.matsuri.nb4a.NativeInterface`.
     */
    private val nativeInterface = object : BoxPlatformInterface, NB4AInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            NearChatVpnService.protectFd(fd)
        }

        override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
            return NearChatVpnService.openTunFromCore().toLong()
        }

        override fun useProcFS(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun findConnectionOwner(
            ipProto: Int,
            srcIp: String,
            srcPort: Int,
            destIp: String,
            destPort: Int
        ): Int {
            val cm = appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return -1
            return try {
                cm.getConnectionOwnerUid(
                    ipProto,
                    InetSocketAddress(srcIp, srcPort),
                    InetSocketAddress(destIp, destPort)
                )
            } catch (_: Throwable) {
                -1
            }
        }

        override fun packageNameByUid(uid: Int): String {
            if (uid <= 1000) return "android"
            val pm = appCtx?.packageManager ?: return "android"
            return pm.getPackagesForUid(uid)?.firstOrNull() ?: "android"
        }

        override fun uidByPackageName(packageName: String): Int {
            val pm = appCtx?.packageManager ?: return 0
            return try {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0).uid
            } catch (_: Throwable) {
                0
            }
        }

        override fun wifiState(): String = ","

        override fun useOfficialAssets(): Boolean = true

        override fun selector_OnProxySelected(selectorTag: String, tag: String) {
            Libcore.resetAllConnections(true)
        }
    }

    @Synchronized
    private fun ensureSetup(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (envInitialized) return
        val externalAssets = (app.getExternalFilesDir(null) ?: app.filesDir).apply { mkdirs() }
        Seq.setContext(app)
        Libcore.initCore(
            "${app.packageName}:bg",
            app.cacheDir.absolutePath + "/",
            app.filesDir.absolutePath + "/",
            externalAssets.absolutePath + "/",
            50,
            true,
            nativeInterface,
            nativeInterface,
            localResolver
        )
        envInitialized = true
    }

    private fun readRecentNekoLog(): String {
        val ctx = appCtx ?: return ""
        return runCatching {
            val logFile = File(ctx.cacheDir, "neko.log")
            if (!logFile.exists()) return@runCatching ""
            logFile.readLines()
                .lastOrNull { it.contains("ERROR", true) || it.contains("WARN", true) || it.contains("fail", true) }
                ?.take(140)
                ?: ""
        }.getOrDefault("")
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

    /**
     * Exact port of NekoBox's `TestInstance.kt` & `UrlTest.kt`:
     * Uses `Libcore.newSingBoxInstance` + `Libcore.urlTest(box, CONNECTION_TEST_URL, 5000)`.
     */
    suspend fun testPing(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides = XrayOverrides()
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) error("Paste a config link or JSON first")
            ensureSetup(context)

            val currentBox = activeBox
            if (currentBox != null && _state.value.isRunning) {
                val res = runCatching {
                    Libcore.urlTest(currentBox, CONNECTION_TEST_URL, 5000).toLong()
                }
                if (res.isSuccess) return@runCatching res.getOrThrow()
            }

            val cfg = buildNekoBoxConfig(trimmed, overrides, forTest = true, isVpn = false)
            val testBox = Libcore.newSingBoxInstance(cfg, localResolver)
            try {
                testBox.start()
                val latency = Libcore.urlTest(testBox, CONNECTION_TEST_URL, 5000)
                latency.toLong()
            } finally {
                runCatching { testBox.close() }
            }
        }
    }

    /**
     * Exact port of NekoBox's `BoxInstance.kt` & `ProxyInstance.kt`:
     * Starts `Libcore.newSingBoxInstance`, calls `box.setAsMain()` and `box.start()`.
     * When `vpnMode = true`, `tun-in` inbound causes `sing-box` to call `openTun()` on `NearChatVpnService`.
     */
    fun start(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides = XrayOverrides(),
        vpnMode: Boolean = false,
        onReady: (() -> Unit)? = null
    ) {
        stop()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _state.value = XrayState(false, "Paste a config link or JSON first")
            return
        }

        _state.value = XrayState(false, "Starting NekoBox sing-box core…")
        val myGen = generation
        Thread {
            try {
                ensureSetup(context)
                if (vpnMode) {
                    NearChatVpnService.startNekoVpn(context)
                    NearChatVpnService.waitForInstance(3500L)
                }
                if (myGen != generation) return@Thread

                val cfg = buildNekoBoxConfig(trimmed, overrides, forTest = false, isVpn = vpnMode)
                val box = Libcore.newSingBoxInstance(cfg, localResolver)
                box.setAsMain()
                box.start()

                if (myGen != generation) {
                    runCatching { box.close() }
                    return@Thread
                }

                activeBox = box
                activeRawInput = trimmed
                activeOverrides = overrides
                activeVpnMode = vpnMode
                lastIface = appCtx?.let { PhysicalNetwork.current(it)?.interfaceName }
                startNetworkWatch()

                val bindHost = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
                val modeLabel = if (vpnMode) "VPN + Proxy" else "Proxy"
                _state.value = XrayState(true, "NekoBox $modeLabel active ($bindHost:$LOCAL_SOCKS_PORT) • Testing…")
                onReady?.invoke()

                val latency = runCatching {
                    Libcore.urlTest(box, CONNECTION_TEST_URL, 5000)
                }.getOrNull()

                if (myGen == generation && _state.value.isRunning) {
                    if (latency != null && latency >= 0) {
                        _state.value = XrayState(
                            true,
                            "NekoBox $modeLabel active ($bindHost:$LOCAL_SOCKS_PORT) • Connected (${latency} ms)"
                        )
                    } else {
                        val logDetail = readRecentNekoLog()
                        val suffix = if (logDetail.isNotEmpty()) " ($logDetail)" else ""
                        _state.value = XrayState(
                            true,
                            "NekoBox $modeLabel active ($bindHost:$LOCAL_SOCKS_PORT)$suffix"
                        )
                    }
                }
            } catch (e: Throwable) {
                AppLog.log("SINGBOX", "Start failed", e)
                _state.value = XrayState(false, "Error: ${e.message}")
                if (vpnMode) {
                    appCtx?.let { NearChatVpnService.stop(it) }
                }
            }
        }.start()
    }

    fun stop() {
        generation++
        stopNetworkWatch()
        val old = activeBox
        activeBox = null
        activeRawInput = null
        if (old != null) {
            Thread {
                runCatching { old.close() }
            }.start()
        }
        _state.value = XrayState(false, "")
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
        lastIface = phys.interfaceName
        AppLog.log("SINGBOX", "Network changed -> resetting connections")
        Thread {
            runCatching { Libcore.resetAllConnections(true) }
        }.start()
    }

    // =========================================================================
    // Exact NekoBox ConfigBuilder.kt + V2RayFmt.kt / TrojanFmt.kt / ShadowsocksFmt.kt
    // =========================================================================

    private fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4.matches(h) || h.contains(":")
    }

    /**
     * Exact port of NekoBox's `io.nekohasekai.sagernet.fmt.ConfigBuilder.kt`:
     * Builds the full NekoBox JSON config for `Libcore.newSingBoxInstance`.
     */
    private fun buildNekoBoxConfig(
        rawInput: String,
        overrides: XrayOverrides,
        forTest: Boolean,
        isVpn: Boolean
    ): String {
        val normalizedInput = extractProxyInput(rawInput)
        val proxyOutbounds = extractOrBuildOutbounds(normalizedInput, overrides, forTest)
        if (proxyOutbounds.length() == 0) error("No valid proxy outbound found")

        val mainOb = proxyOutbounds.getJSONObject(0)
        val mainTag = mainOb.optString("tag", "proxy").ifBlank { "proxy" }
        mainOb.put("tag", mainTag)

        // Collect all server domains from outbounds so DNS rules bypass them via dns-direct (NekoBox bypassDNSBeans)
        val domainListDnsDirectForce = LinkedHashSet<String>()
        for (i in 0 until proxyOutbounds.length()) {
            val ob = proxyOutbounds.optJSONObject(i) ?: continue
            val srv = ob.optString("server", "").trim()
            if (srv.isNotBlank() && !isIpLiteral(srv)) {
                domainListDnsDirectForce.add(srv.lowercase())
            }
        }
        domainListDnsDirectForce.add("dns.google")

        // Inbounds (NekoBox ConfigBuilder.kt lines 133-167)
        val inbounds = JSONArray()
        if (!forTest) {
            if (isVpn) {
                inbounds.put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("stack", "gvisor")
                        .put("endpoint_independent_nat", true)
                        .put("mtu", 9000)
                        .put("sniff", true)
                        .put("sniff_override_destination", false)
                        .put("inet4_address", JSONArray().put("${NearChatVpnService.PRIVATE_VLAN4_CLIENT}/28"))
                )
            }
            val bind = if (overrides.allowLan) "0.0.0.0" else "127.0.0.1"
            inbounds.put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "mixed-in")
                    .put("listen", bind)
                    .put("listen_port", LOCAL_SOCKS_PORT)
                    .put("sniff", true)
                    .put("sniff_override_destination", false)
            )
        }

        // Outbounds (NekoBox ConfigBuilder.kt lines 485-490)
        val outbounds = JSONArray()
        for (i in 0 until proxyOutbounds.length()) {
            outbounds.put(proxyOutbounds.getJSONObject(i))
        }
        ensureOutboundTag(outbounds, "direct", "direct")
        ensureOutboundTag(outbounds, "bypass", "direct")

        // DNS (NekoBox ConfigBuilder.kt lines 505-573)
        val dnsServers = JSONArray()
            .put(JSONObject().put("tag", "dns-block").put("address", "rcode://success"))
            .put(JSONObject().put("tag", "dns-local").put("address", "local").put("detour", "direct"))
            .put(
                JSONObject()
                    .put("tag", "dns-direct")
                    .put("address", "https://223.5.5.5/dns-query")
                    .put("detour", "direct")
                    .put("address_resolver", "dns-local")
                    .put("strategy", "ipv4_only")
            )
        if (!forTest) {
            dnsServers.put(
                JSONObject()
                    .put("tag", "dns-remote")
                    .put("address", "https://dns.google/dns-query")
                    .put("detour", mainTag)
                    .put("address_resolver", "dns-direct")
                    .put("strategy", "ipv4_only")
            )
        }

        val dnsRules = JSONArray()
        val dnsObj = JSONObject()
            .put("servers", dnsServers)
            .put("rules", dnsRules)
            .put("final", if (forTest) "dns-direct" else "dns-remote")
            .put("independent_cache", true)

        // Route (NekoBox ConfigBuilder.kt lines 172-176 & 538-573)
        val routeRules = JSONArray()
        if (!forTest) {
            routeRules.put(
                JSONObject()
                    .put("port", JSONArray().put(53))
                    .put("action", "hijack-dns")
            )
            routeRules.put(
                JSONObject()
                    .put("protocol", JSONArray().put("dns"))
                    .put("action", "hijack-dns")
            )
            routeRules.put(
                JSONObject()
                    .put("ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
                    .put("source_ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
                    .put("action", "reject")
            )

            if (isVpn) {
                dnsObj.put(
                    "fakeip",
                    JSONObject()
                        .put("enabled", true)
                        .put("inet4_range", "198.18.0.0/15")
                        .put("inet6_range", "fc00::/18")
                )
                dnsServers.put(
                    JSONObject()
                        .put("tag", "dns-fake")
                        .put("address", "fakeip")
                        .put("strategy", "ipv4_only")
                )
            }

            if (domainListDnsDirectForce.isNotEmpty()) {
                dnsRules.put(
                    JSONObject()
                        .put("domain", JSONArray(domainListDnsDirectForce.toList()))
                        .put("server", "dns-direct")
                )
            }
            dnsRules.put(
                JSONObject()
                    .put("outbound", JSONArray().put("any"))
                    .put("server", "dns-direct")
            )
            if (isVpn) {
                dnsRules.put(
                    JSONObject()
                        .put("inbound", JSONArray().put("tun-in"))
                        .put("server", "dns-fake")
                        .put("disable_cache", true)
                )
            }
        }

        val routeObj = JSONObject()
            .put("auto_detect_interface", true)
            .put("final", mainTag)
            .put("rules", routeRules)

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dnsObj)
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", routeObj)
            .toString()
    }

    private fun ensureOutboundTag(outbounds: JSONArray, tag: String, type: String) {
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == tag) return
        }
        outbounds.put(JSONObject().put("type", type).put("tag", tag))
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

    private fun extractOrBuildOutbounds(
        input: String,
        overrides: XrayOverrides,
        forTest: Boolean
    ): JSONArray {
        val domainStrategy = if (forTest) "" else "prefer_ipv4"
        if (input.startsWith("{")) {
            val root = JSONObject(input)
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
                        val converted = convertXrayOutboundToSingBox(item, overrides)
                        if (domainStrategy.isNotEmpty()) converted.put("domain_strategy", domainStrategy)
                        cleanOutbounds.put(converted)
                    }
                } else {
                    val type = item.optString("type", "").lowercase()
                    if (type in auxTypes || type.isBlank()) continue
                    val cloned = JSONObject(item.toString())
                    if (forTest) {
                        cloned.remove("domain_strategy")
                    } else if (!cloned.has("domain_strategy") && type != "selector" && type != "urltest") {
                        cloned.put("domain_strategy", domainStrategy)
                    }
                    applyOverridesToSingBoxOutbound(cloned, overrides)
                    cleanOutbounds.put(cloned)
                }
            }
            return cleanOutbounds
        }

        val ob = when {
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
        if (domainStrategy.isNotEmpty()) {
            ob.put("domain_strategy", domainStrategy)
        }
        return JSONArray().put(ob)
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
