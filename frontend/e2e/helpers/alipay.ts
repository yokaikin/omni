import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import { ALIPAY_BUYER_LOGON, ALIPAY_PAY_PASSWORD } from './env'
import { apiDelete, apiGet, type ApiResult } from './api'
import type { OrderRecord } from './purchase'

/**
 * 沙箱收银台弹窗内完成付款（真实页面流程，v3-v12 逆向验证）：
 * 1. auth.htm「账户付款」页：账号（服务端可能预填锁定为 oar***@sandbox.com，可不填）+ 支付密码 → 下一步
 * 2. lightPayCashier.htm 确认页：等安全检测通过 → 6 位支付密码 → 确认付款
 * 注意：支付宝密码控件必须用 pressSequentially（fill 无效）；
 * 输入确认页密码后安全控件会清空提交按钮的 name 属性，需按 value 定位。
 */
async function payOnSandboxCashier(popup: Page): Promise<void> {
  await popup.waitForLoadState('domcontentloaded')
  // 等收银台安全控件初始化，避免 pressSequentially 落在未就绪控件上
  await popup.waitForTimeout(5_000)

  // auth.htm：账号输入框（可能被服务端预填锁定为只读隐藏，此时跳过）
  const loginIdInput = popup.locator('input[name="loginId"]')
  if (await loginIdInput.isVisible().catch(() => false)) {
    await loginIdInput.click()
    await loginIdInput.pressSequentially(ALIPAY_BUYER_LOGON, { delay: 60 })
    await loginIdInput.blur()
  }
  const payPasswordInput = popup.locator('input[name="payPasswd_rsainput"]')
  await expect(payPasswordInput).toBeVisible({ timeout: 30_000 })
  await payPasswordInput.click()
  await payPasswordInput.pressSequentially(ALIPAY_PAY_PASSWORD, { delay: 120 })
  // 输入后必须 blur + 等待：安全控件内部状态就绪前点"下一步"会被静默拦截（无网络请求、无导航）
  await payPasswordInput.blur()
  await popup.waitForTimeout(4_000)
  // 「下一步」是 span 文本，不是原生 button
  await popup.locator('text=下一步').first().click()

  // lightPayCashier.htm：安全检测完成标志（两处文本，取第一个可见）
  await popup.waitForURL(/lightPayCashier\.htm/, { timeout: 60_000 })
  await expect(popup.locator('text=安全设置检测成功').first()).toBeVisible({ timeout: 60_000 })

  const confirmPassword = popup.locator('input[name="payPassword_rsainput"]')
  await expect(confirmPassword).toBeVisible({ timeout: 30_000 })
  await confirmPassword.click()
  await confirmPassword.pressSequentially(ALIPAY_PAY_PASSWORD, { delay: 120 })
  await confirmPassword.blur()
  await popup.waitForTimeout(3_000)
  // 输满密码后 input[name="J_authSubmit"] 的 name 属性被控件清空，按 value 定位
  const confirmButton = popup.locator('input[value="确认付款"]')
  await expect(confirmButton).toBeVisible({ timeout: 30_000 })
  await confirmButton.click()
}

/**
 * C 端 UI 支付主流程：订单列表 → 立即支付 → 沙箱收银台付款 → sync 确认。
 * 返回支付后的订单（status=2）。
 */
export async function payOrderViaUi(page: Page, token: string, order: OrderRecord): Promise<OrderRecord> {
  // 取消除目标订单外的旧待支付订单：旧订单的支付宝交易已过 15m 有效期，
  // 留在列表会让"待支付 tab 第一个立即支付"命中过期交易
  const allOrders: ApiResult<OrderRecord[]> = await apiGet<OrderRecord[]>('/api/order/my', token)
  for (const stale of allOrders.data.filter(o => o.status === 1 && o.id !== order.id)) {
    await apiDelete(`/api/order/${stale.id}`, token)
  }

  await page.goto('/orders')
  await expect(page.getByText('我的订单').first()).toBeVisible({ timeout: 60_000 })

  // 待支付 tab → 目标订单的立即支付按钮（最新待支付订单排最前）
  await page.getByRole('button', { name: '待支付' }).click()
  const payButton = page.getByRole('button', { name: '立即支付' }).first()
  await expect(payButton).toBeVisible({ timeout: 30_000 })
  await payButton.click()

  // 支付弹窗（应显示目标订单号）
  const payModal = page.getByRole('dialog')
  await expect(payModal).toBeVisible({ timeout: 30_000 })
  await expect(payModal.getByText(order.orderNo)).toBeVisible()

  // 打开支付宝支付页面（新标签页 → 沙箱收银台）
  const [popup] = await Promise.all([
    page.waitForEvent('popup', { timeout: 30_000 }),
    payModal.getByRole('link', { name: '打开支付宝支付页面' }).click(),
  ])
  expect(popup).toBeTruthy()

  await payOnSandboxCashier(popup)

  // 支付完成后收银台跳回 return_url（localhost:3000/payment/result）
  await popup.waitForURL(/localhost:3000\/payment\/result/, { timeout: 120_000 }).catch(() => undefined)

  // 支付弹窗 3 秒轮询 sync 自动确认并关闭
  await expect(payModal).toBeHidden({ timeout: 60_000 })
  await popup.close().catch(() => undefined)

  // API 断言：订单已支付
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    const api: ApiResult<OrderRecord> = await apiGet<OrderRecord>(`/api/order/${order.id}`, token)
    if (api.data.status === 2) {
      return api.data
    }
    await page.waitForTimeout(3000)
  }
  throw new Error(`订单 ${order.orderNo} 未在超时内变为已支付`)
}
