# AgentCraft 学习指南

> 配合 [README.md](./README.md) 使用：README 讲「这个项目是什么、怎么跑起来」，本指南讲「代码怎么读、怎么改、怎么讲」。
>
> 面试口径不要看这份，看 [简历与面试.md](./简历与面试.md)。

> **架构变更说明（2026-11-13）**
> 项目已删除从未被任何入口调用的编排骨架（`agent/orchestrator.py` / `planner.py` / `executor.py` /
> `state.py` / `policies.py` / `memory_agent.py`，约 1490 行），`agent/` 目录已不存在，事件总线迁至
> `core/events.py`。**本指南已按删除后的真实代码重写**，不再包含 Orchestrator / Planner / Executor 的教学内容。
> 真实请求链路是：`api/ → RouterAgent → workflows/*_agent 固定流程 → 评估层 → 事件总线落库`。

---

## 目录

- [第 0 章 概念扫盲（新手从这里开始）](#第-0-章-概念扫盲新手从这里开始)
- [第 1 章 项目全貌](#第-1-章-项目全貌)
- [第 2 章 学习路线](#第-2-章-学习路线)
- [第 3 章 核心代码解读](#第-3-章-核心代码解读)
- [第 4 章 和 LangGraph 的对照](#第-4-章-和-langgraph-的对照)
- [第 5 章 验证与调试](#第-5-章-验证与调试)
- [第 6 章 动手实践](#第-6-章-动手实践)
- [第 7 章 面试技术问答](#第-7-章-面试技术问答)
- [第 8 章 踩坑与经验教训](#第-8-章-踩坑与经验教训)
- [附录](#附录)

---

## 第 0 章 概念扫盲（新手从这里开始）

这一章不涉及本项目代码，只解释后面会反复出现的五个词。**已经懂的直接跳到第 1 章。**

### 0.1 什么是 Agent

传统程序是「输入 → 处理 → 输出」，每一步都由程序员写死。

Agent 是「输入 → 判断 → 决定下一步做什么 → 调工具 → 再判断 → 输出」，**下一步做什么由模型根据情况决定**。

举个对比：
- 传统程序：用户问"什么是数据库索引"，直接查库返回
- Agent：先判断意图（知识问答还是闲聊？）→ 决定要不要检索 → 检索完判断资料够不够 → 够了才生成

**本项目的 Agent 不是"自主规划型"**：它不做动态步骤规划，而是「路由一次 + 各 Agent 跑固定流程」。
这么设计是刻意的——链路短、延迟可控、每一步都能落成一条轨迹。第 3 章会讲为什么。

### 0.2 什么是 RAG

**RAG = Retrieval-Augmented Generation（检索增强生成）**。

要解决三个问题：
- LLM 的知识有截止日期
- LLM 不知道你公司的内部文档
- 直接问 LLM，它会一本正经地编（幻觉）

做法是三步：

```
用户问题
   ↓
① 检索：从知识库里找相关文档
   ↓
② 增强：把找到的文档作为"参考资料"塞进提示词
   ↓
③ 生成：让 LLM 只基于参考资料回答，并标注引用
```

本项目的链路：问题 → （可选改写）→ 向量检索 → （可选重排）→ 充分性判断 → 生成 + 引用标注。

### 0.3 什么是向量数据库

传统数据库靠精确匹配：

```sql
SELECT * FROM articles WHERE title LIKE '%数据库索引%'
```

向量数据库把文本转成一串数字（向量），靠**语义相似度**搜索：

```
"数据库索引怎么优化"  →  [0.12, 0.85, 0.33, ...]
                              ↓ 与库中向量算余弦相似度
                       取最相似的 Top-K 片段
```

好处是「数据库索引」和「索引优化技巧」字面不同、语义相近，也能搜到。

本项目：
- 配置 `USE_MILVUS=false`（默认）→ **FAISS**，索引落在 `python-service/faiss_index/`
- 配置 `USE_MILVUS=true` → **Milvus**；未装 `pymilvus` 或连不上会自动回退 FAISS

### 0.4 什么是 LLM

就是 GPT、通义千问、DeepSeek 这类大模型。本项目里它干四件事：判断意图、改写问题、生成答案、评判忠实度。

**模型怎么配**（`python-service/core/config.py`、`.env`）：

```
LLM_MODEL=deepseek-v4.1-flash        # 换模型只改这一行
LLM_BASE_URL=...compatible-mode/v1   # 默认百炼 OpenAI 兼容端点
LLM_API_KEY=...                      # 不配则沿用 DASHSCOPE_API_KEY
```

同一个 OpenAI 兼容端点可以跑通义、DeepSeek、Kimi 等托管模型，换模型只改 `LLM_MODEL`。
**本项目没有接 Ollama 本地模型**——早期文档写过，是错的。

Embedding 有两种：`EMBEDDING_MODEL=dashscope`（云端 API）或 `local`（本地
`BAAI/bge-small-zh-v1.5`）。

### 0.5 什么是 Tool

Tool 是 Agent 能调用的"能力"，像项目经理手下的员工：Agent 负责决策，Tool 负责干活。

本项目的工具在 `python-service/tools/__init__.py` 里统一注册，**实际注册 8 个**：

| 工具名 | 作用 |
|---|---|
| `question_rewrite` | 问题改写（LLM 语义改写 + 关键词降级）|
| `knowledge_search` | 向量相似度检索 |
| `rerank` | 检索结果重排 |
| `conversation_memory_read` | 读取会话记忆（含压缩）|
| `conversation_memory_write` | 写入会话记忆 |
| `doc_summary` | 文档摘要 |
| `ocr_extract` | 图片 OCR 解析 |
| `web_search` | 联网检索（Tavily，`tool` 模式用）|

两个容易被误解的点：
- 记忆工具的真实名字带 `conversation_` 前缀，不是 `memory_read`
- `answer_generation` **不是工具**，它是 Agent 内部的步骤；`tools/citation.py` 也不是注册工具，
  而是 `RetrievalAgent` 直接 import 的引用标注辅助类

---

## 第 1 章 项目全貌

### 1.1 一句话与它解决的问题

> 一个 Agent 编排层自研的企业知识库问答系统：Java + Python + React 三服务，实现三级 RAG 链路、
> 多 Agent 路由、三层评估与全链路毫秒级轨迹留痕。

要解决的问题：企业文档散落在各处、检索只能靠关键词、答案给出来了却没依据可查。
本项目把「文档 → 可追溯答案」做通，并且每一步都留痕 —— 出问题能定位到具体哪一步、哪个工具、耗时多少。

### 1.2 三服务架构

```
┌──────────┐     ┌──────────────────┐     ┌─────────────────────┐
│  React   │────▶│  Java (Spring    │────▶│  Python (FastAPI    │
│  前端     │◀────│  Boot) :8080     │◀────│  + LangChain) :8000 │
└──────────┘     │  业务/鉴权/留痕    │     │  多Agent/评估/轨迹    │
                 └────────┬─────────┘     └──────┬───────┬──────┘
                          │                      │       │
                    ┌─────▼─────┐         ┌──────▼──┐ ┌──▼─────┐
                    │ MySQL     │         │ Redis   │ │ FAISS  │
                    │ 业务/轨迹  │         │ 会话记忆 │ │ 向量库  │
                    └───────────┘         └─────────┘ └────────┘
```

职责划分：
- **React**：用户端（问答/知识库/登录）+ 管理端（仪表盘/轨迹/评估/知识管理等，共 12 页）
- **Java**：鉴权、会话与消息落库、文档管理、轨迹查询接口
- **Python**：所有 AI 逻辑——路由、检索、生成、评估、埋点

> **不要把它理解成"五层可编排架构"。** 那是早期按代码目录画的分层图，对应的 Orchestrator 层已删除。
> 现在的分层是 `api/ → workflows/ → tools/ + core/`，是**代码组织方式**，不是运行时数据流。

### 1.3 一次请求的完整数据流

这是全篇最重要的一张图，**真实链路由代码验证过**：

```
用户在前端提问
   │
   ▼
1. Java 后端接收（鉴权 + 落 message 表），调用 Python
   │
   ▼
2. POST /api/ask/stream（前端这条流式接口）或 /api/ask（非流式）
   │  api/routes.py：生成 run_id → set_run_id(run_id)
   ▼
3. RouterAgent.route()
   │  ├─ IntentClassifier.classify()  意图分类（LLM 优先 + 关键词兜底）
   │  └─ classify_complexity()        复杂度判定（关键词 + 长度，纯规则）
   ▼
4. 分发到 5 个目标之一
   ├─ ChitChatAgent        闲聊
   ├─ KnowledgeQAAgent     知识问答   ← 主链路
   ├─ AdminCopilotAgent    管理助手（内部委托 OpsAgent 做数据分析）
   ├─ InspectionAgent      知识巡检
   └─ ReasoningAgent       推理（知识问答被判定为 complex 时升级过来）
   │
   ▼
5. KnowledgeQAAgent.ask()
   ├─ conversation_memory_read   读会话记忆
   ├─ _ask_l1 / _ask_l2          选链路
   │    └─ RetrievalAgent.retrieve()  改写 → 检索 → 重排
   ├─ 充分性评估（core/retrieval_evaluator）
   ├─ LLM 生成答案 + 引用
   ├─ trigger_faithfulness_judge  后台线程判忠实度（不阻塞）
   └─ conversation_memory_write  写回记忆
   │
   ▼
6. EventBus 发布事件 → RunRecorder 落库 agent_run / agent_step
   │
   ▼
7. 返回给 Java → SSE 流式给前端
```

关键点：
- **只有一条主链路**，没有"状态机驱动"这回事
- `run_id` 是全链路唯一的串联标识，靠 `core/run_context.py` 的 `contextvars` 传递，不用层层传参
- 轨迹落库是**旁路**：埋点失败不影响业务响应

### 1.4 目录导航：该读哪些文件

```
python-service/
├── main.py                    FastAPI 入口（挂 /api 前缀）
├── api/
│   ├── routes.py              /api/ask、/api/ask/stream、/api/parse、/api/summary…
│   └── agent_routes.py        /api/agent/run、/api/agent/run/stream、/api/agent/tools
├── workflows/                 ★ 业务编排都在这里
│   ├── router_agent.py        ★ 路由入口（先读这个）
│   ├── knowledge_qa_agent.py  ★ 主链路（再读这个）
│   ├── retrieval_agent.py       检索子能力（被问答 Agent 调用）
│   ├── reasoning_agent.py       推理
│   ├── chitchat_agent.py        闲聊
│   ├── admin_copilot_agent.py   管理助手
│   ├── ops_agent.py             运营分析（被管理助手调用）
│   └── inspection_agent.py      知识巡检
├── intent/classifier.py       意图分类器
├── tools/
│   ├── registry.py            ★ 工具注册中心（单例 + 超时 + 重试）
│   ├── base.py                  Tool 基类与 Schema 定义
│   └── *.py                     8 个工具实现
├── core/
│   ├── events.py              ★ 事件总线（EventBus 单例 + 事件类型）
│   ├── run_recorder.py          轨迹落库（agent_run / agent_step）
│   ├── run_context.py           run_id 的 contextvars 上下文
│   ├── retrieval_evaluator.py   充分性 + 答案质量评估
│   ├── faithfulness_judge.py    忠实度评估（LLM-as-judge）
│   ├── vector_store.py          向量库封装（Milvus / FAISS）
│   ├── llm.py                   LLM 调用封装
│   ├── redis_client.py          会话记忆
│   └── mysql_client.py          数据库访问（含未启用的 UserMemoryClient）
└── tests/                     pytest 回归（54 项）
```

**新手最该先读的 4 个文件**：`workflows/router_agent.py` → `workflows/knowledge_qa_agent.py` →
`tools/registry.py` → `core/events.py`。读完这四个，主流程就通了。

---

## 第 2 章 学习路线

### 2.1 三个阶段（配套验收标准）

| 阶段 | 目标 | 时间 | 验收（做不到就别往下走）|
|---|---|---|---|
| 一、跑通 | 知道用户看到的是什么 | 1-2 天 | 能提问得到答案；能在管理端看到这条请求的轨迹 |
| 二、读通 | 知道代码怎么走 | 3-5 天 | 不看代码能画出 1.3 那张数据流图；能指出每条轨迹对应哪行代码 |
| 三、改造 | 能动手加东西 | 3-5 天 | 能加一条日志、改一个参数、加一个新工具并跑通 |

### 2.2 精读计划（从 workflows 入手，不要从 core 开始）

```
Day 1  跑通项目 + 管理端点一遍
       → README 快速启动；登录用户端/管理端各问一个问题

Day 2  workflows/router_agent.py（295 行）
       重点：TaskType 枚举、route() 的五个分支、classify_complexity() 的三档判定
       问自己：为什么"对比/分析"类问题要升级到 ReasoningAgent？

Day 3  workflows/knowledge_qa_agent.py（535 行）
       重点：ask() 的整体骨架 → _ask_l1 → _ask_l2
       问自己：L2 比 L1 多做了哪两件事？为什么值得多花时间？

Day 4  workflows/retrieval_agent.py + tools/knowledge_search.py
       重点：改写 → 检索 → 重排 → 引用标注，四个动作各自在哪
       问自己：检索分数为什么要转成余弦相似度？

Day 5  tools/registry.py + tools/base.py
       重点：单例、validate_input、ThreadPoolExecutor 超时、重试与退避、执行追踪

Day 6  core/events.py + core/run_recorder.py + core/run_context.py
       重点：事件怎么发、run_id 怎么传、两张表怎么落

Day 7  core/retrieval_evaluator.py + core/faithfulness_judge.py
       重点：三层评估各判什么、为什么忠实度要异步
```

### 2.3 前置知识清单

**必须掌握（不学看不懂代码）**

```
Java 基础
├── 面向对象（接口、继承）
├── 集合框架（List / Map）
└── Spring Boot 基础（Controller、Service、依赖注入）

Python 基础
├── 语法（函数、类、装饰器）
├── 类型注解（typing）
├── dataclass
└── 异步基础（async/await，了解即可）

数据库基础
├── MySQL 增删改查
├── Redis 基本概念（key / TTL / List）
└── 知道向量数据库是什么（不需要会用）
```

**建议掌握（学了更好理解）**

```
设计模式
├── 单例（ToolRegistry、EventBus 都用了双检锁单例）
├── 观察者（EventBus 订阅/发布）
├── 注册器（工具注册）
└── 声明式组装（ToolSchema + ToolMetadata 定义一个工具）

AI 基础
├── LLM / Prompt 基础
├── RAG 是什么
├── Embedding 是什么
└── Agent = LLM + Tools + Memory + 编排
```

**不需要掌握**

```
├── Milvus（默认走 FAISS，零依赖）
├── Docker 部署（学习阶段本地跑就够）
├── React（只做展示，不影响理解 Agent）
└── LangChain 内部实现（用它的组件，但边界见第 4 章）
```

---

## 第 3 章 核心代码解读

### 3.1 RouterAgent —— 任务分流（295 行）

它是整个 AI 服务的入口。职责只有两件：**判断该谁处理**，**把请求交出去**。

```python
# workflows/router_agent.py（结构简化，非逐行）

class TaskType(Enum):
    CHITCHAT = "chitchat"
    KNOWLEDGE_QA = "knowledge_qa"
    ADMIN_COPILOT = "admin_copilot"
    KNOWLEDGE_INSPECTION = "knowledge_inspection"
    REASONING = "reasoning"
    UNKNOWN = "unknown"


class RouterAgent:
    def route(self, input_text, conversation_id=None, user_id=None,
              context="", is_admin=False, **kwargs):
        task_type = self.classify_task(input_text, is_admin)   # ① 判类型
        self._publish_intent_event(task_type, input_text)      # ② 发轨迹事件

        if task_type == TaskType.CHITCHAT:
            return self.chitchat_agent.chat(...)
        elif task_type == TaskType.KNOWLEDGE_QA:
            return self.knowledge_qa_agent.ask(...)
        elif task_type == TaskType.ADMIN_COPILOT:
            return self.admin_copilot_agent.handle(...)
        elif task_type == TaskType.KNOWLEDGE_INSPECTION:
            inspection_type = self._parse_inspection_type(input_text)
            return self.inspection_agent.inspect(...)
        elif task_type == TaskType.REASONING:
            return self.reasoning_agent.reason(...)
        else:
            return self.knowledge_qa_agent.ask(...)            # ③ 兜底
```

`classify_task()` 内部是两层判断：

```python
result = self.classifier.classify(input_text, is_admin)   # LLM 优先 + 关键词兜底

intent_to_task = {
    IntentType.CHITCHAT: TaskType.CHITCHAT,
    IntentType.KNOWLEDGE_QA: TaskType.KNOWLEDGE_QA,
    IntentType.ADMIN_OPERATION: TaskType.ADMIN_COPILOT,
    IntentType.KNOWLEDGE_INSPECTION: TaskType.KNOWLEDGE_INSPECTION,
    IntentType.IDENTITY_QUERY: TaskType.CHITCHAT,          # "你是谁" 归闲聊
    IntentType.UNKNOWN: TaskType.KNOWLEDGE_QA,             # 兜底走问答
}
task_type = intent_to_task.get(result.intent, TaskType.KNOWLEDGE_QA)

# 只有知识问答会再判一次复杂度，可能升级成推理
if task_type == TaskType.KNOWLEDGE_QA:
    if self.classify_complexity(input_text) == "complex":
        return TaskType.REASONING
```

复杂度判定（**注意：纯规则，不调 LLM**）：

```python
def classify_complexity(self, input_text) -> str:
    l3 = ["对比", "比较", "优缺点", "区别", "异同",
          "分析", "总结", "归纳", "评估", "权衡"]
    if any(ind in input_text for ind in l3) and len(input_text) > 15:
        return "complex"        # → L3，升级到 ReasoningAgent

    l2 = ["它", "这个", "那个", "上面", "之前", "刚才"]
    if any(ind in input_text for ind in l2) or len(input_text.strip()) < 10:
        return "medium"         # → L2，改写 + 重排

    return "simple"             # → L1，直接检索生成
```

**要理解的三个设计决策**

1. **为什么路由层这么薄？** 路由只做分类和转发，不含业务逻辑。新增一个 Agent 只需加一个 `TaskType` + 一个分支。
2. **为什么意图用 LLM、复杂度用规则？** 意图是开放语义（"帮我看看知识库有没有重复的"），规则写不全；复杂度只是粗筛，用规则零成本、零延迟，误判的代价也只是链路选长了一点。
3. **`RetrievalAgent` 和 `OpsAgent` 为什么不在分支里？** 它们不是"被路由的 Agent"，而是子能力：知识问答内部委托检索，管理助手内部委托运营分析。**路由目标是 5 个，不是 7 个**——这一点很容易说错。

**面试要点**
- 5 类任务怎么分？→ 6 种意图映射到 5 类，身份询问归闲聊、未知走问答兜底
- 三级链路谁判的？→ `classify_complexity()`，关键词 + 长度，不调 LLM
- 加一个 Agent 要动哪里？→ `TaskType` + `intent_to_task` + `route()` 分支 + 新 workflow 类

### 3.2 KnowledgeQAAgent —— 一条完整链路（535 行）

这是主链路，读懂它就等于读懂整个 RAG 流程。入口 `ask()` 的结构：

```python
def ask(self, question, conversation_id=None, user_id=None, context="", **kwargs):
    # 1. 读会话记忆
    conversation_history = ""
    if conversation_id and tool_registry.has_tool("conversation_memory_read"):
        history = tool_registry.invoke_tool(
            "conversation_memory_read",
            {"conversation_id": conversation_id, "limit": 10}
        )
        if history.get("messages"):
            conversation_history = self._format_history(history["messages"])

    # 2. 合并上下文（检索上下文 + 历史对话）
    full_context = context
    if conversation_history:
        full_context = f"{context}\n\n{conversation_history}" if context else conversation_history

    # 3. 判复杂度，选链路
    complexity = self.router.classify_complexity(question)
    if complexity == "medium":
        return self._ask_l2(question, conversation_id, full_context)
    else:
        return self._ask_l1(question, conversation_id, full_context)
```

两个链路的分工：

| | L1 `_ask_l1` | L2 `_ask_l2` |
|---|---|---|
| 改写 | ✗ | ✓ `question_rewrite` |
| 检索 | ✓ 直接调 `vector_store.search(k=5, use_rerank=False)` | ✓ 经 `RetrievalAgent`（内部直接 `tool.execute()`）|
| 重排 | ✗ | ✓ `rerank` |
| 评估 | 充分性 + 答案质量 + 忠实度（异步）| 同左 |
| 零命中 | 联网补救 → 仍无则通用知识兜底 | 同左 |
| 适用 | 短、直接的事实性问题 | 含指代、信息不足的问题 |

**L1 的真实执行顺序**（`_ask_l1`，容易被想当然写错）：

```
vector_store.search(k=5, similarity_threshold=0.45, use_rerank=False)
  ↓
evaluate_retrieval()          充分性判断（L1 也有）
  ↓
零命中？→ WEB_SEARCH_MODE=tool   → _try_web_search()（能拿来源）
        → WEB_SEARCH_MODE=bailian → enable_search=True 交给模型（无结构化来源）
  ↓
仍无资料？→ 走通用知识兜底回答（不冷拒绝，保证任何路径都有完整轨迹）
  ↓
get_answer() 生成 + _build_sources() 引用
  ↓
evaluate_answer()             答案质量（L1 也有，不是 L2 专属）
trigger_faithfulness_judge()  daemon 线程，不阻塞
_save_to_memory()             写回记忆
```

两个容易踩的细节：
- **零命中不是拒答**：先尝试联网补救，还拿不到就用通用知识回答，并在答案前加降级说明。两种降级文案是不同的——联网搜索说"来自联网搜索"，只有知识库且不充分才说"知识库内容有限"。
- **评估顺序有讲究**：先评估再写记忆，保证轨迹时序是"答案生成 → 记忆写入"。

L2 的核心在 `RetrievalAgent.retrieve()`（`workflows/retrieval_agent.py`）：

```python
def retrieve(self, query, conversation_context="", use_rewrite=False,
             use_rerank=False, top_k=5, similarity_threshold=0.45):
    # ① 改写（仅 use_rewrite=True 时执行）
    if use_rewrite:
        rewritten_query = question_rewrite_tool.execute({...})
        publish_step("question_rewritten", ...)

    # ② 检索：单路向量检索；要重排时先多取一些候选
    retrieved_docs = self._retrieve_documents(
        rewritten_query, k=top_k * 3 if use_rerank else top_k,
        similarity_threshold=similarity_threshold)
    publish_step("retrieval_completed", ...)

    # ③ 重排（仅 use_rerank=True 时执行）
    if use_rerank and retrieved_docs:
        reranked = self._rerank_documents(...)
        publish_step("rerank_completed", ...)

    # ④ 引用整合（注意：此时答案还没生成，只能拿到空串）
    citation_result = self.citation_integrator.integrate(answer="", documents=...)
```

**这里有个真实的坑，值得单独说**：

`retrieve()` 是**通用能力**，默认 `use_rewrite=False, use_rerank=False`，只有 L2 显式传了
`use_rewrite=True, use_rerank=True`。所以判断"某条链路会不会改写/重排"，要看**调用处传了什么**，
不能只看 `retrieve` 的函数签名。

另一个坑是第 ④ 步：引用整合器在**答案生成之前**就被调用了，那时 `answer=""`，无法做真实的
"答案片段 ↔ 文档"匹配；而且它的 `min_citation_score` 作用在 rerank 关键词分数上（语义和余弦相似度不同），
会把来源错误地过滤成 1 篇。所以 L2 最终**不用** `retrieval_result.citations`，而是用 `_build_sources(docs)`
按文档去重、展示全部命中文档。这正是踩坑笔记 §9 里记录的那条链。

写入记忆在回答结束之后：

```python
def _save_to_memory(self, conversation_id, question, answer):
    if not conversation_id or not tool_registry.has_tool("conversation_memory_write"):
        return
    tool_registry.invoke_tool("conversation_memory_write",
                              {"conversation_id": conversation_id,
                               "role": "user", "content": question})
    tool_registry.invoke_tool("conversation_memory_write",
                              {"conversation_id": conversation_id,
                               "role": "assistant", "content": answer})
```

**要理解的三个设计决策**

1. **为什么先读记忆再判断复杂度？** 因为历史对话本身会影响上下文完整性，且读记忆是 10ms 级操作，成本可忽略。
2. **为什么 L1 不重排？** 重排要跑 cross-encoder，是链路里最慢的一环之一。简单问题靠向量检索 + 阈值就够，不值得付这个成本。
3. **写记忆为什么放在最后？** 失败不能影响已经生成的答案；写失败只 `logger.warning`，是"尽力而为"。

**面试要点**
- 一条完整链路做了哪几件事？→ 读记忆 → 改写 → 检索 → 重排 → 充分性判断 → 生成 → 忠实度异步评判 → 写记忆
- 充分性不足怎么办？→ 降级并在回答前标注"仅供参考"，同时把评估信息带进返回体
- 记忆什么时候写？→ 生成之后，且失败不阻断

### 3.3 ToolRegistry —— 工具注册与超时重试（143 行）

```python
class ToolRegistry:
    _instance = None
    _lock = threading.Lock()

    def __new__(cls):                       # ① 双检锁单例
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._tools = {}
        return cls._instance

    def register_tool(self, tool):          # ② 注册
        self._tools[tool.name] = tool

    def invoke_tool(self, tool_name, parameters, run_id=None):   # ③ 调用
        tool = self.get_tool(tool_name)
        if not tool:
            raise ValueError(f"Tool not found: {tool_name}")
        if not tool.validate_input(parameters):                  # 参数校验
            raise ValueError(f"Invalid input parameters for tool: {tool_name}")

        tool_call_id = tool_execution_tracker.start_tool_call(...)  # ④ 追踪开始

        retries, max_retries = 0, tool.metadata.max_retries
        while retries <= max_retries:
            try:
                with ThreadPoolExecutor(max_workers=1) as executor:  # ⑤ 强制超时
                    future = executor.submit(tool.execute, parameters)
                    result = future.result(timeout=tool.metadata.timeout_ms / 1000)
                tool_execution_tracker.complete_tool_call(tool_call_id, result, ...)
                return result
            except Exception as e:
                retries += 1
                if retries > max_retries:
                    tool_execution_tracker.fail_tool_call(tool_call_id, str(e), ...)
                    raise
                time.sleep(0.5)                                   # ⑥ 固定退避
```

**要理解的三个设计决策**

1. **为什么用线程池做超时？** `tool.execute()` 是同步阻塞调用，Python 没有通用的同步超时机制；丢进线程池 `future.result(timeout=...)` 是最直接的办法。代价是卡死的线程收不回来，所以超时值要设得合理。
2. **重试为什么是固定 0.5 秒？** 简单够用。LLM 限流通常是瞬时抖动，固定退避已经能救回大部分情况，比引入指数退避 + 抖动的复杂度更划算。
3. **执行追踪和事件总线什么关系？** `tool_execution_tracker` 记的是**工具级**明细，`EventBus` 记的是**步骤级**轨迹，两者在管理端是不同视图。

**面试要点**
- 为什么单例？→ 全局只需要一个注册表，避免多个实例导致工具注册到不同字典
- 超时怎么做的？→ `ThreadPoolExecutor` + `future.result(timeout=...)`
- 执行追踪有什么用？→ 能看到每次工具调用的入参、出参、耗时、状态

**一个必须知道的边界**（容易说大）：

`ToolRegistry` 的超时 / 重试 / 追踪，**只对经过 `invoke_tool()` 的调用生效**。而 `RetrievalAgent`
内部是直接 `self.question_rewrite_tool.execute(...)`、`self.knowledge_search_tool.execute(...)`，
**绕过了注册中心**——所以 L2 的改写、检索、重排这三步没有 registry 的超时保护，靠工具自身
（如 `web_search` 里的 `requests` timeout）兜底。

这个设计有意无意地造成了不一致：走 registry 的调用有统一保护，直连的没有。如果被问到
"所有工具调用都统一治理了吗"，准确回答是**"注册中心提供了统一治理能力，但并非所有调用点都走它"**。
想改成全走 registry 也不难，只是要重新评估延迟开销。

### 3.4 记忆与上下文（真实实现）

真正在链路上跑的记忆是四层（第四层 2026-11-14 接通只读）：

```
① 请求上下文（run_id）   core/run_context.py    contextvars，单次请求内隔离
② 会话记忆（Redis）      core/redis_client.py   conversation:{id}:messages，24h TTL
③ 知识记忆（向量库）     core/vector_store.py   Milvus 优先，FAISS 兜底
④ 用户记忆（MySQL）      core/mysql_client.py   user_memory 表 + 问答链路只读注入（2026-11-14）
```

**Java 侧还有一条轨，容易被忽略**：

Java 的 `ConversationContextService` 从 MySQL 维护 `conversation_context`（摘要 / 重要性 / 滑动窗口），
每次落消息时更新，每 5 条触发一次摘要生成（关键词提取，**不调 LLM**），每 10 条写一次长期记忆。
它是**跨会话永久保存**的，而 Redis 只有 24 小时。

两条轨的分工（2026-11-14 调整后）：

| 侧 | 传给模型的内容 | 为什么 |
|---|---|---|
| Java | 更早对话的**摘要**（有则传，无则空串） | 覆盖 24 小时以前的历史 |
| Python | 最近 **5 条原文**（Redis） | 保证指代衔接自然 |

> 之前两边各传最近 10 条原文，同一批对话在 Prompt 里出现两次（长度翻倍）。
> 修复过程与实测数据见 [docs/记忆改造计划.md](docs/记忆改造计划.md) 阶段 1。

几个能讲出细节的点：

- **没有 `AgentState` 了**。编排骨架删除后，步骤状态只是各 workflow 方法里的局部变量；跨模块传的只有
  `run_id`，靠 `set_run_id()` / `get_run_id()`，contextvars 天然按请求隔离，并发不串。
- **会话记忆只有 3 个 Agent 在用**：`chitchat_agent`、`knowledge_qa_agent`、`reasoning_agent` 调
  `conversation_memory_read` / `conversation_memory_write`；`ops_agent`、`inspection_agent`、
  `retrieval_agent`、`admin_copilot_agent` 不碰记忆。
- **压缩是真做了的**：消息条数超过 10（约 5 轮）触发 LLM 摘要，保留最近 5 条原文（约 2-3 轮），
  摘要存 `conversation:{id}:summary`，TTL 1 小时，过期后重新生成。
- **落库的长记录不喂给模型**：`agent_run` / `agent_step` / `tool_call` 是执行轨迹，`qa_log` 是问答日志
  （带 like/dislike），都只服务管理端与评估，不参与推理。
- **用户记忆只做了"读"**：2026-11-14 建了 `user_memory` 表，问答链路开头读一次，拼成
  `[用户长期偏好] {...}` 注入上下文（最多 10 条 / 500 字符）。但**没有自动写入**——不去写就一直是空表。
  准确说法是"预留并接通了用户记忆的读取通路"，不是"已实现个性化记忆"。
- **别把设计当实现**：`conversation_context` 表在 Java 侧是**真实读写**的（摘要 / 窗口 / 重要性），
  但 Python 链路从不访问它，`embedding` 字段也始终为空。可以说"Java 有会话摘要、Python 有原文记忆，
  两者分工"。

**面试要点**
- 为什么分层？→ 生命周期与读写成本的权衡：请求内 contextvars → 24 小时 Redis → 持久化向量库
- 对话压缩怎么做的？→ 消息超 10 条 LLM 摘要 + 保留最近 5 条原文 + 摘要缓存 1 小时
- 记忆存在哪、谁在用？→ 必须能说出具体文件和具体 Agent
- 用户记忆做到哪一步？→ 表 + 只读注入已通，写入与抽取未做（`UserMemoryClient.update_user_memory` 目前无人调用）

### 3.5 EventBus 与轨迹落库

`core/events.py` 是三件东西：事件类型常量、事件类、事件总线单例。

```python
class EventType:
    RUN_STARTED = "run_started"
    RUN_COMPLETED = "run_completed"
    STEP_STARTED = "step_started"
    STEP_COMPLETED = "step_completed"
    STEP_FAILED = "step_failed"
    STEP_SKIPPED = "step_skipped"
    TOOL_CALL_COMPLETED = "tool_call_completed"
    INTENT_RECOGNIZED = "intent_recognized"
    QUESTION_REWRITTEN = "question_rewritten"
    RETRIEVAL_COMPLETED = "retrieval_completed"
    SUFFICIENCY_EVALUATED = "sufficiency_evaluated"
    ANSWER_GENERATED = "answer_generated"
    MEMORY_WRITTEN = "memory_written"
    ...   # 共 21 个
```

事件类有：`RunStartedEvent` / `RunCompletedEvent` / `RunFailedEvent`、`StepStartedEvent` /
`StepCompletedEvent` / `StepFailedEvent`、`ToolCallCompletedEvent` / `ToolCallFailedEvent`、
`AnswerGeneratedEvent`。

总线本身是单例，能力很朴素：

```python
class EventBus:
    def __new__(cls): ...                      # 单例
    def subscribe(self, event_type, handler):  # 按类型订阅
    def subscribe_global(self, handler):       # 订阅全部
    def publish(self, event): ...              # 发布（异常不外抛）
    def clear(self): ...
```

业务侧不直接调 `EventBus`，而是调 `core/retrieval_evaluator.py` 里的 `publish_step()` 助手：

```python
publish_step("memory_read", {"message_count": 6, "compressed": True}, duration_ms=12.3)
```

`publish_step` 内部取 `get_run_id()`，没有 run_id 就直接返回（脚本/定时任务场景自动静默）。

落库在 `core/run_recorder.py`：订阅事件 → 攒成一次 run → 写 `agent_run`（一次请求一行）和
`agent_step`（每步一行）。

**要理解的三个设计决策**

1. **为什么要事件总线，不直接写库？** 解耦：业务只负责"发生了什么"，落库、统计、告警可以各自订阅。
   加一个消费者不用改业务代码。
2. **为什么埋点不能抛异常？** 可观测性是旁路，不能因为它挂了导致用户拿不到答案。
3. **`run_id` 为什么不作为参数层层传递？** 链路深度有 5-6 层，改签名成本高；`contextvars` 按请求隔离，
   且不污染函数签名。

**面试要点**
- 一次请求怎么串起来？→ `run_id` 在入口生成，`contextvars` 承载，事件里带上
- 轨迹看什么？→ 每步耗时、入参出参、失败原因；管理端是瀑布图 + 键值对
- 埋点会不会拖慢业务？→ 发布是内存操作，落库是旁路，异常被吞掉

---

## 第 4 章 和 LangGraph 的对照

> **面试策略**：不要主动讲"我和 LangGraph 的区别"，显得刻意。先讲清自己的架构；面试官问到框架时再展开。

### 4.1 概念上的对应关系

**不是"我用 LangGraph 的思路实现了它"，而是"我手写了它对应位置的那一小块"**：

| LangGraph 的概念 | 本项目的实现 | 文件 |
|---|---|---|
| Router / 条件边 | `RouterAgent.route()`：意图 → `TaskType`，复杂度触发升级 | `workflows/router_agent.py` · `intent/classifier.py` |
| Tool Node | `ToolRegistry.invoke_tool()`：统一超时 / 重试 / 追踪 | `tools/registry.py` |
| Checkpoint / Trace | `EventBus` + `run_id` + `RunRecorder` | `core/events.py` · `core/run_context.py` · `core/run_recorder.py` |
| 评估节点 | 充分性 / 答案质量 / 忠实度 | `core/retrieval_evaluator.py` · `core/faithfulness_judge.py` |
| Multi-Agent | 5 个路由目标 + 2 个子能力 Agent | `workflows/*` |

**没有对应的**：状态图、检查点回放、动态步骤规划、子图嵌套。本项目是"路由一次 + 固定流程"，
这也意味着**不要说"我实现了状态机"**。

### 4.2 技术边界（重要）

**"自研"指的是编排层，不是整个技术栈**：

| 层次 | 实现方式 | 涉及文件 |
|---|---|---|
| **Agent 编排层**<br>意图路由 / 流程控制 / 工具注册 / 事件总线 / 评估层 / 全链路留痕 | ✅ **自研** | `intent/classifier.py` · `workflows/*` · `tools/registry.py` · `core/events.py` · `core/run_recorder.py` · `core/retrieval_evaluator.py` · `core/faithfulness_judge.py` |
| **基础设施层**<br>LLM 调用 / Prompt 模板 / 输出解析 / 文档加载 / 文本切分 / 向量库封装 | 📦 **LangChain 组件** | `core/llm.py` · `core/parser.py` · `core/text_splitter.py` · `core/vector_store.py` |

**为什么这样分工**：编排逻辑是理解 Agent 的关键，也是定制最集中的地方（按复杂度切链路、自定义阈值、
失败分级留痕），值得自己写；基础设施已有成熟标准，重复造轮子没有收益。

**三个必须避开的说法**：

| ❌ 不要这样说 | 为什么 |
|---|---|
| "基于 LangGraph 搭建" | 全项目 `langgraph` 引用次数为 0，`requirements.txt` 里是传递依赖 |
| "全部手写 / 不依赖任何框架" | LLM 调用、Prompt、解析、文档加载、切分、向量库封装都是 LangChain 的 |
| "前端 TypeScript" | 纯 JavaScript，仓库没有 `tsconfig.json` |

**准确说法**：

> 我依赖 LangChain 的**基础组件**；但 Agent 的**编排层**（路由、流程控制、工具注册、事件总线）
> 是自己实现的，没有用 LangGraph。

### 4.3 迁移能力

学完这个项目，你真正获得的是"看得懂别人怎么实现"，而不是"会调某个框架的 API"：

```
理解路由与分发   → 任何需要分类分发的场景
理解固定流程编排 → 任何需要可控链路的场景
理解工具注册     → 任何需要能力扩展的场景
理解事件驱动     → 任何需要追踪监控的场景
理解记忆分层     → 任何需要上下文管理的场景
```

遇到新框架时，看源码就能对上号——因为每个框架都是这几块的重新组合。

---

## 第 5 章 验证与调试

### 5.1 看管理端轨迹（最直观）

```
1. 启动项目（README 快速启动）
2. 用户端问："什么是数据库索引"
3. 管理端 → Agent 执行记录
4. 能看到：意图分类 → 记忆读取 → 问题改写 → 知识检索 → 重排序
          → 结果评估 → 答案生成 → 忠实度评估 → 记忆写入
   每一步都有耗时、入参、出参
```

**判断链路类型**：看到"问题改写 + 重排序"就是 L2；只有"知识检索 + 答案生成"就是 L1；闲聊只有 3 步。

### 5.2 看服务日志

Python 服务的日志按 Agent 打标签，真实输出形如：

```
[RouterAgent] Routing to: knowledge_qa for input: 什么是数据库索引...
[KnowledgeQAAgent] Processing question: 什么是数据库索引...
[KnowledgeQAAgent] Loaded 6 messages from memory (compressed: False)
[KnowledgeQAAgent] Complexity: simple
[RetrievalAgent] Starting retrieval for: 什么是数据库索引
[RetrievalAgent] Retrieval completed: 5 docs
[ToolRegistry] Tool knowledge_search executed in 156.32ms
[KnowledgeQAAgent] Saved conversation to memory
```

对照着看：`Routing to` 告诉你路由结果，`Complexity` 告诉你走了哪条链路，
`Loaded N messages` 告诉你记忆读了几条。

### 5.3 跑测试（最严谨）

```bash
cd python-service
venv/Scripts/python -m pytest -q          # Windows
# python -m pytest -q                      # macOS / Linux
```

当前基线：**54 项通过**。改动后用这个确认没破坏既有行为。

测试关注点举例（`tests/` 下）：

```python
def test_tool_registry():
    from tools.registry import tool_registry
    assert tool_registry.has_tool("knowledge_search")
    assert tool_registry.has_tool("conversation_memory_read")
    result = tool_registry.invoke_tool(
        "knowledge_search", {"query": "数据库索引", "top_k": 3}
    )
    assert "chunks" in result
```

### 5.4 直接打接口

```bash
curl -X POST http://localhost:8000/api/agent/run \
  -H "Content-Type: application/json" \
  -d '{"input": "什么是数据库索引", "conversation_id": "test-001", "user_id": "test-user"}'
```

返回里包含 `run_id`、`trace_id`、`status`、`answer`、`sources`、`task_type`。
注意 **`steps` 恒为空数组**——轨迹已经落库了，不在响应体里返回；要看去管理端，或用 Java 侧的
`/api/agent-run/**` 只读接口按 `run_id` 查。

其他可用接口：

```
POST /api/ask              非流式问答，返回完整 JSON
POST /api/ask/stream       SSE 流式 —— 前端实际走的就是这一条
GET  /api/agent/tools      工具清单与元数据
GET  /api/agent/stats      任务类型统计（关键词分类器调试用）
GET  /api/health           健康检查
```

> **别搞混两个入口**：Java 的 `ChatStreamService` 调的是 Python 的 `/api/ask/stream`；
> `/api/agent/run` 是给手动触发/调试用的，Java 侧目前没有接它。

---

## 第 6 章 动手实践

三个练习按难度递增，每个都能独立完成、不破坏既有功能。

### 6.1 加日志：看清执行顺序

**改哪**：`workflows/router_agent.py` 的 `route()` 和 `workflows/knowledge_qa_agent.py` 的 `ask()`。

```python
# router_agent.py route() 开头
logger.info(f"[RouterAgent] input={input_text[:30]}, "
            f"complexity={self.classify_complexity(input_text)}")
```

```python
# knowledge_qa_agent.py ask() 里读完记忆之后
logger.info(f"[KnowledgeQAAgent] history_msgs={len(history.get('messages', []))}, "
            f"compressed={history.get('compressed')}")
```

**验证**：重启服务（不加 `--reload`），问一个问题，终端能看到这两行。
**收获**：理解 `classify_complexity` 在路由层和问答层各被调用一次。

### 6.2 改参数：观察参数的影响

| 改什么 | 文件 | 位置 | 观察点 |
|---|---|---|---|
| 检索条数（L2 链路）| `tools/knowledge_search.py` | `top_k`（默认 3）| 改成 1 看是否漏召；改成 10 看噪声是否变多 |
| 检索条数（L1 链路）| `workflows/knowledge_qa_agent.py` 的 `_ask_l1` | 硬编码 `k=5` | **L1 不走工具**，改工具参数对它无效 |
| 相似度阈值 | `workflows/knowledge_qa_agent.py` 的 `_ask_l1` 传参 | 显式传 `similarity_threshold=0.45` | 改 `vector_store.py` 的默认值对 L1 无效（被显式传参覆盖）|
| 记忆压缩阈值 | `tools/memory_read.py` | `COMPRESS_THRESHOLD`（10）、`KEEP_RECENT`（5）| 改小后连问几轮，看摘要是否提前出现 |
| LLM 温度 | `core/llm.py` | `temperature`（默认 0.7）| 改 0.1 更稳定，改 1.0 更发散 |

**注意**：改阈值前先看 `retrieval_evaluator.py` 里的低分线（0.4）。两者有联动——低分线应该低于检索硬阈值，
否则"通过检索的文档"会被判定为"低分"，直接导致充分性评估误报。

### 6.3 加工具：理解抽象与注册

**目标**：加一个 `weather` 工具，让它能被注册、被调用、被追踪。

**第 1 步**：新建 `python-service/tools/weather_tool.py`

```python
from typing import Dict, Any
from tools.base import Tool, ToolSchema, SchemaProperty, ToolMetadata


class WeatherTool(Tool):
    def __init__(self):
        super().__init__(
            name="weather",
            description="查询城市天气",
            input_schema=ToolSchema(
                properties={
                    "city": SchemaProperty(type="string", description="城市名", required=True),
                },
                type="object",
            ),
            output_schema=ToolSchema(
                properties={
                    "city": SchemaProperty(type="string", description="城市", required=True),
                    "weather": SchemaProperty(type="string", description="天气", required=True),
                    "temperature": SchemaProperty(type="string", description="温度", required=False),
                },
                type="object",
            ),
            metadata=ToolMetadata(timeout_ms=5000, max_retries=1,
                                  permission="user", description="查询天气"),
        )

    def execute(self, parameters: Dict[str, Any]) -> Dict[str, Any]:
        city = parameters.get("city", "北京")
        return {"city": city, "weather": "晴", "temperature": "25C"}
```

**第 2 步**：在 `tools/__init__.py` 里注册（**注意不是 `registry.py`**）

```python
from tools.weather_tool import WeatherTool

def register_all_tools():
    tools = [
        ...,
        WeatherTool(),
    ]
```

**第 3 步**：验证

```bash
curl http://localhost:8000/api/agent/tools    # 能看到 weather
```

**第 4 步**（可选）：在 `knowledge_qa_agent.py` 的某个链路里调用它

```python
result = tool_registry.invoke_tool("weather", {"city": "北京"})
```

**收获**：理解"继承基类 → 声明 Schema → 注册 → 调用"这条链。通过 `tool_registry.invoke_tool()`
调用时，超时 / 重试 / 追踪由注册中心统一加，工具本身不用关心（但直连 `tool.execute()` 就没有这层保护，
见 3.3 末尾的边界说明）。

> 想让 Agent 自动决定用这个工具？本项目**没有**工具自动选择（no function calling / ReAct 循环），
> 需要你在链路里显式调用。这是刻意的取舍：可控性优先。

---

## 第 7 章 面试技术问答

> 简历表述、技术边界、追问答法请看 [简历与面试.md](./简历与面试.md)。本章只讲技术链路怎么答。

### Q1：Agent 的执行流程是怎样的

```
以知识问答为例：
1. 请求进 api/routes.py，生成 run_id 并写入 contextvars
2. RouterAgent.classify_task() 判任务类型（LLM 意图分类 + 关键词兜底）
   知识问答还会再判复杂度，complex 则升级为 ReasoningAgent
3. 进 KnowledgeQAAgent.ask()：读会话记忆 → 合并上下文
4. 按复杂度走 _ask_l1（检索直出）或 _ask_l2（改写 → 检索 → 重排）
5. 充分性评估：资料不够则降级并标注"仅供参考"
6. LLM 生成答案 + 引用标注
7. 后台线程异步判忠实度（不阻塞响应）
8. 写回会话记忆
9. 每步通过 publish_step 发事件，RunRecorder 落 agent_run / agent_step
```

关键决策：路由层薄、链路固定、评估旁路、追踪旁路。

### Q2：为什么不用现成的 Agent 框架

```
1. 定制点正好都在框架覆盖不到的地方
   按复杂度切三条链路、自定义充分性阈值、失败分级留痕（SKIPPED / FAILED）

2. 链路短更可控
   固定流程的延迟是确定的，出问题能定位到具体步骤

3. 基础设施没有重造
   LLM 调用、Prompt、解析、切分、向量库封装直接用 LangChain
```

### Q3：遇到过什么困难

```
1. 缓存键跨用户泄露（P0）
   缓存 key 没带 userId，A 的数据被 B 读到 → key 加入用户维度

2. 工具调用无限等待
   同步调用没有超时，一个卡住阻塞整条链路 → ThreadPoolExecutor 强制超时 + 重试

3. FAISS 的 L2 距离语义错配
   直接把 L2 距离当相似度和 0.45 比，阈值恒成立、等于没过滤
   → 检索层统一转真实余弦相似度，并按实测分布校准阈值

4. 意图识别逻辑分散
   多处各写一套 → 抽成独立 IntentClassifier，统一 LLM + 关键词兜底
```

### Q4：三级链路具体怎么切

见 3.1 的 `classify_complexity()`：L3 是"命中对比/分析类词 + 长度 > 15"，L2 是"含指代或长度 < 10"，
其余 L1。**是启发式规则，不调 LLM**，也不经过任何规划器。

### Q5：记忆是怎么做的

见 3.4。一句话版本：Redis 存会话（24h TTL，超 10 条消息触发 LLM 压缩，保留最近 5 条），
`contextvars` 存 `run_id`，只有 3 个 Agent 读写记忆，**没有用户级长期记忆**。

### Q6：可观测性怎么做的

事件总线 + `run_id` 串联。业务只 `publish`，`RunRecorder` 订阅后写 `agent_run`（一次请求）和
`agent_step`（每步）。埋点异常被吞掉，不影响业务。管理端是耗时瀑布 + 键值对。

---

## 第 8 章 踩坑与经验教训

以下是从项目 2026-03-08 至今的提交历史里提炼的真实问题。

### 安全性

```
P0 缓存键跨用户泄露
  问题：缓存键没有包含 userId，用户 A 的缓存返回给用户 B
  教训：缓存代码默认不考虑多租户，安全审查必须人工做

P0 URL 判断正则误判
  问题：正则不够严格，本地文件路径被误判为 URL
  教训：AI 生成的正则往往"够用就行"，边界情况要人审

密码明文存储
  问题：初期密码明文入库
  教训：安全功能不能"以后再加"
```

### AI 服务质量

```
闲聊返回文档引用
  问题：用户问"你好"，系统返回知识库文档引用
  教训：意图识别是 AI 系统的命脉，分类不准 = 体验灾难

AI 异常信息暴露给用户
  问题：Python 服务异常时技术细节直接返回前端
  教训：AI 生成的错误处理默认"抛异常"，你需要"友好降级"

对话上下文被错误信息污染
  问题：回答失败后错误信息留在上下文里
  教训：对话状态管理比想象中复杂，错误恢复要提前设计
```

### 架构演进

```
意图识别逻辑分散
  问题：散落在多个文件，逻辑不一致
  教训：发现重复立即抽取，否则会越复制越乱

工具执行无限等待
  问题：Tool 调 LLM 没有超时，一个卡住阻塞整个 Agent
  教训：AI 系统里"等待"最危险，所有外部调用必须有超时

步骤执行失败无重试
  问题：失败直接终止
  教训：AI 调用天然不稳定（网络 / 限流 / 模型抖动），重试是必须的
```

### Vibe Coding 的 8 条铁律

| # | 铁律 |
|---|---|
| 1 | 安全审查必须人工做 — AI 不会主动考虑缓存键隔离、SQL 注入 |
| 2 | 异常处理必须明确要求 — 默认"抛异常"，你要"友好降级" |
| 3 | 发现重复立即抽取 — AI 喜欢把同一逻辑复制到多个文件 |
| 4 | 所有外部调用必须有超时 — LLM 调用天然不稳定 |
| 5 | 记忆系统提前规划 — 比想象的复杂 |
| 6 | 外部依赖必须有降级 — Milvus 挂了不能整个系统不可用 |
| 7 | 配置文件不能硬编码 — 密钥用环境变量 |
| 8 | 意图识别是命脉 — 分类不准 = 用户体验灾难 |

---

## 附录

### A. 常见问题

**代码看不懂怎么办**

```
1. 不要从 core/ 开始，先从 workflows/router_agent.py 看
2. 跟着一次真实请求走：加打印、看日志、对照管理端轨迹
3. 画两张图：调用链（谁调谁）、数据流（数据从哪来到哪去）
```

**项目跑不起来**

```bash
java -version        # 需要 17
python --version     # 需要 3.10+
node --version       # 需要 18+
mysql -u root -p     # 检查 MySQL
redis-cli ping       # 检查 Redis，应返回 PONG
```

常见原因：`.env` 没配 `DASHSCOPE_API_KEY`、数据库没建表（`sql/init.sql` 不含建库语句）、
Python 启动加了 `--reload` 导致 CPU 打满。

**概念太多记不住**

先记住四个：Agent（谁来做）、RAG（怎么找资料）、Tool（能做什么）、Memory（记得什么）。
剩下的是细节。别背名词，跟着 `knowledge_qa_agent.py` 走一遍比背十遍有用。

### B. 学习检查清单

**阶段一：跑通**
```
✅ 能启动五个服务，问出第一个问题
✅ 能在管理端看到这条请求的轨迹
✅ 能说清核心表的职责：`agent_run` / `agent_step` 存轨迹，`conversation` / `message` 存会话
```

**阶段二：读通**
```
✅ 能背出 5 类任务分别路由到哪个 Agent
✅ 能说清 L1 和 L2 的差别
✅ 能指出一次请求的轨迹里每一步对应哪行代码
✅ 能解释 run_id 为什么用 contextvars 传
```

**阶段三：改造**
```
✅ 能加日志观察执行顺序
✅ 能改检索条数/阈值并解释效果变化
✅ 能加一个工具并让它出现在 /api/agent/tools
✅ 能讲清"为什么没上 LangGraph"而不说错边界
```

### C. 学习资源

**Java / Spring Boot**
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)
- [Spring Boot in Action](https://www.manning.com/books/spring-boot-in-action)

**Python / FastAPI**
- [Python 官方教程](https://docs.python.org/3/tutorial/)
- [FastAPI 官方文档](https://fastapi.tiangolo.com/)
- [Fluent Python](https://www.oreilly.com/library/view/fluent-python-2nd/9781492056348/)

**AI / Agent**
- [Prompt Engineering Guide](https://www.promptingguide.ai/)
- [LangChain 官方文档](https://docs.langchain.com/)
- [Lilian Weng: LLM Powered Agents](https://lilianweng.github.io/posts/2023-06-23-agent/)
- [ReAct 论文](https://arxiv.org/abs/2210.03629)

**可以对照阅读的项目**
- [Dify](https://github.com/langgenius/dify) — 生产级 Agent 平台
- [LangChain](https://github.com/langchain-ai/langchain)
- [AutoGen](https://github.com/microsoft/autogen)

**进一步阅读**
```
书籍
├──《Designing Data-Intensive Applications》
├──《Designing Machine Learning Systems》
└──《Building LLM Powered Applications》

论文
├── ReAct: Synergizing Reasoning and Acting in Language Models
├── Toolformer: Language Models Can Teach Themselves to Use Tools
└── Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks
```

---

> **配套文档**：[README.md](./README.md)（项目与启动）· [简历与面试.md](./简历与面试.md)（面试口径）·
> [踩坑笔记.md](./踩坑笔记.md)（14 个问题的完整复盘）
