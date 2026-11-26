package com.bilimili.message;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatSocket socket;
    private final String[] origins;
    public WebSocketConfig(ChatSocket socket,@Value("${message.allowed-origins}") String origins) { this.socket=socket; this.origins=origins.split(","); }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) { registry.addHandler(socket,"/im").setAllowedOrigins(origins); }
}
