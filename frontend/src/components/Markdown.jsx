import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './Markdown.css';

/**
 * AI 消息 Markdown 渲染组件
 * - remark-gfm: 支持表格/删除线/任务列表 (LLM 回答常用)
 * - 样式收敛在 .md-body 内, 不影响气泡外布局
 */
export default function Markdown({ content }) {
  return (
    <div className="md-body">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content || ''}</ReactMarkdown>
    </div>
  );
}
