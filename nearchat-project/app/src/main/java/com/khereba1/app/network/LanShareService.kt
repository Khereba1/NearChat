package com.khereba1.app.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.khereba1.app.MainActivity
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

    companion object {
        private const val CHANNEL_ID = "nearchat_lan_channel"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.khereba1.nearchat.STOP_LAN"

        private val _state = MutableStateFlow(LanServerState())
        val state: StateFlow<LanServerState> = _state

        fun start(context: Context) {
            val intent = Intent(context, LanShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanShareService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        if (server == null) {
            val s = LanHttpServer(this)
            if (s.start()) {
                server = s
                val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                val url = "http://$ip:${s.port}"
                _state.value = LanServerState(isRunning = true, baseUrl = url, port = s.port)
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

    override fun onDestroy() {
        server?.stop()
        server = null
        _state.value = LanServerState(isRunning = false)
        super.onDestroy()
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
}
