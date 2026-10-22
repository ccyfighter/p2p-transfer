package com.transfer.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Socket

/**
 * 网络通道治理：
 *  1) 传输 socket 强制绑定到 Wi-Fi（内网直连绝不会走移动数据，0 流量）
 *  2) 可选地把蜂窝网络拉起来保持在线，让本机依然能上网
 */
object NetworkBinder {

    @Volatile
    private var cellularNetwork: Network? = null

    @Volatile
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    private fun cm(context: Context): ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /** 找到当前的 Wi-Fi 网络（热点客户端模式下就是连着热点的那个 Wi-Fi） */
    fun findWifiNetwork(context: Context): Network? {
        val manager = cm(context) ?: return null
        manager.allNetworks.firstOrNull { n ->
            manager.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }?.let { return it }
        val active = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(active) ?: return null
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) active else null
    }

    /**
     * 把 socket 绑定到 Wi-Fi。必须在 socket.connect() 之前调用，否则无效。
     * 绑定失败时返回 false，调用方直接走系统默认路由即可（内网地址同样不会走蜂窝）。
     */
    fun bindSocketToWifi(context: Context, socket: Socket): Boolean {
        return try {
            val network = findWifiNetwork(context) ?: return false
            network.bindSocket(socket)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 请求并保持蜂窝网络在线（本 App 内的 socket 可 bind 到它；同时防止系统休眠蜂窝射频） */
    fun requestCellular(
        context: Context,
        onAvailable: (Network) -> Unit = {},
        onLost: () -> Unit = {}
    ) {
        releaseCellular(context)
        val manager = cm(context) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                onAvailable(network)
            }

            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
                onLost()
            }
        }
        cellularCallback = callback
        runCatching { manager.requestNetwork(request, callback) }
    }

    fun releaseCellular(context: Context) {
        val manager = cm(context) ?: return
        cellularCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        cellularCallback = null
        cellularNetwork = null
    }

    fun isCellularHeld(): Boolean = cellularNetwork != null

    /** 让本 App 内需要外网的连接走蜂窝（未来扩展用） */
    fun bindSocketToCellular(socket: Socket): Boolean {
        return try {
            cellularNetwork?.bindSocket(socket) != null
        } catch (e: Exception) {
            false
        }
    }
}
