import { test, expect } from '@playwright/test'
import { apiGet, apiPost, buildStorageState, loginViaApi } from './helpers/api'
import { ACCOUNTS } from './helpers/env'

/**
 * 08 客服闭环（真实浏览器 + 真实后端）
 *
 * 覆盖：C端 /help 发起 AI 咨询 → 转人工 → B端 /support 坐席接入 →
 * 坐席回复 → C端轮询收到人工回复 → API 断言会话与消息落库。
 *
 * 说明：
 * - 每次运行注册独立新用户，避免历史会话（ASSIGNED 后无法再次转人工）导致重跑失败。
 * - AI 流式应答依赖后端模型配置，不强制断言 AI 回复内容，仅断言用户消息上屏。
 */

interface SupportConversationVo {
  id: number
  subject: string
  status: string
  lastMessage: string | null
}

interface SupportMessageVo {
  id: number
  senderType: string
  content: string
}

test.describe('08 客服闭环', () => {
  test('C端咨询转人工，坐席接入并回复，用户收到', async ({ browser }) => {
    test.setTimeout(180_000)

    // 独立新用户（无历史客服会话，保证可转人工）
    const phone = `139${String(Math.floor(Math.random() * 90000000) + 10000000)}`
    const password = 'E2ePass123456'
    const registerResult = await apiPost('/api/user/register', {
      phone,
      password,
      confirmPassword: password,
    })
    expect(registerResult.code, `注册失败: ${registerResult.message}`).toBe(200)
    const user = await loginViaApi(phone, password)
    const agent = await loginViaApi(ACCOUNTS.supportAgent.phone, ACCOUNTS.supportAgent.password)

    const stamp = Date.now().toString().slice(-6)
    const question = `订单问题咨询${stamp}`
    const agentReply = `人工回复${stamp}`

    const userContext = await browser.newContext({ storageState: buildStorageState(user) })
    const userPage = await userContext.newPage()
    const agentContext = await browser.newContext({ storageState: buildStorageState(agent) })
    const agentPage = await agentContext.newPage()

    // --- C端：/help 发起咨询 ---
    await userPage.goto('/help')
    const supportAside = userPage.locator('aside:has(h2:has-text("在线客服"))')
    await expect(supportAside).toBeVisible()
    const supportInput = supportAside.locator('input')
    await expect(supportInput).toBeEnabled()
    await supportInput.fill(question)
    await supportAside.locator('button[title="发送"]').click()

    // 用户消息上屏（AI 流式回复由后端模型决定，不强制断言）
    await expect(userPage.getByText(question)).toBeVisible({ timeout: 30_000 })

    // --- C端：转人工 ---
    const handoffButton = userPage.getByRole('button', { name: '转人工客服' })
    await expect(handoffButton).toBeEnabled({ timeout: 90_000 })
    await handoffButton.click()
    await expect(
      userPage.getByRole('button', { name: '人工介入请等待' })
    ).toBeDisabled({ timeout: 15_000 })

    // --- B端：坐席工作台接入会话 ---
    await agentPage.goto('/support')
    const queueItem = agentPage.locator('aside.border-r button', { hasText: question })
    await expect(queueItem).toBeVisible({ timeout: 30_000 })
    await queueItem.click()

    const claimButton = agentPage.getByRole('button', { name: '接入', exact: true })
    await expect(claimButton).toBeEnabled()
    await claimButton.click()

    // 接入后会话状态变为 ASSIGNED，离开默认“待处理”过滤视图；
    // 3s 轮询会把 active 切回待处理列表，这里切到“处理中”并重新选中本会话再回复。
    await agentPage.getByRole('button', { name: /^处理中/ }).click()
    const progressItem = agentPage.locator('aside.border-r button', { hasText: question })
    await expect(progressItem).toBeVisible({ timeout: 15_000 })
    await progressItem.click()

    const replyInput = agentPage.getByPlaceholder('输入回复内容')
    await expect(replyInput).toBeEnabled({ timeout: 15_000 })

    // --- B端：坐席回复 ---
    await replyInput.fill(agentReply)
    const sendButton = agentPage.getByTitle('发送')
    await expect(sendButton).toBeEnabled()
    await sendButton.click()
    // 消息区断言（左侧会话列表的 lastMessage 也会包含同文本，需限定在主消息区）
    const agentMessageBox = agentPage.locator('main > section')
    await expect(agentMessageBox.getByText(agentReply)).toBeVisible({ timeout: 15_000 })

    // --- C端：轮询（3s）收到人工回复 ---
    await expect(userPage.getByText(agentReply)).toBeVisible({ timeout: 20_000 })

    // --- API 断言：会话 ASSIGNED + 人工消息落库 ---
    const myResult = await apiGet<SupportConversationVo[]>(
      '/api/user/support/conversations/my',
      user.token
    )
    expect(myResult.code).toBe(200)
    const conversation = (myResult.data || []).find(
      item => item.subject.includes(question) || (item.lastMessage || '').includes(question)
    )
    expect(conversation, '未找到本次咨询会话').toBeTruthy()
    expect(conversation!.status).toBe('ASSIGNED')

    const messagesResult = await apiGet<SupportMessageVo[]>(
      `/api/user/support/conversations/${conversation!.id}/messages`,
      user.token
    )
    expect(messagesResult.code).toBe(200)
    const messages = messagesResult.data || []
    expect(messages.some(item => item.senderType === 'USER' && item.content === question)).toBe(true)
    expect(messages.some(item => item.senderType === 'AGENT' && item.content === agentReply)).toBe(true)

    await userContext.close()
    await agentContext.close()
  })
})
