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
        private const val BUFFER_SIZE = 256 * 1024 // 扩大缓冲区到 256KB 提升 UFS 闪存爆发读写吞吐
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
            onLog("接收服务就绪，端口: $PORT")

            while (!serverSocket.isClosed) {
                val client = serverSocket.accept()
                client.tcpNoDelay = true // 关闭 Nagle 算法，消除微小网络包延迟
                onLog("已建立传输通道: ${client.inetAddress.hostAddress}")

                val dis = DataInputStream(BufferedInputStream(client.getInputStream(), BUFFER_SIZE))
                val baseDir = FileHelper.getDownloadDir()

                val totalCount = dis.readInt()
                var currentIndex = 0
                onLog("待接收总数: $totalCount 个项目")

                while (true) {
                    val hasNext = try { dis.readBoolean() } catch (e: EOFException) { false }
                    if (!hasNext) break

                    currentIndex++
                    val relPath = dis.readUTF()
                    val fileSize = dis.readLong()

                    val targetFile = File(baseDir, relPath)
                    targetFile.parentFile?.mkdirs()

                    val localCrc = CRC32()
                    FileOutputStream(targetFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRemaining = fileSize
                        var totalRead = 0L

                        while (bytesRemaining > 0) {
                            val toRead = Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = dis.read(buffer, 0, toRead)
                            if (read == -1) throw IOException("传输中途对端断开")
                            fos.write(buffer, 0, read)
                            localCrc.update(buffer, 0, read)
                            bytesRemaining -= read
                            totalRead += read

                            val p = if (fileSize > 0) totalRead.toFloat() / fileSize else 1f
                            onProgress(relPath, p, currentIndex, totalCount)
                        }
                        fos.flush()
                    }

                    // 读入对端边传边算追加在末尾的真实 CRC32
                    val remoteCrc = dis.readLong()

                    if (localCrc.value == remoteCrc) {
                        onLog("校验一致 [$currentIndex/$totalCount]: $relPath")
                        onCompleted(targetFile)
                    } else {
                        onLog("⚠️ 校验失败（数据受损）: $relPath")
                    }
                }
                client.close()
                onLog("本次所有任务处理完毕！")
                break // 完成后结束此轮监听
            }
        } catch (e: Exception) {
            onLog("传输服务已关闭/中断: ${e.message}")
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
            onLog("正在连接目标: $serverIp:$PORT ...")
            val socket = Socket(serverIp, PORT)
            socket.tcpNoDelay = true
            val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))

            val totalCount = items.size
            dos.writeInt(totalCount)
            dos.flush()

            var currentIndex = 0
            val buffer = ByteArray(BUFFER_SIZE)

            for (item in items) {
                currentIndex++
                dos.writeBoolean(true)
                dos.writeUTF(item.name)
                dos.writeLong(item.size)

                val localCrc = CRC32()
                val input = item.openStream(context)
                if (input != null) {
                    input.use { fis ->
                        var bytesRead: Int
                        var totalSent = 0L
                        val totalSize = item.size

                        // 关键：单次顺序读入，同时送入网络与 CRC32，绝不二次读盘
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            dos.write(buffer, 0, bytesRead)
                            localCrc.update(buffer, 0, bytesRead)
                            totalSent += bytesRead
                            if (totalSize > 0) {
                                onProgress(item.name, totalSent.toFloat() / totalSize, currentIndex, totalCount)
                            }
                        }
                        dos.flush()
                    }
                }
                // 在文件尾部追加写出 CRC32
                dos.writeLong(localCrc.value)
                dos.flush()
                onLog("已送达 [$currentIndex/$totalCount]: ${item.name}")
            }
            dos.writeBoolean(false) // 结束标识
            dos.flush()
            socket.close()
            onLog("全部 $totalCount 个项目推送完成！")
        } catch (e: Exception) {
            onLog("发送中断: ${e.message}")
            throw e
        }
    }
}
