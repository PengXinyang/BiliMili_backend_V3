package com.bilimili.message;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 从用户服务迁出的消息业务。写操作串行并使用数据库事务，推送发生在提交之后。2026.11.26 */
@Service
public class ChatService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RealtimeHub hub;
    private final UserDirectory users;
    private static final Set<String> KINDS = Set.of("reply", "at_num", "up_vote", "system_message", "dynamic", "message");
    private final RowMapper<ChatMessage> messageMapper = (r, n) -> new ChatMessage(r.getLong("id"), r.getInt("post_id"), r.getInt("accept_id"),
            r.getInt("withdraw") == 1 ? "" : r.getString("content"), r.getInt("withdraw"), r.getTimestamp("time").toInstant(), r.getInt("read_flag") == 1);
    public ChatService(JdbcTemplate db, PlatformTransactionManager manager, RealtimeHub hub, UserDirectory users) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.hub = hub; this.users = users;
    }
    private Timestamp now() { return Timestamp.from(Instant.now()); }
    private void pair(int uid, int peer, String token) {
        if (uid == peer) throw new ApiException(400, "不能向自己发送私聊消息");
        users.user(peer, token);
    }
    private void ensureChat(int peer, int owner) {
        try { db.update("INSERT INTO chat(post_id,accept_id,latest_time) VALUES(?,?,?)", peer, owner, now()); }
        catch (DuplicateKeyException ignored) { }
    }
    private void ensureUnread(int uid) {
        try { db.update("INSERT INTO message_unread(uid) VALUES(?)", uid); } catch (DuplicateKeyException ignored) { }
    }
    private void recount(int uid) {
        ensureUnread(uid);
        db.update("UPDATE message_unread SET message=(SELECT COALESCE(SUM(unread_num),0) FROM chat WHERE accept_id=?) WHERE uid=?", uid, uid);
    }
    public synchronized Map<String, Object> open(int uid, int peer, String token) {
        pair(uid, peer, token);
        return tx.execute(s -> { ensureChat(peer, uid); db.update("UPDATE chat SET is_deleted=0,latest_time=? WHERE post_id=? AND accept_id=?", now(), peer, uid); return conversation(uid, peer); });
    }
    public synchronized ChatMessage send(int uid, int peer, String content, String token) {
        pair(uid, peer, token);
        if (content == null || content.isBlank() || content.length() > 500) throw new ApiException(400, "消息内容长度须为 1 至 500 个字符");
        return tx.execute(s -> {
            ensureChat(peer, uid); ensureChat(uid, peer);
            KeyHolder key = new GeneratedKeyHolder(); Timestamp time = now();
            db.update(c -> { PreparedStatement p = c.prepareStatement("INSERT INTO chat_detailed(post_id,accept_id,content,time) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                p.setInt(1, uid); p.setInt(2, peer); p.setString(3, content); p.setTimestamp(4, time); return p; }, key);
            long id = Objects.requireNonNull(key.getKey()).longValue();
            db.update("UPDATE chat SET is_deleted=0,latest_time=? WHERE post_id=? AND accept_id=?", time, peer, uid);
            db.update("UPDATE chat SET is_deleted=0,latest_time=?,unread_num=unread_num+1 WHERE post_id=? AND accept_id=?", time, uid, peer);
            recount(peer);
            ChatMessage message = find(id);
            Map<String, Object> event = Map.of("type", "接收", "detail", message);
            hub.emit(uid, "message", event); hub.emit(peer, "message", event);
            return message;
        });
    }
    private ChatMessage find(long id) {
        List<ChatMessage> rows = db.query("SELECT * FROM chat_detailed WHERE id=?", messageMapper, id);
        if (rows.isEmpty()) throw new ApiException(404, "消息不存在"); return rows.get(0);
    }
    private void member(ChatMessage m, int uid) { if (m.postId() != uid && m.acceptId() != uid) throw new ApiException(403, "不能操作其他用户的消息"); }
    public List<Map<String, Object>> conversations(int uid, int offset, int limit) {
        bounds(offset, limit);
        List<Integer> peers = db.query("SELECT post_id FROM chat WHERE accept_id=? AND is_deleted=0 ORDER BY latest_time DESC,id DESC LIMIT ? OFFSET ?", (r,n)->r.getInt(1), uid, limit, offset);
        List<Map<String,Object>> result = new ArrayList<>();
        for (int peer : peers) result.add(conversation(uid, peer)); return result;
    }
    private Map<String, Object> conversation(int uid, int peer) {
        return db.queryForObject("SELECT * FROM chat WHERE accept_id=? AND post_id=?", (r,n) -> {
            Map<String,Object> row = new LinkedHashMap<>(); row.put("id", r.getLong("id")); row.put("peerId", peer);
            row.put("unreadNum", r.getInt("unread_num")); row.put("latestTime", r.getTimestamp("latest_time").toInstant());
            List<ChatMessage> latest = historyRows(uid, peer, 0, 1); row.put("lastMessage", latest.isEmpty() ? null : latest.get(0)); return row;
        }, uid, peer);
    }
    public Map<String,Object> history(int uid, int peer, int offset, int limit) {
        bounds(offset,limit);
        if (peer <= 0 || peer == uid) throw new ApiException(400, "会话对象无效");
        List<ChatMessage> rows = historyRows(uid,peer,offset,limit+1);
        boolean more = rows.size() > limit;
        return Map.of("messageList", more ? rows.subList(0,limit) : rows, "moreMessage", more);
    }
    private List<ChatMessage> historyRows(int uid,int peer,int offset,int limit) {
        return db.query("SELECT * FROM chat_detailed WHERE (post_id=? AND accept_id=? AND post_del=0) OR (post_id=? AND accept_id=? AND accept_del=0) ORDER BY id DESC LIMIT ? OFFSET ?", messageMapper, uid,peer,peer,uid,limit,offset);
    }
    private void bounds(int offset,int limit) { if (offset<0 || offset>100000 || limit<1 || limit>100) throw new ApiException(400,"分页参数无效"); }
    public synchronized void read(int uid,int peer) {
        tx.executeWithoutResult(s -> {
            db.update("UPDATE chat_detailed SET read_flag=1 WHERE post_id=? AND accept_id=?", peer,uid);
            db.update("UPDATE chat SET unread_num=0 WHERE post_id=? AND accept_id=?",peer,uid);
            recount(uid); hub.emit(uid,"message",Map.of("type","已读","peerId",peer));
        });
    }
    public synchronized void deleteConversation(int uid,int peer) {
        tx.executeWithoutResult(s -> { read(uid,peer); db.update("UPDATE chat SET is_deleted=1 WHERE post_id=? AND accept_id=?",peer,uid); hub.emit(uid,"message",Map.of("type","移除","peerId",peer)); });
    }
    public synchronized void deleteMessage(int uid,long id) {
        tx.executeWithoutResult(s -> {
            ChatMessage m=find(id); member(m,uid);
            if (m.postId()==uid) db.update("UPDATE chat_detailed SET post_del=1 WHERE id=?",id);
            else {
                db.update("UPDATE chat_detailed SET accept_del=1,read_flag=1 WHERE id=?",id);
                if (!m.read()) decrement(m.acceptId(),m.postId());
            }
            hub.emit(uid,"message",Map.of("type","删除","id",id));
        });
    }
    private void decrement(int owner,int peer) {
        db.update("UPDATE chat SET unread_num=CASE WHEN unread_num>0 THEN unread_num-1 ELSE 0 END WHERE post_id=? AND accept_id=?",peer,owner); recount(owner);
    }
    public synchronized void withdraw(int uid,long id) {
        tx.executeWithoutResult(s -> {
            ChatMessage m=find(id);
            if(m.postId()!=uid) throw new ApiException(403,"只能撤回自己的消息");
            if(m.withdraw()==1) return;
            if(Instant.now().isAfter(m.time().plusSeconds(120))) throw new ApiException(409,"发送超过两分钟，不能撤回");
            db.update("UPDATE chat_detailed SET withdraw=1,read_flag=1 WHERE id=?",id);
            if(!m.read()) decrement(m.acceptId(),m.postId());
            Map<String,Object> event=Map.of("type","撤回","id",id,"sendId",uid,"acceptId",m.acceptId());
            hub.emit(uid,"message",event); hub.emit(m.acceptId(),"message",event);
        });
    }
    public synchronized Map<String,Object> unread(int uid) {
        ensureUnread(uid);
        return db.queryForObject("SELECT * FROM message_unread WHERE uid=?", (r,n)->Map.of("uid",uid,"reply",r.getInt("reply"),"atNum",r.getInt("at_num"),"upVote",r.getInt("up_vote"),"systemMessage",r.getInt("system_message"),"message",r.getInt("message"),"dynamic",r.getInt("dynamic")),uid);
    }
    public String kind(String value) {
        if(value==null) throw new ApiException(400,"消息分类不能为空");
        String kind=switch(value){case "love" -> "up_vote"; case "at" -> "at_num"; case "system" -> "system_message"; case "whisper" -> "message"; default -> value;};
        if(!KINDS.contains(kind)) throw new ApiException(400,"未知的消息分类"); return kind;
    }
    public synchronized void clear(int uid,String value) {
        String column=kind(value);
        tx.executeWithoutResult(s -> {
            ensureUnread(uid);
            if(column.equals("message")) {
                db.update("UPDATE chat_detailed SET read_flag=1 WHERE accept_id=?",uid);
                db.update("UPDATE chat SET unread_num=0 WHERE accept_id=?",uid);
            }
            db.update("UPDATE message_unread SET `"+column+"`=0 WHERE uid=?",uid);
            hub.emit(uid,column,Map.of("type","全部已读"));
        });
    }
    public synchronized void clearAll(int uid) { tx.executeWithoutResult(s -> { for(String kind:KINDS) clear(uid,kind); }); }
    public synchronized void notify(int uid,String value,String eventId) {
        String column=kind(value);
        if(uid<=0 || eventId==null || eventId.isBlank() || eventId.length()>128 || column.equals("message")) throw new ApiException(400,"通知参数无效");
        tx.executeWithoutResult(s -> {
            try { db.update("INSERT INTO message_event(event_id,uid,kind,created_at) VALUES(?,?,?,?)",eventId,uid,column,now()); }
            catch(DuplicateKeyException ex) { return; }
            ensureUnread(uid);
            db.update("UPDATE message_unread SET `"+column+"`=`"+column+"`+1 WHERE uid=?",uid);
            hub.emit(uid,value,Map.of("type","接收","eventId",eventId));
        });
    }
}
