package com.transfer.p2p

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val engine = TransferEngine()
    val selectedItems = mutableStateListOf<TransferItem>()
    private var scannedIpCallback: ((String) -> Unit)? = null

    // 单个/多选文件选择器（使用严谨的大小解析逻辑）
    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris?.forEach { uri ->
            val details = FileHelper.getUriDetails(this, uri)
            selectedItems.add(
                TransferItem(
                    name = details.first,
                    size = details.second,
                    openStream = { ctx -> ctx.contentResolver.openInputStream(uri) }
                )
            )
        }
        Toast.makeText(this, "已添加 ${uris.size} 个文件", Toast.LENGTH_SHORT).show()
    }

    // 文件夹选择器
    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val list = FileHelper.collectTreeFiles(this, it)
            selectedItems.addAll(list)
            Toast.makeText(this, "已展开添加 ${list.size} 个散落文件", Toast.LENGTH_SHORT).show()
        }
    }

    // 二维码扫码
    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val raw = result.contents
            val ip = if (raw.startsWith("p2p://")) raw.removePrefix("p2p://") else raw
            scannedIpCallback?.invoke(ip)
            Toast.makeText(this, "成功配对 IP: $ip", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAllFilesAccessPermission()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TransferAppScreen(
                        selectedItems = selectedItems,
                        onPickSingleFiles = { pickFilesLauncher.launch(arrayOf("*/*")) },
                        onOpenFolderPicker = { pickFolderLauncher.launch(null) },
                        onScanQr = { callback ->
                            scannedIpCallback = callback
                            val options = ScanOptions().apply {
                                setPrompt("请扫描接收端屏幕上的二维码")
                                setBeepEnabled(true)
                                setOrientationLocked(true)
                            }
                            scanQrLauncher.launch(options)
                        },
                        onInstallApk = { FileHelper.installApk(this, it) },
                        onNotifyFileSaved = { file -> FileHelper.notifyMediaScanner(this, file) },
                        onOpenDownloadFolder = { openDownloadFolder() },
                        onFetchApps = { FileHelper.getInstalledApps(this) },
                        onStartSend = { ip, onLog, onProgress, onFinish ->
                            engine.sendItems(this, ip, selectedItems.toList(), onLog, onProgress)
                            onFinish()
                        },
                        engine = engine
                    )
                }
            }
        }
    }

    // 调起系统文件管理器直达保存目录
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
            Toast.makeText(this, "文件保存在: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }
}

fun getLocalHotspotIp(): String {
    try {
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
    } catch (_: Exception) {}
    return "192.168.43.1"
}

fun generateQrBitmap(content: String): Bitmap? {
    return try {
        val size = 512
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

@Composable
fun TransferAppScreen(
    selectedItems: MutableList<TransferItem>,
    onPickSingleFiles: () -> Unit,
    onOpenFolderPicker: () -> Unit,
    onScanQr: ((String) -> Unit) -> Unit,
    onInstallApk: (File) -> Unit,
    onNotifyFileSaved: (File) -> Unit,
    onOpenDownloadFolder: () -> Unit,
    onFetchApps: () -> List<AppItem>,
    onStartSend: suspend (ip: String, onLog: (String) -> Unit, onProgress: (String, Float) -> Unit, onFinish: () -> Unit) -> Unit,
    engine: TransferEngine
) {
    var mode by remember { mutableStateOf("HOME") }
    var logs by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    var currentProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    var showAppConfirmDialog by remember { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("192.168.43.1") }
    var serverLocalIp by remember { mutableStateOf("") }
    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }

    if (showAppConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showAppConfirmDialog = false },
            title = { Text("授权确认") },
            text = { Text("读取应用列表需要扫描本机已安装非系统应用并提取 base.apk，是否允许？") },
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
        Text("P2P 热点极速快传", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(12.dp))

        if (mode == "HOME") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("请选择此设备角色：", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            mode = "RECEIVER"
                            serverLocalIp = getLocalHotspotIp()
                            scope.launch {
                                engine.startServer(
                                    onLog = { logs += "\n$it" },
                                    onProgress = { name, p ->
                                        progressText = "$name : ${(p * 100).toInt()}%"
                                        currentProgress = p
                                    },
                                    onCompleted = { file ->
                                        // 关键：广播通知系统媒体库刷新相册
                                        onNotifyFileSaved(file)
                                        if (file.name.endsWith(".apk")) {
                                            onInstallApk(file)
                                        }
                                    }
                                )
                            }
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
                }
            }
        }

        if (mode == "RECEIVER") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本机热点真实 IP: $serverLocalIp", style = MaterialTheme.typography.titleMedium)
                    Text("保存位置: 内部存储/Download/P2PTransfer", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                    val qr = remember(serverLocalIp) { generateQrBitmap("p2p://$serverLocalIp") }
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(180.dp).padding(vertical = 8.dp)
                        )
                    }

                    Button(
                        onClick = onOpenDownloadFolder,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text("打开接收文件夹")
                    }

                    if (progressText.isNotEmpty()) {
                        LinearProgressIndicator(progress = currentProgress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text(progressText, modifier = Modifier.padding(top = 4.dp))
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
                Button(onClick = {
                    onScanQr { scanned -> targetIp = scanned }
                }) {
                    Text("扫码配对")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPickSingleFiles, modifier = Modifier.weight(1f)) {
                    Text("选文件", style = MaterialTheme.typography.labelMedium)
                }
                Button(onClick = onOpenFolderPicker, modifier = Modifier.weight(1f)) {
                    Text("选文件夹", style = MaterialTheme.typography.labelMedium)
                }
                Button(onClick = { showAppConfirmDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("扫应用", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            onStartSend(targetIp, { logs += "\n$it" }, { name, p ->
                                progressText = "$name : ${(p * 100).toInt()}%"
                                currentProgress = p
                            }, {
                                // 关键：传输完成后清空选中列表
                                selectedItems.clear()
                            })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("开始传输 (${selectedItems.size}项)")
                }

                if (selectedItems.isNotEmpty()) {
                    OutlinedButton(onClick = { selectedItems.clear() }) {
                        Text("清空")
                    }
                }
            }

            if (appList.isNotEmpty()) {
                Text("已安装应用 (点击加入待传列表):", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
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
                                            openStream = { FileInputStream(app.apkFile) }
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

        Spacer(modifier = Modifier.height(8.dp))
        Text("传输状态日志：", style = MaterialTheme.typography.labelSmall)
        Card(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            LazyColumn(modifier = Modifier.padding(6.dp)) {
                item {
                    Text(logs, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
