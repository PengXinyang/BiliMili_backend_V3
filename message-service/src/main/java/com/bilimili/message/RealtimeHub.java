package com.bilimili.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** 连接属于消息服务；当前版本部署单副本，断线消息从数据库历史接口补取。2026.11.26 */
@Component
public class RealtimeHub {
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final UserDirectory users;
    private static class Connection {
        final WebSocketSession socket;
        final long created = System.currentTimeMillis();
        volatile Integer uid;
        volatile String token;
        Connection(WebSocketSession socket) { this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536); }
    }
    public RealtimeHub(ObjectMapper json, UserDirectory users) { this.json = json; this.users = users; }
    public void connected(WebSocketSession socket) { connections.put(socket.getId(), new Connection(socket)); }
    public void removed(WebSocketSession socket) { connections.remove(socket.getId()); }
    public void authenticate(WebSocketSession socket, String token) {
        Connection c = connections.get(socket.getId());
        if (c == null) throw new ApiException(401, "连接已关闭");
        if (c.uid != null) throw new ApiException(400, "连接已完成认证");
        int uid = users.authenticate(token); c.token = token; c.uid = uid;
        reply(socket, "connected", Map.of("uid", uid));
    }
    public int uid(WebSocketSession socket) {
        Connection c = connections.get(socket.getId());
        if (c == null || c.uid == null) throw new ApiException(401, "请先发送 code=100 和 Bearer token");
        if (users.authenticate(c.token) != c.uid) throw new ApiException(401, "连接身份已失效");
        return c.uid;
    }
    public String token(WebSocketSession socket) { return connections.get(socket.getId()).token; }
    public void reply(WebSocketSession socket, String type, Object data) {
        Connection c = connections.get(socket.getId());
        if (c != null) write(c, type, data);
    }
    private void write(Connection c, String type, Object data) {
        try {
            if (c.socket.isOpen()) c.socket.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type", type, "time", Instant.now().toString(), "data", data))));
        } catch (IOException | RuntimeException ex) { close(c); }
    }
    public void emit(int uid, String type, Object data) {
        Runnable delivery = () -> {
            for (Connection c : connections.values()) {
                if (Objects.equals(c.uid, uid)) {
                    try { if (users.authenticate(c.token) == uid) write(c, type, data); else close(c); }
                    catch (ApiException ex) { close(c); }
                }
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { delivery.run(); }
            });
        } else delivery.run();
    }
    public void disconnect(int uid) { for (Connection c : connections.values()) if (Objects.equals(c.uid, uid)) close(c); }
    private void close(Connection c) {
        connections.remove(c.socket.getId());
        try { c.socket.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) { }
    }
    @Scheduled(fixedDelay = 10000)
    public void sweep() {
        for (Connection c : connections.values()) {
            if (c.uid == null) { if (System.currentTimeMillis() - c.created > 15000) close(c); }
            else { try { if (users.authenticate(c.token) != c.uid) close(c); } catch (ApiException ex) { close(c); } }
        }
    }
}
