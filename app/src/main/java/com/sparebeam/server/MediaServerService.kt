package com.sparebeam.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

class MediaServerService : Service() {
    private var server: SpareBeamHttpServer? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("SpareBeam is running").setContentText("Sharing selected folders on your local Wi-Fi").setOngoing(true).build())
        runCatching {
            if (server == null) {
                val wifiIp = wifiAddress(this) ?: error("Wi-Fi address unavailable")
                server = SpareBeamHttpServer(this, wifiIp).also { it.start(5_000, false) }
                errorMessage.value = null
                running.value = true
            }
        }
            .onFailure {
                running.value = false
                errorMessage.value = "Could not start the media server. Check Wi-Fi and try again."
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        return START_STICKY
    }
    override fun onDestroy() { server?.stop(); server = null; running.value = false; super.onDestroy() }
    private fun createChannel() { if (android.os.Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Media server", NotificationManager.IMPORTANCE_LOW)) }
    companion object {
        const val ACTION_START = "com.sparebeam.START"
        const val ACTION_STOP = "com.sparebeam.STOP"
        private const val CHANNEL = "sparebeam_server"
        private const val NOTIFICATION_ID = 1
        val running = MutableStateFlow(false)
        val errorMessage = MutableStateFlow<String?>(null)
        private fun wifiAddress(context: Context): String? = runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
            if (ip == "0.0.0.0") null else ip
        }.getOrNull()
        fun address(context: Context): String? = wifiAddress(context)?.let { "http://$it:8080" }
    }
}
