package com.ruoyi.music.domain;

/**
 * 房间里的一个成员。
 *
 * 同时承担两个角色：REST 的入参（{@code /create}、{@code /join}、{@code /presence}）
 * 和 REST/WS 的出参。这样客户端在"上报自己"和"读回房间"时用同一套字段，
 * 少一层映射，也少一处漏字段的机会。
 *
 * **注意 [subnet]/[gateway]/[lanIp] 是客户端自报的**，服务端不做校验也不信任：
 * 真正能不能直连由房主那边的 UDP 探测说了算，这里的字段只用来"初筛"，
 * 让客户端知道"值不值得试一下局域网"。所以伪造这些字段没有收益 ——
 * 改了也只会让房主多打几个探测包，然后照样判成公网。
 */
public class RoomMemberDto {

    private String deviceId;

    private String nickname;

    /** 房主标记。一个房间同时只有一个。 */
    private boolean host;

    /** 客户端自报的网段，形如 `192.168.1.0/24`。 */
    private String subnet;

    /** 客户端自报的默认网关。 */
    private String gateway;

    /** 客户端自报的局域网 IP。房主实际用的是 UDP 收包的源地址。 */
    private String lanIp;

    /** 局域网监听端口，固定 47820。 */
    private int lanPort;

    /** 探测结果。由房主判定后不落服务端，这里恒为 false，留给客户端合并。 */
    private boolean lanOk;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public boolean isHost() {
        return host;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public String getSubnet() {
        return subnet;
    }

    public void setSubnet(String subnet) {
        this.subnet = subnet;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getLanIp() {
        return lanIp;
    }

    public void setLanIp(String lanIp) {
        this.lanIp = lanIp;
    }

    public int getLanPort() {
        return lanPort;
    }

    public void setLanPort(int lanPort) {
        this.lanPort = lanPort;
    }

    public boolean isLanOk() {
        return lanOk;
    }

    public void setLanOk(boolean lanOk) {
        this.lanOk = lanOk;
    }
}
