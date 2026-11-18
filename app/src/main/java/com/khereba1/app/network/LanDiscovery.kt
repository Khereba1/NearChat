package com.khereba1.app.network

import android.content.Context
import com.khereba1.app.AppLog
import com.khereba1.app.data.NearChatDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets

/**
 * Lightweight UDP Auto-Discovery on LAN / Wi-Fi Hotspot.
 * - Host broadcasts its HTTP port + shared chat tokens every 2.5 seconds.
 * - Client listens and automatically maps each chat token -> live "http://<hostIp>:<port>".
 */
object LanDiscovery {
    private const val UDP_PORT = 48765
    private const val EXPIRY_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var listenSocket: DatagramSocket? = null

    private data class Entry(val serverUrl: String, val lastSeenMs: Long)
    private val entries = LinkedHashMap<String, Entry>()

    private val _discoveredHosts = MutableStateFlow<Map<String, String>>(emptyMap())
    val discoveredHosts: StateFlow<Map<String, String>> = _discoveredHosts

    fun startBroadcasting(context: Context, portProvider: () -> Int) {
        if (broadcastJob?.isActive == true) return
        val db = NearChatDb.get(context)
        broadcastJob = scope.launch {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { broadcast = true }
                while (isActive) {
                    val tokens = db.getSharedTokens()
                    val port = portProvider()
                    if (tokens.isNotEmpty() && port > 0) {
                        val payload = JSONObject()
                            .put("app", "NearChat")
                            .put("port", port)
                            .put("tokens", JSONArray(tokens))
                            .toString()
                            .toByteArray(StandardCharsets.UTF_8)

                        val targets = collectBroadcastAddresses()
                        for (addr in targets) {
                            runCatching {
                                sock.send(DatagramPacket(payload, payload.size, addr, UDP_PORT))
                            }
                        }
                    }
                    delay(2500)
                }
            } catch (e: Throwable) {
                AppLog.log("Discovery", "Broadcast loop ended: ${e.message}")
            } finally {
                runCatching { sock?.close() }
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    fun startListening() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            // Periodic expiry cleaner
            val cleaner = launch {
                while (isActive) {
                    delay(3000)
                    pruneExpired()
                }
            }
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                    soTimeout = 3000
                }
                listenSocket = sock
                val buf = ByteArray(4096)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        val senderIp = packet.address?.hostAddress ?: continue
                        val raw = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        val json = JSONObject(raw)
                        if (json.optString("app") != "NearChat") continue
                        val port = json.optInt("port", 8765)
                        val arr = json.optJSONArray("tokens") ?: continue
                        val url = "http://$senderIp:$port"
                        val now = System.currentTimeMillis()
                        synchronized(entries) {
                            for (i in 0 until arr.length()) {
                                val tok = arr.optString(i).trim().uppercase()
                                if (tok.isNotEmpty()) {
                                    entries[tok] = Entry(url, now)
                                }
                            }
                            emitSnapshotLocked()
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal timeout to check isActive
                    } catch (_: Throwable) {
                    }
                }
            } catch (e: Throwable) {
                AppLog.log("Discovery", "Listener failed: ${e.message}")
            } finally {
                cleaner.cancel()
                runCatching { listenSocket?.close() }
                listenSocket = null
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
    }

    fun resolveToken(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return _discoveredHosts.value[token.trim().uppercase()]
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            val it = entries.entries.iterator()
            var changed = false
            while (it.hasNext()) {
                if (now - it.next().value.lastSeenMs > EXPIRY_MS) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) emitSnapshotLocked()
        }
    }

    private fun emitSnapshotLocked() {
        val map = LinkedHashMap<String, String>(entries.size)
        for ((k, v) in entries) {
            map[k] = v.serverUrl
        }
        _discoveredHosts.value = map
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val set = LinkedHashSet<InetAddress>()
        runCatching { set.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    val bcast = ia.broadcast
                    if (addr is Inet4Address && bcast != null) {
                        set.add(bcast)
                    }
                }
            }
        }
        return set.toList()
    }
}
