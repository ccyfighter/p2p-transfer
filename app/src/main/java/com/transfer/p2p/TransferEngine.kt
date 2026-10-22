package com.transfer.p2p

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

class TransferEngine {

    companion object {
        const val PORT = 9099
        private const val MAGIC = 0x50325033          // "P2P3"
        private const val VERSION = 3

        // ---- 吞吐调优 ----
        private const val CHUNK_MAX = 1024 * 1024           // 1MB 数据块
        private const val SOCK_BUF = 4 * 1024 * 1024        // 4MB TCP 窗口（必须在握手前设置）
        private const val HEAD_BUF = 64 * 1024              // 仅用于头部小字段
        private const val READ_BUF = 256 * 1024
        private const val READ_TIMEOUT_MS = 120_000

        private const val ENABLE_CRC = false                // 默认关：60GB 大批量时省 CPU；要校验改 true
        private const val PART_SUFFIX = ".p2ptmp"
    }

    // ==================== 接收端：常驻监听，永不自动退出 ====================
    suspend fun runServer(
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit,
        onCompleted: (File) -> Unit,
        onSessionEnd: (Boolean, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val holder = AtomicReference<ServerSocket?>(null)
        // accept() 是阻塞调用，取消协程时靠 close() 才能退出
        currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { holder.get()?.close() } }

        while (currentCoroutineContext().isActive) {
            var server: ServerSocket? = null
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.receiveBufferSize = SOCK_BUF      // ★ 在 bind 前设置，accept 出的 socket 会继承
                ss.bind(InetSocketAddress(PORT), 8)
                server = ss
                holder.set(ss)
                onLog("接收服务已就绪：端口 $PORT 持续监听中（传完不退出，可反复接收）")

                while (currentCoroutineContext().isActive && !ss.isClosed) {
                    val client: Socket = try {
                        ss.accept()
                    } catch (e: SocketException) {
                        if (!currentCoroutineContext().isActive) break
                        onLog("监听套接字异常：${e.message}，1 秒后重建")
                        break
                    }
                    try {
                        handleClient(client, onLog, onProgress, onCompleted, onSessionEnd)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        onLog("本次会话中断：$msg（监听仍在，可直接重连续传）")
                        onSessionEnd(false, msg)
                    } finally {
                        runCatching { client.close() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLog("监听启动失败：${e.message}，1 秒后自动重试…")
            } finally {
                holder.set(null)
                runCatching { server?.close() }
            }
            if (currentCoroutineContext().isActive) delay(1000)
        }
    }

    private suspend fun handleClient(
        client: Socket,
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit,
        onCompleted: (File) -> Unit,
        onSessionEnd: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        client.tcpNoDelay = true
        client.keepAlive = true
        client.receiveBufferSize = SOCK_BUF
        client.soTimeout = READ_TIMEOUT_MS

        val dis = DataInputStream(BufferedInputStream(client.getInputStream(), READ_BUF))
        val dos = DataOutputStream(BufferedOutputStream(client.getOutputStream(), HEAD_BUF))

        // 握手：先自报家门
        dos.writeInt(MAGIC)
        dos.writeInt(VERSION)
        dos.flush()

        val baseDir = FileHelper.getDownloadDir()
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            onLog("无法写入 ${baseDir.absolutePath}，请到系统设置授予「所有文件访问权限」")
            onSessionEnd(false, "存储权限不足")
            return@withContext
        }

        val totalCount = try { dis.readInt() } catch (e: EOFException) { 0 }
        if (totalCount <= 0) {
            onLog("对端没有待传项目")
            return@withContext
        }
        onLog("已连接 ${client.inetAddress?.hostAddress}，待接收 $totalCount 项")

        var currentIndex = 0
        var okCount = 0
        var skippedCount = 0
        val buffer = ByteArray(CHUNK_MAX)
        var lastProgressAt = 0L

        while (true) {
            val hasNext = try { dis.readBoolean() } catch (e: EOFException) { false }
            if (!hasNext) break

            currentIndex++
            val rawName = dis.readUTF()
            val declaredSize = dis.readLong()
            val relPath = sanitizeRelPath(rawName)

            if (relPath == null) {
                dos.writeByte(0); dos.writeLong(0L); dos.flush()
                onLog("跳过非法文件名：$rawName")
                continue
            }

            val target = File(baseDir, relPath)
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val part = File(target.absolutePath + PART_SUFFIX)

            // 秒传：已完整存在
            if (declaredSize > 0 && target.exists() && target.length() == declaredSize && !part.exists()) {
                dos.writeByte(0); dos.writeLong(declaredSize); dos.flush()
                skippedCount++
                onProgress(relPath, 1f, currentIndex, totalCount)
                continue
            }

            // 断点续传偏移
            var offset = 0L
            if (declaredSize > 0 && part.exists()) {
                offset = if (part.length() in 1 until declaredSize) part.length() else 0L
                if (offset == 0L) part.delete()
            }
            dos.writeByte(1); dos.writeLong(offset); dos.flush()

            var written = offset
            FileOutputStream(part, offset > 0L).use { fos ->
                while (true) {
                    val len = dis.readInt()             // 分块封包：长度 + 数据，0 = 本文件结束
                    if (len == 0) break
                    if (len < 0 || len > CHUNK_MAX) throw IOException("数据块长度异常($len)，协议错位")
                    dis.readFully(buffer, 0, len)
                    fos.write(buffer, 0, len)           // 1MB 大块直写，不做 fsync（速度优先）
                    written += len

                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= 60L || written == declaredSize) {
                        lastProgressAt = now
                        val p = if (declaredSize > 0) written.toFloat() / declaredSize else 0f
                        onProgress(relPath, if (p > 0.99f) 0.99f else p, currentIndex, totalCount)
                    }
                }
            }

            dis.readLong() // 兼容位：发送端 CRC
            if (declaredSize > 0 && written < declaredSize) {
                throw IOException("接收不完整：$written/$declaredSize（临时文件已保留，可续传）")
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }

            okCount++
            onProgress(relPath, 1f, currentIndex, totalCount)
            onLog("接收完成 [$currentIndex/$totalCount] ${formatSize(written)}：$relPath")
            onCompleted(target)
        }

        val summary = "本次接收完成：成功 $okCount/$totalCount" +
                if (skippedCount > 0) "（已存在跳过 $skippedCount 个）" else ""
        onLog(summary)
        onSessionEnd(true, summary)
    }

    // ==================== 发送端 ====================
    suspend fun sendItems(
        context: Context,
        serverIp: String,
        items: List<TransferItem>,
        onLog: (String) -> Unit,
        onProgress: (String, Float, Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val socket = connectWithRetry(context, serverIp, onLog)
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = READ_TIMEOUT_MS

            val rawOut = BufferedOutputStream(socket.getOutputStream(), HEAD_BUF)
            val dos = DataOutputStream(rawOut)
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream(), READ_BUF))

            val magic = dis.readInt()
            val version = dis.readInt()
            if (magic != MAGIC) {
                throw IOException("握手失败：对端 $serverIp:$PORT 不是本 App，请确认两端装的是同一版 APK")
            }
            onLog("握手成功（协议 v$version），开始推送 ${items.size} 个项目")

            dos.writeInt(items.size)
            dos.flush()

            // 帧缓冲：前 4 字节写长度，其后放数据 → 每块只发一次 syscall
            val frame = ByteArray(CHUNK_MAX + 4)
            var currentIndex = 0
            var lastProgressAt = 0L

            for (item in items) {
                currentIndex++
                dos.writeBoolean(true)
                dos.writeUTF(item.name)
                dos.writeLong(item.size)
                dos.flush()

                val status = dis.readByte().toInt()
                val resumeOffset = dis.readLong()

                if (status == 0) {   // 对端已完整存在，秒传
                    onProgress(item.name, 1f, currentIndex, items.size)
                    continue
                }

                val input = item.openStream(context)
                    ?: throw IOException("无法读取：${item.name}")

                input.use { fis ->
                    var skippedBytes = 0L
                    while (skippedBytes < resumeOffset) {
                        val s = fis.skip(resumeOffset - skippedBytes)
                        if (s <= 0L) break
                        skippedBytes += s
                    }
                    val crc = if (ENABLE_CRC && resumeOffset == 0L) CRC32() else null
                    var sent = skippedBytes
                    while (true) {
                        val n = fis.read(frame, 4, CHUNK_MAX)
                        if (n <= 0) break
                        frame[0] = (n ushr 24).toByte()
                        frame[1] = (n ushr 16).toByte()
                        frame[2] = (n ushr 8).toByte()
                        frame[3] = n.toByte()
                        rawOut.write(frame, 0, n + 4)     // ★ 长度与数据合并为一次写
                        crc?.update(frame, 4, n)
                        sent += n

                        val now = System.currentTimeMillis()
                        if (item.size > 0 && now - lastProgressAt >= 60L) {
                            lastProgressAt = now
                            val p = sent.toFloat() / item.size
                            onProgress(item.name, if (p > 0.99f) 0.99f else p, currentIndex, items.size)
                        }
                    }
                    rawOut.writeInt(0)     // 本文件结束标记
                    rawOut.flush()
                    dos.writeLong(crc?.value ?: 0L)
                    dos.flush()
                }
                onProgress(item.name, 1f, currentIndex, items.size)
            }

            dos.writeBoolean(false)
            dos.flush()
            onLog("全部 ${items.size} 个项目推送完成")
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun connectWithRetry(context: Context, ip: String, onLog: (String) -> Unit): Socket {
        val host = ip.trim()
            .removePrefix("p2p://")
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
        if (host.isEmpty()) throw IOException("目标 IP 为空，请填对端热点 IP 或扫码")

        var last: Exception? = null
        for (attempt in 1..3) {
            onLog("正在连接 $host:$PORT …（第 $attempt/3 次）")
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.receiveBufferSize = SOCK_BUF
                s.sendBufferSize = SOCK_BUF         // ★ 必须在 connect 前，才能参与 TCP 窗口协商
                val bound = NetworkBinder.bindSocketToWifi(context, s)
                onLog(if (bound) "传输通道已锁定到 Wi‑Fi（内网直连，0 流量）"
                else "未找到 Wi‑Fi 网络句柄，使用系统默认路由（内网地址同样不走蜂窝）")
                s.connect(InetSocketAddress(host, PORT), 6000)
                return s
            } catch (e: Exception) {
                last = e
                runCatching { s.close() }
                onLog("连接失败：${explain(e)}")
                if (attempt < 3) delay(1500)
            }
        }
        throw IOException("连不上接收端（$host:$PORT）：${explain(last ?: IOException())}", last)
    }

    private fun explain(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            e is ConnectException || m.contains("ECONNREFUSED", true) ->
                "对端 9099 端口没人在监听。请确认对端 App 已打开并停在「接收端」页面（被系统回收也会这样）"
            e is SocketTimeoutException || m.contains("timed out", true) ->
                "连接超时。请确认两台手机连在同一热点、IP 是否填对"
            e is NoRouteToHostException || m.contains("ENETUNREACH", true) ->
                "网络不可达。请先连接对端热点（注意别走移动数据）"
            e is UnknownHostException -> "地址无法解析，IP 格式不对"
            else -> m.ifEmpty { e.javaClass.simpleName }
        }
    }

    private fun sanitizeRelPath(raw: String): String? {
        val cleaned = raw.replace('\\', '/').trim().trimStart('/')
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2fGB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }
}
