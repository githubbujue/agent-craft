"""重建 FAISS 索引：从 MySQL knowledge_chunk 读取全部切块，DashScope 向量化后构建本地 FAISS 索引。

用途：init.sql 只包含文本切块（MySQL），向量索引为上传时构建的本地文件，不在仓库内。
本脚本用于在导入历史数据后重建向量索引。

用法：venv\Scripts\python rebuild_index.py
"""
import time

import mysql.connector
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document

from core.config import config

DB_CONFIG = dict(
    host="localhost",
    port=3306,
    database="ai_knowledge_db",
    user="root",
    password="123456",
    use_unicode=True,
    charset="utf8mb4",
)

EMBED_BATCH = 20  # DashScope 单次请求上限 25 条，留余量


def fetch_chunks():
    conn = mysql.connector.connect(**DB_CONFIG)
    cur = conn.cursor(dictionary=True)
    cur.execute(
        """
        SELECT kc.id AS chunk_id, kc.doc_id, kc.chunk_text, kc.chunk_index,
               kc.page_number, kd.doc_name
        FROM knowledge_chunk kc
        LEFT JOIN knowledge_doc kd ON kc.doc_id = kd.id
        """
    )
    rows = cur.fetchall()
    cur.close()
    conn.close()
    return rows


def main():
    rows = [r for r in fetch_chunks() if r["chunk_text"] and r["chunk_text"].strip()]
    print(f"Fetched {len(rows)} non-empty chunks from MySQL")

    embeddings = DashScopeEmbeddings(
        model="text-embedding-v1",
        dashscope_api_key=config.DASHSCOPE_API_KEY,
    )

    def to_doc(r):
        return Document(
            page_content=r["chunk_text"],
            metadata={
                "doc_id": int(r["doc_id"]),
                "chunk_id": int(r["chunk_id"]),
                "chunk_index": int(r["chunk_index"] or 0),
                "page_number": int(r["page_number"] or 1),
                "source": r["doc_name"] or f"doc_{r['doc_id']}",
            },
        )

    docs = [to_doc(r) for r in rows]

    start = time.time()
    store = None
    for i in range(0, len(docs), EMBED_BATCH):
        batch = docs[i : i + EMBED_BATCH]
        if store is None:
            store = FAISS.from_documents(batch, embeddings)
        else:
            store.add_documents(batch)
        print(f"Indexed {min(i + EMBED_BATCH, len(docs))}/{len(docs)}")
        time.sleep(0.2)
    print(f"Embedding+index done in {time.time() - start:.1f}s")

    store.save_local(config.VECTOR_STORE_PERSIST_DIR)
    print(f"Saved FAISS index to {config.VECTOR_STORE_PERSIST_DIR}, total {len(docs)} vectors")


if __name__ == "__main__":
    main()
