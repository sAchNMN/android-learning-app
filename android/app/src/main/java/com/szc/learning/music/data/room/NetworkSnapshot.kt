package com.szc.learning.music.data.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 本机当前的网络快照。
 *
 * **刻意不读 WiFi BSSID。** 判断「是否同一局域网」最常见的做法是比 AP 的 MAC，
 * 但从 Android 10 起 BSSID 属于位置信息，要 `ACCESS_FINE_LOCATION` 加上用户打开定位
 * 才拿得到 —— 为了一个房间判定去要定位权限，用户第一反应是拒绝，功能直接废掉。
 *
 * 换成"网段 + 网关"：同一台路由器下发的设备，子网和网关必然相同；
 * 网关不同就一定不在一层。这个判断会**误判为同一网络**的唯一情况是
 * 两个 AP 桥接在同一网段 —— 而那种情况下它们本来就是二层可达的，判定为局域网反而是对的。
 *
 * 初筛之后仍然要 [LanTransport] 做广播探测实证，因为 AP 隔离（客户端隔离）
 * 会让"同网段"的两台设备互相 ping 不通。
 */
data class NetworkSnapshot(
    val wifi: Boolean = false,
    val ip: String? = null,
    val prefixLength: Int = 0,
    val gateway: String? = null,
    /** 形如 `192.168.1.0/24`，用于判断两台设备是否同网。 */
    val subnetKey: String? = null,
    /** 子网定向广播地址，形如 `192.168.1.255`。 */
    val broadcast: String? = null,
) {
    /** 只有 WiFi/有线才可能做局域网直连；蜂窝网络下广播包发不出去。 */
    val usableForLan: Boolean
        get() = wifi && ip != null && prefixLength in 8..30 && broadcast != null

    companion object {

        fun capture(context: Context): NetworkSnapshot = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching NetworkSnapshot()
            val network = manager.activeNetwork ?: return@runCatching NetworkSnapshot()
            val capabilities = manager.getNetworkCapabilities(network)
                ?: return@runCatching NetworkSnapshot()
            // 有线也算：电视盒子和部分车机走 ethernet，同样能广播
            val wired = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val link = manager.getLinkProperties(network) ?: return@runCatching NetworkSnapshot()
            parse(link, wired)
        }.getOrDefault(NetworkSnapshot())

        private fun parse(link: LinkProperties, wired: Boolean): NetworkSnapshot {
            val linkAddress = link.linkAddresses
                .firstOrNull { it.address is Inet4Address }
                ?: return NetworkSnapshot(wifi = wired)
            val address = linkAddress.address as Inet4Address
            val prefix = linkAddress.prefixLength.coerceIn(0, 32)
            val gateway = link.routes
                .firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.takeIf { it is Inet4Address }
                ?.hostAddress

            val ipValue = toLong(address)
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val networkValue = ipValue and mask
            val broadcastValue = networkValue or (mask.inv() and 0xFFFFFFFFL)

            return NetworkSnapshot(
                wifi = wired,
                ip = address.hostAddress,
                prefixLength = prefix,
                gateway = gateway,
                subnetKey = "${fromLong(networkValue)}/$prefix",
                broadcast = fromLong(broadcastValue),
            )
        }

        /** 两台设备网段与网关都一致，才进入探测环节。 */
        fun maybeSameLan(a: NetworkSnapshot, b: NetworkSnapshot): Boolean =
            a.usableForLan && b.usableForLan &&
                a.subnetKey != null && a.subnetKey == b.subnetKey &&
                a.gateway != null && a.gateway == b.gateway

        private fun toLong(address: InetAddress): Long =
            address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }

        private fun fromLong(value: Long): String =
            "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}
