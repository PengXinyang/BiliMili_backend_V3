# BiliMili 本地 Kubernetes 部署

2026-11-27 已在 Docker Desktop 的 kind 集群中完成实际部署。上下文为 `docker-desktop`，Kubernetes v1.36.1，单节点 `desktop-control-plane`，独立命名空间 `bilimili`。包含原客户端和管理端的 11 个 Pod 均为 Ready；5 个 PVC 已绑定。

## 提交包与本机材料

此目录已整理为源码提交包：依赖、构建产物、Git 元数据、`deploy/.local/`、`deploy/.tools/` 及独立截图均未包含。报告截图已经内嵌 Word。下文的凭据、快照和截图路径是部署与验证脚本生成的位置；新环境运行相应脚本后会生成文件。本机既有实验的凭据与验收记录保存在作业目录外的备份中，说明见 `../../提交说明.md`。

## 访问与重新打开

- 原客户端：http://localhost:8787
- 原管理端：http://localhost:8788
- 后端辅助验证页面：http://localhost:18080/media/demo/index.html
- 实机验收记录：http://localhost:18080/media/demo/evidence.html
- 网关健康检查：http://localhost:18080/actuator/health
- Elasticsearch：http://localhost:19200/_cluster/health
- Nacos 配置与服务发现 API：http://localhost:18848/nacos/

演示账号为 `demo_alice`、`demo_bob`（UID 1、2）。随机生成的演示密码保存在 `deploy/.local/demo-accounts.json`，不会写入 Git 或截图。独立管理员为 `demo_admin`（UID 3），密码保存在 `deploy/.local/frontend-accounts.json`。8787、8788 分别运行原 Vue 客户端和管理端；18080 下的实验页面保留用于后端诊断。

重新启动电脑或 Docker Desktop 后，等待 Kubernetes 运行，在仓库根目录执行：

```powershell
kubectl --context docker-desktop get pods -n bilimili
./deploy/start-local.ps1 -Restart
```

脚本在后台启动仅绑定 127.0.0.1 的五个端口转发（未部署前端时跳过其端口）。Pod 被重建后，旧的端口转发可能结束，重新执行此命令即可。集群状态页面使用带时间戳的采集快照，内容查询及私聊页直接访问当前服务。

## 部署组成

| 服务 | 容器端口 | 职责 |
|---|---:|---|
| client-frontend | 8787 | 原 Vue 客户端，Nginx 静态托管与 HTTP/WebSocket 代理 |
| admin-frontend | 8788 | 原 Vue 管理端，独立管理员登录与内容审核查询 |
| gateway-service | 8080 | Spring Cloud Gateway，统一 HTTP/WebSocket 入口 |
| user-service | 8081 | 注册登录、用户资料、供消息服务验证身份、本地媒体读取 |
| video-service | 8082 | 视频元数据与用户资料聚合 |
| article-service | 8083 | 文章元数据、PVC 正文和作者资料聚合 |
| message-service | 8084 | 独立私聊、未读、已读、撤回、消息持久化 |
| mysql | 3306 | 四个独立业务数据库，各自应用账号 |
| redis | 6379 | 登录状态与缓存，启用 AOF |
| nacos | 8848 / 9848 / 9849 | 配置管理、服务注册与发现 |
| elasticsearch | 9200 | 用户和视频索引，单节点、单分片、零副本 |

本次四个业务服务使用 `lab-20261127` 标签；网关和两个前端使用 `frontend-20261127` 标签。调试时生成的 local、local2、local3、local4 标签已删除。前四个服务注册到 Nacos；独立消息服务使用 Kubernetes Service DNS。Nacos 是注册与配置中心，实际网关仍为 Spring Cloud Gateway。

持久化卷为 `mysql-data`、`redis-data`、`es-data`、`nacos-data`、`media-data`，使用 Docker Desktop 默认的 `standard` StorageClass。媒体卷由三个业务服务共享；视频上传临时分片使用 emptyDir。数据库、索引、配置和演示媒体均属于这套独立环境，没有使用历史云服务数据。

## 本次代码与配置修正

1. 修正父 POM 的错误输出文件名，补齐业务模块可执行 JAR 打包和 Dockerfile。
2. 移除用户、视频模块重复注册的 Feign token 拦截器。公开作者查询使用只返回 UserDTO 的 `/user/public/{uid}`，原账户凭据接口仍由网关隔离。
3. 增加 `local-storage` Profile，通过 PVC 保存媒体与文章；不启用该 Profile 时仍使用原 OSS 实现。网关新增 `/media/**` 路由。
4. 配置 Nacos、数据库、Redis、ES 的集群内地址，并通过 Secret 注入数据库密码及服务间认证令牌。
5. 数据库初始化脚本显式设置 UTF-8，避免中文分类数据按错误编码导入。提供两个普通演示账号、一个独立管理员、一条视频、一篇文章以及测试产生的私聊记录。
6. 为原客户端补齐私聊页、文章页、认证 WebSocket、未读同步及断线重连；修正管理端文章 API、分类字段、状态切换及成功提示。
7. 修正网关的收藏夹查询与播放状态路由，增加路由测试；配置两个前端的同源 Nginx 代理和明确的 WebSocket Origin。
8. 提供独立的部署生成、初始化、验收和截图脚本。

## 从代码重新部署

前提：Docker Desktop 运行，kind 集群已创建；本机有 Java 17 或更新版本、Maven、Python、Node.js/npm、kubectl；镜像拉取网络可用。建议至少预留 5 GB 磁盘空间供重新构建。依赖版本记录在 `requirements.txt`。

```powershell
python -m pip install -r deploy/requirements.txt
./deploy/deploy-local.ps1 -Maven 'mvn.cmd'
```

本机 Maven 也可使用此前作业的工具：

```powershell
./deploy/deploy-local.ps1 -Maven 'D:/ziliao/服务计算基础/服务计算期末作业/备份/Homework-1/.tools/apache-maven-3.9.9/bin/mvn.cmd'
```

重新构建默认生成含日期和时间的新标签。两个前端目录与后端目录应并列放置，目录名分别为 `hhjt-frontend-client-master`、`hhjt-frontend-admin-master`。后端部署就绪后执行：

```powershell
python deploy/prepare_frontend_demo.py
./deploy/deploy-frontends.ps1
```

前端脚本先拉取 Nginx 镜像，然后逐个执行 `npm ci`、`npm run build`、镜像构建和 Kubernetes 部署，最后启动端口转发。前端 Dockerfile 使用预先构建的 `dist`；请勿跳过静态构建后直接拿旧产物制作镜像。`-SkipBuild -ImageTag frontend-20261127` 仅用于复用本次已经存在的两个前端镜像。

若只做开发调试，先停止对应前端的端口转发，再在前端目录运行 `npm ci`、`npm run serve`；开发代理仍访问本机 18080 网关。正式展示使用集群内 Nginx 提供的构建产物。

脚本依次生成配置、部署基础设施、发布 Nacos 配置和 ES 索引、部署应用、复制媒体、创建演示账号并执行验收。`render_local.py` 会复用 `.local/credentials.json` 中已有密码；不要在保留数据库卷时删除这个文件再生成不同密码。SQL 初始化只在空 MySQL 数据目录首次启动时执行。

`deploy/.local/`、`deploy/.tools/` 和采集快照均被 Git 忽略；生成的基础设施 JSON 包含 Secret，不应作为公开提交材料。可提交的部署代码包括渲染脚本、Nacos 非敏感配置、应用 YAML、Dockerfile 和验证页面。

## 已执行的验证

此前原有 29 项测试与新增的 2 项本地文件存储测试通过。本次前端适配新增 1 项网关路由测试，网关模块的 9 项测试全部通过；两套前端生产构建成功。

`verify_local.py --persistence` 在真实集群上完成 11 项验收：双账号登录、鉴权及内部接口隔离、视频作者聚合、匿名公开访问、文章正文与作者、视频 Range 请求、ES 检索、双用户 WebSocket 投递、历史与已读、发送者撤回、消息 Pod 重建后保留历史。

```powershell
python deploy/verify_local.py --persistence
python deploy/verify_local.py --snapshot
```

验收脚本会用演示账号发送测试消息，并在指定 `--persistence` 时重建消息服务 Pod；不会删除数据库。原始记录写入 `deploy/.local/verification.json`，快照写入 `deploy/demo/cluster.json`。快照含采集时刻，不能代表以后任意时刻的状态。

原客户端和管理端的 14 项浏览器验收通过，覆盖双账号登录、视频和文章查询、空消息限制、发送确认、未读与已读、实时回复、撤回同步、刷新恢复、断线重连、管理端登录及两类审核查询；检查过程中没有未捕获的浏览器异常或失败的业务请求。

```powershell
python deploy/verify_local.py --snapshot
python deploy/capture_frontends.py
```

脚本使用本机 Chrome 和 Playwright，通过真实 UI 登录并发送少量演示消息，截图及去除凭据的记录保存在仓库外的 `../部署验证材料/`。新报告使用 05—09、11、12 号截图。01—04 号为此前后端辅助页面的记录，保留其原始采集时间，不代表前端部署后的 Pod 数量。

## 适用范围

这是单机课程实验，全部服务各一个副本；消息服务尚未适配跨副本实时连接路由。单节点、ReadWriteOnce 本地卷和单副本数据库不提供跨机器容灾。

Nacos 和 ES 在独立本地实验中关闭鉴权，服务均为 ClusterIP，本机转发仅绑定回环地址；Nacos 2.2.3 控制台在此模式可能只显示“未开启鉴权”的提示，配置和服务查询由 API 完成。本次未进行生产部署加固，也未验证原项目全部历史业务，例如审核状态变更的完整流程、全部上传流程、评论和推荐。

报告可依据截图与记录陈述上述已验证行为；不能将 Pod Ready 等同于所有业务功能均已通过。
