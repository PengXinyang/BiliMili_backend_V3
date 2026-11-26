package com.bilimili.message;

import com.fasterxml.jackson.databind.*;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 保留原来的 100 认证、101 发送、102 撤回指令；103 标记已读，104 心跳。2026.11.26 */
@Component
public class ChatSocket extends TextWebSocketHandler {
    private final ObjectMapper json;
    private final RealtimeHub hub;
    private final ChatService chats;
    public ChatSocket(ObjectMapper json,RealtimeHub hub,ChatService chats) { this.json=json; this.hub=hub; this.chats=chats; }
    @Override public void afterConnectionEstablished(WebSocketSession session) { session.setTextMessageSizeLimit(8192); hub.connected(session); }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage frame) throws Exception {
        try {
            JsonNode body=json.readTree(frame.getPayload());
            if(body==null || !body.isObject()) throw new ApiException(400,"消息必须是 JSON 对象");
            int code=body.path("code").asInt(-1);
            if(code==100) { hub.authenticate(session,body.path("content").asText()); return; }
            int uid=hub.uid(session);
            switch(code) {
                case 101 -> {
                    ChatMessage sent=chats.send(uid,body.path("acceptId").asInt(),body.path("content").asText(),hub.token(session));
                    hub.reply(session,"ack",Map.of("id",sent.id()));
                }
                case 102 -> { chats.withdraw(uid,body.path("id").asLong()); hub.reply(session,"ack",Map.of("id",body.path("id").asLong())); }
                case 103 -> { chats.read(uid,body.path("peerId").asInt()); hub.reply(session,"ack",Map.of("read",true)); }
                case 104 -> hub.reply(session,"pong",Map.of("uid",uid));
                default -> throw new ApiException(400,"不支持的消息指令");
            }
        } catch(ApiException ex) {
            hub.reply(session,"error",Map.of("code",ex.status,"message",ex.getMessage()));
            if(ex.status==401) session.close(CloseStatus.POLICY_VIOLATION);
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            hub.reply(session,"error",Map.of("code",400,"message","JSON 格式错误"));
        } catch(RuntimeException ex) {
            hub.reply(session,"error",Map.of("code",500,"message","消息处理失败，请查询历史记录确认结果"));
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) { hub.removed(session); }
    @Override public void handleTransportError(WebSocketSession session,Throwable error) throws Exception { hub.removed(session); session.close(CloseStatus.SERVER_ERROR); }
}
