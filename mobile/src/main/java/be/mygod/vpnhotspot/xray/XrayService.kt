package be.mygod.vpnhotspot.xray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.R

/** Keeps Routify alive in the background while the proxy is on, with a visible notification. */
class XrayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Routify connection", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
  PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = Notification.Builder(this, CHANNEL)
  .setContentTitle("Routify is on")
  .setContentText("Routify is running in the background. Tap to open.")
  .setSmallIcon(R.drawable.ic_route)
  .setOngoing(true)
  .setContentIntent(open)
  .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "routify_connection"
        private const val ID = 1701
        fun start() = ContextCompat.startForegroundService(app, Intent(app, XrayService::class.java))
        fun stop() { app.stopService(Intent(app, XrayService::class.java)) }
    }
}
