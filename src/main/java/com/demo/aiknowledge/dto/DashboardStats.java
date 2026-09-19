package com.demo.aiknowledge.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DashboardStats {
    private Long userCount;
    private Long docCount;
    private Long qaCount;
    private Double hitRate;
    
    private List<Map<String, Object>> hotDocs;
    private List<Map<String, Object>> topQuestions;
    private List<Map<String, Object>> unansweredQuestions;
    private List<Map<String, Object>> questionTrends;
    /** 提问类型分布(饼图用): [{name: task_type, value: 数量}] */
    private List<Map<String, Object>> taskTypeDistribution;
}
