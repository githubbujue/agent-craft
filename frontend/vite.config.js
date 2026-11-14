import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      // 用户记忆接口在 Python 服务上，必须先于 '/api' 注册（Vite 按 key 顺序匹配前缀），
      // 否则会被下面的 '/api' 规则整体转给 Java，管理端页面拿到 404
      '/api/memory': {
        target: 'http://localhost:8000',
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
