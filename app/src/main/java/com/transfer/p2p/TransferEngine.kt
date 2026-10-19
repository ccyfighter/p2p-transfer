package com.transfer.p2p

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class TransferEngine {

    companion object {
        const val PORT = 9099
        private const val BUFFER_SIZE = 128 * 1024 // 128KB 高吞吐缓冲
    }

    // 服务端：接收文件流并根据相对路径落盘
    suspend fun startServer(
        onLog: (String) -> Unit,
        onProgress: (fileName: String, progress: Float) -> Unit,
        onCompleted: (File) -> Unit
    ) = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(PORT)
            onLog("接收端服务就绪，端口: $PORT。请确保本机已开启热点。")

            while (!serverSocket.isClosed) {
                val client = serverSocket.accept()
                onLog("已与发送端建立连接: ${client.inetAddress.hostAddress}")

                val dis = DataInputStream(BufferedInputStream(client.getInputStream()))
                val baseDir = FileHelper.getDownloadDir()

                while (true) {
                    val hasNext = try { dis.readBoolean() } catch (e: EOFException) { false }
                    if (!hasNext) break

                    val relPath = dis.readUTF()
                    val fileSize = dis.readLong()
                    onLog("正在接收: $relPath (${fileSize / 1024 / 1024} MB)")

                    val targetFile = File(baseDir, relPath)
                    targetFile.parentFile?.mkdirs()

                    FileOutputStream(targetFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRemaining = fileSize
                        var totalRead = 0L

                        while (bytesRemaining > 0) {
                            val toRead = Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = dis.read(buffer, 0, toRead)
                            if (read == -1) throw IOException("传输被对端异常中断")
                            fos.write(buffer, 0, read)
                            bytesRemaining -= read
                            totalRead += read
                            onProgress(relPath, totalRead.toFloat() / fileSize)
                        }
                        fos.flush()
                    }
                    onLog("已接收保存: ${targetFile.name}")
                    onCompleted(targetFile)
                }
                client.close()
                onLog("批次任务全部接收完毕！")
            }
        } catch (e: Exception) {
            onLog("通信异常: ${e.message}")
        } finally {
            serverSocket?.close()
        }
    }

    // 客户端：批量发送文件与应用 APK
    suspend fun sendItems(
        context: Context,
        serverIp: String,
        items: List<TransferItem>,
        onLog: (String) -> Unit,
        onProgress: (fileName: String, progress: Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onLog("正在连接热点网关: $serverIp:$PORT ...")
            val socket = Socket(serverIp, PORT)
            val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            for (item in items) {
                onLog("准备推送: ${item.name} ...")
                dos.writeBoolean(true) // 标识继续发送
                dos.writeUTF(item.name)
                dos.writeLong(item.size)
                dos.flush()

                val input = item.openStream(context) ?: continue
                input.use { fis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalSent = 0L
                    val totalSize = item.size

                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        dos.write(buffer, 0, bytesRead)
                        totalSent += bytesRead
                        if (totalSize > 0) {
                            onProgress(item.name, totalSent.toFloat() / totalSize)
                        }
                    }
                    dos.flush()
                }
                onLog("已成功推送: ${item.name}")
            }
            dos.writeBoolean(false) // 结束信号
            dos.flush()
            socket.close()
            onLog("全部文件传输完成！")
        } catch (e: Exception) {
            onLog("发送中断: ${e.message}")
        }
    }
}