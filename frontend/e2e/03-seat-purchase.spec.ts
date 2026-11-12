import { test, expect } from '@playwright/test'
import { ACCOUNTS, MAIN_ACTIVITY } from './helpers/env'
import { buildStorageState, loginViaApi, updateState } from './helpers/api'
import { warmUpDevPage } from './helpers/ui'
import { purchaseTicketViaUi, snapshotOrderIds, cancelPendingOrders } from './helpers/purchase'

test('C端座位购买闭环：选票下单生成待支付订单', async ({ browser }) => {
  test.setTimeout(300_000)

  const login = await loginViaApi(ACCOUNTS.user.phone, ACCOUNTS.user.password)
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()

  // 清理历史遗留的待支付订单（避免限购 LIMITED 拦截），再快照订单集合
  await cancelPendingOrders(login.token)
  const beforeIds = await snapshotOrderIds(login.token)

  await warmUpDevPage(page, `/activity/${MAIN_ACTIVITY.activityId}`, '选择票档')

  const order = await purchaseTicketViaUi(page, login.token, beforeIds)
  expect(order.orderNo).toBeTruthy()
  expect(order.quantity).toBe(1)
  expect(Number(order.amount)).toBe(MAIN_ACTIVITY.price)

  // 订单列表可见
  await page.goto('/orders')
  await expect(page.getByText(order.orderNo).first()).toBeVisible({ timeout: 60_000 })

  updateState({ orderId: order.id, orderNo: order.orderNo })

  await context.close()
})
