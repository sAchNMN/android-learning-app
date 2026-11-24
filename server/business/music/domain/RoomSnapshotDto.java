package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间快照：客户端加入、轮询、上报指纹后拿到的都是它。
 *
 * 不含"当前在播什么"—— 播放状态是秒级变化的，走 WebSocket / 局域网 UDP，
 * 不走 HTTP。把状态塞进 REST 会让每台设备都得轮询，延迟最少一个往返起步。
 */
public class RoomSnapshotDto {

    private String code;

    /** 房主的 deviceId。成员靠它把时钟请求定向发给房主。 */
    private String hostId;

    private List<RoomMemberDto> members = new ArrayList<>();

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public List<RoomMemberDto> getMembers() {
        return members;
    }

    public void setMembers(List<RoomMemberDto> members) {
        this.members = members;
    }
}
