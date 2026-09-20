/**
 * JS 侧色板 —— 与 src/styles/tokens.css 一一对应
 *
 * 为什么需要这个文件(而不是在 JSX 里写 var()):
 *   绝大多数场景下, React 内联 style / <style> 标签里都可以直接写 var(--xxx),
 *   浏览器会解析。但有两处做不到:
 *     1. ECharts / Canvas —— 用 canvas 绘制, 不参与 CSS 变量解析, 传 var(--x) 只会画不出来
 *     2. "色值 + 透明度后缀" 拼接 —— 如 statusColors[s] + '20',
 *        拼出来是 'var(--danger)20' 这种非法值
 *   所以这两个场景必须用真实色值, 统一从这里取。
 *
 * 维护约定: 改 tokens.css 的色值时, 这里同步改 —— 两边是"同一套颜色的两种写法"。
 */
export const COLOR = {
  // 强调(深松绿)
  accent: '#1f6f5c',
  accentSecondary: '#17594a',
  accentSoft: '#eaf3f0',

  // 语义状态
  success: '#2f8f5b',
  successSoft: '#eef7f1',

  warning: '#b7791f',
  warningSoft: '#fdf6e7',

  danger: '#c0392b',
  dangerSoft: '#fdecea',

  info: '#2b6cb0',
  infoSoft: '#eaf1f8',

  // 中性
  neutral: '#5c6370',
  neutralSoft: '#f0f2f5',
  disabled: '#c9ced6',

  // 文字
  textPrimary: '#14161a',
  textSecondary: '#5c6370',
  textMuted: '#9aa1ad',

  // 背景 / 边框
  bgPrimary: '#ffffff',
  bgSecondary: '#f4f6f9',
  bgTertiary: '#f0f2f5',
  border: '#e6e8ec',
  borderLight: '#d8dbe0',
};

/**
 * 评分/等级档位色: 从"红 → 橙 → 黄 → 浅绿 → 绿"的连续梯度。
 * 用于评估看板的分档直方图、质量等级标签等 —— 语义是"越好越绿"。
 */
export const SCALE_COLORS = [
  COLOR.danger,      // 最差
  '#d9663a',         // 差
  COLOR.warning,     // 中
  '#6fae76',         // 良
  COLOR.success,     // 优
];

/**
 * 任务类型标识色
 *
 * 这是少数"允许多色相"的地方: 六种任务类型需要一眼区分, 同色系做不到。
 * 但旧版用的是纯饱和色(#10b981 / #3b82f6 / #8b5cf6 / #f59e0b / #ec4899),
 * 六种高饱和色并排就是"彩虹", 与整体的克制基调冲突。
 * 这里统一"降饱和 + 收敛明度": 色相仍可区分, 但不再抢眼。
 *
 * 注意: 会被用于 "色值 + '20'" 拼半透明底, 必须是真实色值。
 */
export const TASK_TYPE_COLORS = {
  chitchat: '#6b8f7f',              // 闲聊 —— 灰绿(最轻)
  knowledge_qa: '#2f6f8f',          // 知识问答 —— 深青蓝(核心功能)
  admin_copilot: '#5c5c8a',         // 管理助手 —— 灰紫
  knowledge_inspection: '#a8762a',  // 知识巡检 —— 赭黄
  reasoning: '#8a4a63',             // 深度推理 —— 灰玫
  unknown: '#8a9099',               // 未知 —— 中性灰
};

export default COLOR;
