"""LLM-as-judge 内容忠实度评估 —— 评估层的第三层(规则版之上)

背景与定位:
    既有评估是纯规则版(检索充分性 / 答案质量), 零成本零延迟, 但它回答不了最关键的
    问题: 答案里的陈述, 检索资料里到底有没有依据?
    内容忠实度(faithfulness)只有 LLM 能判 —— 这是 RAGAS 一类框架的核心指标, 也是
    评估层从"答案像不像话"走向"答案有没有编"的分水岭。

设计原则(面试要点):
    1. **不阻塞主链路**: 评判放在 daemon 线程执行, 用户拿到的答案与响应时间不受影响。
       规则评估"零成本零延迟"的卖点保持不变 —— LLM 评判本质是"后台的更聪明的遥测",
       与 run_recorder 的"遥测失败绝不影响主流程"是同一哲学。
    2. **fail-open + 静默**: 评判异常只记日志; 总开关可整体关闭, 采样率可按比例运行
       (全量评判有 token 成本)。
    3. **rubric 必须与生成侧 prompt 对齐**: grounded generation 明确允许"资料部分覆盖时
       补充通用知识并标注(以下为通用知识补充)"。已标注的补充不算幻觉 —— rubric 里
       必须写明, 否则评估会与生成互相打架(踩坑笔记 9.5 记录过这类"两层评估语义鸿沟")。
    4. **run_id 必须显式传递**: run_id 存在 contextvars 里, 新线程读不到 —— 必须在
       触发点(主线程)捕获后传入, 否则事件会被 _publish 静默丢弃, 功能看似生效实则
       没有任何留痕。
"""
import json
import logging
import random
import re
import threading
import time

from agent.events import EventBus, Event
from core.config import config
from core.run_context import get_run_id

logger = logging.getLogger(__name__)

# 送几段资料给 judge —— 控制 token 成本
_MAX_CONTEXTS = 5
# 单段资料截断长度
_MAX_CONTEXT_CHARS = 1200

# 兜底解析用: judge 输出被 max_tokens 截断时, 至少把核心分数抠出来(真实踩过的坑)
_FAITHFULNESS_RE = re.compile(r'"faithfulness"\s*:\s*([0-9]*\.?[0-9]+)')
_CLAIMS_RE = re.compile(r'"unsupported_claims"\s*:\s*\[(.*?)(?:\]|$)', re.S)
_QUOTED_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

_JUDGE_PROMPT = """你是 RAG 系统的"答案忠实度"评审员。请判断【回答】中的事实性陈述能否在【检索资料】中找到依据。

【用户问题】
{question}

【检索资料】
{contexts}

【回答】
{answer}

评判规则:
1. 只评判事实性陈述(概念、数据、结论、步骤、结论性判断等); 称呼、客套话、引导语不计入。
2. 回答中出现了资料里没有的事实性内容 → 记为"无依据陈述"。
3. 例外: 若回答明确标注了"（以下为通用知识补充）"或类似字样, 则其后内容属于刻意补充,
   不计为幻觉, 不扣分; 若回答声明"知识库中未找到相关信息"并给出通用知识, 同样不计为幻觉。
4. faithfulness 取"有依据的事实性陈述"占全部事实性陈述的比例; 没有事实性陈述时给 1.0。
5. 输出务必精简: unsupported_claims 最多 3 条、每条不超过 60 字; reasoning 不超过 50 字。

只输出 JSON, 不要任何解释文字或代码块标记:
{{"faithfulness": 0.0~1.0 的数字,
  "unsupported_claims": ["...", "..."],
  "reasoning": "一句话"}}
"""


def _get_llm():
    """惰性取单例, 避免模块导入期的循环依赖"""
    from core.llm import llm_service
    return llm_service


def _publish_with_run_id(run_id: str, event_type: str, data: dict) -> None:
    """显式传 run_id 发布事件

    不能复用 retrieval_evaluator._publish —— 它靠 get_run_id() 从 contextvars 取值,
    而后台线程读不到主线程的 contextvars, 会导致事件被静默丢弃。
    """
    try:
        EventBus().publish(Event(event_type=event_type, run_id=run_id, data=data))
    except Exception as e:
        logger.warning(f"[FaithfulnessJudge] publish {event_type} failed: {e}")


def _extract_contexts(docs: list) -> list:
    """从检索结果提取资料原文(兼容 LangChain Document 与 dict)"""
    contexts = []
    for doc in (docs or [])[:_MAX_CONTEXTS]:
        text = getattr(doc, "page_content", None)
        if text is None and isinstance(doc, dict):
            text = doc.get("content") or doc.get("page_content")
        if not text:
            continue
        contexts.append(str(text)[:_MAX_CONTEXT_CHARS])
    return contexts


def _parse_judge_output(raw: str) -> dict:
    """解析 judge 输出 —— 容错处理代码块包裹、前后缀文字、以及被 max_tokens 截断的 JSON

    截断是真实踩到过的坑: reasoning 写得太长会把 JSON 尾部切断, 导致整体解析失败、
    连分数都拿不到。所以 JSON 解析失败时退化为正则抠取核心字段, 能拿多少算多少。
    """
    text = (raw or "").strip()
    if "```" in text:
        parts = text.split("```")
        if len(parts) >= 3:
            text = parts[1]
        if text.lstrip().lower().startswith("json"):
            text = text.lstrip()[4:]

    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end > start:
        try:
            data = json.loads(text[start:end + 1])
            return {
                "faithfulness": max(0.0, min(1.0, round(float(data.get("faithfulness", 0.0)), 3))),
                "unsupported_claims": [str(c)[:200] for c in (data.get("unsupported_claims") or [])][:5],
                "reasoning": str(data.get("reasoning") or "")[:500],
            }
        except Exception:
            pass  # JSON 不完整/非法 → 落到下面的正则兜底

    m = _FAITHFULNESS_RE.search(text)
    if not m:
        raise ValueError(f"judge 输出中找不到 faithfulness: {raw[:200]}")
    claims_m = _CLAIMS_RE.search(text)
    claims = _QUOTED_RE.findall(claims_m.group(1)) if claims_m else []
    return {
        "faithfulness": max(0.0, min(1.0, round(float(m.group(1)), 3))),
        "unsupported_claims": [c[:200] for c in claims][:5],
        "reasoning": "（JSON 输出被截断, 已按正则兜底解析）",
    }


def _judge_worker(question: str, answer: str, docs: list, chain: str, run_id: str) -> None:
    """后台线程体: 调 LLM 评判 + 发布留痕事件。任何异常都不外抛。"""
    started = time.time()
    raw = ""  # 提到 try 之外: 失败分支要靠它还原"模型到底吐了什么"
    try:
        contexts = _extract_contexts(docs)
        if not contexts:
            return
        prompt = _JUDGE_PROMPT.format(
            question=question[:500],
            contexts="\n\n---\n\n".join(contexts),
            answer=answer[:3000],
        )
        # temperature=0: 评判任务要确定性, 不能随机
        # max_tokens=1200: prompt 已约束输出精简, 但留足余量防止 JSON 被截断
        # 空输出重试一次: 这类失败多为服务端限流/超时等偶发抖动, 重试命中率高
        for attempt in range(2):
            raw = _get_llm().generate(prompt, temperature=0.0, max_tokens=1200)
            if _FAITHFULNESS_RE.search(raw or ""):
                break
            if attempt == 0:
                logger.info(f"[FaithfulnessJudge][{chain}] output unusable, retrying once")

        result = _parse_judge_output(raw)
        cost_ms = int((time.time() - started) * 1000)

        _publish_with_run_id(run_id, "faithfulness_evaluated", {
            "input": question[:200],
            "output": {
                "chain": chain,
                "faithfulness": result["faithfulness"],
                "unsupported_claims": result["unsupported_claims"],
                "reasoning": result["reasoning"],
            },
            "duration_ms": cost_ms,
        })
        logger.info(f"[FaithfulnessJudge][{chain}] faithfulness={result['faithfulness']} "
                    f"unsupported_claims={len(result['unsupported_claims'])} ({cost_ms}ms)")
    except Exception as e:
        cost_ms = int((time.time() - started) * 1000)
        # fail-open 保持不变: 评判是"后台遥测", 失败绝不影响任何业务。
        # 但要留下两样东西, 否则这类失败完全不可观测:
        #   1) 日志记下模型原始返回(长度 + 开头) —— 只知道"找不到 faithfulness"
        #      无从下手, 得看清它是空串、道歉话术、还是被截断的 JSON
        #   2) 库里留一条 FAILED 记录 —— 让"评判失败"在页面上与"压根没评判"区分开
        logger.warning(
            f"[FaithfulnessJudge][{chain}] judge failed (ignored): {e} "
            f"({cost_ms}ms, raw_len={len(raw)}, raw_head={raw[:200]!r})")
        _publish_with_run_id(run_id, "faithfulness_evaluated", {
            "input": question[:200],
            "output": {"chain": chain, "error": str(e)[:300]},
            "status": "FAILED",
            "error": str(e)[:500],
            "duration_ms": cost_ms,
        })


def trigger_faithfulness_judge(question: str, answer: str, docs: list,
                               chain: str = "") -> bool:
    """触发一次后台忠实度评判(非阻塞)。在各链路的答案生成点调用。

    Returns:
        True  = 已派发后台评判
        False = 因开关关闭 / 未命中采样 / 无资料 / 无 run_id 而跳过
    """
    try:
        if not config.FAITHFULNESS_JUDGE_ENABLED:
            return False
        # 无资料可对照(如知识库零命中, 答案是纯通用知识) → 忠实度无从评判, 跳过
        if not answer or not docs:
            return False
        if random.random() > config.FAITHFULNESS_JUDGE_SAMPLE_RATE:
            return False
        # 必须在主线程捕获: contextvars 不跨线程
        run_id = get_run_id()
        if not run_id:
            return False

        threading.Thread(
            target=_judge_worker,
            args=(question, answer, docs, chain, run_id),
            daemon=True,
            name="faithfulness-judge",
        ).start()
        return True
    except Exception as e:
        logger.warning(f"[FaithfulnessJudge] spawn failed (ignored): {e}")
        return False
