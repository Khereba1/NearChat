package com.khereba1.app.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.khereba1.app.AppLog
import com.khereba1.app.MainActivity
import com.khereba1.app.R
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.khereba1.app.PREFS
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.InetSocketAddress


class NearChatVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var watchJob: Job? = null
    private var isNekoCoreMode: Boolean = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "nearchat_vpn_channel"
        private const val NOTIF_ID = 1002
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_NEKO_CORE = "neko_core_mode"
        const val ACTION_STOP = "com.khereba1.nearchat.STOP_VPN"

        // NekoBox VpnService VLAN constants
        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"

        @Volatile
        private var instance: NearChatVpnService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        fun protectFd(fd: Int): Boolean {
            return try {
                instance?.protect(fd) ?: false
            } catch (_: Throwable) {
                false
            }
        }

        fun waitForInstance(timeoutMs: Long = 3500L): NearChatVpnService? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val cur = instance
                if (cur != null) return cur
                Thread.sleep(50)
            }
            return instance
        }

        /** Called by NekoBox libcore BoxPlatformInterface.openTun(). */
        fun openTunFromCore(): Int {
            val svc = waitForInstance(3500L) ?: throw IllegalStateException("NearChatVpnService not running")
            return svc.establishNekoTun()
        }

        fun startNekoVpn(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, NearChatVpnService::class.java).apply {
                putExtra(EXTRA_NEKO_CORE, true)
                putExtra(EXTRA_HOST_IP, "127.0.0.1")
                putExtra(EXTRA_SOCKS_PORT, XrayTunnelManager.LOCAL_SOCKS_PORT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun start(context: Context, hostIp: String, socksPort: Int = 1080) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, NearChatVpnService::class.java).apply {
                putExtra(EXTRA_NEKO_CORE, false)
                putExtra(EXTRA_HOST_IP, hostIp)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, NearChatVpnService::class.java).setAction(ACTION_STOP)
            appContext.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            val wasNeko = isNekoCoreMode
            stopTunnel()
            if (wasNeko) {
                runCatching { XrayTunnelManager.stop() }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val nekoMode = intent.getBooleanExtra(EXTRA_NEKO_CORE, false)
        val hostIp = intent.getStringExtra(EXTRA_HOST_IP)?.trim()?.ifEmpty { "192.168.49.1" } ?: "192.168.49.1"
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080).coerceIn(1, 65535)
        isNekoCoreMode = nekoMode

        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, NearChatVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val subtitle = if (nekoMode) {
            "NekoBox sing-box TUN active"
        } else {
            "Routing all apps via $hostIp:$socksPort"
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("NearChat VPN Active")
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_server), stopIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        if (nekoMode) {
            // In NekoBox mode, libcore calls openTun() -> establishNekoTun() during box.start()
            watchNekoCore()
        } else {
            startManualTunnel(hostIp, socksPort, openIntent)
            watchLocalTunnel(hostIp, socksPort)
        }
        return START_NOT_STICKY
    }

    /**
     * Exact port of NekoBox's VpnService.startVpn():
     * Establishes the TUN fd with 172.19.0.1/30, DNS 172.19.0.2, MTU 9000, and returns fd to libcore.
     */
    @Synchronized
    fun establishNekoTun(): Int {
        try {
            tunFd?.close()
        } catch (_: Throwable) {
        }
        tunFd = null

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Builder()
            .setConfigureIntent(openIntent)
            .setSession("NearChat VPN")
            .setMtu(9000)
            .addAddress(PRIVATE_VLAN4_CLIENT, 30)
            .addDnsServer(PRIVATE_VLAN4_ROUTER)
            .addRoute("0.0.0.0", 0)
            .addDisallowedApplication(packageName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val phys = PhysicalNetwork.current(this)
        if (phys != null) {
            runCatching { builder.setUnderlyingNetworks(arrayOf(phys.network)) }
        }

        val pfd = builder.establish() ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        tunFd = pfd
        _isRunning.value = true

        PhysicalNetwork.unwatch(this, netCallback)
        netCallback = PhysicalNetwork.watch(this) { refreshUnderlyingNetwork() }
        return pfd.fd
    }

    private fun watchNekoCore() {
        watchJob?.cancel()
        watchJob = scope.launch {
            var seenRunning = false
            XrayTunnelManager.state.collect { st ->
                if (st.isRunning) {
                    seenRunning = true
                } else if (seenRunning) {
                    AppLog.log("VPN", "NekoBox core stopped -> stopping VPN")
                    stopTunnel()
                    stopSelf()
                }
            }
        }
    }

    private fun watchLocalTunnel(hostIp: String, socksPort: Int) {
        watchJob?.cancel()
        watchJob = null
        if (hostIp != "127.0.0.1" || socksPort != XrayTunnelManager.LOCAL_SOCKS_PORT) return
        if (!_isRunning.value) return
        watchJob = scope.launch {
            var seenRunning = false
            XrayTunnelManager.state.collect { st ->
                if (st.isRunning) {
                    seenRunning = true
                } else if (seenRunning) {
                    AppLog.log("VPN", "Local tunnel stopped -> stopping VPN")
                    stopTunnel()
                    stopSelf()
                }
            }
        }
    }

    private fun startManualTunnel(hostIp: String, socksPort: Int, configureIntent: PendingIntent) {
        stopTunnel()
        try {
            val builder = Builder()
                .setConfigureIntent(configureIntent)
                .setSession("NearChat VPN")
                .setMtu(1500)
                .addAddress("10.10.10.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val phys = PhysicalNetwork.current(this)
            if (phys != null) {
                try {
                    builder.setUnderlyingNetworks(arrayOf(phys.network))
                } catch (_: Throwable) {
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                AppLog.log("VPN", "VpnService.Builder.establish() returned null")
                _isRunning.value = false
                stopSelf()
                return
            }
            tunFd = pfd

            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val socksPass = prefs.getString("vpn_proxy_pass", "")?.trim().orEmpty()
            val authLines = if (socksPass.isNotEmpty()) {
                "\n  username: 'nearchat'\n  password: '${socksPass.replace("'", "")}'"
            } else ""
            val configFile = File(filesDir, "tproxy.yml")
            configFile.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 10.10.10.1
                socks5:
                  port: $socksPort
                  address: $hostIp
                  udp: 'udp'$authLines
                """.trimIndent()
            )

            val ok = TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
            _isRunning.value = ok
            AppLog.log("VPN", "TProxyStartService($hostIp:$socksPort) -> $ok")
            if (!ok) {
                stopTunnel()
                stopSelf()
                return
            }

            netCallback = PhysicalNetwork.watch(this) { refreshUnderlyingNetwork() }
        } catch (e: Throwable) {
            AppLog.log("VPN", "Failed to start VPN tunnel", e)
            stopTunnel()
            stopSelf()
        }
    }

    private fun refreshUnderlyingNetwork() {
        if (tunFd == null) return
        val phys = PhysicalNetwork.current(this) ?: return
        try {
            setUnderlyingNetworks(arrayOf(phys.network))
        } catch (_: Throwable) {
        }
    }

    private fun stopTunnel() {
        PhysicalNetwork.unwatch(this, netCallback)
        netCallback = null
        try {
            if (!isNekoCoreMode && TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        } catch (_: Throwable) {
        }
        try {
            tunFd?.close()
        } catch (_: Throwable) {
        }
        tunFd = null
        _isRunning.value = false
    }

    override fun onRevoke() {
        val wasNeko = isNekoCoreMode
        stopTunnel()
        if (wasNeko) {
            runCatching { XrayTunnelManager.stop() }
        }
        stopSelf()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        stopTunnel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "NearChat VPN",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}


/**
 * Watches Wi-Fi connections and automatically starts/stops Client VPN when connected to a
 * NearChat Host Hotspot or LAN proxy (e.g., streaming tablet/phone without root).
 * Works without GPS/Location permission by probing the DHCP gateway IP on the Wi-Fi transport.
 */
object HotspotAutoVpnManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    private var activeWifiNetwork: Network? = null
    private var autoTriggeredSession = false
    private var probeJob: Job? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    activeWifiNetwork = network
                    scheduleProbe(appContext, network, cm.getLinkProperties(network))
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    activeWifiNetwork = network
                    scheduleProbe(appContext, network, linkProperties)
                }

                override fun onLost(network: Network) {
                    if (activeWifiNetwork == network) {
                        activeWifiNetwork = null
                    }
                    probeJob?.cancel()
                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val autoEnabled = prefs.getBoolean("auto_vpn_on_hotspot", false)
                    if (autoEnabled && NearChatVpnService.isRunning.value && !XrayTunnelManager.state.value.isRunning) {
                        AppLog.log("AutoVPN", "Host Wi-Fi disconnected -> auto-stopping Client VPN")
                        autoTriggeredSession = false
                        NearChatVpnService.stop(appContext)
                    }
                }
            })
        }
    }

    fun triggerCheckNow(context: Context) {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val net = activeWifiNetwork ?: return
        scheduleProbe(appContext, net, cm.getLinkProperties(net))
    }

    private fun scheduleProbe(appContext: Context, network: Network, lp: LinkProperties?) {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_vpn_on_hotspot", false)) return
        if (NearChatVpnService.isRunning.value || XrayTunnelManager.state.value.isRunning) return
        if (VpnService.prepare(appContext) != null) {
            AppLog.log("AutoVPN", "VPN permission not granted yet; grant once in app first.")
            return
        }

        probeJob?.cancel()
        probeJob = scope.launch {
            val savedHost = prefs.getString("vpn_host_ip", "192.168.49.1")?.trim().orEmpty()
            val savedPort = prefs.getString("vpn_host_port", "1080")?.trim()?.toIntOrNull() ?: 1080

            val candidateHosts = linkedSetOf<String>()
            if (savedHost.isNotEmpty() && savedHost != "127.0.0.1") candidateHosts.add(savedHost)
            lp?.routes?.forEach { route ->
                val gw = route.gateway
                if (gw is Inet4Address && !gw.isLoopbackAddress) {
                    gw.hostAddress?.let { if (it != "0.0.0.0") candidateHosts.add(it) }
                }
            }
            candidateHosts.add("192.168.49.1")
            candidateHosts.add("192.168.43.1")

            val candidatePorts = linkedSetOf(savedPort, 1080, XrayTunnelManager.LOCAL_SOCKS_PORT)

            // Try up to 3 quick passes (gives DHCP + Host Router a moment after Wi-Fi association)
            for (attempt in 1..3) {
                if (NearChatVpnService.isRunning.value || XrayTunnelManager.state.value.isRunning) return@launch
                for (host in candidateHosts) {
                    for (port in candidatePorts) {
                        if (isPortOpenOnNetwork(network, host, port, 550)) {
                            AppLog.log("AutoVPN", "Detected Host proxy at $host:$port on Wi-Fi -> auto-starting Client VPN")
                            prefs.edit()
                                .putString("vpn_host_ip", host)
                                .putString("vpn_host_port", port.toString())
                                .apply()
                            autoTriggeredSession = true
                            NearChatVpnService.start(appContext, host, port)
                            return@launch
                        }
                    }
                }
                delay(1200)
            }
        }
    }

    private fun isPortOpenOnNetwork(network: Network, host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            network.socketFactory.createSocket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Keeps CPU and Wi-Fi radio at high performance on old streaming/host devices when screen turns off.
 */
object StreamerKeepAlive {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (enabled) {
            try {
                if (wakeLock == null) {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NearChat::StreamerWakeLock").apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire()
                }
                if (wifiLock == null) {
                    val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm.createWifiLock(mode, "NearChat::StreamerWifiLock").apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
                AppLog.log("StreamerMode", "WakeLock + WifiLock acquired for uninterrupted Hotspot/Proxy streaming")
            } catch (e: Throwable) {
                AppLog.log("StreamerMode", "Failed to acquire locks", e)
            }
        } else {
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Throwable) {}
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Throwable) {}
            AppLog.log("StreamerMode", "WakeLock + WifiLock released")
        }
    }
}
