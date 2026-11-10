import { ApiError } from './api.ts'
import type { TicketFinderResult, TicketIntent } from '@/types/api'

export const AI_TICKET_FINDER_QUICK_EXAMPLES = [
  '上海周末的演唱会',
  '广州300元以内的话剧',
  '北京近期适合两个人看的演出',
] as const

export interface FinderCondition {
  label: string
  value: string
}

export function formatFinderConditions(intent: TicketIntent | null | undefined): FinderCondition[] {
  if (!intent) return []

  const conditions: FinderCondition[] = []
  if (intent.keyword) conditions.push({ label: '关键词', value: intent.keyword })
  if (intent.city) conditions.push({ label: '城市', value: intent.city })
  if (intent.dateFrom && intent.dateTo) {
    conditions.push({ label: '日期', value: intent.dateFrom === intent.dateTo ? intent.dateFrom : `${intent.dateFrom} ～ ${intent.dateTo}` })
  } else if (intent.dateFrom) {
    conditions.push({ label: '日期', value: intent.dateFrom })
  } else if (intent.dateTo) {
    conditions.push({ label: '日期', value: intent.dateTo })
  }
  if (intent.peopleCount != null) conditions.push({ label: '人数', value: `${intent.peopleCount} 人` })
  if (intent.minPrice != null && intent.maxPrice != null) {
    conditions.push({ label: '预算', value: `¥${intent.minPrice} ～ ¥${intent.maxPrice}` })
  } else if (intent.maxPrice != null) {
    conditions.push({ label: '预算', value: `≤ ¥${intent.maxPrice}` })
  } else if (intent.minPrice != null) {
    conditions.push({ label: '预算', value: `≥ ¥${intent.minPrice}` })
  }
  if (intent.needAdjacentSeats != null) {
    conditions.push({ label: '连座', value: intent.needAdjacentSeats ? '需要连座' : '不要求连座' })
  }
  if (intent.saleStatus) conditions.push({ label: '销售状态', value: formatSaleStatus(intent.saleStatus) })
  if (intent.isSupportSeat != null) conditions.push({ label: '选座', value: intent.isSupportSeat ? '支持选座' : '无需选座' })
  if (intent.realNameRequired != null) conditions.push({ label: '实名', value: intent.realNameRequired ? '实名制' : '非实名制' })
  return conditions
}

function formatSaleStatus(value: string) {
  if (value === 'on_sale') return '售票中'
  if (value === 'coming_soon') return '待开票'
  if (value === 'sold_out') return '已售罄'
  return value
}

export function buildFinderPurchaseHref(result: TicketFinderResult) {
  const params = new URLSearchParams({
    sessionId: String(result.sessionId),
    ticketTypeId: String(result.ticketTypeId),
  })
  return `/activity/${result.activityId}?${params.toString()}`
}

export function getFinderErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 401) return '请先登录后使用 AI 智能找票'
    if (error.code === 403) return '当前账号暂时不能使用 AI 智能找票'
    if (error.code === 400) return error.message || '找票条件不正确，请调整后重试'
    if (error.code === 404) return '暂时找不到相关票务信息，请调整条件后重试'
    if (error.code === 429) return '当前请求较多，请稍后再试'
    if (error.code === 504) return 'AI 查询时间较长，请稍后重试。'
    if (error.message.includes('超时')) return '找票服务响应超时，请稍后重试'
    if (error.code === 502) return 'AI 返回结果未通过系统校验，请重新尝试。'
    if (error.code === 503) return 'AI 找票服务暂时不可用，请稍后再试。'
    if (error.code === 500) return 'AI 找票服务暂时不可用，请稍后重试'
  }
  return '找票失败，请稍后重试'
}
