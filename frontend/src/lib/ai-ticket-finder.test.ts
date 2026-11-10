import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError } from './api.ts'
import {
  buildFinderPurchaseHref,
  formatFinderConditions,
  getFinderErrorMessage,
} from './ai-ticket-finder.ts'
import type { TicketFinderResult, TicketIntent } from '@/types/api'

test('only renders explicitly provided finder conditions', () => {
  const intent: TicketIntent = {
    keyword: '演唱会',
    city: '东京',
    dateFrom: '2026-09-19',
    dateTo: '2026-09-20',
    preferredDate: null,
    minPrice: null,
    maxPrice: 500,
    peopleCount: 2,
    needAdjacentSeats: null,
    saleStatus: null,
    isSupportSeat: null,
    realNameRequired: null,
    sortPreference: null,
  }

  assert.deepEqual(formatFinderConditions(intent), [
    { label: '关键词', value: '演唱会' },
    { label: '城市', value: '东京' },
    { label: '日期', value: '2026-09-19 ～ 2026-09-20' },
    { label: '人数', value: '2 人' },
    { label: '预算', value: '≤ ¥500' },
  ])
})

test('builds the existing activity detail route with real purchase identifiers', () => {
  const result: TicketFinderResult = {
    activityId: 101,
    activityName: '东京演唱会',
    sessionId: 202,
    sessionStartTime: '2026-09-19T19:30:00',
    venueId: 303,
    venueName: '东京巨蛋',
    city: '东京',
    ticketTypeId: 404,
    ticketTypeName: 'VIP',
    price: 500,
    availableQuantity: 2,
    saleStatus: 'on_sale',
  }

  assert.equal(
    buildFinderPurchaseHref(result),
    '/activity/101?sessionId=202&ticketTypeId=404',
  )
})

test('maps technical finder failures to friendly Chinese messages', () => {
  assert.equal(getFinderErrorMessage(new ApiError(401, '登录状态已失效，请重新登录')), '请先登录后使用 AI 智能找票')
  assert.equal(getFinderErrorMessage(new ApiError(403, '没有权限执行该操作')), '当前账号暂时不能使用 AI 智能找票')
  assert.equal(getFinderErrorMessage(new ApiError(404, '未找到相关记录')), '暂时找不到相关票务信息，请调整条件后重试')
  assert.equal(getFinderErrorMessage(new ApiError(429, '请求过于频繁')), '当前请求较多，请稍后再试')
  assert.equal(getFinderErrorMessage(new ApiError(500, '服务暂不可用，请稍后重试')), 'AI 找票服务暂时不可用，请稍后重试')
  assert.equal(getFinderErrorMessage(new ApiError(502, '网关错误')), 'AI 返回结果未通过系统校验，请重新尝试。')
  assert.equal(getFinderErrorMessage(new ApiError(504, '服务响应超时，请稍后重试')), 'AI 查询时间较长，请稍后重试。')
  assert.equal(getFinderErrorMessage(new ApiError(503, '服务暂不可用，请稍后重试')), 'AI 找票服务暂时不可用，请稍后再试。')
  assert.equal(getFinderErrorMessage(new Error('Exception: stack trace')), '找票失败，请稍后重试')
})
