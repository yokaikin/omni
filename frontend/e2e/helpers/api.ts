import * as fs from 'node:fs'
import * as path from 'node:path'
import { GATEWAY_URL, BASE_URL, STATE_FILE } from './env'

/**
 * 网关 API 客户端（node fetch，供测试准备/断言数据用）
 */

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export async function apiPost<T = unknown>(pathname: string, body: unknown, token?: string): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${GATEWAY_URL}${pathname}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
  return (await res.json()) as ApiResult<T>
}

export async function apiGet<T = unknown>(pathname: string, token?: string): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${GATEWAY_URL}${pathname}`, { method: 'GET', headers })
  return (await res.json()) as ApiResult<T>
}

export async function apiDelete<T = unknown>(pathname: string, token?: string): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${GATEWAY_URL}${pathname}`, { method: 'DELETE', headers })
  return (await res.json()) as ApiResult<T>
}

export interface LoginData {
  token: string
  userId: number
  phone: string
  nickname: string | null
  avatar: string | null
  role: string
  permissionCodes?: string[]
}

/** API 登录（绕过 UI，规避 Sentinel 限流；UI 登录由 01-auth 用例覆盖） */
export async function loginViaApi(phone: string, password: string): Promise<LoginData> {
  const result = await apiPost<LoginData>('/api/user/login', { loginType: 'password', account: phone, password })
  if (result.code !== 200 || !result.data?.token) {
    throw new Error(`API 登录失败: ${phone}, code=${result.code}, message=${result.message}`)
  }
  return result.data
}

/** 构造 Playwright storageState（注入 localStorage 登录态） */
export function buildStorageState(login: LoginData) {
  return {
    cookies: [],
    origins: [
      {
        origin: BASE_URL,
        localStorage: [
          { name: 'omni_token', value: login.token },
          {
            name: 'omni_user',
            value: JSON.stringify({
              userId: login.userId,
              phone: login.phone,
              nickname: login.nickname,
              avatar: login.avatar,
              role: login.role,
              permissionCodes: login.permissionCodes || [],
            }),
          },
        ],
      },
    ],
  }
}

/** 跨用例共享状态（订单号、requestId、entryCode 等主链数据） */
export interface E2eState {
  registeredPhone?: string
  registeredPassword?: string
  newActivityId?: number
  grabRequestId?: string
  orderId?: number
  orderNo?: string
  paidOrderNo?: string
  entryCode?: string
  ticketCodes?: string[]
  refundId?: number
  refundOrderNo?: string
  refundNo?: string
  checkInTicketNo?: string
  waitlistEntryId?: number
}

export function readState(): E2eState {
  try {
    const raw = fs.readFileSync(path.resolve(process.cwd(), STATE_FILE), 'utf-8')
    return JSON.parse(raw) as E2eState
  } catch {
    return {}
  }
}

export function updateState(patch: Partial<E2eState>): void {
  const current = readState()
  const next = { ...current, ...patch }
  const target = path.resolve(process.cwd(), STATE_FILE)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, JSON.stringify(next, null, 2), 'utf-8')
}
