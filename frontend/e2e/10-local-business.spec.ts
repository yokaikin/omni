import { test, expect } from '@playwright/test'
import { ACCOUNTS, BASE_URL, GATEWAY_URL } from './helpers/env'
import { apiGet, apiPost, buildStorageState, loginViaApi, updateState, type ApiResult } from './helpers/api'
import { cancelPendingOrders, purchaseTicketViaUi, snapshotOrderIds, type OrderRecord } from './helpers/purchase'
import { warmUpDevPage } from './helpers/ui'

interface TicketWalletItem {
  ticketId: number
  ticketNo: string
  orderId: number
  status: number
  checkedInAt?: string | null
}

interface TicketEntryCode {
  ticketId: number
  entryCode: string
}

interface LocalPaymentResponse {
  orderId: number
  orderNo: string
  paymentNo: string
  orderStatus: number
  message: string
}

const loopbackHosts = new Set(['localhost', '127.0.0.1', '::1'])
const isLocalE2eTarget = [BASE_URL, GATEWAY_URL].every(endpoint => loopbackHosts.has(new URL(endpoint).hostname))

test('本地模拟短信登录：发送验证码 → 使用固定验证码登录', async ({ page }) => {
  test.skip(!isLocalE2eTarget, '本地模拟短信用例只允许访问本机前端和 Gateway')

  await warmUpDevPage(page, '/login', '密码登录')
  await page.getByRole('button', { name: '验证码登录' }).click()
  await page.getByPlaceholder('请输入手机号').fill(ACCOUNTS.user.phone)
  await page.getByRole('button', { name: '获取验证码' }).click()
  await expect(page.getByText('验证码已发送，请按短信提示输入。')).toBeVisible({ timeout: 30_000 })
  await page.getByPlaceholder('请输入验证码').fill('666666')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).not.toHaveURL(/\/login/, { timeout: 45_000 })
  await expect.poll(() => page.evaluate(() => localStorage.getItem('omni_token'))).toBeTruthy()
})

/**
 * 本地业务闭环不依赖短信供应商、HTTPS 回调或支付宝买家账号。
 * 购票仍走浏览器，支付确认只调用本地受配置保护的模拟支付接口，随后回到页面和 API 验证业务状态。
 */
test('本地业务闭环：浏览器购票 → 本地支付确认 → 订单与票夹状态一致', async ({ browser }) => {
  test.skip(!isLocalE2eTarget, '本地模拟支付用例只允许访问本机前端和 Gateway')
  test.setTimeout(300_000)

  const login = await loginViaApi(ACCOUNTS.user.phone, ACCOUNTS.user.password)
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()

  // 只清理本 E2E 创建的待支付订单，保护用户已有本地订单。
  await cancelPendingOrders(login.token, ACCOUNTS.user.userId)
  const beforeIds = await snapshotOrderIds(login.token)
  const order = await purchaseTicketViaUi(page, login.token, beforeIds)

  const payment: ApiResult<LocalPaymentResponse> = await apiPost<LocalPaymentResponse>(
    '/api/payment/mock/pay',
    { orderId: order.id },
    login.token,
  )
  expect(payment.code, `本地支付确认失败：${payment.message}`).toBe(200)
  expect(payment.data.orderId).toBe(order.id)
  expect(payment.data.orderStatus).toBe(2)
  expect(payment.data.paymentNo).toBeTruthy()

  const deadline = Date.now() + 60_000
  let paidOrder: OrderRecord | undefined
  while (Date.now() < deadline) {
    const orders: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', login.token)
    paidOrder = orders.data.find(item => item.id === order.id)
    if (paidOrder?.status === 2) break
    await new Promise(resolve => setTimeout(resolve, 1500))
  }
  expect(paidOrder?.status, '本地支付后订单未变为已支付').toBe(2)

  // 页面状态断言：订单列表显示目标订单和已支付状态。
  await page.goto('/orders')
  const orderCard = page.locator('div.rounded-3xl').filter({ hasText: order.orderNo }).first()
  await expect(orderCard).toBeVisible({ timeout: 60_000 })
  await expect(orderCard).toContainText('已支付')

  // 后端状态断言：支付确认已生成电子票，票夹页面可见同一票号。
  const tickets: ApiResult<TicketWalletItem[]> = await apiGet<TicketWalletItem[]>('/api/order/tickets', login.token)
  const ticket = tickets.data.find(item => item.orderId === order.id && item.status === 1)
  expect(ticket, '本地支付后未生成可用电子票').toBeTruthy()

  await page.goto('/tickets')
  await expect(page.getByRole('heading', { name: '我的票夹' })).toBeVisible({ timeout: 60_000 })
  const ticketCard = page.locator('div.rounded-lg.border.border-\\[\\#eee\\]').filter({ hasText: ticket!.ticketNo }).first()
  await expect(ticketCard).toBeVisible({ timeout: 60_000 })
  await ticketCard.getByRole('button', { name: '入场码' }).click()
  const entryDialog = page.locator('div.fixed.inset-0').filter({ hasText: '入场码' })
  await expect(entryDialog).toBeVisible({ timeout: 30_000 })
  await expect(entryDialog.locator('svg').first()).toBeVisible()

  const entry: ApiResult<TicketEntryCode> = await apiPost<TicketEntryCode>(
    `/api/order/tickets/${ticket!.ticketId}/entry-code`,
    {},
    login.token,
  )
  expect(entry.code).toBe(200)
  expect(entry.data.entryCode).toBeTruthy()
  await entryDialog.getByRole('button', { name: '关闭' }).click()
  await expect(entryDialog).toBeHidden({ timeout: 15_000 })

  await context.close()

  // 后台手动核销并回读电子票状态，确认本地演示链路完成履约。
  const adminLogin = await loginViaApi(ACCOUNTS.admin.phone, ACCOUNTS.admin.password)
  const adminContext = await browser.newContext({ storageState: buildStorageState(adminLogin) })
  const adminPage = await adminContext.newPage()
  await adminPage.goto('/console/check-in')
  await expect(adminPage.getByText('手动应急核验')).toBeVisible({ timeout: 60_000 })
  await adminPage.getByPlaceholder('请输入场次编号').fill(String(order.sessionId))
  await adminPage.getByPlaceholder('请输入票码').fill(entry.data.entryCode)
  await adminPage.getByRole('button', { name: '立即核销' }).click()
  await expect(adminPage.getByText(`核销成功：${ticket!.ticketNo}`)).toBeVisible({ timeout: 30_000 })

  const checkedInTickets: ApiResult<TicketWalletItem[]> = await apiGet<TicketWalletItem[]>('/api/order/tickets', login.token)
  const checkedInTicket = checkedInTickets.data.find(item => item.ticketId === ticket!.ticketId)
  expect(checkedInTicket?.status).toBe(2)
  expect(checkedInTicket?.checkedInAt).toBeTruthy()

  updateState({ orderId: order.id, orderNo: order.orderNo, paidOrderNo: order.orderNo, ticketCodes: [ticket!.ticketNo] })
  await adminContext.close()
})
