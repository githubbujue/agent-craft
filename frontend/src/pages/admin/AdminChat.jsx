import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import Markdown from '../../components/Markdown';
import './AdminChat.css';

const adminApi = axios.create({
  baseURL: '/api/admin-chat',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

adminApi.interceptors.request.use(
  config => {
    const token = localStorage.getItem('adminToken');
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }
    return config;
  },
  error => Promise.reject(error)
);

const TASK_TYPE_INFO = {
  chitchat: { icon: '💬', label: '闲聊', color: '#10b981' },
  knowledge_qa: { icon: '📚', label: '知识问答', color: '#3b82f6' },
  admin_copilot: { icon: '⚙️', label: '管理助手', color: '#8b5cf6' },
  knowledge_inspection: { icon: '🔍', label: '知识巡检', color: '#f59e0b' },
  reasoning: { icon: '🧠', label: '深度推理', color: '#ec4899' },
  unknown: { icon: '🤖', label: 'AI助手', color: '#6b7280' }
};

const adminChatAPI = {
  createConversation: (adminId, title) =>
    adminApi.post(`/conversations?adminId=${adminId}&title=${title || ''}`),
  getConversations: (adminId) =>
    adminApi.get(`/conversations?adminId=${adminId}`),
  sendMessage: (adminId, conversationId, content) =>
    adminApi.post(`/messages?adminId=${adminId}&conversationId=${conversationId}`, { content }),
  /**
   * 流式问答（SSE）—— 与用户端 chatAPI.sendMessageStream 同一套事件契约:
   *   routed {task_type} | token {content} | end {content} | sources {sources, task_type}
   *   | error {content} | saved {messageId}
   *
   * 为什么用 fetch + ReadableStream: EventSource 只支持 GET 且无法携带 Authorization 头,
   * 本接口是 POST + JWT 鉴权（与用户端同一取舍）。
   *
   * @param data     {conversationId, content}
   * @param handlers {onEvent(evt), onError(err), onComplete()}
   * @param options  {signal} AbortController 信号 —— 支持"停止生成"
   */
  sendMessageStream: async (data, handlers = {}, options = {}) => {
    const { onEvent, onError, onComplete } = handlers;
    try {
      const token = localStorage.getItem('adminToken');
      const headers = { 'Content-Type': 'application/json' };
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const response = await fetch('/api/admin-chat/messages/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify(data),
        signal: options.signal,
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP ${response.status}: ${errorText || '请求失败'}`);
      }
      if (!response.body) {
        throw new Error('当前浏览器不支持流式读取');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      // SSE 以空行分隔事件: 逐块读取、按 "\n\n" 切分、解析 data: 行
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const chunks = buffer.split('\n\n');
        buffer = chunks.pop() || ''; // 末尾可能是不完整事件, 留到下一轮

        for (const chunk of chunks) {
          const dataLine = chunk.split('\n').find(l => l.startsWith('data:'));
          if (!dataLine) continue;
          const jsonStr = dataLine.slice(5).trim();
          if (!jsonStr) continue;
          try {
            if (onEvent) onEvent(JSON.parse(jsonStr));
          } catch (error) {
            console.error('SSE 事件解析失败:', error, jsonStr);
          }
        }
      }
      if (onComplete) onComplete();
    } catch (error) {
      // 用户主动停止（abort）不是错误, 按正常结束处理
      if (error.name === 'AbortError') {
        if (onComplete) onComplete();
        return;
      }
      console.error('管理端流式请求失败:', error);
      if (onError) onError(error);
    }
  },
  getMessages: (conversationId) =>
    adminApi.get(`/messages?conversationId=${conversationId}`),
  deleteConversation: (id) => adminApi.delete(`/conversations/${id}`),
  updateConversation: (id, data) => adminApi.put(`/conversations/${id}`, data),
  submitFeedback: (messageId, feedbackType) =>
    adminApi.post('/messages/feedback', { messageId, feedbackType }),
};

export default function AdminChat() {
  const navigate = useNavigate();
  const adminId = parseInt(localStorage.getItem('adminId'));
  const [conversations, setConversations] = useState([]);
  const [currentConversation, setCurrentConversation] = useState(null);
  const [messages, setMessages] = useState([]);
  const [inputMessage, setInputMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [processingStep, setProcessingStep] = useState(null);
  const [currentTaskType, setCurrentTaskType] = useState(null);
  const [menuOpenId, setMenuOpenId] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editTitle, setEditTitle] = useState('');
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleteConversationId, setDeleteConversationId] = useState(null);
  const [abortController, setAbortController] = useState(null);
  const [abortedRequests, setAbortedRequests] = useState(new Set());
  const messagesEndRef = useRef(null);
  const menuRef = useRef(null);

  useEffect(() => {
    document.title = 'AI 知识系统-管理助手';
    return () => {
      document.title = 'AI 知识系统';
    };
  }, []);

  useEffect(() => {
    const adminToken = localStorage.getItem('adminToken');
    if (!adminToken) {
      navigate('/admin/login');
      return;
    }
    if (!adminId) {
      navigate('/admin/login');
      return;
    }
    loadConversations();
  }, [adminId, navigate]);

  useEffect(() => {
    if (currentConversation) {
      loadMessages(currentConversation.id);
    }
  }, [currentConversation]);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const getFileInfo = (fullName) => {
    if (!fullName) return { icon: '📄', name: '相关文档' };

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_/i;
    const cleanName = fullName.replace(uuidRegex, '');

    const ext = cleanName.split('.').pop().toLowerCase();
    let icon = '📄';
    switch (ext) {
      case 'pdf': icon = '📕'; break;
      case 'doc':
      case 'docx': icon = '📘'; break;
      case 'txt': icon = '📄'; break;
      case 'md': icon = '📝'; break;
      case 'xlsx':
      case 'xls': icon = '📗'; break;
      case 'ppt':
      case 'pptx': icon = '📙'; break;
      case 'zip':
      case 'rar': icon = '📦'; break;
      default: icon = '📄';
    }

    return { icon, name: cleanName };
  };

  const renderSources = (sourcesJson) => {
    if (!sourcesJson) return null;
    try {
      const sources = JSON.parse(sourcesJson);
      if (!Array.isArray(sources) || sources.length === 0) return null;
      return (
        <div className="message-sources">
          <h4>参考来源:</h4>
          <ul>
            {sources.map((s, i) => {
              const { icon, name } = getFileInfo(s.doc || s.doc_name);
              return (
                <li key={i}>
                  {s.source && (s.source.startsWith('http://') || s.source.startsWith('https://')) ? (
                      <span className="source-link" onClick={() => window.open(s.source, '_blank')}>
                        {icon} {name}
                      </span>
                  ) : (
                      <span className="source-link" onClick={() => window.open(`/knowledge?docId=${s.doc_id || s.docId}&page=${s.page}`, '_blank')}>
                        {icon} {name}
                      </span>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      );
    } catch (e) {
      console.error("Parse sources failed", e);
      return null;
    }
  };

  const getTaskTypeBadge = (taskType) => {
    const info = TASK_TYPE_INFO[taskType] || TASK_TYPE_INFO.unknown;
    return (
      <span
        className="admin-task-type-badge"
        style={{ backgroundColor: info.color + '20', color: info.color }}
        title={`任务类型: ${info.label}`}
      >
        {info.icon} {info.label}
      </span>
    );
  };

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setMenuOpenId(null);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  const handleMenuClick = (id, e) => {
    e.stopPropagation();
    setMenuOpenId(menuOpenId === id ? null : id);
  };

  const handlePin = async (conv, e) => {
    e.stopPropagation();
    setMenuOpenId(null);
    try {
      await adminChatAPI.updateConversation(conv.id, { isPinned: !conv.isPinned });
      loadConversations();
    } catch (err) {
      console.error('更新置顶失败', err);
    }
  };

  const handleRenameStart = (conv, e) => {
    e.stopPropagation();
    setMenuOpenId(null);
    setEditingId(conv.id);
    setEditTitle(conv.title);
  };

  const handleRenameSave = async (id) => {
    try {
      await adminChatAPI.updateConversation(id, { title: editTitle });
      setEditingId(null);
      loadConversations();
    } catch (err) {
      console.error('重命名失败', err);
    }
  };

  const handleRenameKeyDown = (e, id) => {
    if (e.key === 'Enter') {
      handleRenameSave(id);
    } else if (e.key === 'Escape') {
      setEditingId(null);
    }
  };

  const handleDelete = (id, e) => {
    e.stopPropagation();
    setMenuOpenId(null);
    setDeleteConversationId(id);
    setShowDeleteConfirm(true);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConversationId) return;

    try {
      await adminChatAPI.deleteConversation(deleteConversationId);
      setConversations(conversations.filter(c => c.id !== deleteConversationId));
      if (currentConversation?.id === deleteConversationId) {
        setCurrentConversation(null);
        setMessages([]);
      }
    } catch (err) {
      console.error('删除会话失败:', err);
    } finally {
      setShowDeleteConfirm(false);
      setDeleteConversationId(null);
    }
  };

  const handleDeleteCancel = () => {
    setShowDeleteConfirm(false);
    setDeleteConversationId(null);
  };

  const loadConversations = async () => {
    try {
      const response = await adminChatAPI.getConversations(adminId);
      setConversations(response.data.data || []);
    } catch (err) {
      console.error('加载会话失败:', err);
    }
  };

  const loadMessages = async (conversationId) => {
    try {
      console.log('=== AdminChat: Loading messages for conversationId:', conversationId);
      const response = await adminChatAPI.getMessages(conversationId);
      console.log('=== AdminChat: Messages response:', response);
      const messages = response.data.data || [];
      const messagesWithFeedback = messages.map(msg => ({
        ...msg,
        feedbackType: msg.feedbackType || msg.feedback || null
      }));
      messagesWithFeedback.sort((a, b) => new Date(a.createTime) - new Date(b.createTime));
      setMessages(messagesWithFeedback);
    } catch (err) {
      console.error('加载消息失败:', err);
    }
  };

  const handleCreateConversation = async () => {
    try {
      const response = await adminChatAPI.createConversation(adminId, '新对话');
      setConversations([response.data.data, ...conversations]);
      setCurrentConversation(response.data.data);
    } catch (err) {
      console.error('创建会话失败:', err);
    }
  };

  const handleSendMessage = async (e) => {
    e.preventDefault();
    if (!inputMessage.trim()) return;
    if (!currentConversation) return;

    const userMessage = {
      id: Date.now(),
      conversationId: currentConversation.id,
      role: 'user',
      content: inputMessage,
      createTime: new Date().toISOString()
    };

    const thinkingMessageId = Date.now() + 1;
    const thinkingMessage = {
      id: thinkingMessageId,
      conversationId: currentConversation.id,
      role: 'assistant',
      content: '',
      isStreaming: true,
      processingStep: null,
      taskType: null,
      createTime: new Date().toISOString()
    };

    const requestId = Date.now() + Math.random();

    setMessages(prev => [...prev, userMessage, thinkingMessage]);
    setInputMessage('');
    setLoading(true);
    setProcessingStep('understanding');
    setCurrentTaskType(null);

    const controller = new AbortController();
    setAbortController(controller);

    try {
      // 流式接收: 逐 token 更新最后一条 assistant 消息（先挂临时 id, 落库后替换为真实 id）
      let liveMessageId = thinkingMessageId;
      // 已接收的正文（用于判断"是否已经出现可见内容"）
      let streamedText = '';
      await adminChatAPI.sendMessageStream(
        { conversationId: currentConversation.id, content: inputMessage },
        {
          onEvent: (evt) => {
            if (abortedRequests.has(requestId)) return;
            switch (evt.type) {
              case 'routed':
                // 路由确定 → "思考中" 切 "生成中"
                setProcessingStep('generating');
                if (evt.task_type) setCurrentTaskType(evt.task_type);
                break;
              case 'token': {
                const delta = evt.content || '';
                // 空 token 不改变 UI：模型流开头常吐若干空块,
                // 若此时就关闭思考动画, 会露出一段空白气泡
                if (!delta) break;
                streamedText += delta;
                // 只有真正出现可见字符（非空白/换行）才隐藏思考动画, 保证动画无缝衔接到正文
                if (streamedText.trim().length > 0) {
                  setProcessingStep(null);
                }
                setMessages(prev => prev.map(m => m.id === liveMessageId
                  ? { ...m, content: (m.content || '') + delta, isStreaming: true }
                  : m));
                break;
              }
              case 'end': {
                // end 携带完整回答, 以其为准（比逐 token 拼接更可靠）。
                // content 有两种形态: 字符串 或 对象 {answer, sources, task_type}（闲聊等链路）
                const ec = evt.content;
                const endText = typeof ec === 'string'
                  ? ec
                  : (ec && typeof ec === 'object' ? (ec.answer || '') : '');
                if (endText && endText.trim().length > 0) {
                  setProcessingStep(null);
                  setMessages(prev => prev.map(m => m.id === liveMessageId
                    ? {
                        ...m,
                        content: endText,
                        taskType: (ec && typeof ec === 'object' && ec.task_type) || m.taskType
                      }
                    : m));
                }
                if (ec && typeof ec === 'object' && Array.isArray(ec.sources)) {
                  setMessages(prev => prev.map(m => m.id === liveMessageId
                    ? { ...m, sources: JSON.stringify(ec.sources) } : m));
                }
                break;
              }
              case 'sources':
                setMessages(prev => prev.map(m => m.id === liveMessageId
                  ? {
                      ...m,
                      sources: JSON.stringify(evt.sources || []),
                      taskType: evt.task_type || m.taskType
                    }
                  : m));
                break;
              case 'saved':
                // 后端已落库: 用真实 messageId 替换临时 id（反馈/点赞依赖真实 id）
                if (evt.messageId) {
                  setMessages(prev => prev.map(m => m.id === liveMessageId
                    ? { ...m, id: evt.messageId } : m));
                  liveMessageId = evt.messageId;
                }
                break;
              case 'error':
                setMessages(prev => prev.map(m => m.id === liveMessageId
                  ? { ...m, content: evt.content || '抱歉，服务暂时不可用。' } : m));
                break;
              default:
                break;
            }
          },
          onComplete: () => {
            // 流结束（正常结束 / 用户中止 都会走这里）: 统一收尾
            setMessages(prev => prev.map(m => (m.id === liveMessageId || m.isStreaming)
              ? {
                  ...m,
                  // 边界保护: 流结束了却没有任何内容时, 不留空白气泡
                  content: (m.content && m.content.trim()) ? m.content : '（本次未生成内容，请重试）',
                  isStreaming: false,
                  processingStep: null
                }
              : m));
            setLoading(false);
            setProcessingStep(null);
            setAbortController(null);
            loadConversations();
          },
          onError: (err) => {
            console.error('发送消息失败:', err);
            setMessages(prev => prev.map(m => m.id === liveMessageId
              ? { ...m, content: '抱歉，我暂时无法回答这个问题，请稍后再试。', isStreaming: false }
              : m));
            setLoading(false);
            setProcessingStep(null);
            setAbortController(null);
          }
        },
        { signal: controller.signal }
      );
    } catch (err) {
      // 兜底（正常情况下错误已在 onError 中处理）
      console.error('发送消息异常:', err);
      setMessages(prev => prev.map(m => m.id === thinkingMessageId
        ? { ...m, content: '抱歉，我暂时无法回答这个问题，请稍后再试。', isStreaming: false }
        : m));
      setLoading(false);
      setProcessingStep(null);
      setAbortController(null);
    }
  };

  const formatDateTime = (dateTimeStr) => {
    if (!dateTimeStr) return '';
    const date = new Date(dateTimeStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const days = Math.floor(diff / (1000 * 60 * 60 * 24));

    if (days === 0) {
      return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    } else if (days === 1) {
      return '昨天';
    } else if (days < 7) {
      return `${days}天前`;
    } else {
      return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
    }
  };

  const handleFeedback = async (messageId, type) => {
    try {
      await adminChatAPI.submitFeedback(messageId, type);

      setMessages(prev => prev.map(msg =>
        msg.id === messageId
          ? { ...msg, feedbackType: type }
          : msg
      ));

      console.log(`Feedback ${type} recorded for message ${messageId}`);
    } catch (err) {
      console.error('提交反馈失败:', err);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('adminToken');
    localStorage.removeItem('adminInfo');
    localStorage.removeItem('adminId');
    navigate('/admin/login');
  };

  const handleStopGeneration = () => {
    if (abortController) {
      abortController.abort();
      setMessages(prev => {
        const lastAssistantMessageIndex = prev.findLastIndex(
          msg => msg.role === 'assistant' && (msg.isStreaming || msg.processingStep)
        );
        if (lastAssistantMessageIndex !== -1) {
          const updatedMessages = [...prev];
          const target = updatedMessages[lastAssistantMessageIndex];
          updatedMessages[lastAssistantMessageIndex] = {
            ...target,
            // 与后端策略一致: 已生成的内容保留（后端也会保存已生成部分）,
            // 仅当一个字都还没生成时才显示"已停止回答"
            content: target.content ? target.content + '\n\n（已停止生成）' : '已停止回答',
            isStreaming: false,
            processingStep: null
          };
          return updatedMessages;
        }
        return prev;
      });
      setLoading(false);
      setProcessingStep(null);
      setAbortController(null);
    }
  };

  const getProcessingMessage = () => {
    // 文案衔接: 发送后先"思考中"（意图分类阶段）, 路由确定后切"生成中",
    // 直到出现第一个可见字符才隐藏动画 —— 中间不出现空白气泡
    const steps = {
      understanding: { icon: '🤔', text: '正在思考...' },
      retrieving: { icon: '🔍', text: '正在检索知识库...' },
      generating: { icon: '✍️', text: '正在生成回答...' }
    };
    return steps[processingStep] || null;
  };

  return (
    <div className="admin-chat-container">
      <div className="admin-chat-sidebar">
        <div className="admin-chat-header">
          <h2>⚙️ 管理助手</h2>
          <button className="admin-new-chat-btn" onClick={handleCreateConversation}>
            <span>+</span> 开启新对话
          </button>
        </div>

        <div className="admin-conversation-list">
          {conversations.map(conv => (
            <div
              key={conv.id}
              className={`admin-conversation-item ${currentConversation?.id === conv.id ? 'active' : ''} ${conv.isPinned ? 'pinned' : ''}`}
              onClick={() => setCurrentConversation(conv)}
            >
              {editingId === conv.id ? (
                <input
                  type="text"
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onBlur={() => handleRenameSave(conv.id)}
                  onKeyDown={(e) => handleRenameKeyDown(e, conv.id)}
                  autoFocus
                  className="admin-rename-input"
                  onClick={(e) => e.stopPropagation()}
                />
              ) : (
                <>
                  <div className="admin-conversation-info">
                    <span className="admin-conversation-title">
                      {conv.title || '新对话'}
                    </span>
                    <span className="admin-conversation-time">
                      {formatDateTime(conv.createTime)}
                    </span>
                  </div>
                  <button
                    className="admin-menu-btn"
                    onClick={(e) => handleMenuClick(conv.id, e)}
                  >
                    •••
                  </button>
                  {menuOpenId === conv.id && (
                    <div className="admin-context-menu" ref={menuRef}>
                      <div className="admin-menu-item" onClick={(e) => handleRenameStart(conv, e)}>
                        ✏️ 重命名
                      </div>
                      <div className="admin-menu-item" onClick={(e) => handlePin(conv, e)}>
                        {conv.isPinned ? '🚫 取消置顶' : '📌 置顶'}
                      </div>
                      <div className="admin-menu-item delete" onClick={(e) => handleDelete(conv.id, e)}>
                        🗑️ 删除
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>
          ))}
        </div>

        <div className="sidebar-footer">
          <div className="user-profile" onClick={handleLogout} title="点击退出登录">
            <div className="user-avatar">👤</div>
            <span>退出登录</span>
          </div>
        </div>
      </div>

      <div className="admin-chat-main">
        {currentConversation ? (
          <>
            <div className="admin-chat-header-bar">
              <h3>{currentConversation.title || '新对话'}</h3>
            </div>

            <div className="admin-messages-container">
              {messages.length === 0 ? (
                <div className="admin-welcome-screen">
                  <div className="admin-welcome-avatar">⚙️</div>
                  <h1>管理助手</h1>
                  <p>我可以帮你进行知识巡检、用户分析、运营统计等管理操作</p>
                </div>
              ) : (
                messages.map((msg, index) => (
                  <div key={msg.id || index} className={`admin-message-row ${msg.role}`}>
                    <div className="admin-message-content-wrapper">
                      <div className="admin-message-avatar">
                        {msg.role === 'user' ? '👤' : '⚙️'}
                      </div>
                      <div className="admin-message-body">
                        {msg.role === 'assistant' && msg.isStreaming && processingStep ? (
                          <div className="admin-processing-indicator">
                            <div className="admin-processing-icon">{getProcessingMessage()?.icon}</div>
                            <div className="admin-processing-text">{getProcessingMessage()?.text}</div>
                            <div className="admin-processing-dots">
                              <span className="dot"></span>
                              <span className="dot"></span>
                              <span className="dot"></span>
                            </div>
                          </div>
                        ) : (
                          <>
                            {msg.role === 'assistant' && msg.taskType && (
                              <div className="admin-message-task-type">
                                {getTaskTypeBadge(msg.taskType)}
                              </div>
                            )}
                            {msg.role === 'assistant'
                              ? <Markdown content={msg.content} />
                              : msg.content}
                            {msg.role === 'assistant' && msg.sources && renderSources(msg.sources)}
                          </>
                        )}
                      </div>
                      {msg.role === 'assistant' && !msg.isStreaming && !msg.processingStep && (
                        <div className="admin-message-feedback">
                          <button
                            className={`admin-feedback-btn like ${msg.feedbackType === 'like' ? 'active' : ''}`}
                            onClick={() => handleFeedback(msg.id, 'like')}
                            title="点赞"
                          >
                            👍
                          </button>
                          <button
                            className={`admin-feedback-btn dislike ${msg.feedbackType === 'dislike' ? 'active' : ''}`}
                            onClick={() => handleFeedback(msg.id, 'dislike')}
                            title="点踩"
                          >
                            👎
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                ))
              )}
              <div ref={messagesEndRef} />
            </div>

            <div className="admin-input-container">
              <div className="admin-input-wrapper">
                <form className="admin-chat-input-area" onSubmit={handleSendMessage}>
                  <textarea
                    value={inputMessage}
                    onChange={(e) => setInputMessage(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        handleSendMessage(e);
                      }
                    }}
                    placeholder="给管理助手发送消息..."
                    rows={1}
                  />
                  <div className="admin-input-buttons">
                    {loading && (
                      <button
                        type="button"
                        className="admin-stop-btn"
                        onClick={handleStopGeneration}
                        title="停止生成"
                      >
                        ⏹️
                      </button>
                    )}
                    <button type="submit" className="admin-send-btn" disabled={!inputMessage.trim()}>
                      ➤
                    </button>
                  </div>
                </form>
              </div>
            </div>
          </>
        ) : (
          <div className="admin-welcome-screen">
            <div className="admin-welcome-avatar">⚙️</div>
            <h1>欢迎使用管理助手</h1>
            <p>基于 AI 技术，为您提供智能化的管理操作支持</p>
            <button className="admin-start-btn" onClick={handleCreateConversation}>
              开始新对话
            </button>
          </div>
        )}
      </div>

      {showDeleteConfirm && (
        <div className="admin-modal-overlay">
          <div className="admin-modal-content">
            <div className="admin-modal-header">
              <h3>确认删除</h3>
            </div>
            <div className="admin-modal-body">
              <p>确定删除此对话吗？</p>
            </div>
            <div className="admin-modal-footer">
              <button className="admin-modal-btn cancel" onClick={handleDeleteCancel}>
                取消
              </button>
              <button className="admin-modal-btn confirm" onClick={handleDeleteConfirm}>
                确定
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}