# R8 保持规则。
#
# 只保「运行期靠名字找代码」的面：反射反序列化、本地存档格式。
# 不要写 `-dontwarn **` —— 它会把真实的缺类问题一起吞掉，等到运行时才炸。

# ---------------------------------------------------------------------------
# Gson：按「字段名」读写 JSON。
#
# 这些类既用于网络响应解析，也是本地存档的格式（收藏、推荐缓存、导入歌单、
# 音源配置都直接把 JSON 串存进 SharedPreferences）。字段名就是协议的一部分：
# 混淆后新包读不出旧包写下的数据，用户升级一次收藏就全空了。
# Gson 自带的 consumer rules 只保 `@SerializedName` 标注的字段，
# 而 MusicTrack / StoredPlaylist / SourceConfig 的字段全部没有标注，这里必须整类保留。
# ---------------------------------------------------------------------------
-keep class com.szc.learning.core.common.music.** { *; }
-keep class com.szc.learning.core.network.** { *; }
-keep class com.szc.learning.music.data.StoredPlaylist { *; }
-keep class com.szc.learning.music.data.SourceConfig { *; }

# 枚举以 name() 参与序列化（TrackSource、PlaylistTag），名字被改就还原不回来
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
