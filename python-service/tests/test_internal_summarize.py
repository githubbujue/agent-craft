"""测试内部摘要接口：鉴权、裁剪、失败处理

刻意只挂载 internal 路由，不加载 main.py —— 否则会连带初始化 FAISS/Redis 等重依赖。
"""
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.internal_routes import (
    MAX_MESSAGE_CHARS,
    MAX_TOTAL_CHARS,
    router,
)

TOKEN = "test-internal-token"


@pytest.fixture
def client(monkeypatch):
    from core.config import config

    monkeypatch.setattr(config, "INTERNAL_TOKEN", TOKEN, raising=False)
    app = FastAPI()
    app.include_router(router, prefix="/api")
    return TestClient(app)


@pytest.fixture
def fake_llm(monkeypatch):
    """替换 llm_service.generate，避免测试真的调模型"""
    from core.llm import llm_service

    state = {"reply": "用户希望整理项目部署方案，已确认使用 Docker 部署，尚未确定监控方案。",
             "error": None, "prompts": []}

    def fake_generate(prompt, temperature=0.7, max_tokens=150):
        state["prompts"].append(prompt)
        if state["error"]:
            raise state["error"]
        return state["reply"]

    monkeypatch.setattr(llm_service, "generate", fake_generate)
    return state


def _body(messages=None, old_summary=None):
    return {
        "conversation_id": 1,
        "old_summary": old_summary,
        "messages": messages if messages is not None else [
            {"role": "user", "content": "帮我看看部署方案"},
            {"role": "assistant", "content": "建议用 Docker Compose"},
        ],
    }


class TestAuth:

    def test_rejects_when_token_not_configured(self, client, fake_llm, monkeypatch):
        from core.config import config

        monkeypatch.setattr(config, "INTERNAL_TOKEN", "", raising=False)

        r = client.post("/api/internal/summarize", json=_body(),
                        headers={"X-Internal-Token": "whatever"})

        assert r.status_code == 503
        # 服务端没配置时不能偷偷放行
        assert fake_llm["prompts"] == []

    def test_rejects_missing_header(self, client, fake_llm):
        r = client.post("/api/internal/summarize", json=_body())

        assert r.status_code == 401
        assert fake_llm["prompts"] == []

    def test_rejects_wrong_token(self, client, fake_llm):
        r = client.post("/api/internal/summarize", json=_body(),
                        headers={"X-Internal-Token": "wrong"})

        assert r.status_code == 401
        assert fake_llm["prompts"] == []


class TestSummarize:

    def test_returns_summary(self, client, fake_llm):
        r = client.post("/api/internal/summarize", json=_body(),
                        headers={"X-Internal-Token": TOKEN})

        assert r.status_code == 200
        assert r.json()["summary"].startswith("用户希望整理项目部署方案")
        assert r.json()["covered_messages"] == 2

    def test_merges_old_summary_into_prompt(self, client, fake_llm):
        client.post("/api/internal/summarize",
                    json=_body(old_summary="之前确定使用 MySQL 8"),
                    headers={"X-Internal-Token": TOKEN})

        prompt = fake_llm["prompts"][0]
        assert "之前确定使用 MySQL 8" in prompt, "旧摘要必须传给模型，否则增量会丢历史"
        assert "帮我看看部署方案" in prompt

    def test_trims_long_message(self, client, fake_llm):
        long_text = "长" * (MAX_MESSAGE_CHARS + 200)
        client.post("/api/internal/summarize",
                    json=_body(messages=[{"role": "user", "content": long_text}]),
                    headers={"X-Internal-Token": TOKEN})

        prompt = fake_llm["prompts"][0]
        assert "长" * MAX_MESSAGE_CHARS in prompt
        assert "长" * (MAX_MESSAGE_CHARS + 1) not in prompt

    def test_keeps_latest_messages_under_budget(self, client, fake_llm):
        messages = [{"role": "user", "content": f"第{i}条" + "内容" * 200} for i in range(20)]
        r = client.post("/api/internal/summarize", json=_body(messages=messages),
                        headers={"X-Internal-Token": TOKEN})

        assert r.status_code == 200
        prompt = fake_llm["prompts"][0]
        assert "第19条" in prompt, "应保留最新的消息"
        assert "第0条" not in prompt, "超出预算的旧消息应被裁掉"

    def test_empty_messages_returns_400(self, client, fake_llm):
        r = client.post("/api/internal/summarize", json=_body(messages=[]),
                        headers={"X-Internal-Token": TOKEN})

        assert r.status_code == 400
        assert fake_llm["prompts"] == []


class TestFailureHandling:

    def test_llm_exception_returns_500(self, client, fake_llm):
        fake_llm["error"] = RuntimeError("model timeout")

        r = client.post("/api/internal/summarize", json=_body(),
                        headers={"X-Internal-Token": TOKEN})

        assert r.status_code == 500

    def test_fallback_reply_is_not_written_as_summary(self, client, fake_llm):
        """generate() 出错时返回固定话术而不是抛异常，绝不能当摘要返回"""
        fake_llm["reply"] = "抱歉，我暂时无法回答这个问题。"

        r = client.post("/api/internal/summarize", json=_body(),
                        headers={"X-Internal-Token": TOKEN})

        assert r.status_code == 500

    def test_too_short_reply_returns_500(self, client, fake_llm):
        fake_llm["reply"] = "好的"

        r = client.post("/api/internal/summarize", json=_body(),
                        headers={"X-Internal-Token": TOKEN})

        assert r.status_code == 500
