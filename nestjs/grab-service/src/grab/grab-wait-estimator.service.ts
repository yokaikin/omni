import { Injectable } from '@nestjs/common';

const WORKER_POLL_INTERVAL_MS = 500;
const DEFAULT_PROCESS_COST_MS = 1000;
const EWMA_ALPHA = 0.3;
const SAMPLE_TTL_MS = 10 * 60 * 1000;

interface ProcessCostSample {
  ewmaMs: number;
  updatedAt: number;
}

/**
 * 抢票排队等待时间估算器：
 * worker 单线程每 500ms 轮询一次、每轮处理一个请求，
 * 因此 estimatedWaitSeconds ≈ queueRank × (轮询间隔 + 单请求实测处理耗时 EWMA) / 1000。
 * 处理耗时按场次记录在进程内（单实例拓扑），无样本或样本过期时退回默认成本。
 */
@Injectable()
export class GrabWaitEstimatorService {
  private readonly samples = new Map<number, ProcessCostSample>();

  recordProcessingMs(sessionId: number, totalMs: number): void {
    if (!Number.isInteger(sessionId) || !Number.isFinite(totalMs) || totalMs <= 0) return;
    const previous = this.samples.get(sessionId);
    const ewmaMs = previous
      ? previous.ewmaMs * (1 - EWMA_ALPHA) + totalMs * EWMA_ALPHA
      : totalMs;
    this.samples.set(sessionId, { ewmaMs, updatedAt: Date.now() });
  }

  estimateWaitSeconds(sessionId: number | null, queueRank: number | null): number | null {
    if (sessionId == null || queueRank == null || !Number.isFinite(queueRank) || queueRank <= 0) return null;
    const sample = this.samples.get(sessionId);
    const costMs = sample && Date.now() - sample.updatedAt <= SAMPLE_TTL_MS
      ? sample.ewmaMs
      : DEFAULT_PROCESS_COST_MS;
    const waitMs = queueRank * (WORKER_POLL_INTERVAL_MS + Math.max(0, costMs));
    return Math.max(1, Math.ceil(waitMs / 1000));
  }
}
