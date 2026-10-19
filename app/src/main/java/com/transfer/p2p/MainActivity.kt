package com.transfer.p2p

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.launch
import java.io.FileInputStream

class MainActivity : ComponentActivity() {

    private val engine = TransferEngine()
    private val selectedItems = mutableStateListOf<TransferItem>()

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val list = FileHelper.collectTreeFiles(this, it)
            selectedItems.addAll(list)
            Toast.makeText(this, "已收录文件夹内 ${list.size} 个散落文件", Toast.LENGTH_SHORT).show()
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
                        onOpenFolderPicker = { pickFolderLauncher.launch(null) },
                        onInstallApk = { FileHelper.installApk(this, it) },
                        onFetchApps = { FileHelper.getInstalledApps(this) },
                        onStartSend = { ip, onLog, onProgress ->
                            engine.sendItems(this, ip, selectedItems.toList(), onLog, onProgress)
                        },
                        engine = engine
                    )
                }
            }
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

@Composable
fun TransferAppScreen(
    selectedItems: MutableList<TransferItem>,
    onOpenFolderPicker: () -> Unit,
    onInstallApk: (java.io.File) -> Unit,
    onFetchApps: () -> List<AppItem>,
    onStartSend: suspend (ip: String, onLog: (String) -> Unit, onProgress: (String, Float) -> Unit) -> Unit,
    engine: TransferEngine
) {
    var mode by remember { mutableStateOf("HOME") }
    var logs by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    var currentProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    var showAppConfirmDialog by remember { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("192.168.43.1") }
    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }

    if (showAppConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showAppConfirmDialog = false },
            title = { Text("读取应用列表授权确认") },
            text = { Text("传输应用需要扫描本机已安装的所有非系统应用，并读取安装包进行传输。是否同意授权读取？") },
            confirmButton = {
                TextButton(onClick = {
                    showAppConfirmDialog = false
                    appList = onFetchApps()
                }) {
                    Text("同意授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("P2P 热点极速快传", style = MaterialTheme.typography.headlineMedium)
        Text("两台手机纯热点直连 · 不走公网 · 零流量消耗", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        if (mode == "HOME") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("请选择此设备角色：", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            mode = "RECEIVER"
                            scope.launch {
                                engine.startServer(
                                    onLog = { logs += "\n$it" },
                                    onProgress = { name, p ->
                                        progressText = "$name : ${(p * 100).toInt()}%"
                                        currentProgress = p
                                    },
                                    onCompleted = { file ->
                                        if (file.name.endsWith(".apk")) {
                                            onInstallApk(file)
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("设为【接收端】(K100 开热点)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            mode = "SENDER"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("设为【发送端】(K80 连热点)")
                    }
                }
            }
        }

        if (mode == "RECEIVER") {
            Text("接收端运行中...", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("请确保本机热点已开，文件将保存至 Downloads/P2PTransfer")
            if (progressText.isNotEmpty()) {
                LinearProgressIndicator(progress = currentProgress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(progressText)
            }
        }

        if (mode == "SENDER") {
            OutlinedTextField(
                value = targetIp,
                onValueChange = { targetIp = it },
                label = { Text("热点主机 IP (默认 192.168.43.1)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAppConfirmDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("扫描应用")
                }
                Button(onClick = onOpenFolderPicker, modifier = Modifier.weight(1f)) {
                    Text("添加文件夹")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        onStartSend(targetIp, { logs += "\n$it" }, { name, p ->
                            progressText = "$name : ${(p * 100).toInt()}%"
                            currentProgress = p
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("开始发送已选内容 (共 ${selectedItems.size} 个项目)")
            }

            if (appList.isNotEmpty()) {
                Text("点击应用加入发送队列:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(appList) { app ->
                        val isAdded = selectedItems.any { it.name == "${app.name}.apk" }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
        Text("传输日志：", style = MaterialTheme.typography.labelSmall)
        Card(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item {
                    Text(logs, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}