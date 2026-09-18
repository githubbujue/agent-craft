import adminRequest from './admin'

/**
 * 评估看板统计 —— 检索充分性 / 答案质量 / 内容忠实度
 *
 * 后端一次返回全部指标(顶部总览 + 分档分布 + 趋势 + 低分清单 + 充分性分布),
 * 前端只需一次请求。
 *
 * @param lowThreshold 低分回答判定阈值(默认 0.8)
 * @param lowLimit     低分清单条数上限
 */
export const getEvalStats = (lowThreshold = 0.8, lowLimit = 10) => {
  return adminRequest.get('/agent/eval-stats', {
    params: { lowThreshold, lowLimit },
  })
}
