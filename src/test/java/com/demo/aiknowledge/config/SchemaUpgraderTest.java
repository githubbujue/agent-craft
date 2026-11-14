package com.demo.aiknowledge.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 启动自检：只补缺失列，已存在时保持幂等
 */
@ExtendWith(MockitoExtension.class)
class SchemaUpgraderTest {

    private static final String TABLE = "conversation_context";
    private static final String COLUMNS_SQL_PREFIX = "SELECT COLUMN_NAME";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SchemaUpgrader upgrader() {
        return new SchemaUpgrader(jdbcTemplate);
    }

    private void stubColumns(List<String> columns) {
        when(jdbcTemplate.queryForList(contains(COLUMNS_SQL_PREFIX), eq(String.class), eq(TABLE)))
                .thenReturn(columns);
    }

    private void stubIndex(long count) {
        when(jdbcTemplate.queryForObject(contains("information_schema.STATISTICS"), eq(Long.class),
                eq(TABLE), eq("idx_summary_status")))
                .thenReturn(count);
    }

    @Test
    @DisplayName("列与索引齐全时不执行任何 DDL")
    void doesNothingWhenSchemaIsUpToDate() {
        stubColumns(List.of("id", "summary", "summary_msg_id", "summary_version",
                "summary_status", "summary_update_time"));
        stubIndex(1L);

        upgrader().upgradeConversationContext();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("缺列时按需补列，不动已有的列")
    void addsMissingColumnsOnly() {
        stubColumns(List.of("id", "summary", "summary_msg_id"));
        stubIndex(1L);

        upgrader().upgradeConversationContext();

        // 缺 3 个列：summary_version / summary_status / summary_update_time
        verify(jdbcTemplate, times(3)).execute(contains("ADD COLUMN"));
        verify(jdbcTemplate, never()).execute(contains("summary_msg_id"));
    }

    @Test
    @DisplayName("缺索引时补索引")
    void addsIndexWhenMissing() {
        stubColumns(List.of("summary_msg_id", "summary_version", "summary_status", "summary_update_time"));
        stubIndex(0L);

        upgrader().upgradeConversationContext();

        verify(jdbcTemplate).execute(contains("idx_summary_status"));
    }

    @Test
    @DisplayName("表不存在时跳过，不去 ALTER 一个不存在的表")
    void skipsWhenTableMissing() {
        stubColumns(List.of());

        upgrader().upgradeConversationContext();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("开关关闭时完全不查库")
    void skipsWhenDisabled() {
        SchemaUpgrader upgrader = upgrader();
        ReflectionTestUtils.setField(upgrader, "enabled", false);

        upgrader.run(null);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("DDL 失败不阻断启动")
    void doesNotThrowWhenDdlFails() {
        stubColumns(List.of("id", "summary"));
        // 第一个 ADD COLUMN 就会抛，走不到索引检查，所以这里不 stub 索引
        doThrow(new RuntimeException("Access denied")).when(jdbcTemplate).execute(anyString());

        SchemaUpgrader upgrader = upgrader();
        // 手工 new 的对象没有经过 @Value 注入，boolean 默认 false，必须显式打开
        ReflectionTestUtils.setField(upgrader, "enabled", true);

        assertDoesNotThrow(() -> upgrader.run(null));
    }

    private static void assertDoesNotThrow(org.junit.jupiter.api.function.Executable executable) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(executable);
    }
}
