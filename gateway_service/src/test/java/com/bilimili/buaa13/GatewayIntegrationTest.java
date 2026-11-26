package com.bilimili.buaa13;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false", "spring.cloud.service-registry.auto-registration.enabled=false"
})
class GatewayIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DisposableServer HTTP = HttpServer.create().host("127.0.0.1").port(0)
            .handle((req, res) -> {
                String auth = req.requestHeaders().get("Authorization", "");
                boolean protectedPath = req.uri().contains("personal/info");
                int status = protectedPath ? (auth.isEmpty() ? 401 : auth.equals("Bearer valid-test") ? 200 : 403) : 200;
                try {
                    String body = JSON.writeValueAsString(Map.of("path", req.uri(), "authorization", auth, "method", req.method().name()));
                    return res.status(status).header("Content-Type", "application/json")
                            .sendString(Mono.just(body));
                } catch (Exception ex) { return Mono.error(ex); }
            }).bindNow();
    private static final DisposableServer WS = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.ws("/im", (in, out) -> out.sendString(in.receive().asString().map(s -> "echo:" + s))))
            .bindNow();

    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry props) {
        for (String key : new String[]{"USER_SERVICE_URL", "VIDEO_SERVICE_URL", "ARTICLE_SERVICE_URL", "MESSAGE_SERVICE_URL"})
            props.add(key, () -> "http://127.0.0.1:" + HTTP.port());
        props.add("MESSAGE_SERVICE_WS_URL", () -> "ws://127.0.0.1:" + WS.port());
    }
    @Autowired WebTestClient client;
    @LocalServerPort int port;
    @AfterAll static void shutdown() { HTTP.disposeNow(); WS.disposeNow(); }

    @Test void loginAndLegacyLoginReachTheSameBackendPath() {
        for (String path : new String[]{"/user/account/login", "/user/user/account/login"}) {
            client.post().uri(path).bodyValue("account=demo").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.path").isEqualTo("/user/account/login")
                    .jsonPath("$.method").isEqualTo("POST");
        }
    }

    @Test void servicePrefixesAndQueriesAreRewrittenExactlyOnce() {
        Map<String,String> cases = Map.of(
                "/user/user/info/get-one?uid=1", "/user/info/get-one?uid=1",
                "/video/getone?vid=7", "/getone?vid=7",
                "/video/video/change/status", "/video/change/status",
                "/video/change/status", "/video/change/status",
                "/article/article/get?aid=4", "/article/get?aid=4",
                "/article/get?aid=4", "/article/get?aid=4",
                "/article/column/markdown", "/column/markdown",
                "/message/api/unread", "/api/unread",
                "/user/msg-unread/all", "/msg-unread/all");
        cases.forEach((input, target) -> client.get().uri(input).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo(target));
    }

    @Test void authenticationHeadersAndBackendFailuresArePreserved() {
        client.get().uri("/user/personal/info").exchange().expectStatus().isUnauthorized();
        client.get().uri("/user/personal/info").header("Authorization", "Bearer invalid-test")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/user/personal/info").header("Authorization", "Bearer valid-test")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.authorization").isEqualTo("Bearer valid-test")
                .jsonPath("$.path").isEqualTo("/user/personal/info");
    }

    @Test void internalAndManagementEndpointsCannotBeProxied() {
        for (String path : new String[]{"/user/internal/message/session", "/message/internal/messages/notifications",
                "/user/actuator/health", "/article/druid/index.html", "/user/user/getUserByName/demo",
                "/user/%69nternal/message/session", "/user/internal;v=1/message/session"}) {
            client.get().uri(URI.create("http://localhost:" + port + path)).exchange().expectStatus().isNotFound();
        }
        client.get().uri("/user-service/user/account/login").exchange().expectStatus().isNotFound();
    }

    @Test void corsPreflightAllowsConfiguredOriginAndRejectsOthers() {
        client.options().uri("/user/account/login").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
        client.options().uri("/user/account/login").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
    }

    @Test void websocketFramesPassThroughBothSupportedPaths() {
        for (String path : new String[]{"/message/im", "/im"}) {
            AtomicReference<String> reply = new AtomicReference<>();
            new ReactorNettyWebSocketClient().execute(URI.create("ws://localhost:" + port + path),
                    session -> session.send(Mono.just(session.textMessage("test-frame")))
                            .thenMany(session.receive().take(1).doOnNext(m -> reply.set(m.getPayloadAsText())))
                            .then()).block(Duration.ofSeconds(10));
            assertEquals("echo:test-frame", reply.get());
        }
    }

    @Test void livenessIsAvailableWithoutLogin() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
