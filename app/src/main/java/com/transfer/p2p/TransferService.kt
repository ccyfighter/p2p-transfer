package com.transfer.p2p

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

class TransferService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val engine = TransferEngine()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "p2p_transfer_channel"

    inner class LocalBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "P2PTransfer::ServiceWakeLock").apply {
            acquire(2 * 60 * 60 * 1000L) // 最长保活2小时
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "P2PTransfer::ServiceWifiLock").apply {
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "传输任务通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示快传实时后台进度"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, content: String, progress: Int, max: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    // 接收端任务
    fun runReceiverTask(
        onLog: (String) -> Unit,
        onProgress: (fileName: String, progress: Float, current: Int, total: Int) -> Unit,
        onCompleted: (File) -> Unit,
        onAllFinished: () -> Unit
    ) {
        updateNotification("接收端运行中", "等待连接并接收文件...", 0, 0)
        serviceScope.launch {
            try {
                engine.startServer(
                    onLog = onLog,
                    onProgress = { name, p, curr, total ->
                        updateNotification(
                            title = "正在接收 [$curr/$total]",
                            content = "$name (${(p * 100).toInt()}%)",
                            progress = (p * 100).toInt(),
                            max = 100
                        )
                        onProgress(name, p, curr, total)
                    },
                    onCompleted = onCompleted
                )
            } finally {
                withContext(Dispatchers.Main) {
                    onAllFinished()
                    stopSelfGracefully() // 任务一完自动结束并干掉通知栏
                }
            }
        }
    }

    // 发送端任务
    fun runSenderTask(
        targetIp: String,
        items: List<TransferItem>,
        onLog: (String) -> Unit,
        onProgress: (fileName: String, progress: Float, current: Int, total: Int) -> Unit,
        onAllFinished: () -> Unit
    ) {
        updateNotification("发送端已启动", "正在发送数据...", 0, 0)
        serviceScope.launch {
            try {
                engine.sendItems(
                    context = applicationContext,
                    serverIp = targetIp,
                    items = items,
                    onLog = onLog,
                    onProgress = { name, p, curr, total ->
                        updateNotification(
                            title = "正在发送 [$curr/$total]",
                            content = "$name (${(p * 100).toInt()}%)",
                            progress = (p * 100).toInt(),
                            max = 100
                        )
                        onProgress(name, p, curr, total)
                    }
                )
            } catch (e: Exception) {
                // 忽略或打印异常
            } finally {
                withContext(Dispatchers.Main) {
                    onAllFinished()
                    stopSelfGracefully() // 发送完毕立即销毁通知栏
                }
            }
        }
    }

    private fun stopSelfGracefully() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }
}
