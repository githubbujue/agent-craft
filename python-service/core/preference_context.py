"""把用户长期偏好格式化成可注入 Prompt 的片段。

L1/L2（knowledge_qa_agent）与 L3（reasoning_agent）共用同一份实现。单独成模块
而不是各 agent 各写一遍：本项目已经因为"两处各写一遍、改一份忘一份"在
conversation_id / user_id 透传上踩过两次，偏好注入再复制一份迟早是第三次。

失败一律返回空串 —— 长期记忆只是锦上添花，MySQL 不可用时不能影响问答主流程。
"""
import json
import logging
from typing import Optional

logger = logging.getLogger(__name__)

# 读侧置信度下限：低于该值的记忆不注入 Prompt，避免一次误抽长期生效
USER_MEMORY_MIN_CONFIDENCE = 0.6
# 条数与字符上限：记忆条目不受控时会把 Prompt 撑爆
MAX_ENTRIES = 10
MAX_CHARS = 500


def format_user_memory(user_id: Optional[str],
                       min_confidence: float = USER_MEMORY_MIN_CONFIDENCE) -> str:
    """返回形如 {"answer_format": "表格"} 的一行文本；无记忆或失败返回空串"""
    if not user_id:
        return ""

    try:
        # 延迟 import：MySQL 不可用时不影响模块加载
        from core.mysql_client import user_memory_client

        memories = user_memory_client.get_user_memory(
            str(user_id), min_confidence=min_confidence
        ) or {}
        if not memories:
            return ""

        # 写入的是 {"value":..., "quote":...}，注入 Prompt 只取 value，
        # 原话 quote 留在库里供排查，不占 Prompt 预算
        items = []
        for key, value in list(memories.items())[:MAX_ENTRIES]:
            if isinstance(value, dict) and "value" in value:
                value = value["value"]
            items.append((key, value))

        text = json.dumps(dict(items), ensure_ascii=False)
        if len(text) > MAX_CHARS:
            text = text[:MAX_CHARS] + "..."
        logger.info(f"[UserMemory] Loaded {len(items)} entries: {list(dict(items))}")
        return text
    except Exception as e:
        logger.warning(f"[UserMemory] Failed to load user memory: {e}")
        return ""
