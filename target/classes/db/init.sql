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
    file_type   VARCHAR(64)  DEFAULT NULL COMMENT '文件类型',
    file_path   VARCHAR(512) DEFAULT NULL COMMENT '文件存储路径（相对 upload 目录）',
    status      TINYINT      NOT NULL DEFAULT 2 COMMENT '上传状态：0-失败 1-成功 2-处理中',
    fail_reason VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    -- content 列已废弃：文档内容分片后存储在 Milvus 向量库，不再落 MySQL
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

-- 注：知识库文档分片已迁移至 Milvus 向量数据库，MySQL 不再存储分片。
-- 如需清理旧数据可执行：DROP TABLE IF EXISTS knowledge_chunk;
