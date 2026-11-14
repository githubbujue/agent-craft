package com.demo.aiknowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(180000); // AI 服务响应较慢（向量检索+LLM），设置3分钟超时
        return new RestTemplate(factory);
    }

    /**
     * 摘要专用：读超时 10s。
     *
     * <p>不能复用上面的 restTemplate —— 那个读超时 180s（给问答用），
     * 摘要任务一旦卡在网络等待上会长时间占住线程；而且摘要失败可降级，没必要等。
     */
    @Bean("summaryRestTemplate")
    public RestTemplate summaryRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .baseUrl("http://localhost:8000") // Python服务地址
                .build();
    }
}
