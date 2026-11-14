"""服务间内部接口（Java -> Python）。

与 `/api/memory/*` 的区别：那边面向管理端，走用户 JWT；这边调用方是后端服务、
没有用户身份，因此用独立的 `X-Internal-Token`。

约定（docs/记忆改造计划.md §6.4）：
- 请求体只带"未覆盖的增量消息"和"上一版摘要"，裁剪在服务端做
- 失败一律返回 5xx，由 Java 侧降级到关键词桩，绝不能让半成品写进 MySQL
"""
import hmac
import logging
from typing import List, Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

from core.config import config

logger = logging.getLogger(__name__)

router = APIRouter()

# 输入裁剪：单条与总量上限，避免把长会话整段塞进模型
MAX_MESSAGE_CHARS = 500
MAX_TOTAL_CHARS = 6000
# 短于此长度的结果视为无效，宁可让 Java 降级也不要写入废话
MIN_SUMMARY_CHARS = 10

# llm_service.generate() 内部出错时返回兜底话术而不是抛异常，
# 这两种固定回复绝不能当成摘要写库
_FALLBACK_REPLIES = (
    "抱歉，我暂时无法回答这个问题。",
    "我是AI助手，很高兴为您服务。",
)

SUMMARY_PROMPT = """你是对话摘要助手。请把【本次新增对话】压缩成供后续对话使用的上下文摘要。

要求：
1. 保留：用户的目标与约束、已确认的事实与结论、未解决的问题、用户明确的偏好
2. 丢弃：寒暄、重复内容、模型的客套话与自我描述
3. 用第三人称陈述，不要出现"用户说""我回答"这类对话式表达
4. 300 字以内，写成连续段落，不要分点编号
5. 若提供了【已有摘要】，必须把其中仍然有效的信息合并进来，不要丢弃

【已有摘要】
{old_summary}

【本次新增对话】
{dialog}
"""


class SummarizeMessage(BaseModel):
    role: str
    content: str


class SummarizeRequest(BaseModel):
    conversation_id: Optional[int] = None
    old_summary: Optional[str] = None
    messages: List[SummarizeMessage] = Field(default_factory=list)


class SummarizeResponse(BaseModel):
    summary: str
    covered_messages: int


def _verify_internal_token(token: Optional[str]):
    expected = getattr(config, "INTERNAL_TOKEN", "") or ""
    if not expected:
        # 未配置时拒绝而不是放行：内部接口裸奔比不可用更危险
        logger.error("[Internal] INTERNAL_TOKEN 未配置，拒绝内部接口调用")
        raise HTTPException(status_code=503, detail="服务端未配置 INTERNAL_TOKEN")
    if not token or not hmac.compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="内部 token 无效")


def _trim_messages(messages: List[SummarizeMessage]) -> List[tuple]:
    """按"从最新往前取"裁剪，控制单条与总长度；返回 (role, content) 列表（已恢复正序）"""
    picked = []
    total = 0
    for msg in reversed(messages):
        content = (msg.content or "").strip()
        if not content:
            continue
        if len(content) > MAX_MESSAGE_CHARS:
            content = content[:MAX_MESSAGE_CHARS] + "…"
        if picked and total + len(content) > MAX_TOTAL_CHARS:
            break
        picked.append((msg.role or "user", content))
        total += len(content)
    picked.reverse()
    return picked


@router.post("/internal/summarize", response_model=SummarizeResponse)
async def summarize(payload: SummarizeRequest,
                    x_internal_token: Optional[str] = Header(None)):
    """把增量对话合并进已有摘要，供 Java 摘要任务调用"""
    _verify_internal_token(x_internal_token)

    dialog_msgs = _trim_messages(payload.messages)
    if not dialog_msgs:
        raise HTTPException(status_code=400, detail="messages 为空，无内容可摘要")

    dialog = "\n".join(f"{role}: {content}" for role, content in dialog_msgs)
    old_summary = (payload.old_summary or "").strip() or "（无）"
    prompt = SUMMARY_PROMPT.format(old_summary=old_summary, dialog=dialog)

    try:
        from core.llm import llm_service
        raw = llm_service.generate(prompt, temperature=0.3, max_tokens=400)
    except Exception as e:
        logger.error(f"[Internal] summarize failed - conversation: {payload.conversation_id}: {e}")
        raise HTTPException(status_code=500, detail=f"摘要生成失败: {e}")

    summary = (raw or "").strip()
    if len(summary) < MIN_SUMMARY_CHARS or summary in _FALLBACK_REPLIES:
        logger.warning(
            f"[Internal] invalid summary - conversation: {payload.conversation_id}, "
            f"length: {len(summary)}"
        )
        raise HTTPException(status_code=500, detail="摘要生成失败: 模型返回为空或兜底话术")

    return SummarizeResponse(summary=summary, covered_messages=len(dialog_msgs))
