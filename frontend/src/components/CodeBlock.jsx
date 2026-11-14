import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Markdown 代码块: 紧凑行高 + 底部复制按钮
 * - 挂在 react-markdown 的 components.pre 上, 拿到原始 hast 节点取纯文本
 * - 复制走 navigator.clipboard, http 非安全上下文降级到 execCommand
 */

// hast 节点 -> 纯文本 (代码原文), 用于写剪贴板
function nodeText(node) {
  if (!node) return '';
  if (node.type === 'text') return node.value || '';
  return (node.children || []).map(nodeText).join('');
}

// 从 <code class="language-xxx"> 里取语言名, 没有就留空
function languageOf(node) {
  const code = (node?.children || []).find((child) => child.tagName === 'code');
  const className = code?.properties?.className;
  const list = Array.isArray(className) ? className : className ? [className] : [];
  const hit = list.find((item) => typeof item === 'string' && item.startsWith('language-'));
  return hit ? hit.slice('language-'.length) : '';
}

async function writeClipboard(text) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 权限被拒或非安全上下文, 走下面的降级
    }
  }
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

export default function CodeBlock({ node, children, ...rest }) {
  // false | 'done' | 'fail'
  const [copied, setCopied] = useState(false);
  const timer = useRef(null);
  const text = nodeText(node);
  const lang = languageOf(node);

  useEffect(() => () => clearTimeout(timer.current), []);

  const handleCopy = useCallback(async () => {
    const ok = await writeClipboard(text);
    setCopied(ok ? 'done' : 'fail');
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1600);
  }, [text]);

  return (
    <div className="code-block">
      <pre {...rest}>{children}</pre>
      <div className="code-block-footer">
        <span className="code-block-lang">{lang || 'code'}</span>
        <button type="button" className="code-copy-btn" onClick={handleCopy}>
          {copied === 'done' ? '已复制' : copied === 'fail' ? '复制失败' : '复制'}
        </button>
      </div>
    </div>
  );
}
