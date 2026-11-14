"""用户长期记忆管理接口。

写入由 `KnowledgeQAAgent._maybe_remember` 自动完成；这里提供查看/删除入口，
用于排查"AI 记错了"这类问题 —— 没有纠错入口的记忆系统，错误会长期生效。

鉴权：复用 Java 侧签发的 JWT（HS256，同一把 secret）。
管理员可操作任意 user_id，普通用户只能操作自己的。
"""
from fastapi import APIRouter, Depends, Header, HTTPException, Query
from typing import Any, Dict, Optional
import logging

from core.auth import caller_id, decode_jwt, is_admin

logger = logging.getLogger(__name__)

router = APIRouter()


async def require_auth(authorization: Optional[str] = Header(None)) -> Dict[str, Any]:
    """校验 Authorization: Bearer <jwt>，返回 claims。"""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=401,
            detail="缺少有效的 Authorization 头",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        return decode_jwt(authorization[7:].strip())
    except ValueError as e:
        raise HTTPException(
            status_code=401,
            detail=f"token 无效: {e}",
            headers={"WWW-Authenticate": "Bearer"},
        )


def _assert_can_access(claims: Dict[str, Any], user_id: str):
    """管理员放行；普通用户只能访问自己的记忆。"""
    if is_admin(claims):
        return
    if not caller_id(claims) or caller_id(claims) != str(user_id):
        raise HTTPException(status_code=403, detail="无权访问其他用户的记忆")


@router.get("/memory/{user_id}")
async def list_memory(user_id: str,
                      min_confidence: float = Query(0.0, ge=0.0, le=1.0),
                      claims: Dict[str, Any] = Depends(require_auth)):
    """查看用户长期记忆。

    min_confidence 默认 0（能看到全部，包括低置信度的推断记忆）；
    想看"实际会进 Prompt 的那部分"就传 0.6。
    """
    _assert_can_access(claims, user_id)
    try:
        from core.mysql_client import user_memory_client
        items = user_memory_client.list_user_memory(user_id, min_confidence=min_confidence)
    except Exception as e:
        logger.error(f"[Memory API] Failed to list memory for {user_id}: {e}")
        raise HTTPException(status_code=500, detail=f"读取用户记忆失败: {e}")

    return {"user_id": user_id, "count": len(items), "items": items}


@router.delete("/memory/{user_id}")
async def clear_memory(user_id: str,
                       claims: Dict[str, Any] = Depends(require_auth)):
    """清空该用户全部长期记忆"""
    _assert_can_access(claims, user_id)
    try:
        from core.mysql_client import user_memory_client
        deleted = user_memory_client.delete_user_memory(user_id)
    except Exception as e:
        logger.error(f"[Memory API] Failed to clear memory for {user_id}: {e}")
        raise HTTPException(status_code=500, detail=f"清空用户记忆失败: {e}")

    logger.info(f"[Memory API] Cleared {deleted} memory entries for {user_id}")
    return {"user_id": user_id, "deleted": deleted}


@router.delete("/memory/{user_id}/{memory_key}")
async def delete_memory_item(user_id: str, memory_key: str,
                             claims: Dict[str, Any] = Depends(require_auth)):
    """删除单条记忆（memory_key 如 answer_language / nickname / preference）"""
    _assert_can_access(claims, user_id)
    try:
        from core.mysql_client import user_memory_client
        deleted = user_memory_client.delete_user_memory(user_id, memory_key)
    except Exception as e:
        logger.error(f"[Memory API] Failed to delete {memory_key} for {user_id}: {e}")
        raise HTTPException(status_code=500, detail=f"删除记忆失败: {e}")

    if deleted == 0:
        raise HTTPException(status_code=404, detail=f"记忆不存在: {memory_key}")

    logger.info(f"[Memory API] Deleted {memory_key} for {user_id}")
    return {"user_id": user_id, "memory_key": memory_key, "deleted": deleted}
