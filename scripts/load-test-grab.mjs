import { createHmac, randomUUID } from 'node:crypto'

const gateway = process.env.LOAD_TEST_GATEWAY || 'http://localhost:8088'
const gatewayUrl = new URL(gateway)
const sessionId = Number(process.env.LOAD_TEST_SESSION_ID || 910062)
const ticketTypeId = Number(process.env.LOAD_TEST_TICKET_TYPE_ID || 920186)
const firstUserId = Number(process.env.LOAD_TEST_FIRST_USER_ID || 300001)
const count = Number(process.env.LOAD_TEST_COUNT || 100)
const jwtSecret = process.env.LOAD_TEST_JWT_SECRET || 'omni-local-jwt-secret-must-be-at-least-32-bytes'
const cleanup = process.env.LOAD_TEST_CLEANUP !== 'false'
const confirm = process.env.LOAD_TEST_CONFIRM === 'YES'
const timeoutMs = Number(process.env.LOAD_TEST_TIMEOUT_MS || 300_000)

if (!confirm) {
  throw new Error('真实压测会创建订单并占用库存，请设置 LOAD_TEST_CONFIRM=YES 后再运行')
}
if (gatewayUrl.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(gatewayUrl.hostname)) {
  throw new Error('压测只允许访问本机 HTTP Gateway（localhost、127.0.0.1 或 ::1）')
}
if (!Number.isInteger(count) || count < 2 || count > 500) throw new Error('LOAD_TEST_COUNT 必须在 2 到 500 之间')
if (!Number.isInteger(sessionId) || !Number.isInteger(ticketTypeId)) throw new Error('场次或票档参数无效')

const jsonHeaders = (token) => ({
  'Content-Type': 'application/json',
  ...(token ? { Authorization: `Bearer ${token}` } : {}),
})

async function request(path, options = {}) {
  const response = await fetch(`${gateway}${path}`, options)
  const text = await response.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    body = { code: response.status, message: text || `HTTP ${response.status}` }
  }
  return { httpStatus: response.status, ...body }
}

function login(userId) {
  const phone = String(17000000000 + userId - 300000)
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const payload = Buffer.from(JSON.stringify({ userId, sub: String(userId), phone, role: 'user', iat: now, exp: now + 1800 })).toString('base64url')
  const content = `${header}.${payload}`
  const signature = createHmac('sha256', jwtSecret).update(content).digest('base64url')
  return { userId, phone, token: `${content}.${signature}` }
}

async function createAttendee(loginData, index) {
  const stamp = `${Date.now()}${index}`.slice(-12)
  const result = await request('/api/user/attendees', {
    method: 'POST',
    headers: jsonHeaders(loginData.token),
    body: JSON.stringify({
      realName: `并发压测${index}`,
      idType: 'ID_CARD',
      idNo: `110000${stamp}X`,
      phone: `139${String(70000000 + index).padStart(8, '0')}`,
    }),
  })
  if (result.code !== 200 || !result.data?.id) throw new Error(`用户 ${loginData.userId} 创建观演人失败：${result.message}`)
  return result.data.id
}

async function submit(loginData, attendeeId, index) {
  const startedAt = performance.now()
  const result = await request('/api/grab/requests', {
    method: 'POST',
    headers: jsonHeaders(loginData.token),
    body: JSON.stringify({
      sessionId,
      ticketTypeId,
      quantity: 1,
      attendeeIds: [attendeeId],
      idempotencyKey: `load-${randomUUID()}-${index}`,
      allocateRandom: false,
    }),
  })
  return {
    index,
    userId: loginData.userId,
    token: loginData.token,
    requestId: result.data?.requestId || null,
    httpStatus: result.httpStatus,
    code: result.code,
    message: result.message,
    latencyMs: performance.now() - startedAt,
  }
}

async function poll(item) {
  if (!item.requestId) return { ...item, status: 'SUBMIT_FAILED', orderId: null }
  const deadline = Date.now() + timeoutMs
  let latest = null
  while (Date.now() < deadline) {
    latest = await request(`/api/grab/requests/${encodeURIComponent(item.requestId)}/progress`, {
      headers: jsonHeaders(item.token),
    })
    const status = latest.data?.status || latest.data?.progressStatus
    if (['ORDER_CREATED', 'SOLD_OUT', 'LIMITED', 'FAILED', 'EXPIRED'].includes(status)) {
      return { ...item, status, orderId: latest.data?.orderId || null, failReason: latest.data?.failReason || null }
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  // A final read closes the race where order creation finishes at the deadline.
  latest = await request(`/api/grab/requests/${encodeURIComponent(item.requestId)}/progress`, {
    headers: jsonHeaders(item.token),
  })
  const finalStatus = latest.data?.status || latest.data?.progressStatus
  if (['ORDER_CREATED', 'SOLD_OUT', 'LIMITED', 'FAILED', 'EXPIRED'].includes(finalStatus)) {
    return { ...item, status: finalStatus, orderId: latest.data?.orderId || null, failReason: latest.data?.failReason || null }
  }
  return { ...item, status: 'TIMEOUT', orderId: latest.data?.orderId || null, failReason: '轮询超时' }
}

async function cleanupOrders(results) {
  const pending = results.filter((item) => item.status === 'ORDER_CREATED' && item.orderId)
  let cancelled = 0
  const failures = []
  for (const item of pending) {
    try {
      const result = await request(`/api/order/${item.orderId}`, {
        method: 'DELETE',
        headers: jsonHeaders(item.token),
      })
      if (result.code === 200) cancelled += 1
      else failures.push({ orderId: item.orderId, userId: item.userId, code: result.code, message: result.message })
    } catch (error) {
      failures.push({ orderId: item.orderId, userId: item.userId, message: error?.message || '取消请求异常' })
    }
  }
  return { attempted: pending.length, cancelled, failures }
}

async function cleanupAttendees(resources) {
  let deleted = 0
  for (const resource of resources) {
    if (!resource?.attendeeId) continue
    const result = await request(`/api/user/attendees/${resource.attendeeId}`, {
      method: 'DELETE',
      headers: jsonHeaders(resource.loginData.token),
    })
    if (result.code === 200) deleted += 1
  }
  return { attempted: resources.filter((resource) => resource?.attendeeId).length, deleted }
}

const startedAt = Date.now()
const logins = Array.from({ length: count }, (_, index) => login(firstUserId + index))
const resources = logins.map((loginData) => ({ loginData, attendeeId: null }))
let results = []
let summary = {}

try {
  const attendeeResults = await Promise.allSettled(
    resources.map((resource, index) => createAttendee(resource.loginData, index + 1)),
  )
  attendeeResults.forEach((result, index) => {
    if (result.status === 'fulfilled') resources[index].attendeeId = result.value
  })
  const attendeeFailures = attendeeResults.filter((result) => result.status === 'rejected')
  if (attendeeFailures.length > 0) {
    throw new Error(`创建观演人失败 ${attendeeFailures.length}/${count} 个：${attendeeFailures[0].reason?.message || '未知错误'}`)
  }

  const submittedResults = await Promise.allSettled(
    resources.map((resource, index) => submit(resource.loginData, resource.attendeeId, index + 1)),
  )
  const submitted = submittedResults.map((result, index) => result.status === 'fulfilled'
    ? result.value
    : {
        index: index + 1,
        userId: resources[index].loginData.userId,
        token: resources[index].loginData.token,
        requestId: null,
        httpStatus: 0,
        code: 0,
        message: result.reason?.message || '提交请求异常',
        latencyMs: null,
      })
  const polledResults = await Promise.allSettled(submitted.map(poll))
  results = polledResults.map((result, index) => result.status === 'fulfilled'
    ? result.value
    : { ...submitted[index], status: 'POLL_FAILED', orderId: null, failReason: result.reason?.message || '轮询请求异常' })
  summary = results.reduce((acc, item) => {
    acc[item.status] = (acc[item.status] || 0) + 1
    return acc
  }, {})
  const latencies = submitted.map((item) => item.latencyMs).filter(Number.isFinite).sort((a, b) => a - b)
  const percentile = (p) => latencies.length === 0 ? 0 : latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * p))]
  console.log(JSON.stringify({
    gateway,
    sessionId,
    ticketTypeId,
    count,
    summary,
    submitLatencyMs: {
      p50: Number(percentile(0.5).toFixed(1)),
      p95: Number(percentile(0.95).toFixed(1)),
      max: Number((latencies.length ? Math.max(...latencies) : 0).toFixed(1)),
    },
    elapsedMs: Date.now() - startedAt,
  }, null, 2))
} finally {
  if (cleanup) {
    const cleanupResult = await cleanupOrders(results)
    console.log(JSON.stringify({ cleanup: cleanupResult }, null, 2))
    const attendeeCleanup = await cleanupAttendees(resources)
    console.log(JSON.stringify({ attendeeCleanup }, null, 2))
  }
}

if ((summary.SUBMIT_FAILED || 0) > 0 || (summary.TIMEOUT || 0) > 0 || (summary.POLL_FAILED || 0) > 0) process.exitCode = 2
