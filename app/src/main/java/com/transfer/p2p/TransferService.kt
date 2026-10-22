package com.transfer.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TransferService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "p2p_transfer_channel"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = TransferEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var listenJob: Job? = null
    private var sendJob: Job? = null

    private var lastNotifyAt = 0L
    private var lastProgressAt = 0L

    inner class LocalBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        // ★ 必须在 onCreate 里立刻进前台：否则 startForegroundService 后 5 秒未 startForeground 会被系统杀进程（闪退）
        pushNotification("P2P 快传待命中", "服务已就绪，等待接收或发送", 0, 0, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pushNotification("P2P 快传待命中", "服务已就绪，等待接收或发送", 0, 0, true)
        return START_STICKY
    }

    /** Android 14 上 dataSync 前台服务有 6 小时超时，超时前优雅收尾 */
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        pushNotification("前台服务已达 6 小时上限", "即将停止，请重新打开 App 继续", 0, 0, true)
        stopListening()
        stopSelf()
    }

    fun isListening(): Boolean = listenJob?.isActive == true
    fun isBusy(): Boolean = listenJob?.isActive == true || sendJob?.isActive == true

    /** 幂等：已在监听则直接返回 */
    fun startListening(
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit,
        onCompleted: (File) -> Unit,
        onState: (Boolean, String) -> Unit
    ) {
        if (listenJob?.isActive == true) {
            postMain { onState(true, "") }
            return
        }
        listenJob = scope.launch {
            postMain { onState(true, "") }
            try {
                engine.runServer(
                    onLog = { msg -> postMain { onLog(msg) } },
                    onProgress = progressThrottled("接收", onProgress),
                    onCompleted = { f -> postMain { onCompleted(f) } },
                    onSessionEnd = { _, msg -> postMain { onState(true, msg) } }
                )
            } catch (e: CancellationException) {
                // 用户主动停止
            } catch (e: Exception) {
                postMain { onLog("监听异常：${e.message}") }
            } finally {
                postMain { onState(false, "监听已停止") }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        pushNotification("P2P 快传待命中", "已停止监听", 0, 0, true)
    }

    fun sendTask(
        targetIp: String,
        items: List<TransferItem>,
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit,
        onAllFinished: (Boolean, String) -> Unit
    ) {
        if (sendJob?.isActive == true) {
            postMain { onLog("已有发送任务在进行中") }
            return
        }
        sendJob = scope.launch {
            var error: String? = null
            try {
                engine.sendItems(
                    context = applicationContext,
                    serverIp = targetIp,
                    items = items,
                    onLog = { msg -> postMain { onLog(msg) } },
                    onProgress = progressThrottled("发送", onProgress)
                )
            } catch (e: CancellationException) {
                error = "任务被取消"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                val ok = error == null
                withContext(Dispatchers.Main) { onAllFinished(ok, error ?: "") }
                if (ok) {
                    pushNotification("发送完成", "全部项目已推送完成", 0, 0, true)
                } else {
                    pushNotification("发送已中断", "接收端仍在监听，可点「继续发送」续传", 0, 0, true)
                }
            }
        }
    }

    // ---------------- 内部工具 ----------------

    /** 80ms 节流，避免每 1MB 块都触发一次 Compose 重组 */
    private fun progressThrottled(
        action: String,
        onProgress: (String, Float, Int, Int) -> Unit
    ): (String, Float, Int, Int) -> Unit = { name, p, curr, total ->
        val now = System.currentTimeMillis()
        if (p >= 1f || now - lastProgressAt >= 80L) {
            lastProgressAt = now
            postMain { onProgress(name, p, curr, total) }
            pushNotification("正在$action [$curr/$total]", "$name (${(p * 100).toInt()}%)", (p * 100).toInt(), 100)
        }
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "传输任务通知", NotificationManager.IMPORTANCE_LOW).apply {
            description = "展示快传实时后台进度"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun pushNotification(title: String, content: String, progress: Int, max: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < 300) return
        lastNotifyAt = now
        val notification = buildNotification(title, content, progress, max)
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun buildNotification(title: String, content: String, progress: Int, max: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (max > 0) builder.setProgress(max, progress.coerceIn(0, max), false)
        return builder.build()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "P2PTransfer::ServiceWakeLock").apply {
            acquire(5 * 60 * 60 * 1000L)   // 5 小时，配合 dataSync 的 6 小时上限
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "P2PTransfer::ServiceWifiLock").apply {
            acquire()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }
}
