package com.demo.aiknowledge.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试前置条件：MySQL / Redis 可达才执行，否则整类跳过。
 *
 * <p>为什么不用 {@code @EnabledIfEnvironmentVariable(named="CI", matches="false")}：
 * 那只解决了 CI 一种情况。本地开发者没启 MySQL 时照样连不上，报的是 Hikari 连接超时，
 * 看不出是"环境没准备"还是"代码写坏了"，排查成本很高。直接探测端口，两种情况都能区分。
 *
 * <p>为什么探测必须在条件里做、不能用 {@code Assumptions.assumeTrue} 放进 {@code @BeforeAll}：
 * {@code @SpringBootTest} 的上下文由 SpringExtension 在 beforeAll 回调里加载，
 * 连不上库时抛的是异常而不是跳过，测试直接红。条件求值发生在 beforeAll 之前，
 * 这也是唯一能在"Spring 上下文尚未加载"时拦住它的时机。
 *
 * <p>强制跳过：{@code mvn test "-Dtest.infra.disabled=true"}
 * （CI runner 上万一恰好有别的进程占着 3306，用这个兜底）。
 * 注意 Windows PowerShell 里必须加引号: 不加会把参数按点拆成 {@code -Dtest} 和
 * {@code .infra.disabled=true}，Maven 报 {@code Unknown lifecycle phase '.infra.disabled=true'}。
 */
public class LocalInfraCondition implements ExecutionCondition {

    private static final int CONNECT_TIMEOUT_MS = 500;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (Boolean.parseBoolean(System.getProperty("test.infra.disabled", "false"))) {
            return ConditionEvaluationResult.disabled("已显式关闭集成测试 (test.infra.disabled=true)");
        }

        String mysqlHost = env("DB_HOST", "127.0.0.1");
        int mysqlPort = envPort("DB_PORT", 3306);
        if (!reachable(mysqlHost, mysqlPort)) {
            return ConditionEvaluationResult.disabled(
                    "跳过集成测试: MySQL " + mysqlHost + ":" + mysqlPort + " 不可达");
        }

        String redisHost = env("REDIS_HOST", "127.0.0.1");
        int redisPort = envPort("REDIS_PORT", 6379);
        if (!reachable(redisHost, redisPort)) {
            return ConditionEvaluationResult.disabled(
                    "跳过集成测试: Redis " + redisHost + ":" + redisPort + " 不可达");
        }

        return ConditionEvaluationResult.enabled(
                "MySQL " + mysqlHost + ":" + mysqlPort + " / Redis " + redisHost + ":" + redisPort + " 可达");
    }

    /**
     * 端口与环境变量保持同一套默认值，避免"端口探测通了但 Spring 连的是别处"的假阳性。
     * 见 application.yml: ${DB_HOST:localhost} / ${DB_PORT:3306} / ${REDIS_PORT:6379}
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envPort(String name, int fallback) {
        try {
            return Integer.parseInt(env(name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
