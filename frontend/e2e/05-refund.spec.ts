import { test, expect } from '@playwright/test'
import { ACCOUNTS, isAlipayAutomationReady, isHttpsStagingTargetReady } from './helpers/env'
import { apiGet, buildStorageState, loginViaApi, updateState, type ApiResult } from './helpers/api'
import { payOrderViaUi } from './helpers/alipay'
import { cancelPendingOrders, purchaseTicketViaUi, snapshotOrderIds, type OrderRecord } from './helpers/purchase'

interface RefundRecord {
  id: number
  orderId: number
  orderNo: string
  refundNo: string
  amount: number
  status: number
  reviewNote: string | null
  alipayRefundNo: string | null
}

/** 轮询我的退款单，直至出现目标订单的退款单 */
async function waitForRefund(token: string, orderId: number, timeoutMs: number): Promise<RefundRecord> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const api: ApiResult<RefundRecord[]> = await apiGet<RefundRecord[]>('/api/payment/refunds/my', token)
    const found = api.data.filter(refund => refund.orderId === orderId).sort((a, b) => b.id - a.id)
    if (found.length > 0) return found[0]
    await new Promise(resolve => setTimeout(resolve, 1500))
  }
  throw new Error(`等待订单 ${orderId} 的退款单超时（${timeoutMs}ms）`)
}

test('退款闭环：C端申请退款 → B端审核同意 → 沙箱退款到账', async ({ browser }) => {
  test.skip(!isAlipayAutomationReady(), '缺少沙箱买家凭据（OMNI_E2E_ALIPAY_BUYER_LOGON/PASSWORD/PAY_PASSWORD），跳过退款闭环')
  test.skip(!isHttpsStagingTargetReady(), '未配置 HTTPS staging，跳过真实退款')
  test.setTimeout(420_000)

  // ===== setup：自备已支付订单（购票 + 沙箱支付） =====
  const login = await loginViaApi(ACCOUNTS.user2.phone, ACCOUNTS.user2.password)
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()

  await cancelPendingOrders(login.token, ACCOUNTS.user2.userId)
  const beforeIds = await snapshotOrderIds(login.token)
  const order = await purchaseTicketViaUi(page, login.token, beforeIds)
  const paidOrder = await payOrderViaUi(page, login.token, order)
  expect(paidOrder.status).toBe(2)

  // ===== C 端：已支付订单申请退款 =====
  await page.goto('/orders')
  await page.getByRole('button', { name: '已支付' }).click()

  // 定位目标订单卡片
  const card = page.locator('div.rounded-3xl').filter({ hasText: order.orderNo }).first()
  await expect(card).toBeVisible({ timeout: 60_000 })

  // 打开申请退款弹窗
  await card.getByRole('button', { name: '申请退款' }).click()
  const refundDialog = page.locator('div.fixed.inset-0').filter({ hasText: '申请退款' })
  await expect(refundDialog).toBeVisible({ timeout: 30_000 })

  // 等待退款明细加载完成
  await expect(refundDialog.getByText('可退票数')).toBeVisible({ timeout: 30_000 })

  // 有座位则勾选第一个座位；无座位默认退款张数 1
  const seatList = refundDialog.locator('div.max-h-\\[132px\\]')
  if (await seatList.isVisible().catch(() => false)) {
    await seatList.locator('button').first().click()
  }

  // 退款原因
  await refundDialog.locator('textarea').fill('E2E 自动化退款验证')

  // 提交申请
  await refundDialog.getByRole('button', { name: '确认申请' }).click()
  await expect(refundDialog).toBeHidden({ timeout: 60_000 })

  // API 断言：退款单已创建（待审核 status=0）
  const refund = await waitForRefund(login.token, order.id, 60_000)
  expect(refund.status).toBe(0)
  expect(refund.amount).toBeGreaterThan(0)
  expect(refund.amount).toBeLessThanOrEqual(order.amount)

  await context.close()

  // ===== B 端：admin 审核同意（真实沙箱 alipay.trade.refund） =====
  const adminLogin = await loginViaApi(ACCOUNTS.admin.phone, ACCOUNTS.admin.password)
  const adminContext = await browser.newContext({ storageState: buildStorageState(adminLogin) })
  const adminPage = await adminContext.newPage()

  await adminPage.goto('/console/refunds')
  await expect(adminPage.getByRole('heading', { name: '退款审核' })).toBeVisible({ timeout: 60_000 })

  // 定位目标退款行
  const refundRow = adminPage.locator('tr').filter({ hasText: refund.refundNo }).first()
  await expect(refundRow).toBeVisible({ timeout: 60_000 })
  await expect(refundRow).toContainText('待审核')

  // 同意 → 确认弹窗 → 确认同意
  await refundRow.getByRole('button', { name: '同意', exact: true }).click()
  const reviewModal = adminPage.locator('div[role="dialog"], div.fixed.inset-0').filter({ hasText: '同意退款' }).last()
  await expect(reviewModal).toBeVisible({ timeout: 30_000 })
  await reviewModal.locator('textarea').fill('E2E 自动化审核通过')
  await reviewModal.getByRole('button', { name: '确认同意' }).click()
  await expect(reviewModal).toBeHidden({ timeout: 120_000 })

  // ===== 落库断言：退款成功 + 订单已退款 =====
  const deadline = Date.now() + 180_000
  let finalRefund: RefundRecord | null = null
  let finalOrder: OrderRecord | null = null
  while (Date.now() < deadline) {
    const refundApi: ApiResult<RefundRecord[]> = await apiGet<RefundRecord[]>('/api/payment/refunds/my', login.token)
    finalRefund = refundApi.data.find(item => item.id === refund.id) || null
    const orderApi: ApiResult<OrderRecord> = await apiGet<OrderRecord>(`/api/order/${order.id}`, login.token)
    finalOrder = orderApi.data
    if (finalRefund && finalRefund.status === 1 && finalOrder.status === 4) break
    await new Promise(resolve => setTimeout(resolve, 3000))
  }
  expect(finalRefund, '退款单未在超时内变为已退款').not.toBeNull()
  expect(finalRefund!.status).toBe(1)
  expect(finalRefund!.alipayRefundNo, '缺少支付宝退款流水号').toBeTruthy()
  expect(finalOrder!.status).toBe(4)

  updateState({ refundOrderNo: order.orderNo, refundNo: refund.refundNo })

  await adminContext.close()
})
