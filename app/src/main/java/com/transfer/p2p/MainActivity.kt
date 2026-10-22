package com.transfer.p2p

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    val selectedItems = mutableStateListOf<TransferItem>()
    private var scannedIpCallback: ((String) -> Unit)? = null
    var isScanning by mutableStateOf(false)

    var transferService: TransferService? = null
    var isServiceBound = false
    var keepCellular by mutableStateOf(false)

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TransferService.LocalBinder
            transferService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            isServiceBound = false
        }
    }

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris?.let { list ->
            lifecycleScope.launch(Dispatchers.IO) {
                val newItems = list.map { uri ->
                    val details = FileHelper.getUriDetails(this@MainActivity, uri)
                    TransferItem(name = details.first, size = details.second, contentUri = uri)
                }
                withContext(Dispatchers.Main) {
                    selectedItems.addAll(newItems)
                    Toast.makeText(this@MainActivity, "已添加 ${newItems.size} 个文件", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            isScanning = true
            lifecycleScope.launch(Dispatchers.IO) {
                val list = FileHelper.collectTreeFilesFast(this@MainActivity, it)
                withContext(Dispatchers.Main) {
                    selectedItems.addAll(list)
                    isScanning = false
                    Toast.makeText(this@MainActivity, "扫描完成：共发现 ${list.size} 个文件", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val raw = result.contents
            val ip = if (raw.startsWith("p2p://")) raw.removePrefix("p2p://") else raw
            scannedIpCallback?.invoke(ip)
            Toast.makeText(this, "已识别 IP: $ip", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkPermissions()

        val serviceIntent = Intent(this, TransferService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE)

        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkMode by remember { mutableStateOf(systemDark) }

            MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TransferAppScreen(
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { isDarkMode = !isDarkMode },
                        isScanning = isScanning,
                        selectedItems = selectedItems,
                        keepCellular = keepCellular,
                        onToggleCellular = { enable ->
                            keepCellular = enable
                            if (enable) {
                                NetworkBinder.requestCellular(this)
                                Toast.makeText(this, "已请求保持移动数据在线", Toast.LENGTH_SHORT).show()
                            } else {
                                NetworkBinder.releaseCellular(this)
                                Toast.makeText(this, "已释放移动数据请求", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPickSingleFiles = { pickFilesLauncher.launch(arrayOf("*/*")) },
                        onOpenFolderPicker = { pickFolderLauncher.launch(null) },
                        onScanQr = { callback ->
                            scannedIpCallback = callback
                            scanQrLauncher.launch(
                                ScanOptions().apply {
                                    setPrompt("请对准接收端屏幕二维码")
                                    setBeepEnabled(true)
                                    setOrientationLocked(true)
                                }
                            )
                        },
                        onInstallApk = { FileHelper.installApk(this, it) },
                        onNotifyFileSaved = { file -> FileHelper.notifyMediaScanner(this, file) },
                        onOpenDownloadFolder = { openDownloadFolder() },
                        onFetchApps = { FileHelper.getInstalledApps(this) },
                        transferServiceGetter = { transferService },
                        onStartReceiver = ::startReceiverListening,
                        onStopReceiver = ::stopReceiverListening
                    )
                }
            }
        }
    }

    /** 带重试：解决刚打开 App 时服务还没 bind 上、点按钮没反应的问题 */
    fun startReceiverListening(
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit,
        onCompleted: (File) -> Unit,
        onState: (Boolean, String) -> Unit
    ) {
        val svc = transferService
        if (svc != null) {
            svc.startListening(onLog, onProgress, onCompleted, onState)
            return
        }
        lifecycleScope.launch {
            repeat(20) {
                delay(150)
                val s = transferService
                if (s != null) {
                    s.startListening(onLog, onProgress, onCompleted, onState)
                    return@launch
                }
            }
            onLog("前台服务未启动成功，请退出 App 后重新打开")
        }
    }

    fun stopReceiverListening() {
        transferService?.stopListening()
    }

    private fun openDownloadFolder() {
        val folder = FileHelper.getDownloadDir()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FP2PTransfer")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "保存在: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        val busy = transferService?.isBusy() == true
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConn)
            isServiceBound = false
        }
        if (!busy) stopService(Intent(this, TransferService::class.java))
    }
}

fun getLocalHotspotIp(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val enumIpAddr = intf.inetAddresses
            while (enumIpAddr.hasMoreElements()) {
                val inetAddress = enumIpAddr.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress.hostAddress?.contains(":") == false) {
                    val ip = inetAddress.hostAddress ?: ""
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip
                    }
                }
            }
        }
        "192.168.43.1"
    } catch (_: Exception) {
        "192.168.43.1"
    }
}

fun generateQrBitmap(content: String): Bitmap? {
    return try {
        val size = 512
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] =
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    } catch (e: Exception) {
        null
    }
}

/** 日志封顶 8000 字，避免几千个文件时字符串无限拼接把堆吃爆 */
fun appendLog(old: String, line: String): String {
    val merged = if (old.isEmpty()) line else "$old\n$line"
    return if (merged.length > 8000) merged.takeLast(8000) else merged
}

@Composable
fun TransferAppScreen(
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    isScanning: Boolean,
    selectedItems: MutableList<TransferItem>,
    keepCellular: Boolean,
    onToggleCellular: (Boolean) -> Unit,
    onPickSingleFiles: () -> Unit,
    onOpenFolderPicker: () -> Unit,
    onScanQr: ((String) -> Unit) -> Unit,
    onInstallApk: (File) -> Unit,
    onNotifyFileSaved: (File) -> Unit,
    onOpenDownloadFolder: () -> Unit,
    onFetchApps: () -> List<AppItem>,
    transferServiceGetter: () -> TransferService?,
    onStartReceiver: (
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit,
        onCompleted: (File) -> Unit,
        onState: (Boolean, String) -> Unit
    ) -> Unit,
    onStopReceiver: () -> Unit
) {
    var mode by remember { mutableStateOf("HOME") }
    var logs by remember { mutableStateOf("") }

    var currentItemName by remember { mutableStateOf("") }
    var singleProgress by remember { mutableStateOf(0f) }
    var currentIndex by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var isTransferring by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }

    var showAppConfirmDialog by remember { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("192.168.43.1") }
    var serverLocalIp by remember { mutableStateOf("") }
    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }

    if (showAppConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showAppConfirmDialog = false },
            title = { Text("授权确认") },
            text = { Text("扫描已安装非系统应用并提取 base.apk，是否允许？") },
            confirmButton = {
                TextButton(onClick = {
                    showAppConfirmDialog = false
                    appList = onFetchApps()
                }) { Text("同意") }
            },
            dismissButton = {
                TextButton(onClick = { showAppConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("P2P 热点快传", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "常驻监听 · 断点续传 · 满速直连 · 0 流量",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onToggleDarkMode) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle Dark Mode"
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (mode == "HOME") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("请选择此设备角色：", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            mode = "RECEIVER"
                            serverLocalIp = getLocalHotspotIp()
                            onStartReceiver(
                                { msg -> logs = appendLog(logs, msg) },
                                { name, p, curr, total ->
                                    currentItemName = name
                                    singleProgress = p
                                    currentIndex = curr
                                    totalCount = total
                                },
                                { file ->
                                    onNotifyFileSaved(file)
                                    if (file.name.endsWith(".apk", ignoreCase = true)) onInstallApk(file)
                                },
                                { listening, msg ->
                                    isListening = listening
                                    if (msg.isNotBlank()) logs = appendLog(logs, msg)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("【接收端】(开热点机)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { mode = "SENDER" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("【发送端】(连热点机)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "提速提示：热点请设为 5GHz + WPA2，两机距离 1 米内，实测可达 45~70MB/s；" +
                                "2.4GHz 只有 10~20MB/s。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        }

        if (mode == "RECEIVER") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本机热点真实 IP: $serverLocalIp", style = MaterialTheme.typography.titleMedium)
                    Text("保存至 Download/P2PTransfer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                    Text(
                        text = if (isListening) "● 监听中：端口 9099 · 可反复接收" else "○ 未监听",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    val qr = remember(serverLocalIp) { generateQrBitmap("p2p://$serverLocalIp") }
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(160.dp).padding(vertical = 6.dp)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenDownloadFolder, modifier = Modifier.weight(1f)) {
                            Text("打开接收文件夹")
                        }
                        OutlinedButton(onClick = {
                            onStopReceiver()
                            isListening = false
                        }, modifier = Modifier.weight(1f)) {
                            Text("停止监听")
                        }
                    }

                    if (!isListening) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                onStartReceiver(
                                    { msg -> logs = appendLog(logs, msg) },
                                    { name, p, curr, total ->
                                        currentItemName = name
                                        singleProgress = p
                                        currentIndex = curr
                                        totalCount = total
                                    },
                                    { file ->
                                        onNotifyFileSaved(file)
                                        if (file.name.endsWith(".apk", ignoreCase = true)) onInstallApk(file)
                                    },
                                    { listening, msg ->
                                        isListening = listening
                                        if (msg.isNotBlank()) logs = appendLog(logs, msg)
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("重新开始监听") }
                    }

                    if (totalCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(currentItemName, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("[$currentIndex/$totalCount]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(progress = singleProgress, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text("${(singleProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (mode == "SENDER") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = targetIp,
                    onValueChange = { targetIp = it },
                    label = { Text("目标 IP") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onScanQr { scanned -> targetIp = scanned } }) {
                    Text("扫码配对")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPickSingleFiles, modifier = Modifier.weight(1f), enabled = !isTransferring) {
                    Text("选文件", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onOpenFolderPicker,
                    modifier = Modifier.weight(1f),
                    enabled = !isScanning && !isTransferring
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("选文件夹", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Button(onClick = { showAppConfirmDialog = true }, modifier = Modifier.weight(1f), enabled = !isTransferring) {
                    Text("扫应用", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "传输时保持移动数据在线",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = keepCellular, onCheckedChange = onToggleCellular)
            }
            Text(
                "传输走 Wi‑Fi 内网直连（已强绑定 Wi‑Fi 通道，0 流量）；" +
                        "本机其他 App 想上网，请再打开系统「WLAN 助理 / 数据加速」。",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val service = transferServiceGetter()
                        if (service == null) {
                            logs = appendLog(logs, "服务未就绪，请稍后再试")
                        } else {
                            isTransferring = true
                            service.sendTask(
                                targetIp = targetIp,
                                items = selectedItems.toList(),
                                onLog = { msg -> logs = appendLog(logs, msg) },
                                onProgress = { name, p, curr, total ->
                                    currentItemName = name
                                    singleProgress = p
                                    currentIndex = curr
                                    totalCount = total
                                },
                                onAllFinished = { success, message ->
                                    isTransferring = false
                                    if (success) {
                                        selectedItems.clear()
                                        logs = appendLog(logs, "全部项目已完成同步传输！")
                                    } else {
                                        logs = appendLog(logs, "传输中断：$message（接收端仍在监听，点「继续发送」即可断点续传）")
                                    }
                                }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = selectedItems.isNotEmpty() && !isScanning && !isTransferring
                ) {
                    val totalMB = selectedItems.sumOf { it.size } / 1024 / 1024
                    Text(if (currentIndex > 0) "继续发送 (${selectedItems.size}项)" else "开始传输 (${selectedItems.size}项 / 约${totalMB}MB)")
                }

                if (selectedItems.isNotEmpty() && !isTransferring) {
                    OutlinedButton(onClick = {
                        selectedItems.clear()
                        currentIndex = 0
                        totalCount = 0
                    }) {
                        Text("清空")
                    }
                }
            }

            if (totalCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(currentItemName, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("[$currentIndex/$totalCount]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = singleProgress, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("${(singleProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }

            if (appList.isNotEmpty()) {
                Text("已安装应用 (点击加入):", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(appList) { app ->
                        val isAdded = selectedItems.any { it.name == "${app.name}.apk" }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isAdded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onClick = {
                                if (isAdded) {
                                    selectedItems.removeAll { it.name == "${app.name}.apk" }
                                } else {
                                    selectedItems.add(
                                        TransferItem(
                                            name = "${app.name}.apk",
                                            size = app.size,
                                            realPath = app.apkFile.absolutePath
                                        )
                                    )
                                }
                            }
                        ) {
                            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(app.name, modifier = Modifier.weight(1f))
                                Text("${app.size / 1024 / 1024} MB")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text("传输日志：", style = MaterialTheme.typography.labelSmall)
        Card(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            LazyColumn(modifier = Modifier.padding(6.dp)) {
                item {
                    Text(logs, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
