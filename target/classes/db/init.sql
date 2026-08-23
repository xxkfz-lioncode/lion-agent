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

