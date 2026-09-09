from typing import Dict, Any, Optional, Generator
from agent.orchestrator import Orchestrator
from agent.state import AgentState
from agent.events import EventBus, event_bus
from workflows.retrieval_agent import RetrievalAgent
from core.vector_store import vector_store
from core.llm import LLMService
from core.retrieval_evaluator import evaluate_retrieval, evaluate_answer, publish_step
from tools.registry import tool_registry
import logging
import json
import time

logger = logging.getLogger(__name__)


class KnowledgeQAAgent:
    """知识问答Agent - 三级链路：L1简化/L2标准/L3推理"""

    def __init__(self):
        self.orchestrator = Orchestrator()
        self.event_bus = event_bus
        self.retrieval_agent = RetrievalAgent()
        self.vector_store = vector_store
        self.llm_service = LLMService()
        self._router = None
        self._reasoning_agent = None

    @property
    def router(self):
        """延迟加载 RouterAgent（避免循环导入）"""
        if self._router is None:
            from workflows.router_agent import RouterAgent
            self._router = RouterAgent()
        return self._router

    @property
    def reasoning_agent(self):
        """延迟加载 ReasoningAgent"""
        if self._reasoning_agent is None:
            from workflows.reasoning_agent import ReasoningAgent
            self._reasoning_agent = ReasoningAgent()
        return self._reasoning_agent

    def ask(self, question: str, conversation_id: Optional[str] = None,
            user_id: Optional[str] = None, context: str = "",
            **kwargs) -> Dict[str, Any]:
        """
        处理知识问答 - 三级链路

        L1 简单：直接检索+生成（80%请求，~2-3s）
        L2 标准：问题改写+检索+重排序+生成（15%请求，~5-8s）
        L3 推理：分解+逐个推理+汇总（5%请求，~10-15s，由 RouterAgent 处理）
        """
        logger.info(f"[KnowledgeQAAgent] Processing question: {question[:50]}...")

        try:
            # 1. 读取会话记忆作为上下文
            conversation_history = ""
            if conversation_id and tool_registry.has_tool("conversation_memory_read"):
                _mt0 = time.time()
                try:
                    history = tool_registry.invoke_tool(
                        "conversation_memory_read",
                        {"conversation_id": conversation_id, "limit": 10}
                    )
                    messages = history.get("messages", [])
                    publish_step("memory_read", {
                        "message_count": len(messages) if messages else 0,
                        "compressed": history.get("compressed", False),
                    }, duration_ms=(time.time() - _mt0) * 1000)
                    if messages:
                        conversation_history = self._format_history(messages)
                        logger.info(f"[KnowledgeQAAgent] Loaded {len(messages)} messages from memory"
                                    f" (compressed: {history.get('compressed', False)})")
                except Exception as e:
                    logger.warning(f"[KnowledgeQAAgent] Failed to read conversation memory: {e}")

            # 合并上下文
            full_context = context
            if conversation_history:
                full_context = f"{context}\n\n{conversation_history}" if context else conversation_history

            # 2. 判断复杂度，选择链路
            complexity = self.router.classify_complexity(question)
            logger.info(f"[KnowledgeQAAgent] Complexity: {complexity}")

            if complexity == "medium":
                return self._ask_l2(question, conversation_id, full_context)
            else:
                return self._ask_l1(question, conversation_id, full_context)

        except Exception as e:
            logger.error(f"[KnowledgeQAAgent] QA failed: {e}", exc_info=True)
            return {
                "answer": "抱歉，处理您的问题时遇到了错误，请稍后再试。",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_qa",
                "error": True
            }

    def _ask_l1(self, question: str, conversation_id: Optional[str],
                full_context: str) -> Dict[str, Any]:
        """L1 简化链路：直接检索+生成"""
        # 直接向量检索
        _t0 = time.time()
        docs = self.vector_store.search(
            query=question, k=5, similarity_threshold=0.45, use_rerank=False
        )
        search_ms = (time.time() - _t0) * 1000
        logger.info(f"[KnowledgeQAAgent] L1 retrieved {len(docs)} documents")

        # 评估层：检索充分性判断（事件留痕，为降级策略提供依据）
        sufficiency = evaluate_retrieval(docs, question, chain="L1", search_ms=search_ms)

        if not docs:
            # 兜底策略：知识库无资料仍走 LLM 通用知识回答（而非冷拒绝），
            # 保证任何路径都有完整轨迹 + 有温度的回答
            answer = self.llm_service.get_answer(question, [], full_context)
            self._save_to_memory(conversation_id, question, answer)
            return {"answer": answer, "sources": [], "has_sources": False,
                    "task_type": "knowledge_qa",
                    "evaluation": self._evaluation_meta(
                        sufficiency,
                        answer_quality=evaluate_answer(question, answer, [], chain="L1"))}

        _g0 = time.time()
        answer = self.llm_service.get_answer(question, docs, full_context)
        gen_ms = (time.time() - _g0) * 1000
        # 降级策略：检索到内容但充分性不足 → 明确提示，防止用户当作权威答案
        if not sufficiency.is_sufficient:
            answer = "❗知识库中相关内容有限，以下回答仅供参考。\n\n" + answer
        sources = self._build_sources(docs)
        # 先评估后写记忆 —— 保证轨迹时序: 答案生成 → 记忆写入
        answer_quality = evaluate_answer(question, answer, sources,
                                         chain="L1", gen_ms=gen_ms)
        self._save_to_memory(conversation_id, question, answer)

        return {
            "answer": answer, "sources": sources,
            "has_sources": len(sources) > 0, "task_type": "knowledge_qa",
            "evaluation": self._evaluation_meta(sufficiency, answer_quality=answer_quality)
        }

    def _ask_l2(self, question: str, conversation_id: Optional[str],
                full_context: str) -> Dict[str, Any]:
        """L2 标准链路：问题改写+检索+重排序+生成"""
        _t0 = time.time()
        retrieval_result = self.retrieval_agent.retrieve(
            query=question,
            conversation_context=full_context,
            use_rewrite=True,
            use_rerank=True,
            top_k=5
        )
        search_ms = (time.time() - _t0) * 1000

        docs = retrieval_result.reranked_documents
        logger.info(f"[KnowledgeQAAgent] L2 retrieved {len(docs)} documents "
                     f"(rewritten: '{retrieval_result.rewritten_query[:30]}...')")

        # 评估层：分数从 metadata["score"] 取(余弦相似度, 语义统一)。
        # 不用 retrieval_result.scores —— 走 rerank 时那是 rerank 分数, 语义不同。
        # 知识检索事件已由 retrieval_agent 在检索点发布, 此处跳过
        sufficiency = evaluate_retrieval(docs, question, chain="L2",
                                         publish_retrieval_event=False)

        if not docs:
            # 兜底策略：同 L1 —— 知识库无资料仍走 LLM 通用知识回答
            answer = self.llm_service.get_answer(question, [], full_context)
            self._save_to_memory(conversation_id, question, answer)
            return {"answer": answer, "sources": [], "has_sources": False,
                    "task_type": "knowledge_qa",
                    "evaluation": self._evaluation_meta(
                        sufficiency,
                        answer_quality=evaluate_answer(question, answer, [], chain="L2"))}

        # 将检索结果转为 LLM 可用的文档格式
        llm_docs = []
        for doc in docs:
            if hasattr(doc, 'page_content'):
                llm_docs.append(doc)
            elif isinstance(doc, dict):
                from langchain_core.documents import Document
                llm_docs.append(Document(
                    page_content=doc.get('content', ''),
                    metadata=doc.get('metadata', {})
                ))

        _g0 = time.time()
        answer = self.llm_service.get_answer(question, llm_docs, full_context)
        gen_ms = (time.time() - _g0) * 1000
        # 降级策略：同 L1
        if not sufficiency.is_sufficient:
            answer = "❗知识库中相关内容有限，以下回答仅供参考。\n\n" + answer

        # 来源列表: 不再走 citation_sources —— 引用整合器在答案生成前被调用
        # (无法做真实引用匹配), 且其 min_citation_score=0.3 作用于 rerank 关键词
        # 分数(语义不同), 会把大部分来源错误过滤成 1 篇。统一用 _build_sources
        # 按文档去重, 展示全部命中文档。
        sources = self._build_sources(docs)
        # 先评估后写记忆 —— 保证轨迹时序: 答案生成 → 记忆写入
        answer_quality = evaluate_answer(question, answer, sources,
                                         chain="L2", gen_ms=gen_ms)
        self._save_to_memory(conversation_id, question, answer)

        return {
            "answer": answer, "sources": sources,
            "has_sources": len(sources) > 0, "task_type": "knowledge_qa",
            "evaluation": self._evaluation_meta(sufficiency, answer_quality=answer_quality)
        }

    def _evaluation_meta(self, sufficiency, answer_quality: Optional[dict] = None) -> Dict[str, Any]:
        """评估结果摘要（随响应返回，前端/管理端可展示）"""
        meta = {
            "is_sufficient": sufficiency.is_sufficient,
            "confidence": sufficiency.confidence,
            "reasoning": sufficiency.reasoning,
        }
        if answer_quality is not None:
            meta["answer_quality"] = answer_quality
        return meta

    def _build_sources(self, docs: list) -> list:
        """构建引用来源（按 doc_id 去重, 统计各文档命中的片段数）"""
        seen_doc_ids = set()
        chunk_counts = {}
        sources = []
        for doc in docs:
            metadata = getattr(doc, 'metadata', {}) if hasattr(doc, 'metadata') else (
                doc.get('metadata', {}) if isinstance(doc, dict) else {}
            )
            doc_id = metadata.get("doc_id")
            if doc_id:
                chunk_counts[doc_id] = chunk_counts.get(doc_id, 0) + 1
            if doc_id and doc_id in seen_doc_ids:
                continue
            if doc_id:
                seen_doc_ids.add(doc_id)
            sources.append({
                "doc_id": doc_id,
                "doc": metadata.get("source", "未知文档"),
                "page": metadata.get("page"),
                "chunk_index": metadata.get("chunk_index"),
                "score": metadata.get("score", 0)
            })
        # 回填每个文档命中的片段数 (例: 5 个 chunk 同属一篇 PDF → 该来源标 5)
        for s in sources:
            if s.get("doc_id") and s["doc_id"] in chunk_counts:
                s["chunk_count"] = chunk_counts[s["doc_id"]]
        return sources

    def _save_to_memory(self, conversation_id: str, question: str, answer: str):
        """保存对话到会话记忆"""
        if not conversation_id or not tool_registry.has_tool("conversation_memory_write"):
            return
        _wt0 = time.time()
        try:
            tool_registry.invoke_tool(
                "conversation_memory_write",
                {"conversation_id": conversation_id, "role": "user", "content": question}
            )
            tool_registry.invoke_tool(
                "conversation_memory_write",
                {"conversation_id": conversation_id, "role": "assistant", "content": answer}
            )
            publish_step("memory_write", {
                "written": "user + assistant",
            }, duration_ms=(time.time() - _wt0) * 1000)
            logger.info(f"[KnowledgeQAAgent] Saved conversation to memory")
        except Exception as e:
            logger.warning(f"[KnowledgeQAAgent] Failed to write conversation memory: {e}")

    def _format_history(self, messages: list) -> str:
        """格式化对话历史为上下文字符串"""
        if not messages:
            return ""

        formatted = []
        for msg in messages:
            role = msg.get("role", "unknown")
            content = msg.get("content", "")
            if role == "system":
                formatted.append(content)
            elif role == "user":
                formatted.append(f"用户: {content}")
            elif role == "assistant":
                formatted.append(f"AI: {content}")

        return "\n".join(formatted)

    def ask_stream(self, question: str, conversation_id: Optional[str] = None,
                   user_id: Optional[str] = None, context: str = "",
                   **kwargs) -> Generator[str, None, None]:
        """
        流式处理知识问答 - 精简RAG链路

        直接走：向量检索 → LLM流式生成 → 返回
        """
        logger.info(f"[KnowledgeQAAgent] Stream processing question: {question[:50]}...")

        try:
            # 1. 直接向量检索
            _t0 = time.time()
            docs = self.vector_store.search(
                query=question,
                k=5,
                similarity_threshold=0.45,
                use_rerank=False
            )
            search_ms = (time.time() - _t0) * 1000

            logger.info(f"[KnowledgeQAAgent] Retrieved {len(docs)} documents")

            # 1.5 评估层：流式链路同样留痕；不充分时先给提示再生成
            sufficiency = evaluate_retrieval(docs, question, chain="STREAM",
                                             search_ms=search_ms)
            if docs and not sufficiency.is_sufficient:
                yield json.dumps({
                    "type": "token",
                    "content": "❗知识库中相关内容有限，以下回答仅供参考。\n\n"
                }, ensure_ascii=False)

            # 2. 构建引用来源（按 doc_id 去重）
            seen_doc_ids = set()
            sources = []
            for doc in docs:
                metadata = getattr(doc, 'metadata', {})
                doc_id = metadata.get("doc_id")
                if doc_id and doc_id in seen_doc_ids:
                    continue
                if doc_id:
                    seen_doc_ids.add(doc_id)
                sources.append({
                    "doc_id": doc_id,
                    "doc": metadata.get("source", "未知文档"),
                    "page": metadata.get("page"),
                    "chunk_index": metadata.get("chunk_index"),
                })

            # 3. 流式 LLM 生成
            for chunk in self.llm_service.get_answer_stream(
                question=question,
                context_docs=docs,
                conversation_context=context
            ):
                yield chunk

            # 4. 发送来源信息
            yield json.dumps({
                "type": "sources",
                "sources": sources,
                "task_type": "knowledge_qa"
            })

        except Exception as e:
            logger.error(f"[KnowledgeQAAgent] Stream QA failed: {e}", exc_info=True)
            yield json.dumps({
                "type": "error",
                "content": "处理问题时遇到错误，请稍后再试。"
            })

    def _ask_with_orchestrator(self, question: str, conversation_id: Optional[str] = None,
                               user_id: Optional[str] = None, context: str = "",
                               **kwargs) -> Dict[str, Any]:
        """
        使用原有的Orchestrator方式进行知识问答（回退方案）
        """
        logger.info(f"[KnowledgeQAAgent] Using orchestrator fallback...")
        result = self.orchestrator.run(
            input_text=question,
            conversation_id=conversation_id,
            user_id=user_id,
            context=context,
            goal=f"回答知识问题: {question[:50]}...",
            **kwargs
        )
        return result

    def _ask_stream_with_orchestrator(self, question: str, conversation_id: Optional[str] = None,
                                      user_id: Optional[str] = None, context: str = "",
                                      **kwargs) -> Generator[str, None, None]:
        """
        使用原有的Orchestrator方式进行流式知识问答（回退方案）
        """
        logger.info(f"[KnowledgeQAAgent] Using orchestrator stream fallback...")
        for event in self.orchestrator.run_stream(
            input_text=question,
            conversation_id=conversation_id,
            user_id=user_id,
            context=context,
            goal=f"流式回答知识问题: {question[:50]}...",
            **kwargs
        ):
            yield event

    def register_callback(self, event_type: str, callback):
        """注册事件回调"""
        self.event_bus.subscribe(event_type, callback)

    def unregister_callback(self, event_type: str, callback):
        """取消事件回调"""
        self.event_bus.unsubscribe(event_type, callback)
