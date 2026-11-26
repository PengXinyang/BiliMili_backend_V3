package com.bilimili.message;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 真 HTTP/WebSocket + H2 数据库；仅用户身份服务使用独立 HTTP 桩。2026.11.26 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageIntegrationTest {
    static final Set<String> revoked=ConcurrentHashMap.newKeySet();
    static final ExecutorService userThreads=Executors.newCachedThreadPool();
    static final HttpServer users=startUsers();
    static HttpServer startUsers() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(userThreads);
            server.createContext("/internal/message/", exchange -> {
                String token=exchange.getRequestHeaders().getFirst("Authorization");
                int status=200; int uid=0;
                if(token==null || !token.matches("Bearer test-[123]") || revoked.contains(token)) status=401;
                else {
                    uid=Integer.parseInt(token.substring(token.length()-1));
                    String path=exchange.getRequestURI().getPath();
                    if(path.contains("/users/")) { uid=Integer.parseInt(path.substring(path.lastIndexOf('/')+1)); if(uid<1 || uid>3) status=404; }
                }
                byte[] body=("{\"uid\":"+uid+",\"nickname\":\"用户"+uid+"\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");
                exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start(); return server;
        } catch(Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:message-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.username",()->"sa"); r.add("spring.datasource.password",()->"");
        r.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
        r.add("message.user-service-url",()->"http://127.0.0.1:"+users.getAddress().getPort());
        r.add("message.internal-token",()->"test-service-secret");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void clearDatabase() {
        revoked.clear();
        for(String table:List.of("message_event","chat_detailed","chat","message_unread")) db.update("DELETE FROM "+table);
    }
    @AfterAll static void stopUsers() { users.stop(0); userThreads.shutdownNow(); }
    HttpResponse<String> call(String method,String path,int uid,String body) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(uid>0) b.header("Authorization","Bearer test-"+uid);
        b.header("Content-Type","application/json");
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).path("data"); }
    long send(int from,int to,String content) throws Exception { return data(call("POST","/api/messages",from,json.writeValueAsString(Map.of("acceptId",to,"content",content)))).path("id").asLong(); }
    int unread(int uid) throws Exception { return data(call("GET","/api/unread",uid,null)).path("message").asInt(); }
    @Test void requiresIdentityAndValidRecipient() throws Exception {
        assertEquals(401,call("GET","/api/chats",0,null).statusCode());
        assertEquals(401,call("GET","/api/chats",9,null).statusCode());
        assertEquals(400,call("POST","/api/messages",1,"{\"acceptId\":1,\"content\":\"self\"}").statusCode());
        assertEquals(404,call("POST","/api/messages",1,"{\"acceptId\":99,\"content\":\"missing\"}").statusCode());
        assertEquals(400,call("POST","/api/messages",1,"{\"acceptId\":2,\"content\":\" \"}").statusCode());
        assertEquals(400,call("GET","/api/chats?offset=-1",1,null).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_detailed",Integer.class));
    }
    @Test void senderComesFromAuthenticationAndUnreadIsConsistent() throws Exception {
        JsonNode sent=data(call("POST","/api/messages",1,"{\"postId\":3,\"acceptId\":2,\"content\":\"你好，私聊测试\"}"));
        assertEquals(1,sent.path("postId").asInt()); assertEquals(1,unread(2)); assertEquals(0,unread(1));
        JsonNode chats=data(call("GET","/api/chats",2,null)); assertEquals(1,chats.size()); assertEquals(1,chats.get(0).path("peerId").asInt());
        data(call("POST","/api/chats/1/read",2,null)); assertEquals(0,unread(2));
        data(call("POST","/api/chats/1/read",2,null)); assertEquals(0,unread(2));
        JsonNode history=data(call("GET","/api/chats/1/messages",2,null)); assertEquals("你好，私聊测试",history.path("messageList").get(0).path("content").asText());
    }
    @Test void historyPaginationAndDeletionAreIsolated() throws Exception {
        for(int i=0;i<12;i++) send(1,2,"m"+i);
        JsonNode first=data(call("GET","/api/chats/1/messages?limit=10",2,null));
        assertEquals(10,first.path("messageList").size()); assertTrue(first.path("moreMessage").asBoolean());
        JsonNode next=data(call("GET","/api/chats/1/messages?limit=10&offset=10",2,null)); assertEquals(2,next.path("messageList").size()); assertFalse(next.path("moreMessage").asBoolean());
        assertEquals(0,data(call("GET","/api/chats/1/messages?offset=100",2,null)).path("messageList").size());
        long id=first.path("messageList").get(0).path("id").asLong();
        assertEquals(403,call("DELETE","/api/messages/"+id,3,null).statusCode());
        data(call("DELETE","/api/messages/"+id,1,null));
        assertEquals(11,data(call("GET","/api/chats/2/messages",1,null)).path("messageList").size());
        assertEquals(12,data(call("GET","/api/chats/1/messages",2,null)).path("messageList").size());
        data(call("DELETE","/api/messages/"+id,2,null)); assertEquals(11,unread(2));
        data(call("DELETE","/api/messages/"+id,2,null)); assertEquals(11,unread(2));
    }
    @Test void withdrawChecksOwnerDeadlineAndHidesContent() throws Exception {
        long id=send(1,2,"撤回内容");
        assertEquals(403,call("POST","/api/messages/"+id+"/withdraw",2,null).statusCode());
        data(call("POST","/api/messages/"+id+"/withdraw",1,null)); assertEquals(0,unread(2));
        data(call("POST","/api/messages/"+id+"/withdraw",1,null)); assertEquals(0,unread(2));
        JsonNode m=data(call("GET","/api/chats/1/messages",2,null)).path("messageList").get(0);
        assertEquals("",m.path("content").asText()); assertEquals(1,m.path("withdraw").asInt());
        long old=send(1,2,"expired"); db.update("UPDATE chat_detailed SET time=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(121)),old);
        assertEquals(409,call("POST","/api/messages/"+old+"/withdraw",1,null).statusCode());
    }
    @Test void removingConversationDoesNotDeleteOtherSideHistory() throws Exception {
        send(1,2,"first"); data(call("DELETE","/api/chats/1",2,null)); assertEquals(0,unread(2));
        assertEquals(0,data(call("GET","/api/chats",2,null)).size());
        assertEquals(1,data(call("GET","/api/chats/2/messages",1,null)).path("messageList").size());
        send(1,2,"again"); assertEquals(1,data(call("GET","/api/chats",2,null)).size()); assertEquals(1,unread(2));
        data(call("POST","/api/unread/clear?column=whisper",2,null)); assertEquals(0,unread(2));
    }
    HttpResponse<String> internal(String path,String token,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("X-Message-Token",token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void internalNotificationsRequireSecretAndAreIdempotent() throws Exception {
        String body="{\"uid\":2,\"kind\":\"love\",\"eventId\":\"like-123\"}";
        assertEquals(403,internal("/internal/messages/notifications","wrong",body).statusCode());
        data(internal("/internal/messages/notifications","test-service-secret",body));
        data(internal("/internal/messages/notifications","test-service-secret",body));
        assertEquals(1,data(call("GET","/api/unread",2,null)).path("upVote").asInt());
        data(call("POST","/msg-unread/clear?column=love",2,null));
        assertEquals(0,data(call("GET","/api/unread",2,null)).path("upVote").asInt());
        assertEquals(400,call("POST","/api/unread/clear?column=unknown",2,null).statusCode());
    }
    @Test void concurrentSendsDoNotLoseUnreadCounts() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(6);
        try {
            List<Future<Long>> jobs=new ArrayList<>(); for(int i=0;i<18;i++) jobs.add(workers.submit(()->send(1,2,"parallel")));
            Set<Long> ids=new HashSet<>(); for(Future<Long> job:jobs) ids.add(job.get(30,TimeUnit.SECONDS));
            assertEquals(18,ids.size()); assertEquals(18,unread(2));
        } finally { workers.shutdownNow(); }
    }
    class SocketClient implements WebSocket.Listener,AutoCloseable {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>(); final StringBuilder buffer=new StringBuilder();
        final CompletableFuture<Integer> closed=new CompletableFuture<>(); WebSocket socket;
        SocketClient() { socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(URI.create("ws://127.0.0.1:"+port+"/im"),this).join(); }
        @Override public void onOpen(WebSocket s) { s.request(1); }
        @Override public CompletionStage<?> onText(WebSocket s,CharSequence text,boolean last) { buffer.append(text); if(last){frames.add(buffer.toString()); buffer.setLength(0);} s.request(1); return null; }
        @Override public CompletionStage<?> onClose(WebSocket s,int status,String reason) { closed.complete(status); return null; }
        void send(String text) { socket.sendText(text,true).join(); }
        JsonNode await(String type) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<end) { String value=frames.poll(1,TimeUnit.SECONDS); if(value!=null) { JsonNode frame=json.readTree(value); if(type.equals(frame.path("type").asText())) return frame; } }
            throw new AssertionError("Missing WebSocket event "+type);
        }
        void login(int uid) throws Exception { send("{\"code\":100,\"content\":\"Bearer test-"+uid+"\"}"); assertEquals(uid,await("connected").path("data").path("uid").asInt()); }
        @Override public void close() { socket.abort(); }
    }
    @Test void webSocketAuthenticatesSendsWithdrawsAndDisconnects() throws Exception {
        try(SocketClient a=new SocketClient();SocketClient b=new SocketClient();SocketClient secondB=new SocketClient()) {
            a.login(1); b.login(2); secondB.login(2);
            a.send("{\"code\":101,\"postId\":3,\"acceptId\":2,\"content\":\"真实 WebSocket\"}");
            JsonNode received=b.await("message").path("data").path("detail"); assertEquals(1,received.path("postId").asInt());
            assertEquals(received.path("id"),secondB.await("message").path("data").path("detail").path("id"));
            long id=received.path("id").asLong(); a.await("ack");
            a.send("{\"code\":102,\"id\":"+id+"}"); assertEquals("撤回",b.await("message").path("data").path("type").asText());
            data(internal("/internal/messages/users/2/disconnect","test-service-secret","{}"));
            assertEquals(1008,b.closed.get(5,TimeUnit.SECONDS)); assertEquals(1008,secondB.closed.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void webSocketRejectsUnauthenticatedAndRevokedTokens() throws Exception {
        try(SocketClient a=new SocketClient()) {
            a.send("{\"code\":101,\"acceptId\":2,\"content\":\"denied\"}"); assertEquals(401,a.await("error").path("data").path("code").asInt());
            assertEquals(1008,a.closed.get(5,TimeUnit.SECONDS));
        }
        try(SocketClient a=new SocketClient()) {
            a.login(1); revoked.add("Bearer test-1"); a.send("{\"code\":101,\"acceptId\":2,\"content\":\"denied\"}");
            assertEquals(401,a.await("error").path("data").path("code").asInt()); assertEquals(1008,a.closed.get(5,TimeUnit.SECONDS));
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_detailed",Integer.class));
    }
}
