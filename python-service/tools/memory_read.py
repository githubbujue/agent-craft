from typing import Dict, Any
from tools.base import Tool, ToolSchema, SchemaProperty, ToolMetadata
from core.config import config
from core.redis_client import redis_client

# 超过该轮数只返回最近几轮原文；更早的内容由 Java 侧的对话摘要承接
TRIM_THRESHOLD = 10
KEEP_RECENT = 5


class ConversationMemoryReadTool(Tool):
    """对话记忆读取工具（含上下文压缩）"""

    def __init__(self):
        input_schema = ToolSchema(
            properties={
                "conversation_id": SchemaProperty(
                    type="string",
                    description="对话ID",
                    required=True
                ),
                "limit": SchemaProperty(
                    type="number",
                    description="返回消息数量限制",
                    required=False,
                    default=10
                )
            },
            type="object"
        )

        output_schema = ToolSchema(
            properties={
                "messages": SchemaProperty(
                    type="array",
                    description="对话消息列表",
                    required=True
                ),
                "conversation_id": SchemaProperty(
                    type="string",
                    description="对话ID",
                    required=True
                ),
                "total_count": SchemaProperty(
                    type="number",
                    description="总消息数量",
                    required=True
                ),
                "compressed": SchemaProperty(
                    type="boolean",
                    description="是否已压缩",
                    required=False
                )
            },
            type="object"
        )

        metadata = ToolMetadata(
            timeout_ms=10000,  # 压缩可能需要更长时间
            max_retries=1,
            permission="user",
            description="读取对话记忆"
        )

        super().__init__(
            name="conversation_memory_read",
            description="读取对话记忆",
            input_schema=input_schema,
            output_schema=output_schema,
            metadata=metadata
        )

        # 刻意不持有 LLM：摘要生成已收敛到 Java 调用的 /api/internal/summarize，
        # 这里只负责读原文，避免出现第二个摘要生成器

    def execute(self, parameters: Dict[str, Any]) -> Dict[str, Any]:
        """执行对话记忆读取（含上下文压缩）"""
        conversation_id = parameters.get("conversation_id")
        limit = int(parameters.get("limit", 10))

        config.logger.info(f"Reading conversation memory for ID: {conversation_id}")

        # 获取消息总数
        total_count = redis_client.get_message_count(conversation_id)

        if total_count == 0:
            # 没有消息，返回空列表
            return {
                "messages": [],
                "conversation_id": conversation_id,
                "total_count": 0,
                "compressed": False
            }

        if total_count <= TRIM_THRESHOLD:
            # 消息不多，直接返回最近的
            messages = redis_client.get_messages(conversation_id, limit)
            return {
                "messages": messages,
                "conversation_id": conversation_id,
                "total_count": total_count,
                "compressed": False,
                "truncated": False,
            }

        # 超过阈值：只返回最近 N 轮原文，更早的内容由 Java 侧的对话摘要承接。
        # 这里不再生成摘要 —— 摘要的唯一生成器是 /api/internal/summarize，
        # 生成结果落 MySQL 后随 context 传进来。两边各生成一份会让同一批对话在
        # Prompt 里出现两段摘要。
        recent_messages = redis_client.get_messages(conversation_id, KEEP_RECENT)
        config.logger.info(
            f"Truncated conversation {conversation_id}: total {total_count}, "
            f"returning latest {KEEP_RECENT} (history summary is provided by Java)"
        )
        return {
            "messages": recent_messages,
            "conversation_id": conversation_id,
            "total_count": total_count,
            "compressed": False,
            "truncated": True,
        }
