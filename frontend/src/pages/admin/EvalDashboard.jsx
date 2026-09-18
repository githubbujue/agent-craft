import { useEffect, useState, useRef } from 'react';
import * as echarts from 'echarts';
import { getEvalStats } from '../../api/evalStats';
import './Dashboard.css';       // 复用统计卡片/表格/区块样式, 与仪表盘保持视觉一致
import './EvalDashboard.css';

// 忠实度分档配色: 低分警示 → 高分健康
const BUCKET_COLORS = {
  '0.0-0.2': '#ff4d4f',
  '0.2-0.4': '#ff7a45',
  '0.4-0.6': '#faad14',
  '0.6-0.8': '#95de64',
  '0.8-1.0': '#52c41a',
};

// null / undefined 显示占位符 —— "没有数据"与"数值为 0"语义不同, 不能混淆
const fmt = (v, digits = 2) =>
  v === null || v === undefined ? '--' : Number(v).toFixed(digits);

// 后端从 MySQL 取出的是 JSON 数组字符串, 这里解析成可读文本
const parseClaims = (raw) => {
  if (!raw) return '-';
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) && arr.length ? arr.join('；') : '-';
  } catch {
    return String(raw).slice(0, 100);
  }
};

export default function EvalDashboard() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const bucketRef = useRef(null);
  const trendRef = useRef(null);
  const pieRef = useRef(null);
  const charts = useRef([]);

  useEffect(() => {
    fetchData();

    const onResize = () => charts.current.forEach((c) => c.resize());
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      charts.current.forEach((c) => c.dispose());
    };
  }, []);

  useEffect(() => {
    if (stats) setTimeout(initCharts, 100);   // 等 DOM 就绪再初始化画布
  }, [stats]);

  const fetchData = async () => {
    try {
      const res = await getEvalStats();
      if (res.code === 200) {
        setStats(res.data);
      } else {
        console.error('评估统计接口返回异常:', res.message);
      }
    } catch (e) {
      console.error('获取评估统计失败:', e);
    } finally {
      setLoading(false);
    }
  };

  const initCharts = () => {
    charts.current.forEach((c) => c.dispose());
    charts.current = [];

    // ---- 1. 忠实度分档分布(柱状图)----
    const buckets = stats.faithfulnessBuckets || [];
    if (bucketRef.current) {
      const chart = echarts.init(bucketRef.current);
      chart.setOption({
        title: { text: '内容忠实度分布', left: 'center', textStyle: { fontSize: 15 } },
        tooltip: { trigger: 'axis' },
        grid: { left: '6%', right: '5%', bottom: '10%', top: '22%', containLabel: true },
        xAxis: { type: 'category', data: buckets.map((b) => b.name) },
        yAxis: { type: 'value', minInterval: 1, name: '回答数' },
        series: [{
          type: 'bar',
          barMaxWidth: 46,
          data: buckets.map((b) => ({
            value: Number(b.value),
            itemStyle: { color: BUCKET_COLORS[b.name] || '#1890ff' },
          })),
          label: { show: true, position: 'top' },
        }],
      });
      charts.current.push(chart);
    }

    // ---- 2. 按天趋势(双 Y 轴: 忠实度 0-1 / 质量分 0-100, 量纲不同不能共用一轴)----
    const trend = stats.trend || [];
    if (trendRef.current) {
      const chart = echarts.init(trendRef.current);
      chart.setOption({
        title: { text: '评估趋势（按天）', left: 'center', textStyle: { fontSize: 15 } },
        tooltip: { trigger: 'axis' },
        legend: { bottom: 0, data: ['内容忠实度', '答案质量分'] },
        grid: { left: '6%', right: '8%', bottom: '20%', top: '22%', containLabel: true },
        xAxis: { type: 'category', data: trend.map((t) => String(t.date)) },
        yAxis: [
          { type: 'value', name: '忠实度', min: 0, max: 1, position: 'left' },
          { type: 'value', name: '质量分', min: 0, max: 100, position: 'right' },
        ],
        series: [
          {
            name: '内容忠实度',
            type: 'line',
            smooth: true,
            connectNulls: false,        // 无数据的日期断点, 不要假装连成一条线
            yAxisIndex: 0,
            itemStyle: { color: '#52c41a' },
            data: trend.map((t) => (t.faithfulness == null ? null : Number(t.faithfulness))),
          },
          {
            name: '答案质量分',
            type: 'line',
            smooth: true,
            connectNulls: false,
            yAxisIndex: 1,
            itemStyle: { color: '#1890ff' },
            data: trend.map((t) => (t.quality == null ? null : Number(t.quality))),
          },
        ],
      });
      charts.current.push(chart);
    }

    // ---- 3. 检索充分性(环形图)----
    const suff = stats.sufficiency || {};
    if (pieRef.current) {
      const chart = echarts.init(pieRef.current);
      chart.setOption({
        title: { text: '检索充分性', left: 'center', textStyle: { fontSize: 15 } },
        tooltip: { trigger: 'item', formatter: '{b}: {c} 次 ({d}%)' },
        legend: { bottom: 0 },
        series: [{
          type: 'pie',
          radius: ['42%', '66%'],
          center: ['50%', '48%'],
          data: [
            { value: Number(suff.sufficient || 0), name: '充分', itemStyle: { color: '#52c41a' } },
            { value: Number(suff.insufficient || 0), name: '不充分', itemStyle: { color: '#ff4d4f' } },
          ],
          label: { formatter: '{b}\n{c} 次' },
        }],
      });
      charts.current.push(chart);
    }
  };

  if (loading) return <div className="loading">加载中...</div>;
  if (!stats) return <div className="error">暂无评估数据</div>;

  const lowList = stats.lowFaithfulness || [];

  return (
    <div className="dashboard-container">
      <h2 className="page-title">评估看板</h2>

      {/* 1. 顶部总览指标 */}
      <div className="stats-cards">
        <div className="stat-card">
          <div className="stat-value">{fmt(stats.avgFaithfulness, 3)}</div>
          <div className="stat-label">平均内容忠实度</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{fmt(stats.avgQualityScore, 1)}</div>
          <div className="stat-label">平均答案质量分</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{fmt(stats.sufficiencyRate, 1)}%</div>
          <div className="stat-label">检索充分率</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.totalRuns ?? 0}</div>
          <div className="stat-label">累计运行次数</div>
        </div>
      </div>

      {/* 2. 图表区 */}
      <div className="eval-charts-row">
        <div className="eval-chart-box">
          <div ref={bucketRef} style={{ width: '100%', height: '100%' }} />
        </div>
        <div className="eval-chart-box">
          <div ref={trendRef} style={{ width: '100%', height: '100%' }} />
        </div>
        <div className="eval-chart-box narrow">
          <div ref={pieRef} style={{ width: '100%', height: '100%' }} />
        </div>
      </div>

      {/* 3. 低分回答清单 —— 直接定位"哪条回答在编" */}
      <div className="dashboard-section">
        <h3>⚠️ 低分回答清单（内容忠实度 &lt; {stats.lowThreshold ?? 0.8}）</h3>
        <table className="data-table">
          <thead>
            <tr>
              <th style={{ width: '30%' }}>问题</th>
              <th style={{ width: '90px' }}>忠实度</th>
              <th>无依据的事实性陈述</th>
            </tr>
          </thead>
          <tbody>
            {lowList.length > 0 ? (
              lowList.map((row) => (
                <tr key={row.runId}>
                  <td className="text-truncate" title={row.question} style={{ maxWidth: 320 }}>
                    {row.question || '-'}
                  </td>
                  <td>
                    <span className={`score-badge ${Number(row.faithfulness) < 0.5 ? 'danger' : 'warn'}`}>
                      {Number(row.faithfulness).toFixed(2)}
                    </span>
                  </td>
                  <td>{parseClaims(row.unsupportedClaims)}</td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan="3" className="empty-text">表现良好，暂无低分回答</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
