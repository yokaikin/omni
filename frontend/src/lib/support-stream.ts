/**
 * 客服会话 SSE 推送通道：
 * - EventSource 无法携带 Authorization 头，先 POST 换取一次性 ticket 再建立长连接；
 * - 断线后自动用新 ticket 重连，连续失败达到上限后回调 onFallback（降级轮询）。
 */
import { buildSupportStreamUrl, issueSupportStreamTicket } from './api.ts'

export type SupportStreamEvent = {
  type: 'message' | 'conversation' | 'ready' | 'ping'
  conversationId?: number
}

export type SupportStreamHandlers = {
  onEvent?: (event: SupportStreamEvent) => void
  onFallback?: () => void
}

const MAX_CONNECT_RETRIES = 3
const RETRY_BASE_DELAY_MS = 1000

export function openSupportStream(channel: 'user' | 'agent', handlers: SupportStreamHandlers = {}): () => void {
  let disposed = false
  let retries = 0
  let source: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  const closeConnection = () => {
    source?.close()
    source = null
  }

  const dispose = () => {
    disposed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    closeConnection()
  }

  const scheduleReconnect = () => {
    if (disposed) return
    if (retries >= MAX_CONNECT_RETRIES) {
      handlers.onFallback?.()
      return
    }
    retries += 1
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void open()
    }, RETRY_BASE_DELAY_MS * retries)
  }

  const open = async () => {
    if (disposed) return
    let ticket: string
    try {
      ticket = (await issueSupportStreamTicket(channel)).ticket
    } catch {
      scheduleReconnect()
      return
    }
    if (disposed) return
    closeConnection()
    source = new EventSource(buildSupportStreamUrl(ticket))
    source.onmessage = (event: MessageEvent<string>) => {
      retries = 0
      let payload: SupportStreamEvent
      try {
        payload = JSON.parse(event.data) as SupportStreamEvent
      } catch {
        return
      }
      if (payload.type === 'ping') return
      handlers.onEvent?.(payload)
    }
    source.onerror = () => {
      closeConnection()
      scheduleReconnect()
    }
  }

  void open()
  return dispose
}
