import { test, expect } from '@playwright/test'
import { ACCOUNTS, isAlipayAutomationReady } from './helpers/env'
import { apiGet, buildStorageState, loginViaApi, readState, updateState, type ApiResult } from './helpers/api'
import { payOrderViaUi } from './helpers/alipay'
import { snapshotOrderIds, type OrderRecord } from './helpers/purchase'

test('支付闭环：沙箱收银台完成支付并确认落库', async ({ browser }) => {
  test.skip(!isAlipayAutomationReady(), '缺少沙箱买家凭据（OMNI_E2E_ALIPAY_BUYER_LOGON/PASSWORD/PAY_PASSWORD），跳过支付闭环')
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

  // UI 断言：订单列表显示已支付
  await page.goto('/orders')
  await expect(page.getByText(targetOrder.orderNo).first()).toBeVisible({ timeout: 60_000 })

  updateState({ paidOrderNo: targetOrder.orderNo })

  await context.close()
})
