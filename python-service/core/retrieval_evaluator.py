"""评估层统一入口 —— 把 agent/planner.py 的评估能力接进活链路

职责 (只做判断, 不修改业务数据 —— 五层架构中的评估层):
    1. evaluate_retrieval: 检索充分性评估 → 发布 sufficiency_evaluated 事件
    2. evaluate_answer:    答案质量轻量评估 (规则版) → 发布 answer_generated 事件

设计决策 (面试要点):
    1. 规则先行, 不用 LLM-as-judge —— 每次评估多烧一次 LLM 调用, 慢且贵;
       规则版零成本零延迟, LLM 评估作为演进方向
    2. fail-open: 评估本身出异常时降级为"充分", 绝不让遥测/评估阻塞主流程
    3. 留痕走 EventBus (run_recorder 落 agent_step), 非请求上下文(无 run_id)自动跳过,
       防止产生关联不上 agent_run 的孤儿步骤
"""
import logging
import time

from agent.events import EventBus, Event
from core.run_context import get_run_id

logger = logging.getLogger(__name__)

_planner = None  # 惰性加载, 避免模块导入时就初始化意图分类器


def _get_planner():
    global _planner
    if _planner is None:
        from agent.planner import Planner
        _planner = Planner()
    return _planner


def extract_scores(docs: list) -> list:
    """从 docs 提取相似度分数: doc.score → metadata.score → 默认 0.5"""
    scores = []
    for doc in docs:
        s = getattr(doc, "score", None)
        if s is None:
            md = getattr(doc, "metadata", None)
            if md is None and isinstance(doc, dict):
                md = doc.get("metadata") or {}
            s = (md or {}).get("score", 0.5)
        try:
            scores.append(float(s))
        except (TypeError, ValueError):
            scores.append(0.5)
    return scores


def _publish(event_type: str, data: dict) -> None:
    """发布留痕事件; 无 run_id 跳过, 异常只记日志"""
    run_id = get_run_id()
    if not run_id:
        return
    try:
        EventBus().publish(Event(event_type=event_type, run_id=run_id, data=data))
    except Exception as e:
        logger.warning(f"[Evaluator] publish {event_type} failed: {e}")


def publish_step(event_type: str, output: dict, duration_ms: float = None,
                 input_text: str = None) -> None:
    """通用步骤留痕入口: 记忆读写/重排序/问题改写等非评估环节走同一通道

    调用方自测耗时, 本函数只负责封装与发布 —— 遥测零侵入业务。
    """
    _publish(event_type, {
        "input": (input_text or "")[:200],
        "output": output,
        "duration_ms": int(duration_ms) if duration_ms else None,
    })


def evaluate_retrieval(docs: list, question: str, chain: str = "",
                       scores: list = None, search_ms: float = None,
                       publish_retrieval_event: bool = True):
    """检索充分性评估 + 事件留痕, 返回 SufficiencyResult

    Args:
        docs: 检索到的文档列表
        question: 触发检索的问题(或子问题)
        chain: 链路标识 (L1/L2/L3/STREAM), 落库用
        scores: 相似度分数; None 时自动从 docs 提取
        search_ms: 检索阶段耗时(毫秒), 用于步骤轨迹展示
        publish_retrieval_event: 是否发布"知识检索"事件 —— L2 链路的检索
            事件由 retrieval_agent 在检索完成后立即发布(保证时序), 此处置 False
    """
    from agent.planner import SufficiencyResult

    if scores is None:
        scores = extract_scores(docs)
    avg_score = round(sum(scores) / len(scores), 3) if scores else 0.0

    # 发布"知识检索"步骤事件（L2 链路由 retrieval_agent 在检索点即时发布, 此处跳过）
    if publish_retrieval_event:
        _publish("retrieval_completed", {
            "input": question[:200],
            "output": {"chunk_count": len(docs), "avg_score": avg_score},
            "duration_ms": int(search_ms) if search_ms else None,
        })

    # 分数语义已校准（vector_store 现在返回真实余弦相似度 0-1）, 分数规则参与判定
    start = time.time()
    try:
        result = _get_planner().evaluate_retrieval_sufficiency(docs, question, scores)
    except Exception as e:
        logger.warning(f"[Evaluator] sufficiency eval failed, fail-open: {e}")
        result = SufficiencyResult(
            is_sufficient=True, confidence=0.5,
            reasoning=f"评估异常已降级处理: {e}",
            missing_aspects=[], suggestions=[])

    _publish("sufficiency_evaluated", {
        "input": question[:200],
        "output": {
            "chain": chain,
            "chunk_count": len(docs),
            "avg_score": avg_score,
            "is_sufficient": result.is_sufficient,
            "confidence": result.confidence,
            "reasoning": result.reasoning,
        },
        "duration_ms": int((time.time() - start) * 1000),
    })
    logger.info(f"[Evaluator][{chain}] sufficient={result.is_sufficient} "
                f"confidence={result.confidence} chunks={len(docs)} avg={avg_score}")
    return result


# 命中即视为"兜底/失败话术", 质量分扣减
_LOW_QUALITY_MARKERS = ("抱歉，知识库中没有找到", "没有找到与您问题相关",
                        "知识库中未找到相关信息")


def evaluate_answer(question: str, answer: str, sources: list,
                    chain: str = "", gen_ms: float = None) -> dict:
    """答案质量轻量评估(规则版), 发布 answer_generated 事件, 返回指标 dict

    评分规则: 有引用来源 +50, 非兜底话术 +30, 长度合理 +20 → 满分 100
    gen_ms: 答案生成(LLM 调用)耗时, 与评估耗时合计为步骤 duration
    """
    start = time.time()
    metrics = {
        "chain": chain,
        "answer_length": len(answer),
        "has_sources": bool(sources),
        "source_count": len(sources or []),
        "is_fallback": any(m in answer for m in _LOW_QUALITY_MARKERS),
    }
    score = 0
    if metrics["has_sources"]:
        score += 50
    if not metrics["is_fallback"]:
        score += 30
    if 20 <= metrics["answer_length"] <= 5000:
        score += 20
    metrics["quality_score"] = score

    _publish("answer_generated", {
        "input": question[:200],
        "output": metrics,
        "duration_ms": int((time.time() - start) * 1000) + int(gen_ms or 0),
    })
    logger.info(f"[Evaluator][{chain}] answer quality={score} fallback={metrics['is_fallback']}")
    return metrics
