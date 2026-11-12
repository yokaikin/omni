import { test, expect } from '@playwright/test'
import { updateState } from './helpers/api'
import { fillStable, ensureValues, warmUpDevPage } from './helpers/ui'

/**
 * 闭环一：注册登录
 * 1. 真实浏览器走 /register 完整注册（图形验证码通过 canvas fillText hook 捕获）
 * 2. 注册成功跳登录页，用新账号 UI 登录
 * 3. 断言登录态与角色跳转
 */

test.describe('注册登录闭环', () => {
  let registeredPhone: string
  const registeredPassword = 'E2ePass123456'

  test('注册新账号并完成登录', async ({ page }) => {
    registeredPhone = `139${String(Math.floor(Math.random() * 90000000) + 10000000)}`

    // 捕获 canvas 逐字符绘制的图形验证码
    await page.addInitScript(() => {
      const original = CanvasRenderingContext2D.prototype.fillText
      ;(window as unknown as Record<string, unknown>).__capturedCaptcha = ''
      CanvasRenderingContext2D.prototype.fillText = function (
        this: CanvasRenderingContext2D,
        text: string,
        x: number,
        y: number,
      ) {
        if (typeof text === 'string' && text.length === 1) {
          const w = window as unknown as Record<string, unknown>
          w.__capturedCaptcha = String(w.__capturedCaptcha || '') + text
        }
        return original.call(this, text, x, y)
      }
    })

    await warmUpDevPage(page, '/register', '注册万象账号')

    const phoneInput = page.getByPlaceholder('请输入手机号')
    const passwordInput = page.getByRole('textbox', { name: '设置密码' })
    const confirmPasswordInput = page.getByRole('textbox', { name: '确认密码' })

    await fillStable(phoneInput, registeredPhone)
    await fillStable(passwordInput, registeredPassword)
    await fillStable(confirmPasswordInput, registeredPassword)

    // 组件挂载时已绘制验证码，直接读取 canvas fillText hook 捕获值
    let captcha = (await page.evaluate(
      () => (window as unknown as Record<string, string>).__capturedCaptcha || '',
    )) as string
    if (captcha.length < 4) {
      await page.locator('canvas').first().click({ force: true })
      await page.waitForTimeout(300)
      captcha = (await page.evaluate(
        () => (window as unknown as Record<string, string>).__capturedCaptcha || '',
      )) as string
    }
    expect(captcha.length).toBeGreaterThanOrEqual(4)
    await page.getByPlaceholder('请输入右侧图形验证码').fill(captcha.slice(-4))

    await page.locator('#agreement').check()
    // 提交前兜底校验，防 Fast Refresh 重挂载丢值
    await ensureValues([
      [phoneInput, registeredPhone],
      [passwordInput, registeredPassword],
      [confirmPasswordInput, registeredPassword],
    ])
    await page.getByRole('button', { name: '同意并注册' }).click()

    await expect(page.getByText('注册成功，请登录')).toBeVisible({ timeout: 30_000 })
    // 关闭全局提示弹窗（若存在确认按钮）
    const confirm = page.getByRole('button', { name: /确认|确定|知道了|好的/ })
    if (await confirm.isVisible().catch(() => false)) {
      await confirm.first().click()
    }
    await expect(page).toHaveURL(/\/login/, { timeout: 30_000 })

    // 使用新账号走 UI 登录
    await fillStable(page.getByPlaceholder('请输入手机号或邮箱'), registeredPhone)
    await fillStable(page.getByPlaceholder('请输入登录密码'), registeredPassword)
    await page.getByRole('button', { name: '登录', exact: true }).click()

    // 普通用户登录成功跳首页
    await expect(page).not.toHaveURL(/\/login/, { timeout: 45_000 })
    const token = await page.evaluate(() => localStorage.getItem('omni_token'))
    expect(token).toBeTruthy()
    const storedUser = await page.evaluate(() => JSON.parse(localStorage.getItem('omni_user') || '{}'))
    expect(storedUser.phone).toBe(registeredPhone)
    expect(storedUser.role).toBe('user')

    updateState({ registeredPhone, registeredPassword })
  })

  test('错误密码登录被拒绝', async ({ page }) => {
    await page.goto('/login')
    await fillStable(page.getByPlaceholder('请输入手机号或邮箱'), '13900000001')
    await fillStable(page.getByPlaceholder('请输入登录密码'), 'wrong-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.locator('div.bg-red-50').first()).toBeVisible({ timeout: 30_000 })
    await expect(page).toHaveURL(/\/login/)
  })
})
