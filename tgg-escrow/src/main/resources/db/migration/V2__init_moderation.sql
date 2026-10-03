-- 持久化 V2：管理与交互支撑表（可移植 DDL，见 V1 的约束：独立 CREATE INDEX，不写 ENGINE/CHARSET）
-- warnings          GM-03 累计警告
-- banned_words      违禁词在线管理（GM-06 的词库持久化）
-- trade_message_map 原地编辑消息映射（12 材料：orderId→messageId）
-- user_prefs        用户偏好（通知模式 all/important）

CREATE TABLE warnings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    guild_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    warn_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_warnings_guild_user ON warnings (guild_id, user_id);

CREATE TABLE banned_words (
    id BIGINT NOT NULL AUTO_INCREMENT,
    guild_id BIGINT NOT NULL,
    word VARCHAR(512) NOT NULL,
    is_regex BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id)
);

CREATE INDEX idx_banned_words_guild ON banned_words (guild_id);

CREATE TABLE trade_message_map (
    trade_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (trade_id)
);

CREATE TABLE user_prefs (
    user_id BIGINT NOT NULL,
    notice_mode VARCHAR(16) NOT NULL DEFAULT 'all',
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id)
);
