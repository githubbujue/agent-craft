# AgentCraft — 智能知识库问答系统

![CI](https://github.com/githubbujue/agent-craft/actions/workflows/ci.yml/badge.svg)

> Java + Python + React 多服务架构 | 三级 RAG 链路 | 评估层 | 全链路毫秒级轨迹

## 项目介绍

AgentCraft 是一个**面向企业知识场景的前后端分离智能 Agent 系统**。上传文档自动解析入库，
用户提问后由多 Agent 协作完成「意图路由 → 会话记忆 → 知识检索 → 充分性评估 → 答案生成 →
引用标注」全流程，全程毫秒级轨迹留痕、管理端可视化。

**解决什么问题**：企业文档散落各处、检索只能靠关键词、答案给出来了却没有依据可查。
这个项目把「文档 → 可追溯答案」整条链路做通，并且**每一步都留痕** —— 出问题能精确定位到
哪一步失败、调用了哪个工具、耗时多少，而不是只看到一句"回答错了"。

**核心能力概览**

| 能力 | 说明 |
|------|------|
| **三级 RAG 链路** | 按问题复杂度动态路由：L1 简化（检索直出）/ L2 标准（改写 + 重排）/ L3 推理（分解 + 汇总）|
| **多 Agent 协作** | `RouterAgent` 意图分发至 5 个 Agent（知识问答 / 推理 / 闲聊 / 知识巡检 / 管理助手）；知识问答内部委托 `RetrievalAgent`，管理助手内部委托 `OpsAgent` |
| **评估层** | 检索充分性(规则) / 答案质量(规则，零成本) / 内容忠实度(LLM-as-judge，后台异步不阻塞主链路) |
| **全链路可观测性** | 自建事件总线埋点，按链路真实路径落 5-9 步毫秒级轨迹，管理端可视化耗时瀑布与失败定位 |
| **检索质量治理** | FAISS L2 距离 → 真实余弦相似度，阈值基于实测分布校准 |

**技术边界**（一句话版，避免误读）

> **编排层自研**（意图路由 / 流程控制 / 工具注册 / 事件总线 / 评估层 / 全链路留痕）：
> `intent/classifier.py` · `workflows/*` · `tools/registry.py` · `core/events.py` · `core/run_recorder.py` · `core/retrieval_evaluator.py` · `core/faithfulness_judge.py`
>
> **基础设施用 LangChain**（LLM 调用 / Prompt 模板 / 输出解析 / 文档加载 / 文本切分 / 向量库封装）：
> `core/llm.py` · `core/parser.py` · `core/text_splitter.py` · `core/vector_store.py`
>
> 未使用 LangGraph 等编排框架（`requirements.txt` 中的 `langgraph*` 仅为传递依赖，业务代码引用次数为 0）。
> 完整边界、不能写的表述、面试追问答法见 [简历与面试.md](简历与面试.md)。

> **历史说明**：2026-11-13 删除了从未被任何入口调用的编排骨架（`agent/orchestrator.py` / `planner.py` /
> `executor.py` / `state.py`），其中唯一被主链路使用的 `evaluate_retrieval_sufficiency()` 已迁至
> `core/retrieval_evaluator.py`，事件总线迁至 `core/events.py`。

## 核心特性

### 1. 三级 RAG 链路（按问题复杂度自动路由）

| 链路 | 触发条件（启发式判定）| 流程 |
|---|---|---|
| L1 简化 | 其余情况（占多数）| 向量检索 → 生成 |
| L2 标准 | 含指代（它 / 这个 / 上面）或长度 < 10 | 问题改写 → 检索 → 重排 → 生成 |
| L3 推理 | 命中「对比 / 分析 / 总结」等且长度 > 15 | `RouterAgent` 升级为 `ReasoningAgent`：分解 → 逐子问题推理 → 汇总 |

> 判定在 `RouterAgent.classify_complexity()`，是**关键词 + 长度的启发式规则，不调用 LLM**；
> 只有意图分类走「LLM 优先 + 关键词兜底」（`intent/classifier.py`）。
>
> 这里**不标具体耗时和占比**：耗时随所用模型波动很大（推理模型与非推理模型能差 4-5 倍），
> 链路占比也没有埋点统计，写死数字反而站不住。

### 2. 评估层（只做判断，不改数据）

- **检索充分性评估**：召回数 + 余弦分数规则判定，不充分时自动降级（回答前标注"仅供参考"）
- **答案质量评估**：规则版零成本（引用支撑度/兜底话术检测/长度合理性），随轨迹落库
- **内容忠实度评估（LLM-as-judge）**：规则版之外的第三层——后台线程异步评判答案中的事实性陈述是否有资料依据，输出 `faithfulness` 分数与无依据陈述清单。**不阻塞主链路**（用户响应耗时不受影响），可开关 + 按比例采样；rubric 与 grounded generation 对齐（已标注的"通用知识补充"不计为幻觉）
- **grounded generation**：提示词三档设计——资料全覆盖 / 部分覆盖（标注"通用知识补充"）/ 完全无关（诚实拒绝）

### 3. Agent 可观测性（自建迷你 LangSmith）

- 事件总线驱动，业务零侵入：按链路真实执行路径落 5-9 步毫秒级轨迹（闲聊仅 3 步）
  `意图分类 → 记忆读取 → 问题改写 → 知识检索 → 重排序 → 结果评估 → 答案生成 → 忠实度评估 → 记忆写入`
- `agent_run` / `agent_step` 双表落库，管理端可视化（耗时瀑布、键值对输出、失败定位）
- 遥测与主流程隔离：评估/落库异常不影响业务响应

### 4. 检索质量治理

- FAISS 未归一化 L2 距离问题治理：检索时统一计算真实余弦相似度（0-1）
- 阈值全链路校准：实测分布定界（库内 0.51-0.63 / 库外 0.25-0.35 → 检索硬阈值 0.45 / 评估低分线 0.4）
- 检索 / 重排两套分数体系分离存储，杜绝语义混用

## 架构

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

## 快速启动

**环境要求**：Java 17 · Python 3.10+ · Node 18+ · MySQL 8.x · Redis 6+

首次运行先建库并导入表结构（`init.sql` 是 mysqldump 导出，**不含 `CREATE DATABASE`**）：

```sql
CREATE DATABASE ai_knowledge_db DEFAULT CHARSET utf8mb4;
```

```bat
mysql -u root -p ai_knowledge_db < sql\init.sql
pip install -r python-service\requirements.txt
cd frontend && npm install
```

之后一键启动（MySQL / Redis / Java:8080 / Python:8000 / 前端:3000 五个窗口）：

```bat
start-all.bat
```

手动启动顺序：MySQL → Redis → `mvn spring-boot:run`(8080) →
`python -m uvicorn main:app --port 8000` → `npm run dev`(3000，Vite 已配 `/api` 代理到 8080)

配置：复制 `python-service/.env.example` 为 `.env`，填入 `DASHSCOPE_API_KEY`

访问地址：用户端 http://localhost:3000/login · 管理端 http://localhost:3000/admin/login

> **Windows 注意**：启动 Python 服务时不要加 `--reload`，watchfiles 会递归扫描 `venv`（含 torch）
> 导致 CPU 空转；`start-all.bat` 已按此方式启动。

## 技术栈

Java 17 / Spring Boot / MyBatis-Plus · Python 3.x / FastAPI / LangChain / FAISS /
React 18 / Vite · MySQL / Redis · 通义千问 / DeepSeek（百炼 OpenAI 兼容端点，改 `LLM_MODEL` 即可切换）

> 关于「哪些是自己写的、哪些用了 LangChain」，见上方 [项目介绍 - 技术边界](#项目介绍)。

## 目录结构

```
├── src/main/java/...        # Java 业务层（对话/文档/管理/鉴权/轨迹查询）
├── python-service/
│   ├── api/                 # 问答 / 流式 / 文档 / Agent 接口
│   ├── workflows/           # RouterAgent + 7 个 Agent（问答/推理/闲聊/巡检/运维/管理助手/检索）
│   ├── intent/              # 意图分类器（LLM 优先 + 关键词兜底）
│   ├── tools/               # 工具集（检索/改写/重排/引用/记忆/OCR/联网）+ 统一注册中心
│   ├── core/                # LLM / 向量库 / 事件总线 / 留痕 / 评估器 / 记忆客户端
│   ├── tests/               # pytest 回归
│   └── main.py              # FastAPI 入口
├── frontend/src/pages/      # 用户端 + 管理端页面
├── sql/init.sql             # 建表脚本
└── start-all.bat            # 一键启动
```

## 设计取舍与踩坑记录

- FAISS 原生返回未归一化 L2 距离，直接拿来做相似度阈值会完全失效 → 检索层统一转真实余弦
- 缓存"读-改-写"回源混入刚落库数据导致消息重复 → 追加前按 id 去重
- 事务内同步调 LLM 接口（长事务占连接）→ 9/12 已重构：拆分短事务 + 独立 PersistenceService（并绕开 @Transactional 自调用失效的坑）
- 完整的排查过程见《踩坑笔记.md》，链路实现见《AgentCraft学习指南.md》

## 相关文档

| 文档 | 用途 |
|------|------|
| [AgentCraft学习指南.md](AgentCraft学习指南.md) | 详细学习：概念扫盲 → 架构 → 代码解读 → 动手实践 → 面试问答 |
| [简历与面试.md](简历与面试.md) | **写简历前必读**：简历条目、亮点表述、技术边界、追问应对 |
| [踩坑笔记.md](踩坑笔记.md) | 项目迭代中的真实问题与决策依据 |
