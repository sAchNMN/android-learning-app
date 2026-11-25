package com.ruoyi.music.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.music.domain.RoomMemberDto;
import com.ruoyi.music.domain.RoomPresenceBody;
import com.ruoyi.music.domain.RoomSnapshotDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一起听房间。
 *
 * ## 为什么不落库
 *
 * 房间的有效期是"这一首歌的时间"，成员进出的粒度是秒。落库意味着：
 * 要给 `biz_music_room` 建表、要写定时任务清理、还要处理"进程重启但库里还挂着
 * 半屋子幽灵成员"的问题。而这些东西**没有任何一秒钟的价值** ——
 * 用户关掉 App 的那一刻，他在房间里的状态就该消失了。
 *
 * 所以这里用一张 `ConcurrentHashMap`：进程重启 = 全部房间解散，这是正确行为而不是缺陷。
 * 单机部署下这个决定完全成立；将来真要集群，这里换成 Redis 的 TTL key 就行，
 * 接口不变。
 *
 * ## 服务端在这里只做三件事
 *
 * 1. **发码**：6 位短码要在全局唯一，客户端做不到
 * 2. **记成员**：谁的网段是什么、谁是房主，跨设备可见的部分
 * 3. **转发**：不在同一局域网时，状态包只能经过这里
 *
 * 播放时间轴完全由房主负责，服务端不参与同步计算 —— 中转延迟不稳定，
 * 让服务端当时间权威反而会比"房主自报时戳 + 成员本地推算"更差。
 */
@Service
public class MusicRoomService {

    private static final Logger log = LoggerFactory.getLogger(MusicRoomService.class);

    /** 两小时没动静就回收。一起听是"一首歌的时间"，留这么久已经非常宽松。 */
    private static final long ROOM_TTL_MS = 2 * 60 * 60 * 1000L;

    /** 汽水的上限是 6 人，这里给到 8：再多人声就压过音乐了。 */
    private static final int MAX_MEMBERS = 8;

    private static final int CODE_LENGTH = 6;

    /**
     * 去掉了 0/O/1/I/L 这几个。
     *
     * 房间码是**要口头念给旁边的人**的（"我这个是 K 7 M 2 P 9"），
     * 保留易混淆字符的结果就是对方输错三次然后放弃。
     */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    private static class Room {
        final String code;
        volatile String hostId;
        volatile long touchedAt;
        final Map<String, RoomMemberDto> members = new ConcurrentHashMap<>();

        Room(String code) {
            this.code = code;
        }
    }

    // ---------------------------------------------------------------- 对外

    public RoomSnapshotDto create(RoomPresenceBody body) {
        requireDevice(body);
        sweep();
        String code = allocateCode();
        Room room = new Room(code);
        room.hostId = body.getDeviceId();
        room.touchedAt = now();
        rooms.put(code, room);
        upsert(room, body, true);
        log.info("一起听房间创建 {}", code);
        return snapshot(room);
    }

    public RoomSnapshotDto join(RoomPresenceBody body) {
        requireDevice(body);
        Room room = require(body.getCode());
        // 断线重连会走到这里：同一个 deviceId 再进来一次，不该被当成第 N 个人
        if (!room.members.containsKey(body.getDeviceId())
            && room.members.size() >= MAX_MEMBERS) {
            throw new ServiceException("房间人数已满");
        }
        upsert(room, body, body.getDeviceId().equals(room.hostId));
        return snapshot(room);
    }

    /**
     * 更新网络指纹。
     *
     * 房间建好之后才换网（WiFi 切流量）是常见操作，房主必须能感知到，
     * 否则还会往一个已经不存在的局域网地址发包。
     */
    public RoomSnapshotDto presence(String code, RoomPresenceBody body) {
        requireDevice(body);
        Room room = require(code);
        upsert(room, body, body.getDeviceId().equals(room.hostId));
        return snapshot(room);
    }

    public RoomSnapshotDto get(String code) {
        Room room = rooms.get(code);
        if (room == null) {
            throw new ServiceException("房间不存在或已解散");
        }
        room.touchedAt = now();
        return snapshot(room);
    }

    /**
     * 离开。房主一走整个房间就散 —— 时间轴在房主身上，
     * 房主没了就没有权威，留着其他人只会各自播各自的。
     */
    public void leave(String code, String deviceId) {
        if (code == null || deviceId == null) {
            return;
        }
        Room room = rooms.get(code);
        if (room == null) {
            return;
        }
        room.members.remove(deviceId);
        room.touchedAt = now();
        if (deviceId.equals(room.hostId) || room.members.isEmpty()) {
            rooms.remove(code);
            log.info("一起听房间解散 {}", code);
        }
    }

    public boolean exists(String code) {
        return code != null && rooms.containsKey(code);
    }

    /** WebSocket 侧广播成员变化用。房间不存在时返回空表而不是抛异常。 */
    public List<RoomMemberDto> members(String code) {
        Room room = rooms.get(code);
        return room == null ? new ArrayList<>() : new ArrayList<>(room.members.values());
    }

    public String hostOf(String code) {
        Room room = rooms.get(code);
        return room == null ? null : room.hostId;
    }

    // ---------------------------------------------------------------- 内部

    private void upsert(Room room, RoomPresenceBody body, boolean host) {
        RoomMemberDto member = new RoomMemberDto();
        member.setDeviceId(body.getDeviceId());
        member.setNickname(defaulted(body.getNickname(), body.getDeviceId()));
        member.setHost(host);
        member.setSubnet(trimToNull(body.getSubnet()));
        member.setGateway(trimToNull(body.getGateway()));
        member.setLanIp(trimToNull(body.getLanIp()));
        member.setLanPort(body.getLanPort());
        room.members.put(body.getDeviceId(), member);
        room.touchedAt = now();
    }

    private RoomSnapshotDto snapshot(Room room) {
        RoomSnapshotDto dto = new RoomSnapshotDto();
        dto.setCode(room.code);
        dto.setHostId(room.hostId);
        dto.setMembers(new ArrayList<>(room.members.values()));
        return dto;
    }

    private Room require(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        Room room = rooms.get(normalized);
        if (room == null) {
            throw new ServiceException("房间不存在或已解散");
        }
        room.touchedAt = now();
        return room;
    }

    private void requireDevice(RoomPresenceBody body) {
        if (body == null || trimToNull(body.getDeviceId()) == null) {
            throw new ServiceException("缺少设备标识");
        }
    }

    private String allocateCode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = builder.toString();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        throw new ServiceException("房间码分配失败，请重试");
    }

    /** 惰性回收：房间数量是个位数量级，每次创建时全扫一遍比开定时任务更省事。 */
    private void sweep() {
        long deadline = now() - ROOM_TTL_MS;
        rooms.entrySet().removeIf(entry -> entry.getValue().touchedAt < deadline);
    }

    private static String defaulted(String value, String fallback) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            return trimmed;
        }
        // 没起名字就显示设备号后四位，比"未知用户"好几个数量级
        String tail = fallback.length() > 4 ? fallback.substring(fallback.length() - 4) : fallback;
        return "听友" + tail;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
