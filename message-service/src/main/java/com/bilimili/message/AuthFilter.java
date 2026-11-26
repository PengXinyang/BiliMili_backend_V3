package com.bilimili.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final UserDirectory users;
    private final ObjectMapper json;
    private final String internalToken;
    public AuthFilter(UserDirectory users, ObjectMapper json, @Value("${message.internal-token:}") String token) {
        this.users = users; this.json = json; this.internalToken = token;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getServletPath();
        try {
            if (path.startsWith("/internal/messages/")) {
                String token = req.getHeader("X-Message-Token");
                if (internalToken.isBlank() || token == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
                    throw new ApiException(403, "服务间认证失败");
            } else if (path.startsWith("/api/") || path.startsWith("/msg-unread/") || path.startsWith("/user/msg-unread/")) {
                req.setAttribute("message.uid", users.authenticate(req.getHeader("Authorization")));
            }
        } catch (ApiException ex) {
            res.setStatus(ex.status); res.setContentType("application/json;charset=UTF-8");
            json.writeValue(res.getWriter(), Map.of("code", ex.status, "message", ex.getMessage())); return;
        }
        chain.doFilter(req, res);
    }
}
