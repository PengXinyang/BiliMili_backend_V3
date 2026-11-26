# Kubernetes 部署适配说明

本次保留 Spring Cloud Gateway 和 Nacos，修正地址、配置加载、请求路径和鉴权。这里提供应用运行约定与 ConfigMap，尚不是一键启动完整系统的部署包。

## 服务名称与端口

| 模块 | 注册名称 / Kubernetes Service 名称 | 默认容器端口 |
|---|---|---|
| gateway_service | gateway-service | 8080 |
| user-service | user-service | 8081 |
| video-service | video-service | 8082 |
| article-service | article-service | 8083 |
| message-service | message-service | 8084 |

前四个服务通过 Nacos 注册与发现。消息服务保持独立，通过 Kubernetes Service DNS 与用户服务、网关通信；当前部署单副本。前三个业务服务及网关可用 SERVER_PORT 覆盖端口，消息服务用 MESSAGE_PORT。相应 Service.targetPort 必须同步设置。旧 Nacos 配置若仍指定 9090 等端口，会覆盖文件默认值，部署时需统一检查。

## 配置加载

- 本机默认 dev；容器设置 SPRING_PROFILES_ACTIVE=k8s。
- 各模块自带 bootstrap 配置，k8s 环境默认连接 nacos:8848，不再注册 127.0.0.1。POD_IP 未设置时由 Nacos 客户端检测本机地址；在 Kubernetes 中建议用 Downward API 注入实际 Pod IP。
- NACOS_SERVER_ADDR 使用 host:port，可通过环境变量覆盖；NACOS_NAMESPACE 填命名空间 ID，public 使用空字符串；NACOS_GROUP 默认为 DEFAULT_GROUP。
- NACOS_USERNAME、NACOS_PASSWORD 从 Secret 注入，仓库不提供密码。
- 使用现有 Spring Cloud Alibaba 2021 + bootstrap 加载机制；移除多余 Spring Cloud Config Server/Client 依赖及重复 config import。
- Nacos 中按服务名称发布 YAML 配置，例如 user-service-k8s.yaml、video-service-k8s.yaml、article-service-k8s.yaml、gateway-service-k8s.yaml，group、namespace 须与客户端相同。原工程的 MySQL、Redis、OSS、Elasticsearch 等配置仍须恢复或补齐，不应以空配置启动业务服务。
- 不要从旧配置中重新引入固定 discovery.ip、localhost 数据库地址、旧网关 routes 或 MVC 设置。业务父 POM 未全面重构，网关已单独继承 Boot，仅加载响应式网关所需依赖。

在同一 namespace 创建 Nacos Service，应用 Deployment 的容器可使用：

```yaml
envFrom:
  - configMapRef:
      name: bilimili-runtime
env:
  - name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
  - name: NACOS_USERNAME
    valueFrom:
      secretKeyRef:
        name: bilimili-secrets
        key: nacos-username
  - name: NACOS_PASSWORD
    valueFrom:
      secretKeyRef:
        name: bilimili-secrets
        key: nacos-password
```

k8s/runtime-config.yaml 是可复用 ConfigMap；部署时按目标 namespace 应用，示例中的 Secret 需自行创建。它不包含 Deployment、Service 或数据库初始化。

## 服务间调用

用户、视频、文章的 Feign 服务名统一为 user-service / video-service，默认使用 Nacos。USER_SERVICE_URL、VIDEO_SERVICE_URL 可选地设为直接 HTTP 地址，便于本地调试；不设置时通过注册中心发现。直接地址应指向目标业务服务，不能指向会再次转发回自己的网关。

消息服务在 k8s 环境默认访问 http://user-service:8081；用户服务默认访问 http://message-service:8084；网关的消息 HTTP 和 WebSocket 目标分别为 http://message-service:8084、ws://message-service:8084。可以分别通过 USER_SERVICE_URL、MESSAGE_SERVICE_URL、MESSAGE_SERVICE_WS_URL 覆盖。用户和消息服务仍需配置相同的 MESSAGE_INTERNAL_TOKEN。消息数据库默认主机 mysql，账号密码及数据库创建方式见 message-service/README.md。

文章服务补齐 Bearer token 透传，用户资料接口统一到实际存在的 /user/{uid}，使用 UserDTO 接收，不读取完整用户实体。此适配不代表原项目所有历史业务接口均已完成联调。

## 网关路径约定

原有“服务前缀 + 后端完整路径”仍保留，通常移除第一个服务前缀。后端自身以 /user 或 /article 开头的常用接口额外提供无重复前缀的入口。路由名称中的 public-paths 表示对外路径别名，不表示免登录。

| 网关路径 | 后端接收到的路径 |
|---|---|
| POST /user/account/login | /user/account/login |
| POST /user/user/account/login（兼容） | /user/account/login |
| GET /user/personal/info | /user/personal/info |
| GET /user/user/info/get-one?uid=1 | /user/info/get-one?uid=1 |
| GET /video/getone?vid=1 | /getone?vid=1 |
| POST /video/change/status | /video/change/status |
| GET /article/get?aid=1 | /article/get?aid=1 |
| GET /article/article/get?aid=1（兼容） | /article/get?aid=1 |
| GET /article/column/markdown | /column/markdown |
| GET /message/api/unread | /api/unread |
| GET /user/msg-unread/all | /msg-unread/all |
| WebSocket /message/im 或 /im | /im |

自动服务发现路由已关闭，避免出现另一组绕过明确路由的入口。网关在前缀重写后阻止转发 /internal、下游 /actuator、/druid 和返回账户凭据的 /user/getUserByName/**；服务间调用使用内部地址。网关自身 /actuator/health/liveness 和 /actuator/health/readiness 可用于探针。

## 鉴权与跨域

网关只使用 WebFlux Security，不创建登录 Session，不启用 Basic 或表单登录，保留原始 Authorization 头与下游 401/403。用户、视频和文章服务使用各自 JWT 过滤器与 Redis 登录状态校验，消息服务通过用户服务校验身份。网关放行路由到下游不等于业务接口允许匿名访问。

用户与视频服务旧的 /**、/user/** 全放行规则已移除；仅允许明确的注册、登录及公开 GET 查询。文章服务接入与其他业务服务一致的 JWT 过滤器。匿名请求受保护接口返回 401，无效 Bearer token 返回 403。管理操作的角色检查仍由业务层负责；这些修改不是对旧项目全部业务权限的审计。

WebSocket /im 允许握手，随后仍由消息服务执行 code=100 首帧认证。GATEWAY_ALLOWED_ORIGINS 和 MESSAGE_ALLOWED_ORIGINS 应设置为实际前端 Origin（协议、主机、端口），不要使用带凭据的通配来源。网关处理 OPTIONS 预检；跨域响应头去重，避免网关和下游同时添加。

## 验证

```powershell
mvn -pl gateway_service,user-service,video-service,article-service,message-service test
mvn -DskipTests package
```

网关测试启动真实 Spring Cloud Gateway 与 HTTP/WebSocket 测试后端，验证路径重写、登录转发、Authorization 透传、401/403、内部接口隔离、CORS、WebSocket 和健康探针。三个业务模块使用真实安全配置与 JWT 过滤器验证路由权限，Redis/JWT 工具使用测试替身。消息模块仍执行已有的 HTTP/WebSocket/H2 集成测试。

这些测试不启动真实 Nacos、MySQL、Redis，也不代表 Kubernetes 已经部署成功。下一阶段仍须搭建集群、准备业务配置及镜像，并验证真实登录和业务调用。
