package com.transfer.p2p

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

class ChatEngine {

    companion object {
        const val CHAT_PORT = 9098
        private const val MAGIC = 0x43484154   // "CHAT"
        private const val MAX_TEXT = 1024 * 1024
    }

    interface Listener {
        fun onState(connected: Boolean, info: String)
        fun onMessage(text: String)
        fun onError(msg: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverRef = AtomicReference<ServerSocket?>(null)
    private val socketRef = AtomicReference<Socket?>(null)
    private val writeLock = Any()

    @Volatile private var out: DataOutputStream? = null
    @Volatile private var job: Job? = null

    fun isActive(): Boolean = job?.isActive == true || socketRef.get() != null

    /** 开热点的一台：常驻监听，对方离开后自动回到等待 */
    fun startServer(listener: Listener) {
        stop()
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                var ss: ServerSocket? = null
                try {
                    val server = ServerSocket()
                    ss = server
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(CHAT_PORT), 4)
                    serverRef.set(server)
                    listener.onState(false, "聊天室监听中 :$CHAT_PORT（等待对方加入）")
                    while (currentCoroutineContext().isActive && !server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: SocketException) {
                            break
                        }
                        listener.onState(true, "对方已加入聊天室")
                        serve(client, listener)
                        listener.onState(false, "对方已离开，继续等待")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listener.onError("监听失败：${e.message}")
                } finally {
                    serverRef.set(null)
                    runCatching { ss?.close() }
                    closeSocket()
                }
                if (currentCoroutineContext().isActive) delay(800)
            }
        }
    }

    /** 连热点的一台：主动连接（同样绑定 Wi-Fi，0 流量） */
    fun connect(ip: String, context: Context, listener: Listener) {
        stop()
        val host = ip.trim().removePrefix("p2p://").substringBefore("/").substringBefore(":")
        job = scope.launch {
            var socket: Socket? = null
            try {
                listener.onState(false, "正在连接聊天室 $host:$CHAT_PORT …")
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                NetworkBinder.bindSocketToWifi(context, s)
                s.connect(InetSocketAddress(host, CHAT_PORT), 6000)
                listener.onState(true, "已连接 $host:$CHAT_PORT")
                serve(s, listener)
                listener.onState(false, "连接已断开，可重新连接")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onError("连接失败：${e.message}")
                listener.onState(false, "未连接")
            } finally {
                runCatching { socket?.close() }
                closeSocket()
            }
        }
    }

    /** 单条连接全双工：一边循环读，写由 send() 加锁串行 */
    private suspend fun serve(socket: Socket, listener: Listener) {
        socket.tcpNoDelay = true
        socketRef.set(socket)
        val dis = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        out = dos
        try {
            dos.writeInt(MAGIC)
            dos.flush()
            val magic = dis.readInt()
            if (magic != MAGIC) throw IOException("对端不是本 App 的聊天室")
            while (currentCoroutineContext().isActive) {
                val len = dis.readInt()
                if (len <= 0 || len > MAX_TEXT) throw IOException("消息长度异常：$len")
                val buf = ByteArray(len)
                dis.readFully(buf)
                val text = String(buf, Charsets.UTF_8)
                currentCoroutineContext().ensureActive()
                listener.onMessage(text)
            }
        } catch (e: EOFException) {
            // 对端正常关闭
        } finally {
            closeSocket()
        }
    }

    fun send(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_TEXT) return false
        return try {
            synchronized(writeLock) {
                val o = out
                if (o == null) false else {
                    o.writeInt(bytes.size)
                    o.write(bytes)
                    o.flush()
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { serverRef.getAndSet(null)?.close() }
        closeSocket()
    }

    private fun closeSocket() {
        out = null
        socketRef.getAndSet(null)?.let { runCatching { it.close() } }
    }
}
