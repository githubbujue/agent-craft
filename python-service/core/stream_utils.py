"""流式下发辅助工具

背景: 管理端报告、知识巡检结果由数据库查询拼装而成（链路里没有 LLM 调用），
不存在真正的 token 流。旧实现是"整段算完 → for char in answer 逐字 yield",
这属于伪流式打字动画（用户看到的进度与真实计算进度无关）。

这里改为按段落/最大长度分块下发: 保留渐进呈现能力, 但不假装在"生成"。
真正的 LLM 链路（知识问答/闲聊）走各自的 chain.stream()，不用本工具。
"""
from typing import List


def split_text_chunks(text: str, max_len: int = 120) -> List[str]:
    """把整段文本切成有意义的分块（优先按空行分段, 过长再按行/长度切）

    Args:
        text: 待下发的完整文本
        max_len: 单个分块的软上限（字符数）, 避免出现超长块导致"一次性闪现"

    Returns:
        分块列表; 空文本返回 []
    """
    if not text:
        return []

    chunks: List[str] = []
    for para in text.split("\n\n"):
        if not para.strip():
            continue
        if len(para) <= max_len:
            chunks.append(para + "\n\n")
            continue

        # 长段落: 按行聚合到 max_len 附近, 单行过长则硬切
        buf = ""
        for line in para.split("\n"):
            while len(line) > max_len:
                if buf:
                    chunks.append(buf + "\n")
                    buf = ""
                chunks.append(line[:max_len])
                line = line[max_len:]
            if buf and len(buf) + len(line) + 1 > max_len:
                chunks.append(buf + "\n")
                buf = line
            else:
                buf = f"{buf}\n{line}" if buf else line
        if buf:
            chunks.append(buf + "\n\n")

    return chunks
