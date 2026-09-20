import React, { useEffect, useState, useRef } from 'react';
import { getDashboardStats } from '../../api/dashboard';
import * as echarts from 'echarts';
import './Dashboard.css';

// task_type → 可读名称(后端返回的是枚举串, 直接显示对用户不友好)
const TASK_TYPE_LABELS = {
  chitchat: '闲聊',
  knowledge_qa: '知识问答',
  reasoning: '深度推理',
  admin_copilot: '管理助手',
  knowledge_inspection: '知识巡检',
  unknown: '未分类',
};

export default function Dashboard() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const pieChartRef = useRef(null);
  const trendChartRef = useRef(null);
  const chartInstances = useRef([]);

  useEffect(() => {
    fetchStats();
    
    const handleResize = () => {
      chartInstances.current.forEach(chart => chart.resize());
    };
    window.addEventListener('resize', handleResize);
    
    return () => {
      window.removeEventListener('resize', handleResize);
      chartInstances.current.forEach(chart => chart.dispose());
    };
  }, []);

  useEffect(() => {
    if (stats) {
      // Small timeout to ensure DOM is ready
      setTimeout(initCharts, 100);
    }
  }, [stats]);

  const fetchStats = async () => {
    try {
      const res = await getDashboardStats();
      if (res.code === 200) {
        setStats(res.data);
      }
    } catch (error) {
      console.error('Fetch stats failed', error);
    } finally {
      setLoading(false);
    }
  };

  const initCharts = () => {
    // Clean up old instances
    chartInstances.current.forEach(chart => chart.dispose());
    chartInstances.current = [];

    // 1. 提问类型分布(饼图 —— 只表达"部分-整体"关系)
    //    改前是"用户数 / 文档数 / 提问数 / 命中率"混在一起: 前三者量纲是"个"、命中率是"%"，
    //    相加无业务含义 → 算出的占比无法解读；且与上方指标卡片完全重复。
    //    现改为按 task_type 统计占比 —— 这才是真正的构成关系，还能看出用户都在问什么类型。
    if (pieChartRef.current) {
        const pieChart = echarts.init(pieChartRef.current);
        const typeData = (stats.taskTypeDistribution || []).map(item => ({
            name: TASK_TYPE_LABELS[item.name] || item.name,
            value: Number(item.value) || 0
        }));
        const hasData = typeData.length > 0;
        const optionPie = {
            title: {
                text: '提问类型分布',
                left: 'center',
                top: 8,
                textStyle: { fontSize: 15, fontWeight: 600 }
            },
            tooltip: {
                trigger: 'item',
                formatter: '{b}: {c} 条 ({d}%)',   // 占比在"同质构成"下才有意义
                confine: true,
                // 固定在环形中心 —— 跟随鼠标时 tooltip 会盖住扇区和外侧标签,
                // 而环心本来就是空的, 是环形图最自然的提示位置
                position: (point, params, dom, rect, size) => [
                    size.viewSize[0] / 2 - size.contentSize[0] / 2,
                    size.viewSize[1] / 2 - size.contentSize[1] / 2
                ]
            },
            legend: {
                bottom: 2,
                left: 'center',
                itemWidth: 10,
                itemHeight: 10,
                itemGap: 14,
                textStyle: { fontSize: 12, color: '#666' }
            },
            series: [
                {
                    name: '提问类型',
                    type: 'pie',
                    // 顶部只需容纳标题一行、底部留给图例, 故半径放大到接近垂直空间上限;
                    // 圆心略高于几何中心, 让上下两侧的标签都有余地(不压标题、不碰图例)
                    radius: ['42%', '58%'],
                    center: ['50%', '53%'],
                    // 刻意不用 roseType: 玫瑰图用于"同类项之间比大小"，
                    // 饼图用于"看占比"，混用会让扇区面积失真(旧代码正是靠它掩盖量纲冲突)
                    itemStyle: {
                        borderRadius: 6,
                        borderColor: '#fff',
                        borderWidth: 2
                    },
                    label: {
                        formatter: '{b}\n{d}%',
                        fontSize: 13,
                        lineHeight: 18,
                        color: '#555'
                    },
                    // 引导线拉长: 文字离扇区更远, 不再挤在一起
                    labelLine: {
                        length: 16,
                        length2: 24,
                        smooth: true
                    },
                    data: hasData ? typeData : [{ name: '暂无数据', value: 1, itemStyle: { color: 'var(--border-color)' } }]
                }
            ]
        };
        pieChart.setOption(optionPie);
        chartInstances.current.push(pieChart);
    }

    // 2. Trend Chart
    if (trendChartRef.current) {
        const trendChart = echarts.init(trendChartRef.current);
        
        // Prepare data
        const trends = stats.questionTrends || [];
        const dates = trends.map(item => item.date);
        const counts = trends.map(item => item.count);

        const optionTrend = {
            title: {
                text: '近7日提问趋势',
                left: 'center',
                textStyle: { fontSize: 16 }
            },
            tooltip: {
                trigger: 'axis'
            },
            grid: {
                left: '3%',
                right: '4%',
                bottom: '10%',
                containLabel: true
            },
            xAxis: {
                type: 'category',
                boundaryGap: false,
                data: dates.length > 0 ? dates : ['无数据']
            },
            yAxis: {
                type: 'value'
            },
            series: [
                {
                    name: '提问数',
                    type: 'line',
                    stack: 'Total',
                    smooth: true,
                    areaStyle: {
                        opacity: 0.3
                    },
                    emphasis: {
                        focus: 'series'
                    },
                    data: dates.length > 0 ? counts : [0]
                }
            ]
        };
        trendChart.setOption(optionTrend);
        chartInstances.current.push(trendChart);
    }
  };

  if (loading) return <div className="loading">加载中...</div>;
  if (!stats) return <div className="error">暂无数据</div>;

  return (
    <div className="dashboard-container">
      <h2 className="page-title">仪表盘</h2>
      
      {/* 核心指标卡片 */}
      <div className="stats-cards">
        <div className="stat-card">
          <div className="stat-value">{stats.userCount}</div>
          <div className="stat-label">用户总数</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.docCount}</div>
          <div className="stat-label">文档总数</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.qaCount}</div>
          <div className="stat-label">提问总数</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{(stats.hitRate || 0).toFixed(1)}%</div>
          <div className="stat-label">AI命中率</div>
        </div>
      </div>

      {/* 图表区域 */}
      <div className="charts-row" style={{ display: 'flex', gap: '20px', margin: '20px 0', height: '350px' }}>
          <div className="chart-container" style={{ flex: 1, background: '#fff', padding: '15px', borderRadius: '8px', boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
              <div ref={pieChartRef} style={{ width: '100%', height: '100%' }}></div>
          </div>
          <div className="chart-container" style={{ flex: 1, background: '#fff', padding: '15px', borderRadius: '8px', boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
              <div ref={trendChartRef} style={{ width: '100%', height: '100%' }}></div>
          </div>
      </div>

      <div className="dashboard-grid">
        {/* 热门文档 */}
        <div className="dashboard-section">
          <h3>🔥 热门文档</h3>
          <table className="data-table">
            <thead>
              <tr>
                <th>文档名称</th>
                <th>查看次数</th>
              </tr>
            </thead>
            <tbody>
              {stats.hotDocs && stats.hotDocs.length > 0 ? (
                stats.hotDocs.map((doc, index) => (
                  <tr key={index}>
                    <td>{doc.title || `文档ID:${doc.doc_id}`}</td>
                    <td>{doc.view_count}</td>
                  </tr>
                ))
              ) : (
                <tr><td colSpan="2" className="empty-text">暂无数据</td></tr>
              )}
            </tbody>
          </table>
        </div>

        {/* 热门问题 */}
        <div className="dashboard-section">
          <h3>💡 热门提问</h3>
          <table className="data-table">
             <thead>
                <tr>
                    <th>问题内容</th>
                    <th>提问次数</th>
                </tr>
             </thead>
             <tbody>
                {stats.topQuestions && stats.topQuestions.length > 0 ? (
                  stats.topQuestions.map((q, index) => (
                    <tr key={index}>
                      <td className="text-truncate" title={q.question} style={{maxWidth: '200px'}}>{q.question}</td>
                      <td>{q.count}</td>
                    </tr>
                  ))
                ) : (
                  <tr><td colSpan="2" className="empty-text">暂无数据</td></tr>
                )}
             </tbody>
          </table>
        </div>

        {/* 未命中问题 */}
        <div className="dashboard-section">
          <h3>❓ 未命中问题 (需优化)</h3>
          <table className="data-table">
             <thead>
                <tr>
                    <th>问题内容</th>
                    <th>频次</th>
                </tr>
             </thead>
             <tbody>
                {stats.unansweredQuestions && stats.unansweredQuestions.length > 0 ? (
                  stats.unansweredQuestions.map((q, index) => (
                    <tr key={index}>
                      <td className="text-truncate" title={q.question} style={{maxWidth: '200px', color: 'var(--danger)'}}>{q.question}</td>
                      <td>{q.count}</td>
                    </tr>
                  ))
                ) : (
                  <tr><td colSpan="2" className="empty-text">表现良好，暂无未命中</td></tr>
                )}
             </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
