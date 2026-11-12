import { test, expect } from '@playwright/test'
import { ACCOUNTS, MAIN_ACTIVITY } from './helpers/env'
import { apiGet, apiPost, buildStorageState, loginViaApi, readState, updateState, type ApiResult } from './helpers/api'
import type { OrderRecord } from './helpers/purchase'

interface TicketWalletItem {
  ticketId: number
  ticketNo: string
  orderId: number
  sessionId: number
  status: number
  checkedInAt?: string | null
}

interface TicketEntryCode {
  ticketId: number
  entryCode: string
  expiresAt: string
}

test('核销闭环：C端生成入场码 → B端手动核销 → 电子票已验票', async ({ browser }) => {
  const state = readState()
  test.skip(!state.paidOrderNo, '缺少已支付订单（依赖 04 支付闭环产出），跳过核销闭环')
  test.setTimeout(240_000)

  const login = await loginViaApi(ACCOUNTS.user.phone, ACCOUNTS.user.password)

  // 定位 04 支付的订单对应电子票（未入场 status=1）
  const myOrders: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', login.token)
  const targetOrder = myOrders.data.find(order => order.orderNo === state.paidOrderNo)
  if (!targetOrder || targetOrder.status !== 2) {
    test.skip(true, '已支付订单不存在或状态已变化，跳过核销闭环')
    return
  }

  const tickets: ApiResult<TicketWalletItem[]> = await apiGet<TicketWalletItem[]>('/api/order/tickets', login.token)
  const targetTickets = tickets.data.filter(ticket => ticket.orderId === targetOrder.id && ticket.status === 1)
  expect(targetTickets.length, '该订单没有未入场的电子票').toBeGreaterThan(0)
  const ticket = targetTickets[0]

  // ===== C 端：票夹生成入场码 =====
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()
  await page.goto('/tickets')
  await expect(page.getByRole('heading', { name: '我的票夹' })).toBeVisible({ timeout: 60_000 })

  // 票卡片可见
  const ticketCard = page.locator('div.rounded-lg.border.border-\\[\\#eee\\]').filter({ hasText: ticket.ticketNo }).first()
  await expect(ticketCard).toBeVisible({ timeout: 60_000 })

  // 点击入场码 → QR 弹窗
  await ticketCard.getByRole('button', { name: '入场码' }).click()
  const entryDialog = page.locator('div.fixed.inset-0').filter({ hasText: '入场码' })
  await expect(entryDialog).toBeVisible({ timeout: 30_000 })
  // QR 码为 svg（弹窗内还有关闭等图标，取第一个即二维码）
  await expect(entryDialog.locator('svg').first()).toBeVisible()

  // 通过 API 生成同票入场码（与 UI 按钮等价），供 B 端核销输入
  const entry: ApiResult<TicketEntryCode> = await apiPost<TicketEntryCode>(
    `/api/order/tickets/${ticket.ticketId}/entry-code`,
    {},
    login.token,
  )
  expect(entry.data.entryCode).toBeTruthy()

  await entryDialog.getByRole('button', { name: '关闭' }).click()
  await expect(entryDialog).toBeHidden({ timeout: 15_000 })
  await context.close()

  // ===== B 端：手动应急核验 =====
  const adminLogin = await loginViaApi(ACCOUNTS.admin.phone, ACCOUNTS.admin.password)
  const adminContext = await browser.newContext({ storageState: buildStorageState(adminLogin) })
  const adminPage = await adminContext.newPage()

  await adminPage.goto('/console/check-in')
  await expect(adminPage.getByText('手动应急核验')).toBeVisible({ timeout: 60_000 })

  // 场次编号
  await adminPage.getByPlaceholder('请输入场次编号').fill(String(MAIN_ACTIVITY.sessionId))

  // 输入票码核销
  await adminPage.getByPlaceholder('请输入票码').fill(entry.data.entryCode)
  await adminPage.getByRole('button', { name: '立即核销' }).click()

  // 成功 toast（3 秒消失，需立即断言）
  await expect(adminPage.getByText(`核销成功：${ticket.ticketNo}`)).toBeVisible({ timeout: 30_000 })

  // 核验记录表：查询后包含该票号
  await adminPage.getByRole('button', { name: '查询' }).click()
  await expect(adminPage.locator('tbody').getByText(ticket.ticketNo).first()).toBeVisible({ timeout: 60_000 })

  // ===== API 断言：票已验票 =====
  const deadline = Date.now() + 60_000
  let finalTicket: TicketWalletItem | null = null
  while (Date.now() < deadline) {
    const after: ApiResult<TicketWalletItem[]> = await apiGet<TicketWalletItem[]>('/api/order/tickets', login.token)
    finalTicket = after.data.find(item => item.ticketId === ticket.ticketId) || null
    if (finalTicket && finalTicket.status === 2 && finalTicket.checkedInAt) break
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  expect(finalTicket, '电子票未在超时内变为已验票').not.toBeNull()
  expect(finalTicket!.status).toBe(2)
  expect(finalTicket!.checkedInAt).toBeTruthy()

  updateState({ checkInTicketNo: ticket.ticketNo })

  await adminContext.close()
})
