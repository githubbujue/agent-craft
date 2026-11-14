"""回归：闲聊链路必须注入用户长期偏好。

背景：L1/L2/L3 都注入了 user_memory，闲聊没注入。而"我之前说过偏好吗？"
这类问题很容易被路由成闲聊 —— 于是同一份记忆一边写入成功、一边回答"你没提过"，
用户看到的就是记忆系统自相矛盾。
"""
import json

from workflows.chitchat_agent import ChitChatAgent


def _patch_memory(monkeypatch, memory_text):
    monkeypatch.setattr("workflows.chitchat_agent.format_user_memory",
                        lambda user_id: memory_text)
    monkeypatch.setattr("workflows.chitchat_agent.publish_step", lambda *a, **k: None)


def test_chat_injects_user_memory(monkeypatch):
    agent = ChitChatAgent()
    seen = {}

    _patch_memory(monkeypatch, '{"preference": "主要用Python写后端"}')
    monkeypatch.setattr(agent, "_generate_chitchat_response",
                        lambda question, history="": seen.setdefault("history", history) or "好的")
    monkeypatch.setattr(agent, "_save_to_memory", lambda *a, **k: None)

    agent.chat("我之前说过我的技术栈偏好吗？", conversation_id="1", user_id="7")

    assert "用户长期偏好" in seen["history"]
    assert "Python" in seen["history"]


def test_chat_stream_injects_user_memory(monkeypatch):
    agent = ChitChatAgent()
    seen = {}

    _patch_memory(monkeypatch, '{"preference": "主要用Python写后端"}')
    monkeypatch.setattr(agent, "_match_rule_response", lambda q: None)
    monkeypatch.setattr(agent, "_save_to_memory", lambda *a, **k: None)
    monkeypatch.setattr(agent, "_build_chitchat_prompt",
                        lambda question, history="": seen.setdefault("history", history) or "prompt")
    monkeypatch.setattr("workflows.chitchat_agent.llm.generate_stream",
                        lambda prompt, **kwargs: iter(["记得，"]))

    events = list(agent.chat_stream("我之前说过我的技术栈偏好吗？",
                                    conversation_id="1", user_id="7"))

    assert "用户长期偏好" in seen["history"]
    assert "Python" in seen["history"]
    assert any(json.loads(e).get("type") == "token" for e in events)
