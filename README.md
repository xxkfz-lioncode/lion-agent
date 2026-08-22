# Lion Agent

基于 **Spring AI 2.0** 的智能体（Agent）服务：多模态对话、多轮记忆、知识库 RAG、语义缓存、工具调用（含 MCP 远程工具），并配套 Vue 3 管理前端。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言/框架 | Java 21、Spring Boot 4.0.7、Spring AI 2.0.0 |
| 数据 | MySQL 8 + MyBatis-Plus、Redis（Spring Data Redis）、Milvus 向量库 |
| 模型 | 通义千问 DashScope（OpenAI 兼容）：qwen 系列对话 + text-embedding-v3（1024 维） |
| 文档解析 | Apache Tika（pdf / doc / docx / txt / md） |
| 认证 | Sa-Token（自定义 HandlerInterceptor） |
| 工具 | Hutool（JSON 序列化）、Resilience4j（熔断）、springdoc-openapi（Swagger UI） |
| 远程工具 | MCP（本服务提供 streamable-http Server，同时以 SSE Client 接入第三方 MCP Server） |
| 可观测性 | Actuator + OpenTelemetry → Langfuse |
| 前端 | Vue 3 + Vite（`frontend/` 目录） |

## 快速开始

```bash
# 1. 环境要求：JDK 21、Maven 3.8+、Docker（可选，用于一键启动中间件）
#    - 方案 A（推荐）：用 Docker Compose 一键启动 MySQL / Redis / Milvus
#      docker compose up -d
#    - 方案 B：自行安装 MySQL 8 / Redis / Milvus（见下方「中间件前置环境」）

# 2. 初始化数据库（方案 B 需要；方案 A 已由 MySQL 容器自动执行 init.sql）
mysql -uroot -p lion_agent < src/main/resources/db/init.sql

# 3. 配置敏感信息：复制模板为 .env 并填入真实值
cp .env.example .env
# 必填：QWEN_API_KEY（通义千问）；DB_USERNAME / DB_PASSWORD / REDIS_PASSWORD 等见 .env.example
# 若中间件跑在 Docker 里（方案 A），MILVUS_HOST 等保持 localhost 即可

# 4. 启动后端（默认激活 dev 环境）
mvn spring-boot:run
# 或指定环境：mvn spring-boot:run -Dspring-boot.run.profiles=prod

# 5. 启动前端
cd frontend && npm install && npm run dev
```

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- 前端页面：`http://localhost:5173`

## 中间件前置环境（手动部署 / 版本要求）

不借助 Docker 时，需自行安装以下中间件并满足版本要求。

### 版本与端口总览

| 中间件 | 版本要求 | 端口 | 本项目用途 |
| --- | --- | --- | --- |
| MySQL | 8.0+ | 3306 | 业务数据：会话、消息、会话摘要、知识库与文档元数据 |
| Redis | 6.0+（建议 7.x） | 6379 | Sa-Token 会话、语义缓存、异步任务队列（`document:process`） |
| Milvus | **2.4.x**（须与 yml 索引/维度一致） | 19530（gRPC）/ 9091（健康检查） | 知识库 RAG 向量检索 + 语义缓存向量存储 |
| MinIO | 与 Milvus 配套版本 | 9000 / 9001 | Milvus 底层对象存储（standalone 依赖） |
| etcd | v3.5+ | 内部（2379） | Milvus 元数据存储（standalone 依赖） |

### MySQL 8

```bash
# 1. 安装 MySQL 8.0+，启动服务
# 2. 创建数据库与账号（账号密码对应 .env 的 DB_USERNAME / DB_PASSWORD）
mysql -uroot -p -e "
CREATE DATABASE IF NOT EXISTS lion_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'xxkfz'@'%' IDENTIFIED BY 'xxkfz';
GRANT ALL PRIVILEGES ON lion_agent.* TO 'xxkfz'@'%';
FLUSH PRIVILEGES;"
# 3. 执行初始化脚本（含建表）
mysql -uxxkfz -p lion_agent < src/main/resources/db/init.sql
```

### Redis

- **用途**：① Sa-Token 会话 token 存储；② 语义缓存（`lion.qa-cache` 相关 key）；③ 知识库异步任务队列（key 前缀 `lion:task:queue:`）。
- 若设置了密码，需在 `.env` 配置 `REDIS_PASSWORD`，且务必开启持久化（AOF 或 RDB），否则重启会丢队列任务与缓存。
- 验证：`redis-cli -a <密码> ping` 返回 `PONG`。
- Windows 本机可用 Memurai 或 WSL 运行；Linux 用 `apt install redis-server` / Docker。

### Milvus 向量数据库

- **架构**：Milvus standalone 依赖 etcd（元数据）+ MinIO（对象存储）两个进程，`docker-compose.yml` 已按官方架构拆分部署。
- **集合（collection）与维度对齐（关键）**：
  - `lion_agent_knowledge`：知识库文档向量，**1024 维，COSINE 度量**——必须与 DashScope `text-embedding-v3` 输出维度一致（已固化在 yml `embedding-dimension: 1024`）；
  - `lion_agent_qa_cache`：语义缓存专用集合（yml `lion.qa-cache.collection-name`），与知识库物理隔离。
  - 若手动预建过集合，需保证维度/度量一致，否则写入报错。
- 验证：`curl http://localhost:9091/healthz` 返回 `OK`；或用 Attu 管理台 `http://localhost:8000` 查看集合数据。
- 升级 Milvus 大版本前先确认索引类型（本项目使用 AUTOINDEX / COSINE）兼容。

### 验证命令汇总

```bash
# MySQL
mysql -uxxkfz -p -h127.0.0.1 -e "select 1"
# Redis
redis-cli -a 123456 ping        # 期望 PONG
# Milvus
curl http://localhost:9091/healthz   # 期望 OK
```

## Docker Compose 部署中间件

项目根目录提供 `docker-compose.yml`，一键编排全部基础设施（MySQL / Redis / etcd / MinIO / Milvus / Attu，即上述中间件的容器化等价方案）。

### 前置环境条件

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| Docker Engine | 20.10+ | `docker --version` 查看 |
| Docker Compose | v2 | `docker compose version` 查看 |
| 端口 | 见下表 | 启动前确认未被占用 |

启动前确认以下端口未被占用：`3306`(MySQL)、`6379`(Redis)、`19530/9091`(Milvus)、`9000/9001`(MinIO)、`8000`(Attu)。

### 服务清单

| 服务 | 镜像 | 对外端口 | 说明 |
| --- | --- | --- | --- |
| `mysql` | mysql:8.0 | 3306 | 业务库 `lion_agent`，首次启动自动执行 `init.sql` 建表 |
| `redis` | redis:7.4-alpine | 6379 | 会话 / 缓存 / 异步任务队列，AOF 持久化 |
| `etcd` | quay.io/coreos/etcd:v3.5.5 | —（内部） | Milvus 元数据存储 |
| `minio` | minio/minio | 9000/9001 | Milvus 对象存储，控制台 `http://localhost:9001`（minioadmin/minioadmin） |
| `milvus-standalone` | milvusdb/milvus:v2.4.17 | 19530/9091 | 向量库，`19530` 为应用连接端口，`9091` 健康检查 |
| `attu` | zilliz/attu:latest | 8000 | Milvus Web 管理台（可选），`http://localhost:8000` |

### 启动与停止

```bash
docker compose up -d              # 启动全部中间件（含 Attu 管理台）
docker compose up -d mysql redis milvus-standalone   # 只启动需要的
docker compose ps                 # 查看状态，等待 healthy
docker compose logs -f milvus-standalone             # 查看日志

docker compose down               # 停止，保留数据
docker compose down -v            # 停止并删除全部数据卷（慎用，会清空数据）
```

### 应用连接配置

中间件连接信息与项目 `.env` 完全对齐（Compose 自动读取 `.env` 中的 `DB_USERNAME` / `DB_PASSWORD` / `REDIS_PASSWORD` 等变量）。**注意**：当前 `.env` 中 `MILVUS_HOST=118.25.109.72` 是远程测试实例，改用本地容器时需改为：

```properties
MILVUS_HOST=localhost
MILVUS_PORT=19530
# DB_URL 默认即 localhost:3306，无需改动
```

### 数据持久化与初始化

- 数据保存在命名卷：`mysql-data`、`redis-data`、`etcd-data`、`minio-data`、`milvus-data`，`docker compose down` 不丢失。
- MySQL 首次启动会挂载 `src/main/resources/db/init.sql` 到容器初始化目录自动建库建表；如需重建，先 `docker compose down -v` 清卷再 `up -d`。
- 镜像版本说明：Milvus 需与 `application*.yml` 中 `embedding-dimension: 1024`、索引类型一致；本项目已使用 Milvus 2.4（AUTOINDEX / COSINE），升级大版本前请先验证兼容性。

### 应用容器化（可选，暂未提供）

当前 Compose 只编排**中间件**，应用仍在本机以 `mvn spring-boot:run` 运行。如需将后端/前端一并容器化部署（`Dockerfile` + Compose service），请基于 `application-prod.yml`（全部走环境变量）扩展，并将 Compose 网络接入本编排。

## 配置管理

敏感信息与部署差异项全部通过**环境变量**注入，不写死在 yml 中。

**加载优先级**：系统环境变量 > 项目根目录 `.env` 文件 > `application*.yml` 内默认值。

`.env` 由 `application.yml` 中 `spring.config.import: optional:file:.env[.properties]` 加载，已加入 `.gitignore`，**禁止提交**；新增变量请同步维护 `.env.example` 模板。

| 变量 | 说明 |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接 |
| `REDIS_PASSWORD` | Redis 密码（无密码留空） |
| `QWEN_API_KEY` | 通义千问 DashScope API Key（必填） |
| `LION_ALAPI_TOKEN` | ALAPI Token（星座运势等第三方接口，可选） |
| `MILVUS_HOST` / `MILVUS_PORT` / `MILVUS_DATABASE` / `MILVUS_COLLECTION` | Milvus 连接与 collection |
| `LANGFUSE_OTLP_ENDPOINT` / `LANGFUSE_OTLP_AUTH` | Langfuse OTLP 端点与 Basic Auth（`base64(pk:sk)`） |
| `LION_UPLOAD_PATH` | 文件上传目录（默认 `upload/`） |

业务开关（yml）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `lion.qa-cache.enabled` | `true` | 语义缓存总开关 |
| `lion.qa-cache.threshold` | `0.78` | 命中相似度阈值（0.75~0.80 合理，过高会永远命中不了） |
| `lion.qa-cache.collection-name` | `lion_agent_qa_cache` | 语义缓存专用 Milvus collection（与知识库物理隔离） |
| `lion.async.consume-threads` | `2` | 异步队列消费线程数 |
| `lion.async.max-retry` | `3` | 文档处理失败最大重试次数 |

## 数据库表

| 表 | 说明 |
| --- | --- |
| `sys_user` | 用户 |
| `conversation` | 会话（支持重命名/删除/清空） |
| `chat_message` | 消息（`user` / `assistant`），多轮记忆的唯一落库来源 |
| `chat_conversation_summary` | 会话摘要，多版本保留（`version` + `last_message_id` 增量游标） |
| `knowledge_base` | 知识库 |
| `knowledge_document` | 文档，`status`：0 失败 / 1 成功 / 2 处理中，失败原因记录在 `fail_reason` |

## 核心设计

### 1. 对话链路（文本 / SSE 流式 / 多模态）

```
用户消息 → 落库 chat_message → Advisor 链 → 模型调用 → 回复落库 + 写语义缓存
```

- **多轮记忆**：历史不再存内存，直接从 `chat_message` 表按 id 升序读取；`ConversationSummaryAdvisor` 只对 `last_message_id` 游标之后的新消息做增量压缩，压缩结果按 `version+1` 落库 `chat_conversation_summary`，未达阈值时复用最新摘要。
- **语义缓存**（`QaCacheAdvisor` + `QaCacheService`）：问题先做向量检索，与历史问题相似度 ≥ 阈值（默认 0.78）直接复用历史回答——省 token、秒回、答案一致；按 `userId` 隔离；Milvus 故障自动降级跳过缓存，不影响主流程。
- **多模态**：`POST /api/chat/multimodal`，`multipart/form-data` 上传 `message` + `images`（多张）/ `imageUrls`，图文一起发送给多模态模型。

### 2. 知识库上传异步化（Redis 生产者-消费者）

```
上传接口 → 校验 → 插元数据(status=2 处理中) → 落盘 upload/{knowledgeId}/{yyyy/MM}/{uuid}_{文件名}
        → push 到 Redis 队列 → 秒回
```

消费者（`DocumentProcessConsumer`，队列 `document:process`）：

```
BRPOP 取任务 → Redis SETNX 幂等锁（防同一文档并发处理）
            → Tika 解析 → 按策略切分 → 分批(10条/批) embedding 写 Milvus → status=1
失败：未超过最大重试次数(默认3)重新入队，否则 status=0 + fail_reason
```

通用组件位于 `common/async`：`RedisTaskQueue`（生产者，Hutool `JSONUtil` 序列化）、`AbstractRedisTaskConsumer`（消费者基类，N 线程轮询 + 优雅关闭）。重启期间 Redis 队列中的任务保留，重启后继续消费。

### 3. 工具注册与筛选（三层收敛，RAG of Tools）

- **常驻工具**：`UserTools` 等高频率低成本工具不走检索，永远注册。
- **权限过滤**：`@ToolPermission` 标注的工具按权限码过滤候选池（当前未实现 `StpInterface`，默认全部公开，接入后自动生效）。
- **向量预筛**：用户 query 与工具描述算相似度 top-3 召回，再交给 function calling 精选。

索引与本体分离：Milvus 中只存工具"目录索引"（`type=tool_index`，与知识库文档向量共用 collection 靠 type 隔离），工具实现仍是 Spring Bean。新增工具 = 写工具类 + 在 `ToolRegistryService` 登记，启动自动重建索引。

MCP：本服务内置 streamable-http Server（`/mcp`），同时可作为 SSE Client 接入第三方 MCP Server（如商品分析），接入的工具同样参与向量索引与熔断保护。

### 4. 可观测性

- Actuator 全量端点（`/actuator/health` 等）。
- OpenTelemetry 链路追踪 → Langfuse Cloud（OTLP，Basic Auth 经环境变量注入），采样率 `management.tracing.sampling.probability=1.0`。

## 接口总览

### 认证 `/api/auth`
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录，返回 token |
| POST | `/api/auth/logout` | 退出登录 |
| GET | `/api/auth/me` | 当前登录用户信息 |

### 对话 `/api/chat`
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/send` | 发送消息（`conversationId` 为空自动建会话） |
| POST | `/api/chat/stream` | SSE 流式返回（事件：start / message / done） |
| POST | `/api/chat/multimodal` | 多模态，`multipart/form-data`：`message`、`conversationId`、`images`(多文件)、`imageUrls`(多 URL) |

### 会话 `/api/conversations`
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/conversations` | 会话列表（分页 + 关键词） |
| GET | `/api/conversations/{id}/messages` | 消息记录（分页） |
| DELETE | `/api/conversations/{id}` | 删除会话 |
| DELETE | `/api/conversations/all` | 清空全部会话 |
| PUT | `/api/conversations/{id}` | 重命名 |

### 知识库 `/api/knowledge`
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/knowledge` | 知识库列表 |
| POST | `/api/knowledge` | 创建知识库 |
| PUT | `/api/knowledge/{id}` | 修改知识库 |
| DELETE | `/api/knowledge/{id}` | 删除知识库 |
| POST | `/api/knowledge/chat` | 知识库问答（RAG：检索相关片段作为上下文） |
| GET | `/api/knowledge/{knowledgeId}/documents` | 文档列表（轮询 `status` 感知异步处理结果） |
| POST | `/api/knowledge/{knowledgeId}/documents` | 上传文档（`file` + `splitter`，异步处理秒回） |
| DELETE | `/api/knowledge/{knowledgeId}/documents/{docId}` | 删除文档 |

认证方式：除注册/登录/多模态等 `@SaIgnore` 接口外，请求头携带 `Authorization: <token>`。

## 项目结构

```
lion-agent/
├── src/main/java/com/lion/agent/
│   ├── advisor/            # Advisor 链：TokenUsage / ConversationSummary / QaCache
│   ├── common/             # Result、PageResult、异步队列组件（async/）
│   ├── config/             # AiConfig、SaTokenConfig 等
│   ├── controller/         # 认证 / 对话 / 会话 / 知识库 / 文档 / 知识库问答
│   ├── dto/                # 请求体与任务消息（DocumentProcessTask）
│   ├── entity/             # 实体（User / Conversation / ChatMessage / ConversationSummary ...）
│   ├── exception/          # 全局异常
│   ├── mapper/             # MyBatis-Plus Mapper
│   ├── service/
│   │   ├── impl/           # 业务实现（Chat / KnowledgeDocument / KnowledgeChat ...）
│   │   └── async/          # DocumentProcessConsumer（Redis 任务消费者）
│   ├── tools/              # UserTools（常驻）、StarFortuneTools（星座）、mcptool/（MCP 工具）
│   └── vo/                 # 返回视图对象
├── src/main/resources/
│   ├── application*.yml    # 按环境拆分（dev / prod），敏感项走环境变量
│   ├── db/init.sql         # 建库建表脚本
├── frontend/               # Vue 3 前端
├── .env / .env.example     # 本地敏感配置（.env 不提交）
└── pom.xml
```

