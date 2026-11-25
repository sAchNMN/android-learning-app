package com.ruoyi.music.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.music.domain.RoomMemberDto;
import com.ruoyi.music.service.MusicRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一起听的公网中转。
 *
 * 这个类**不做任何同步计算**。它只干一件事：把一台设备的包原样递给房间里另外几台。
 * 时间轴、锚点、纠偏全部在客户端，服务端连"当前播到第几秒"都不解析 ——
 * 一旦服务端开始理解业务字段，协议升级就要三端一起发版，而中转层只需要转发。
 *
 * ## 局域网和公网的关系
 *
 * 局域网可达时，客户端的状态包根本不经过这里（走 UDP 直连），
 * 但**这条连接仍然保持着**：房间成员、模式切换、时钟握手的失败回退都依赖它。
 * 所以这里的连接数 ≈ 房间人数，而不是"局域网模式下就没有连接"。
 */
@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);

    private static final String TYPE_HELLO = "hello";
    private static final String TYPE_MEMBERS = "members";

    /** 路径末段是房间码，前面固定是这段前缀。 */
    private static final String PATH_PREFIX = "/app/music/room/ws/";

    @Autowired
    private MusicRoomService roomService;

    /** sessionId → 会话上下文。 */
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();

    /** 房间码 → sessionId 集合。 */
    private final Map<String, Set<String>> rooms = new ConcurrentHashMap<>();

    /**
     * sessionId → session。
     *
     * WebSocketSession 拿不到"遍历所有会话"的接口，按 deviceId 定向转发时
     * 必须有这么一张反查表。**连接关闭时必须手动删**，否则每进一次房间
     * 就多留一个已经失效的 session 对象，直到进程重启。
     */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private static class Context {
        String roomCode;
        String deviceId;
        String nickname;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Context context = parseContext(session);
        if (context == null) {
            // 房间码或设备号解析不出来，直接断：留着一条没有归属的连接，
            // 后面每条消息都要重新判断"该转给谁"，不如当场拒绝
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        if (!roomService.exists(context.roomCode)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        contexts.put(session.getId(), context);
        sessions.put(session.getId(), session);
        rooms.computeIfAbsent(context.roomCode, key -> ConcurrentHashMap.newKeySet())
            .add(session.getId());
        log.info("一起听 WS 接入 room={} device={}", context.roomCode, context.deviceId);
        broadcastMembers(context.roomCode);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Context context = contexts.get(session.getId());
        if (context == null) {
            return;
        }
        JSONObject json = parse(message.getPayload());
        if (json == null) {
            return;
        }
        String type = json.getString("type");
        if (type == null || type.isEmpty()) {
            return;
        }

        // from 必须是这条连接自己的身份。不校验的话，任何一台设备都能冒充房主发状态，
        // 整个房间会被一条伪造包带跑
        json.put("from", context.deviceId);

        if (TYPE_HELLO.equals(type)) {
            broadcastMembers(context.roomCode);
            return;
        }

        String target = json.getString("target");
        String payload = json.toJSONString();
        if (target != null && !target.isEmpty()) {
            sendToDevice(context.roomCode, target, payload, session.getId());
        } else {
            fanOut(context.roomCode, payload, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        Context context = contexts.remove(session.getId());
        if (context == null) {
            return;
        }
        Set<String> peers = rooms.get(context.roomCode);
        if (peers != null) {
            peers.remove(session.getId());
            if (peers.isEmpty()) {
                rooms.remove(context.roomCode);
            }
        }
        // 连接断了不等于离开了房间：用户可能只是切了个后台。
        // 成员由 REST 的生命周期管，这里只负责通知"有人掉线了，成员表可能变了"
        broadcastMembers(context.roomCode);
        log.info("一起听 WS 断开 room={} device={}", context.roomCode, context.deviceId);
    }

    // ---------------------------------------------------------------- 内部

    private void broadcastMembers(String roomCode) {
        List<RoomMemberDto> members = roomService.members(roomCode);
        JSONObject message = new JSONObject();
        message.put("type", TYPE_MEMBERS);
        message.put("from", "server");
        message.put("members", members);
        fanOut(roomCode, message.toJSONString(), null);
    }

    /** 发给房间里除 [exceptSessionId] 之外的所有人。 */
    private void fanOut(String roomCode, String payload, String exceptSessionId) {
        Set<String> peers = rooms.get(roomCode);
        if (peers == null) {
            return;
        }
        for (String sessionId : peers) {
            if (sessionId.equals(exceptSessionId)) {
                continue;
            }
            send(sessionId, payload);
        }
    }

    private void sendToDevice(String roomCode, String deviceId, String payload, String exceptSessionId) {
        Set<String> peers = rooms.get(roomCode);
        if (peers == null) {
            return;
        }
        for (String sessionId : peers) {
            if (sessionId.equals(exceptSessionId)) {
                continue;
            }
            Context peer = contexts.get(sessionId);
            if (peer != null && deviceId.equals(peer.deviceId)) {
                send(sessionId, payload);
                return;
            }
        }
    }

    /**
     * WebSocketSession 不是线程安全的。
     *
     * 这里的写入来自多条线程（各自的消息线程），不加锁会出现
     * "一帧被拆成两半写出去"，对端读到的是坏 JSON —— 表现为偶发的解析失败，
     * 而且只在多人同时操作时出现，极难复现。
     */
    private void send(String sessionId, String payload) {
        WebSocketSession target = findSession(sessionId);
        if (target == null || !target.isOpen()) {
            return;
        }
        try {
            synchronized (target) {
                target.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.debug("一起听转发失败 session={} {}", sessionId, e.getMessage());
        }
    }

    private WebSocketSession findSession(String sessionId) {
        return sessions.get(sessionId);
    }

    private Context parseContext(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            if (uri == null) {
                return null;
            }
            String path = uri.getPath();
            int index = path == null ? -1 : path.indexOf(PATH_PREFIX);
            if (index < 0) {
                return null;
            }
            String code = path.substring(index + PATH_PREFIX.length());
            if (code.contains("/")) {
                code = code.substring(0, code.indexOf('/'));
            }
            code = code.trim().toUpperCase();
            if (code.isEmpty()) {
                return null;
            }
            Map<String, String> query = queryOf(uri.getQuery());
            String deviceId = query.get("deviceId");
            if (deviceId == null || deviceId.isEmpty()) {
                return null;
            }
            Context context = new Context();
            context.roomCode = code;
            context.deviceId = deviceId;
            context.nickname = query.get("nickname");
            return context;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> queryOf(String raw) {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = pair.substring(0, split);
            String value = pair.substring(split + 1);
            result.put(key, java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
        return result;
    }

    private static JSONObject parse(String payload) {
        try {
            return JSON.parseObject(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
