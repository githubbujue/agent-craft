-- ============================================================================
-- 阶段 2.5 迁移：摘要架构收敛（rolling summary）
-- 对应 docs/记忆改造计划.md §6.3
--
-- 适用于「已经建过表」的库；新建库直接执行 init.sql 即可（已含这些列）。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 1060 duplicate column，
-- 报错说明该列已存在，可忽略。
-- ============================================================================

ALTER TABLE `conversation_context`
  ADD COLUMN `summary_msg_id` bigint DEFAULT NULL COMMENT '摘要覆盖到的最后一条消息ID（增量游标）' AFTER `summary`,
  ADD COLUMN `summary_version` int DEFAULT '0' COMMENT '摘要版本，每次重新生成+1' AFTER `summary_msg_id`,
  ADD COLUMN `summary_status` varchar(16) DEFAULT 'none' COMMENT '摘要状态: none/generating/ready/failed' AFTER `summary_version`,
  ADD COLUMN `summary_update_time` datetime DEFAULT NULL COMMENT '摘要最后生成时间' AFTER `summary_status`;

-- 按状态检索失败/生成中的摘要（排查与补偿任务用）
ALTER TABLE `conversation_context`
  ADD KEY `idx_summary_status` (`summary_status`);
