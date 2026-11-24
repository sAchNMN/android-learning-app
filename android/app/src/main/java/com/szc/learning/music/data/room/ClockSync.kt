package com.szc.learning.music.data.room

import android.os.SystemClock

/**
 * 与房主时钟的对齐。
 *
 * 两台设备的 `elapsedRealtime()` 各算各的开机时长，直接比较毫无意义。
 * 要判断"你在第 42 秒时我在第几秒"，必须先估出两个时钟的固定差
 * （offset = 房主时钟 − 本地时钟）。
 *
 * 用 NTP 的四时戳法：
 * ```
 * 本地 t1 ──发出──▶ 房主 t2
 * 本地 t4 ◀──回包── 房主 t3
 * offset = ((t2 − t1) + (t3 − t4)) / 2
 * rtt    = (t4 − t1) − (t3 − t2)
 * ```
 *
 * **只取 RTT 最小的那个样本**，这是这套方法里最容易被忽略的一步：
 * 单向延迟无法单独测出来，只能假设去回对称。RTT 越大说明中间排队越多，
 * 对称假设越不成立；RTT 最小那次几乎没排队，估出来的 offset 才贴近真值。
 * 取平均反而会把排队的样本混进来，越平均越飘。
 */
class ClockSync {

    private data class Sample(val offset: Long, val rtt: Long)

    private val samples = ArrayDeque<Sample>()

    /** 房主时钟 − 本地时钟。未同步时为 0，此时 [hostNow] 等于本地时钟。 */
    @Volatile
    var offset: Long = 0L
        private set

    /** 目前观测到的最小往返，UI 上显示的"延迟"就是它的一半。 */
    @Volatile
    var bestRtt: Long = Long.MAX_VALUE
        private set

    val synced: Boolean get() = samples.isNotEmpty()

    /** 单程延迟估计。RTT 最小样本的一半，比"当前 RTT 的一半"稳得多。 */
    val oneWayMs: Long get() = if (bestRtt == Long.MAX_VALUE) -1 else bestRtt / 2

    fun onAck(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        // 负数说明房主那边时钟倒退或包乱序；超过 3 秒的样本没有参考价值
        if (rtt < 0 || rtt > MAX_RTT_MS) return
        val sampleOffset = ((t2 - t1) + (t3 - t4)) / 2
        samples.addLast(Sample(sampleOffset, rtt))
        while (samples.size > WINDOW) samples.removeFirst()
        val best = samples.minByOrNull { it.rtt } ?: return
        offset = best.offset
        bestRtt = best.rtt
    }

    /** 换算到房主时钟。 */
    fun hostNow(): Long = SystemClock.elapsedRealtime() + offset

    /** 把房主时钟上的时刻换算成本地时钟上的时刻。 */
    fun toLocal(hostMs: Long): Long = hostMs - offset

    /**
     * 网络切换（WiFi 换 4G、走回 LAN）之后旧样本全部作废。
     *
     * 不清的话，换网瞬间的 offset 还是旧链路算出来的，
     * 会被当成真实偏差去 seek，听起来就是"换网时音乐猛地跳一下"。
     */
    fun reset() {
        samples.clear()
        offset = 0L
        bestRtt = Long.MAX_VALUE
    }

    private companion object {
        /** 8 个样本足够压住抖动，再多会把"刚换网"的旧样本留太久。 */
        const val WINDOW = 8
        const val MAX_RTT_MS = 3_000L
    }
}
