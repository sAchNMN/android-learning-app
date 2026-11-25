package com.ruoyi.music.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 一起听中转的 WebSocket 端点。
 *
 * ```text
 * ws://host/app/music/room/ws/{房间码}?deviceId=xxx&nickname=yyy
 * ```
 *
 * ## 上线前必须做的一件事
 *
 * 若依的 `SecurityConfig` 是 `anyRequest().authenticated()`，而 **WebSocket 握手
 * 走的是普通 HTTP GET，会先被 Security 拦下来**。`@Anonymous` 注解对它无效 ——
 * 那个注解只对 `RequestMappingHandlerMapping` 里的控制器方法生效，
 * WebSocket 的 handler 注册在另一套映射里，扫描不到。
 *
 * 所以在 `ruoyi-framework/src/main/java/com/ruoyi/framework/config/SecurityConfig.java`
 * 的 `filterChain` 里补一行：
 *
 * ```java
 * .requestMatchers("/app/music/room/ws/**").permitAll()
 * ```
 *
 * 漏了这行的话，症状是**客户端 WS 一直 401 重连**，而 REST 接口全都正常 ——
 * 因为 REST 有 `@Anonymous` 兜着，很容易以为是别的问题。
 *
 * ## 为什么不用 `@ServerEndpoint`
 *
 * 若依自带的 WebSocket 推送用的是 JSR-356 注解方式（`ServerEndpointExporter`），
 * 那套在 Spring 容器里拿不到 `@Autowired` 的 bean，只能靠静态字段去够，
 * 而这里中转需要读 {@link com.ruoyi.music.service.MusicRoomService} 的成员表。
 * Spring 的 `WebSocketHandler` 是普通 bean，注入是自然的。
 */
@Configuration
@EnableWebSocket
public class RoomWebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private RoomWebSocketHandler roomWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomWebSocketHandler, "/app/music/room/ws/**")
            // 客户端是原生 App，没有浏览器同源限制。这里放开是为了调试时
            // 能直接用浏览器或 wscat 连上来验协议，不用额外装代理
            .setAllowedOriginPatterns("*");
    }
}
