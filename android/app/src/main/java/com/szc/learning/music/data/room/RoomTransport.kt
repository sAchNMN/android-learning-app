package com.szc.learning.music.data.room

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 收到的一条消息 + 它的网络来源。局域网下 [sourceIp] 用来补全对端地址表。 */
data class Incoming(val message: RoomMessage, val sourceIp: String? = null)

/**
 * 房间传输通道。
 *
 * 两种实现语义一致，上层不用关心当前走的是哪条路：
 * - [LanTransport]：局域网 UDP 单播，没有中转，端到端延迟个位数毫秒
 * - [WanTransport]：后端 WebSocket 中转，延迟取决于用户到服务器的往返
 *
 * 约定：[broadcast] 的语义是"发给房间里的其他人"。
 * 局域网下房主对每个已知对端单播，成员则发给房主 —— 房间里没有星形以外的拓扑，
 * 成员之间互发既没必要（状态只有房主是权威）也会让成员数平方级放大流量。
 */
interface RoomTransport {

    val mode: SyncMode

    val incoming: SharedFlow<Incoming>

    /** 通道是否还活着。局域网看 socket，公网看 WebSocket 连接状态。 */
    val alive: StateFlow<Boolean>

    suspend fun broadcast(message: RoomMessage)

    /**
     * 强制发给"整个通道"：局域网下是无视对端表的子网广播，公网等同 [broadcast]。
     *
     * 探测必须用它 —— 那时对端表还是空的，走 [broadcast] 会一个包都发不出去，
     * 表现就是"永远探测不到人在线"。
     */
    suspend fun shout(message: RoomMessage)

    suspend fun sendTo(deviceId: String, message: RoomMessage)

    /** 已确证的局域网对端：deviceId → ip。公网通道下恒为空。 */
    fun lanPeers(): Map<String, String>

    fun close()
}
