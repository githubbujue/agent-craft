package com.demo.aiknowledge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-gram 相似度的行为约束。
 *
 * <p>这些用例的存在意义：改成 2-gram 之前，{@code findSimilarConversations}
 * 用空白分词，中文查询必然零命中，而没有任何测试能发现（它零调用）。
 */
class TextSimilarityTest {

    private static final String DOCKER_SUMMARY =
            "用户想用 Docker Compose 部署项目，监控方案尚未确定";

    @Test
    @DisplayName("中文召回提问能命中相关摘要")
    void chineseQueryHitsRelatedSummary() {
        double score = TextSimilarity.coverage("上次说的部署方案呢", DOCKER_SUMMARY);
        assertTrue(score >= 0.25,
                "应命中「部署」「方案」，实际 score=" + score);
    }

    @Test
    @DisplayName("无关提问不命中")
    void unrelatedQueryScoresZero() {
        double score = TextSimilarity.coverage("今天天气怎么样", DOCKER_SUMMARY);
        assertEquals(0.0, score, 0.0001, "不该命中任何 gram，实际 score=" + score);
    }

    @Test
    @DisplayName("时间指代被停用，单独出现不产生命中")
    void timeReferenceIsStopWord() {
        // "上次那个" 若不被停用，会对任何摘要都产生命中，等于没过滤
        double score = TextSimilarity.coverage("上次那个", DOCKER_SUMMARY);
        assertEquals(0.0, score, 0.0001, "时间指代不该贡献得分，实际 score=" + score);
    }

    @Test
    @DisplayName("英文关键词整词匹配，不被拆碎")
    void englishTokenKeptWhole() {
        double score = TextSimilarity.coverage("Docker", DOCKER_SUMMARY);
        assertEquals(1.0, score, 0.0001, "docker 应整词命中");
    }

    @Test
    @DisplayName("典型召回提问命中，但覆盖率低于 0.25（阈值标定的依据）")
    void typicalRecallQuestionIsRelevant() {
        String query = "上次我们说的部署方案是什么来着";
        // 真实数据标定：这条提问切出 11 个 gram，只命中「部署」「方案」，覆盖率仅 0.18。
        // 只靠覆盖率阈值会漏掉这种最典型的召回提问，因此需要"最少命中 2 个 gram"配合。
        double score = TextSimilarity.coverage(query, DOCKER_SUMMARY);
        assertTrue(score < 0.25, "覆盖率应低于 0.25，实际=" + score);
        assertTrue(TextSimilarity.isRelevant(query, DOCKER_SUMMARY, 0.15, 2),
                "覆盖率 0.15 + 命中 2 个 gram 时应判定相关，实际 score=" + score);
    }

    @Test
    @DisplayName("只命中一个泛词不算相关")
    void singleGenericWordIsNotEnough() {
        // 只命中「方案」，不能因此把任何提到"方案"的摘要都召回
        assertFalse(TextSimilarity.isRelevant("这个方案怎么样", DOCKER_SUMMARY, 0.15, 2));
    }

    @Test
    @DisplayName("空输入安全返回 0")
    void blankInputsAreSafe() {
        assertEquals(0.0, TextSimilarity.coverage(null, DOCKER_SUMMARY), 0.0001);
        assertEquals(0.0, TextSimilarity.coverage("部署", null), 0.0001);
        assertEquals(0.0, TextSimilarity.coverage("", ""), 0.0001);
        // 全是停用词的提问，提取不出 gram，也不能除零
        assertEquals(0.0, TextSimilarity.coverage("那个是什么", DOCKER_SUMMARY), 0.0001);
    }
}
