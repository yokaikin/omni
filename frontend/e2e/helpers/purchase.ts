import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import { execSync } from 'node:child_process'
import { BASE_URL, GATEWAY_URL, MAIN_ACTIVITY } from './env'
import { apiDelete, apiGet, apiPost, readState, updateState, type ApiResult } from './api'
import { splitOwnedPendingOrders } from './purchase-order-policy'

export interface OrderRecord {
  id: number
  orderNo: string
  sessionId: number
  ticketTypeId: number
  quantity: number
  amount: number
  status: number
}

export interface AttendeeRecord {
  id: number
  realName: string
  idNoMask: string
}

/**
 * 每次购票前动态新增唯一观演人：java-order 会拒绝"该观演人已购买本场次门票"，
 * 复用种子观演人会让 910010 场次只能买一次；新建观演人让用例无限可重复。
 */
export async function createUniqueAttendee(token: string): Promise<AttendeeRecord> {
  const stamp = `${Date.now().toString().slice(-9)}${Math.floor(Math.random() * 90 + 10)}`
  const realName = `E2E观演人${stamp}`
  const idNo = `110${stamp}0000X`
  const api: ApiResult<AttendeeRecord> = await apiPost<AttendeeRecord>(
    '/api/user/attendees',
    { realName, idType: 'ID_CARD', idNo, phone: `139${stamp.slice(-8)}` },
    token,
  )
  if (api.code !== 200 || !api.data?.id) {
    throw new Error(`新建观演人失败: code=${api.code}, message=${api.message}`)
  }
  return api.data
}

/** 在订单列表轮询，直至出现不属于 beforeIds 的新目标订单 */
export async function waitForNewOrder(
  token: string,
  beforeIds: Set<number>,
  timeoutMs: number,
): Promise<OrderRecord> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const api: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', token)
    const found = api.data
      .filter(order => !beforeIds.has(order.id))
      .filter(order => order.sessionId === MAIN_ACTIVITY.sessionId && order.ticketTypeId === MAIN_ACTIVITY.ticketTypeId)
      .sort((a, b) => b.id - a.id)
    if (found.length > 0) return found[0]
    await new Promise(resolve => setTimeout(resolve, 1500))
  }
  throw new Error(`等待新订单超时（${timeoutMs}ms）`)
}

/**
 * C 端 UI 购票主流程：活动页 → 选普通票 → 立即购买 → 选观演人 → 确认支付 → 等待订单创建。
 * 返回新创建的待支付订单。
 */
export async function purchaseTicketViaUi(page: Page, token: string, beforeIds: Set<number>): Promise<OrderRecord> {
  // 每次购票动态新增唯一观演人，规避"该观演人已购买本场次门票"的实名限购
  const attendee = await createUniqueAttendee(token)

  // 活动详情页
  await page.goto(`/activity/${MAIN_ACTIVITY.activityId}`)
  await expect(page.getByText('选择票档').first()).toBeVisible({ timeout: 60_000 })

  // 切换到目标票档：普通票 ¥80
  const generalTicket = page.getByRole('button', { name: /普通票/ })
  await expect(generalTicket).toBeVisible({ timeout: 30_000 })
  await generalTicket.click()

  // 立即购买 → 确认订单弹窗
  await page.getByRole('button', { name: '立即购买' }).click()
  await expect(page.getByRole('heading', { name: '确认订单' })).toBeVisible({ timeout: 30_000 })

  // 勾选本轮新建观演人（按姓名定位）+ 退票规则
  const attendeeCheckbox = page
    .locator('label.cursor-pointer', { hasText: attendee.realName })
    .locator('input[type="checkbox"]')
  await expect(attendeeCheckbox).toBeVisible({ timeout: 30_000 })
  await attendeeCheckbox.check()
  await page.locator('label:has-text("我已阅读退票规则") input[type="checkbox"]').check()

  // 确认支付（提交抢票请求）
  await page.getByRole('button', { name: '确认支付' }).click()

  // API 侧等待新订单落库（ORDER_CREATED 后 UI 会自动打开支付弹窗并关闭进度弹窗）
  const order = await waitForNewOrder(token, beforeIds, 120_000)
  expect(order.status).toBe(1)
  const ownedOrderIds = readState().e2eOwnedOrderIds || []
  updateState({ e2eOwnedOrderIds: [...new Set([...ownedOrderIds, order.id])] })

  // 支付弹窗应自动打开并显示新订单号
  const payModal = page.getByRole('dialog').filter({ hasText: '支付宝支付' })
  await expect(payModal).toBeVisible({ timeout: 60_000 })
  await expect(payModal.getByText(order.orderNo).first()).toBeVisible({ timeout: 30_000 })

  // 关闭支付弹窗（支付闭环由 04 单独覆盖）
  await payModal.getByRole('button', { name: '关闭支付弹窗' }).click()
  await expect(payModal).toBeHidden({ timeout: 15_000 })

  return order
}

/** 记录当前订单 id 集合（购票前调用） */
export async function snapshotOrderIds(token: string): Promise<Set<number>> {
  const api: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', token)
  return new Set(api.data.map(order => order.id))
}

/**
 * 仅清理 E2E 状态文件登记的本用例待支付订单，保护其他本地订单。
 */
export async function cancelPendingOrders(token: string, userId: number): Promise<void> {
  const api: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', token)
  const pending = api.data.filter(
    order => order.status === 1 && order.sessionId === MAIN_ACTIVITY.sessionId && order.ticketTypeId === MAIN_ACTIVITY.ticketTypeId,
  )
  const state = readState()
  const { owned: ownedPending, unowned: unownedPending } = splitOwnedPendingOrders(
    pending,
    state.e2eOwnedOrderIds || [],
  )
  if (unownedPending.length > 0) {
    throw new Error(`发现 ${unownedPending.length} 笔非本 E2E 创建的待支付订单，为保护本地数据已停止购票；请自行处理后重试`)
  }
  for (const order of ownedPending) {
    const result = await apiDelete(`/api/order/${order.id}`, token)
    if (result.code !== 200) {
      throw new Error(`取消本 E2E 待支付订单失败: orderId=${order.id}, code=${result.code}`)
    }
  }
  updateState({ e2eOwnedOrderIds: (state.e2eOwnedOrderIds || []).filter(id => !ownedPending.some(order => order.id === id)) })
  if (ownedPending.length > 0) clearGrabUserHold(userId)
}

/** 只在本地 E2E 环境清除本机 Docker Redis 中对应专用测试账号的 hold。 */
export function clearGrabUserHold(userId: number): void {
  const localHosts = new Set(['localhost', '127.0.0.1', '::1'])
  if (![BASE_URL, GATEWAY_URL].every(endpoint => localHosts.has(new URL(endpoint).hostname))) return
  const key = `grab:user-hold:${userId}:${MAIN_ACTIVITY.sessionId}:${MAIN_ACTIVITY.ticketTypeId}`
  try {
    execSync(`docker exec omni-redis redis-cli DEL ${key}`, { stdio: 'ignore' })
  } catch {
    // Redis 容器不可达时忽略：hold 到期后自然失效
  }
}
