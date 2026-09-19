package com.demo.aiknowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("admin_message")
public class AdminMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private String sources;
    private String taskType;
    private String feedbackType;        // 反馈类型（like/dislike）
    private LocalDateTime feedbackTime; // 反馈时间
    private LocalDateTime createTime;
}