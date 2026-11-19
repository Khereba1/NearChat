package com.khereba1.app.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.khereba1.app.MainActivity
import com.khereba1.app.PREFS
import com.khereba1.app.R
import com.khereba1.app.data.NearChatDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LanServerState(
    val isRunning: Boolean = false,
    val baseUrl: String = "",
    val port: Int = 8765
)

class LanShareService : Service() {
    private var server: LanHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var watcherModeOnly: Boolean = false

    companion object {
        private const val CHANNEL_ID = "nearchat_lan_channel"
        private const val WATCHER_CHANNEL_ID = "nearchat_watcher_channel"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.khereba1.nearchat.STOP_LAN"
        private const val ACTION_START_WATCHER = "com.khereba1.nearchat.START_WATCHER"
        private const val ACTION_STOP_WATCHER = "com.khereba1.nearchat.STOP_WATCHER"

        private val _state = MutableStateFlow(LanServerState())
        val state: StateFlow<LanServerState> = _state

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, LanShareService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_vpn_on_hotspot", false) && prefs.getBoolean("auto_vpn_bg_watcher", false)) {
                startWatcher(appContext)
            } else {
                appContext.stopService(Intent(appContext, LanShareService::class.java))
            }
        }

        fun startWatcher(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, LanShareService::class.java).setAction(ACTION_START_WATCHER)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stopWatcher(context: Context) {
            val appContext = context.applicationContext
            if (_state.value.isRunning) return
            appContext.stopService(Intent(appContext, LanShareService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            WifiProxyServer.stop(keepGroup = true)
            releaseLocks()
            LanDiscovery.stopBroadcasting()
            server?.stop()
            server = null
            _state.value = LanServerState(isRunning = false)
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_vpn_on_hotspot", false) && prefs.getBoolean("auto_vpn_bg_watcher", false)) {
                return runWatcherForeground()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_WATCHER) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("auto_vpn_bg_watcher", false).apply()
            if (!_state.value.isRunning) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (action == ACTION_START_WATCHER && server == null) {
            return runWatcherForeground()
        }

        watcherModeOnly = false
        ensureChannel()
        if (server == null) {
            val s = LanHttpServer(this)
            if (s.start()) {
                acquireLocks()
                server = s
                val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                val url = "http://$ip:${s.port}"
                _state.value = LanServerState(isRunning = true, baseUrl = url, port = s.port)
                LanDiscovery.startBroadcasting(this) { server?.port ?: 8765 }
            }
        }

        val currentUrl = _state.value.baseUrl.ifEmpty { "LAN" }
        val count = NearChatDb.get(this).getSharedChatsCount()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LanShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.notif_lan_title))
            .setContentText(getString(R.string.notif_lan_text, count, currentUrl))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_server), stopIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    private fun runWatcherForeground(): Int {
        watcherModeOnly = true
        HotspotAutoVpnManager.init(this)
        ensureWatcherChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopWatcherIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, LanShareService::class.java).setAction(ACTION_STOP_WATCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, WATCHER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.auto_vpn_watcher_title))
            .setContentText(getString(R.string.auto_vpn_hotspot_desc))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_server), stopWatcherIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        LanDiscovery.stopBroadcasting()
        server?.stop()
        server = null
        _state.value = LanServerState(isRunning = false)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NearChat:LanWake").apply {
                    acquire(60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NearChat:LanWifi").apply {
                    acquire()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_lan),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun ensureWatcherChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(WATCHER_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    WATCHER_CHANNEL_ID,
                    "Auto-VPN Background Watcher",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
