-- =====================================================================
-- tb_item_change_log 变更日志表（多级缓存兜底同步用）
-- 适用库: information
-- 背景: ItemChangeLogSyncTask 每 30 秒扫描未处理日志，canal 异常时兜底失效缓存。
--       此前项目未建此表，导致日志反复提示 "变更日志兜底同步异常"。
-- 说明: CREATE TABLE IF NOT EXISTS，可重复执行。
-- =====================================================================
USE information;

CREATE TABLE IF NOT EXISTS tb_item_change_log (
  id            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  item_id       bigint unsigned NOT NULL COMMENT '商品id',
  change_type   varchar(16) NOT NULL DEFAULT 'UPDATE' COMMENT '变更类型(INSERT/UPDATE/DELETE)',
  is_processed  tinyint NOT NULL DEFAULT 0 COMMENT '是否已处理 0未处理 1已处理',
  create_time   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  process_time  timestamp NULL DEFAULT NULL COMMENT '处理时间',
  PRIMARY KEY (id),
  KEY idx_item_id (item_id),
  KEY idx_is_processed (is_processed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品变更日志表(多级缓存兜底同步)';