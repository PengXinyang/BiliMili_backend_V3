# BiliMili message-service

消息模块从 `user-service` 拆出后的独立 Spring Boot 2.7.15 / Java 17 服务。HTTP 与 WebSocket 共用 8084 端口。用户、视频、文章、消息构成四个业务服务，网关仍为统一入口。

## 迁移范围

- 消息服务拥有 `chat`、`chat_detailed`、`message_unread` 表，以及通知去重表 `message_event`。
- 私聊发送、两分钟内撤回、双方各自删除、历史分页、会话创建与移除、已读和未读计数均在本服务实现。
- 用户服务中的 Netty IMServer、聊天 Mapper/实体/Service、未读 Controller 已移除。
- 用户注册不再直接写消息表；本服务按需创建未读记录。评论和点赞通过 `MessageServiceClient` 发送通知，退出登录调用消息服务断开连接。
- 新增用户服务身份接口 `/internal/message/session` 和 `/internal/message/users/{uid}`，受原有 JWT 过滤器保护。只返回 UID、昵称与头像，不返回密码或完整用户实体。
- 原 Netty 7071 端口改为由 Spring 生命周期管理的 WebSocket `/im`，避免旧 IMServer 未启动、重复配置 pipeline 和资源关闭问题。旧客户端需要修改连接地址；100/101/102 指令保留。
- 本模块独立继承 Boot 父 POM，并由根 POM 聚合构建，避免继承原工程的 OSS、Elasticsearch、Nacos、Druid 等非消息依赖。

## 构建与自动验证

在项目根目录执行（需要 JDK 17+、Maven 3.9+）：

```powershell
mvn -pl message-service test
mvn -pl message-service package
mvn -pl user-service -am -DskipTests package
```

集成测试使用真实嵌入式 HTTP/WebSocket 服务和 H2 数据库；只有 user-service 的身份接口由测试 HTTP 桩替代。测试覆盖认证、接收方不存在、发送者伪造、历史分页、单侧删除、越权拒绝、撤回时限、未读计数、并发发送、通知去重、多连接推送及失效登录连接。测试账号和 token 仅存在于测试代码，正式服务不接受测试 token。

## 本地运行

先恢复并启动原用户服务的 MySQL、Redis 和 JWT 配置。消息服务不直接读取用户库或登录 Redis，不持有 JWT 签名密钥。

双方进程必须配置相同的 `MESSAGE_INTERNAL_TOKEN`（自行设置一个随机服务间密钥，不提交到仓库）。用户服务配置 `MESSAGE_SERVICE_URL` 指向消息服务；消息服务配置 `USER_SERVICE_URL` 为用户服务的**直接地址**，不要指回消息服务或形成网关循环。

消息服务可先使用持久化 H2 文件运行，减少联调时的数据库准备工作：

```powershell
$env:USER_SERVICE_URL = 'http://localhost:8081' # 改为原用户服务实际地址
$env:MESSAGE_INTERNAL_TOKEN = '<与用户服务相同的随机密钥>'
java -jar message-service/target/message-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

H2 文件位于启动工作目录的 `data/message*`，关闭服务后数据仍保留。健康检查：`http://localhost:8084/actuator/health`。调试页面：`http://localhost:8084/`；使用两个已在原用户服务登录的账号及其 Bearer token 进行测试。页面不保存 token。

使用 MySQL 时，执行 `database/create-database.sql`，为应用账号分配目标库权限，再配置：

```powershell
$env:MESSAGE_DB_URL = 'jdbc:mysql://localhost:3306/MESSAGESERVICE?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
$env:MESSAGE_DB_USER = 'message'
$env:MESSAGE_DB_PASSWORD = '<数据库密码>'
java -jar message-service/target/message-service-0.0.1-SNAPSHOT.jar
```

启动仅执行 `CREATE TABLE IF NOT EXISTS`，不会删除表。不要将消息数据源指向旧 USERSERVICE 库；旧表不含新 `read_flag` 字段，应按下节复制到独立库。

`MESSAGE_PORT` 可覆盖端口；`MESSAGE_ALLOWED_ORIGINS` 可设置逗号分隔的 WebSocket 浏览器来源。默认允许 localhost 的 3000、5173、8080 端口，同源访问也可用。内置调试页面和经网关同源的调用无需另配跨域 HTTP。

## 历史数据迁移

`database/migrate-from-user.sql` 面向 MySQL 8，需由有权读取旧库、写入新库及创建存储过程的账号执行：

1. 备份旧库，停止旧服务的消息写入。
2. 创建 MESSAGESERVICE 库，启动一次新消息服务建表，再停止新服务。
3. 确认源库名为 USERSERVICE，目标四张表为空；名称不同时先修改 SQL。
4. 执行迁移 SQL，核对记录数，再启动新服务。

脚本在事务中复制记录和原 ID；发现目标已有数据会拒绝执行。不会删除或修改旧库。旧数据只有会话级未读数，脚本将每个会话最新的 N 条可见、未撤回消息标记为未读，再统一重算计数。这是旧数据粒度限制下的重建方式，不是原先逐条已读状态的精确恢复。迁移失败后事务回滚；若存储过程仍存在，排查后手动删除该过程再重试。该脚本不会自动执行。

## HTTP 接口

以下 `/api` 接口均携带 `Authorization: Bearer <原登录 token>`。身份来自用户服务，不能通过请求参数指定当前用户。成功响应为 `{code:200,message:"OK",data:...}`；错误同时使用真实 HTTP 400/401/403/404/409/503 状态。

| 方法 | 路径 | 内容 |
|---|---|---|
| POST | `/api/chats/{peer}` | 创建/重新打开与对方的会话 |
| GET | `/api/chats?offset=0&limit=20` | 我的会话列表及最新消息 |
| POST | `/api/messages` | JSON：`{"acceptId":2,"content":"你好"}` |
| GET | `/api/chats/{peer}/messages?offset=0&limit=20` | 按消息 ID 倒序分页，返回 `messageList`、`moreMessage` |
| POST | `/api/chats/{peer}/read` | 该会话已读，清除自己的未读 |
| DELETE | `/api/chats/{peer}` | 从自己的列表移除会话，不删除对方历史 |
| DELETE | `/api/messages/{id}` | 仅从当前参与者的历史中删除 |
| POST | `/api/messages/{id}/withdraw` | 发送者两分钟内撤回；查询不再返回撤回内容 |
| GET | `/api/unread` | 当前用户全部消息分类未读数 |
| POST | `/api/unread/clear?column=message` | 清除一类未读 |
| POST | `/api/unread/clear-all` | 清除全部类别未读 |

消息最长 500 个 Java 字符；分页 limit 为 1–100，offset 为 0–100000。移除会话后新消息会重新显示会话。收到实时推送不等同已读，客户端确认展示后须发送已读操作。

分类支持 `reply`、`at_num`、`up_vote`、`system_message`、`dynamic`、`message`，兼容 `at`、`love`、`system`、`whisper` 别名。保留 `/msg-unread/all`、`clear`、`clear-all`、`clear-batch`、`column` 及 `/user/msg-unread/...` 别名。原未读 Controller 中未实现的时间范围、提醒偏好和任意增减/重置接口不再返回虚假成功，当前不提供这些 API；通知增量通过受保护的内部接口生成。

## WebSocket 协议

直连 `ws://localhost:8084/im`；经网关连接 `/message/im` 或兼容 `/im`。连接后 15 秒内认证，服务每 10 秒清理未认证/失效连接；每次命令和向用户推送前也会校验登录状态。

```json
{"code":100,"content":"Bearer <登录 token>"}
{"code":101,"acceptId":2,"content":"你好"}
{"code":102,"id":123}
{"code":103,"peerId":2}
{"code":104}
```

分别表示认证、发送、撤回、已读、心跳。服务端事件格式保持 `type/time/data`：`connected` 为认证确认，`message` 为消息变化，`ack` 为指令完成，`pong` 为心跳，`error` 包含错误码与原因。发送事件的 `data.detail` 包含消息 ID；客户端用 ID 合并多连接推送与历史记录。发送成功前连接中断时，应查询历史确认结果，当前不提供发送请求级的恰好一次语义。

## 服务间通知与网关

用户服务使用 `X-Message-Token` 访问以下接口，内部密钥为空时接口拒绝请求：

- `POST /internal/messages/notifications`：`{"uid":2,"kind":"reply","eventId":"业务事件唯一标识"}`。同一 eventId 不重复增加未读。
- `POST /internal/messages/users/{uid}/disconnect`：断开该用户的全部消息连接。

网关新增 `/message/**` HTTP 路由和 WebSocket 路由，以及 `/user/msg-unread/**` 的兼容路由。网关配置 `MESSAGE_SERVICE_URL`、`MESSAGE_SERVICE_WS_URL`；在容器/Kubernetes 中可分别设为 `http://message-service:8084`、`ws://message-service:8084`，用户服务也使用集群内地址。新服务直接通过地址通信，不要求额外注册 Nacos。

## 运行边界

- 当前消息服务部署**单副本**。数据库事务和本进程写操作串行化保证计数一致；在线连接表也属于单进程。扩多副本前须补充跨实例消息分发和分布式并发控制。
- 私聊记录和未读数落库后再推送；离线或推送失败通过历史接口补取。评论/点赞事件由原业务同步或异步发起，目前没有跨服务事务或可靠 outbox，调用失败需要上游补偿，不宣称通知一定送达。
- H2 测试不替代真实 MySQL 数据迁移、原用户服务 JWT/Redis 联调或 Kubernetes 验证；这几项需在对应环境中执行。
- 构建后的可执行 JAR 可通过本目录 Dockerfile 制作镜像；本次拆分没有自动修改任何已运行数据库，也没有部署集群。

实现参考：[Spring WebSocket](https://docs.spring.io/spring-framework/docs/5.3.30/reference/html/web.html#websocket-server)、[Spring 事务同步](https://docs.spring.io/spring-framework/docs/5.3.30/reference/html/data-access.html#transaction-event)。
