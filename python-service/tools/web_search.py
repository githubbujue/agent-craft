from typing import Dict, Any, List

from tools.base import Tool, ToolSchema, SchemaProperty, ToolMetadata
from core.config import config


class WebSearchTool(Tool):
    """联网搜索工具 —— 知识库零命中时的补救检索源

    与 knowledge_search 的分工:
        知识库检索 = 主路径(私有资料, 有权威性)
        联网搜索   = 补救路径(公开信息, 仅在知识库完全无料时启用)

    设计要点:
        1. is_available() 自检 —— 未配 key / 开关关闭时调用方直接跳过,
           整条链路与加此功能前完全一致(零回归风险)
        2. 供应商相关代码收敛在 _search_tavily / _normalize 两个方法里,
           以后换 Serper/Bing 只需再加一个 _search_xxx, 调用方零改动
        3. 输出结构与 knowledge_search 保持同构({results, count}),
           调用方无需为"联网"写分支
    """

    def __init__(self):
        input_schema = ToolSchema(
            properties={
                "query": SchemaProperty(
                    type="string",
                    description="搜索关键词",
                    required=True
                ),
                "top_k": SchemaProperty(
                    type="number",
                    description="返回结果数量",
                    required=False,
                    default=config.WEB_SEARCH_TOP_K
                ),
            },
            type="object"
        )

        output_schema = ToolSchema(
            properties={
                "results": SchemaProperty(
                    type="array",
                    description="搜索结果列表(title / url / snippet)",
                    required=True
                ),
                "count": SchemaProperty(
                    type="number",
                    description="返回条数",
                    required=True
                ),
            },
            type="object"
        )

        metadata = ToolMetadata(
            timeout_ms=config.WEB_SEARCH_TIMEOUT_MS,
            # 联网失败多因超时/限流, 重试一次足矣 —— 再多只会让用户等更久
            max_retries=1,
            permission="user",
            description="联网搜索互联网上的公开信息"
        )

        super().__init__(
            name="web_search",
            description="联网搜索互联网上的公开信息",
            input_schema=input_schema,
            output_schema=output_schema,
            metadata=metadata
        )

    def is_available(self) -> bool:
        """是否可用: 开关打开 且 配了 key。

        不可用时调用方直接跳过, 不进任何网络调用 —— 这是"没配 key 就等于没这功能"
        的保证点, 也是本功能零回归风险的关键。
        """
        return bool(config.WEB_SEARCH_ENABLED and config.TAVILY_API_KEY)

    def execute(self, parameters: Dict[str, Any]) -> Dict[str, Any]:
        """执行联网搜索"""
        query = (parameters.get("query") or "").strip()
        if not query:
            return {"results": [], "count": 0}

        top_k = int(parameters.get("top_k") or config.WEB_SEARCH_TOP_K)

        raw = self._search_tavily(query, top_k)
        results = self._normalize(raw)
        return {"results": results, "count": len(results)}

    # ---------------- 供应商相关: 换引擎只需替换/新增 _search_xxx ----------------

    def _search_tavily(self, query: str, top_k: int) -> List[dict]:
        """Tavily Search API —— 为 LLM 优化, 直接返回正文摘要 + 链接"""
        import requests

        resp = requests.post(
            "https://api.tavily.com/search",
            json={
                "api_key": config.TAVILY_API_KEY,
                "query": query,
                "max_results": top_k,
                "search_depth": "basic",
                "include_answer": False,
            },
            # requests 的超时单位是秒; 与工具元数据的 timeout_ms 保持同一语义
            timeout=config.WEB_SEARCH_TIMEOUT_MS / 1000.0,
        )
        resp.raise_for_status()
        return resp.json().get("results", []) or []

    def _normalize(self, raw: List[dict]) -> List[dict]:
        """统一成 {title, url, snippet} —— 与具体供应商解耦"""
        out = []
        for item in raw:
            url = (item.get("url") or "").strip()
            if not url:
                # 没有链接的结果对"来源可追溯"没有价值, 直接丢掉
                continue
            out.append({
                "title": (item.get("title") or url).strip(),
                "url": url,
                "snippet": (item.get("content") or "").strip(),
                # Tavily 的相关性分(0-1) —— 上层会放进 metadata["score"],
                # 由 _build_sources 带进 sources 列表供前端展示
                "score": item.get("score"),
            })
        return out
