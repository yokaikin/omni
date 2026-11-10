/**
 * API 客户端 - fetch 封装
 */
import { getToken } from './auth.ts'
import type {
  ApiResult,
  AssetUploadVO,
  ChangePasswordRequest,
  GrabProgressResult,
  GrabRequestResult,
  LoginResponse,
  NotificationPreferenceVO,
  OrganizerApplicationListParams,
  OrganizerApplicationQueryStatus,
  OrganizerApplicationStatus,
  OrganizerApplicationMaterialVO,
  OrganizerApplicationVO,
  OrganizerDirectoryListParams,
  OrganizerDirectoryVO,
  OrganizerOnsaleSummaryVO,
  OrganizerOpsAssignmentPayload,
  OrganizerOpsAssignmentVO,
  OrganizerOpsFollowUpPayload,
  OrganizerOpsFollowUpVO,
  PrivateAssetVO,
  ResetPasswordRequest,
  SeatMapResponse,
  SessionVisibleStockResult,
  SubmitGrabRequestPayload,
  SubjectType,
  ActivityMarketingRulePayload,
  CsAuditRequest,
  CsCopilotEditRequest,
  CsCopilotRejectRequest,
  CsInternalNoteRequest,
  CsInternalNoteVO,
  CsOrgTreeVO,
  CsRecentOrderContextVO,
  CsSessionMessageVO,
  CsSessionQueryParams,
  CsSessionVO,
  CsTransferRequest,
  CsUserSessionHistoryVO,
  SupportAiSuggestion,
  UserAttendeeExportVO,
  UserAttendeePayload,
  UserAttendeeVO,
  UserBrowseHistoryPayload,
  UserBrowseHistoryVO,
  UserInfo,
} from '@/types/api'

const BASE_URL = process.env.NEXT_PUBLIC_API_URL || ''
const DEFAULT_REQUEST_TIMEOUT_MS = 5000
const CONSOLE_ADMIN_REQUEST_TIMEOUT_MS = 20000
const QR_PAY_REQUEST_TIMEOUT_MS = 15000
const SUPPORT_MESSAGE_REQUEST_TIMEOUT_MS = 70000
const COPILOT_GENERATE_REQUEST_TIMEOUT_MS = 35000
const AI_FINDER_REQUEST_TIMEOUT_MS = 70000
const MESSAGE_LABELS: Record<string, string> = {
  'ticket type sold out': '当前票档已售罄',
  'order confirmation pending': '订单确认中，请稍后查看订单结果',
  'team order confirmation pending': '小队订单确认中，请稍后查看订单结果',
  unauthorized: '登录状态已失效，请重新登录',
  forbidden: '没有权限执行该操作',
  'not found': '未找到相关记录',
  'bad request': '请求参数不正确',
  conflict: '请求冲突，请刷新后重试',
  'internal server error': '服务暂不可用，请稍后重试',
  'service unavailable': '服务暂不可用，请稍后重试',
  'gateway timeout': '服务响应超时，请稍后重试',
}
const PARAMETER_LABELS: Record<string, string> = {
  userId: '用户ID',
  teamId: '小队ID',
  attendeeId: '实名观演人ID',
  waitlistId: '候补记录ID',
  artistId: '艺人ID',
  activityId: '活动ID',
  resolutionId: '风险处理ID',
  stationConfigReviewId: '站点变更审核ID',
  aiSuggestionId: 'AI建议编号',
}

class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

function assertPositiveInteger(value: number, name: string) {
  if (!Number.isInteger(value) || value <= 0) {
    throw new ApiError(400, `${formatParameterLabel(name)}不正确`)
  }
}

function formatParameterLabel(name: string) {
  const label = PARAMETER_LABELS[name] ?? (hasChinese(name) ? name : '参数')
  return label.replaceAll('ID', '编号')
}

function hasChinese(value: string) {
  return /[\u3400-\u9fff]/.test(value)
}

function toUserVisibleMessage(message: string | null | undefined, fallback = '请求失败，请稍后重试') {
  if (!message) return fallback
  const trimmed = message.trim()
  const localized = MESSAGE_LABELS[trimmed.toLowerCase()]
  if (localized) return localized
  return hasChinese(trimmed) ? trimmed : fallback
}

async function request<T>(url: string, options?: RequestInit, config?: { timeoutMs?: number }): Promise<T> {
  const token = getToken()
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...options?.headers,
  }

  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), config?.timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS)

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${url}`, {
      ...options,
      headers,
      signal: controller.signal
    })
  } catch {
    throw new ApiError(503, '服务暂不可用，请稍后重试')
  } finally {
    clearTimeout(timeoutId)
  }

  let result: ApiResult<T>
  try {
    result = await response.json()
  } catch {
    throw new ApiError(503, '服务暂不可用，请稍后重试')
  }

  if (result.code !== 200) {
    throw new ApiError(result.code, toUserVisibleMessage(result.message))
  }

  return result.data
}

async function multipartRequest<T>(url: string, formData: FormData, options?: Omit<RequestInit, 'body'>): Promise<T> {
  const token = getToken()
  const headers: HeadersInit = {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...options?.headers,
  }

  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_REQUEST_TIMEOUT_MS)

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${url}`, {
      ...options,
      method: options?.method || 'POST',
      headers,
      body: formData,
      signal: controller.signal,
    })
  } catch {
    throw new ApiError(503, '服务暂不可用，请稍后重试')
  } finally {
    clearTimeout(timeoutId)
  }

  if (!response.ok) {
    try {
      const result = await response.json() as ApiResult<unknown>
      throw new ApiError(result.code || response.status, toUserVisibleMessage(result.message || response.statusText))
    } catch (err) {
      if (err instanceof ApiError) throw err
      throw new ApiError(response.status, toUserVisibleMessage(response.statusText))
    }
  }

  let result: ApiResult<T>
  try {
    result = await response.json()
  } catch {
    throw new ApiError(503, '服务暂不可用，请稍后重试')
  }

  if (result.code !== 200) {
    throw new ApiError(result.code, toUserVisibleMessage(result.message))
  }

  return result.data
}

// ========== 用户服务 ==========

export async function login(params: { loginType: string; account: string; password?: string; smsCode?: string }) {
  return request<LoginResponse>(
    '/api/user/login',
    { method: 'POST', body: JSON.stringify(params) }
  )
}

export async function register(params: { phone: string; password: string; confirmPassword: string }) {
  return request<void>('/api/user/register', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function getUserInfo() {
  return request<UserInfo>('/api/user/info')
}

export async function updateProfile(params: { nickname?: string | null; email?: string | null; avatar?: string | null; organizerName?: string | null }) {
  return request<UserInfo>('/api/user/profile', {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function uploadUserAvatar(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return multipartRequest<UserInfo>('/api/user/assets/avatar', formData)
}

export async function listUserAttendees() {
  return request<UserAttendeeVO[]>('/api/user/attendees')
}

export async function exportUserAttendees() {
  return request<UserAttendeeExportVO>('/api/user/attendees/export')
}

export async function createUserAttendee(params: UserAttendeePayload) {
  return request<UserAttendeeVO>('/api/user/attendees', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function updateUserAttendee(id: number, params: UserAttendeePayload) {
  assertPositiveInteger(id, 'attendeeId')
  return request<UserAttendeeVO>(`/api/user/attendees/${id}`, {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function deleteUserAttendee(id: number) {
  assertPositiveInteger(id, 'attendeeId')
  return request<void>(`/api/user/attendees/${id}`, { method: 'DELETE' })
}

export async function recordUserBrowseHistory(params: UserBrowseHistoryPayload) {
  return request<UserBrowseHistoryVO>('/api/user/browse-history', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function listUserBrowseHistory() {
  return request<UserBrowseHistoryVO[]>('/api/user/browse-history')
}

export async function clearUserBrowseHistory() {
  return request<void>('/api/user/browse-history', { method: 'DELETE' })
}

export async function verifyPasswordIdentity(params: { oldPassword: string; smsCode: string }) {
  return request<void>('/api/user/password/verify', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function changePassword(params: ChangePasswordRequest) {
  return request<void>('/api/user/password', {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function verifyCurrentPhone(params: { smsCode: string }) {
  return request<void>('/api/user/phone/verify-current', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function changePhone(params: { currentSmsCode: string; newPhone: string; newSmsCode: string }) {
  return request<UserInfo>('/api/user/phone', {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function resetPassword(params: ResetPasswordRequest) {
  return request<void>('/api/user/password/reset', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function submitOrganizerApplication(params: { organizerName: string; subjectType: SubjectType; contactName: string; contactPhone: string; contactEmail?: string | null; licenseNo?: string | null; businessScope?: string | null; description?: string | null }) {
  return request<OrganizerApplicationVO>('/api/user/organizer/applications', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function getMyOrganizerApplication() {
  return request<OrganizerApplicationVO | null>('/api/user/organizer/applications/my')
}

const ORGANIZER_APPLICATION_STATUS_QUERY: Record<OrganizerApplicationStatus, OrganizerApplicationQueryStatus> = {
  0: 'PENDING',
  1: 'APPROVED',
  2: 'REJECTED',
}

export function listOrganizerApplications(status?: OrganizerApplicationStatus): Promise<OrganizerApplicationVO[]>
export function listOrganizerApplications(params?: OrganizerApplicationListParams): Promise<import('@/types/api').PageResult<OrganizerApplicationVO>>
export async function listOrganizerApplications(
  paramsOrStatus?: OrganizerApplicationListParams | OrganizerApplicationStatus,
) {
  const isLegacyCall = paramsOrStatus === undefined || typeof paramsOrStatus === 'number'
  const legacyStatus = typeof paramsOrStatus === 'number' ? ORGANIZER_APPLICATION_STATUS_QUERY[paramsOrStatus] : undefined
  const params: OrganizerApplicationListParams = isLegacyCall ? { status: legacyStatus } : (paramsOrStatus ?? {})
  const search = new URLSearchParams()
  search.set('page', String(params.page || 1))
  search.set('size', String(params.size || 10))
  if (params.keyword?.trim()) search.set('keyword', params.keyword.trim())
  if (params.status) search.set('status', params.status)
  if (params.subjectType) search.set('subjectType', params.subjectType)
  const result = await request<import('@/types/api').PageResult<OrganizerApplicationVO>>(
    `/api/user/organizer/applications/admin?${search.toString()}`,
  )
  return isLegacyCall ? result.records : result
}

export async function approveOrganizerApplication(id: number, reviewNote?: string) {
  return request<OrganizerApplicationVO>(`/api/user/organizer/applications/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote }),
  })
}

export async function rejectOrganizerApplication(id: number, reviewNote: string) {
  return request<OrganizerApplicationVO>(`/api/user/organizer/applications/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote }),
  })
}

export async function sendSmsCode(phone: string) {
  return request<string>(`/api/user/send-code?phone=${encodeURIComponent(phone)}`, {
    method: 'POST',
  })
}

export async function listHelpFaqs() {
  return request<import('@/types/api').HelpFaqVO[]>('/api/user/help/faqs')
}

export async function startSupportConversation(params: { subject?: string | null; initialMessage?: string | null; preferHuman?: boolean }) {
  return request<import('@/types/api').SupportConversationVO>('/api/user/support/conversations', {
    method: 'POST',
    body: JSON.stringify(params),
  }, { timeoutMs: SUPPORT_MESSAGE_REQUEST_TIMEOUT_MS })
}

export async function listMySupportConversations() {
  return request<import('@/types/api').SupportConversationVO[]>('/api/user/support/conversations/my')
}

export async function listSupportMessages(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportMessageVO[]>(`/api/user/support/conversations/${conversationId}/messages`)
}

export async function markSupportHelpPresence() {
  return request<void>('/api/user/support/presence/help', {
    method: 'POST',
  })
}

export async function clearSupportHelpPresence(options?: { keepalive?: boolean }) {
  return request<void>('/api/user/support/presence/help/leave', {
    method: 'POST',
    keepalive: options?.keepalive,
  })
}

export async function sendSupportMessage(conversationId: number, content: string) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportMessageVO>(`/api/user/support/conversations/${conversationId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  }, { timeoutMs: SUPPORT_MESSAGE_REQUEST_TIMEOUT_MS })
}

export type SupportMessageStreamHandlers = {
  onUserMessage?: (message: import('@/types/api').SupportMessageVO) => void
  onThinking?: (message?: string) => void
  onDelta?: (content: string) => void
  onAssistantMessage?: (message: import('@/types/api').SupportMessageVO) => void
  onDone?: (message?: import('@/types/api').SupportMessageVO | null) => void
}

function parseSsePayload(data: string): unknown {
  if (!data) return null
  try {
    return JSON.parse(data)
  } catch {
    return data
  }
}

function readStringField(payload: unknown, field: string) {
  if (payload && typeof payload === 'object' && field in payload) {
    const value = (payload as Record<string, unknown>)[field]
    return typeof value === 'string' ? value : ''
  }
  return ''
}

function readMessageField(payload: unknown) {
  if (payload && typeof payload === 'object' && 'message' in payload) {
    return (payload as Record<string, unknown>).message as import('@/types/api').SupportMessageVO | null
  }
  return null
}

function dispatchSupportStreamEvent(eventName: string, data: string, handlers: SupportMessageStreamHandlers) {
  const payload = parseSsePayload(data)
  if (eventName === 'userMessage') {
    handlers.onUserMessage?.(payload as import('@/types/api').SupportMessageVO)
    return ''
  }
  if (eventName === 'thinking') {
    handlers.onThinking?.(readStringField(payload, 'message') || '客服正在思考')
    return ''
  }
  if (eventName === 'delta') {
    const content = readStringField(payload, 'content')
    if (content) handlers.onDelta?.(content)
    return ''
  }
  if (eventName === 'done') {
    handlers.onDone?.(readMessageField(payload))
    return ''
  }
  if (eventName === 'error') {
    return readStringField(payload, 'message') || '客服暂时无法回复，请稍后重试'
  }
  return ''
}

function processSseBlock(block: string, handlers: SupportMessageStreamHandlers) {
  let eventName = 'message'
  const dataLines: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim()
      continue
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  }
  if (dataLines.length === 0) return ''
  return dispatchSupportStreamEvent(eventName, dataLines.join('\n'), handlers)
}

export async function sendSupportMessageStream(
  conversationId: number,
  content: string,
  handlers: SupportMessageStreamHandlers = {}
) {
  assertPositiveInteger(conversationId, '客服会话ID')
  const token = getToken()
  const response = await fetch(`${BASE_URL}/api/user/support/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ content }),
    cache: 'no-store',
  })

  if (!response.ok) {
    try {
      const result = await response.json() as ApiResult<unknown>
      throw new ApiError(result.code || response.status, toUserVisibleMessage(result.message || response.statusText))
    } catch (err) {
      if (err instanceof ApiError) throw err
      throw new ApiError(response.status, toUserVisibleMessage(response.statusText))
    }
  }
  if (!response.body) {
    throw new ApiError(503, '客服暂时无法回复，请稍后重试')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let streamError = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      streamError = processSseBlock(block, handlers) || streamError
      boundary = buffer.indexOf('\n\n')
    }
  }

  buffer += decoder.decode()
  if (buffer.trim()) {
    streamError = processSseBlock(buffer, handlers) || streamError
  }
  if (streamError) {
    throw new ApiError(503, streamError)
  }
}

export async function handoffSupportConversation(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/conversations/${conversationId}/handoff`, {
    method: 'POST',
  })
}

export async function confirmCloseSupportConversation(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/conversations/${conversationId}/close`, {
    method: 'POST',
  })
}

export async function rejectCloseSupportConversation(conversationId: number, reason?: string) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/conversations/${conversationId}/close/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function listAgentSupportConversations(statusOrOptions?: string | { status?: string; queue?: string }) {
  const params = new URLSearchParams()
  if (typeof statusOrOptions === 'string') {
    params.set('status', statusOrOptions)
  } else {
    if (statusOrOptions?.status) params.set('status', statusOrOptions.status)
    if (statusOrOptions?.queue) params.set('queue', statusOrOptions.queue)
  }
  const qs = params.toString()
  return request<import('@/types/api').SupportConversationVO[]>(`/api/user/support/agent/conversations${qs ? `?${qs}` : ''}`)
}

export async function claimSupportConversation(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/agent/conversations/${conversationId}/claim`, {
    method: 'POST',
  })
}

export async function listSupportNotes(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportNoteVO[]>(`/api/user/support/agent/conversations/${conversationId}/notes`)
}

export async function addSupportNote(conversationId: number, content: string) {
  assertPositiveInteger(conversationId, '客服会话ID')
  if (!content.trim()) throw new ApiError(400, '内部备注不能为空')
  return request<import('@/types/api').SupportNoteVO>(`/api/user/support/agent/conversations/${conversationId}/notes`, {
    method: 'POST',
    body: JSON.stringify({ content: content.trim() }),
  })
}

export async function updateSupportTags(conversationId: number, tags: string[]) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/agent/conversations/${conversationId}/tags`, {
    method: 'PUT',
    body: JSON.stringify({ tags }),
  })
}

export async function listSupportQuickReplies() {
  return request<import('@/types/api').SupportQuickReplyVO[]>('/api/user/support/agent/quick-replies')
}

export async function listEnabledSupportAgents() {
  return request<import('@/types/api').SupportAccountVO[]>('/api/user/support/agent/accounts')
}

export async function transferSupportConversation(conversationId: number, targetAgentId: number, reason?: string) {
  assertPositiveInteger(conversationId, '客服会话ID')
  assertPositiveInteger(targetAgentId, '转接客服')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/agent/conversations/${conversationId}/transfer`, {
    method: 'POST',
    body: JSON.stringify({ targetAgentId, reason }),
  })
}

export async function escalateSupportConversation(conversationId: number, reason?: string) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/agent/conversations/${conversationId}/escalate`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function listSupportAudits(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportAuditVO[]>(`/api/user/support/agent/conversations/${conversationId}/audits`)
}

export async function getCsOrgTree() {
  return request<CsOrgTreeVO>('/api/user/cs/org-tree')
}

export async function listCsSessions(params: CsSessionQueryParams = {}) {
  const searchParams = new URLSearchParams()
  if (params.groupId) searchParams.set('groupId', String(params.groupId))
  if (params.agentId) searchParams.set('agentId', String(params.agentId))
  if (params.unassignedOnly) searchParams.set('unassignedOnly', 'true')
  if (params.sourceType) searchParams.set('sourceType', params.sourceType)
  if (params.status) searchParams.set('status', params.status)
  if (params.slaTimeoutOnly) searchParams.set('slaTimeoutOnly', 'true')
  if (params.keyword?.trim()) searchParams.set('keyword', params.keyword.trim())
  searchParams.set('page', String(params.page || 1))
  searchParams.set('size', String(params.size || 30))
  if (params.sort) searchParams.set('sort', params.sort)
  return request<import('@/types/api').PageResult<CsSessionVO>>(`/api/user/cs/sessions?${searchParams.toString()}`)
}

export async function listCsSessionMessages(sessionId: number) {
  assertPositiveInteger(sessionId, '客服会话编号')
  return request<CsSessionMessageVO[]>(`/api/user/cs/sessions/${sessionId}/messages`)
}

export async function listCsUserSessionHistory(userId: number) {
  assertPositiveInteger(userId, '用户编号')
  return request<CsUserSessionHistoryVO[]>(`/api/user/cs/users/${userId}/sessions-history`)
}

export async function claimCsSession(sessionId: number) {
  assertPositiveInteger(sessionId, '客服会话编号')
  return request<CsSessionVO>(`/api/user/cs/sessions/${sessionId}/claim`, { method: 'POST' })
}

export async function transferCsSession(sessionId: number, body: CsTransferRequest) {
  assertPositiveInteger(sessionId, '客服会话编号')
  return request<CsSessionVO>(`/api/user/cs/sessions/${sessionId}/transfer`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function escalateCsSession(sessionId: number, reason?: string) {
  assertPositiveInteger(sessionId, '客服会话编号')
  return request<CsSessionVO>(`/api/user/cs/sessions/${sessionId}/escalate`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason?.trim() || null }),
  })
}

export async function auditCsSession(sessionId: number, body: CsAuditRequest) {
  assertPositiveInteger(sessionId, '客服会话编号')
  return request<CsSessionVO>(`/api/user/cs/sessions/${sessionId}/audit`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function addCsInternalNote(sessionId: number, body: CsInternalNoteRequest) {
  assertPositiveInteger(sessionId, '客服会话编号')
  if (!body.content.trim()) throw new ApiError(400, '内部备注不能为空')
  return request<CsInternalNoteVO>(`/api/user/cs/sessions/${sessionId}/internal-note`, {
    method: 'POST',
    body: JSON.stringify({ content: body.content.trim() }),
  })
}

export async function generateCsCopilotSuggestion(sessionId: number) {
  assertPositiveInteger(sessionId, '客服会话编号')
  return request<SupportAiSuggestion>(`/api/user/cs/sessions/${sessionId}/copilot/suggestions`, {
    method: 'POST',
  }, { timeoutMs: COPILOT_GENERATE_REQUEST_TIMEOUT_MS })
}

export async function acceptCsCopilotSuggestion(suggestionId: number) {
  assertPositiveInteger(suggestionId, 'aiSuggestionId')
  return request<SupportAiSuggestion>(`/api/user/cs/copilot/suggestions/${suggestionId}/accept`, {
    method: 'POST',
  })
}

export async function editCsCopilotSuggestion(suggestionId: number, body: CsCopilotEditRequest) {
  assertPositiveInteger(suggestionId, 'aiSuggestionId')
  return request<SupportAiSuggestion>(`/api/user/cs/copilot/suggestions/${suggestionId}/edit`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function rejectCsCopilotSuggestion(suggestionId: number, body: CsCopilotRejectRequest = {}) {
  assertPositiveInteger(suggestionId, 'aiSuggestionId')
  return request<SupportAiSuggestion>(`/api/user/cs/copilot/suggestions/${suggestionId}/reject`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function getRecentTicketOrderContext(userId: number) {
  assertPositiveInteger(userId, '用户编号')
  return request<CsRecentOrderContextVO[]>(
    `/api/ticket/admin/orders/user-recent-context?userId=${encodeURIComponent(String(userId))}`,
    undefined,
    { timeoutMs: 3000 },
  )
}

export async function getSupportConversationContext(conversationId: number) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportContextVO>(`/api/user/support/agent/conversations/${conversationId}/context`)
}

export async function closeSupportConversation(conversationId: number, reason?: string) {
  assertPositiveInteger(conversationId, '客服会话ID')
  return request<import('@/types/api').SupportConversationVO>(`/api/user/support/agent/conversations/${conversationId}/close`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function listSupportAccounts() {
  return request<import('@/types/api').SupportAccountVO[]>('/api/user/support/admin/accounts')
}

export async function createSupportAccount(params: { phone: string; nickname: string; password: string; supportRole?: string }) {
  return request<import('@/types/api').SupportAccountVO>('/api/user/support/admin/accounts', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function updateSupportAccount(id: number, params: { phone: string; nickname: string; password?: string; status?: number; supportRole?: string }) {
  assertPositiveInteger(id, '客服账号ID')
  return request<import('@/types/api').SupportAccountVO>(`/api/user/support/admin/accounts/${id}`, {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function deactivateSupportAccount(id: number) {
  assertPositiveInteger(id, '客服账号ID')
  return request<import('@/types/api').SupportAccountVO>(`/api/user/support/admin/accounts/${id}/deactivate`, {
    method: 'POST',
  })
}

export async function uploadOrganizerApplicationMaterial(applicationId: number, materialType: string, file: File) {
  const formData = new FormData()
  formData.append('materialType', materialType)
  formData.append('file', file)
  return multipartRequest<OrganizerApplicationMaterialVO>(`/api/user/organizer/applications/${applicationId}/materials`, formData)
}

export async function listOrganizers(params: OrganizerDirectoryListParams = {}) {
  const search = new URLSearchParams()
  search.set('page', String(params.page || 1))
  search.set('size', String(params.size || 10))
  if (params.keyword?.trim()) search.set('keyword', params.keyword.trim())
  if (params.followUpOperator) search.set('followUpOperator', String(params.followUpOperator))
  if (params.cooperationStatus?.trim()) search.set('cooperationStatus', params.cooperationStatus.trim())
  return request<import('@/types/api').PageResult<OrganizerDirectoryVO>>(`/api/user/console/organizers?${search.toString()}`)
}

export async function revokeOrganizer(organizerId: number, reason: string) {
  assertPositiveInteger(organizerId, '主办方ID')
  return request<OrganizerDirectoryVO>(`/api/user/console/organizers/${organizerId}/revoke`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function deleteSupportAccount(id: number) {
  assertPositiveInteger(id, '客服账号ID')
  return request<import('@/types/api').SupportAccountVO>(`/api/user/support/admin/accounts/${id}`, {
    method: 'DELETE',
  })
}

export async function listExceptionTasks(status?: string) {
  const qs = status ? `?status=${encodeURIComponent(status)}` : ''
  return request<import('@/types/api').ExceptionTaskVO[]>(`/api/user/console/exception-tasks${qs}`)
}

export async function createExceptionTask(params: import('@/types/api').ExceptionTaskCreatePayload) {
  return request<import('@/types/api').ExceptionTaskVO>('/api/user/console/exception-tasks', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function claimExceptionTask(taskId: number) {
  assertPositiveInteger(taskId, '异常任务ID')
  return request<import('@/types/api').ExceptionTaskVO>(`/api/user/console/exception-tasks/${taskId}/claim`, {
    method: 'POST',
  })
}

export async function resolveExceptionTask(taskId: number, result: string) {
  assertPositiveInteger(taskId, '异常任务ID')
  const normalized = result.trim()
  if (!normalized) throw new Error('处理结果不能为空')
  return request<import('@/types/api').ExceptionTaskVO>(`/api/user/console/exception-tasks/${taskId}/resolve`, {
    method: 'POST',
    body: JSON.stringify({ result: normalized } satisfies import('@/types/api').ExceptionTaskActionPayload),
  })
}

export async function closeExceptionTask(taskId: number, result: string) {
  assertPositiveInteger(taskId, '异常任务ID')
  const normalized = result.trim()
  if (!normalized) throw new Error('处理结果不能为空')
  return request<import('@/types/api').ExceptionTaskVO>(`/api/user/console/exception-tasks/${taskId}/close`, {
    method: 'POST',
    body: JSON.stringify({ result: normalized } satisfies import('@/types/api').ExceptionTaskActionPayload),
  })
}

export async function listReconciliationBatches() {
  return request<import('@/types/api').ReconciliationBatchVO[]>('/api/user/console/reconciliation/batches')
}

export async function createReconciliationBatch(bizDate: string) {
  return request<import('@/types/api').ReconciliationBatchVO>('/api/user/console/reconciliation/batches', {
    method: 'POST',
    body: JSON.stringify({ bizDate }),
  })
}

export async function getReconciliationBatchDetail(batchNo: string) {
  const normalized = batchNo.trim()
  if (!normalized) throw new Error('对账批次号不能为空')
  return request<import('@/types/api').ReconciliationBatchDetailVO>(`/api/user/console/reconciliation/batches/${encodeURIComponent(normalized)}`)
}

export async function resolveReconciliationDifference(batchNo: string, differenceId: number) {
  const normalized = batchNo.trim()
  if (!normalized) throw new Error('对账批次号不能为空')
  assertPositiveInteger(differenceId, '对账差异ID')
  return request<import('@/types/api').ReconciliationDifferenceVO>(`/api/user/console/reconciliation/batches/${encodeURIComponent(normalized)}/differences/${differenceId}/resolve`, {
    method: 'POST',
  })
}

export async function ignoreReconciliationDifference(batchNo: string, differenceId: number) {
  const normalized = batchNo.trim()
  if (!normalized) throw new Error('对账批次号不能为空')
  assertPositiveInteger(differenceId, '对账差异ID')
  return request<import('@/types/api').ReconciliationDifferenceVO>(`/api/user/console/reconciliation/batches/${encodeURIComponent(normalized)}/differences/${differenceId}/ignore`, {
    method: 'POST',
  })
}

export async function listRbacRoles() {
  return request<import('@/types/api').RbacRoleVO[]>('/api/user/console/rbac/roles')
}

export async function listRbacPermissions() {
  return request<import('@/types/api').RbacPermissionVO[]>('/api/user/console/rbac/permissions')
}

export async function updateRbacRolePermissions(roleCode: string, permissionCodes: string[]) {
  return request<void>(`/api/user/console/rbac/roles/${encodeURIComponent(roleCode)}/permissions`, {
    method: 'PUT',
    body: JSON.stringify({ permissionCodes }),
  })
}

export async function searchRbacUsers(keyword: string) {
  const normalized = keyword.trim()
  if (!normalized) return []
  return request<import('@/types/api').RbacUserPermissionSummaryVO[]>(
    `/api/user/console/rbac/users?keyword=${encodeURIComponent(normalized)}`,
  )
}

export async function getRbacUserPermissions(userId: number) {
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').RbacUserPermissionVO>(`/api/user/console/rbac/users/${userId}/permissions`)
}

export async function updateRbacUserPermissionOverrides(
  userId: number,
  payload: import('@/types/api').RbacUserPermissionOverridePayload,
) {
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').RbacUserPermissionVO>(`/api/user/console/rbac/users/${userId}/permissions`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export async function listOrganizerAdminAccounts() {
  return request<import('@/types/api').OrganizerAdminAccountVO[]>('/api/user/console/organizer-admins')
}

export async function createOrganizerAdminAccount(params: { phone: string; nickname: string; password: string }) {
  return request<import('@/types/api').OrganizerAdminAccountVO>('/api/user/console/organizer-admins', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function updateOrganizerAdminAccount(id: number, params: { phone: string; nickname: string; password?: string; status?: number }) {
  assertPositiveInteger(id, '平台主办方运营员账号ID')
  return request<import('@/types/api').OrganizerAdminAccountVO>(`/api/user/console/organizer-admins/${id}`, {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function deactivateOrganizerAdminAccount(id: number) {
  assertPositiveInteger(id, '平台主办方运营员账号ID')
  return request<import('@/types/api').OrganizerAdminAccountVO>(`/api/user/console/organizer-admins/${id}/deactivate`, {
    method: 'POST',
  })
}

export async function deleteOrganizerAdminAccount(id: number) {
  assertPositiveInteger(id, '平台主办方运营员账号ID')
  return request<import('@/types/api').OrganizerAdminAccountVO>(`/api/user/console/organizer-admins/${id}`, {
    method: 'DELETE',
  })
}

export async function listOrganizerOpsAssignments() {
  return request<OrganizerOpsAssignmentVO[]>('/api/user/console/organizer-ops/assignments')
}

export async function updateOrganizerOpsAssignment(organizerUserId: number, params: OrganizerOpsAssignmentPayload) {
  assertPositiveInteger(organizerUserId, '主办方ID')
  return request<OrganizerOpsAssignmentVO>(`/api/user/console/organizer-ops/assignments/${organizerUserId}`, {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function listOrganizerOpsFollowUps(organizerUserId: number) {
  assertPositiveInteger(organizerUserId, '主办方ID')
  return request<OrganizerOpsFollowUpVO[]>(`/api/user/console/organizer-ops/assignments/${organizerUserId}/follow-ups`)
}

export async function createOrganizerOpsFollowUp(organizerUserId: number, params: OrganizerOpsFollowUpPayload) {
  assertPositiveInteger(organizerUserId, '主办方ID')
  return request<OrganizerOpsFollowUpVO>(`/api/user/console/organizer-ops/assignments/${organizerUserId}/follow-ups`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function listOperationAuditLogs(params: {
  operatorId?: number | null
  action?: string
  targetType?: string
  success?: boolean | null
  traceId?: string
  limit?: number
} = {}) {
  const search = new URLSearchParams()
  if (params.operatorId) search.set('operatorId', String(params.operatorId))
  if (params.action?.trim()) search.set('action', params.action.trim())
  if (params.targetType?.trim()) search.set('targetType', params.targetType.trim())
  if (params.success !== undefined && params.success !== null) search.set('success', String(params.success))
  if (params.traceId?.trim()) search.set('traceId', params.traceId.trim())
  if (params.limit) search.set('limit', String(params.limit))
  const qs = search.toString()
  return request<import('@/types/api').OperationAuditLogVO[]>(`/api/user/console/audit-logs${qs ? `?${qs}` : ''}`)
}

export { ApiError }

// ========== 票务服务 ==========

export async function listActivities(params: {
  page?: number
  size?: number
  categoryId?: number
  keyword?: string
  city?: string
  dateFrom?: string
  dateTo?: string
  minPrice?: number
  maxPrice?: number
  saleStatus?: string
  seatMapOnly?: boolean
  isSupportSeat?: boolean
  realNameRequired?: boolean
  sort?: string
}) {
  const searchParams = new URLSearchParams()
  if (params.page) searchParams.set('page', String(params.page))
  if (params.size) searchParams.set('size', String(params.size))
  if (params.categoryId) searchParams.set('categoryId', String(params.categoryId))
  if (params.keyword?.trim()) searchParams.set('keyword', params.keyword.trim())
  if (params.city?.trim()) searchParams.set('city', params.city.trim())
  if (params.dateFrom) searchParams.set('dateFrom', params.dateFrom)
  if (params.dateTo) searchParams.set('dateTo', params.dateTo)
  if (params.minPrice != null) searchParams.set('minPrice', String(params.minPrice))
  if (params.maxPrice != null) searchParams.set('maxPrice', String(params.maxPrice))
  if (params.saleStatus) searchParams.set('saleStatus', params.saleStatus)
  const isSupportSeat = params.isSupportSeat ?? params.seatMapOnly
  if (isSupportSeat !== undefined) searchParams.set('isSupportSeat', String(isSupportSeat))
  if (params.realNameRequired !== undefined) searchParams.set('realNameRequired', String(params.realNameRequired))
  if (params.sort) searchParams.set('sort', params.sort)
  const qs = searchParams.toString()
  return request<import('@/types/api').PageResult<import('@/types/api').ActivityVO>>(
    `/api/ticket/activities${qs ? `?${qs}` : ''}`
  )
}

export async function getActivityDetail(id: number) {
  return request<import('@/types/api').ActivityDetailVO>(`/api/ticket/activities/${id}`)
}

export async function interpretAiTicketFinder(query: string) {
  return request<import('@/types/api').FinderResponse>('/api/ticket/ai/finder/interpret', {
    method: 'POST',
    body: JSON.stringify({ query }),
  }, { timeoutMs: AI_FINDER_REQUEST_TIMEOUT_MS })
}

export async function searchAiTicketFinder(query: string) {
  return request<import('@/types/api').FinderResponse>('/api/ticket/ai/finder/search', {
    method: 'POST',
    body: JSON.stringify({ query }),
  }, { timeoutMs: AI_FINDER_REQUEST_TIMEOUT_MS })
}

export async function listActivityReviews(activityId: number) {
  assertPositiveInteger(activityId, '活动ID')
  return request<import('@/types/api').ActivityReviewListVO>(`/api/ticket/activities/${activityId}/reviews`)
}

export async function createActivityReview(activityId: number, params: { orderId?: number | null; rating: number; content?: string | null; images?: string | null }) {
  assertPositiveInteger(activityId, '活动ID')
  return request<import('@/types/api').ActivityReviewVO>(`/api/ticket/activities/${activityId}/reviews`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function reportActivityReview(activityId: number, reviewId: number, reason: string) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(reviewId, '评价ID')
  return request<import('@/types/api').ActivityReviewReportVO>(`/api/ticket/activities/${activityId}/reviews/${reviewId}/reports`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function listActivityQuestions(activityId: number) {
  assertPositiveInteger(activityId, '活动ID')
  return request<import('@/types/api').ActivityQuestionVO[]>(`/api/ticket/activities/${activityId}/questions`)
}

export async function createActivityQuestion(activityId: number, content: string) {
  assertPositiveInteger(activityId, '活动ID')
  return request<import('@/types/api').ActivityQuestionVO>(`/api/ticket/activities/${activityId}/questions`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}

type ActivityEngagementListParams = { activityId?: number; status?: number }
type ActivityEngagementScopedReviewParams = { itemType?: string; status?: number }
type ActivityEngagementScopedStringStatusParams = { itemType?: string; status?: string }

export async function listAdminActivityEngagements(params: { page?: number; size?: number; keyword?: string; itemType?: string; todoOnly?: boolean } = {}) {
  const search = new URLSearchParams()
  search.set('page', String(params.page || 1))
  search.set('size', String(params.size || 10))
  if (params.keyword?.trim()) search.set('keyword', params.keyword.trim())
  if (params.itemType) search.set('itemType', params.itemType)
  if (params.todoOnly != null) search.set('todoOnly', String(params.todoOnly))
  return request<import('@/types/api').PageResult<import('@/types/api').ActivityEngagementOverviewVO>>(
    `/api/ticket/admin/activity-engagements?${search.toString()}`,
    undefined,
    { timeoutMs: CONSOLE_ADMIN_REQUEST_TIMEOUT_MS }
  )
}

export function listAdminActivityReviews(params?: ActivityEngagementListParams): Promise<import('@/types/api').ActivityReviewVO[]>
export function listAdminActivityReviews(activityId: number, params?: ActivityEngagementScopedReviewParams): Promise<import('@/types/api').ActivityReviewVO[]>
export async function listAdminActivityReviews(
  activityIdOrParams: number | ActivityEngagementListParams = {},
  params: ActivityEngagementScopedReviewParams = {}
) {
  const search = new URLSearchParams()
  if (typeof activityIdOrParams === 'number') {
    assertPositiveInteger(activityIdOrParams, '活动ID')
    if (params.itemType) search.set('itemType', params.itemType)
    if (params.status != null) search.set('status', String(params.status))
    const qs = search.toString()
    return request<import('@/types/api').ActivityReviewVO[]>(`/api/ticket/admin/activities/${activityIdOrParams}/reviews${qs ? `?${qs}` : ''}`)
  }
  if (activityIdOrParams.activityId) search.set('activityId', String(activityIdOrParams.activityId))
  if (activityIdOrParams.status != null) search.set('status', String(activityIdOrParams.status))
  const qs = search.toString()
  return request<import('@/types/api').ActivityReviewVO[]>(`/api/ticket/admin/activity-engagement/reviews${qs ? `?${qs}` : ''}`)
}

export async function moderateAdminActivityReview(reviewId: number, action: 'APPROVE' | 'HIDE' | 'RESTORE', note?: string) {
  assertPositiveInteger(reviewId, '评价ID')
  const body: import('@/types/api').ActivityReviewModerationRequest = note ? { action, note } : { action }
  return request<import('@/types/api').ActivityReviewVO>(`/api/ticket/admin/activity-engagement/reviews/${reviewId}/moderation`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function listAdminActivityReviewReports(status?: string): Promise<import('@/types/api').ActivityReviewReportVO[]>
export function listAdminActivityReviewReports(activityId: number, params?: ActivityEngagementScopedStringStatusParams): Promise<import('@/types/api').ActivityReviewReportVO[]>
export async function listAdminActivityReviewReports(
  statusOrActivityId?: string | number,
  params: ActivityEngagementScopedStringStatusParams = {}
) {
  const search = new URLSearchParams()
  if (typeof statusOrActivityId === 'number') {
    assertPositiveInteger(statusOrActivityId, '活动ID')
    if (params.itemType) search.set('itemType', params.itemType)
    if (params.status) search.set('status', params.status)
    const qs = search.toString()
    return request<import('@/types/api').ActivityReviewReportVO[]>(`/api/ticket/admin/activities/${statusOrActivityId}/reports${qs ? `?${qs}` : ''}`)
  }
  if (statusOrActivityId) search.set('status', statusOrActivityId)
  const qs = search.toString()
  return request<import('@/types/api').ActivityReviewReportVO[]>(`/api/ticket/admin/activity-engagement/review-reports${qs ? `?${qs}` : ''}`)
}

export async function moderateAdminActivityReviewReport(reportId: number, action: 'RESOLVE' | 'HIDE' | 'REJECT', note?: string) {
  assertPositiveInteger(reportId, '举报ID')
  const body: import('@/types/api').ActivityReviewReportModerationRequest = note ? { action, note } : { action }
  return request<import('@/types/api').ActivityReviewReportVO>(`/api/ticket/admin/activity-engagement/review-reports/${reportId}/moderation`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function listAdminActivityQuestions(params?: { activityId?: number; status?: string }): Promise<import('@/types/api').ActivityQuestionVO[]>
export function listAdminActivityQuestions(activityId: number, params?: ActivityEngagementScopedStringStatusParams): Promise<import('@/types/api').ActivityQuestionVO[]>
export async function listAdminActivityQuestions(
  activityIdOrParams: number | { activityId?: number; status?: string } = {},
  params: ActivityEngagementScopedStringStatusParams = {}
) {
  const search = new URLSearchParams()
  if (typeof activityIdOrParams === 'number') {
    assertPositiveInteger(activityIdOrParams, '活动ID')
    if (params.itemType) search.set('itemType', params.itemType)
    if (params.status) search.set('status', params.status)
    const qs = search.toString()
    return request<import('@/types/api').ActivityQuestionVO[]>(`/api/ticket/admin/activities/${activityIdOrParams}/questions${qs ? `?${qs}` : ''}`)
  }
  if (activityIdOrParams.activityId) search.set('activityId', String(activityIdOrParams.activityId))
  if (activityIdOrParams.status) search.set('status', activityIdOrParams.status)
  const qs = search.toString()
  return request<import('@/types/api').ActivityQuestionVO[]>(`/api/ticket/admin/activity-engagement/questions${qs ? `?${qs}` : ''}`)
}

export async function moderateAdminActivityQuestion(questionId: number, params: import('@/types/api').ActivityQuestionModerationRequest) {
  assertPositiveInteger(questionId, '问题ID')
  return request<import('@/types/api').ActivityQuestionVO>(`/api/ticket/admin/activity-engagement/questions/${questionId}/moderation`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function updateAdminActivityReviewStatus(activityId: number, reviewId: number, body: import('@/types/api').ActivityReviewStatusRequest) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(reviewId, '评价ID')
  const { itemType = 'ACTIVITY', ...payload } = body
  const search = new URLSearchParams({ itemType })
  return request<import('@/types/api').ActivityReviewVO>(`/api/ticket/admin/activities/${activityId}/reviews/${reviewId}/status?${search.toString()}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export async function replyAdminActivityQuestion(activityId: number, questionId: number, body: import('@/types/api').ActivityQuestionReplyRequest) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(questionId, '问题ID')
  const { itemType = 'ACTIVITY', ...payload } = body
  const search = new URLSearchParams({ itemType })
  return request<import('@/types/api').ActivityQuestionVO>(`/api/ticket/admin/activities/${activityId}/questions/${questionId}/reply?${search.toString()}`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export async function updateAdminActivityQuestion(activityId: number, questionId: number, body: import('@/types/api').ActivityQuestionUpdateRequest) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(questionId, '问题ID')
  const { itemType = 'ACTIVITY', ...payload } = body
  const search = new URLSearchParams({ itemType })
  return request<import('@/types/api').ActivityQuestionVO>(`/api/ticket/admin/activities/${activityId}/questions/${questionId}?${search.toString()}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export async function updateAdminActivityReportStatus(activityId: number, reportId: number, body: import('@/types/api').ActivityReviewReportStatusRequest) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(reportId, '举报ID')
  const { itemType = 'ACTIVITY', ...payload } = body
  const search = new URLSearchParams({ itemType })
  return request<import('@/types/api').ActivityReviewReportVO>(`/api/ticket/admin/activities/${activityId}/reports/${reportId}/status?${search.toString()}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export async function getSeatMap(sessionId: number, ticketTypeId: number) {
  return request<SeatMapResponse>(`/api/ticket/sessions/${sessionId}/ticket-types/${ticketTypeId}/seats`)
}

export async function listCategories() {
  return request<import('@/types/api').CategoryVO[]>('/api/ticket/categories')
}

export async function getSearchHistory() {
  return request<string[]>('/api/v1/search/history')
}

export async function addSearchHistory(keyword: string) {
  return request<string[]>('/api/v1/search/history', {
    method: 'POST',
    body: JSON.stringify({ keyword }),
  })
}

export async function clearSearchHistory() {
  return request<void>('/api/v1/search/history', { method: 'DELETE' })
}

export async function getSearchTrending() {
  return request<import('@/types/api').SearchTrendingItem[]>('/api/v1/search/trending')
}

export async function searchAdminArtists(keyword: string) {
  const params = new URLSearchParams()
  if (keyword.trim()) params.set('keyword', keyword.trim())
  const qs = params.toString()
  return request<import('@/types/api').ArtistSearchVO[]>(`/api/ticket/admin/artists/search${qs ? `?${qs}` : ''}`)
}

export async function listAdminArtists(params: import('@/types/api').ArtistListParams = {}) {
  const search = new URLSearchParams()
  search.set('page', String(params.page ?? 1))
  search.set('size', String(params.size ?? 10))
  if (params.keyword?.trim()) search.set('keyword', params.keyword.trim())
  if (params.category?.trim()) search.set('category', params.category.trim())
  if (params.status) search.set('status', params.status)
  if (params.reviewStatus) search.set('reviewStatus', params.reviewStatus)
  if (params.riskStatus) search.set('riskStatus', params.riskStatus)
  return request<import('@/types/api').PageResult<import('@/types/api').ArtistEntity>>(`/api/ticket/admin/artists?${search.toString()}`)
}

export async function getAdminArtist(id: number) {
  assertPositiveInteger(id, 'artistId')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}`)
}

export async function updateAdminArtist(id: number, params: import('@/types/api').ArtistUpdateRequest) {
  assertPositiveInteger(id, 'artistId')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}`, {
    method: 'PUT',
    body: JSON.stringify(params),
  })
}

export async function submitAdminArtist(params: import('@/types/api').ArtistSubmissionRequest) {
  return request<import('@/types/api').ArtistEntity>('/api/ticket/admin/artists/submissions', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function listPendingAdminArtists(params: import('@/types/api').ArtistListParams = {}) {
  const search = new URLSearchParams()
  search.set('page', String(params.page ?? 1))
  search.set('size', String(params.size ?? 10))
  if (params.keyword?.trim()) search.set('keyword', params.keyword.trim())
  if (params.category?.trim()) search.set('category', params.category.trim())
  if (params.status) search.set('status', params.status)
  if (params.reviewStatus) search.set('status', params.reviewStatus)
  if (params.riskStatus) search.set('riskStatus', params.riskStatus)
  if (params.qualificationStatus) search.set('qualificationStatus', params.qualificationStatus)
  return request<import('@/types/api').PageResult<import('@/types/api').ArtistEntity>>(`/api/ticket/admin/artists/pending?${search.toString()}`)
}

export async function reviewAdminArtist(id: number, params: import('@/types/api').ArtistReviewRequest) {
  assertPositiveInteger(id, 'artistId')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}/review`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function approveAdminArtist(id: number, params: Pick<import('@/types/api').ArtistReviewRequest, 'note'> = {}) {
  assertPositiveInteger(id, 'artistId')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function rejectAdminArtist(id: number, params: Pick<import('@/types/api').ArtistReviewRequest, 'note'>) {
  assertPositiveInteger(id, 'artistId')
  const note = params.note?.trim()
  if (!note) throw new ApiError(400, '驳回原因不能为空')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  })
}

export async function updateAdminArtistRisk(id: number, params: import('@/types/api').ArtistRiskRequest) {
  assertPositiveInteger(id, 'artistId')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}/risk`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function markRiskAdminArtist(id: number, params: { reason?: string | null }) {
  assertPositiveInteger(id, 'artistId')
  const reason = params.reason?.trim()
  if (!reason) throw new ApiError(400, '风险原因不能为空')
  return request<import('@/types/api').ArtistEntity>(`/api/ticket/admin/artists/${id}/mark-risk`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function submitActivityRiskResolution(id: number, params: import('@/types/api').ActivityRiskResolutionRequest) {
  assertPositiveInteger(id, 'activityId')
  const { userId: _userId, ...safeParams } = params
  return request<import('@/types/api').ActivityRiskResolutionVO>(`/api/ticket/admin/activities/${id}/risk-resolution`, {
    method: 'POST',
    body: JSON.stringify(safeParams),
  })
}

export async function listActivityRiskResolutions(paramsOrStatus: import('@/types/api').RiskResolutionListParams | string = {}) {
  const params: import('@/types/api').RiskResolutionListParams = typeof paramsOrStatus === 'string' ? { status: paramsOrStatus } : paramsOrStatus
  const search = new URLSearchParams()
  search.set('page', String(params.page ?? 1))
  search.set('size', String(params.size ?? 10))
  if (params.keyword?.trim()) search.set('keyword', params.keyword.trim())
  if (params.reasonType?.trim()) search.set('reasonType', params.reasonType.trim())
  if (params.status) search.set('status', params.status)
  return request<import('@/types/api').PageResult<import('@/types/api').ActivityRiskResolutionVO>>(`/api/ticket/admin/risk-resolutions?${search.toString()}`)
}

export async function listMyNotifications() {
  return request<import('@/types/api').NotificationVO[]>('/api/notification/list')
}

export async function getNotificationSummary() {
  return request<import('@/types/api').NotificationSummaryVO>('/api/notification/summary')
}

const NOTIFICATION_PREFERENCES: NotificationPreferenceVO[] = [
  {
    channel: 'IN_APP',
    label: '站内通知',
    enabled: true,
    locked: true,
    statusText: '已开启',
    description: '订单、候补、抢票、退款、改期和客服回复都会通过站内消息提醒。',
  },
  {
    channel: 'SMS',
    label: '短信通知',
    enabled: false,
    locked: true,
    statusText: '暂不可用',
    description: '当前仅提供站内消息提醒；短信通知开放后可在这里开启。',
  },
]

export async function getNotificationPreferences() {
  return NOTIFICATION_PREFERENCES.map((item) => ({ ...item }))
}

export async function markAllNotificationsRead() {
  return request<import('@/types/api').NotificationSummaryVO>('/api/notification/read-all', {
    method: 'POST',
  })
}

export async function markNotificationRead(id: number) {
  assertPositiveInteger(id, '通知ID')
  return request<import('@/types/api').NotificationSummaryVO>(`/api/notification/${id}/read`, {
    method: 'POST',
  })
}

export async function deleteReadNotifications() {
  return request<import('@/types/api').NotificationSummaryVO>('/api/notification/read', {
    method: 'DELETE',
  })
}

export async function deleteNotification(id: number) {
  assertPositiveInteger(id, '通知ID')
  return request<import('@/types/api').NotificationSummaryVO>(`/api/notification/${id}`, {
    method: 'DELETE',
  })
}

export async function suspendActivityForRisk(id: number, params: { userId: number; reason?: string }) {
  assertPositiveInteger(id, 'activityId')
  const { userId: _userId, ...safeParams } = params
  return request<import('@/types/api').ActivityRiskResolutionVO>(`/api/ticket/admin/activities/${id}/suspend`, {
    method: 'POST',
    body: JSON.stringify(safeParams),
  })
}

export async function listAdminRiskCases() {
  return request<import('@/types/api').ActivityRiskCaseVO[]>('/api/ticket/admin/risk-cases')
}

export async function reviewActivityRiskResolution(id: number, params: import('@/types/api').ActivityRiskResolutionReviewRequest) {
  assertPositiveInteger(id, 'resolutionId')
  const { userId: _userId, ...safeParams } = params
  return request<import('@/types/api').ActivityRiskResolutionVO>(`/api/ticket/admin/risk-resolutions/${id}/review`, {
    method: 'POST',
    body: JSON.stringify(safeParams),
  })
}

export async function approveActivityRiskResolution(id: number, params: { reviewNote?: string | null } = {}) {
  assertPositiveInteger(id, 'resolutionId')
  return request<import('@/types/api').ActivityRiskResolutionVO>(`/api/ticket/admin/risk-resolutions/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote: params.reviewNote?.trim() || null }),
  })
}

export async function rejectActivityRiskResolution(id: number, params: { reviewNote?: string | null }) {
  assertPositiveInteger(id, 'resolutionId')
  const reviewNote = params.reviewNote?.trim()
  if (!reviewNote) throw new ApiError(400, '驳回原因不能为空')
  return request<import('@/types/api').ActivityRiskResolutionVO>(`/api/ticket/admin/risk-resolutions/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote }),
  })
}

export async function uploadTicketAsset(params: { userId: number; bizType: string; file: File }) {
  const formData = new FormData()
  formData.append('bizType', params.bizType)
  formData.append('file', params.file)
  return multipartRequest<AssetUploadVO>('/api/ticket/admin/assets', formData)
}

export async function uploadPrivateAsset(params: { userId: number; bizType: string; file: File }) {
  const formData = new FormData()
  formData.append('bizType', params.bizType)
  formData.append('file', params.file)
  return multipartRequest<PrivateAssetVO>('/api/ticket/admin/private-assets', formData)
}

export function privateAssetDownloadUrl(id: number) {
  assertPositiveInteger(id, '附件ID')
  return `${BASE_URL}/api/ticket/admin/private-assets/${id}/download`
}

export async function createReservation(_userId: number, sessionId: number) {
  return request<void>('/api/ticket/reservations', {
    method: 'POST',
    body: JSON.stringify({ sessionId }),
  })
}

export async function listReservations(_userId: number) {
  return request<import('@/types/api').ReservationEntity[]>('/api/ticket/reservations')
}

export async function createSubscription(params: import('@/types/api').SubscriptionPayload) {
  return request<import('@/types/api').SubscriptionVO>('/api/ticket/subscriptions', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function listSubscriptions() {
  return request<import('@/types/api').SubscriptionVO[]>('/api/ticket/subscriptions')
}

export async function cancelSubscription(id: number) {
  assertPositiveInteger(id, '订阅ID')
  return request<void>(`/api/ticket/subscriptions/${id}`, { method: 'DELETE' })
}

// ========== 订单服务 ==========

export async function submitGrabRequest(params: SubmitGrabRequestPayload) {
  return request<GrabRequestResult>('/api/grab/requests', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function getGrabRequest(requestId: string) {
  return request<GrabRequestResult>(`/api/grab/requests/${encodeURIComponent(requestId)}`)
}

export async function getGrabProgress(requestId: string) {
  return request<GrabProgressResult>(`/api/grab/requests/${encodeURIComponent(requestId)}/progress`)
}

export async function getGrabVisibleStock(sessionId: number, ticketTypeIds: number[]) {
  const params = new URLSearchParams()
  params.set('ticketTypeIds', ticketTypeIds.join(','))
  return request<SessionVisibleStockResult>(`/api/grab/sessions/${sessionId}/stock-visible?${params.toString()}`)
}

export async function cancelGrabRequest(requestId: string) {
  return request<GrabRequestResult>(`/api/grab/requests/${encodeURIComponent(requestId)}/cancel`, { method: 'POST' })
}

export async function createWaitlistEntry(params: { sessionId: number; ticketTypeId: number; quantity: number; attendeeIds: number[] }) {
  return request<import('@/types/api').WaitlistEntryVO>('/api/waitlist/entries', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export async function listMyWaitlistEntries() {
  return request<import('@/types/api').WaitlistEntryVO[]>('/api/waitlist/my')
}

export async function cancelWaitlistEntry(id: number) {
  assertPositiveInteger(id, 'waitlistId')
  return request<import('@/types/api').WaitlistEntryVO>(`/api/waitlist/entries/${id}`, { method: 'DELETE' })
}

export async function listOrders() {
  return request<import('@/types/api').OrderEntity[]>('/api/order/my')
}

export async function listMyTickets() {
  return request<import('@/types/api').TicketWalletItemVO[]>('/api/order/tickets')
}

export async function createTicketEntryCode(ticketId: number) {
  assertPositiveInteger(ticketId, '电子票ID')
  return request<import('@/types/api').TicketEntryCodeVO>(`/api/order/tickets/${ticketId}/entry-code`, { method: 'POST' })
}

export async function createTicketTransfer(ticketId: number) {
  assertPositiveInteger(ticketId, '电子票ID')
  return request<import('@/types/api').TicketTransferCreateVO>(`/api/order/tickets/${ticketId}/transfer`, { method: 'POST' })
}

export async function revokeTicketTransfer(ticketId: number) {
  assertPositiveInteger(ticketId, '电子票ID')
  return request<import('@/types/api').TicketTransferRevokeVO>(`/api/order/tickets/${ticketId}/transfer/revoke`, { method: 'POST' })
}

export async function claimTicketTransfer(transferCode: string) {
  return request<import('@/types/api').TicketTransferClaimVO>('/api/order/tickets/transfers/claim', {
    method: 'POST',
    body: JSON.stringify({ transferCode }),
  })
}

export async function listTrashOrders() {
  return request<import('@/types/api').OrderEntity[]>('/api/order/my/trash')
}

export async function getOrderDetail(id: number) {
  return request<import('@/types/api').OrderEntity>(`/api/order/${id}`)
}

export async function cancelOrder(id: number) {
  return request<void>(`/api/order/${id}`, { method: 'DELETE' })
}

export async function hideOrder(id: number) {
  return request<void>(`/api/order/${id}/hide`, { method: 'POST' })
}

export async function restoreOrder(id: number) {
  return request<void>(`/api/order/${id}/restore`, { method: 'POST' })
}

export async function payOrder(id: number) {
  return request<{ payUrl: string }>(`/api/order/${id}/pay`, { method: 'POST' })
}

export async function createAlipayPagePay(orderId: number) {
  return request<import('@/types/api').PagePayResponse>('/api/payment/alipay/page-pay', {
    method: 'POST',
    body: JSON.stringify({ orderId }),
  })
}

export async function createAlipayQrPay(orderId: number) {
  return request<import('@/types/api').QrPayResponse>('/api/payment/alipay/qr-pay', {
    method: 'POST',
    body: JSON.stringify({ orderId }),
  }, { timeoutMs: QR_PAY_REQUEST_TIMEOUT_MS })
}

export async function syncAlipayPayment(orderId: number) {
  return request<import('@/types/api').PaymentStatusResponse>(`/api/payment/alipay/sync/${orderId}`)
}

export async function getRefundOptions(orderId: number) {
  return request<import('@/types/api').RefundOptionsVO>(`/api/order/${orderId}/refund-options`)
}

export async function applyRefund(
  orderId: number,
  reasonOrParams?: string | { reason?: string; reasonType?: string; quantity?: number; orderSeatIds?: number[] },
  reasonType?: string,
) {
  const body = typeof reasonOrParams === 'object' && reasonOrParams !== null
    ? { orderId, ...reasonOrParams }
    : { orderId, reason: reasonOrParams, reasonType }
  return request<import('@/types/api').RefundRequestVO>('/api/payment/refunds/apply', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function listMyRefunds() {
  return request<import('@/types/api').RefundRequestVO[]>('/api/payment/refunds/my')
}

export async function listAdminRefunds(status?: number) {
  const qs = status === undefined ? '' : `?status=${status}`
  return request<import('@/types/api').RefundRequestVO[]>(`/api/payment/refunds/admin${qs}`)
}

export async function approveRefund(id: number, reviewNote?: string) {
  return request<import('@/types/api').RefundRequestVO>(`/api/payment/refunds/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote }),
  })
}

export async function rejectRefund(id: number, reviewNote?: string) {
  return request<import('@/types/api').RefundRequestVO>(`/api/payment/refunds/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote }),
  })
}

export async function batchReviewRefunds(ids: number[], action: 'APPROVE' | 'REJECT', reason: string) {
  if (!ids.length) throw new ApiError(400, '退款申请不能为空')
  for (const id of ids) assertPositiveInteger(id, '退款申请ID')
  const normalizedReason = reason.trim()
  if (!normalizedReason) throw new ApiError(400, '批量审核原因不能为空')
  return request<import('@/types/api').RefundRequestVO[]>('/api/payment/refunds/admin/batch-review', {
    method: 'POST',
    body: JSON.stringify({ ids, action, reason: normalizedReason }),
  })
}

export function submitPayForm(payForm: string) {
  const container = document.createElement('div')
  container.style.display = 'none'
  container.innerHTML = payForm
  document.body.appendChild(container)

  const form = container.querySelector('form')
  if (!form) {
    container.remove()
    throw new Error('支付宝支付表单无效')
  }

  form.submit()
}

// ========== B端管理接口 ==========

export async function getAdminSummary() {
  return request<import('@/types/api').AdminSummaryVO>('/api/ticket/admin/summary')
}

export async function getActivityMarketing(activityId: number) {
  assertPositiveInteger(activityId, 'activityId')
  return request<import('@/types/api').ActivityMarketingOverviewVO>(`/api/ticket/admin/activities/${activityId}/marketing`)
}

export async function updateActivityMarketing(activityId: number, body: ActivityMarketingRulePayload) {
  assertPositiveInteger(activityId, 'activityId')
  const { userId: _userId, ...safeBody } = body
  return request<import('@/types/api').ActivityMarketingOverviewVO>(`/api/ticket/admin/activities/${activityId}/marketing`, {
    method: 'PUT',
    body: JSON.stringify(safeBody),
  })
}

export async function getGrabOpsSummary() {
  return request<import('@/types/api').GrabOpsSummaryVO>('/api/grab/admin/ops-summary')
}

export async function getPlatformOpsSummary() {
  return request<import('@/types/api').PlatformOpsSummaryVO>('/api/user/console/ops-summary')
}

export async function listConsoleOrders(params: { paidOnly?: boolean } = {}) {
  const searchParams = new URLSearchParams()
  if (params.paidOnly !== undefined) searchParams.set('paidOnly', String(params.paidOnly))
  return request<import('@/types/api').OrderEntity[]>(`/api/ticket/admin/orders?${searchParams.toString()}`)
}

export async function getCheckInOverview(sessionId: number) {
  assertPositiveInteger(sessionId, '场次ID')
  return request<import('@/types/api').CheckInOverviewVO>(
    `/api/ticket/admin/check-in/overview?sessionId=${encodeURIComponent(String(sessionId))}`
  )
}

export async function listCheckInRecords(params: { sessionId: number; result?: string; page?: number; size?: number }) {
  assertPositiveInteger(params.sessionId, '场次ID')
  const searchParams = new URLSearchParams()
  searchParams.set('sessionId', String(params.sessionId))
  if (params.result) searchParams.set('result', params.result)
  if (params.page) searchParams.set('page', String(params.page))
  if (params.size) searchParams.set('size', String(params.size))
  return request<import('@/types/api').CheckInRecordVO[]>(
    `/api/ticket/admin/check-in/records?${searchParams.toString()}`
  )
}

export async function manualCheckInTicket(params: { sessionId: number; entryCode: string; deviceCode?: string | null }) {
  assertPositiveInteger(params.sessionId, '场次ID')
  const entryCode = params.entryCode.trim()
  if (!entryCode) throw new ApiError(400, '票码不能为空')
  const deviceCode = params.deviceCode?.trim()
  return request<import('@/types/api').CheckInRecordVO>('/api/ticket/admin/check-in/manual', {
    method: 'POST',
    body: JSON.stringify({
      sessionId: params.sessionId,
      entryCode,
      ...(deviceCode ? { deviceCode } : {}),
    }),
  })
}

export async function listAdminTours(userId: number, params: { page?: number; size?: number; categoryId?: number } = {}) {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page || 1))
  searchParams.set('size', String(params.size || 10))
  if (params.categoryId) searchParams.set('categoryId', String(params.categoryId))
  return request<import('@/types/api').PageResult<import('@/types/api').TourEntity>>(
    `/api/ticket/admin/tours?${searchParams.toString()}`
  )
}

export async function getAdminTourDetail(userId: number, tourId: number) {
  return request<import('@/types/api').TourAdminDetailVO>(`/api/ticket/admin/tours/${tourId}`)
}

export async function deleteTourDraft(userId: number, tourId: number) {
  assertPositiveInteger(userId, '用户ID')
  assertPositiveInteger(tourId, '巡演ID')
  return request<void>(`/api/ticket/admin/tours/${tourId}`, { method: 'DELETE' })
}

export async function announceTourCities(userId: number, tourId: number) {
  assertPositiveInteger(userId, '用户ID')
  assertPositiveInteger(tourId, '巡演ID')
  return request<import('@/types/api').TourEntity>(`/api/ticket/admin/tours/${tourId}/announce`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function deactivateTour(tourId: number, body: { userId: number; confirmRefund: boolean; reason?: string }) {
  assertPositiveInteger(tourId, '巡演ID')
  assertPositiveInteger(body.userId, '用户ID')
  return request<import('@/types/api').RefundImpactResponse>(`/api/ticket/admin/tours/${tourId}/deactivate`, {
    method: 'POST',
    body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function createTourDraft(body: import('@/types/api').TourDraftCreatePayload) {
  return request<import('@/types/api').TourEntity>('/api/ticket/admin/tours/draft', {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function createStationDraft(tourId: number, body: Record<string, unknown>) {
  return request<import('@/types/api').StationEntity>(`/api/ticket/admin/tours/${tourId}/stations/draft`, {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function createActivityDraft(body: import('@/types/api').ActivityDraftPayload) {
  return request<import('@/types/api').ActivityDraftResponseVO>('/api/ticket/admin/activities/draft', {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function getActivityStation(activityId: number) {
  assertPositiveInteger(activityId, '活动ID')
  return request<import('@/types/api').StationConfigVersionDetailVO>(`/api/ticket/admin/activities/${activityId}/station`)
}

export async function createStationConfigVersion(stationId: number, body: import('@/types/api').StationConfigVersionPayload) {
  assertPositiveInteger(stationId, '站点ID')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/stations/${stationId}/config-versions`, {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function updateStationConfigVersion(versionId: number, body: import('@/types/api').StationConfigVersionPayload) {
  assertPositiveInteger(versionId, '站点配置版本ID')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-versions/${versionId}`, {
    method: 'PUT', body: JSON.stringify(body),
  })
}

export async function deleteStationConfigVersion(versionId: number) {
  assertPositiveInteger(versionId, '站点配置版本ID')
  return request<void>(`/api/ticket/admin/station-config-versions/${versionId}`, { method: 'DELETE' })
}

export async function submitStationConfigVersion(versionId: number) {
  assertPositiveInteger(versionId, '站点配置版本ID')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-versions/${versionId}/submit`, {
    method: 'POST',
  })
}

export async function withdrawStationConfigVersion(versionId: number) {
  assertPositiveInteger(versionId, '站点配置版本ID')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-versions/${versionId}/withdraw`, {
    method: 'POST',
  })
}

export async function listStationConfigReviews(params: import('@/types/api').StationConfigReviewListParams = {}) {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page ?? 1))
  searchParams.set('size', String(params.size ?? 10))
  if (params.keyword?.trim()) searchParams.set('keyword', params.keyword.trim())
  if (params.city?.trim()) searchParams.set('city', params.city.trim())
  if (params.changeType?.trim()) searchParams.set('changeType', params.changeType.trim())
  if (params.status) searchParams.set('status', params.status)
  return request<import('@/types/api').PageResult<import('@/types/api').StationConfigVersionVO>>(`/api/ticket/admin/station-config-reviews?${searchParams.toString()}`)
}

export async function getStationConfigReviewDiff(id: number) {
  assertPositiveInteger(id, 'stationConfigReviewId')
  return request<import('@/types/api').StationConfigReviewDiffVO>(`/api/ticket/admin/station-config-reviews/${id}/diff`)
}

export async function approveStationConfigVersion(versionId: number, body: { reviewNote?: string | null } = {}) {
  assertPositiveInteger(versionId, '站点配置版本ID')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-versions/${versionId}/approve`, {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function rejectStationConfigVersion(versionId: number, body: { reviewNote?: string | null } = {}) {
  assertPositiveInteger(versionId, '站点配置版本ID')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-versions/${versionId}/reject`, {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function approveStationConfigReview(id: number, body: { reviewNote?: string | null } = {}) {
  assertPositiveInteger(id, 'stationConfigReviewId')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-reviews/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote: body.reviewNote?.trim() || null }),
  })
}

export async function rejectStationConfigReview(id: number, body: { reviewNote?: string | null }) {
  assertPositiveInteger(id, 'stationConfigReviewId')
  const reviewNote = body.reviewNote?.trim()
  if (!reviewNote) throw new ApiError(400, '驳回原因不能为空')
  return request<import('@/types/api').StationConfigVersionVO>(`/api/ticket/admin/station-config-reviews/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote }),
  })
}

export async function publishStation(stationId: number, body: Record<string, unknown> & { perUserLimit?: number | null }) {
  return request<Record<string, unknown>>(`/api/ticket/admin/stations/${stationId}/publish`, {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function getTourDetail(id: number) {
  return request<import('@/types/api').TourDetailVO>(`/api/ticket/tours/${id}`)
}

export async function listAdminActivities(params: { page?: number; size?: number; keyword?: string; status?: number; categoryId?: number } = {}) {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page || 1))
  searchParams.set('size', String(params.size || 10))
  if (params.keyword?.trim()) searchParams.set('keyword', params.keyword.trim())
  if (params.status !== undefined) searchParams.set('status', String(params.status))
  if (params.categoryId) searchParams.set('categoryId', String(params.categoryId))
  return request<import('@/types/api').PageResult<import('@/types/api').ActivityEntity>>(
    `/api/ticket/admin/activities?${searchParams.toString()}`,
    undefined,
    { timeoutMs: CONSOLE_ADMIN_REQUEST_TIMEOUT_MS }
  )
}

export async function getAdminActivity(id: number) {
  return request<import('@/types/api').ActivityEntity>(`/api/ticket/admin/activities/${id}`)
}

export async function createAdminActivity(body: Record<string, unknown> & { perUserLimit?: number | null }) {
  const { userId: _userId, ...safeBody } = body
  return request<import('@/types/api').ActivityEntity>('/api/ticket/admin/activities', {
    method: 'POST', body: JSON.stringify(safeBody),
  })
}

export async function updateAdminActivity(id: number, body: Record<string, unknown> & { perUserLimit?: number | null }) {
  const { userId: _userId, ...safeBody } = body
  return request<import('@/types/api').ActivityEntity>(`/api/ticket/admin/activities/${id}`, {
    method: 'PUT', body: JSON.stringify(safeBody),
  })
}

export async function updateActivityStatus(id: number, body: Record<string, unknown>) {
  return request<void>(`/api/ticket/admin/activities/${id}/status`, {
    method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function deactivateActivity(id: number, body: { userId: number; confirmRefund: boolean; reason?: string }) {
  return request<import('@/types/api').RefundImpactResponse>(`/api/ticket/admin/activities/${id}/deactivate`, {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function notifyActivityBuyers(id: number, body: { userId: number; confirmNotify: boolean; content: string }) {
  const { userId: _userId, ...safeBody } = body
  return request<import('@/types/api').ActivityBuyerNotificationResponse>(`/api/ticket/admin/activities/${id}/buyer-notifications`, {
    method: 'POST', body: JSON.stringify(safeBody),
  })
}

export async function deactivateOrganizer(body: import('@/types/api').DeactivateOrganizerRequest) {
  return request<import('@/types/api').RefundImpactResponse>('/api/ticket/admin/organizers/deactivate', {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function batchOrganizerOnsaleSummary(organizerIds: number[], options: { timeoutMs?: number } = {}) {
  const normalizedIds = [...new Set(organizerIds.filter(id => Number.isInteger(id) && id > 0))]
  return request<OrganizerOnsaleSummaryVO[]>('/api/ticket/admin/organizers/batch-onsale-summary', {
    method: 'POST',
    body: JSON.stringify({ organizerIds: normalizedIds }),
  }, { timeoutMs: options.timeoutMs ?? 3000 })
}

export async function deleteAdminActivity(id: number, body: { userId: number; reason: string }) {
  return request<import('@/types/api').DeleteActivityResponse>(`/api/ticket/admin/activities/${id}`, {
    method: 'DELETE',
    body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function createAdminSession(body: Record<string, unknown>) {
  return request<import('@/types/api').SessionEntity>('/api/ticket/admin/sessions', {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function listAdminSessions(userId: number, params: { page?: number; size?: number; activityId?: number; venueId?: number; status?: number } = {}) {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page || 1))
  searchParams.set('size', String(params.size || 10))
  if (params.activityId) searchParams.set('activityId', String(params.activityId))
  if (params.venueId) searchParams.set('venueId', String(params.venueId))
  if (params.status !== undefined) searchParams.set('status', String(params.status))
  return request<import('@/types/api').PageResult<import('@/types/api').SessionAdminVO>>(
    `/api/ticket/admin/sessions?${searchParams.toString()}`,
    undefined,
    { timeoutMs: CONSOLE_ADMIN_REQUEST_TIMEOUT_MS }
  )
}

export async function updateAdminSession(id: number, body: Record<string, unknown>) {
  return request<import('@/types/api').SessionEntity>(`/api/ticket/admin/sessions/${id}`, {
    method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function deleteAdminSession(id: number, userId: number) {
  return request<void>(`/api/ticket/admin/sessions/${id}`, { method: 'DELETE' })
}

export async function createAdminTicketType(body: import('@/types/api').AdminTicketTypeCreateRequest) {
  return request<import('@/types/api').TicketTypeEntity>('/api/ticket/admin/ticket-types', {
    method: 'POST', body: JSON.stringify(body),
  })
}

export async function updateAdminTicketType(id: number, body: Record<string, unknown>) {
  return request<import('@/types/api').TicketTypeEntity>(`/api/ticket/admin/ticket-types/${id}`, {
    method: 'PUT', body: JSON.stringify(body),
  })
}

export async function batchUpdateAdminTicketTypes(body: import('@/types/api').TicketTypeBatchUpdateRequest) {
  return request<import('@/types/api').TicketTypeEntity[]>('/api/ticket/admin/ticket-types/batch-update', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function deleteAdminTicketType(id: number, userId: number) {
  return request<void>(`/api/ticket/admin/ticket-types/${id}`, { method: 'DELETE' })
}

export async function listAdminVenues(userId: number) {
  return request<import('@/types/api').VenueEntity[]>('/api/ticket/admin/venues', undefined, { timeoutMs: CONSOLE_ADMIN_REQUEST_TIMEOUT_MS })
}

export async function createAdminVenue(body: Record<string, unknown>) {
  return request<import('@/types/api').VenueEntity>('/api/ticket/admin/venues', {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function updateAdminVenue(id: number, body: Record<string, unknown>) {
  return request<import('@/types/api').VenueEntity>(`/api/ticket/admin/venues/${id}`, {
    method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function deleteAdminVenue(id: number, userId: number) {
  return request<void>(`/api/ticket/admin/venues/${id}`, { method: 'DELETE' })
}

export async function createVenueArea(id: number, body: Record<string, unknown>) {
  return request<import('@/types/api').SeatTemplateResponse>(`/api/ticket/admin/venues/${id}/areas`, {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function listVenueAreas(id: number, userId: number) {
  return request<import('@/types/api').VenueAreaVO[]>(`/api/ticket/admin/venues/${id}/areas`)
}

export async function listVenueSeats(venueId: number, userId: number) {
  return request<import('@/types/api').VenueSeatVO[]>(`/api/ticket/admin/venues/${venueId}/seats`)
}

export async function createVenueSeat(venueId: number, body: import('@/types/api').VenueSeatRequest) {
  return request<import('@/types/api').SeatTemplateSyncResponseVO>(`/api/ticket/admin/venues/${venueId}/seats`, {
    method: 'POST', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function updateVenueSeat(seatId: number, body: import('@/types/api').VenueSeatRequest) {
  return request<import('@/types/api').SeatTemplateSyncResponseVO>(`/api/ticket/admin/venue-seats/${seatId}`, {
    method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)),
  })
}

export async function deleteVenueSeat(seatId: number, userId: number) {
  return request<import('@/types/api').SeatTemplateSyncResponseVO>(`/api/ticket/admin/venue-seats/${seatId}`, { method: 'DELETE' })
}

export async function getActivitySeatLayout(activityId: number, userId: number) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').SeatCraftLayoutVO | null>(`/api/ticket/admin/activities/${activityId}/seat-layout`)
}

export async function createBlankActivitySeatLayout(activityId: number, userId: number) {
  assertPositiveInteger(activityId, '活动ID')
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').SeatCraftLayoutVO>(`/api/ticket/admin/activities/${activityId}/seat-layout/blank`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function updateActivitySeatLayout(activityId: number, body: { userId: number; layout: import('@/types/api').SeatCraftLayoutVO }) {
  return request<import('@/types/api').SeatCraftLayoutVO>(`/api/ticket/admin/activities/${activityId}/seat-layout`, { method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)) })
}

export type SeatCraftOwnerType = 'activity' | 'session' | 'station'

function assertSeatCraftOwner(ownerType: SeatCraftOwnerType, ownerId: number) {
  if (ownerType !== 'activity' && ownerType !== 'session' && ownerType !== 'station') {
    throw new ApiError(400, 'SeatCraft 归属类型无效')
  }
  assertPositiveInteger(ownerId, 'SeatCraft 归属ID')
}

export async function getSeatCraftDraft(ownerType: SeatCraftOwnerType, ownerId: number) {
  assertSeatCraftOwner(ownerType, ownerId)
  return request<import('@/types/api').SeatCraftVersionedLayoutVO | null>(`/api/ticket/admin/seatcraft/${ownerType}/${ownerId}/draft`)
}

export async function saveSeatCraftDraft(ownerType: SeatCraftOwnerType, ownerId: number, layout: import('@/types/api').SeatCraftVersionedLayoutRequest) {
  assertSeatCraftOwner(ownerType, ownerId)
  return request<import('@/types/api').SeatCraftVersionedLayoutVO>(`/api/ticket/admin/seatcraft/${ownerType}/${ownerId}/draft`, {
    method: 'PUT',
    body: JSON.stringify(layout),
  })
}

export async function publishSeatCraftDraft(ownerType: SeatCraftOwnerType, ownerId: number) {
  assertSeatCraftOwner(ownerType, ownerId)
  return request<import('@/types/api').SeatCraftVersionedLayoutVO>(`/api/ticket/admin/seatcraft/${ownerType}/${ownerId}/publish`, {
    method: 'POST',
  })
}

export async function listSeatCraftVersions(ownerType: SeatCraftOwnerType, ownerId: number) {
  assertSeatCraftOwner(ownerType, ownerId)
  return request<import('@/types/api').SeatCraftVersionSummaryVO[]>(`/api/ticket/admin/seatcraft/${ownerType}/${ownerId}/versions`)
}

export async function rollbackSeatCraftVersion(ownerType: SeatCraftOwnerType, ownerId: number, versionId: number) {
  assertSeatCraftOwner(ownerType, ownerId)
  assertPositiveInteger(versionId, 'SeatCraft 版本ID')
  return request<import('@/types/api').SeatCraftVersionedLayoutVO>(`/api/ticket/admin/seatcraft/${ownerType}/${ownerId}/versions/${versionId}/rollback`, {
    method: 'POST',
  })
}

export async function deleteSeatCraftVersion(ownerType: SeatCraftOwnerType, ownerId: number, versionId: number) {
  assertSeatCraftOwner(ownerType, ownerId)
  assertPositiveInteger(versionId, 'SeatCraft 版本ID')
  return request<void>(`/api/ticket/admin/seatcraft/${ownerType}/${ownerId}/versions/${versionId}`, {
    method: 'DELETE',
  })
}

export async function getVenueDefaultLayout(venueId: number) {
  return request<import('@/types/api').SeatCraftLayoutVO | null>(`/api/ticket/admin/venues/${venueId}/default-layout`)
}

export async function updateVenueDefaultLayout(venueId: number, body: { userId: number; layout: import('@/types/api').SeatCraftLayoutVO }) {
  return request<import('@/types/api').SeatCraftLayoutVO>(`/api/ticket/admin/venues/${venueId}/default-layout`, { method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)) })
}

export async function listVenueSeatLayoutTemplates(venueId: number, userId: number) {
  assertPositiveInteger(venueId, '地点ID')
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').SeatLayoutTemplateCandidateVO[]>(`/api/ticket/admin/venues/${venueId}/seat-layout-templates`)
}

export async function getSessionSeatLayout(sessionId: number, userId: number) {
  assertPositiveInteger(sessionId, '场次ID')
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').SeatCraftLayoutVO | null>(`/api/ticket/admin/sessions/${sessionId}/seat-layout`)
}

export async function createBlankSessionSeatLayout(sessionId: number, userId: number) {
  assertPositiveInteger(sessionId, '场次ID')
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').SeatCraftLayoutVO>(`/api/ticket/admin/sessions/${sessionId}/seat-layout/blank`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function updateSessionSeatLayout(sessionId: number, body: { userId: number; layout: import('@/types/api').SeatCraftLayoutVO }) {
  return request<import('@/types/api').SeatCraftLayoutVO>(`/api/ticket/admin/sessions/${sessionId}/seat-layout`, { method: 'PUT', body: JSON.stringify((({ userId: _userId, ...safeBody }) => safeBody)(body)) })
}

export async function updateSessionTicketBindings(sessionId: number, body: import('@/types/api').SessionTicketBindingRequest) {
  return request<void>(`/api/ticket/admin/sessions/${sessionId}/ticket-bindings`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export async function getSessionTicketDrafts(sessionId: number, userId: number) {
  assertPositiveInteger(sessionId, '场次ID')
  assertPositiveInteger(userId, '用户ID')
  return request<import('@/types/api').SeatCraftSectionVO[]>(`/api/ticket/admin/sessions/${sessionId}/seat-layout/ticket-drafts`)
}

export async function submitVenueApplication(body: Record<string, unknown>) {
  const { userId: _userId, ...safeBody } = body
  return request<import('@/types/api').VenueApplicationVO>('/api/ticket/admin/venue-applications', {
    method: 'POST', body: JSON.stringify(safeBody),
  })
}

export async function listMyVenueApplications(_userId?: number) {
  return request<import('@/types/api').VenueApplicationVO[]>('/api/ticket/admin/venue-applications/my')
}

export async function listVenueApplications(params: {
  page?: number
  size?: number
  keyword?: string
  status?: import('@/types/api').VenueApplicationReviewStatus
  city?: string
  capacityScale?: import('@/types/api').VenueApplicationCapacityScale
  materialCompleteness?: string
} = {}) {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page || 1))
  searchParams.set('size', String(params.size || 10))
  if (params.keyword?.trim()) searchParams.set('keyword', params.keyword.trim())
  if (params.status) searchParams.set('status', params.status)
  if (params.city) searchParams.set('city', params.city)
  if (params.capacityScale) searchParams.set('capacityScale', params.capacityScale)
  if (params.materialCompleteness) searchParams.set('materialCompleteness', params.materialCompleteness)
  return request<import('@/types/api').PageResult<import('@/types/api').VenueApplicationVO>>(
    `/api/ticket/admin/venue-applications?${searchParams.toString()}`,
    undefined,
    { timeoutMs: CONSOLE_ADMIN_REQUEST_TIMEOUT_MS },
  )
}

export async function reviewVenueApplication(id: number, body: Record<string, unknown>) {
  const { userId: _userId, ...safeBody } = body
  return request<import('@/types/api').VenueApplicationVO>(`/api/ticket/admin/venue-applications/${id}/review`, {
    method: 'POST', body: JSON.stringify(safeBody),
  })
}

export async function approveVenueApplication(id: number, reviewNote?: string) {
  assertPositiveInteger(id, '场馆申请编号')
  return request<import('@/types/api').VenueApplicationVO>(`/api/ticket/admin/venue-applications/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote: reviewNote?.trim() || null }),
  })
}

export async function rejectVenueApplication(id: number, reviewNote: string) {
  assertPositiveInteger(id, '场馆申请编号')
  return request<import('@/types/api').VenueApplicationVO>(`/api/ticket/admin/venue-applications/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reviewNote: reviewNote.trim() }),
  })
}

export async function uploadVenueApplicationMaterial(file: File, materialType: 'FIRE_SAFETY_PERMIT' | 'VENUE_LEASE_AGREEMENT', userId: number) {
  const bizType = materialType === 'FIRE_SAFETY_PERMIT' ? 'venue-fire-safety' : 'venue-lease-agreement'
  return uploadPrivateAsset({ userId, bizType, file })
}
