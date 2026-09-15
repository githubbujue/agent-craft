package com.demo.aiknowledge.service.impl;

import com.demo.aiknowledge.common.ErrorCode;
import com.demo.aiknowledge.dto.AgentRunRequest;
import com.demo.aiknowledge.dto.AgentRunResponse;
import com.demo.aiknowledge.exception.BusinessException;
import com.demo.aiknowledge.service.AgentRunService;
import com.demo.aiknowledge.service.AgentService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * Agent 主动触发服务 —— 目前未实现, 故显式废弃(调用即报错)。
 *
 * <p>为什么显式报错而不是保留原实现: 原先的 runAgent / retryAgentRun 是"桩实现"——
 * 只往 agent_run 表插一条空记录并返回模拟响应, 并不调用 Python 的 Agent 执行接口。
 * 这属于"静默失效": 调用方拿到 HTTP 200 + 假数据, 不报任何错, 反而更容易被误用。
 * 与其留一个静默返回假数据的入口, 不如让它直接报错。
 *
 * <p>为什么现在没有调用方: 系统的真实 Agent 执行不经过这里, 而是发生在对话链路内部 ——
 * Java /api/chat/stream → Python RouterAgent.route_stream() → 各领域 Agent;
 * 执行轨迹由 Python 侧 EventBus → RunRecorder 落 agent_run / agent_step 表,
 * 再由 AgentRunController(/api/agent-run/**, 只读查询)对管理端暴露。
 *
 * <p>将来若需要"不经过聊天、主动触发 Agent"(例如管理端重跑某次运行、定时知识巡检),
 * 应把实现接上 Python 已有的 POST /api/agent/run(见 python-service/api/agent_routes.py),
 * 而不是恢复原来的桩逻辑。
 */
@Service
public class AgentServiceImpl implements AgentService {

    @Resource
    private AgentRunService agentRunService;

    @Override
    public AgentRunResponse runAgent(AgentRunRequest request) {
        throw new BusinessException(ErrorCode.NOT_IMPLEMENTED,
                "Agent 主动触发未实现: 真实执行请走对话接口 /api/chat/stream; "
                        + "若确需主动触发, 请接入 Python 的 POST /api/agent/run");
    }

    @Override
    public boolean cancelAgentRun(String runId) {
        // 注: 本方法未被任何 Controller 暴露(AgentController 只暴露 /run 与两个查询接口),
        // 保留仅为接口完整性。状态值统一大写, 对齐前端 statusLabels 的既有约定
        // (踩坑笔记第 3 条: 数据层小写 / 展示层大写 曾导致状态列空白)。
        return agentRunService.updateAgentRunStatus(runId, "CANCELLED");
    }

    @Override
    public AgentRunResponse retryAgentRun(String runId) {
        throw new BusinessException(ErrorCode.NOT_IMPLEMENTED,
                "Agent 重试未实现: 原因同 runAgent —— 原桩实现只写假记录而不真正执行");
    }
}
