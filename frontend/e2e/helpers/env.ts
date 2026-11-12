/**
 * E2E 环境常量与账号
 */

export const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:3000'
export const GATEWAY_URL = process.env.E2E_GATEWAY_URL || 'http://127.0.0.1:8088'

/** 沙箱买家凭据（真实浏览器收银台自动化），缺失时支付闭环用例跳过 */
export const ALIPAY_BUYER_LOGON = process.env.OMNI_E2E_ALIPAY_BUYER_LOGON || ''
export const ALIPAY_BUYER_PASSWORD = process.env.OMNI_E2E_ALIPAY_BUYER_PASSWORD || ''
export const ALIPAY_PAY_PASSWORD = process.env.OMNI_E2E_ALIPAY_PAY_PASSWORD || ''

export const isAlipayAutomationReady = (): boolean =>
  ALIPAY_BUYER_LOGON !== '' && ALIPAY_BUYER_PASSWORD !== '' && ALIPAY_PAY_PASSWORD !== ''

/** 种子账号（sql/seeds/prod-split-real-demo/04-user-ops.sql） */
export const ACCOUNTS = {
  user: { phone: '13900000001', password: '123456', userId: 2004 },
  user2: { phone: '13900000002', password: '123456', userId: 2008 },
  admin: { phone: '13800000001', password: '123456', userId: 2002 },
  organizer: { phone: '13800000002', password: '123456', userId: 2003 },
  supportAgent: { phone: '13910000003', password: '123456' },
} as const

/** 主链业务数据（种子活动，与 omni-alipay-sandbox-acceptance 验收数据一致） */
export const MAIN_ACTIVITY = {
  activityId: 900010,
  sessionId: 910010,
  ticketTypeId: 920030,
  price: 80,
} as const

/** E2E 演示活动（B 端活动配置用例创建，避免污染种子活动） */
export const NEW_ACTIVITY_PREFIX = 'E2E自动化活动'

/** 跨用例共享状态文件（订单号、requestId、entryCode 等），位于项目根 runtime/ */
export const STATE_FILE = process.env.E2E_STATE_FILE || '../runtime/e2e-state.json'
