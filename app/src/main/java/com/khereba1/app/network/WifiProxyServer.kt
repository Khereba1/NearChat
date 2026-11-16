package com.khereba1.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
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
    val p2pSsid: String = "",
    val p2pPass: String = "",
    val p2pError: String = ""
)

object WifiProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pManager: WifiP2pManager? = null

    private val _state = MutableStateFlow(RouterBridgeState())
    val state: StateFlow<RouterBridgeState> = _state

    @SuppressLint("MissingPermission")
    fun start(context: Context, preferredPort: Int = 8899) {
        if (running.get()) return
        try {
            val ss = try {
                ServerSocket(preferredPort)
            } catch (_: Throwable) {
                ServerSocket(0)
            }
            serverSocket = ss
            running.set(true)
            val exec = Executors.newCachedThreadPool()
            pool = exec
            val ip = NetworkUtils.getLocalIpAddress() ?: "192.168.49.1"
            _state.value = RouterBridgeState(
                isRunning = true,
                proxyHost = ip,
                proxyPort = ss.localPort
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

            // Try starting a Wi-Fi Direct group if hardware allows
            try {
                val mgr = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                p2pManager = mgr
                if (mgr != null) {
                    val ch = mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)
                    p2pChannel = ch
                    mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            mgr.requestGroupInfo(ch) { group ->
                                if (group != null) {
                                    _state.value = _state.value.copy(
                                        p2pSsid = group.networkName ?: "",
                                        p2pPass = group.passphrase ?: "",
                                        p2pError = ""
                                    )
                                }
                            }
                        }

                        override fun onFailure(reason: Int) {
                            _state.value = _state.value.copy(
                                p2pError = "Wi-Fi Direct unavailable (code $reason)"
                            )
                        }
                    })
                }
            } catch (e: Throwable) {
                AppLog.log("PROXY", "Wi-Fi Direct group error", e)
            }
        } catch (e: Throwable) {
            AppLog.log("PROXY", "Proxy start failed", e)
            stop()
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        try { pool?.shutdownNow() } catch (_: Throwable) {}
        pool = null
        try {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr != null && ch != null) {
                mgr.removeGroup(ch, null)
            }
        } catch (_: Throwable) {}
        p2pChannel = null
        _state.value = RouterBridgeState(isRunning = false)
    }

    private fun handleProxyClient(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 30_000
                val inStream = c.getInputStream()
                val outStream = c.getOutputStream()
                val firstLine = readLine(inStream) ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0].uppercase()
                val target = parts[1]

                val headerLines = mutableListOf<String>()
                while (true) {
                    val line = readLine(inStream) ?: break
                    if (line.isEmpty()) break
                    headerLines.add(line)
                }

                if (method == "CONNECT") {
                    val host = target.substringBefore(":")
                    val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
                    Socket(host, port).use { remote ->
                        outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                        outStream.flush()
                        val t1 = Thread { pipe(inStream, remote.getOutputStream()) }
                        t1.start()
                        pipe(remote.getInputStream(), outStream)
                        t1.join(1000)
                    }
                } else {
                    val uri = java.net.URI(target)
                    val host = uri.host ?: return
                    val port = if (uri.port > 0) uri.port else 80
                    val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                        (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                    Socket(host, port).use { remote ->
                        val rOut = remote.getOutputStream()
                        rOut.write("$method $path HTTP/1.1\r\n".toByteArray(StandardCharsets.UTF_8))
                        for (h in headerLines) {
                            if (!h.lowercase().startsWith("proxy-connection")) {
                                rOut.write("$h\r\n".toByteArray(StandardCharsets.UTF_8))
                            }
                        }
                        rOut.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
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
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                output.flush()
            }
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
