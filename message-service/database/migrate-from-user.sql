-- MySQL 8；迁移前停写旧服务并备份，先启动一次新服务创建空表，再停止新服务。
-- 本脚本只从 USERSERVICE 复制，不删除或更新原数据库；目标必须完全为空。
-- 数据库名称不同，请先修改 USERSERVICE / MESSAGESERVICE。
USE MESSAGESERVICE;
DELIMITER //
CREATE PROCEDURE migrate_bilimili_messages_20261126()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  IF (SELECT COUNT(*) FROM chat)+(SELECT COUNT(*) FROM chat_detailed)+(SELECT COUNT(*) FROM message_unread)+(SELECT COUNT(*) FROM message_event)>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Target message database must be empty';
  END IF;
  INSERT INTO chat(id,post_id,accept_id,is_deleted,unread_num,latest_time)
    SELECT id,post_id,accept_id,is_deleted,unread_num,latest_time FROM USERSERVICE.chat;
  INSERT INTO chat_detailed(id,post_id,accept_id,content,post_del,accept_del,withdraw,time,status,read_flag)
    SELECT id,post_id,accept_id,content,post_del,accept_del,withdraw,time,status,1 FROM USERSERVICE.chat_detailed;
  INSERT INTO message_unread(uid,reply,at_num,up_vote,system_message,message,`dynamic`)
    SELECT uid,reply,at_num,up_vote,system_message,message,`dynamic` FROM USERSERVICE.message_unread;
  -- 旧表只有会话级未读数。按每个接收方会话的最新 N 条可见消息重建逐条已读标记。
  UPDATE chat_detailed d JOIN (
    SELECT ranked.id FROM (
      SELECT id,post_id,accept_id,ROW_NUMBER() OVER(PARTITION BY post_id,accept_id ORDER BY id DESC) AS rn
      FROM chat_detailed WHERE withdraw=0 AND accept_del=0
    ) ranked JOIN chat c ON c.post_id=ranked.post_id AND c.accept_id=ranked.accept_id
    WHERE ranked.rn<=c.unread_num
  ) pending ON pending.id=d.id SET d.read_flag=0;
  UPDATE chat c SET unread_num=(SELECT COUNT(*) FROM chat_detailed d WHERE d.post_id=c.post_id AND d.accept_id=c.accept_id AND d.read_flag=0);
  INSERT INTO message_unread(uid) SELECT DISTINCT accept_id FROM chat WHERE accept_id NOT IN (SELECT uid FROM message_unread);
  UPDATE message_unread u SET message=(SELECT COALESCE(SUM(c.unread_num),0) FROM chat c WHERE c.accept_id=u.uid);
  COMMIT;
END//
DELIMITER ;
CALL migrate_bilimili_messages_20261126();
DROP PROCEDURE migrate_bilimili_messages_20261126;
