import assert from 'node:assert/strict'
import { test } from 'node:test'
import { GRAB_PROGRESS_POLL_INTERVAL_MS, startGrabProgressPolling } from './grab-progress-polling.ts'

test('starts grab progress polling immediately and repeats every 500ms', () => {
  const calls: string[] = []
  const intervals: number[] = []
  const cleared: unknown[] = []

  const stop = startGrabProgressPolling(
    () => calls.push('fetch'),
    {
      setIntervalFn: (callback, intervalMs) => {
        intervals.push(intervalMs)
        calls.push('schedule')
        return 'timer-1'
      },
      clearIntervalFn: (timer) => cleared.push(timer),
    },
  )

  assert.equal(GRAB_PROGRESS_POLL_INTERVAL_MS, 500)
  assert.deepEqual(calls, ['fetch', 'schedule'])
  assert.deepEqual(intervals, [500])

  stop()

  assert.deepEqual(cleared, ['timer-1'])
})

test('supports a custom polling interval', () => {
  const intervals: number[] = []

  const stop = startGrabProgressPolling(
    () => {},
    {
      intervalMs: 5000,
      setIntervalFn: (_callback, intervalMs) => {
        intervals.push(intervalMs)
        return 'timer-2'
      },
      clearIntervalFn: () => {},
    },
  )

  assert.deepEqual(intervals, [5000])

  stop()
})
