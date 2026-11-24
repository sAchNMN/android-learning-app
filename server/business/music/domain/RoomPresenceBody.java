package com.ruoyi.music.domain;

/**
 * 房间的入参。
 *
 * 出参用 [RoomMemberDto]，入参单独一个类是因为多了 [code] ——
 * 客户端 join 时房间码是必填，但成员自己的描述里不该带房间码。
 * 合成一个类的话，房间里每个成员的 JSON 里都会多出一个冗余的 code。
 */
public class RoomPresenceBody {

    /** 加入 / 上报指纹时必填；创建时忽略（由服务端生成）。 */
    private String code;

    private String deviceId;

    private String nickname;

    private String subnet;

    private String gateway;

    private String lanIp;

    private int lanPort;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

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
}
