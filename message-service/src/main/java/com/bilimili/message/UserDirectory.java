package com.bilimili.message;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** 用户身份始终由 user-service 校验，消息服务不信任客户端提交的发送者 UID。2026.11.26 */
@Component
public class UserDirectory {
    private final RestTemplate http;
    private final String base;
    public UserDirectory(RestTemplateBuilder builder, @Value("${message.user-service-url}") String base) {
        this.http = builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(3)).build();
        this.base = base.replaceAll("/+$", "");
    }
    public int authenticate(String token) {
        if (token == null || !token.startsWith("Bearer ") || token.length() < 8) throw new ApiException(401, "请先登录");
        Map<?, ?> body = get("/internal/message/session", token);
        Object uid = body.get("uid");
        if (!(uid instanceof Number) || ((Number) uid).intValue() <= 0) throw new ApiException(401, "用户身份无效");
        return ((Number) uid).intValue();
    }
    public Map<?, ?> user(int uid, String token) {
        if (uid <= 0) throw new ApiException(400, "用户 ID 无效");
        return get("/internal/message/users/" + uid, token);
    }
    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.set(HttpHeaders.AUTHORIZATION, token);
        try {
            Map<?, ?> result = http.exchange(base + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (result == null) throw new ApiException(503, "用户服务响应为空");
            return result;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 404) throw new ApiException(404, "用户不存在");
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) throw new ApiException(401, "登录已失效");
            throw new ApiException(503, "用户服务暂不可用");
        } catch (RestClientException ex) { throw new ApiException(503, "无法连接用户服务"); }
    }
}
