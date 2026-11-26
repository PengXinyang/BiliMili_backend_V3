package com.bilimili.buaa13.config;

import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 在 StripPrefix 之后检查实际转发路径，防止内部接口经服务前缀暴露。2026.11.26 */
@Component
public class InternalEndpointFilter implements GlobalFilter, Ordered {
    @Override public int getOrder() { return 10001; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        List<String> segments = exchange.getRequest().getPath().pathWithinApplication().elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(element -> ((PathContainer.PathSegment) element).valueToMatch()).toList();
        String path = "/" + String.join("/", segments);
        boolean ambiguousPath = segments.stream().anyMatch(segment -> segment.equals(".")
                || segment.equals("..") || segment.contains("/") || segment.contains("\\"));
        if (ambiguousPath || path.equals("/internal") || path.startsWith("/internal/")
                || path.equals("/actuator") || path.startsWith("/actuator/")
                || path.equals("/druid") || path.startsWith("/druid/")
                || path.startsWith("/user/getUserByName/")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
