package com.demo.aiknowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话上下文实体
 * 用于管理对话的短期记忆和长期记忆
 */
@Data
@TableName("conversation_context")
public class ConversationContext {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对话ID
     */
    private Long conversationId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 对话摘要（长期记忆）
     * 存储对话的核心内容和关键信息
     */
    private String summary;

    /**
     * 对话向量 —— <b>预留字段，从未写入，目前恒为 null</b>。
     *
     * <p>跨会话召回现在走 2-gram 文本相似度（{@code util/TextSimilarity}），而不是向量检索。
     * 原因：向量库在 Python 侧（FAISS/Milvus），而召回逻辑在 Java 侧，
     * 跨进程检索的架构成本远高于收益；且召回对象是"已浓缩的会话摘要"，短文本上 2-gram 够用。
     *
     * <p>将来会话量上来（几百条以上）时：由 Python 侧生成向量回写此字段，
     * 并提供检索接口，Java 侧改为调用即可。在此之前不要把它当成已实现的能力。
     */
    private String embedding;

    /**
     * 摘要覆盖到的最后一条消息ID（游标）
     * 用于增量摘要：只处理 id 大于该值的消息
     */
    private Long summaryMsgId;

    /**
     * 摘要版本号，每次重新生成 +1
     */
    private Integer summaryVersion;

    /**
     * 摘要状态：none / generating / ready / failed
     */
    private String summaryStatus;

    /**
     * 摘要最后生成时间
     */
    private LocalDateTime summaryUpdateTime;

    /**
     * 上下文窗口大小
     */
    private Integer windowSize;

    /**
     * 重要性评分
     * 用于决定哪些对话需要长期保存
     */
    private Double importanceScore;

    /**
     * 最后更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 内部类：对话消息
     */
    @Data
    public static class ContextMessage {
        private String role; // user or assistant
        private String content;
        private String sources; // 来源信息，JSON格式
        private LocalDateTime timestamp;
        private Double importance; // 消息重要性评分
    }

    /**
     * 内部类：对话摘要
     */
    @Data
    public static class ConversationSummary {
        private String keyTopics; // 关键话题
        private String keyDecisions; // 关键决策
        private String userPreferences; // 用户偏好
        private String actionItems; // 待办事项
        private LocalDateTime lastUpdated;
    }
}