package com.demo.aiknowledge.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动自检：补齐阶段 2.5 需要的摘要字段。
 *
 * <p>存在的理由：`sql/init.sql` 只对"全新建的库"生效，存量库不会因为脚本改了而自己长出新列。
 * 漏执行的后果是 SQL 报 "Unknown column"，异常被业务代码 catch 掉，
 * 表现成"摘要永远不生成"—— 这类静默失败本项目已经踩过好几次。
 *
 * <p>边界（刻意收得很窄）：
 * <ul>
 *   <li>只做加列 / 加索引，即向后兼容变更；删列、改类型、改默认值一律不做</li>
 *   <li>幂等：先查 information_schema，缺了才执行，重复启动无副作用</li>
 *   <li>失败不阻断启动：摘要不可用不影响问答，日志会明确要求手工执行迁移脚本</li>
 * </ul>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaUpgrader implements ApplicationRunner {

    private static final String TABLE = "conversation_context";
    private static final String SUMMARY_INDEX = "idx_summary_status";

    private final JdbcTemplate jdbcTemplate;

    @Value("${schema.auto-upgrade.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[Schema] 自动补列已关闭（schema.auto-upgrade.enabled=false）");
            return;
        }
        try {
            upgradeConversationContext();
        } catch (Exception e) {
            // 权限不足 / 表不存在 / 无法连接：不阻断启动
            log.error("[Schema] 自动补列失败，请手工执行 sql/migration_summary_v2.5.sql 后重启", e);
        }
    }

    void upgradeConversationContext() {
        List<String> columnList = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                String.class, TABLE);
        Set<String> columns = new HashSet<>(columnList);
        if (columns.isEmpty()) {
            log.info("[Schema] 表 {} 不存在，跳过自动补列（新建库请执行 sql/init.sql）", TABLE);
            return;
        }

        addColumnIfMissing(columns, "summary_msg_id",
                "bigint DEFAULT NULL COMMENT '摘要覆盖到的最后一条消息ID（增量游标）'");
        addColumnIfMissing(columns, "summary_version",
                "int DEFAULT '0' COMMENT '摘要版本，每次重新生成+1'");
        addColumnIfMissing(columns, "summary_status",
                "varchar(16) DEFAULT 'none' COMMENT '摘要状态: none/generating/ready/failed'");
        addColumnIfMissing(columns, "summary_update_time",
                "datetime DEFAULT NULL COMMENT '摘要最后生成时间'");

        addIndexIfMissing();
    }

    private void addColumnIfMissing(Set<String> existing, String column, String definition) {
        if (existing.contains(column)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE `" + TABLE + "` ADD COLUMN `" + column + "` " + definition);
        log.info("[Schema] 已补列 {}.{}", TABLE, column);
    }

    private void addIndexIfMissing() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Long.class, TABLE, SUMMARY_INDEX);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + TABLE + "` ADD KEY `"
                    + SUMMARY_INDEX + "` (`summary_status`)");
            log.info("[Schema] 已补索引 {}", SUMMARY_INDEX);
        }
    }
}
