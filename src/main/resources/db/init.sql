-- =====================================================================
-- Lion Agent 智能问答系统 数据库初始化脚本
-- 执行方式：mysql -uroot -p < init.sql  或 在 Navicat 等工具中执行
-- =====================================================================

CREATE DATABASE IF NOT EXISTS lion_agent
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE lion_agent;

-- ---------------------------------------------------------------------
-- 用户表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(64)  NOT NULL COMMENT '用户名',
    password   VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密）',
    nickname   VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar     VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-正常',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 对话会话表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS chat_conversation;
CREATE TABLE chat_conversation (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '所属用户 ID',
    title      VARCHAR(128) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB COMMENT ='对话会话表';

-- ---------------------------------------------------------------------
-- 聊天消息表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS chat_message;
CREATE TABLE chat_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT       NOT NULL COMMENT '所属会话 ID',
    role            VARCHAR(16)  NOT NULL COMMENT '角色：user-用户 assistant-AI',
    content         TEXT         NOT NULL COMMENT '消息内容',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    KEY idx_conversation_id (conversation_id)
) ENGINE = InnoDB COMMENT ='聊天消息表';

-- ---------------------------------------------------------------------
-- 知识库表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS knowledge_base;
CREATE TABLE knowledge_base (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '所属用户 ID',
    name        VARCHAR(128) NOT NULL COMMENT '知识库名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '知识库描述',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB COMMENT ='知识库表';

-- ---------------------------------------------------------------------
-- 知识库文档表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS knowledge_document;
CREATE TABLE knowledge_document (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_id BIGINT      NOT NULL COMMENT '所属知识库 ID',
    file_name   VARCHAR(256) NOT NULL COMMENT '文件名称',
    file_size   BIGINT       DEFAULT 0 COMMENT '文件大小（字节）',
    file_type   VARCHAR(120)  DEFAULT NULL COMMENT '文件类型',
    file_path   VARCHAR(512) DEFAULT NULL COMMENT '文件存储路径（相对 upload 目录）',
    status      TINYINT      NOT NULL DEFAULT 2 COMMENT '上传状态：0-失败 1-成功 2-处理中',
    fail_reason VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    splitter    VARCHAR(32)  DEFAULT NULL COMMENT '文档切分方式（token/recursive/paragraph/sentence/line/semantic）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    KEY idx_knowledge_id (knowledge_id)
) ENGINE = InnoDB COMMENT ='知识库文档表';

-- ---------------------------------------------------------------------
-- 会话摘要表（ConversationSummaryAdvisor 压缩结果落库）
-- 每个会话每次压缩插入一条记录，version 递增，保留摘要历史
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS chat_conversation_summary;
CREATE TABLE chat_conversation_summary (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT       NOT NULL COMMENT '会话 ID，关联 chat_conversation.id',
    summary         TEXT         NOT NULL COMMENT '压缩后的对话摘要内容',
    message_count   INT          NOT NULL DEFAULT 0 COMMENT '本次压缩的新增消息条数',
    last_message_id BIGINT       NOT NULL DEFAULT 0 COMMENT '本次摘要覆盖到的最大消息 ID（chat_message.id 游标，增量摘要）',
    version         INT          NOT NULL DEFAULT 1 COMMENT '摘要版本（同一会话每次压缩递增）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_version (conversation_id, version),
    KEY idx_conversation_id (conversation_id)
) ENGINE = InnoDB COMMENT ='会话摘要表';

-- ---------------------------------------------------------------------
-- Token 用量统计表（TokenUsageAdvisor 每次模型调用后落库，前端用量页面展示）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_token_usage;
CREATE TABLE ai_token_usage (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT      NOT NULL COMMENT '用户 ID，关联 sys_user.id',
    conversation_id   BIGINT      DEFAULT NULL COMMENT '会话 ID，关联 chat_conversation.id（知识库问答为 NULL）',
    chat_type         VARCHAR(16) NOT NULL DEFAULT 'chat' COMMENT '会话类型：chat-常规对话 kb-知识库问答',
    call_type         VARCHAR(8)  NOT NULL DEFAULT 'sync' COMMENT '调用方式：sync-同步 stream-流式',
    model             VARCHAR(64) DEFAULT NULL COMMENT '模型名称',
    prompt_tokens     INT         NOT NULL DEFAULT 0 COMMENT '输入 token 数',
    completion_tokens INT         NOT NULL DEFAULT 0 COMMENT '输出 token 数',
    total_tokens      INT         NOT NULL DEFAULT 0 COMMENT '总 token 数',
    cost_ms           BIGINT      NOT NULL DEFAULT 0 COMMENT '调用耗时（毫秒）',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_conversation_id (conversation_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB COMMENT ='Token 用量统计表';

-- ---------------------------------------------------------------------
-- 用户长期记忆表（长期记忆方案：LLM 抽取用户事实/偏好落库，
-- 向量存 Milvus lion_agent_memory 供检索注入，本表为 MySQL 侧原文与元数据）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_memory;
CREATE TABLE ai_memory
(
    id                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id                BIGINT        NOT NULL COMMENT '归属用户 ID，关联 sys_user.id',
    memory_type            VARCHAR(16)   NOT NULL DEFAULT 'fact' COMMENT '记忆类型：fact-事实 preference-偏好',
    content                VARCHAR(1024) NOT NULL COMMENT '记忆内容（如：用户预算是 50 万）',
    importance             TINYINT       NOT NULL DEFAULT 3 COMMENT '重要性 1-5（LLM 抽取时打分，越高越重要）',
    source_conversation_id BIGINT                 DEFAULT NULL COMMENT '来源会话 ID，关联 chat_conversation.id（知识库问答为 NULL）',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    KEY                    idx_user_id (user_id)
) ENGINE = InnoDB COMMENT ='用户长期记忆表';

-- ---------------------------------------------------------------------
-- 自定义技能表（页面维护 → 参数替换 → 动态构建 ToolCallback 注册给模型）
-- prompt_template 中 {{param}} 占位符运行时替换为用户自定义技能参数
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_skill;
CREATE TABLE ai_skill (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '所属用户 ID（0=全局技能）',
    name            VARCHAR(64)  NOT NULL COMMENT '技能名（工具名，模型可见，字母数字下划线）',
    description     VARCHAR(512) NOT NULL COMMENT '技能描述（模型判断何时调用 + 向量检索语料）',
    prompt_template TEXT         NOT NULL COMMENT '提示词模板，{{param}} 占位符运行时替换',
    parameters      TEXT         DEFAULT NULL COMMENT '参数定义 JSON 数组：[{"name","type","description","required","defaultValue"}]',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_name (user_id, name),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB COMMENT ='自定义技能表';

-- ---------------------------------------------------------------------
-- 内置技能种子数据（user_id=0 全局技能）
-- ---------------------------------------------------------------------
INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'api_doc_generator', '根据代码或接口描述，自动生成专业的 RESTful API 文档。支持指定文档格式和详细程度，输出包含请求示例、响应示例和错误码说明。',
'你是一位经验丰富的技术文档工程师，擅长编写清晰、规范的 API 文档。请根据以下信息生成专业的 RESTful API 文档。

## 输入信息

- **API 代码/描述**：
```
{{code_or_description}}
```
- **文档格式**：{{format}}
- **详细程度**：{{detail_level}}
- **基础 URL**：{{base_url}}

## 输出要求

请按照以下结构生成文档：

### 接口概述

| 属性 | 值 |
|------|------|
| **接口名称** | （从代码/描述中提取） |
| **请求方法** | GET / POST / PUT / DELETE |
| **请求路径** | {{base_url}}/... |
| **功能描述** | 一句话描述接口功能 |
| **认证方式** | 如代码中有相关注解则标注，否则标注"待确认" |

### 请求参数

分别列出以下类型的参数（如有）：

**Path 参数**

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|

**Query 参数**

| 参数名 | 类型 | 必填 | 默认值 | 说明 | 示例 |
|--------|------|------|--------|------|------|

**Request Body**（JSON 格式）

```json
{
  // 带注释的请求体示例
}
```

### 响应结果

**成功响应**（HTTP 200）

```json
{
  // 带注释的响应体示例
}
```

**响应字段说明**

| 字段 | 类型 | 说明 |
|------|------|------|

### 错误码

| HTTP 状态码 | 错误码 | 说明 | 解决方案 |
|-------------|--------|------|----------|
| 400 | BAD_REQUEST | 请求参数校验失败 | 检查必填参数和格式 |
| 404 | NOT_FOUND | 资源不存在 | 确认资源 ID 是否正确 |
| 500 | INTERNAL_ERROR | 服务器内部错误 | 联系开发人员 |

### 调用示例

**cURL**

```bash
curl -X {METHOD} ''{base_url}/...'' \
  -H ''Content-Type: application/json'' \
  -d ''{...}''
```

### 注意事项

列出使用此接口时需要注意的要点，如：
- 频率限制
- 数据大小限制
- 并发注意事项
- 幂等性说明',
'[{"name":"code_or_description","type":"string","description":"API 的代码片段（如 Controller 方法）或自然语言描述","required":true,"defaultValue":""},{"name":"format","type":"string","description":"文档输出格式，可选值：markdown（Markdown 格式）、openapi（OpenAPI 3.0 YAML 片段）","required":false,"defaultValue":"markdown"},{"name":"detail_level","type":"string","description":"文档详细程度，可选值：brief（简要，仅核心信息）、standard（标准，含示例）、detailed（详细，含所有字段说明和边界情况）","required":false,"defaultValue":"standard"},{"name":"base_url","type":"string","description":"API 的基础 URL 路径前缀","required":false,"defaultValue":"/api/v1"}]', 1);

INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'code_explain', '解释代码片段的功能和逻辑，逐行分析代码的作用',
'请详细解释以下代码的功能和逻辑，逐行分析关键代码的作用：

{{input}}',
'[{"name":"input","type":"string","description":"需要解释的代码片段","required":true,"defaultValue":""}]', 1);

INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'code_review', '对代码进行专业的多维度审查，从安全性、性能、可读性、最佳实践等角度给出评分和改进建议。支持指定编程语言和关注重点。',
'你是一位资深的代码审查专家，拥有丰富的软件工程经验。请对以下代码进行专业的多维度审查。

## 审查对象

- **编程语言**：{{language}}
- **关注重点**：{{focus}}

```
{{code}}
```

## 审查要求

请严格按照以下结构输出审查报告：

### 1. 总体评价
用 1-2 句话概括代码的整体质量。

### 2. 评分（满分 10 分）

| 维度 | 得分 | 说明 |
|------|------|------|
| 安全性 | ?/10 | 是否存在注入、越权、敏感信息泄露等风险 |
| 性能 | ?/10 | 是否存在不必要的计算、内存泄漏、N+1 查询等问题 |
| 可读性 | ?/10 | 命名规范、代码结构、注释质量 |
| 架构设计 | ?/10 | 职责划分、耦合度、扩展性 |
| 最佳实践 | ?/10 | 是否遵循语言/框架的惯用写法和最佳实践 |

### 3. 问题清单

按严重程度（严重 / 警告 / 建议）逐条列出发现的问题，每条包含：
- **位置**：指出问题所在的代码行或代码段
- **问题描述**：说明问题是什么
- **风险说明**：解释可能带来的后果
- **修复建议**：给出具体的修复代码或方案

### 4. 改进后的代码

如果存在严重或警告级别的问题，请给出改进后的完整代码，并用注释标注修改点。

### 5. 总结建议

给出 2-3 条最重要的改进方向，帮助开发者提升代码质量。',
'[{"name":"code","type":"string","description":"需要审查的代码片段","required":true,"defaultValue":""},{"name":"language","type":"string","description":"编程语言（如 Java、Python、Go 等），不确定时填 auto 自动检测","required":false,"defaultValue":"auto"},{"name":"focus","type":"string","description":"审查关注重点，可选值：security（安全性）、performance（性能）、readability（可读性）、all（全面审查）","required":false,"defaultValue":"all"}]', 1);

INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'sql_generator', '根据自然语言描述生成 SQL 查询语句。支持指定数据库类型、表结构信息，生成可直接执行的 SQL，并附带性能优化建议。',
'你是一位资深的数据库工程师和 SQL 专家。请根据以下信息生成高质量的 SQL 查询语句。

## 输入信息

- **数据库类型**：{{database_type}}
- **查询需求**：{{requirement}}
- **表结构**：{{table_schema}}
- **SQL 风格**：{{style}}

## 输出要求

请严格按照以下结构输出：

### 1. 需求分析

用 2-3 句话分析查询需求，明确需要哪些表、哪些字段、什么条件和排序。

### 2. 表结构（如果用户未提供，则推断）

用表格展示涉及的表结构：

| 表名 | 字段 | 类型 | 说明 |
|------|------|------|------|
| ... | ... | ... | ... |

### 3. SQL 查询语句

```sql
-- 生成的 SQL，附带行内注释解释关键逻辑
```

### 4. 执行说明

- 解释 SQL 的执行逻辑和预期结果
- 说明可能的边界情况（如 NULL 值处理、空结果等）

### 5. 性能优化建议

- 建议创建的索引
- 可能的查询优化方向
- 大数据量下的注意事项',
'[{"name":"requirement","type":"string","description":"用自然语言描述你想要查询的数据需求，例如：查询最近7天每个部门的销售总额，按金额降序排列","required":true,"defaultValue":""},{"name":"database_type","type":"string","description":"数据库类型（如 MySQL、PostgreSQL、Oracle、SQLite），不同数据库的 SQL 语法可能有差异","required":false,"defaultValue":"MySQL"},{"name":"table_schema","type":"string","description":"相关表的结构信息，包括表名、字段名、字段类型、主外键关系等","required":false,"defaultValue":"未提供，请根据需求合理推断表结构"},{"name":"style","type":"string","description":"SQL 风格偏好，可选值：simple（简洁优先）、optimized（性能优先）、readable（可读性优先）","required":false,"defaultValue":"optimized"}]', 1);

INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'summarize', '对用户提供的文本内容进行摘要总结，生成简洁的要点概述',
'请对以下文本进行摘要总结，提取核心要点，生成简洁的概述：

{{input}}',
'[{"name":"input","type":"string","description":"需要摘要总结的文本内容","required":true,"defaultValue":""}]', 1);

INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'tech_trivia', '生成一条有趣的技术冷知识或编程趣闻。当用户想听点有趣的技术故事、编程冷知识、计算机历史趣闻时调用此技能，无需任何输入参数。',
'你是一位博学风趣的技术科普作家，请随机生成一条有趣的技术冷知识或编程趣闻。

## 输出要求

请严格按照以下结构输出：

### 今日技术趣闻

**标题**：（一句话概括这个趣闻，要有吸引力）

### 故事

用 3-5 段生动有趣的文字讲述这个技术趣闻，要求：
- 内容真实可考证，不要编造
- 语言轻松幽默，像在和朋友聊天
- 涉及的技术概念要用通俗易懂的方式解释

### 技术解读

用 2-3 句话从技术角度解释这个趣闻背后的原理或影响。

### 你知道吗？

再补充 2-3 条与主题相关的迷你冷知识，每条一句话，用编号列出。

### 标签

给出 3-5 个相关标签，如：#编程语言 #计算机历史 #算法 #开源 #互联网

---

**选题范围**（随机选择一个方向）：
1. 编程语言的诞生故事（如 Python 名字的由来、Java 最初叫 Oak）
2. 著名 Bug 和事故（如千年虫、阿丽亚娜 5 号火箭）
3. 算法和数据结构的趣事（如快排的发明、哈希表的巧妙设计）
4. 互联网和开源的历史（如第一封电子邮件、Linux 的诞生）
5. 硬件和芯片的冷知识（如摩尔定律、第一台计算机）
6. 科技公司的有趣往事（如 Google 的车库创业、苹果的 1984 广告）
7. 程序员文化和梗（如 404 的由来、Hello World 的起源）
8. 数学与计算机的交叉趣闻（如图灵测试、P=NP 问题）

请确保每次生成的内容都不同，充满惊喜感！',
NULL, 1);

INSERT INTO ai_skill (user_id, name, description, prompt_template, parameters, status) VALUES
(0, 'translate', '翻译文本内容。自动检测源语言，中文翻译为英文，其他语言翻译为中文',
'请翻译以下文本。如果是中文，翻译为英文；如果是其他语言，翻译为中文。只输出翻译结果，不要输出任何解释。

{{input}}',
'[{"name":"input","type":"string","description":"需要翻译的文本内容","required":true,"defaultValue":""}]', 1);

-- ---------------------------------------------------------------------
-- 提示词模板表（ai_prompt_template）
-- 用于持久化管理 classpath:prompts/*.st 中的提示词模板
-- 当数据库存在记录时，以该记录内容作为“DB 版本”生效；否则回退到 classpath 文件
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_prompt_template;
CREATE TABLE ai_prompt_template (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)  NOT NULL COMMENT '模板显示名',
    file_name   VARCHAR(128) NOT NULL COMMENT '模板文件名（如 system-prompt.st）',
    description VARCHAR(512) DEFAULT NULL COMMENT '模板用途描述',
    content     TEXT         NOT NULL COMMENT '模板内容',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_name (file_name)
) ENGINE = InnoDB COMMENT ='提示词模板表';

-- ---------------------------------------------------------------------
-- MCP 服务表（ai_mcp_server）
-- 管理外部 MCP Server（SSE 协议）连接，启动时自动连接 enabled=1 的记录
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_mcp_server;
CREATE TABLE ai_mcp_server (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(128)  NOT NULL COMMENT '服务别名',
    url           VARCHAR(1024) NOT NULL COMMENT 'MCP Server URL（SSE endpoint）',
    transport_type VARCHAR(32)  NOT NULL DEFAULT 'sse' COMMENT '传输协议：sse',
    enabled       TINYINT       NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0禁用',
    status        VARCHAR(32)   NOT NULL DEFAULT 'disconnected' COMMENT '连接状态：connected/disconnected/error',
    error_msg     VARCHAR(1024) DEFAULT NULL COMMENT '最近一次连接错误信息',
    description   VARCHAR(512)  DEFAULT NULL COMMENT '服务描述',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT ='MCP 服务表';

-- ---------------------------------------------------------------------
-- MCP 服务工具表（ai_mcp_server_tool）
-- 缓存每个 MCP Server 发现的工具定义，供前端展示和测试调用
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_mcp_server_tool;
CREATE TABLE ai_mcp_server_tool (
    id            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    server_id     BIGINT         NOT NULL COMMENT '所属 MCP Server ID',
    name          VARCHAR(256)   NOT NULL COMMENT '工具名',
    description   VARCHAR(2048)  DEFAULT NULL COMMENT '工具描述',
    input_schema  JSON           DEFAULT NULL COMMENT '工具输入参数 JSON Schema',
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_tool_name (server_id, name),
    KEY idx_server_id (server_id)
) ENGINE = InnoDB COMMENT ='MCP 服务工具表';

