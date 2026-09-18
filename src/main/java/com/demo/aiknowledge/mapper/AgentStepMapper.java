package com.demo.aiknowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.aiknowledge.entity.AgentStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {
    
    @Select("SELECT * FROM agent_step WHERE run_id = #{runId} ORDER BY created_at ASC")
    List<AgentStep> selectByRunId(String runId);
    
    @Select("SELECT * FROM agent_step WHERE status = #{status} ORDER BY created_at DESC")
    List<AgentStep> selectByStatus(String status);

    // ===================== 评估看板统计 =====================
    // 评估结果以 JSON 文本存在 agent_step.output 里, 这里用 MySQL 的 JSON 函数直接在库内聚合,
    // 避免把全量记录搬到应用层再遍历(数据量上涨后差距会很明显)。
    // 统一用 JSON_VALID 兜底: 跳过历史脏数据, 防止一条非法 JSON 让整个看板查询失败。

    /** 平均内容忠实度(0-1) */
    @Select("SELECT ROUND(AVG(CAST(output->>'$.faithfulness' AS DECIMAL(5,3))), 3) "
            + "FROM agent_step WHERE step_type = 'faithfulness_evaluated' "
            + "AND output IS NOT NULL AND JSON_VALID(output)")
    Double selectAvgFaithfulness();

    /** 平均答案质量分(0-100) */
    @Select("SELECT ROUND(AVG(CAST(output->>'$.quality_score' AS DECIMAL(6,2))), 1) "
            + "FROM agent_step WHERE step_type = 'answer_generated' "
            + "AND output IS NOT NULL AND JSON_VALID(output)")
    Double selectAvgQualityScore();

    /** 检索充分率(百分比 0-100) */
    @Select("SELECT ROUND(SUM(output->>'$.is_sufficient' = 'true') / COUNT(*) * 100, 1) "
            + "FROM agent_step WHERE step_type = 'sufficiency_evaluated' "
            + "AND output IS NOT NULL AND JSON_VALID(output)")
    Double selectSufficiencyRate();

    /** 忠实度分 5 档分布 */
    @Select("SELECT bucket AS name, COUNT(*) AS value FROM ("
            + "  SELECT CASE"
            + "    WHEN CAST(output->>'$.faithfulness' AS DECIMAL(5,3)) < 0.2 THEN '0.0-0.2'"
            + "    WHEN CAST(output->>'$.faithfulness' AS DECIMAL(5,3)) < 0.4 THEN '0.2-0.4'"
            + "    WHEN CAST(output->>'$.faithfulness' AS DECIMAL(5,3)) < 0.6 THEN '0.4-0.6'"
            + "    WHEN CAST(output->>'$.faithfulness' AS DECIMAL(5,3)) < 0.8 THEN '0.6-0.8'"
            + "    ELSE '0.8-1.0' END AS bucket"
            + "  FROM agent_step"
            + "  WHERE step_type = 'faithfulness_evaluated' AND JSON_VALID(output)"
            + ") t GROUP BY bucket ORDER BY bucket")
    List<Map<String, Object>> selectFaithfulnessDistribution();

    /** 按天趋势: 忠实度与答案质量用条件聚合在同一条 SQL 里取(两者分属不同 step_type) */
    @Select("SELECT DATE(created_at) AS date, "
            + "ROUND(AVG(CASE WHEN step_type = 'faithfulness_evaluated' "
            + "  THEN CAST(output->>'$.faithfulness' AS DECIMAL(5,3)) END), 3) AS faithfulness, "
            + "ROUND(AVG(CASE WHEN step_type = 'answer_generated' "
            + "  THEN CAST(output->>'$.quality_score' AS DECIMAL(6,2)) END), 1) AS quality, "
            + "COUNT(DISTINCT run_id) AS runs "
            + "FROM agent_step "
            + "WHERE step_type IN ('faithfulness_evaluated', 'answer_generated') AND JSON_VALID(output) "
            + "GROUP BY DATE(created_at) ORDER BY date")
    List<Map<String, Object>> selectEvalTrend();

    /** 低分回答清单(忠实度低于阈值), 关联 agent_run 取问题原文 */
    @Select("SELECT s.run_id AS runId, "
            + "LEFT(COALESCE(r.input, r.goal, ''), 50) AS question, "
            + "CAST(s.output->>'$.faithfulness' AS DECIMAL(5,3)) AS faithfulness, "
            + "s.output->>'$.unsupported_claims' AS unsupportedClaims, "
            + "s.created_at AS createdAt "
            + "FROM agent_step s LEFT JOIN agent_run r ON s.run_id = r.run_id "
            + "WHERE s.step_type = 'faithfulness_evaluated' AND JSON_VALID(s.output) "
            + "AND CAST(s.output->>'$.faithfulness' AS DECIMAL(5,3)) < #{threshold} "
            + "ORDER BY s.created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> selectLowFaithfulness(@Param("threshold") double threshold,
                                                    @Param("limit") int limit);

    /** 检索充分性分布(充分 / 不充分 / 总数) */
    @Select("SELECT SUM(output->>'$.is_sufficient' = 'true') AS sufficient, "
            + "SUM(output->>'$.is_sufficient' = 'false') AS insufficient, "
            + "COUNT(*) AS total "
            + "FROM agent_step WHERE step_type = 'sufficiency_evaluated' AND JSON_VALID(output)")
    Map<String, Object> selectSufficiencyDistribution();
}
