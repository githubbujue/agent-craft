package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.demo.aiknowledge.dto.DashboardStats;
import com.demo.aiknowledge.entity.DocViewLog;
import com.demo.aiknowledge.entity.KnowledgeDoc;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.entity.QaLog;
import com.demo.aiknowledge.entity.QaUnanswered;
import com.demo.aiknowledge.entity.User;
import com.demo.aiknowledge.mapper.*;
import com.demo.aiknowledge.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private KnowledgeDocMapper docMapper;
    @Autowired
    private QaLogMapper qaLogMapper;
    @Autowired
    private QaUnansweredMapper qaUnansweredMapper;
    @Autowired
    private DocViewLogMapper docViewLogMapper;
    @Autowired
    private MessageMapper messageMapper;

    @Override
    public DashboardStats getStats() {
        DashboardStats stats = new DashboardStats();

        // 1. Basic Counts
        stats.setUserCount(userMapper.selectCount(new QueryWrapper<User>()));
        stats.setDocCount(docMapper.selectCount(new QueryWrapper<KnowledgeDoc>()));
        long totalQa = qaLogMapper.selectCount(new QueryWrapper<QaLog>());
        stats.setQaCount(totalQa);
        
        // Calculate Hit Rate
        // Sum of counts in qa_unanswered because each row has a count
        QueryWrapper<QaUnanswered> unansweredQuery = new QueryWrapper<>();
        unansweredQuery.select("sum(count)");
        List<Object> sumResult = qaUnansweredMapper.selectObjs(unansweredQuery);
        long unansweredTotal = 0;
        if (sumResult != null && !sumResult.isEmpty() && sumResult.get(0) != null) {
             unansweredTotal = Long.parseLong(sumResult.get(0).toString());
        }

        if (totalQa > 0) {
            long answered = Math.max(0, totalQa - unansweredTotal);
            double rate = (double) answered / totalQa;
            stats.setHitRate(rate * 100);
        } else {
            stats.setHitRate(0.0);
        }

        // 2. Hot Docs (Top 5 by view count)
        // Select doc_id, count(*) as view_count from doc_view_log group by doc_id order by view_count desc limit 5
        QueryWrapper<DocViewLog> hotDocsWrapper = new QueryWrapper<>();
        hotDocsWrapper.select("doc_id", "count(*) as view_count")
                .groupBy("doc_id")
                .orderByDesc("view_count")
                .last("limit 5");
        stats.setHotDocs(docViewLogMapper.selectMaps(hotDocsWrapper));
        
        // Enrich Hot Docs with Titles
        for (Map<String, Object> map : stats.getHotDocs()) {
            Long docId = (Long) map.get("doc_id");
            KnowledgeDoc doc = docMapper.selectById(docId);
            if (doc != null) {
                map.put("title", doc.getDocName());
            }
        }

        // 3. Top Questions (from QaUnanswered or QaLog? Prompt says "Top 1 How to apply server")
        // Let's assume frequently asked questions are stored in qa_unanswered with count, 
        // OR we aggregate from qa_log. 
        // Prompt implies qa_unanswered tracks "Missed" questions, but we also want "Hot" questions.
        // Let's query qa_log for hot questions (exact match might be rare, but let's try)
        QueryWrapper<QaLog> topQaWrapper = new QueryWrapper<>();
        topQaWrapper.select("question", "count(*) as count")
                .groupBy("question")
                .orderByDesc("count")
                .last("limit 5");
        stats.setTopQuestions(qaLogMapper.selectMaps(topQaWrapper));

        // 4. Unanswered Questions (Top 5)
        QueryWrapper<QaUnanswered> unansweredWrapper = new QueryWrapper<>();
        unansweredWrapper.orderByDesc("count").last("limit 5");
        List<QaUnanswered> unansweredList = qaUnansweredMapper.selectList(unansweredWrapper);
        List<Map<String, Object>> unansweredMaps = new ArrayList<>();
        for (QaUnanswered u : unansweredList) {
            Map<String, Object> m = new HashMap<>();
            m.put("question", u.getQuestion());
            m.put("count", u.getCount());
            unansweredMaps.add(m);
        }
        stats.setUnansweredQuestions(unansweredMaps);

        // 5. Question Trends (Last 7 days)
        QueryWrapper<QaLog> trendWrapper = new QueryWrapper<>();
        trendWrapper.select("DATE_FORMAT(create_time, '%Y-%m-%d') as date", "count(*) as count")
                .ge("create_time", LocalDateTime.now().minusDays(7))
                .groupBy("date")
                .orderByAsc("date");
        stats.setQuestionTrends(qaLogMapper.selectMaps(trendWrapper));

        // 6. 提问类型分布(仪表盘饼图用)
        // 为什么查 message 而不是 qa_log: qa_log 表没有 task_type 列, 类型信息只存在于 message。
        // 为什么限定 role='assistant': 用户消息的 task_type 恒为 unknown, 真实类型由 AI 回复携带。
        // 为什么排除 unknown: 早期版本未写入类型(现存 84 条), 让"未分类"占据饼图主体没有洞察价值。
        // 别名直接取 name/value: 前端 ECharts 的饼图数据格式就是 {name, value}, 在 SQL 层对齐少一层转换。
        QueryWrapper<Message> typeWrapper = new QueryWrapper<>();
        typeWrapper.select("task_type as name", "count(*) as value")
                .eq("role", "assistant")
                .ne("task_type", "unknown")
                .groupBy("task_type")
                .orderByDesc("value");
        stats.setTaskTypeDistribution(messageMapper.selectMaps(typeWrapper));

        return stats;
    }
}
