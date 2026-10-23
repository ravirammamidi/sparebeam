package com.sparebeam.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.sparebeam.R

class MediaServerService : Service() {
    private var server: SpareBeamHttpServer? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("SpareBeam is running").setContentText("Sharing selected folders on your local Wi-Fi").setOngoing(true).build())
        runCatching { if (server == null) { server = SpareBeamHttpServer(this).also { it.start(5_000, false) }; isRunning = true } }
            .onFailure { isRunning = false; stopSelf() }
        return START_STICKY
    }
    override fun onDestroy() { server?.stop(); server = null; isRunning = false; super.onDestroy() }
    private fun createChannel() { if (android.os.Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Media server", NotificationManager.IMPORTANCE_LOW)) }
    companion object {
        const val ACTION_START = "com.sparebeam.START"
        const val ACTION_STOP = "com.sparebeam.STOP"
        private const val CHANNEL = "sparebeam_server"
        private const val NOTIFICATION_ID = 1
        @Volatile var isRunning = false
        fun address(context: Context): String? = runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
            if (ip == "0.0.0.0") null else "http://$ip:8080"
        }.getOrNull()
    }
}
