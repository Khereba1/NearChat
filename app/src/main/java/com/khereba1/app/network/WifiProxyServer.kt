package com.khereba1.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import com.khereba1.app.AppLog
import com.khereba1.app.PREFS
import com.khereba1.app.data.NearChatDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ConnectedClientInfo(
    val ip: String,
    val bytesUsed: Long = 0L,
    val bytesPerSec: Long = 0L,
    val blocked: Boolean = false,
    val quotaMb: Int = 0,
    val speedLimitKbps: Int = 0
)

data class RouterBridgeState(
    val isRunning: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8899,
    val socksPort: Int = 1080,
    val p2pSsid: String = "",
    val p2pPass: String = "",
    val p2pError: String = "",
    val proxyUser: String = "",
    val proxyPass: String = ""
)

object WifiProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var socksSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pManager: WifiP2pManager? = null
    private var appContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var upstreamHost: String = ""
    private var upstreamPort: Int = 0
    @Volatile private var requireProxyAuth: Boolean = false
    @Volatile private var proxyAuthPass: String = ""
    private const val PROXY_USER = "nearchat"

    fun ensureRouterPass(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString("router_pass", "") ?: ""
        if (saved.length >= 8) return saved
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val generated = buildString { repeat(12) { append(chars[rnd.nextInt(chars.length)]) } }
        prefs.edit().putString("router_pass", generated).apply()
        return generated
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(RouterBridgeState())
    val state: StateFlow<RouterBridgeState> = _state

    private class ClientStats(val ip: String) {
        val bytesUsed = AtomicLong(0L)
        @Volatile var lastWindowBytes: Long = 0L
        @Volatile var lastWindowTimeMs: Long = System.currentTimeMillis()
        @Volatile var bytesPerSec: Long = 0L
        @Volatile var blocked: Boolean = false
        @Volatile var quotaMb: Int = 0
        @Volatile var speedLimitKbps: Int = 0
        @Volatile var lastSeenMs: Long = System.currentTimeMillis()
    }

    private val clientMap = ConcurrentHashMap<String, ClientStats>()
    private val _clients = MutableStateFlow<List<ConnectedClientInfo>>(emptyList())
    val clients: StateFlow<List<ConnectedClientInfo>> = _clients
    @Volatile private var lastEmitMs: Long = 0L

    private fun touchClient(ip: String): ClientStats {
        val st = clientMap.computeIfAbsent(ip) { ClientStats(it) }
        st.lastSeenMs = System.currentTimeMillis()
        maybeEmitClients(force = false)
        return st
    }

    private fun isClientDenied(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "::1") return false
        val st = clientMap[ip] ?: return false
        if (st.blocked) return true
        if (st.quotaMb > 0 && st.bytesUsed.get() >= st.quotaMb.toLong() * 1024L * 1024L) return true
        return false
    }

    private fun recordClientBytes(ip: String, delta: Long) {
        if (delta <= 0L || ip == "127.0.0.1" || ip == "::1") return
        val st = touchClient(ip)
        val total = st.bytesUsed.addAndGet(delta)
        val now = System.currentTimeMillis()
        val elapsed = now - st.lastWindowTimeMs
        if (elapsed >= 1000L) {
            st.bytesPerSec = ((total - st.lastWindowBytes) * 1000L) / elapsed.coerceAtLeast(1L)
            st.lastWindowBytes = total
            st.lastWindowTimeMs = now
        }
        maybeEmitClients(force = false)
    }

    private fun maybeEmitClients(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitMs < 1000L) return
        lastEmitMs = now
        val list = clientMap.values
            .filter { it.ip != "127.0.0.1" && it.ip != "::1" }
            .sortedByDescending { it.lastSeenMs }
            .map { st ->
                val bps = if (now - st.lastSeenMs > 4000L) 0L else st.bytesPerSec
                ConnectedClientInfo(
                    ip = st.ip,
                    bytesUsed = st.bytesUsed.get(),
                    bytesPerSec = bps,
                    blocked = st.blocked,
                    quotaMb = st.quotaMb,
                    speedLimitKbps = st.speedLimitKbps
                )
            }
        _clients.value = list
    }

    fun setClientBlocked(ip: String, blocked: Boolean) {
        val st = clientMap.computeIfAbsent(ip) { ClientStats(it) }
        st.blocked = blocked
        maybeEmitClients(force = true)
    }

    fun setClientLimits(ip: String, quotaMb: Int, speedLimitKbps: Int) {
        val st = clientMap.computeIfAbsent(ip) { ClientStats(it) }
        st.quotaMb = quotaMb.coerceAtLeast(0)
        st.speedLimitKbps = speedLimitKbps.coerceAtLeast(0)
        maybeEmitClients(force = true)
    }

    fun resetClientUsage(ip: String) {
        val st = clientMap[ip] ?: return
        st.bytesUsed.set(0L)
        st.lastWindowBytes = 0L
        st.bytesPerSec = 0L
        maybeEmitClients(force = true)
    }

    fun supportsDirectWifiHotspot(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.isStaApConcurrencySupported == true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun start(context: Context, preferredPort: Int = 8899, preferredSocksPort: Int = 1080) {
        if (running.get()) return
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customPass = ensureRouterPass(ctx)
        requireProxyAuth = prefs.getBoolean("router_require_auth", false)
        proxyAuthPass = customPass
        val useLohs = prefs.getBoolean("router_use_lohs", false)
        val upstream = (prefs.getString("upstream_socks", "") ?: "").trim()
        if (upstream.contains(":")) {
            upstreamHost = upstream.substringBefore(":").trim()
            upstreamPort = upstream.substringAfter(":").trim().toIntOrNull() ?: 0
        } else {
            upstreamHost = ""
            upstreamPort = 0
        }

        try {
            val ss = try {
                ServerSocket(preferredPort, 100, InetAddress.getByName("0.0.0.0"))
            } catch (_: Throwable) {
                ServerSocket(0, 100, InetAddress.getByName("0.0.0.0"))
            }
            val socks = try {
                ServerSocket(preferredSocksPort, 100, InetAddress.getByName("0.0.0.0"))
            } catch (_: Throwable) {
                ServerSocket(0, 100, InetAddress.getByName("0.0.0.0"))
            }
            serverSocket = ss
            socksSocket = socks
            running.set(true)

            acquireLocks(ctx)
            try {
                LanShareService.start(ctx)
            } catch (_: Throwable) {
            }

            val exec = Executors.newCachedThreadPool()
            pool = exec
            val ip = NetworkUtils.getLocalIpAddress() ?: "192.168.49.1"
            _state.value = RouterBridgeState(
                isRunning = true,
                proxyHost = ip,
                proxyPort = ss.localPort,
                socksPort = socks.localPort,
                p2pError = "Starting Wi-Fi Direct…"
            )

            exec.execute {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        exec.execute { handleProxyClient(client) }
                    } catch (_: Throwable) {
                        if (!running.get()) break
                    }
                }
            }

            exec.execute {
                while (running.get()) {
                    try {
                        val client = socks.accept()
                        exec.execute { handleSocks5Client(client) }
                    } catch (_: Throwable) {
                        if (!running.get()) break
                    }
                }
            }

            if (useLohs && LocalHotspotManager.isSupported()) {
                LocalHotspotManager.start(ctx) { info, err ->
                    if (info != null && info.ssid.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            proxyHost = NetworkUtils.findHotspotIp(),
                            p2pSsid = info.ssid,
                            p2pPass = info.passphrase,
                            p2pError = ""
                        )
                    } else {
                        _state.value = _state.value.copy(p2pError = err)
                    }
                }
            } else try {
                val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                p2pManager = mgr
                if (mgr != null) {
                    val ch = mgr.initialize(ctx, Looper.getMainLooper(), null)
                    p2pChannel = ch
                    mgr.requestGroupInfo(ch) { existing ->
                        if (existing != null && !existing.networkName.isNullOrEmpty()) {
                            _state.value = _state.value.copy(
                                proxyHost = "192.168.49.1",
                                p2pSsid = existing.networkName ?: "",
                                p2pPass = existing.passphrase ?: "",
                                p2pError = ""
                            )
                        } else {
                            createP2pGroup(mgr, ch, customPass)
                        }
                    }
                } else {
                    _state.value = _state.value.copy(p2pError = "Wi-Fi Direct not supported on this device")
                }
            } catch (e: Throwable) {
                AppLog.log("PROXY", "Wi-Fi Direct setup error", e)
                _state.value = _state.value.copy(p2pError = "Wi-Fi Direct error: ${e.message}")
            }
        } catch (e: Throwable) {
            AppLog.log("PROXY", "Proxy start failed", e)
            stop(keepGroup = false)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NearChat:RouterWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "NearChat:RouterWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Throwable) {
            AppLog.log("PROXY", "Lock acquire error", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {}
        wifiLock = null
    }

    @SuppressLint("MissingPermission")
    private fun createP2pGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, customPass: String) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                pollGroupInfo(mgr, ch, attempt = 0)
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.BUSY -> "Wi-Fi Direct busy (turn Location ON and retry)"
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported on this device"
                    else -> "Wi-Fi Direct unavailable (code $reason) — turn Location ON or hardware blocks simultaneous Wi-Fi"
                }
                _state.value = _state.value.copy(p2pError = msg)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val safePass = customPass.trim().let { if (it.length >= 8) it.take(63) else "NearChat123" }
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-NC-NearChat")
                .setPassphrase(safePass)
                .build()
            mgr.createGroup(ch, config, listener)
        } else {
            mgr.createGroup(ch, listener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollGroupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, attempt: Int) {
        if (!running.get()) return
        mgr.requestGroupInfo(ch) { group ->
            if (group != null && !group.networkName.isNullOrEmpty()) {
                _state.value = _state.value.copy(
                    proxyHost = "192.168.49.1",
                    p2pSsid = group.networkName ?: "",
                    p2pPass = group.passphrase ?: "",
                    p2pError = ""
                )
            } else if (attempt < 5) {
                mainHandler.postDelayed({ pollGroupInfo(mgr, ch, attempt + 1) }, 700L)
            } else {
                _state.value = _state.value.copy(
                    proxyHost = "192.168.49.1",
                    p2pError = "Wi-Fi Direct group active, but SSID/Pass hidden by system."
                )
            }
        }
    }

    fun stop(keepGroup: Boolean = false) {
        running.set(false)
        LocalHotspotManager.stop()
        mainHandler.removeCallbacksAndMessages(null)
        releaseLocks()
        try { serverSocket?.close() } catch (_: Throwable) {}
        try { socksSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        socksSocket = null
        try { pool?.shutdownNow() } catch (_: Throwable) {}
        pool = null

        val ctx = appContext
        if (!keepGroup) {
            try {
                val mgr = p2pManager
                    ?: (ctx?.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager)
                val ch = p2pChannel ?: mgr?.initialize(ctx, Looper.getMainLooper(), null)
                if (mgr != null && ch != null) {
                    mgr.removeGroup(ch, null)
                }
            } catch (_: Throwable) {}
            p2pChannel = null
        }

        if (ctx != null) {
            try {
                if (NearChatDb.get(ctx).getSharedChatsCount() == 0) {
                    LanShareService.stop(ctx)
                }
            } catch (_: Throwable) {}
        }
        _state.value = RouterBridgeState(isRunning = false)
    }

    @SuppressLint("MissingPermission")
    fun resetP2pGroup(context: Context) {
        try {
            val mgr = p2pManager
                ?: (context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager)
            val ch = p2pChannel ?: mgr?.initialize(context.applicationContext, Looper.getMainLooper(), null)
            if (mgr != null && ch != null) {
                mgr.removeGroup(ch, null)
            }
        } catch (_: Throwable) {
        }
        p2pChannel = null
        _state.value = _state.value.copy(p2pSsid = "", p2pPass = "")
    }

    private fun tuneSocket(s: Socket) {
        try {
            s.soTimeout = 120_000
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (_: Throwable) {
        }
    }

    private fun isUpstreamUsable(): Boolean {
        if (upstreamHost.isEmpty() || upstreamPort !in 1..65535) return false
        if ((upstreamHost == "127.0.0.1" || upstreamHost == "localhost") &&
            upstreamPort == XrayTunnelManager.LOCAL_SOCKS_PORT &&
            !XrayTunnelManager.state.value.isRunning
        ) {
            return false
        }
        return true
    }

    private fun activeInternetNetwork(): Network? {
        val ctx = appContext ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val allNets = cm.allNetworks
            var best: Network? = null
            var bestScore = -1
            for (net in allNets) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                var score = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 20
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10
                if (score > bestScore) {
                    bestScore = score
                    best = net
                }
            }
            best
        } catch (_: Throwable) {
            null
        }
    }

    private fun openInternetSocket(host: String, port: Int): Socket {
        if (isUpstreamUsable()) {
            return openViaUpstreamSocks5(upstreamHost, upstreamPort, host, port)
        }
        val net = activeInternetNetwork()
        val sock = if (net != null) {
            try {
                net.socketFactory.createSocket(host, port)
            } catch (_: Throwable) {
                Socket(host, port)
            }
        } else {
            Socket(host, port)
        }
        tuneSocket(sock)
        return sock
    }

    private fun openViaUpstreamSocks5(upHost: String, upPort: Int, targetHost: String, targetPort: Int): Socket {
        val s = Socket(upHost, upPort)
        tuneSocket(s)
        val outS = s.getOutputStream()
        val inS = s.getInputStream()
        outS.write(byteArrayOf(0x05, 0x01, 0x00))
        outS.flush()
        val r0 = inS.read()
        val r1 = inS.read()
        if (r0 != 0x05 || r1 != 0x00) {
            s.close()
            throw java.io.IOException("Upstream SOCKS5 auth failed")
        }
        val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        outS.write(req)
        outS.flush()
        val hdr = ByteArray(4)
        readExact(inS, hdr)
        if (hdr[1] != 0x00.toByte()) {
            s.close()
            throw java.io.IOException("Upstream SOCKS5 connect failed: ${hdr[1]}")
        }
        when (hdr[3].toInt() and 0xFF) {
            0x01 -> readExact(inS, ByteArray(6))
            0x03 -> {
                val l = inS.read()
                if (l > 0) readExact(inS, ByteArray(l + 2))
            }
            0x04 -> readExact(inS, ByteArray(18))
        }
        return s
    }

    private fun openInternetDatagramSocket(): DatagramSocket {
        val ds = DatagramSocket(0)
        ds.soTimeout = 60_000
        try {
            activeInternetNetwork()?.bindSocket(ds)
        } catch (_: Throwable) {
        }
        return ds
    }

    private fun handleSocks5Client(client: Socket) {
        client.use { c ->
            try {
                tuneSocket(c)
                val inS = c.getInputStream()
                val outS = c.getOutputStream()

                val ver = inS.read()
                if (ver != 0x05) return
                val nMethods = inS.read()
                if (nMethods <= 0) return
                val methods = ByteArray(nMethods)
                readExact(inS, methods)
                if (requireProxyAuth) {
                    if (!methods.contains(0x02.toByte())) {
                        outS.write(byteArrayOf(0x05, 0xFF.toByte()))
                        outS.flush()
                        return
                    }
                    outS.write(byteArrayOf(0x05, 0x02))
                    outS.flush()
                    val authVer = inS.read()
                    val uLen = inS.read()
                    if (authVer != 0x01 || uLen <= 0) return
                    val uBytes = ByteArray(uLen)
                    readExact(inS, uBytes)
                    val pLen = inS.read()
                    if (pLen <= 0) return
                    val pBytes = ByteArray(pLen)
                    readExact(inS, pBytes)
                    val uStr = String(uBytes, StandardCharsets.UTF_8)
                    val pStr = String(pBytes, StandardCharsets.UTF_8)
                    if (uStr != PROXY_USER || pStr != proxyAuthPass) {
                        outS.write(byteArrayOf(0x01, 0x01))
                        outS.flush()
                        return
                    }
                    outS.write(byteArrayOf(0x01, 0x00))
                    outS.flush()
                } else {
                    outS.write(byteArrayOf(0x05, 0x00))
                    outS.flush()
                }

                val rVer = inS.read()
                val cmd = inS.read()
                inS.read()
                val atyp = inS.read()
                if (rVer != 0x05) return

                val host = when (atyp) {
                    0x01 -> {
                        val b = ByteArray(4)
                        readExact(inS, b)
                        InetAddress.getByAddress(b).hostAddress ?: return
                    }
                    0x03 -> {
                        val len = inS.read()
                        if (len <= 0) return
                        val b = ByteArray(len)
                        readExact(inS, b)
                        String(b, StandardCharsets.UTF_8)
                    }
                    0x04 -> {
                        val b = ByteArray(16)
                        readExact(inS, b)
                        InetAddress.getByAddress(b).hostAddress ?: return
                    }
                    else -> return
                }
                val p1 = inS.read()
                val p2 = inS.read()
                if (p1 < 0 || p2 < 0) return
                val port = (p1 shl 8) or p2

                if (cmd == 0x03) {
                    handleSocks5UdpAssociate(c, inS, outS)
                    return
                }
                if (cmd != 0x01) return

                val clientIp = c.inetAddress?.hostAddress ?: "unknown"
                touchClient(clientIp)
                if (isClientDenied(clientIp)) return
                openInternetSocket(host, port).use { remote ->
                    outS.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    outS.flush()
                    val t1 = Thread { pipe(inS, remote.getOutputStream(), clientIp) }
                    t1.start()
                    pipe(remote.getInputStream(), outS, clientIp)
                    t1.join(3000)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Resolves a raw DNS payload over TCP (RFC 1035 2-byte length prefix) via the upstream SOCKS5
     * tunnel (sing-box) so Wi-Fi Direct hotspot clients get working DNS even when UDP is blocked.
     */
    private fun queryDnsOverUpstreamTcp(dnsServerIp: String, queryPayload: ByteArray): ByteArray? {
        if (!isUpstreamUsable()) return null
        return try {
            openViaUpstreamSocks5(upstreamHost, upstreamPort, dnsServerIp, 53).use { s ->
                s.soTimeout = 5000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                val len = queryPayload.size
                val framed = ByteArray(2 + len)
                framed[0] = ((len shr 8) and 0xFF).toByte()
                framed[1] = (len and 0xFF).toByte()
                System.arraycopy(queryPayload, 0, framed, 2, len)
                out.write(framed)
                out.flush()
                val lenBuf = ByteArray(2)
                readExact(inp, lenBuf)
                val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (respLen !in 1..8192) return null
                val resp = ByteArray(respLen)
                readExact(inp, resp)
                resp
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleSocks5UdpAssociate(ctrl: Socket, inS: InputStream, outS: OutputStream) {
        val localUdp = DatagramSocket(0, InetAddress.getByName("0.0.0.0")).apply {
            soTimeout = 120_000
        }
        val outUdp = openInternetDatagramSocket()
        try {
            val bindPort = localUdp.localPort
            val localIpBytes = ctrl.localAddress.address.let {
                if (it.size == 4) it else byteArrayOf(192.toByte(), 168.toByte(), 49, 1)
            }
            outS.write(
                byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    localIpBytes[0], localIpBytes[1], localIpBytes[2], localIpBytes[3],
                    ((bindPort shr 8) and 0xFF).toByte(), (bindPort and 0xFF).toByte()
                )
            )
            outS.flush()

            var clientAddr: InetSocketAddress? = null
            val relayActive = AtomicBoolean(true)

            val replyThread = Thread {
                val rBuf = ByteArray(4096)
                while (relayActive.get()) {
                    try {
                        val rp = DatagramPacket(rBuf, rBuf.size)
                        outUdp.receive(rp)
                        val cTarget = clientAddr ?: continue
                        val srcIp = rp.address.address
                        if (srcIp.size != 4) continue
                        val wrapped = ByteArray(10 + rp.length)
                        wrapped[0] = 0; wrapped[1] = 0; wrapped[2] = 0; wrapped[3] = 0x01
                        System.arraycopy(srcIp, 0, wrapped, 4, 4)
                        wrapped[8] = ((rp.port shr 8) and 0xFF).toByte()
                        wrapped[9] = (rp.port and 0xFF).toByte()
                        System.arraycopy(rp.data, rp.offset, wrapped, 10, rp.length)
                        localUdp.send(DatagramPacket(wrapped, wrapped.size, cTarget))
                    } catch (_: Throwable) {
                        if (!relayActive.get()) break
                    }
                }
            }
            replyThread.start()

            val ctrlMonitor = Thread {
                try {
                    while (inS.read() != -1) { }
                } catch (_: Throwable) {
                } finally {
                    relayActive.set(false)
                    try { localUdp.close() } catch (_: Throwable) {}
                    try { outUdp.close() } catch (_: Throwable) {}
                }
            }
            ctrlMonitor.start()

            val buf = ByteArray(4096)
            while (relayActive.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                localUdp.receive(pkt)
                val currentClient = InetSocketAddress(pkt.address, pkt.port)
                clientAddr = currentClient
                val len = pkt.length
                if (len < 10) continue
                val atyp = buf[3].toInt() and 0xFF
                var offset = 4
                val dstAddr: InetAddress = when (atyp) {
                    0x01 -> {
                        if (len < 10) continue
                        val b = buf.copyOfRange(4, 8)
                        offset = 8
                        InetAddress.getByAddress(b)
                    }
                    0x03 -> {
                        val dLen = buf[4].toInt() and 0xFF
                        if (len < 5 + dLen + 2) continue
                        val domain = String(buf, 5, dLen, StandardCharsets.UTF_8)
                        offset = 5 + dLen
                        InetAddress.getByName(domain)
                    }
                    else -> continue
                }
                val dstPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                offset += 2
                val payloadLen = len - offset
                if (payloadLen <= 0) continue

                if (dstPort == 53 && isUpstreamUsable()) {
                    val dnsQuery = buf.copyOfRange(offset, offset + payloadLen)
                    val srcIp = dstAddr.address
                    pool?.execute {
                        val resp = queryDnsOverUpstreamTcp("1.1.1.1", dnsQuery)
                        if (resp != null && srcIp.size == 4) {
                            val wrapped = ByteArray(10 + resp.size)
                            wrapped[0] = 0; wrapped[1] = 0; wrapped[2] = 0; wrapped[3] = 0x01
                            System.arraycopy(srcIp, 0, wrapped, 4, 4)
                            wrapped[8] = 0
                            wrapped[9] = 53
                            System.arraycopy(resp, 0, wrapped, 10, resp.size)
                            runCatching { localUdp.send(DatagramPacket(wrapped, wrapped.size, currentClient)) }
                        }
                    }
                    continue
                }
                outUdp.send(DatagramPacket(buf, offset, payloadLen, dstAddr, dstPort))
            }
        } catch (_: Throwable) {
        } finally {
            try { localUdp.close() } catch (_: Throwable) {}
            try { outUdp.close() } catch (_: Throwable) {}
        }
    }

    private fun readExact(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r <= 0) throw java.io.EOFException()
            off += r
        }
    }

    private fun handleProxyClient(client: Socket) {
        client.use { c ->
            try {
                tuneSocket(c)
                val inStream = c.getInputStream()
                val outStream = c.getOutputStream()
                val firstLine = readLine(inStream) ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0].uppercase()
                val target = parts[1]

                val headerLines = mutableListOf<String>()
                var contentLength = 0
                while (true) {
                    val line = readLine(inStream) ?: break
                    if (line.isEmpty()) break
                    headerLines.add(line)
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val clientIp = c.inetAddress?.hostAddress ?: "unknown"
                touchClient(clientIp)
                if (isClientDenied(clientIp)) return
                if (requireProxyAuth) {
                    val authHdr = headerLines.firstOrNull { it.lowercase().startsWith("proxy-authorization:") }
                    val expected = "Basic " + Base64.encodeToString("$PROXY_USER:$proxyAuthPass".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    val provided = authHdr?.substringAfter(":")?.trim() ?: ""
                    if (provided != expected) {
                        outStream.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"NearChat\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                        outStream.flush()
                        return
                    }
                }
                if (method == "CONNECT") {
                    val host = target.substringBefore(":")
                    val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
                    openInternetSocket(host, port).use { remote ->
                        outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                        outStream.flush()
                        val t1 = Thread { pipe(inStream, remote.getOutputStream(), clientIp) }
                        t1.start()
                        pipe(remote.getInputStream(), outStream, clientIp)
                        t1.join(3000)
                    }
                } else {
                    val uri = java.net.URI(target)
                    val host = uri.host ?: return
                    val port = if (uri.port > 0) uri.port else 80
                    val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                        (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                    openInternetSocket(host, port).use { remote ->
                        val rOut = remote.getOutputStream()
                        rOut.write("$method $path HTTP/1.1\r\n".toByteArray(StandardCharsets.UTF_8))
                        for (h in headerLines) {
                            if (!h.lowercase().startsWith("proxy-connection")) {
                                rOut.write("$h\r\n".toByteArray(StandardCharsets.UTF_8))
                            }
                        }
                        rOut.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                        if (contentLength > 0) {
                            val buf = ByteArray(16384)
                            var rem = contentLength
                            while (rem > 0) {
                                val r = inStream.read(buf, 0, minOf(buf.size, rem))
                                if (r <= 0) break
                                rOut.write(buf, 0, r)
                                rem -= r
                            }
                        }
                        rOut.flush()
                        pipe(remote.getInputStream(), outStream, clientIp)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, clientIp: String = "") {
        try {
            val buf = ByteArray(32768)
            while (true) {
                if (clientIp.isNotEmpty() && isClientDenied(clientIp)) break
                val startNs = System.nanoTime()
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                if (clientIp.isNotEmpty()) {
                    recordClientBytes(clientIp, n.toLong())
                    val limitKbps = clientMap[clientIp]?.speedLimitKbps ?: 0
                    if (limitKbps > 0) {
                        val expectedMs = (n.toLong() * 1000L) / (limitKbps.toLong() * 1024L).coerceAtLeast(1L)
                        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                        val sleepMs = expectedMs - elapsedMs
                        if (sleepMs > 0L) {
                            Thread.sleep(sleepMs.coerceAtMost(500L))
                        }
                    }
                }
            }
            output.flush()
        } catch (_: Throwable) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else String(buf.toByteArray(), StandardCharsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return String(buf.toByteArray(), StandardCharsets.UTF_8)
    }
}
