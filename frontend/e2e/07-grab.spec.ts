import { test, expect } from '@playwright/test'
import { ACCOUNTS } from './helpers/env'
import { apiGet, buildStorageState, loginViaApi, updateState, type ApiResult } from './helpers/api'

interface WaitlistEntry {
  id: number
  sessionId: number
  ticketTypeId: number
  status: string
  rank?: number | null
}

/** 售罄场次：加入候补场景使用（activity 900116 李泉爵士流行音乐会 天津站，session 910116 票已全部售罄） */
const SOLD_OUT_ACTIVITY = {
  activityId: 900116,
  sessionId: 910116,
  ticketTypeId: 920348, // 普通票
}

test('候补闭环：售罄场次购买失败 → 加入候补 → 查看候补 → 取消候补', async ({ browser }) => {
  test.setTimeout(300_000)

  const login = await loginViaApi(ACCOUNTS.user.phone, ACCOUNTS.user.password)
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()

  // ===== 售罄活动页：选中售罄票档 → 页面直接显示"当前票档暂不可购买" + 加入候补入口 =====
  await page.goto(`/activity/${SOLD_OUT_ACTIVITY.activityId}`)
  await expect(page.getByText('选择票档').first()).toBeVisible({ timeout: 60_000 })

  const generalTicket = page.getByRole('button', { name: /普通票/ })
  await expect(generalTicket).toBeVisible({ timeout: 30_000 })
  await generalTicket.click()

  // 售罄提示出现
  await expect(page.getByText('当前票档暂不可购买')).toBeVisible({ timeout: 30_000 })

  // ===== 加入候补 =====
  const joinWaitlistButton = page.getByRole('button', { name: '加入候补' })
  await expect(joinWaitlistButton).toBeVisible({ timeout: 30_000 })
  await joinWaitlistButton.click()

  // 确认候补弹窗：勾选第一个观演人 → 确认加入候补
  const waitlistConfirmDialog = page.locator('div.fixed.inset-0').filter({ hasText: '确认候补' })
  await expect(waitlistConfirmDialog).toBeVisible({ timeout: 30_000 })

  const attendeeCheckbox = waitlistConfirmDialog.locator('label.cursor-pointer input[type="checkbox"]')
  if (await attendeeCheckbox.count() > 0) {
    await attendeeCheckbox.first().check()
  }
  await waitlistConfirmDialog.getByRole('button', { name: '确认加入候补' }).click()

  // 成功提示：已加入候补（绿色提示条）
  await expect(page.getByText(/已加入候补/).first()).toBeVisible({ timeout: 60_000 })

  // API 断言：候补记录存在（WAITING）
  const waitlistApi: ApiResult<WaitlistEntry[]> = await apiGet<WaitlistEntry[]>('/api/waitlist/my', login.token)
  const entry = waitlistApi.data
    .filter(
      item =>
        item.sessionId === SOLD_OUT_ACTIVITY.sessionId &&
        item.ticketTypeId === SOLD_OUT_ACTIVITY.ticketTypeId &&
        item.status !== 'CANCELLED',
    )
    .sort((a, b) => b.id - a.id)[0]
  expect(entry, '候补记录未创建').toBeTruthy()
  expect(['WAITING', 'ALLOCATING']).toContain(entry!.status)

  updateState({ waitlistEntryId: entry!.id })

  // ===== 我的候补页面 =====
  await page.goto('/waitlist')
  await expect(page.getByRole('heading', { name: '我的候补' })).toBeVisible({ timeout: 60_000 })

  // 排队中的候补卡片可见
  await expect(page.getByText('排队中', { exact: true })).toBeVisible({ timeout: 60_000 })
  const cancelButtons = page.getByRole('button', { name: '取消候补' })
  await expect(cancelButtons.first()).toBeVisible({ timeout: 60_000 })

  // ===== 取消候补 =====
  await cancelButtons.first().click()

  // globalConfirm 确认弹窗
  const confirmDialog = page.locator('div.fixed.inset-0').filter({ hasText: '确认取消这条候补记录' })
  await expect(confirmDialog).toBeVisible({ timeout: 30_000 })
  await confirmDialog.getByRole('button', { name: '确定' }).click()
  await expect(confirmDialog).toBeHidden({ timeout: 30_000 })

  // API 断言：候补已取消
  const deadline = Date.now() + 60_000
  let cancelled: WaitlistEntry | null = null
  while (Date.now() < deadline) {
    const after: ApiResult<WaitlistEntry[]> = await apiGet<WaitlistEntry[]>('/api/waitlist/my', login.token)
    cancelled = after.data.find(item => item.id === entry!.id) || null
    if (cancelled && cancelled.status === 'CANCELLED') break
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  expect(cancelled, '候补记录未在超时内变为已取消').not.toBeNull()
  expect(cancelled!.status).toBe('CANCELLED')

  await context.close()
})
