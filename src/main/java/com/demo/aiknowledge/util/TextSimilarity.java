package com.demo.aiknowledge.util;

import java.util.HashSet;
import java.util.Set;

/**
 * 轻量中文文本相似度：2-gram 覆盖率。
 *
 * <p>为什么不用分词库：项目没有引入 HanLP/jieba 这类依赖，为一个召回排序引入
 * 几十 MB 的模型不划算；而召回对象是"已经浓缩过的会话摘要"，短文本上 2-gram 够用。
 *
 * <p>为什么不能用空白分词：中文没有空格，{@code "上次说的部署方案呢".split("\\s+")}
 * 只会得到一个"关键词"，再 {@code summary.contains(整句)} 永远为 false ——
 * 这是 {@code findSimilarConversations} 之前零命中的真正原因。
 *
 * <p>停用词只放虚词/疑问词/时间指代，不放名词。像"方案""问题"这类泛名词如果停掉，
 * 召回会连同真实话题一起丢失。
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /**
     * 停用词：命中这些片段不参与打分。
     *
     * <p>时间指代（上次/之前/刚才）必须停掉 —— 否则"上次我们说的那个…"这种
     * 典型召回提问会对任何摘要都产生命中，等于没有过滤。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "有", "没", "不", "就", "也", "都",
            "我", "你", "他", "她", "它", "我们", "你们", "他们", "咱们",
            "这", "那", "这个", "那个", "这些", "那些", "这样", "那样",
            "什么", "怎么", "如何", "为何", "为什么", "哪些", "哪个",
            "吗", "呢", "吧", "啊", "呀", "哦", "嗯",
            "上次", "上回", "之前", "刚才", "以前", "上次说", "说过", "提到",
            "一下", "一个", "可以", "能否", "帮我", "请问", "告诉", "说说", "讲讲",
            "the", "and", "for", "with", "what", "how", "this", "that"
    );

    /**
     * 提取 2-gram 集合。
     *
     * <p>中文按连续片段切 2-gram；英文/数字保留整词（{@code docker} 比 {@code do}/{@code oc}
     * 更有区分度，拆碎反而噪声大）。
     */
    public static Set<String> twoGrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null || text.isBlank()) {
            return grams;
        }
        // 非中文/字母/数字一律当分隔符，避免标点进入 gram
        String cleaned = text.toLowerCase().replaceAll("[^\\u4e00-\\u9fa5a-z0-9]+", " ");
        for (String token : cleaned.split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.matches("[a-z0-9]+")) {
                if (!STOP_WORDS.contains(token)) {
                    grams.add(token);
                }
                continue;
            }
            // 单字片段没有 2-gram，跳过
            for (int i = 0; i + 1 < token.length(); i++) {
                String gram = token.substring(i, i + 2);
                if (!STOP_WORDS.contains(gram)) {
                    grams.add(gram);
                }
            }
        }
        return grams;
    }

    /**
     * query 的 2-gram 有多少比例出现在 text 里，取值 0~1。
     *
     * <p>用覆盖率而不是 Jaccard：摘要通常比提问长得多，Jaccard 会被摘要长度稀释，
     * 导致"短提问命中长摘要"的分数天然偏低。
     */
    public static double coverage(String query, String text) {
        Set<String> queryGrams = twoGrams(query);
        if (queryGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> textGrams = twoGrams(text);
        if (textGrams.isEmpty()) {
            return 0.0;
        }
        long hit = queryGrams.stream().filter(textGrams::contains).count();
        return (double) hit / queryGrams.size();
    }

    /**
     * 判断 query 与 text 是否相关：命中 gram 数够多，且覆盖率达标。
     *
     * <p>为什么不能只看覆盖率：「上次我们说的部署方案是什么来着」会切出 11 个 gram
     * （含"次我""们说""的部"这类跨词边界的噪声），即便命中了"部署""方案"两个真正
     * 有价值的词，覆盖率也只有 0.18。纯覆盖率阈值要么漏掉这种典型召回提问，
     * 要么放宽到把无关摘要也放进来。
     *
     * <p>为什么还要限制最少命中数：单个 gram 命中太容易是巧合 ——
     * 「这个方案怎么样」只会命中"方案"，不该因此把任何提到"方案"的摘要都召回。
     */
    public static boolean isRelevant(String query, String text, double minCoverage, int minHitGrams) {
        Set<String> queryGrams = twoGrams(query);
        Set<String> textGrams = twoGrams(text);
        if (queryGrams.isEmpty() || textGrams.isEmpty()) {
            return false;
        }
        long hit = queryGrams.stream().filter(textGrams::contains).count();
        return hit >= minHitGrams && (double) hit / queryGrams.size() >= minCoverage;
    }
}
