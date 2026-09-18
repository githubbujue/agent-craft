package com.demo.aiknowledge.service.impl;

import com.demo.aiknowledge.mapper.AgentRunMapper;
import com.demo.aiknowledge.mapper.AgentStepMapper;
import com.demo.aiknowledge.service.EvalStatsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 评估看板统计实现 —— 只做聚合查询与拼装, 不修改任何业务数据。
 *
 * <p>空库或某类评估尚无数据时, 各项指标可能是 null(如忠实度 9/15 才上线),
 * 这里原样返回 null, 交由前端显示为占位符, 而不是伪造 0 —— 0 与"没有数据"语义不同。
 */
@Service
@Slf4j
public class EvalStatsServiceImpl implements EvalStatsService {

    @Resource
    private AgentStepMapper agentStepMapper;

    @Resource
    private AgentRunMapper agentRunMapper;

    @Override
    public Map<String, Object> getEvalStats(double lowFaithfulnessThreshold, int lowLimit) {
        Map<String, Object> stats = new HashMap<>();

        // 1. 顶部总览指标
        stats.put("avgFaithfulness", agentStepMapper.selectAvgFaithfulness());
        stats.put("avgQualityScore", agentStepMapper.selectAvgQualityScore());
        stats.put("sufficiencyRate", agentStepMapper.selectSufficiencyRate());
        stats.put("totalRuns", agentRunMapper.selectCount(null));

        // 2. 忠实度分档分布
        stats.put("faithfulnessBuckets", agentStepMapper.selectFaithfulnessDistribution());

        // 3. 按天趋势
        stats.put("trend", agentStepMapper.selectEvalTrend());

        // 4. 低分回答清单(用于定位"哪条回答在编")
        stats.put("lowFaithfulness", agentStepMapper.selectLowFaithfulness(
                lowFaithfulnessThreshold, lowLimit));
        stats.put("lowThreshold", lowFaithfulnessThreshold);

        // 5. 检索充分性分布
        stats.put("sufficiency", agentStepMapper.selectSufficiencyDistribution());

        log.info("[EvalStats] avgFaithfulness={}, avgQuality={}, sufficiencyRate={}%",
                stats.get("avgFaithfulness"), stats.get("avgQualityScore"),
                stats.get("sufficiencyRate"));
        return stats;
    }
}
