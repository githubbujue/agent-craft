import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import CodeBlock from './CodeBlock';
import './Markdown.css';

/**
 * AI 消息 Markdown 渲染组件
 * - remark-gfm: 支持表格/删除线/任务列表 (LLM 回答常用)
 * - pre 换成 CodeBlock: 紧凑行高 + 底部复制按钮
 * - 样式收敛在 .md-body 内, 不影响气泡外布局
 */
// 提到模块级, 避免每次渲染都重建 components 导致代码块整体重挂载
const COMPONENTS = { pre: CodeBlock };

export default function Markdown({ content }) {
  return (
    <div className="md-body">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={COMPONENTS}>
        {content || ''}
      </ReactMarkdown>
    </div>
  );
}
