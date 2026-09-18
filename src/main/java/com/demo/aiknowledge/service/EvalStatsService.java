package com.demo.aiknowledge.service;

import java.util.Map;

/**
 * 评估看板统计 —— 把散落在 agent_step 里的评估结果聚合成可展示的指标。
 *
 * <p>三类评估数据来源(均存在 agent_step.output 的 JSON 里):
 * <ul>
 *   <li>{@code sufficiency_evaluated} —— 检索充分性: avg_score / is_sufficient / confidence</li>
 *   <li>{@code answer_generated}      —— 答案质量: quality_score / has_sources</li>
 *   <li>{@code faithfulness_evaluated} —— 内容忠实度: faithfulness / unsupported_claims</li>
 * </ul>
 */
public interface EvalStatsService {

    /**
     * 一次性返回看板所需的全部统计数据(前端只需一次请求)。
     *
     * @param lowFaithfulnessThreshold 低分回答的判定阈值(默认 0.8)
     * @param lowLimit                 低分清单返回条数上限
     */
    Map<String, Object> getEvalStats(double lowFaithfulnessThreshold, int lowLimit);
}
