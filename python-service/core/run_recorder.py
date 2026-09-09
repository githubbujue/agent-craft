"""Agent 执行留痕 —— 事件总线订阅者

职责:
    订阅 EventBus 全局事件, 将执行流程中的关键节点落库到
    agent_run / agent_step 两张表 (v1 遗产, 表结构已就绪)。

设计原则:
    1. 遥测失败绝不影响主流程 —— 所有落库异常只记日志, 不向外抛
    2. 每次落库使用独立短连接 —— mysql-connector 的连接非线程安全
    3. 业务零侵入 —— orchestrator/routes 只负责 publish,
       不感知本模块的存在; 以后换 Kafka/ES 只改这里

事件 -> 落库映射:
    run_started              -> INSERT agent_run (status='running')
    run_completed            -> UPDATE agent_run (status='completed', output)
    run_failed               -> UPDATE agent_run (status='failed', error_message)
    intent_recognized        -> INSERT agent_step (意图分类)
    question_rewritten       -> INSERT agent_step (问题改写)
    retrieval_completed      -> INSERT agent_step (知识检索)
    sufficiency_evaluated    -> INSERT agent_step (结果评估)
    answer_generated         -> INSERT agent_step (答案生成)
"""
import json
import uuid
import threading

import mysql.connector

from agent.events import EventBus, Event
from core.config import config

# 事件类型 -> agent_step.step_name (对人更友好)
_STEP_NAME = {
    "intent_recognized": "意图分类",
    "memory_read": "记忆读取",
    "question_rewritten": "问题改写",
    "retrieval_completed": "知识检索",
    "rerank_completed": "重排序",
    "sufficiency_evaluated": "结果评估",
    "answer_generated": "答案生成",
    "memory_write": "记忆写入",
}


class RunRecorder:
    """订阅事件总线, 将 Agent 执行事件写入 agent_run / agent_step"""

    def __init__(self):
        self._subscribed = False
        self._lock = threading.Lock()

    # ---------- 对外接口 ----------

    def start(self):
        """订阅事件总线 (幂等, 重复调用安全)"""
        with self._lock:
            if self._subscribed:
                return
            EventBus().subscribe_global(self._on_event)
            self._subscribed = True
            config.logger.info("RunRecorder: subscribed to EventBus (global)")

    # ---------- 事件分发 ----------

    def _on_event(self, event: Event):
        """全局事件处理器: 遥测失败绝不影响主流程"""
        try:
            t = event.event_type
            if t == "run_started":
                self._insert_run(event)
            elif t in ("run_completed", "run_failed"):
                self._finish_run(event)
            elif t in _STEP_NAME:
                self._insert_step(event)
            # 其余事件类型暂不落库, 需要时在这里扩展
        except Exception as e:
            config.logger.error(
                f"RunRecorder: persist event failed ({event.event_type}): {e}")

    # ---------- 落库实现 ----------

    def _connect(self):
        """独立短连接 (用完即关, 规避 mysql-connector 的线程安全问题)"""
        return mysql.connector.connect(
            host=config.DB_HOST,
            port=config.DB_PORT,
            database=config.DB_NAME,
            user=config.DB_USER,
            password=config.DB_PASSWORD,
            charset="utf8mb4",
            use_unicode=True,
        )

    def _insert_run(self, event: Event):
        """RUN_STARTED -> INSERT agent_run (status='running')"""
        data = event.data
        conn = self._connect()
        try:
            cur = conn.cursor()
            cur.execute(
                """INSERT INTO agent_run
                   (id, run_id, trace_id, conversation_id, user_id,
                    status, goal, input, start_time, created_at)
                   VALUES (%s, %s, %s, %s, %s, 'RUNNING', %s, %s, %s, %s)""",
                (
                    str(uuid.uuid4()),
                    event.run_id,
                    data.get("trace_id"),
                    data.get("conversation_id"),
                    data.get("user_id"),
                    data.get("goal"),
                    data.get("input_data"),
                    event.timestamp,
                    event.timestamp,
                ),
            )
            conn.commit()
            cur.close()
        finally:
            conn.close()

    def _finish_run(self, event: Event):
        """RUN_COMPLETED / RUN_FAILED -> UPDATE agent_run 终态"""
        data = event.data
        failed = event.event_type == "run_failed"
        conn = self._connect()
        try:
            cur = conn.cursor()
            cur.execute(
                """UPDATE agent_run
                   SET status = %s, output = %s,
                       error_message = %s, end_time = %s
                   WHERE run_id = %s""",
                (
                    "FAILED" if failed else "COMPLETED",
                    None if failed else json.dumps(
                        data.get("output"), ensure_ascii=False),
                    data.get("error") if failed else None,
                    event.timestamp,
                    event.run_id,
                ),
            )
            conn.commit()
            cur.close()
        finally:
            conn.close()

    def _insert_step(self, event: Event):
        """STEP 类事件 -> INSERT agent_step"""
        data = event.data
        output = data.get("output")
        if isinstance(output, (dict, list)):
            output = json.dumps(output, ensure_ascii=False)
        conn = self._connect()
        try:
            cur = conn.cursor()
            cur.execute(
                """INSERT INTO agent_step
                   (id, run_id, step_type, step_name, status,
                    input, output, start_time, end_time,
                    duration_ms, created_at)
                   VALUES (%s, %s, %s, %s, 'COMPLETED', %s, %s, %s, %s, %s, %s)""",
                (
                    str(uuid.uuid4()),
                    event.run_id,
                    event.event_type,
                    _STEP_NAME.get(event.event_type, event.event_type),
                    data.get("input"),
                    output,
                    event.timestamp,
                    event.timestamp,
                    data.get("duration_ms"),
                    event.timestamp,
                ),
            )
            conn.commit()
            cur.close()
        finally:
            conn.close()


# 模块级单例
run_recorder = RunRecorder()
