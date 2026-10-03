-- V14：删除违禁词表（群管理整体移除批次，2026-10-03）。
--
-- banned_words 的唯一消费者（BannedWordStore / BannedWordRegistry / AdminWordController）
-- 已随群管理全族移除（用户决策：只保留担保交易）。表内数据为运营期词库，
-- **删除后不可恢复**——部署前如有需要请先 mysqldump 备份。
--
-- ⚠️ 注意本迁移**只删 banned_words**：
--   · warnings 表**保留**——ET-61（惩罚编排，PenaltyService）的跨群警告计数仍读它；
--   · trade_message_map / user_prefs 保留——交易侧在用。
DROP TABLE IF EXISTS banned_words;
