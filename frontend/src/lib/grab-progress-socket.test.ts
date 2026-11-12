import assert from 'node:assert/strict'
import { test } from 'node:test'
import { connectGrabProgressSocket, isGrabProgressSocketEnabled } from './grab-progress-socket.ts'

const ORIGINAL_WS_URL = process.env.NEXT_PUBLIC_GRAB_WS_URL

test('socket is disabled when NEXT_PUBLIC_GRAB_WS_URL is not configured', () => {
  delete process.env.NEXT_PUBLIC_GRAB_WS_URL

  assert.equal(isGrabProgressSocketEnabled(), false)

  const statuses: string[] = []
  const disconnect = connectGrabProgressSocket({
    requestId: 'gr-1',
    token: 'token',
    onProgress: () => {},
    onQueueAdvanced: () => {},
    onStatusChange: (status) => statuses.push(status),
  })

  assert.deepEqual(statuses, ['disabled'])
  disconnect()
})

test('socket reports connecting when WS base URL is configured', () => {
  process.env.NEXT_PUBLIC_GRAB_WS_URL = 'http://127.0.0.1:9'

  assert.equal(isGrabProgressSocketEnabled(), true)

  const statuses: string[] = []
  const disconnect = connectGrabProgressSocket({
    requestId: 'gr-1',
    token: 'token',
    onProgress: () => {},
    onQueueAdvanced: () => {},
    onStatusChange: (status) => statuses.push(status),
  })

  assert.deepEqual(statuses, ['connecting'])
  disconnect()
})

test.after(() => {
  if (ORIGINAL_WS_URL == null) {
    delete process.env.NEXT_PUBLIC_GRAB_WS_URL
  } else {
    process.env.NEXT_PUBLIC_GRAB_WS_URL = ORIGINAL_WS_URL
  }
})
