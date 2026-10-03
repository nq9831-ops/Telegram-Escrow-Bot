-- 持久化 V9：user_prefs 增加 ET-80「榜单公开」两列。
--
-- 背景：ET-80 排名隐私控制此前只做到「默认脱敏」，用户主动公开的 opt-in 是计划 Wave 4 的延期项——
-- UserPreferencePort 只有通知模式，user_prefs 也没有公开开关与 @username 落点。
-- 需求原文（13 号材料:13）：「排名默认脱敏（@u***A），用户可自选是否公开用户名。」
--
-- 口径：
--   rank_public       是否公开；既有行一律 DEFAULT FALSE（「从未表态」= 维持脱敏，不改变既有行为）；
--   public_username   公开时展示的 @username（不带前导 @），可空——未公开者无需占用。
--
-- 【可移植性】遵循 V1 文末约束：不写 ENGINE/CHARSET/COLLATE；ALTER ... ADD COLUMN 在
-- MySQL 8 与测试用 H2 MODE=MySQL 下均可执行。BOOLEAN 由 Hibernate 按 Boolean 映射，
-- MySQL 落 TINYINT(1)、H2 落 BOOLEAN——ddl-auto=validate 对布尔列二者兼容。

ALTER TABLE user_prefs ADD COLUMN rank_public BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_prefs ADD COLUMN public_username VARCHAR(64);
