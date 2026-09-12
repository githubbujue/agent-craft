import { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { knowledgeAPI } from '../api/index';
import './Knowledge.css';

export default function Knowledge() {
  const navigate = useNavigate();
  const location = useLocation();
  const userId = localStorage.getItem('userId');
  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(false);
  // 说明: 用户端知识库仅保留"浏览"能力。
  // 上传 / 删除属于知识库管理操作, 已收归管理端 —— 后端对应接口也已收紧为 ADMIN 角色,
  // 前端同步移除入口, 避免普通用户看到无权限的操作按钮。

  useEffect(() => {
    if (!userId) {
      navigate('/login');
      return;
    }
    loadDocuments();
  }, [userId, navigate]);

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const docId = params.get('docId');
    if (docId && userId) {
      viewDocument(docId);
    }
  }, [location, userId]);

  const viewDocument = async (id) => {
    try {
      const res = await knowledgeAPI.view(id, userId);
      // Here we could open a modal or redirect to a viewer
      // For now, just show a message that we recorded the view
      // and maybe highlight the document if it's in the list
      console.log('Viewed doc:', res.data);
    } catch (e) {
      console.error('View doc failed', e);
    }
  };

  const loadDocuments = async () => {
    setLoading(true);
    try {
      const response = await knowledgeAPI.list();
      setDocuments(response.data || []);
    } catch (err) {
      console.error('加载文档失败:', err);
    } finally {
      setLoading(false);
    }
  };

  const getStatusBadge = (status) => {
    const statusMap = {
      'PENDING': { text: '处理中', className: 'pending' },
      'COMPLETED': { text: '已完成', className: 'completed' },
      'FAILED': { text: '失败', className: 'failed' }
    };
    const info = statusMap[status] || { text: status, className: '' };
    return <span className={`status-badge ${info.className}`}>{info.text}</span>;
  };

  return (
    <div className="knowledge-container">
      <div className="knowledge-header">
        {/* 用户端为"知识库浏览"页: 仅查看文档与解析状态, 管理操作在管理端 */}
        <h1>知识库</h1>
        <button className="btn btn-default" onClick={() => navigate('/chat')}>
          返回聊天
        </button>
      </div>

      {/* Document list */}
      <div className="document-section card">
        <h3>文档列表</h3>

        {loading ? (
          <div className="loading">加载中...</div>
        ) : documents.length === 0 ? (
          <div className="empty-list">暂无文档，请先上传</div>
        ) : (
          <table className="document-table">
            <thead>
              <tr>
                <th>文档名称</th>
                <th>状态</th>
                <th>上传时间</th>
              </tr>
            </thead>
            <tbody>
              {documents.map(doc => (
                <tr key={doc.id}>
                  <td className="doc-name">{doc.docName}</td>
                  <td>{getStatusBadge(doc.status)}</td>
                  <td>{new Date(doc.createTime).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
