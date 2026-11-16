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
import com.khereba1.app.AppLog
import com.khereba1.app.PREFS
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
import java.util.concurrent.atomic.AtomicBoolean

data class RouterBridgeState(
    val isRunning: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8899,
    val socksPort: Int = 1080,
    val p2pSsid: String = "",
    val p2pPass: String = "",
    val p2pError: String = ""
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(RouterBridgeState())
    val state: StateFlow<RouterBridgeState> = _state

    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun start(context: Context, preferredPort: Int = 8899, preferredSocksPort: Int = 1080) {
        if (running.get()) return
        val ctx = context.applicationContext
        appContext = ctx
        val customPass = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("router_pass", "NearChat123") ?: "NearChat123"
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

            try {
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

    fun stop(keepGroup: Boolean = true) {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        releaseLocks()
        try { serverSocket?.close() } catch (_: Throwable) {}
        try { socksSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        socksSocket = null
        try { pool?.shutdownNow() } catch (_: Throwable) {}
        pool = null
        if (!keepGroup) {
            try {
                val mgr = p2pManager
                val ch = p2pChannel
                if (mgr != null && ch != null) {
                    mgr.removeGroup(ch, null)
                }
            } catch (_: Throwable) {}
            p2pChannel = null
        }
        _state.value = RouterBridgeState(isRunning = false)
    }

    private fun tuneSocket(s: Socket) {
        try {
            s.soTimeout = 120_000
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (_: Throwable) {
        }
    }

    private fun activeInternetNetwork(): Network? {
        val ctx = appContext ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) net else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun openInternetSocket(host: String, port: Int): Socket {
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
                repeat(nMethods) { inS.read() }
                outS.write(byteArrayOf(0x05, 0x00))
                outS.flush()

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

                openInternetSocket(host, port).use { remote ->
                    outS.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    outS.flush()
                    val t1 = Thread { pipe(inS, remote.getOutputStream()) }
                    t1.start()
                    pipe(remote.getInputStream(), outS)
                    t1.join(3000)
                }
            } catch (_: Throwable) {
            }
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
                    while (inS.read() != -1) { /* keep watching TCP control socket */ }
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
                clientAddr = InetSocketAddress(pkt.address, pkt.port)
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

                if (method == "CONNECT") {
                    val host = target.substringBefore(":")
                    val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
                    openInternetSocket(host, port).use { remote ->
                        outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                        outStream.flush()
                        val t1 = Thread { pipe(inStream, remote.getOutputStream()) }
                        t1.start()
                        pipe(remote.getInputStream(), outStream)
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
                        pipe(remote.getInputStream(), outStream)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
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
