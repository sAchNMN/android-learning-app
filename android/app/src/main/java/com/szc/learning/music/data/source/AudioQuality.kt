package com.szc.learning.music.data.source

import androidx.annotation.StringRes
import com.szc.learning.R

/**
 * 播放音质档位。
 *
 * 音质**不是本地文件属性**，而是取址请求里的参数：客户端把 [br] 带给平台，
 * 平台按这个账号的权限下发对应码率的流。所以「有没有不同音质的文件」不成立，
 * 真正决定能不能听无损的是账号权限 —— 见 [vipOnly] 与 [resolveNotice]。
 *
 * 档位顺序即权重顺序（ordinal 递增），用来比较「请求的」和「实际拿到的」：
 * 平台降级（会员过期、曲目无该档资源）时靠这个顺序判断是否需要提示用户。
 *
 * 与后端 `NeteaseSourceProvider.bitrate` 的映射保持一致，两边不要各写一套。
 */
enum class AudioQuality(
    /** 请求参数与本地存储用的稳定键。 */
    val key: String,
    @param:StringRes val labelRes: Int,
    /** 网易云 `br` 参数（bps）。 */
    val br: Int,
    /** 平台在下发地址时回填的 level 名，用来比对实际档位。 */
    val level: String,
    /** 是否需要平台会员权限。仅用于 UI 标注，权限真伪一律以接口返回为准。 */
    val vipOnly: Boolean,
) {
    STANDARD("standard", R.string.quality_standard, 128_000, "standard", false),
    HIGH("high", R.string.quality_high, 192_000, "higher", false),
    EXHIGH("exhigh", R.string.quality_exhigh, 320_000, "exhigh", false),
    LOSSLESS("lossless", R.string.quality_lossless, 999_000, "lossless", true),
    HIRES("hires", R.string.quality_hires, 1_999_000, "hires", true);

    companion object {
        /**
         * 默认档位保持 320k。
         *
         * 这是改动前客户端写死的值（`NeteaseClientSource.BITRATE`），
         * 默认值不跟着新功能漂移，老用户升级后听感不变。
         */
        val DEFAULT: AudioQuality = EXHIGH

        fun of(key: String?): AudioQuality =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: DEFAULT

        /**
         * 平台返回的 level → 本地档位。
         *
         * 网易云还会吐 `jyeffect`（沉浸声）/`sky`（空间音频）/`jymaster`（超清母带）
         * 这几个新档位，它们都比 lossless 高；本地没有对应选项，一律按 [HIRES] 处理，
         * 只用于「实际不比请求差」的比较，不用于展示。
         */
        fun ofLevel(level: String?): AudioQuality? = when (level?.trim()?.lowercase()) {
            null, "" -> null
            "standard" -> STANDARD
            "higher", "high" -> HIGH
            "exhigh" -> EXHIGH
            "lossless" -> LOSSLESS
            "hires", "jyeffect", "sky", "jymaster" -> HIRES
            else -> null
        }

        /**
         * 实际码率 → 档位。酷狗不给 level，只给 bitrate，用它反推。
         *
         * 分档取「不高于实际值的最高档」：128k 的 mp3 是 128000，但转码后常见
         * 127xxx，卡死等值会让所有结果都判成 null。
         */
        fun ofBitrate(br: Int): AudioQuality? = when {
            br <= 0 -> null
            br < 160_000 -> STANDARD
            br < 256_000 -> HIGH
            br < 700_000 -> EXHIGH
            br < 1_500_000 -> LOSSLESS
            else -> HIRES
        }
    }
}

/**
 * 音质降级判定。
 *
 * 判断依据只有接口返回，**不预判会员身份**：
 * 1. 会员过期、曲目本身没有无损母带、地区限制，都会让平台静默降级，
 *    提前查 `vipType` 会得出「你是会员所以没问题」的错误结论；
 * 2. `freeTrialInfo` 非空是唯一能确定「只给了试听片段」的信号。
 *
 * 返回 null 表示按用户所选原样拿到，UI 不需要提示。
 */
fun qualityFallback(
    requested: AudioQuality,
    actualLevel: String?,
    actualBr: Int,
    trial: Boolean,
): QualityFallback? {
    if (trial) return QualityFallback(requested, null, trial = true)
    val actual = AudioQuality.ofLevel(actualLevel) ?: AudioQuality.ofBitrate(actualBr) ?: return null
    return if (actual.ordinal >= requested.ordinal) null else QualityFallback(requested, actual, trial = false)
}

/** [actual] 为 null 且 [trial] 为 true 时表示「只有试听片段」，档位未知。 */
data class QualityFallback(
    val requested: AudioQuality,
    val actual: AudioQuality?,
    val trial: Boolean,
)
