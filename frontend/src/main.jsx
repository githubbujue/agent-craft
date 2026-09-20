import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
// 全局设计令牌(色板/圆角/阴影/过渡)必须先于各页面样式加载,
// 否则页面里的 var() 会取不到值
import './styles/tokens.css'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
