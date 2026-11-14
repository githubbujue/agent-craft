package com.demo.aiknowledge.service;

import com.demo.aiknowledge.entity.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 摘要生成器：把"增量消息 + 旧摘要"交给 Python 侧的 LLM 接口，返回合并后的新摘要。
 *
 * <p>所有失败路径都返回 {@link Optional#empty()}，由调用方降级到本地关键词桩 ——
 * 摘要只是上下文补充，任何情况下都不能让消息落库或问答主流程失败。
 */
@Slf4j
@Component
public class SummaryGenerator {

    private final RestTemplate summaryRestTemplate;
    private final String aiServiceUrl;
    private final String internalToken;

    /** 只在第一次缺配置时告警，避免每条消息刷一行日志 */
    private volatile boolean tokenMissingWarned = false;

    public SummaryGenerator(@Qualifier("summaryRestTemplate") RestTemplate summaryRestTemplate,
                            @Value("${ai.service.url}") String aiServiceUrl,
                            @Value("${internal.token:}") String internalToken) {
        this.summaryRestTemplate = summaryRestTemplate;
        this.aiServiceUrl = aiServiceUrl;
        this.internalToken = internalToken;
    }

    /**
     * 调用内部摘要接口。
     *
     * @param conversationId 会话ID（仅用于日志）
     * @param oldSummary     上一版摘要，允许为空
     * @param messages       增量消息（游标之后的部分）
     * @return 新摘要；LLM 不可用/超时/返回非法时为空
     */
    public Optional<String> summarize(Long conversationId, String oldSummary, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Optional.empty();
        }

        // token 未配置：直接降级，不发请求。
        // 若这里"拒绝调用"，上线时漏配一个变量就会让摘要功能整体失效，且失败得很安静
        if (internalToken == null || internalToken.isBlank()) {
            if (!tokenMissingWarned) {
                log.warn("[Summary] internal.token 未配置，摘要改用本地关键词兜底（不再尝试调用 Python）");
                tokenMissingWarned = true;
            }
            return Optional.empty();
        }

        try {
            List<Map<String, String>> payloadMessages = new ArrayList<>(messages.size());
            for (Message message : messages) {
                Map<String, String> item = new HashMap<>(2);
                item.put("role", message.getRole() == null ? "user" : message.getRole());
                item.put("content", message.getContent() == null ? "" : message.getContent());
                payloadMessages.add(item);
            }

            Map<String, Object> body = new HashMap<>(4);
            body.put("conversation_id", conversationId);
            body.put("old_summary", oldSummary);
            body.put("messages", payloadMessages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            ResponseEntity<Map> response = summaryRestTemplate.postForEntity(
                    aiServiceUrl + "/internal/summarize",
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object summary = response.getBody().get("summary");
                if (summary instanceof String text && !text.isBlank()) {
                    return Optional.of(text);
                }
            }
            log.warn("[Summary] 内部摘要接口返回异常 - conversationId: {}, status: {}",
                    conversationId, response.getStatusCode());
            return Optional.empty();

        } catch (Exception e) {
            log.warn("[Summary] 调用内部摘要接口失败，降级到关键词兜底 - conversationId: {}, error: {}",
                    conversationId, e.getMessage());
            return Optional.empty();
        }
    }
}
