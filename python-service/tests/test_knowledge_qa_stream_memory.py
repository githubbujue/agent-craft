"""回归：流式问答（聊天页走的链路）必须把本轮问答写入会话记忆。

背景：Java 侧落库是 MySQL，而 Python 侧 conversation_memory_read 读的是 Redis。
若 ask_stream 不写会话记忆，下一轮的"最近 5 条原文"永远是空的 ——
用户看到的现象是多轮对话像失忆，指代（"它"）找不到对象，且日志里毫无报错。
"""
import json

from workflows.knowledge_qa_agent import KnowledgeQAAgent


class _Doc:
    page_content = "Python 是一门通用编程语言"
    metadata = {"doc_id": 1, "source": "test.md", "page": 1, "chunk_index": 0}


class _Sufficiency:
    is_sufficient = True
    confidence = 0.9


class _VectorStore:
    def search(self, **kwargs):
        return [_Doc()]


class _LLM:
    def get_answer_stream(self, question, context_docs, conversation_context,
                          enable_search=False):
        for piece in ("回答", "内容"):
            yield json.dumps({"type": "token", "content": piece}, ensure_ascii=False)


def _make_agent() -> KnowledgeQAAgent:
    """绕过 __init__ 里的真实 LLM/向量库初始化，只装本用例需要的依赖。"""
    agent = KnowledgeQAAgent.__new__(KnowledgeQAAgent)
    agent.vector_store = _VectorStore()
    agent.llm_service = _LLM()
    return agent


def test_ask_stream_writes_conversation_memory(monkeypatch):
    agent = _make_agent()
    saved = []

    monkeypatch.setattr(agent, "_build_full_context", lambda *a, **k: "")
    monkeypatch.setattr(agent, "_save_to_memory", lambda cid, q, a: saved.append((cid, q, a)))
    monkeypatch.setattr(agent, "_maybe_remember", lambda *a, **k: None)
    monkeypatch.setattr("workflows.knowledge_qa_agent.evaluate_retrieval",
                        lambda *a, **k: _Sufficiency())
    monkeypatch.setattr("workflows.knowledge_qa_agent.evaluate_answer", lambda *a, **k: {})
    monkeypatch.setattr("workflows.knowledge_qa_agent.trigger_faithfulness_judge",
                        lambda *a, **k: None)

    events = list(agent.ask_stream("Python 是什么", conversation_id=101, user_id=7))

    assert saved == [(101, "Python 是什么", "回答内容")], "流式回答结束后必须写会话记忆"
    types = [json.loads(e).get("type") for e in events if e.startswith("{")]
    assert "sources" in types


def test_ask_stream_without_conversation_id_still_answers(monkeypatch):
    """没有 conversation_id 时不能因为写记忆而中断回答。"""
    agent = _make_agent()

    monkeypatch.setattr(agent, "_build_full_context", lambda *a, **k: "")
    monkeypatch.setattr(agent, "_maybe_remember", lambda *a, **k: None)
    monkeypatch.setattr("workflows.knowledge_qa_agent.evaluate_retrieval",
                        lambda *a, **k: _Sufficiency())
    monkeypatch.setattr("workflows.knowledge_qa_agent.evaluate_answer", lambda *a, **k: {})
    monkeypatch.setattr("workflows.knowledge_qa_agent.trigger_faithfulness_judge",
                        lambda *a, **k: None)

    events = list(agent.ask_stream("Python 是什么", conversation_id=None, user_id=7))

    types = [json.loads(e).get("type") for e in events if e.startswith("{")]
    assert "sources" in types
    assert "error" not in types
