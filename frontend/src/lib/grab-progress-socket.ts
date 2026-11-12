'use client'

import { io } from 'socket.io-client'
import type { GrabProgressResult } from '@/types/api'

const GRAB_SOCKET_PATH = '/api/grab/socket.io'
const GRAB_SOCKET_NAMESPACE = '/grab-progress'
const GRAB_SOCKET_RECONNECTION_ATTEMPTS = 3
const GRAB_SOCKET_CONNECT_TIMEOUT_MS = 5000

export type GrabProgressSocketStatus = 'disabled' | 'connecting' | 'connected' | 'disconnected'

export const GRAB_PROGRESS_CONNECTED_POLL_INTERVAL_MS = 5000

function grabWsBaseUrl(): string {
  return (process.env.NEXT_PUBLIC_GRAB_WS_URL || '').trim()
}

export function isGrabProgressSocketEnabled(): boolean {
  return grabWsBaseUrl().length > 0
}

function isProgressLike(value: unknown): value is GrabProgressResult {
  return typeof value === 'object' && value !== null && typeof (value as GrabProgressResult).requestId === 'string'
}

export function connectGrabProgressSocket(options: {
  requestId: string
  token: string | null
  onProgress: (progress: GrabProgressResult) => void
  onQueueAdvanced: () => void
  onStatusChange: (status: GrabProgressSocketStatus) => void
}): () => void {
  if (!isGrabProgressSocketEnabled()) {
    options.onStatusChange('disabled')
    return () => {}
  }

  options.onStatusChange('connecting')

  const socket = io(`${grabWsBaseUrl().replace(/\/+$/, '')}${GRAB_SOCKET_NAMESPACE}`, {
    path: GRAB_SOCKET_PATH,
    auth: { token: options.token ?? '' },
    reconnectionAttempts: GRAB_SOCKET_RECONNECTION_ATTEMPTS,
    timeout: GRAB_SOCKET_CONNECT_TIMEOUT_MS,
  })

  socket.on('connect', () => {
    options.onStatusChange('connected')
    socket.emit('subscribe', { requestId: options.requestId })
  })

  socket.on('subscribed', (progress: unknown) => {
    if (isProgressLike(progress)) options.onProgress(progress)
  })

  socket.on('error', () => {
    options.onStatusChange('disconnected')
  })

  socket.on('progress', (progress: unknown) => {
    if (isProgressLike(progress)) options.onProgress(progress)
  })

  socket.on('queue-advanced', () => {
    options.onQueueAdvanced()
  })

  socket.on('disconnect', () => {
    options.onStatusChange('disconnected')
  })

  socket.on('connect_error', () => {
    options.onStatusChange('disconnected')
  })

  return () => {
    socket.removeAllListeners()
    socket.disconnect()
  }
}
