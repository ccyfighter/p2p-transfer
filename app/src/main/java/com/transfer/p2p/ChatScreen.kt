package com.transfer.p2p

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val fromMe: Boolean,
    val time: Long = System.currentTimeMillis()
)

@Composable
fun ChatScreen(
    status: String,
    connected: Boolean,
    autoListen: Boolean,
    messages: List<ChatMessage>,
    targetIp: String,
    onTargetIpChange: (String) -> Unit,
    onListen: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSend: (String) -> Boolean,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 若已进入「接收端」角色（说明是开热点那台），进聊天室自动开始监听
    LaunchedEffect(Unit) { if (autoListen) onListen() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (connected) "● $status" else "○ $status",
            style = MaterialTheme.typography.labelMedium,
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = targetIp,
            onValueChange = onTargetIpChange,
            label = { Text("对端 IP（连热点机填 10.x / 192.168.x）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onListen, modifier = Modifier.weight(1f)) { Text("开始监听(热点机)") }
            Button(onClick = onConnect, modifier = Modifier.weight(1f)) { Text("连接(连热点机)") }
            OutlinedButton(onClick = onDisconnect) { Text("断开") }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = if (msg.fromMe) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.fromMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("p2p-chat", msg.text))
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.time)),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("输入消息") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                val text = try {
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                } catch (e: Exception) {
                    ""
                }
                if (text.isEmpty()) {
                    Toast.makeText(context, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
                } else {
                    input = text
                }
            }) { Text("粘贴") }

            Button(
                onClick = {
                    if (input.isBlank()) return@Button
                    if (onSend(input)) input = ""
                    else Toast.makeText(context, "未连接，先点【连接】或【开始监听】", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("发送") }

            OutlinedButton(onClick = onClear) { Text("清空") }
        }

        Text(
            "点任意消息气泡即可复制 · 剪贴板文字可一键粘贴后发送",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
