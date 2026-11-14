"""用户长期偏好抽取：关键词预筛 + LLM 结构化抽取。

为什么不是纯正则：正则只能覆盖"以后请用中文回答"这类模板句，
"统一用中文回答""后面都给我表格"这种自然表达全部漏掉（实测就漏过）。

为什么不是纯 LLM：每条消息都调一次模型，成本和延迟都不划算。
先用关键词判断"这句话像不像在提长期要求"，命中才真正请求模型。

失败一律返回空列表，由调用方降级到正则 —— 偏好抽取只是锦上添花。
"""
import json
import logging
from typing import Any, Dict, List

logger = logging.getLogger(__name__)

# 预筛信号词：出现任一才值得花一次 LLM 调用。
# 宁可放宽一点 —— 漏掉的表现是"用户说了偏好但没记住"，比多花一次调用更难察觉。
_PREF_HINTS = (
    # 时间指向"从现在起"
    "以后", "今后", "往后", "后面", "之后", "接下来", "从现在开始",
    # 明确的指令口吻
    "统一", "记住", "请用", "不要用", "别用", "别再", "一律", "始终", "都要",
    # 偏好自述
    "我喜欢", "我习惯", "我偏好", "更喜欢",
    # 称呼
    "叫我", "称呼我",
)

# 只允许这四类键，避免模型自己发明字段把 user_memory 写乱
_ALLOWED_KEYS = {"answer_language", "answer_format", "nickname", "preference"}

_MAX_VALUE_CHARS = 30

_EXTRACT_PROMPT = """判断用户下面这句话有没有表达**长期偏好**（对之后所有对话都生效的要求）。

只输出一个 JSON，不要解释、不要代码块：
{{"preferences": [{{"key": "...", "value": "...", "confidence": 0.95}}]}}

key 只能是以下之一：
- answer_language：回答使用的语言（中文/英文等）
- answer_format：回答的形式（表格/要点列表/代码加说明等）
- nickname：希望被怎么称呼
- preference：其他长期偏好（简洁/详细/不要客套话等）

判断规则：
1. 只提取"以后都要这样"的长期要求；一次性请求（"这次用表格"）不算
2. 没有就返回 {{"preferences": []}}
3. value 用简短中文，不超过 20 字
4. 用户没明确说的不要猜

用户的话：{question}
"""


def looks_like_preference(question: str) -> bool:
    """廉价的预筛，决定要不要调用模型"""
    if not question:
        return False
    return any(hint in question for hint in _PREF_HINTS)


def _strip_code_fence(text: str) -> str:
    """模型经常不听话地套一层 ```json 围栏"""
    text = (text or "").strip()
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    if lines and lines[0].startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip().startswith("```"):
        lines = lines[:-1]
    return "\n".join(lines).strip()


def _normalize(items: Any) -> List[Dict[str, Any]]:
    """把模型输出收敛成可落库的结构，非法项直接丢弃而不是写脏数据"""
    if not isinstance(items, list):
        return []

    result = []
    for item in items:
        if not isinstance(item, dict):
            continue

        key = str(item.get("key") or "").strip()
        value = str(item.get("value") or "").strip()
        if key not in _ALLOWED_KEYS or not value:
            continue
        if len(value) > _MAX_VALUE_CHARS:
            value = value[:_MAX_VALUE_CHARS]

        try:
            confidence = float(item.get("confidence", 0.85))
        except (TypeError, ValueError):
            confidence = 0.85
        # 夹到 [0,1]，并留一点下限：低于 0.5 的记忆本来也不会进 Prompt
        confidence = max(0.0, min(1.0, confidence))
        if confidence < 0.5:
            continue

        result.append({
            "key": key,
            "value": value,
            "confidence": round(confidence, 2),
            "source": "inferred",
        })
    return result


def extract_preferences(question: str) -> List[Dict[str, Any]]:
    """返回 [{'key','value','confidence','source'}]；没有/失败返回空列表"""
    if not looks_like_preference(question):
        return []

    try:
        from core.llm import llm_service
        raw = llm_service.generate(
            _EXTRACT_PROMPT.format(question=question.strip()),
            temperature=0.0,
            max_tokens=300,
        )
    except Exception as e:
        logger.warning(f"[Preference] LLM 抽取失败，降级到规则: {e}")
        return []

    text = _strip_code_fence(raw)
    if not text:
        return []

    try:
        payload = json.loads(text)
    except Exception:
        logger.warning(f"[Preference] 模型输出不是合法 JSON，降级到规则: {text[:80]}")
        return []

    return _normalize(payload.get("preferences"))
