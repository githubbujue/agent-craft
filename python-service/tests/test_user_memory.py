"""测试用户长期记忆：偏好抽取规则、写入落库、JWT 鉴权"""
import base64
import hashlib
import hmac
import json
import time

import pytest


# --------------------------------------------------------------------------
# 工具
# --------------------------------------------------------------------------

def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def make_token(claims: dict, secret: str = "test-secret", alg: str = "HS256") -> str:
    """按 Java jjwt 的格式造一个 HS256 token"""
    header = _b64(json.dumps({"alg": alg, "typ": "JWT"}, separators=(",", ":")).encode())
    payload = _b64(json.dumps(claims, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    return f"{header}.{payload}.{_b64(sig)}"


class FakeMemoryClient:
    """记录写入调用的假客户端，避免测试依赖 MySQL"""

    def __init__(self, error: Exception = None):
        self.writes = []
        self.error = error

    def update_user_memory(self, user_id, key, value, source="agent", confidence=1.0):
        if self.error:
            raise self.error
        self.writes.append({
            "user_id": user_id,
            "key": key,
            "value": value,
            "source": source,
            "confidence": confidence,
        })


@pytest.fixture
def agent():
    """绕过 __init__ 的重依赖（FAISS/Redis），只测纯逻辑方法"""
    from workflows.knowledge_qa_agent import KnowledgeQAAgent
    return KnowledgeQAAgent.__new__(KnowledgeQAAgent)


@pytest.fixture
def fake_memory(monkeypatch):
    from core.mysql_client import user_memory_client

    fake = FakeMemoryClient()
    monkeypatch.setattr(user_memory_client, "update_user_memory", fake.update_user_memory)
    return fake


# --------------------------------------------------------------------------
# 偏好抽取
# --------------------------------------------------------------------------

class TestPreferenceExtraction:

    @pytest.fixture(autouse=True)
    def no_llm(self, monkeypatch):
        """正则兜底路径：把 LLM 抽取置空，避免单元测试真的调模型"""
        from core import preference_extractor

        monkeypatch.setattr(preference_extractor, "extract_preferences", lambda q: [])

    @pytest.mark.parametrize("question,key,value,source,confidence", [
        ("以后请用表格格式回答", "answer_format", "表格", "explicit", 1.0),
        ("以后请用中文回答", "answer_language", "中文", "explicit", 1.0),
        ("今后回答都用英文输出", "answer_language", "英文", "explicit", 1.0),
        ("请叫我老王", "nickname", "老王", "inferred", 0.9),
        ("我一直喜欢简洁的回答", "preference", "简洁的回答", "inferred", 0.8),
    ])
    def test_rule_fallback(self, agent, fake_memory, question, key, value, source, confidence):
        """LLM 不可用时，模板句式仍能被正则兜住"""
        agent._remember_task("u1", question)

        assert len(fake_memory.writes) == 1
        write = fake_memory.writes[0]
        assert write["key"] == key
        assert write["value"]["value"] == value
        assert write["value"]["quote"] == question
        assert write["source"] == source
        assert write["confidence"] == confidence

    def test_llm_result_takes_priority(self, agent, fake_memory, monkeypatch):
        """LLM 抽到结果时不再回退正则 —— 自然表达（统一用中文回答）靠它才抓得住"""
        from core import preference_extractor

        monkeypatch.setattr(preference_extractor, "extract_preferences", lambda q: [{
            "key": "answer_language", "value": "中文",
            "confidence": 0.92, "source": "inferred",
        }])

        agent._remember_task("u1", "统一用中文回答")

        assert len(fake_memory.writes) == 1
        assert fake_memory.writes[0]["key"] == "answer_language"
        assert fake_memory.writes[0]["value"]["value"] == "中文"
        assert fake_memory.writes[0]["confidence"] == 0.92

    def test_format_does_not_also_record_language(self, agent, fake_memory):
        """'表格格式' 只算格式，不能同时写成 answer_language"""
        agent._remember_task("u1", "以后请用表格格式回答")

        assert [w["key"] for w in fake_memory.writes] == ["answer_format"]

    def test_plain_question_writes_nothing(self, agent, fake_memory):
        agent._remember_task("u1", "今天天气怎么样")

        assert fake_memory.writes == []

    def test_missing_user_id_writes_nothing(self, agent, fake_memory):
        agent._maybe_remember(None, "以后请用中文回答")

        assert fake_memory.writes == []

    def test_empty_question_writes_nothing(self, agent, fake_memory):
        agent._maybe_remember("u1", "   ")

        assert fake_memory.writes == []

    def test_maybe_remember_runs_in_background(self, agent, fake_memory):
        """_maybe_remember 只负责投递，不能阻塞调用方"""
        import time

        agent._maybe_remember("u1", "以后请用中文回答")
        deadline = time.time() + 3
        while time.time() < deadline and not fake_memory.writes:
            time.sleep(0.05)

        assert len(fake_memory.writes) == 1

    def test_write_failure_is_silent(self, agent, monkeypatch):
        """记忆写入失败不能影响问答主流程"""
        from core.mysql_client import user_memory_client

        broken = FakeMemoryClient(error=RuntimeError("MySQL is down"))
        monkeypatch.setattr(user_memory_client, "update_user_memory", broken.update_user_memory)

        agent._remember_task("u1", "以后请用中文回答")  # 不抛异常即通过


# --------------------------------------------------------------------------
# JWT 鉴权
# --------------------------------------------------------------------------

class TestJwt:

    def test_valid_token(self):
        from core.auth import decode_jwt

        token = make_token({"userId": 1001, "role": "USER", "exp": int(time.time()) + 60})
        claims = decode_jwt(token, secret="test-secret")

        assert claims["userId"] == 1001
        assert claims["role"] == "USER"

    def test_tampered_payload_rejected(self):
        from core.auth import decode_jwt

        token = make_token({"userId": 1001, "role": "USER"})
        header, payload, sig = token.split(".")
        forged = _b64(json.dumps({"userId": 1, "role": "ADMIN"}).encode())

        with pytest.raises(ValueError, match="签名校验失败"):
            decode_jwt(f"{header}.{forged}.{sig}", secret="test-secret")

    def test_wrong_secret_rejected(self):
        from core.auth import decode_jwt

        token = make_token({"userId": 1001})

        with pytest.raises(ValueError, match="签名校验失败"):
            decode_jwt(token, secret="another-secret")

    def test_expired_token_rejected(self):
        from core.auth import decode_jwt

        token = make_token({"userId": 1001, "exp": int(time.time()) - 1})

        with pytest.raises(ValueError, match="已过期"):
            decode_jwt(token, secret="test-secret")

    def test_alg_none_rejected(self):
        """alg=none 是经典绕过手法，必须拒绝"""
        from core.auth import decode_jwt

        token = make_token({"userId": 1, "role": "ADMIN"}, alg="none")

        with pytest.raises(ValueError, match="不支持的签名算法"):
            decode_jwt(token, secret="test-secret")

    def test_malformed_token_rejected(self):
        from core.auth import decode_jwt

        with pytest.raises(ValueError, match="格式非法"):
            decode_jwt("not-a-jwt", secret="test-secret")

    @pytest.mark.parametrize("role,expected", [
        ("ADMIN", True), ("admin", True), ("Admin", True),
        ("USER", False), ("", False), (None, False),
    ])
    def test_is_admin(self, role, expected):
        from core.auth import is_admin

        assert is_admin({"role": role}) is expected

    def test_caller_id_returns_string(self):
        from core.auth import caller_id

        assert caller_id({"userId": 1001}) == "1001"
        assert caller_id({}) == ""
