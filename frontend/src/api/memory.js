import axios from 'axios';

// 用户记忆接口在 Python 服务上：/api/memory/* 由 vite / nginx 单独代理到 :8000。
// 不能复用 admin.js —— 它的响应拦截器要求 {code, message, data} 包装，
// 而 Python 返回的是裸 JSON（{user_id, count, items}），复用会把每个成功响应判成失败。
const memoryApi = axios.create({
  baseURL: '/api/memory',
  timeout: 15000,
});

memoryApi.interceptors.request.use((config) => {
  // 与管理端共用同一个 token：Java 和 Python 的 JWT_SECRET 一致，
  // Python 侧按 role=admin 放行任意 user_id
  const token = localStorage.getItem('adminToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

memoryApi.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status;
    if (status === 401) {
      localStorage.removeItem('adminToken');
      localStorage.removeItem('adminInfo');
      window.location.href = '/admin/login';
    }
    // FastAPI 用 detail 字段带错误信息
    const detail = error.response?.data?.detail;
    const message =
      typeof detail === 'string' ? detail : detail?.message || error.message;
    return Promise.reject(new Error(status ? `${status}: ${message}` : message));
  }
);

/** 查询某用户的长期记忆；minConfidence=0 表示连低置信度的推断记忆一起看 */
export const listUserMemory = (userId, minConfidence = 0) =>
  memoryApi.get(`/${encodeURIComponent(userId)}`, {
    params: { min_confidence: minConfidence },
  });

/** 删除单条（传 memoryKey）或清空该用户全部（不传） */
export const deleteUserMemory = (userId, memoryKey) =>
  memoryKey
    ? memoryApi.delete(`/${encodeURIComponent(userId)}/${encodeURIComponent(memoryKey)}`)
    : memoryApi.delete(`/${encodeURIComponent(userId)}`);

export default memoryApi;
