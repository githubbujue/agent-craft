import redis
import json
import time
import logging
from core.config import config

logger = logging.getLogger(__name__)

class RedisClient:
    """Redis 客户端，用于会话记忆存储"""

    def __init__(self):
        """初始化Redis连接池"""
        self.pool = redis.ConnectionPool(
            host=config.REDIS_HOST,
            port=config.REDIS_PORT,
            password=config.REDIS_PASSWORD if config.REDIS_PASSWORD else None,
            db=config.REDIS_DB,
            decode_responses=True,
            max_connections=20
        )
        self.client = redis.Redis(connection_pool=self.pool)
        logger.info(f"Redis client initialized: {config.REDIS_HOST}:{config.REDIS_PORT}")

    def add_message(self, conversation_id: str, role: str, content: str):
        """追加消息到会话"""
        key = f"conversation:{conversation_id}:messages"
        message = json.dumps({
            "role": role,
            "content": content,
            "timestamp": time.time()
        }, ensure_ascii=False)
        self.client.rpush(key, message)
        self.client.expire(key, 86400)  # 24小时过期
        logger.debug(f"Added message to conversation {conversation_id}: {role}")

    def get_messages(self, conversation_id: str, limit: int = 10) -> list:
        """获取最近 N 条消息"""
        key = f"conversation:{conversation_id}:messages"
        messages = self.client.lrange(key, -limit, -1)
        return [json.loads(m) for m in messages]

    def get_all_messages(self, conversation_id: str) -> list:
        """获取所有消息"""
        key = f"conversation:{conversation_id}:messages"
        messages = self.client.lrange(key, 0, -1)
        return [json.loads(m) for m in messages]

    def get_message_count(self, conversation_id: str) -> int:
        """获取消息总数"""
        key = f"conversation:{conversation_id}:messages"
        return self.client.llen(key)

    def clear_conversation(self, conversation_id: str):
        """清空会话的消息列表，并失效 Java 侧的摘要缓存"""
        self.client.delete(f"conversation:{conversation_id}:messages")
        # 摘要本体存在 MySQL（事实源），这里只删 Java 的缓存 key（conv:summary:{id}）；
        # 是否删除 MySQL 里的摘要记录由 Java 侧决定
        self.client.delete(f"conv:summary:{conversation_id}")
        logger.info(f"Cleared conversation {conversation_id}")

    # 注：原先的 get_summary / set_summary 已移除。
    # 摘要的唯一生成器是 Java 调用的 /api/internal/summarize，事实源在 MySQL，
    # Python 不再写 conversation:{id}:summary —— 保留这两个方法只会让人以为还有第二个生成器。

# 创建全局实例
redis_client = RedisClient()
