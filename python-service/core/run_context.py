"""请求级 run_id 上下文

场景:
    Agent 链路深处 (workflows/评估器) 需要发布留痕事件, 但 run_id 是在
    routes.py 请求入口生成的 —— 不想为传一个 ID 把整条调用链签名都改掉。

用法:
    routes.py 请求进入时 set_run_id(run_id), 链路深处 get_run_id() 取用。
    contextvars 天然按请求隔离, 并发请求互不串扰。
"""
from contextvars import ContextVar

_run_id_var: ContextVar = ContextVar("agent_run_id", default=None)


def set_run_id(run_id: str) -> None:
    """请求入口设置本次运行的 run_id"""
    _run_id_var.set(run_id)


def get_run_id():
    """链路深处获取当前 run_id; 非请求上下文(如定时任务/脚本)返回 None"""
    return _run_id_var.get()
