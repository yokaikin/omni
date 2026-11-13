import { test, expect } from '@playwright/test'
import { ACCOUNTS, isAlipayAutomationReady, isHttpsStagingTargetReady } from './helpers/env'
import { apiGet, apiGetInternal, buildStorageState, loginViaApi, readState, updateState, type ApiResult } from './helpers/api'
import { payOrderViaUi } from './helpers/alipay'
import { snapshotOrderIds, type OrderRecord } from './helpers/purchase'

test('支付闭环：沙箱收银台完成支付并确认落库', async ({ browser }) => {
  test.skip(!isAlipayAutomationReady(), '缺少沙箱买家凭据（OMNI_E2E_ALIPAY_BUYER_LOGON/PASSWORD/PAY_PASSWORD），跳过支付闭环')
  test.skip(!isHttpsStagingTargetReady(), '未配置 HTTPS staging，跳过真实支付')
  test.skip(!process.env.OMNI_E2E_INTERNAL_TOKEN, '缺少 staging internal token，无法核验支付宝异步通知落库')
  test.setTimeout(300_000)

  const login = await loginViaApi(ACCOUNTS.user.phone, ACCOUNTS.user.password)
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()

  // 只支付 03 本轮创建的订单（state.orderNo）：支付宝交易 timeout_express=15m，
  // 兜底取旧待支付订单会命中已过期交易（收银台报"交易因超时已失败"），必须紧随 03 运行
  const state = readState()
  expect(state.orderNo, '缺少本轮订单号，请先运行 03 购票用例').toBeTruthy()
  const myOrders: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', login.token)
  const targetOrder = myOrders.data.find(order => order.orderNo === state.orderNo && order.status === 1)
  if (!targetOrder) {
    throw new Error(`订单 ${state.orderNo} 不存在或已不是待支付状态（支付宝交易 15 分钟过期），请重跑 03 后立即运行 04`)
  }

  const paidOrder = await payOrderViaUi(page, login.token, targetOrder)
  expect(paidOrder.status).toBe(2)

  const callbackDeadline = Date.now() + 120_000
  let callbackStatus: ApiResult<{
    orderId: number
    paymentStatus: number
    tradeNo: string | null
    notifyTime: string | null
    signedNotificationReceived: boolean
  }> | null = null
  while (Date.now() < callbackDeadline) {
    callbackStatus = await apiGetInternal(`/api/payment/internal/alipay-notify-status/${targetOrder.id}`)
    if (callbackStatus.data?.signedNotificationReceived) break
    await page.waitForTimeout(3000)
  }
  expect(callbackStatus?.code, '无法读取 staging 支付宝回调验收状态').toBe(200)
  expect(callbackStatus?.data?.paymentStatus).toBe(1)
  expect(callbackStatus?.data?.tradeNo).toBeTruthy()
  expect(callbackStatus?.data?.notifyTime).toBeTruthy()
  expect(callbackStatus?.data?.signedNotificationReceived, '订单由主动查询确认，但没有观察到已验签的异步通知').toBe(true)

  // UI 断言：订单列表显示已支付
  await page.goto('/orders')
  await expect(page.getByText(targetOrder.orderNo).first()).toBeVisible({ timeout: 60_000 })

  updateState({ paidOrderNo: targetOrder.orderNo })

  await context.close()
})
