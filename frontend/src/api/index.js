import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  timeout: 180000,
  headers: {
    'Content-Type': 'application/json'
  },
  withCredentials: true // 允许携带cookie
});

// 请求拦截器 - 添加Authorization header
api.interceptors.request.use(
  config => {
    const token = getCookie('accessToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  error => {
    return Promise.reject(error);
  }
);

// 响应拦截器
api.interceptors.response.use(
  response => {
    if (response.data.code === 200) {
      // 处理登录和注册响应，存储token到cookie
      if (response.config.url.includes('/auth/login') || response.config.url.includes('/auth/register')) {
        if (response.data.data && response.data.data.accessToken) {
          setCookie('accessToken', response.data.data.accessToken, 1); // 1小时过期
          setCookie('refreshToken', response.data.data.refreshToken, 24); // 24小时过期
        }
      }
      return response.data;
    }
    return Promise.reject(new Error(response.data.message || 'Request failed'));
  },
  error => {
    // 处理401错误，尝试刷新token
    if (error.response && error.response.status === 401) {
      const refreshToken = getCookie('refreshToken');
      if (refreshToken) {
        return api.post('/auth/refresh', {}, {
          headers: {
            Authorization: `Bearer ${refreshToken}`
          }
        }).then(response => {
          if (response.data && response.data.accessToken) {
            setCookie('accessToken', response.data.accessToken, 1);
            setCookie('refreshToken', response.data.refreshToken, 24);
            // 重新发送原请求
            error.config.headers.Authorization = `Bearer ${response.data.accessToken}`;
            return api(error.config);
          }
        }).catch(() => {
          // 刷新token失败，清除cookie并跳转到登录页
          clearCookies();
          window.location.href = '/login';
        });
      } else {
        // 没有refreshToken，跳转到登录页
        clearCookies();
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

// Cookie操作函数
function setCookie(name, value, hours) {
  const expires = new Date();
  expires.setTime(expires.getTime() + hours * 60 * 60 * 1000);
  document.cookie = `${name}=${value};expires=${expires.toUTCString()};path=/;SameSite=Lax`;
}

function getCookie(name) {
  const cookieName = `${name}=`;
  const decodedCookie = decodeURIComponent(document.cookie);
  const cookieArray = decodedCookie.split(';');
  for (let i = 0; i < cookieArray.length; i++) {
    let cookie = cookieArray[i];
    while (cookie.charAt(0) === ' ') {
      cookie = cookie.substring(1);
    }
    if (cookie.indexOf(cookieName) === 0) {
      return cookie.substring(cookieName.length, cookie.length);
    }
  }
  return '';
}

function clearCookies() {
  setCookie('accessToken', '', -1);
  setCookie('refreshToken', '', -1);
  localStorage.removeItem('user');
  localStorage.removeItem('userId');
}

// Auth API
export const authAPI = {
  sendCode: (phone) => api.post(`/auth/sendCode?phone=${phone}`),
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
  updateUser: (data) => api.post('/auth/update', data),
};

// Chat API
export const chatAPI = {
  createConversation: (userId, title) =>
    api.post(`/chat/conversations?userId=${userId}&title=${title || ''}`),
  getConversations: (userId) =>
    api.get(`/chat/conversations?userId=${userId}`),
  sendMessage: (data, config) => api.post('/chat/messages', data, config),
  /**
   * 流式问答（SSE）
   *
   * 为什么用 fetch + ReadableStream 而不是 EventSource：
   *   EventSource 只支持 GET, 且无法携带 Authorization 头 —— 本接口是 POST + JWT 鉴权。
   *
   * 事件契约（透传自 Python, 末尾追加 saved）:
   *   routed {task_type} | token {content} | end {content} | sources {sources, task_type}
   *   | error {content} | saved {messageId}
   *
   * @param data     {userId, conversationId, content}
   * @param handlers {onEvent(evt), onError(err), onComplete()}
   * @param options  {signal} AbortController 信号 —— 支持"停止生成"
   */
  sendMessageStream: async (data, handlers = {}, options = {}) => {
    const { onEvent, onError, onComplete } = handlers;
    try {
      const token = getCookie('accessToken');
      const headers = { 'Content-Type': 'application/json' };
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const response = await fetch('/api/chat/stream', {
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

      // SSE 以空行分隔事件, 逐块读取、按 "\n\n" 切分、解析 data: 行
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
      console.error('流式请求失败:', error);
      if (onError) onError(error);
    }
  },
  getMessages: (conversationId) =>
    api.get(`/chat/messages?conversationId=${conversationId}`),
  deleteConversation: (id) => api.delete(`/chat/conversations/${id}`),
  updateConversation: (id, data) => api.put(`/chat/conversations/${id}`, data),
  // 临时图片上传
  uploadImage: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post('/chat/upload/image', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  },
  // 查看临时图片
  viewImage: (id) => api.get(`/chat/view/image/${id}`),
  // 提交消息反馈
  submitFeedback: (messageId, feedbackType) => 
    api.post('/chat/messages/feedback', { messageId, feedbackType }),
};

// Knowledge API
export const knowledgeAPI = {
  upload: (file, categoryId) => {
    const formData = new FormData();
    formData.append('file', file);
    if (categoryId) {
      formData.append('categoryId', categoryId);
    }
    return api.post('/knowledge/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  },
  list: (categoryId) =>
    api.get(`/knowledge/list${categoryId ? `?categoryId=${categoryId}` : ''}`),
  delete: (id) => api.delete(`/knowledge/${id}`),
  view: (id, userId) => api.get(`/knowledge/view/${id}?userId=${userId}`),
};

export default api;
