"""agent 包 —— 仅保留事件总线（可观测性基础设施）。

原编排骨架（Orchestrator / Planner / Executor / AgentState / policies / MemoryAgent）
已于本次重构删除: 它们从未被任何入口调用（`_ask_with_orchestrator()` 零调用点，
/api/ask 与 /api/agent/run 都走 RouterAgent + 固定流程）。其中唯一被主链路使用的
`Planner.evaluate_retrieval_sufficiency()` 已迁至 `core/retrieval_evaluator.py`。

保留 events.py 是因为 run_recorder / retrieval_evaluator / faithfulness_judge /
router_agent / api 层都依赖 EventBus 做业务零侵入埋点。
"""
from agent.events import EventBus, Event, EventType

__all__ = ["EventBus", "Event", "EventType"]
