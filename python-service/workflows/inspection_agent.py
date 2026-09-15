from typing import Dict, Any, Optional, Generator, List
from core.mysql_client import mysql_client
from core.stream_utils import split_text_chunks
from core.llm import llm
import logging
import json

logger = logging.getLogger(__name__)


class InspectionAgent:
    """知识巡检Agent - 专门处理知识库质量检查的工作流"""

    def __init__(self):
        self.inspection_types = {
            "duplicate": "重复文档检测",
            "low_quality": "低质量片段检测",
            "stale": "过期知识检测",
            "unpopular": "无人访问文档检测",
        }

    def inspect(self, inspection_type: str, conversation_id: Optional[str] = None,
               user_id: Optional[str] = None, context: str = "",
               **kwargs) -> Dict[str, Any]:
        """
        执行知识巡检

        Args:
            inspection_type: 巡检类型 (duplicate/low_quality/stale/unpopular)
            conversation_id: 会话ID
            user_id: 用户ID
            context: 对话上下文
            **kwargs: 其他参数

        Returns:
            包含巡检结果的字典
        """
        logger.info(f"[InspectionAgent] Processing inspection: {inspection_type}")

        try:
            if inspection_type == "duplicate":
                return self._check_duplicate_docs()

            elif inspection_type == "low_quality":
                return self._check_low_quality_chunks()

            elif inspection_type == "stale":
                return self._check_stale_knowledge()

            elif inspection_type == "unpopular":
                return self._check_unpopular_docs()

            else:
                return self._run_full_inspection()

        except Exception as e:
            logger.error(f"[InspectionAgent] Inspection error: {str(e)}")
            return {
                "answer": f"执行巡检时出错：{str(e)}",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "error": True
            }

    def inspect_stream(self, inspection_type: str, conversation_id: Optional[str] = None,
                      user_id: Optional[str] = None, context: str = "",
                      **kwargs) -> Generator[str, None, None]:
        """
        流式执行知识巡检

        Args:
            inspection_type: 巡检类型
            conversation_id: 会话ID
            user_id: 用户ID
            context: 对话上下文
            **kwargs: 其他参数

        Yields:
            JSON格式的事件流
        """
        logger.info(f"[InspectionAgent] Stream inspection: {inspection_type}")

        try:
            result = self.inspect(inspection_type, conversation_id, user_id, context, **kwargs)
            answer = result.get("answer", "")

            yield json.dumps({
                "type": "inspection_started",
                "inspection_type": inspection_type
            })

            # ① 数据层: 巡检结果是数据库查询拼装的, 按段落分块先行下发
            #   （不再 for char 逐字回放 —— 那是伪流式打字动画）
            for chunk in split_text_chunks(answer):
                yield json.dumps({
                    "type": "token",
                    "content": chunk
                })

            # ② 解读层: 与运营报告/管理助手各报告一致 —— 报告数据交给 LLM
            #    做结论/风险/建议（真流式）; LLM 只解读不生成数据, 失败静默跳过
            insight = ""
            for chunk in llm.generate_report_insight_stream(answer, "知识巡检"):
                insight += chunk
                yield json.dumps({
                    "type": "token",
                    "content": chunk
                })

            if insight:
                result = {**result, "answer": answer + insight}

            # default=str: 巡检结果含数据库查出的 date/datetime 等非 JSON 类型
            yield json.dumps({
                "type": "end",
                "content": result
            }, default=str)
        except Exception as e:
            logger.error(f"[InspectionAgent] Stream error: {str(e)}")
            yield json.dumps({
                "type": "error",
                "content": str(e)
            })

    def _check_duplicate_docs(self) -> Dict[str, Any]:
        """检测重复文档"""
        try:
            # 查询可能有重复的文档（相同标题）
            # 表/列名以真实 schema 为准: knowledge_doc.doc_name
            # （旧代码写 knowledge_docs/title/is_deleted/created_at → SQL 全部报错 → 恒显示"未发现"）
            query = """
                SELECT d1.id as doc1_id, d1.doc_name as doc1_title,
                       d2.id as doc2_id, d2.doc_name as doc2_title
                FROM knowledge_doc d1
                JOIN knowledge_doc d2 ON d1.doc_name = d2.doc_name AND d1.id < d2.id
                LIMIT 20
            """
            duplicates = mysql_client.fetch_all(query) or []

            if not duplicates:
                return {
                    "answer": "✅ 重复文档检测完成\n\n未发现明显的重复文档，知识库文档唯一性良好。",
                    "sources": [],
                    "has_sources": False,
                    "task_type": "knowledge_inspection",
                    "inspection_type": "duplicate",
                    "data": {"duplicates": [], "count": 0}
                }

            duplicate_list = []
            for dup in duplicates:
                duplicate_list.append({
                    "doc1_id": dup.get("doc1_id"),
                    "doc1_title": dup.get("doc1_title"),
                    "doc2_id": dup.get("doc2_id"),
                    "doc2_title": dup.get("doc2_title"),
                })

            answer = f"""⚠️ 发现 {len(duplicates)} 对可能重复的文档：

"""
            for i, dup in enumerate(duplicate_list[:5], 1):
                answer += f"{i}. 《{dup['doc1_title']}》 与 《{dup['doc2_title']}》\n"

            if len(duplicate_list) > 5:
                answer += f"\n... 还有 {len(duplicate_list) - 5} 对重复文档未显示"

            answer += "\n\n建议：请管理员核实这些文档是否真的重复，决定是否合并或删除。"

            return {
                "answer": answer,
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "duplicate",
                "data": {"duplicates": duplicate_list, "count": len(duplicate_list)}
            }
        except Exception as e:
            logger.error(f"[InspectionAgent] Duplicate check error: {str(e)}")
            return {
                "answer": "重复文档检测失败，请稍后重试。",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "duplicate",
                "error": True
            }

    def _check_low_quality_chunks(self) -> Dict[str, Any]:
        """检测低质量知识片段"""
        try:
            # 查询可能低质量的片段（内容过短或过长）
            # 真实列名: knowledge_chunk.chunk_text（旧代码写 content + 不存在的 is_deleted）
            query = """
                SELECT id, doc_id, chunk_text, LENGTH(chunk_text) as content_length
                FROM knowledge_chunk
                WHERE LENGTH(chunk_text) < 50 OR LENGTH(chunk_text) > 5000
                ORDER BY content_length ASC
                LIMIT 20
            """
            low_quality = mysql_client.fetch_all(query) or []

            if not low_quality:
                return {
                    "answer": "✅ 低质量片段检测完成\n\n未发现明显的低质量知识片段，所有片段长度都在合理范围内。",
                    "sources": [],
                    "has_sources": False,
                    "task_type": "knowledge_inspection",
                    "inspection_type": "low_quality",
                    "data": {"low_quality_chunks": [], "count": 0}
                }

            chunk_list = []
            for chunk in low_quality:
                chunk_list.append({
                    "chunk_id": chunk.get("id"),
                    "doc_id": chunk.get("doc_id"),
                    "content_length": chunk.get("content_length"),
                })

            too_short = sum(1 for c in chunk_list if c["content_length"] < 50)
            too_long = sum(1 for c in chunk_list if c["content_length"] > 5000)

            answer = f"""⚠️ 发现 {len(low_quality)} 个可能低质量的知识片段：

📊 统计：
- 内容过短（<50字）：{too_short} 个
- 内容过长（>5000字）：{too_long} 个

"""
            for i, chunk in enumerate(chunk_list[:5], 1):
                if chunk["content_length"] < 50:
                    answer += f"{i}. 片段ID {chunk['chunk_id']}：内容过短（{chunk['content_length']}字）\n"
                else:
                    answer += f"{i}. 片段ID {chunk['chunk_id']}：内容过长（{chunk['content_length']}字）\n"

            if len(chunk_list) > 5:
                answer += f"\n... 还有 {len(chunk_list) - 5} 个片段未显示"

            answer += "\n\n建议：请管理员审核这些片段，过短的考虑合并，过长的考虑拆分。"

            return {
                "answer": answer,
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "low_quality",
                "data": {"low_quality_chunks": chunk_list, "count": len(chunk_list)}
            }
        except Exception as e:
            logger.error(f"[InspectionAgent] Low quality check error: {str(e)}")
            return {
                "answer": "低质量片段检测失败，请稍后重试。",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "low_quality",
                "error": True
            }

    def _check_stale_knowledge(self) -> Dict[str, Any]:
        """检测过期知识"""
        try:
            # 查询30天以上未更新的文档
            # 注意: knowledge_doc 表只有 create_time, 没有 update_time 列 ——
            # 旧代码查 updated_at（列不存在 → 恒报错 → 恒显示"未发现过期"）。
            # 这里以创建时间近似, 并在文案中如实说明口径。
            query = """
                SELECT id, doc_name as title, create_time
                FROM knowledge_doc
                WHERE create_time < DATE_SUB(NOW(), INTERVAL 30 DAY)
                ORDER BY create_time ASC
                LIMIT 20
            """
            stale_docs = mysql_client.fetch_all(query) or []

            if not stale_docs:
                return {
                    "answer": "✅ 过期知识检测完成\n\n所有文档都是30天内创建的，知识库内容较为新鲜。",
                    "sources": [],
                    "has_sources": False,
                    "task_type": "knowledge_inspection",
                    "inspection_type": "stale",
                    "data": {"stale_docs": [], "count": 0}
                }

            doc_list = []
            for doc in stale_docs:
                doc_list.append({
                    "doc_id": doc.get("id"),
                    "title": doc.get("title"),
                    "updated_at": str(doc.get("create_time")),
                })

            answer = f"""⚠️ 发现 {len(stale_docs)} 个 30 天前创建的文档（系统未记录文档更新时间，以创建时间近似）：

"""
            for i, doc in enumerate(doc_list[:5], 1):
                answer += f"{i}. 《{doc['title']}》 - 创建于：{doc['updated_at']}\n"

            if len(doc_list) > 5:
                answer += f"\n... 还有 {len(doc_list) - 5} 个文档未显示"

            answer += "\n\n建议：请管理员审核这些文档，确认内容是否仍然有效，必要时进行更新。"

            return {
                "answer": answer,
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "stale",
                "data": {"stale_docs": doc_list, "count": len(doc_list)}
            }
        except Exception as e:
            logger.error(f"[InspectionAgent] Stale knowledge check error: {str(e)}")
            return {
                "answer": "过期知识检测失败，请稍后重试。",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "stale",
                "error": True
            }

    def _check_unpopular_docs(self) -> Dict[str, Any]:
        """检测无人访问的文档"""
        try:
            # 数据源说明: 旧代码查询的 doc_view_logs 表并不存在
            # （SQL 恒报错 → 恒显示"所有文档都有访问记录"的假结论）。
            # 改用真实可得的代理指标: 以"回答中引用过该文档"计为被使用 ——
            # 从 message.sources（JSON 数组, 含 doc_id）统计每篇文档的引用次数。
            docs = mysql_client.fetch_all(
                "SELECT id, doc_name as title, create_time FROM knowledge_doc "
                "WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY)"
            ) or []
            cited_rows = mysql_client.fetch_all(
                "SELECT sources FROM message WHERE sources IS NOT NULL AND sources != ''"
            ) or []

            cite_count = {}
            for row in cited_rows:
                try:
                    arr = json.loads(row.get("sources") or "[]")
                    if isinstance(arr, list):
                        for s in arr:
                            did = (s or {}).get("doc_id")
                            if did is not None:
                                cite_count[did] = cite_count.get(did, 0) + 1
                except Exception:
                    continue  # 单条 sources 解析失败不影响整体统计

            unpopular = [d for d in docs if d.get("id") not in cite_count]

            if not unpopular:
                return {
                    "answer": "✅ 冷门文档检测完成\n\n所有文档都至少被回答引用过一次，知识库使用率良好。",
                    "sources": [],
                    "has_sources": False,
                    "task_type": "knowledge_inspection",
                    "inspection_type": "unpopular",
                    "data": {"unpopular_docs": [], "count": 0}
                }

            doc_list = []
            for doc in unpopular:
                doc_list.append({
                    "doc_id": doc.get("id"),
                    "title": doc.get("title"),
                    "created_at": str(doc.get("create_time")),
                    "view_count": 0,
                })

            answer = f"""⚠️ 发现 {len(unpopular)} 个从未被回答引用过的文档（以"回答引用"作为使用代理指标）：

"""
            for i, doc in enumerate(doc_list[:5], 1):
                answer += f"{i}. 《{doc['title']}》 - 创建于：{doc['created_at']}\n"

            if len(doc_list) > 5:
                answer += f"\n... 还有 {len(doc_list) - 5} 个文档未显示"

            answer += "\n\n建议：请管理员审核这些文档，确认是否需要更新内容或从知识库移除。"

            return {
                "answer": answer,
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "unpopular",
                "data": {"unpopular_docs": doc_list, "count": len(doc_list)}
            }
        except Exception as e:
            logger.error(f"[InspectionAgent] Unpopular docs check error: {str(e)}")
            return {
                "answer": "无人访问文档检测失败，请稍后重试。",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "unpopular",
                "error": True
            }

    def _run_full_inspection(self) -> Dict[str, Any]:
        """执行完整巡检"""
        try:
            # 获取各项巡检结果
            duplicate_result = self._check_duplicate_docs()
            low_quality_result = self._check_low_quality_chunks()
            stale_result = self._check_stale_knowledge()
            unpopular_result = self._check_unpopular_docs()

            # 汇总问题数量
            total_issues = (
                duplicate_result.get("data", {}).get("count", 0) +
                low_quality_result.get("data", {}).get("count", 0) +
                stale_result.get("data", {}).get("count", 0) +
                unpopular_result.get("data", {}).get("count", 0)
            )

            answer = f"""🔍 知识库完整巡检报告

━━━━━━━━━━━━━━━━━━

📋 巡检项目概览：

1️⃣ 重复文档检测：{duplicate_result.get("data", {}).get("count", 0)} 个问题
2️⃣ 低质量片段检测：{low_quality_result.get("data", {}).get("count", 0)} 个问题
3️⃣ 过期知识检测：{stale_result.get("data", {}).get("count", 0)} 个问题
4️⃣ 无人访问文档：{unpopular_result.get("data", {}).get("count", 0)} 个问题

━━━━━━━━━━━━━━━━━━

📊 问题总计：{total_issues} 个

"""

            if total_issues == 0:
                answer += "🎉 恭喜！知识库质量良好，未发现明显问题。"
            else:
                answer += "⚠️ 建议及时处理以上问题，以保持知识库质量。\n\n如需详细查看某一类问题，请单独执行该类巡检。"

            return {
                "answer": answer,
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "full",
                "data": {
                    "duplicate_count": duplicate_result.get("data", {}).get("count", 0),
                    "low_quality_count": low_quality_result.get("data", {}).get("count", 0),
                    "stale_count": stale_result.get("data", {}).get("count", 0),
                    "unpopular_count": unpopular_result.get("data", {}).get("count", 0),
                    "total_issues": total_issues
                }
            }
        except Exception as e:
            logger.error(f"[InspectionAgent] Full inspection error: {str(e)}")
            return {
                "answer": f"完整巡检失败：{str(e)}",
                "sources": [],
                "has_sources": False,
                "task_type": "knowledge_inspection",
                "inspection_type": "full",
                "error": True
            }

    def get_inspection_summary(self) -> Dict[str, Any]:
        """获取巡检摘要（不执行详细检测）"""
        return {
            "available_inspections": self.inspection_types,
            "usage": {
                "duplicate": "检测标题相同的重复文档",
                "low_quality": "检测内容过短或过长的片段",
                "stale": "检测30天以上未更新的文档",
                "unpopular": "检测7天内无人访问的文档",
                "full": "执行完整巡检（包含以上四项）"
            }
        }
