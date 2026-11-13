import { expect, test } from '@playwright/test'
import { ACCOUNTS, GATEWAY_URL, isHttpsStagingTargetReady } from './helpers/env'
import { loginViaApi, updateState, type ApiResult } from './helpers/api'

interface DeliveryStatus {
  eventId: string
  channel: 'SMS' | 'EMAIL'
  status: string
  providerMessageId: string | null
  failureReason: string | null
  retryCount: number
  sentTime: string | null
}

interface PrivateAsset {
  id: number
  status: string
  fileSize: number
}

interface SignedDownload {
  url: string
  expiresIn: number
}

const enabled = process.env.OMNI_E2E_RUN_SUPPLIER_SMOKE === 'true'

function requireStagingAndCredentials(names: string[]): void {
  test.skip(!enabled, '未显式开启真实供应商验收')
  if (!isHttpsStagingTargetReady()) throw new Error('真实供应商验收只允许使用 HTTPS staging 地址')

  const missing = names.filter(name => !process.env[name])
  if (missing.length > 0) throw new Error(`真实供应商验收缺少配置项：${missing.join(', ')}`)
}

async function expectDeliverySent(eventId: string, channel: DeliveryStatus['channel']): Promise<void> {
  const token = process.env.OMNI_E2E_INTERNAL_TOKEN!
  const deadline = Date.now() + 6 * 60_000
  let status: DeliveryStatus | null = null
  while (Date.now() < deadline) {
    const url = new URL('/api/notification/internal/deliveries/status', GATEWAY_URL)
    url.searchParams.set('eventId', eventId)
    url.searchParams.set('channel', channel)
    const response = await fetch(url, {
      headers: { 'X-Internal-Token': token },
      signal: AbortSignal.timeout(15_000),
    })
    const result = (await response.json()) as ApiResult<DeliveryStatus | null>
    if (!response.ok || result.code !== 200) {
      throw new Error(`查询 ${channel} 投递状态失败，HTTP ${response.status}，业务码 ${result.code}`)
    }
    status = result.data
    if (status?.status === 'SENT') break
    if (status?.status === 'SKIPPED') throw new Error(`${channel} 渠道被跳过，未完成真实发送`)
    await new Promise(resolve => setTimeout(resolve, 5_000))
  }

  expect(status?.status, `${channel} 未在超时内得到供应商发送成功状态`).toBe('SENT')
  expect(status?.providerMessageId, `${channel} 缺少供应商消息 ID`).toBeTruthy()
  expect(status?.sentTime, `${channel} 缺少发送时间`).toBeTruthy()
}

test('真实供应商：短信与邮件均由供应商接受', async () => {
  requireStagingAndCredentials([
    'OMNI_E2E_INTERNAL_TOKEN',
    'OMNI_E2E_NOTIFICATION_USER_ID',
    'OMNI_E2E_SMS_TEMPLATE_CODE',
    'OMNI_E2E_SMS_TEMPLATE_PARAMS',
  ])

  let smsParams: Record<string, string>
  try {
    smsParams = JSON.parse(process.env.OMNI_E2E_SMS_TEMPLATE_PARAMS!) as Record<string, string>
  } catch {
    throw new Error('OMNI_E2E_SMS_TEMPLATE_PARAMS 必须是符合已审核短信模板变量的 JSON 对象')
  }
  if (!smsParams || typeof smsParams !== 'object' || Array.isArray(smsParams)) {
    throw new Error('OMNI_E2E_SMS_TEMPLATE_PARAMS 必须是 JSON 对象')
  }

  const userId = Number(process.env.OMNI_E2E_NOTIFICATION_USER_ID)
  if (!Number.isSafeInteger(userId) || userId <= 0) {
    throw new Error('OMNI_E2E_NOTIFICATION_USER_ID 必须是正整数')
  }

  const eventId = `supplier-smoke-${Date.now()}`
  const token = process.env.OMNI_E2E_INTERNAL_TOKEN!
  const response = await fetch(`${GATEWAY_URL}/api/notification/internal/events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Internal-Token': token },
    body: JSON.stringify({
      eventId,
      eventType: 'E2E_SUPPLIER_SMOKE',
      userId,
      templateCode: process.env.OMNI_E2E_SMS_TEMPLATE_CODE,
      channels: ['SMS', 'EMAIL'],
      content: `Omni staging 通知渠道验收 ${eventId}`,
      payload: smsParams,
    }),
  })
  const result = (await response.json()) as ApiResult<null>
  expect(response.ok, `创建通知事件失败，HTTP ${response.status}`).toBe(true)
  expect(result.code, `创建通知事件失败，业务码 ${result.code}`).toBe(200)

  await expectDeliverySent(eventId, 'SMS')
  await expectDeliverySent(eventId, 'EMAIL')
})

test('真实对象存储：上传、权限拒绝和签名下载', async ({ browser }) => {
  requireStagingAndCredentials([
    'OMNI_E2E_ORGANIZER_PHONE',
    'OMNI_E2E_ORGANIZER_PASSWORD',
    'OMNI_E2E_USER_PHONE',
    'OMNI_E2E_USER_PASSWORD',
  ])

  const organizer = await loginViaApi(ACCOUNTS.organizer.phone, ACCOUNTS.organizer.password)
  const buyer = await loginViaApi(ACCOUNTS.user.phone, ACCOUNTS.user.password)
  const context = await browser.newContext()
  const organizerPage = await context.newPage()
  const pdf = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n', 'ascii')
  const upload = await organizerPage.request.post(`${GATEWAY_URL}/api/ticket/admin/private-assets`, {
    headers: { Authorization: `Bearer ${organizer.token}` },
    multipart: {
      bizType: 'venue-proof',
      file: { name: 'staging-storage-smoke.pdf', mimeType: 'application/pdf', buffer: pdf },
    },
  })
  const uploaded = (await upload.json()) as ApiResult<PrivateAsset>
  expect(upload.ok(), `私有文件上传失败，HTTP ${upload.status()}`).toBe(true)
  expect(uploaded.code, `私有文件上传失败，业务码 ${uploaded.code}`).toBe(200)
  expect(uploaded.data.id).toBeGreaterThan(0)
  updateState({ supplierAssetId: uploaded.data.id })

  const denied = await fetch(`${GATEWAY_URL}/api/ticket/admin/private-assets/${uploaded.data.id}/download`, {
    headers: { Authorization: `Bearer ${buyer.token}` },
  })
  expect([401, 403]).toContain(denied.status)

  const signedResponse = await fetch(
    `${GATEWAY_URL}/api/ticket/admin/private-assets/${uploaded.data.id}/download-url?expirySeconds=300`,
    { headers: { Authorization: `Bearer ${organizer.token}` } },
  )
  const signed = (await signedResponse.json()) as ApiResult<SignedDownload>
  expect(signedResponse.ok, `生成签名下载地址失败，HTTP ${signedResponse.status}`).toBe(true)
  expect(signed.code).toBe(200)
  expect(signed.data.expiresIn).toBe(300)

  const downloaded = await fetch(signed.data.url)
  expect(downloaded.ok, `签名下载失败，HTTP ${downloaded.status}`).toBe(true)
  expect(Buffer.from(await downloaded.arrayBuffer()).equals(pdf)).toBe(true)
  await context.close()
})
