import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { deleteUserMemory, listUserMemory } from '../../api/memory';
import './AdminDashboard.css';

// 与后端读侧阈值保持一致：低于 0.6 的记忆不会注入 Prompt
const EFFECTIVE_CONFIDENCE = 0.6;

/** memory_value 可能是 {"value","quote"} 也可能是历史遗留的纯字符串 */
function valueOf(raw) {
  if (raw && typeof raw === 'object') {
    return raw.value === undefined ? JSON.stringify(raw) : String(raw.value);
  }
  return String(raw ?? '');
}

function quoteOf(raw) {
  return raw && typeof raw === 'object' && raw.quote ? String(raw.quote) : '';
}

export default function MemoryManagement() {
  const [searchParams] = useSearchParams();
  const initialUserId = searchParams.get('userId') || '';

  const [userId, setUserId] = useState(initialUserId);
  const [onlyEffective, setOnlyEffective] = useState(false);
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [queriedId, setQueriedId] = useState('');

  const fetchMemory = useCallback(async (uid, effectiveOnly) => {
    if (!uid) {
      setError('请先填写用户 ID');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const data = await listUserMemory(uid, effectiveOnly ? EFFECTIVE_CONFIDENCE : 0);
      setItems(data.items || []);
      setQueriedId(data.user_id || uid);
    } catch (e) {
      setItems([]);
      setError(e.message || '查询失败');
    } finally {
      setLoading(false);
    }
  }, []);

  // 从用户管理页带 userId 跳进来时直接查一次
  useEffect(() => {
    if (initialUserId) {
      fetchMemory(initialUserId, false);
    }
  }, [initialUserId, fetchMemory]);

  const handleSearch = (e) => {
    e?.preventDefault();
    fetchMemory(userId.trim(), onlyEffective);
  };

  const handleDeleteOne = async (memoryKey) => {
    if (!window.confirm(`确定删除记忆「${memoryKey}」？删除后 AI 将不再记得这一条。`)) {
      return;
    }
    try {
      await deleteUserMemory(queriedId, memoryKey);
      fetchMemory(queriedId, onlyEffective);
    } catch (e) {
      setError(e.message || '删除失败');
    }
  };

  const handleClearAll = async () => {
    if (!window.confirm(`确定清空用户 ${queriedId} 的全部记忆？此操作不可恢复。`)) {
      return;
    }
    try {
      await deleteUserMemory(queriedId);
      setItems([]);
    } catch (e) {
      setError(e.message || '清空失败');
    }
  };

  return (
    <div>
      <div className="page-header">
        <h1>用户记忆</h1>
        <p>AI 从对话中记下的长期偏好，会注入后续回答的 Prompt；这里可以查看与删除</p>
      </div>

      <div className="admin-card">
        <form className="search-bar" onSubmit={handleSearch}>
          <div className="search-row">
            <div className="search-item">
              <label>用户 ID</label>
              <input
                type="text"
                placeholder="例如 3"
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
              />
            </div>
            <div className="search-item">
              <label>筛选</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontWeight: 'normal' }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto' }}
                  checked={onlyEffective}
                  onChange={(e) => setOnlyEffective(e.target.checked)}
                />
                只看会生效的（≥ {EFFECTIVE_CONFIDENCE}）
              </label>
            </div>
            <div className="search-actions">
              <button type="submit" className="btn btn-primary" disabled={loading}>
                {loading ? '查询中...' : '查询'}
              </button>
              {queriedId && items.length > 0 && (
                <button type="button" className="btn btn-default" onClick={handleClearAll}>
                  清空该用户
                </button>
              )}
            </div>
          </div>
        </form>

        {error && <div className="text-error" style={{ padding: '8px 0' }}>{error}</div>}

        <div className="card-header">
          <h2>记忆列表</h2>
          <span className="total-info">
            {queriedId ? `用户 ${queriedId} · 共 ${items.length} 条` : '输入用户 ID 后查询'}
          </span>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>加载中...</div>
        ) : items.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
            {queriedId ? '该用户还没有被记住的偏好' : '暂无数据'}
          </div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th>记忆键</th>
                <th>值</th>
                <th>来源</th>
                <th>置信度</th>
                <th>更新时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => {
                const quote = quoteOf(item.memory_value);
                const effective = Number(item.confidence) >= EFFECTIVE_CONFIDENCE;
                return (
                  <tr key={item.memory_key}>
                    <td>{item.memory_key}</td>
                    <td title={quote ? `用户原话：${quote}` : ''}>
                      {valueOf(item.memory_value)}
                      {quote && (
                        <div style={{ color: '#999', fontSize: '12px', marginTop: '2px' }}>
                          「{quote}」
                        </div>
                      )}
                    </td>
                    <td>{item.source || '-'}</td>
                    <td>
                      <span className={`status-badge ${effective ? 'normal' : 'pending'}`}>
                        {Number(item.confidence).toFixed(2)}
                      </span>
                    </td>
                    <td>{item.update_time || '-'}</td>
                    <td>
                      <button
                        className="action-btn delete"
                        onClick={() => handleDeleteOne(item.memory_key)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
