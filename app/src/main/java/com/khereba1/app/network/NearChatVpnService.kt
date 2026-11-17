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

class NearChatVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var watchJob: Job? = null
    private var currentHostIp: String = ""
    private var currentSocksPort: Int = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "nearchat_vpn_channel"
        private const val NOTIF_ID = 1002
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val ACTION_STOP = "com.khereba1.nearchat.STOP_VPN"

        @Volatile
        private var instance: NearChatVpnService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        /** Lets the tunnel core keep its own outbound sockets outside the VPN (no routing loop). */
        fun protectFd(fd: Int): Boolean {
            return try {
                instance?.protect(fd) ?: false
            } catch (_: Throwable) {
                false
            }
        }

        fun start(context: Context, hostIp: String, socksPort: Int = 1080) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, NearChatVpnService::class.java).apply {
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
            // The system re-created us after the process died. Never resume a stale tunnel:
            // the SOCKS target (sing-box or the router host) is gone and an empty VPN cuts the internet.
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            val wasLocalCore = currentHostIp == "127.0.0.1" && currentSocksPort == XrayTunnelManager.LOCAL_SOCKS_PORT
            stopTunnel()
            if (wasLocalCore) {
                runCatching { XrayTunnelManager.stop() }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val hostIp = intent.getStringExtra(EXTRA_HOST_IP)?.trim()?.ifEmpty { "192.168.49.1" } ?: "192.168.49.1"
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080).coerceIn(1, 65535)
        currentHostIp = hostIp
        currentSocksPort = socksPort

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
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("NearChat VPN Active")
            .setContentText("Routing all apps via $hostIp:$socksPort")
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_server), stopIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        startTunnel(hostIp, socksPort, openIntent)
        watchLocalTunnel(hostIp, socksPort)
        return START_NOT_STICKY
    }

    /**
     * If the VPN points at the built-in sing-box tunnel, tie the VPN's life to the tunnel's life.
     * Without this, stopping or crashing the core leaves every app routed into a dead port.
     */
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

    private fun startTunnel(hostIp: String, socksPort: Int, configureIntent: PendingIntent) {
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

            val configFile = File(filesDir, "tproxy.yml")
            configFile.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 10.10.10.1
                socks5:
                  port: $socksPort
                  address: $hostIp
                  udp: 'udp'
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
            if (TProxyService.TProxyIsRunning()) {
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
        stopTunnel()
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
