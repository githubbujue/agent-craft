"""JWT 校验（HS256），与 Java 侧 `JwtUtil` 共用同一把 secret。

为什么手写而不引 PyJWT：本项目 requirements 是 pip freeze 全量快照，
为一个 30 行的校验逻辑加依赖不划算；HS256 就是 HMAC-SHA256，标准库即可。
Java 侧 `Keys.hmacShaKeyFor(secret.getBytes())` 用的正是 secret 的 UTF-8 字节。
"""
import base64
import hashlib
import hmac
import json
import logging
import os
import time
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

# 与 src/main/resources/application.yml 的 jwt.secret 默认值保持一致，
# 否则本地开发时 Java 签发的 token 在 Python 侧必然验签失败
_DEFAULT_SECRET = "your-secret-key-change-in-production"
_warned_default_secret = False


def _get_secret() -> str:
    global _warned_default_secret
    secret = os.getenv("JWT_SECRET", "")
    if secret:
        return secret
    if not _warned_default_secret:
        logger.warning(
            "[Auth] JWT_SECRET 未配置，回退到默认密钥（仅限本地开发，"
            "生产环境必须与 Java 侧配置同一把密钥）"
        )
        _warned_default_secret = True
    return _DEFAULT_SECRET


def _b64url_decode(segment: str) -> bytes:
    padding = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(segment + padding)


def decode_jwt(token: str, secret: Optional[str] = None) -> Dict[str, Any]:
    """校验 HS256 签名与过期时间，返回 claims；任何问题都抛 ValueError。"""
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("token 格式非法")

    header_seg, payload_seg, signature_seg = parts
    signing_input = f"{header_seg}.{payload_seg}".encode()
    expected = hmac.new(
        (secret or _get_secret()).encode(), signing_input, hashlib.sha256
    ).digest()
    try:
        actual = _b64url_decode(signature_seg)
    except Exception:
        raise ValueError("token 签名非法")

    # 定长比较，避免时序侧信道
    if not hmac.compare_digest(expected, actual):
        raise ValueError("token 签名校验失败")

    try:
        header = json.loads(_b64url_decode(header_seg))
        claims = json.loads(_b64url_decode(payload_seg))
    except Exception:
        raise ValueError("token 内容非法")

    if header.get("alg") != "HS256":
        raise ValueError(f"不支持的签名算法: {header.get('alg')}")

    exp = claims.get("exp")
    if exp is not None and float(exp) < time.time():
        raise ValueError("token 已过期")

    return claims


def is_admin(claims: Dict[str, Any]) -> bool:
    return str(claims.get("role") or "").strip().lower() == "admin"


def caller_id(claims: Dict[str, Any]) -> str:
    """Java 侧把用户 ID 放在 `userId` claim 里（不是标准 sub，sub 是用户名）。"""
    return str(claims.get("userId") or "")
