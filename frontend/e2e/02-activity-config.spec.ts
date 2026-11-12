import { test, expect } from '@playwright/test'
import { ACCOUNTS } from './helpers/env'
import { apiGet, buildStorageState, loginViaApi, updateState } from './helpers/api'
import { fillStable, warmUpDevPage } from './helpers/ui'

interface ActivityRecord {
  id: number
  name: string
  publishStatus?: string
}

interface PageData {
  records: ActivityRecord[]
}

test('B端活动配置闭环：organizer 创建活动草稿并出现在活动列表', async ({ browser }) => {
  test.setTimeout(180_000)

  const login = await loginViaApi(ACCOUNTS.organizer.phone, ACCOUNTS.organizer.password)
  const context = await browser.newContext({ storageState: buildStorageState(login) })
  const page = await context.newPage()

  const activityName = `E2E自动化活动-${Date.now()}`

  // 三步向导：步骤1 活动信息
  await warmUpDevPage(page, '/console/activities/new', '新建我的活动')

  await fillStable(page.getByPlaceholder('例：2026 XX演唱会北京站'), activityName)
  await page.locator('select').first().selectOption({ label: '演唱会' })

  // 艺人搜索（结果下拉容器为 .max-h-56）
  await fillStable(page.getByPlaceholder('搜索艺人/团队名称、别名、代表作品'), '周杰伦')
  const artistResult = page.locator('div.max-h-56 button').filter({ hasText: '周杰伦' }).first()
  await expect(artistResult).toBeVisible({ timeout: 20_000 })
  await artistResult.click()
  await expect(page.getByText('主艺人').first()).toBeVisible()

  await page.getByRole('button', { name: /下一步/ }).click()

  // 步骤2 站点配置：场馆待定模式，仅城市必填
  await expect(page.getByText('站点配置').first()).toBeVisible()
  await fillStable(page.getByLabel(/城市/), '北京')
  await page.getByRole('button', { name: /下一步/ }).click()

  // 步骤3 确认提交
  await expect(page.getByText('提交内容确认')).toBeVisible()
  await page.getByRole('button', { name: '提交我的活动' }).click()

  // 成功提示（自定义模态框），确认后跳转活动编辑页
  await expect(page.getByText(/活动草稿已(创建|保存)/).first()).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: '确定' }).click()
  await page.waitForURL(/\/console\/activities\/(\d+)\/edit/, { timeout: 30_000 })
  const activityId = Number(page.url().match(/\/console\/activities\/(\d+)\/edit/)?.[1])
  expect(activityId).toBeGreaterThan(0)

  // 编辑页应回显活动名称
  await expect(page.getByPlaceholder('例：2026 XX演唱会北京站')).toHaveValue(activityName, { timeout: 30_000 })

  // 活动列表可见（列表按 id 升序分页，用名称搜索定位新草稿）
  await page.goto('/console/activities')
  await fillStable(page.getByPlaceholder('搜索活动名称'), activityName)
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText(activityName).first()).toBeVisible({ timeout: 60_000 })

  // API 断言：草稿状态
  const api = await apiGet<PageData>(
    `/api/ticket/admin/activities?page=1&size=20&keyword=${encodeURIComponent(activityName)}`,
    login.token,
  )
  expect(api.code).toBe(200)
  const record = api.data.records.find((item) => item.id === activityId)
  expect(record).toBeTruthy()
  expect(record?.publishStatus).toBe('draft')

  updateState({ newActivityId: activityId })

  await context.close()
})
