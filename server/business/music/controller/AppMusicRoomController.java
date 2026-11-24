package com.ruoyi.music.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.music.domain.RoomPresenceBody;
import com.ruoyi.music.service.MusicRoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一起听房间接口。
 *
 * 五个动作，全部匿名可访问 —— App 没有账号体系，`deviceId` 就是身份。
 * 这意味着房间里没有"账号安全"这回事，也不需要：
 * 房间码是 6 位随机，两小时后自动回收，泄给别人最多是被听一会儿歌。
 *
 * **这些接口只管"谁在房间里"**。播放状态不走 HTTP，
 * 局域网走 UDP、公网走 WebSocket —— 见 {@link com.ruoyi.music.ws.RoomWebSocketHandler}。
 */
@Anonymous
@RestController
@RequestMapping("/app/music/room")
public class AppMusicRoomController {

    @Autowired
    private MusicRoomService roomService;

    /** 建房。返回房间码，房主身份由第一个建的人占据。 */
    @PostMapping("/create")
    public AjaxResult create(@RequestBody RoomPresenceBody body) {
        return AjaxResult.success(roomService.create(body));
    }

    /** 按码加入。房间不存在时抛 ServiceException，由全局处理器转成错误提示。 */
    @PostMapping("/join")
    public AjaxResult join(@RequestBody RoomPresenceBody body) {
        return AjaxResult.success(roomService.join(body));
    }

    /**
     * 读房间当前状态。
     *
     * 房主每 5 秒拉一次来发现成员变化。没做服务端主动推送，是因为
     * "有人进房"这件事本来就需要一两秒才能完成 U（客户端要起 socket），
     * 再快也没有意义，反而要维护一条推送通道。
     */
    @GetMapping("/{code}")
    public AjaxResult snapshot(@PathVariable("code") String code) {
        return AjaxResult.success(roomService.get(code));
    }

    /** 上报网络指纹。换了网络（WiFi→流量）就调一次。 */
    @PostMapping("/{code}/presence")
    public AjaxResult presence(
        @PathVariable("code") String code,
        @RequestBody RoomPresenceBody body
    ) {
        return AjaxResult.success(roomService.presence(code, body));
    }

    /** 离开。房主离开时整个房间解散。 */
    @PostMapping("/leave")
    public AjaxResult leave(@RequestBody RoomPresenceBody body) {
        roomService.leave(body == null ? null : body.getCode(), body == null ? null : body.getDeviceId());
        return AjaxResult.success();
    }
}
