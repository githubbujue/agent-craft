package com.demo.aiknowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用CSRF保护，对于前后端分离的应用
            .csrf(csrf -> csrf.disable())
            // 启用CORS支持
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // 配置请求授权
            .authorizeHttpRequests(auth -> auth
                // SSE(SseEmitter) 会触发 Servlet 容器的 ASYNC 异步再分发; STATELESS 模式下
                // SecurityContext 不跨分发保留, 若不放行会在异步分发阶段抛 AccessDenied
                // (表现为流被掐断: "Response ended prematurely")。
                // 安全性说明: 首次 REQUEST 分发已完成鉴权, ASYNC/ERROR 只是同一请求的
                // 内部再分发, 放行它们不构成新的访问入口。
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,
                                        jakarta.servlet.DispatcherType.ERROR).permitAll()
                // 允许OPTIONS预检请求
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                // 允许所有/api/auth下的请求
                .requestMatchers("/api/auth/**").permitAll()
                // 允许所有/api/admin/login请求
                .requestMatchers("/api/admin/login").permitAll()
                // 允许图片访问路径
                .requestMatchers("/api/chat/view/image/**").permitAll()
                // 管理员接口需要ADMIN角色
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // ===== 鉴权补全: 以下管理能力接口未以 /api/admin 为前缀, 原先只要求"已登录",
                //       普通用户即可访问, 属垂直越权。逐一收紧为仅 ADMIN =====
                // 管理助手对话: 可执行知识巡检等管理操作, 绝不许可普通用户
                .requestMatchers("/api/admin-chat/**").hasRole("ADMIN")
                // Agent 执行留痕查询: 含全体用户的提问内容/会话归属, 仅管理员可查
                .requestMatchers("/api/agent-run/**").hasRole("ADMIN")
                // Agent 运行与步骤查询: 属管理/运维能力
                .requestMatchers("/api/agent/**").hasRole("ADMIN")
                // 缓存管理(含清空缓存域/刷新等破坏性操作): 属运维能力, 仅管理员
                .requestMatchers("/api/cache/**").hasRole("ADMIN")
                // 用户接口需要USER或ADMIN角色
                .requestMatchers("/api/chat/**").hasAnyRole("USER", "ADMIN")
                // 知识库写操作(上传/删除)属管理能力, 收紧为仅 ADMIN —— 普通用户只能浏览
                // 注意: 规则按声明顺序匹配, 具体规则必须放在下方通配规则之前
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/knowledge/upload").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/knowledge/**").hasRole("ADMIN")
                // 知识库浏览(列表/详情): 登录用户均可
                .requestMatchers("/api/knowledge/**").hasAnyRole("USER", "ADMIN")
                // 其他请求需要认证
                .anyRequest().authenticated()
            )
            // 添加JWT过滤器
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // 禁用默认的登录表单
            .formLogin(form -> form.disable())
            // 禁用默认的HTTP基本认证
            .httpBasic(httpBasic -> httpBasic.disable());

        // 配置无状态会话管理
        http.sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000")); // 前端开发服务器
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(Arrays.asList("Authorization")); // 允许前端访问Authorization header

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
