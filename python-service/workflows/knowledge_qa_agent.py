from typing import Dict, Any, Optional, Generator
from concurrent.futures import ThreadPoolExecutor
from core.events import EventBus, event_bus
from workflows.retrieval_agent import RetrievalAgent
from core.vector_store import vector_store
from core.llm import LLMService
from core.retrieval_evaluator import evaluate_retrieval, evaluate_answer, publish_step
from core.faithfulness_judge import trigger_faithfulness_judge
from core.config import config
from core.preference_context import USER_MEMORY_MIN_CONFIDENCE, format_user_memory
from tools.registry import tool_registry
import logging
import json
import re
import time

logger = logging.getLogger(__name__)

# 长期偏好抽取规则：只认用户显式表达（如"以后请用中文回答"），不做模型推测。
# 每条规则 → (memory_key, 正则, source, confidence)；捕获组 1 即偏好值。
# 显式指令（"以后/请始终…"）记 explicit + 1.0；带推断成分的记 inferred + 0.8~0.9。
_PREF_RULES = (
    ("answer_format", re.compile(
        r"(?:以后|今后|往后|从现在开始|请始终|请一直)[^。！？\n]{0,12}?"
        r"(?:用|使用|以)([\u4e00-\u9fa5A-Za-z]{1,10}?)(?:格式|形式|方式)"),
     "explicit", 1.0),
    ("answer_language", re.compile(
        r"(?:以后|今后|往后|从现在开始|请始终|请一直)[^。！？\n]{0,12}?"
        r"(?:用|使用|以)([\u4e00-\u9fa5A-Za-z]{1,8}?)(?:来)?(?:回答|作答|回复|输出)"),
     "explicit", 1.0),
    ("nickname", re.compile(
        r"(?:以后|今后|往后|请)(?:都)?(?:叫我|称呼我(?:为)?)([\u4e00-\u9fa5A-Za-z0-9]{1,12})"),
     "inferred", 0.9),
    ("preference", re.compile(
        r"我(?:一直|通常|平时)?(?:习惯|更喜欢|喜欢|偏好)([^，。！？、,;；!！?？\n]{2,30})"),
     "inferred", 0.8),
)

# 偏好抽取专用线程池：抽取可能要调一次 LLM，绝不能占用请求线程；
# 2 个并发足够（同一用户很少连着两句都在提偏好）
_pref_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="pref-extract")

# 读侧置信度下限在 core/preference_context（L1/L2 与 L3 共用同一实现），
# 这里 import 进来只为不破坏既有引用。


class KnowledgeQAAgent:
    """知识问答Agent - 三级链路：L1简化/L2标准/L3推理"""

    def __init__(self):
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
            # 1. 读取会话记忆 + 用户长期偏好，合并成 Prompt 上下文（与流式链路共用同一实现）
            full_context = self._build_full_context(conversation_id, user_id, context)

            # 2. 判断复杂度，选择链路
            complexity = self.router.classify_complexity(question)
            logger.info(f"[KnowledgeQAAgent] Complexity: {complexity}")

            if complexity == "medium":
                result = self._ask_l2(question, conversation_id, full_context)
            else:
                result = self._ask_l1(question, conversation_id, full_context)

            # 2.5 从用户问题中抽取长期偏好（仅显式/强规则，失败静默，不影响回答）
            self._maybe_remember(user_id, question)
            return result

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

        # 知识库零命中 → 联网补救; 拿不到才走下面的"通用知识兜底"
        used_web = False
        enable_search = False
        if not docs:
            if config.WEB_SEARCH_MODE == "tool":
                # tool 模式: 本项目自己搜, 能拿到来源链接
                docs = self._try_web_search(question, chain="L1")
                used_web = bool(docs)
            elif config.WEB_SEARCH_MODE == "bailian":
                # bailian 模式: 交给百炼服务端检索(无需额外 key, 但拿不到结构化来源)
                enable_search = True
                used_web = True
                self._publish_bailian_search(question, chain="L1")
                logger.info("[WebSearch][L1] bailian 模式: 将 enable_search=True 传给 LLM")

        if not docs:
            # 兜底策略：知识库无资料仍走 LLM 通用知识回答（而非冷拒绝），
            # 保证任何路径都有完整轨迹 + 有温度的回答
            # 兜底同样是一次真实 LLM 调用, 也要计耗时并传给 evaluate_answer,
            # 否则零命中时"答案生成"步骤会落成 0ms(此前漏传 gen_ms)
            _g0 = time.time()
            answer = self.llm_service.get_answer(question, [], full_context,
                                                 enable_search=enable_search)
            gen_ms = (time.time() - _g0) * 1000
            self._save_to_memory(conversation_id, question, answer)
            return {"answer": answer, "sources": [], "has_sources": False,
                    "task_type": "knowledge_qa",
                    "evaluation": self._evaluation_meta(
                        sufficiency,
                        answer_quality=evaluate_answer(question, answer, [],
                                                       chain="L1", gen_ms=gen_ms))}

        _g0 = time.time()
        answer = self.llm_service.get_answer(question, docs, full_context)
        gen_ms = (time.time() - _g0) * 1000
        # 降级提示分两种, 不能混用一句话:
        #   联网补救 → 如实说明"来自联网搜索"
        #   只有知识库且不充分 → 沿用原提示
        # (原提示说的是"知识库内容有限", 而网页本来就不是知识库, 套用会误导用户)
        if used_web:
            answer = "🌐 知识库中未找到相关资料，以下内容来自联网搜索，仅供参考。\n\n" + answer
        elif not sufficiency.is_sufficient:
            answer = "❗知识库中相关内容有限，以下回答仅供参考。\n\n" + answer
        sources = self._build_sources(docs)
        # 先评估后写记忆 —— 保证轨迹时序: 答案生成 → 记忆写入
        answer_quality = evaluate_answer(question, answer, sources,
                                         chain="L1", gen_ms=gen_ms)
        # 后台忠实度评判(非阻塞): 只派发 daemon 线程, 不影响响应耗时与轨迹时序
        trigger_faithfulness_judge(question, answer, docs, chain="L1")
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

        # 知识库零命中 → 联网补救; 拿不到才走下面的"通用知识兜底"
        used_web = False
        enable_search = False
        if not docs:
            if config.WEB_SEARCH_MODE == "tool":
                # tool 模式: 本项目自己搜, 能拿到来源链接
                docs = self._try_web_search(question, chain="L2")
                used_web = bool(docs)
            elif config.WEB_SEARCH_MODE == "bailian":
                # bailian 模式: 交给百炼服务端检索(无需额外 key, 但拿不到结构化来源)
                enable_search = True
                used_web = True
                self._publish_bailian_search(question, chain="L2")

        if not docs:
            # 兜底策略：同 L1 —— 知识库无资料仍走 LLM 通用知识回答
            # gen_ms 同 L1: 兜底也是真实 LLM 调用, 不传会让"答案生成"落成 0ms
            _g0 = time.time()
            answer = self.llm_service.get_answer(question, [], full_context,
                                                 enable_search=enable_search)
            gen_ms = (time.time() - _g0) * 1000
            self._save_to_memory(conversation_id, question, answer)
            return {"answer": answer, "sources": [], "has_sources": False,
                    "task_type": "knowledge_qa",
                    "evaluation": self._evaluation_meta(
                        sufficiency,
                        answer_quality=evaluate_answer(question, answer, [],
                                                       chain="L2", gen_ms=gen_ms))}

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
        # 降级提示: 同 L1 —— 联网来源必须如实标注, 不能套用"知识库内容有限"
        if used_web:
            answer = "🌐 知识库中未找到相关资料，以下内容来自联网搜索，仅供参考。\n\n" + answer
        elif not sufficiency.is_sufficient:
            answer = "❗知识库中相关内容有限，以下回答仅供参考。\n\n" + answer

        # 来源列表: 不再走 citation_sources —— 引用整合器在答案生成前被调用
        # (无法做真实引用匹配), 且其 min_citation_score=0.3 作用于 rerank 关键词
        # 分数(语义不同), 会把大部分来源错误过滤成 1 篇。统一用 _build_sources
        # 按文档去重, 展示全部命中文档。
        sources = self._build_sources(docs)
        # 先评估后写记忆 —— 保证轨迹时序: 答案生成 → 记忆写入
        answer_quality = evaluate_answer(question, answer, sources,
                                         chain="L2", gen_ms=gen_ms)
        # 后台忠实度评判(非阻塞): 只派发 daemon 线程, 不影响响应耗时与轨迹时序
        trigger_faithfulness_judge(question, answer, docs, chain="L2")
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
            entry = {
                "doc_id": doc_id,
                "doc": metadata.get("source", "未知文档"),
                "page": metadata.get("page"),
                "chunk_index": metadata.get("chunk_index"),
                "score": metadata.get("score", 0)
            }
            # 联网来源没有页码, 但有 URL。前端(用户端 Chat / 管理端 AdminChat)是按
            # "source 是否以 http(s) 开头"来决定"打开网页"还是"打开知识库文档"的,
            # 不把 URL 带出去, 网页来源就会被当成库内文档, 点开跳到一个不存在的 docId。
            if metadata.get("url"):
                entry["source"] = metadata["url"]
            sources.append(entry)
        # 回填每个文档命中的片段数 (例: 5 个 chunk 同属一篇 PDF → 该来源标 5)
        for s in sources:
            if s.get("doc_id") and s["doc_id"] in chunk_counts:
                s["chunk_count"] = chunk_counts[s["doc_id"]]
        return sources

    def _try_web_search(self, question: str, chain: str) -> list:
        """知识库零命中时的联网补救。

        返回可直接喂给 LLM 的 Document 列表; 不可用 / 失败 / 零结果时返回 []。

        全程 fail-open —— 联网是"补救手段", 任何异常都不得影响主链路;
        但失败会以 FAILED 状态留痕, 避免又变成"失败与压根没跑长得一样"。
        """
        from langchain_core.documents import Document

        _t0 = time.time()
        if not tool_registry.has_tool("web_search"):
            logger.warning(
                f"[WebSearch][{chain}] skipped: tool 'web_search' not registered "
                f"(WEB_SEARCH_MODE={config.WEB_SEARCH_MODE})")
            return []
        tool = tool_registry.get_tool("web_search")
        # 未配 key / 开关关闭 → 直接跳过, 连网络调用都不发起
        if not tool.is_available():
            # 不能静默返回: mode=tool 是使用者明确选择过的,
            # 缺 key 时整条链路消失, 现象与"根本没做联网功能"完全一致,
            # 排查时极易误判成代码没生效 —— 所以既要打日志, 也要留 SKIPPED 轨迹
            reason = ("WEB_SEARCH_ENABLED=false" if not config.WEB_SEARCH_ENABLED
                      else "TAVILY_API_KEY is empty")
            logger.warning(
                f"[WebSearch][{chain}] skipped: {reason} "
                f"(WEB_SEARCH_MODE={config.WEB_SEARCH_MODE}); "
                f"set TAVILY_API_KEY, or switch WEB_SEARCH_MODE to 'bailian'")
            publish_step("web_search",
                         {"hits": 0, "chain": chain, "skipped": reason},
                         duration_ms=(time.time() - _t0) * 1000,
                         input_text=question, status="SKIPPED")
            return []
        try:
            result = tool_registry.invoke_tool(
                "web_search",
                {"query": question, "top_k": config.WEB_SEARCH_TOP_K})
        except Exception as e:
            publish_step("web_search", {"hits": 0, "chain": chain},
                         duration_ms=(time.time() - _t0) * 1000,
                         input_text=question, status="FAILED", error=str(e)[:500])
            logger.warning(f"[WebSearch][{chain}] failed (ignored): {e}")
            return []

        docs = []
        for r in result.get("results", []):
            docs.append(Document(
                # 来源写进 page_content —— LLMService.get_answer 只读 page_content
                # (不读 metadata), 放 metadata 里模型看不见, 也就无法正确标注引用
                page_content=f"[网页来源: {r['title']} | {r['url']}]\n{r['snippet']}",
                metadata={
                    "doc_id": f"web:{r['url']}",
                    "source": r["title"],
                    "url": r["url"],
                    "score": r.get("score") or 0,
                },
            ))

        publish_step("web_search", {"hits": len(docs), "chain": chain},
                     duration_ms=(time.time() - _t0) * 1000, input_text=question)
        logger.info(f"[WebSearch][{chain}] {len(docs)} hits for: {question[:40]}")
        return docs

    def _publish_bailian_search(self, question: str, chain: str) -> None:
        """bailian 模式(百炼服务端联网)的留痕。

        与 tool 模式不同, 这里没有可独立测量的耗时 —— 检索在百炼侧完成, 时间已
        并入紧随其后的答案生成。所以 duration_ms 留空(页面显示 -), 并在 output
        里写明原因, 避免这一行被误读成"耗时没采集到"。
        """
        publish_step("web_search", {
            "provider": "bailian_enable_search",
            "chain": chain,
            "note": "百炼服务端检索, 耗时已计入答案生成",
        }, input_text=question)

    @staticmethod
    def _stream_piece(chunk: str) -> str:
        """从 get_answer_stream 的 token 事件中取出正文。

        该生成器产出的是要原样转发给前端的 SSE 载荷（``{"type":"token",...}``），
        但答案评估、忠实度评判、会话记忆需要的是纯文本。
        """
        if not chunk:
            return ""
        try:
            event = json.loads(chunk)
        except (TypeError, ValueError):
            return chunk
        if not isinstance(event, dict):
            return chunk
        if event.get("type") != "token":
            return ""
        content = event.get("content")
        return content if isinstance(content, str) else ""

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

    def _maybe_remember(self, user_id: Optional[str], question: str):
        """异步抽取用户长期偏好并写入 user_memory（MySQL）。

        交给后台线程：抽取可能要走一次 LLM，绝不能拖慢答案返回。
        无 user_id / 空问题 / 线程池满，全部静默跳过。
        """
        if not user_id or not question or not question.strip():
            return
        try:
            _pref_executor.submit(self._remember_task, str(user_id), question.strip())
        except Exception as e:
            # 长期记忆只是锦上添花，连提交失败都不能影响本次回答
            logger.debug(f"[KnowledgeQAAgent] Skip preference extraction: {e}")

    def _remember_task(self, user_id: str, question: str):
        """先走 LLM 抽取，没结果再用正则兜底；任何异常只记日志。"""
        try:
            from core.preference_extractor import extract_preferences

            items = extract_preferences(question)
            via = "llm"
            if not items:
                items = self._rule_preferences(question)
                via = "rule"
            if not items:
                return

            from core.mysql_client import user_memory_client
            for item in items:
                user_memory_client.update_user_memory(
                    user_id,
                    item["key"],
                    {"value": item["value"], "quote": question[:120]},
                    source=item["source"],
                    confidence=item["confidence"],
                )
            logger.info(
                "[KnowledgeQAAgent] Remembered user preference keys: %s (via %s)",
                [i["key"] for i in items], via,
            )
        except Exception as e:
            # 写入失败不影响本次回答
            logger.warning(f"[KnowledgeQAAgent] Failed to remember user preference: {e}")

    def _rule_preferences(self, question: str):
        """正则兜底：LLM 不可用或没抽出东西时，覆盖"以后请用中文回答"这类模板句。"""
        hits = []
        for key, pattern, source, confidence in _PREF_RULES:
            m = pattern.search(question)
            if not m:
                continue
            value = (m.group(1) or "").strip(" ，。、,.;；!！?？")
            if not value:
                continue
            # "以后用表格格式回答" 已由 answer_format 命中，避免语言键重复记录
            if key == "answer_language" and value.endswith(("格式", "形式", "方式")):
                continue
            hits.append({
                "key": key,
                "value": value,
                "source": source,
                "confidence": confidence,
            })
        return hits

    def _read_conversation_history(self, conversation_id: Optional[str]) -> str:
        """读最近 5 条原文。

        只取最近 5 条：更早的历史由 Java 侧以摘要形式放进 context，
        两边都传原文会让同一批对话在 Prompt 里重复（见 docs/记忆改造计划.md 阶段 1）。
        """
        if not conversation_id or not tool_registry.has_tool("conversation_memory_read"):
            return ""

        _mt0 = time.time()
        try:
            history = tool_registry.invoke_tool(
                "conversation_memory_read",
                {"conversation_id": conversation_id, "limit": 5}
            )
            messages = history.get("messages", [])
            publish_step("memory_read", {
                "message_count": len(messages) if messages else 0,
                "compressed": history.get("compressed", False),
            }, duration_ms=(time.time() - _mt0) * 1000)
            if messages:
                logger.info(f"[KnowledgeQAAgent] Loaded {len(messages)} messages from memory"
                            f" (compressed: {history.get('compressed', False)})")
                return self._format_history(messages)
        except Exception as e:
            logger.warning(f"[KnowledgeQAAgent] Failed to read conversation memory: {e}")
        return ""

    def _read_user_memory(self, user_id: Optional[str]) -> str:
        """读用户长期偏好，实现见 core/preference_context（L1/L2 与 L3 共用）。"""
        return format_user_memory(user_id)

    def _build_full_context(self, conversation_id: Optional[str], user_id: Optional[str],
                            context: str) -> str:
        """会话记忆 + 用户偏好 + Java 摘要 → 最终 Prompt 上下文。

        同步(ask)与流式(ask_stream)共用。此前流式链路自己拼上下文、且漏了记忆读取，
        用户实际用的聊天页因此既读不到最近 5 条原文、也拿不到长期偏好，
        而这一切是静默的 —— 只在回答质量上表现为"像失忆"。
        """
        conversation_history = self._read_conversation_history(conversation_id)
        user_memory_text = self._read_user_memory(user_id)

        full_context = context
        if conversation_history:
            full_context = f"{context}\n\n{conversation_history}" if context else conversation_history
        if user_memory_text:
            block = f"[用户长期偏好] {user_memory_text}"
            full_context = f"{full_context}\n\n{block}" if full_context else block

        logger.info(f"[KnowledgeQAAgent] prompt_context_len={len(full_context)} "
                    f"(java_summary={len(context)}, redis_history={len(conversation_history)}, "
                    f"user_memory={len(user_memory_text)})")
        return full_context

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
            # 0. 会话记忆 + 用户长期偏好（与同步链路共用同一实现）
            #    此前流式链路完全没有这一步, 聊天页既读不到最近 5 条原文、
            #    也拿不到"用户偏好用表格"这类记忆, 表现是回答像失忆。
            full_context = self._build_full_context(conversation_id, user_id, context)

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

            # 1.6 知识库零命中 → 联网补救(聊天页走的正是这条链路)
            #     必须放在"构建引用来源"之前 —— 这样才能让后面的 sources 与
            #     流式生成原样复用, 一行后续代码都不用改
            stream_enable_search = False
            if not docs:
                if config.WEB_SEARCH_MODE == "tool":
                    # tool 模式: 本项目自己搜, 能拿到来源链接
                    docs = self._try_web_search(question, chain="STREAM")
                elif config.WEB_SEARCH_MODE == "bailian":
                    # bailian 模式: 交给百炼服务端检索(无需额外 key)
                    stream_enable_search = True
                    self._publish_bailian_search(question, chain="STREAM")

                if docs or stream_enable_search:
                    yield json.dumps({
                        "type": "token",
                        "content": "🌐 知识库中未找到相关资料，以下内容来自联网搜索，仅供参考。\n\n"
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

            # 3. 流式 LLM 生成（累积全文 + 实测耗时, 供评估与留痕使用）
            #    注意: get_answer_stream 产出的是 SSE 载荷 {"type":"token","content":...},
            #    原样 yield 给前端, 但累积时必须取正文 —— 否则评估拿到的"答案"
            #    是一串 JSON, answer_length 虚高, 会话记忆里也写进 JSON 包装。
            _g0 = time.time()
            answer_parts = []
            for chunk in self.llm_service.get_answer_stream(
                question=question,
                context_docs=docs,
                conversation_context=full_context,
                enable_search=stream_enable_search
            ):
                piece = self._stream_piece(chunk)
                if piece:
                    answer_parts.append(piece)
                yield chunk
            answer = "".join(answer_parts)
            gen_ms = (time.time() - _g0) * 1000

            # 4. 发送来源信息
            yield json.dumps({
                "type": "sources",
                "sources": sources,
                "task_type": "knowledge_qa"
            })

            # 4.5 答案质量评估留痕 —— 与 L1/L2 对齐(流式链路此前缺这一步,
            #     导致轨迹里没有"答案生成"步骤)。gen_ms 传真实 LLM 耗时, 语义与同步链路一致。
            #     时序: 答案生成 → 忠实度评估, 与同步链路一致
            evaluate_answer(question, answer, sources, chain="STREAM", gen_ms=gen_ms)

            # 5. 后台忠实度评判(非阻塞): 流已发完, 派发线程即返回, 不拖慢收尾
            trigger_faithfulness_judge(question, answer, docs, chain="STREAM")

            # 4.8 写入会话记忆(Redis)。
            #     此前流式链路只"读"不"写": 第 0 步读到的 recent 5 条永远是空的
            #     (Java 落库在 MySQL, 会话记忆读的是 Redis), 表现是聊天页多轮对话
            #     像失忆 —— 指代("它")在下一轮找不到对象。同步/L1/L2/闲聊/L3 都写,
            #     唯独聊天页走的这条链漏了, 属于遗漏而非设计。
            self._save_to_memory(conversation_id, question, answer)

            # 6. 抽取显式长期偏好(失败静默, 不影响已发出的流式内容)
            self._maybe_remember(user_id, question)

        except Exception as e:
            logger.error(f"[KnowledgeQAAgent] Stream QA failed: {e}", exc_info=True)
            yield json.dumps({
                "type": "error",
                "content": "处理问题时遇到错误，请稍后再试。"
            })

    def register_callback(self, event_type: str, callback):
        """注册事件回调"""
        self.event_bus.subscribe(event_type, callback)

    def unregister_callback(self, event_type: str, callback):
        """取消事件回调"""
        self.event_bus.unsubscribe(event_type, callback)
