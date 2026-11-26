package com.bilimili.buaa13.service.client;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** 用户业务仅提交消息事件，消息存储和连接由 message-service 持有。2026.11.26 */
@Component
public class MessageServiceClient {
    private static final Logger log=LoggerFactory.getLogger(MessageServiceClient.class);
    private final RestTemplate http;
    private final String base;
    private final String token;
    public MessageServiceClient(RestTemplateBuilder builder,@Value("${message.service-url:${MESSAGE_SERVICE_URL:http://localhost:8084}}") String base,
                                @Value("${MESSAGE_INTERNAL_TOKEN:}") String token) {
        this.http=builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(5)).build();
        this.base=base.replaceAll("/+$",""); this.token=token;
    }
    public void notify(Integer uid,String kind,String eventId) {
        post("/internal/messages/notifications",Map.of("uid",uid,"kind",kind,"eventId",eventId));
    }
    public void disconnect(Integer uid) {
        try { post("/internal/messages/users/"+uid+"/disconnect",Map.of()); }
        catch(RuntimeException ex) { log.warn("Message service disconnect failed for uid={}; revoked sessions are rechecked by message-service",uid); }
    }
    private void post(String path,Object body) {
        if(token.isBlank()) throw new IllegalStateException("MESSAGE_INTERNAL_TOKEN is required for message-service calls");
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Message-Token",token);
        http.exchange(base+path,HttpMethod.POST,new HttpEntity<>(body,headers),Void.class);
    }
}
