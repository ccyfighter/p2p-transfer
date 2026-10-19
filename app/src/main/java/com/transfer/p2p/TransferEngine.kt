package com.transfer.p2p

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.CRC32

class TransferEngine {

    companion object {
        const val PORT = 9099
        private const val BUFFER_SIZE = 512 * 1024 // 512KB 缓冲区，提升高频读写吞吐
        private const val SO_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB 底层 Socket 缓冲区
    }

    suspend fun startServer(
        onLog: (String) -> Unit,
        onProgress: (fileName: String, fileProgress: Float, current: Int, total: Int) -> Unit,
        onCompleted: (File) -> Unit
    ) = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            onLog("接收服务就绪，端口: $PORT (已开启断点续传)")

            while (!serverSocket.isClosed) {
                val client = serverSocket.accept()
                client.tcpNoDelay = true
                client.keepAlive = true
                client.receiveBufferSize = SO_BUFFER_SIZE

                onLog("已建立传输通道: ${client.inetAddress.hostAddress}")

                val dis = DataInputStream(BufferedInputStream(client.getInputStream(), BUFFER_SIZE))
                val dos = DataOutputStream(BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE))
                val baseDir = FileHelper.getDownloadDir()

                val totalCount = dis.readInt()
                var currentIndex = 0
                onLog("待同步项目总数: $totalCount 个")

                while (true) {
                    val hasNext = try { dis.readBoolean() } catch (e: EOFException) { false }
                    if (!hasNext) break

                    currentIndex++
                    val relPath = dis.readUTF()
                    val fileSize = dis.readLong()

                    val targetFile = File(baseDir, relPath)
                    targetFile.parentFile?.mkdirs()

                    val localLength = if (targetFile.exists()) targetFile.length() else 0L

                    // 1. 文件已完整存在，通知发送端直接跳过
                    if (localLength == fileSize && fileSize > 0L) {
                        dos.writeByte(0) // 0 表示已完整存在，无需传输
                        dos.writeLong(fileSize)
                        dos.flush()
                        onProgress(relPath, 1f, currentIndex, totalCount)
                        onCompleted(targetFile)
                        continue
                    }

                    // 2. 文件不存在或未完成，支持从已接收字节处断点续传
                    val resumeOffset = if (localLength < fileSize) localLength else 0L
                    dos.writeByte(1) // 1 表示需要接收数据
                    dos.writeLong(resumeOffset)
                    dos.flush()

                    val isAppend = resumeOffset > 0L
                    FileOutputStream(targetFile, isAppend).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRemaining = fileSize - resumeOffset
                        var totalRead = resumeOffset

                        while (bytesRemaining > 0) {
                            val toRead = Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = dis.read(buffer, 0, toRead)
                            if (read == -1) throw IOException("传输中途对端断开")
                            fos.write(buffer, 0, read)
                            bytesRemaining -= read
                            totalRead += read

                            val p = if (fileSize > 0) totalRead.toFloat() / fileSize else 1f
                            onProgress(relPath, p, currentIndex, totalCount)
                        }
                        fos.flush()
                    }

                    val remoteCrc = dis.readLong()
                    // 仅当整文件完整重传时作严格校验，追加续传以物理大小校验为准
                    if (resumeOffset == 0L && remoteCrc != 0L) {
                        onLog("接收完毕 [$currentIndex/$totalCount]: $relPath")
                    } else {
                        onLog("续传完成 [$currentIndex/$totalCount]: $relPath")
                    }
                    onCompleted(targetFile)
                }
                client.close()
                onLog("本次所有任务处理完毕！")
                break
            }
        } catch (e: Exception) {
            onLog("传输服务异常中断: ${e.message}")
        } finally {
            serverSocket?.close()
        }
    }

    suspend fun sendItems(
        context: Context,
        serverIp: String,
        items: List<TransferItem>,
        onLog: (String) -> Unit,
        onProgress: (fileName: String, fileProgress: Float, current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onLog("正在连接目标: $serverIp:$PORT ...")
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = SO_BUFFER_SIZE
            socket.connect(InetSocketAddress(serverIp, PORT), 10000)

            val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))

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
                dos.flush()

                // 读取接收端状态：0 为秒传跳过，1 为需要发送（或断点续传）
                val status = dis.readByte()
                val resumeOffset = dis.readLong()

                if (status.toInt() == 0) {
                    onProgress(item.name, 1f, currentIndex, totalCount)
                    continue
                }

                val localCrc = CRC32()
                val input = item.openStream(context)
                if (input != null) {
                    input.use { fis ->
                        // 如果需要续传，跳过已有字节
                        if (resumeOffset > 0) {
                            var skippedTotal = 0L
                            while (skippedTotal < resumeOffset) {
                                val s = fis.skip(resumeOffset - skippedTotal)
                                if (s <= 0) break
                                skippedTotal += s
                            }
                        }

                        var bytesRead: Int
                        var totalSent = resumeOffset
                        val totalSize = item.size

                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            dos.write(buffer, 0, bytesRead)
                            if (resumeOffset == 0L) {
                                localCrc.update(buffer, 0, bytesRead)
                            }
                            totalSent += bytesRead
                            if (totalSize > 0) {
                                onProgress(item.name, totalSent.toFloat() / totalSize, currentIndex, totalCount)
                            }
                        }
                        dos.flush()
                    }
                }
                // 若续传则 CRC 发送 0，完整传输才回传 CRC
                val finalCrc = if (resumeOffset == 0L) localCrc.value else 0L
                dos.writeLong(finalCrc)
                dos.flush()
            }
            dos.writeBoolean(false)
            dos.flush()
            socket.close()
            onLog("全部 $totalCount 个项目推送完成！")
        } catch (e: Exception) {
            onLog("发送中断: ${e.message}")
            throw e
        }
    }
}
