package com.bilimili.message;

import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
public class MessageController {
    private final ChatService chats;
    private final RealtimeHub hub;
    public MessageController(ChatService chats, RealtimeHub hub) { this.chats=chats; this.hub=hub; }
    private int uid(HttpServletRequest req) { return (Integer) req.getAttribute("message.uid"); }
    private Map<String,Object> ok(Object data) { return Map.of("code",200,"message","OK","data",data); }
    public record Send(@NotNull @Min(1) Integer acceptId, @NotBlank @Size(max=500) String content) { }
    public record Notice(@NotNull @Min(1) Integer uid, @NotBlank String kind, @NotBlank @Size(max=128) String eventId) { }
    @GetMapping("/api/chats")
    public Object list(HttpServletRequest r,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) { return ok(chats.conversations(uid(r),offset,limit)); }
    @PostMapping("/api/chats/{peer}")
    public Object open(HttpServletRequest r,@PathVariable int peer) { return ok(chats.open(uid(r),peer,r.getHeader("Authorization"))); }
    @GetMapping("/api/chats/{peer}/messages")
    public Object history(HttpServletRequest r,@PathVariable int peer,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) { return ok(chats.history(uid(r),peer,offset,limit)); }
    @PostMapping("/api/messages")
    public Object send(HttpServletRequest r,@Valid @RequestBody Send body) { return ok(chats.send(uid(r),body.acceptId(),body.content(),r.getHeader("Authorization"))); }
    @PostMapping("/api/chats/{peer}/read")
    public Object read(HttpServletRequest r,@PathVariable int peer) { chats.read(uid(r),peer); return ok(true); }
    @DeleteMapping("/api/chats/{peer}")
    public Object deleteChat(HttpServletRequest r,@PathVariable int peer) { chats.deleteConversation(uid(r),peer); return ok(true); }
    @DeleteMapping("/api/messages/{id}")
    public Object deleteMessage(HttpServletRequest r,@PathVariable long id) { chats.deleteMessage(uid(r),id); return ok(true); }
    @PostMapping("/api/messages/{id}/withdraw")
    public Object withdraw(HttpServletRequest r,@PathVariable long id) { chats.withdraw(uid(r),id); return ok(true); }
    @GetMapping({"/api/unread","/msg-unread/all","/user/msg-unread/all"})
    public Object unread(HttpServletRequest r) { return ok(chats.unread(uid(r))); }
    @PostMapping({"/api/unread/clear","/msg-unread/clear","/user/msg-unread/clear"})
    public Object clear(HttpServletRequest r,@RequestParam String column) { chats.clear(uid(r),column); return ok(true); }
    @PostMapping({"/api/unread/clear-all","/msg-unread/clear-all","/user/msg-unread/clear-all"})
    public Object clearAll(HttpServletRequest r) { chats.clearAll(uid(r)); return ok(true); }
    @PostMapping({"/msg-unread/clear-batch","/user/msg-unread/clear-batch"})
    public Object clearBatch(HttpServletRequest r,@RequestParam String[] columns) {
        for(String column:columns) chats.kind(column);
        for(String column:columns) chats.clear(uid(r),column); return ok(true);
    }
    @GetMapping({"/msg-unread/column","/user/msg-unread/column"})
    public Object column(HttpServletRequest r,@RequestParam String column) {
        String key=switch(chats.kind(column)) { case "at_num" -> "atNum"; case "up_vote" -> "upVote"; case "system_message" -> "systemMessage"; default -> chats.kind(column); };
        return ok(chats.unread(uid(r)).get(key));
    }
    @PostMapping("/internal/messages/notifications")
    public Object notice(@Valid @RequestBody Notice body) { chats.notify(body.uid(),body.kind(),body.eventId()); return ok(true); }
    @PostMapping("/internal/messages/users/{uid}/disconnect")
    public Object disconnect(@PathVariable int uid) { hub.disconnect(uid); return ok(true); }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> failure(ApiException ex) { return ResponseEntity.status(ex.status).body(Map.of("code",ex.status,"message",ex.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> invalid(MethodArgumentNotValidException ex) { return ResponseEntity.badRequest().body(Map.of("code",400,"message","请求参数无效")); }
}
