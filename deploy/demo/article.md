# BiliMili 微服务部署实验

本实验将网关、用户、视频、文章与消息服务部署在本地 Kubernetes 集群。

- Nacos：注册与发现、配置管理
- MySQL：业务数据与私聊消息
- Redis：登录状态与缓存
- Elasticsearch：用户与视频搜索索引
- PVC：数据库、索引与媒体文件的持久化

本文通过 article-service 读取 PVC 文件，通过 user-service 获取作者资料，再由 Spring Cloud Gateway 返回。
