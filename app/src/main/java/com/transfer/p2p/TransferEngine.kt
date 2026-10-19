package com.transfer.p2p

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.CRC32

class TransferEngine {

    companion object {
        const val PORT = 9099
        private const val BUFFER_SIZE = 128 * 1024 // 128KB 缓冲区
    }

    // 接收服务端
    suspend fun startServer(
        onLog: (String) -> Unit,
        onProgress: (fileName: String, fileProgress: Float, current: Int, total: Int) -> Unit,
        onCompleted: (File) -> Unit
    ) = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(PORT)
            onLog("接收端服务就绪，端口: $PORT")

            while (!serverSocket.isClosed) {
                val client = serverSocket.accept()
                onLog("已建立连接: ${client.inetAddress.hostAddress}")

                val dis = DataInputStream(BufferedInputStream(client.getInputStream()))
                val baseDir = FileHelper.getDownloadDir()

                // 先读取本批次总任务数
                val totalCount = dis.readInt()
                var currentIndex = 0
                onLog("本批次待接收任务总数: $totalCount 个")

                while (true) {
                    val hasNext = try { dis.readBoolean() } catch (e: EOFException) { false }
                    if (!hasNext) break

                    currentIndex++
                    val relPath = dis.readUTF()
                    val fileSize = dis.readLong()
                    val expectedCrc = dis.readLong() // 接收端预期的 CRC32

                    onLog("正在接收 [$currentIndex/$totalCount]: $relPath (${fileSize / 1024 / 1024} MB)")

                    val targetFile = File(baseDir, relPath)
                    targetFile.parentFile?.mkdirs()

                    val crc = CRC32()
                    FileOutputStream(targetFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRemaining = fileSize
                        var totalRead = 0L

                        while (bytesRemaining > 0) {
                            val toRead = Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = dis.read(buffer, 0, toRead)
                            if (read == -1) throw IOException("传输中断")
                            fos.write(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            bytesRemaining -= read
                            totalRead += read

                            val p = if (fileSize > 0) totalRead.toFloat() / fileSize else 1f
                            onProgress(relPath, p, currentIndex, totalCount)
                        }
                        fos.flush()
                    }

                    // CRC32 一致性校验
                    if (crc.value == expectedCrc) {
                        onLog("校验通过: $relPath")
                        onCompleted(targetFile)
                    } else {
                        onLog("⚠️ CRC 校验失败（数据损坏）: $relPath")
                    }
                }
                client.close()
                onLog("批次任务接收完毕！")
            }
        } catch (e: Exception) {
            onLog("通信异常: ${e.message}")
        } finally {
            serverSocket?.close()
        }
    }

    // 发送客户端
    suspend fun sendItems(
        context: Context,
        serverIp: String,
        items: List<TransferItem>,
        onLog: (String) -> Unit,
        onProgress: (fileName: String, fileProgress: Float, current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onLog("正在连接热点网关: $serverIp:$PORT ...")
            val socket = Socket(serverIp, PORT)
            val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val totalCount = items.size
            dos.writeInt(totalCount) // 发送总文件数
            dos.flush()

            var currentIndex = 0
            for (item in items) {
                currentIndex++
                onLog("准备推送 [$currentIndex/$totalCount]: ${item.name} ...")

                // 计算 CRC32
                val crc = CRC32()
                val tempBuffer = ByteArray(BUFFER_SIZE)
                var crcInput = item.openStream(context)
                crcInput?.use { input ->
                    var r: Int
                    while (input.read(tempBuffer).also { r = it } != -1) {
                        crc.update(tempBuffer, 0, r)
                    }
                }

                dos.writeBoolean(true)
                dos.writeUTF(item.name)
                dos.writeLong(item.size)
                dos.writeLong(crc.value) // 发送 CRC32
                dos.flush()

                val input = item.openStream(context) ?: continue
                input.use { fis ->
                    var bytesRead: Int
                    var totalSent = 0L
                    val totalSize = item.size

                    while (fis.read(tempBuffer).also { bytesRead = it } != -1) {
                        dos.write(tempBuffer, 0, bytesRead)
                        totalSent += bytesRead
                        if (totalSize > 0) {
                            onProgress(item.name, totalSent.toFloat() / totalSize, currentIndex, totalCount)
                        }
                    }
                    dos.flush()
                }
                onLog("已推送完成: ${item.name}")
            }
            dos.writeBoolean(false) // 结束信号
            dos.flush()
            socket.close()
            onLog("全部 $totalCount 个项目推送完成！")
        } catch (e: Exception) {
            onLog("发送中断: ${e.message}")
        }
    }
}
