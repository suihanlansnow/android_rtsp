package com.shiliu.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 推流前台服务：息屏、切后台或锁屏时保持相机、编码与网络线程存活。
 * 只负责保活与通知展示，真正的推流链路仍由 StreamController 持有。
 */
class StreamService : Service() {

    companion object {
        private const val CHANNEL_ID = "stream_service"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_RTSP_URL = "rtsp_url"
        private const val EXTRA_RTP_URL = "rtp_url"

        fun start(context: Context, rtspUrl: String, rtpUrl: String) {
            val intent = Intent(context, StreamService::class.java)
                .putExtra(EXTRA_RTSP_URL, rtspUrl)
                .putExtra(EXTRA_RTP_URL, rtpUrl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShiLiu:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rtsp = intent?.getStringExtra(EXTRA_RTSP_URL) ?: "--"
        val rtp = intent?.getStringExtra(EXTRA_RTP_URL) ?: "--"
        startForeground(NOTIFICATION_ID, buildNotification(rtsp, rtp))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "推流服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持摄像头推流在后台运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(rtspUrl: String, rtpUrl: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在推流")
            .setContentText(rtspUrl)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("RTSP：$rtspUrl\nRTP：$rtpUrl")
            )
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
