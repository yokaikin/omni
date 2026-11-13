/**
 * E2E 环境常量与账号
 */

export const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:3000'
export const GATEWAY_URL = process.env.E2E_GATEWAY_URL || 'http://127.0.0.1:8088'

export const isHttpsStagingTargetReady = (): boolean => {
  const values = [process.env.E2E_BASE_URL, process.env.E2E_GATEWAY_URL]
  if (values.some(value => !value)) return false
  return values.every(value => {
    try {
      const url = new URL(value!)
      const hostname = url.hostname.toLowerCase()
      return url.protocol === 'https:'
        && hostname !== 'localhost'
        && !hostname.startsWith('127.')
        && hostname !== '0.0.0.0'
        && hostname !== '[::1]'
    } catch {
      return false
    }
  })
}

/** 沙箱买家凭据（真实浏览器收银台自动化），缺失时支付闭环用例跳过 */
export const ALIPAY_BUYER_LOGON = process.env.OMNI_E2E_ALIPAY_BUYER_LOGON || ''
export const ALIPAY_BUYER_PASSWORD = process.env.OMNI_E2E_ALIPAY_BUYER_PASSWORD || ''
export const ALIPAY_PAY_PASSWORD = process.env.OMNI_E2E_ALIPAY_PAY_PASSWORD || ''

export const isAlipayAutomationReady = (): boolean =>
  ALIPAY_BUYER_LOGON !== '' && ALIPAY_BUYER_PASSWORD !== '' && ALIPAY_PAY_PASSWORD !== ''

const accountValue = (name: string, fallback: string): string => process.env[name] || fallback

const accountId = (name: string, fallback: number): number => {
  const value = process.env[name]
  if (!value) return fallback
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} 必须是正整数`)
  }
  return parsed
}

/** 本地使用演示种子账号；staging 可从 .env.e2e.local 或环境变量注入专用账号。 */
export const ACCOUNTS = {
  user: {
    phone: accountValue('OMNI_E2E_USER_PHONE', '13900000001'),
    password: accountValue('OMNI_E2E_USER_PASSWORD', '123456'),
    userId: accountId('OMNI_E2E_USER_ID', 2004),
  },
  user2: {
    phone: accountValue('OMNI_E2E_USER2_PHONE', '13900000002'),
    password: accountValue('OMNI_E2E_USER2_PASSWORD', '123456'),
    userId: accountId('OMNI_E2E_USER2_ID', 2008),
  },
  admin: {
    phone: accountValue('OMNI_E2E_ADMIN_PHONE', '13800000001'),
    password: accountValue('OMNI_E2E_ADMIN_PASSWORD', '123456'),
    userId: accountId('OMNI_E2E_ADMIN_ID', 2002),
  },
  organizer: {
    phone: accountValue('OMNI_E2E_ORGANIZER_PHONE', '13800000002'),
    password: accountValue('OMNI_E2E_ORGANIZER_PASSWORD', '123456'),
    userId: accountId('OMNI_E2E_ORGANIZER_ID', 2003),
  },
  supportAgent: {
    phone: accountValue('OMNI_E2E_SUPPORT_AGENT_PHONE', '13910000003'),
    password: accountValue('OMNI_E2E_SUPPORT_AGENT_PASSWORD', '123456'),
  },
} as const

/** 主链业务数据（种子活动，与 omni-alipay-sandbox-acceptance 验收数据一致） */
export const MAIN_ACTIVITY = {
  activityId: accountId('OMNI_E2E_ACTIVITY_ID', 900010),
  sessionId: accountId('OMNI_E2E_SESSION_ID', 910010),
  ticketTypeId: accountId('OMNI_E2E_TICKET_TYPE_ID', 920030),
  price: Number(process.env.OMNI_E2E_TICKET_PRICE || 80),
} as const

export const SOLD_OUT_ACTIVITY = {
  activityId: accountId('OMNI_E2E_SOLD_OUT_ACTIVITY_ID', 900116),
  sessionId: accountId('OMNI_E2E_SOLD_OUT_SESSION_ID', 910116),
  ticketTypeId: accountId('OMNI_E2E_SOLD_OUT_TICKET_TYPE_ID', 920348),
} as const

/** E2E 演示活动（B 端活动配置用例创建，避免污染种子活动） */
export const NEW_ACTIVITY_PREFIX = 'E2E自动化活动'

/** 跨用例共享状态文件（订单号、requestId、entryCode 等），位于项目根 runtime/ */
export const STATE_FILE = process.env.E2E_STATE_FILE || '../runtime/e2e-state.json'
