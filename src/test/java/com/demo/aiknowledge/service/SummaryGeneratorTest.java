package com.demo.aiknowledge.service;

import com.demo.aiknowledge.entity.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 摘要生成器：失败必须降级为空，绝不能把异常/空串抛给调用方
 */
@ExtendWith(MockitoExtension.class)
class SummaryGeneratorTest {

    private static final String URL = "http://127.0.0.1:8000/api";

    @Mock
    private RestTemplate restTemplate;

    private SummaryGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SummaryGenerator(restTemplate, URL, "test-token");
    }

    private List<Message> messages() {
        Message msg = new Message();
        msg.setId(1L);
        msg.setConversationId(100L);
        msg.setRole("user");
        msg.setContent("帮我看看部署方案");
        return List.of(msg);
    }

    @Test
    @DisplayName("内部接口返回摘要时正确解析")
    void returnsSummaryFromResponse() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("summary", "用户关注部署方案"), HttpStatus.OK));

        Optional<String> result = generator.summarize(100L, null, messages());

        assertTrue(result.isPresent());
        assertEquals("用户关注部署方案", result.get());
        verify(restTemplate).postForEntity(eq(URL + "/internal/summarize"), any(), eq(Map.class));
    }

    @Test
    @DisplayName("token 未配置时不发请求，直接降级")
    void skipsHttpWhenTokenMissing() {
        SummaryGenerator noToken = new SummaryGenerator(restTemplate, URL, "  ");

        Optional<String> result = noToken.summarize(100L, null, messages());

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("接口返回 5xx 时降级")
    void returnsEmptyOnServerError() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));

        assertTrue(generator.summarize(100L, null, messages()).isEmpty());
    }

    @Test
    @DisplayName("网络异常/超时降级，不向上抛")
    void returnsEmptyOnException() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("read timed out"));

        assertTrue(generator.summarize(100L, null, messages()).isEmpty());
    }

    @Test
    @DisplayName("返回体缺少 summary 字段时降级")
    void returnsEmptyWhenSummaryMissing() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("covered_messages", 2), HttpStatus.OK));

        assertTrue(generator.summarize(100L, null, messages()).isEmpty());
    }

    @Test
    @DisplayName("空消息列表不发请求")
    void returnsEmptyForEmptyMessages() {
        assertTrue(generator.summarize(100L, "旧摘要", Collections.emptyList()).isEmpty());
        verifyNoInteractions(restTemplate);
    }
}
