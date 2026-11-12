import type { Locator, Page } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * UI 通用操作 helper
 */

/**
 * Next.js dev 模式下 React hydration / Fast Refresh 可能晚于 fill，
 * 导致受控 input 值被重置。轮询 fill 直至值稳定保留。
 */
export async function fillStable(locator: Locator, value: string, timeoutMs = 15_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    await locator.fill(value)
    const current = await locator.inputValue()
    if (current === value) {
      await locator.page().waitForTimeout(250)
      const after = await locator.inputValue()
      if (after === value) return
    }
    await locator.page().waitForTimeout(400)
  }
  throw new Error(`fillStable 超时: 值未能稳定保留在 ${timeoutMs}ms 内`)
}

/**
 * 提交前兜底：确认所有字段值仍然在位（防 Fast Refresh 重挂载丢值），
 * 丢失的字段重新 fillStable。
 */
export async function ensureValues(fields: Array<[Locator, string]>): Promise<void> {
  for (const [locator, value] of fields) {
    const current = await locator.inputValue()
    if (current !== value) {
      await fillStable(locator, value)
    }
  }
}

/**
 * Next.js dev 首次访问会按需编译并触发 Fast Refresh 重挂载。
 * 预热：等待编译稳定后 reload 一次，再继续交互。
 */
export async function warmUpDevPage(page: Page, url: string, readyText: string): Promise<void> {
  await page.goto(url)
  await expect(page.getByText(readyText).first()).toBeVisible()
  await page.waitForTimeout(2500)
  await page.reload()
  await expect(page.getByText(readyText).first()).toBeVisible()
  await page.waitForTimeout(500)
}

/** 断言当前 URL 包含指定路径片段 */
export async function expectPath(page: Page, fragment: string): Promise<void> {
  await expect(page).toHaveURL(new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
}
