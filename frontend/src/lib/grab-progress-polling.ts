export const GRAB_PROGRESS_POLL_INTERVAL_MS = 500

type TimerHandle = ReturnType<typeof setInterval>

export function startGrabProgressPolling(
  fetchProgress: () => void,
  options: {
    intervalMs?: number
    setIntervalFn?: (callback: () => void, intervalMs: number) => TimerHandle | unknown
    clearIntervalFn?: (timer: TimerHandle | unknown) => void
  } = {},
) {
  fetchProgress()
  const intervalMs = options.intervalMs ?? GRAB_PROGRESS_POLL_INTERVAL_MS
  const timer = (options.setIntervalFn ?? setInterval)(fetchProgress, intervalMs)
  return () => {
    ;(options.clearIntervalFn ?? clearInterval)(timer as TimerHandle)
  }
}
