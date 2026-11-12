import { Injectable } from '@nestjs/common';
import { RedisService } from './redis.service';

export interface EnqueueRequest {
  requestId: string;
  sessionId: number;
  userId: number;
  ttlSeconds?: number;
}

export interface EnqueueResult {
  queueSeq: number;
  queueRank: number;
}

export interface PublishReservedRequest extends EnqueueRequest {
  queueSeq: number;
}

export interface QueueRequestMetadata {
  requestId: string;
  sessionId: number | null;
  userId: number | null;
  queueSeq: number | null;
  status: string | null;
  inflightAt: number | null;
}

@Injectable()
export class GrabQueueService {
  constructor(private readonly redis: RedisService) {}

  async reserveQueueSeq(sessionId: number): Promise<EnqueueResult> {
    const queueSeq = Number(await this.redis.eval(`return redis.call('INCR', KEYS[1])`, [this.queueSeqKey(sessionId)], []));
    const queueRank = await this.calculateQueueRank(sessionId, queueSeq);
    return { queueSeq, queueRank };
  }

  async publishReserved(request: PublishReservedRequest): Promise<void> {
    const sessionId = String(request.sessionId);
    const script = `
      local queued = redis.call('LPOS', KEYS[2], ARGV[1])
      local inflight = redis.call('LPOS', KEYS[4], ARGV[1])

      redis.call('HSET', KEYS[1],
        'requestId', ARGV[1],
        'sessionId', ARGV[2],
        'userId', ARGV[3],
        'queueSeq', ARGV[4],
        'status', ARGV[5]
      )
      local ttl = tonumber(ARGV[6])
      if ttl and ttl > 0 then
        redis.call('EXPIRE', KEYS[1], ttl)
      end
      if queued or inflight then
        redis.call('SADD', KEYS[3], ARGV[2])
        return 0
      end
      redis.call('RPUSH', KEYS[2], ARGV[1])
      redis.call('SADD', KEYS[3], ARGV[2])
      return 1
    `;

    await this.redis.eval(
      script,
      [
        this.requestKey(request.requestId),
        this.queueKey(request.sessionId),
        this.activeSessionsKey(),
        this.inflightQueueKey(request.sessionId),
      ],
      [
        request.requestId,
        sessionId,
        String(request.userId),
        String(request.queueSeq),
        'QUEUED',
        String(this.metadataTtlSeconds(request.ttlSeconds)),
      ],
    );
  }

  async enqueue(request: EnqueueRequest): Promise<EnqueueResult> {
    const sessionId = String(request.sessionId);
    const script = `
      local queueSeq = redis.call('INCR', KEYS[1])
      redis.call('HSET', KEYS[2],
        'requestId', ARGV[1],
        'sessionId', ARGV[2],
        'userId', ARGV[3],
        'queueSeq', tostring(queueSeq),
        'status', ARGV[4]
      )
      local ttl = tonumber(ARGV[5])
      if ttl and ttl > 0 then
        redis.call('EXPIRE', KEYS[2], ttl)
      end
      redis.call('RPUSH', KEYS[3], ARGV[1])
      redis.call('SADD', KEYS[4], ARGV[2])
      return queueSeq
    `;
    const queueSeq = Number(
      await this.redis.eval(
        script,
        [this.queueSeqKey(request.sessionId), this.requestKey(request.requestId), this.queueKey(request.sessionId), this.activeSessionsKey()],
        [request.requestId, sessionId, String(request.userId), 'QUEUED', String(this.metadataTtlSeconds(request.ttlSeconds))],
      ),
    );

    const queueRank = await this.calculateQueueRank(request.sessionId, queueSeq);
    return { queueSeq, queueRank };
  }

  async calculateQueueRank(sessionId: number, queueSeq: number): Promise<number> {
    const processed = await this.redis.get(this.processedSeqKey(sessionId));
    const processedSeq = processed ? Number(processed) : 0;

    return Math.max(queueSeq - processedSeq - 1, 0);
  }

  async markProcessed(sessionId: number, queueSeq: number): Promise<void> {
    const script = `
      local currentSeq = tonumber(redis.call('GET', KEYS[1]) or '0') or 0
      local newSeq = tonumber(ARGV[1])
      if newSeq > currentSeq then
        redis.call('SET', KEYS[1], ARGV[1])
        return 1
      end
      return 0
    `;

    await this.redis.eval(script, [this.processedSeqKey(sessionId)], [String(queueSeq)]);
  }

  async dequeue(sessionId: number): Promise<string | null> {
    const script = `
      local requestId = redis.call('LPOP', KEYS[1])
      if requestId then
        redis.call('RPUSH', KEYS[2], requestId)
        redis.call('HSET', KEYS[3] .. requestId, 'status', 'INFLIGHT', 'inflightAt', ARGV[1])
      end
      return requestId
    `;

    return this.redis.eval(script, [this.queueKey(sessionId), this.inflightQueueKey(sessionId), this.requestKeyPlaceholder()], [String(Date.now())]);
  }

  async ackProcessed(sessionId: number, requestId: string, queueSeq: number): Promise<void> {
    const script = `
      local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
      if removed <= 0 then
        return 0
      end
      local currentSeq = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
      local newSeq = tonumber(ARGV[2])
      if newSeq > currentSeq then
        redis.call('SET', KEYS[2], ARGV[2])
        redis.call('DEL', KEYS[3])
        return 1
      end
      redis.call('DEL', KEYS[3])
      return 0
    `;

    await this.redis.eval(script, [this.inflightQueueKey(sessionId), this.processedSeqKey(sessionId), this.requestKey(requestId)], [requestId, String(queueSeq)]);
  }

  async removeQueuedRequest(sessionId: number, requestId: string): Promise<void> {
    const script = `
      redis.call('LREM', KEYS[1], 0, ARGV[1])
      redis.call('LREM', KEYS[2], 0, ARGV[1])
      redis.call('DEL', KEYS[3])
      if redis.call('LLEN', KEYS[1]) == 0 and redis.call('LLEN', KEYS[2]) == 0 then
        redis.call('SREM', KEYS[4], ARGV[2])
      end
      return 1
    `;

    await this.redis.eval(
      script,
      [this.queueKey(sessionId), this.inflightQueueKey(sessionId), this.requestKey(requestId), this.activeSessionsKey()],
      [requestId, String(sessionId)],
    );
  }

  async ackOrphanInflight(sessionId: number, requestId: string): Promise<void> {
    const script = `
      local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
      if removed <= 0 then
        return 0
      end
      local queueSeq = tonumber(redis.call('HGET', KEYS[3], 'queueSeq') or '0') or 0
      if queueSeq > 0 then
        local currentSeq = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
        if queueSeq > currentSeq then
          redis.call('SET', KEYS[2], tostring(queueSeq))
        end
      end
      redis.call('DEL', KEYS[3])
      return 1
    `;

    await this.redis.eval(script, [this.inflightQueueKey(sessionId), this.processedSeqKey(sessionId), this.requestKey(requestId)], [requestId]);
  }

  async requeueInflight(sessionId: number, requestId: string): Promise<void> {
    const script = `
      local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
      if removed > 0 then
        redis.call('LPUSH', KEYS[2], ARGV[1])
        redis.call('SADD', KEYS[3], ARGV[2])
        return 1
      end
      return 0
    `;

    await this.redis.eval(script, [this.inflightQueueKey(sessionId), this.queueKey(sessionId), this.activeSessionsKey()], [requestId, String(sessionId)]);
  }

  async discardInflight(sessionId: number, requestId: string): Promise<void> {
    await this.redis.eval(
      `return redis.call('LREM', KEYS[1], 1, ARGV[1])`,
      [this.inflightQueueKey(sessionId)],
      [requestId],
    );
  }

  async listInflightRequestIds(sessionId: number): Promise<string[]> {
    return this.redis.lrange(this.inflightQueueKey(sessionId), 0, -1);
  }

  async getRequestMetadata(requestId: string): Promise<QueueRequestMetadata | null> {
    const metadata = await this.redis.hgetall(this.requestKey(requestId));
    if (!metadata.requestId) return null;

    return {
      requestId: metadata.requestId,
      sessionId: this.parseNumber(metadata.sessionId),
      userId: this.parseNumber(metadata.userId),
      queueSeq: this.parseNumber(metadata.queueSeq),
      status: metadata.status ?? null,
      inflightAt: this.parseNumber(metadata.inflightAt),
    };
  }

  async getActiveSessions(): Promise<number[]> {
    const sessions = await this.redis.smembers(this.activeSessionsKey());

    return sessions.map((sessionId) => Number(sessionId)).filter((sessionId) => !Number.isNaN(sessionId));
  }

  async removeActiveSession(sessionId: number): Promise<void> {
    await this.removeActiveSessionIfQueueEmpty(sessionId);
  }

  async removeActiveSessionIfQueueEmpty(sessionId: number): Promise<void> {
    const script = `
      if redis.call('LLEN', KEYS[1]) == 0 and redis.call('LLEN', KEYS[2]) == 0 then
        return redis.call('SREM', KEYS[3], ARGV[1])
      end
      return 0
    `;

    await this.redis.eval(script, [this.queueKey(sessionId), this.inflightQueueKey(sessionId), this.activeSessionsKey()], [String(sessionId)]);
  }

  private queueSeqKey(sessionId: number): string {
    return `grab:queue:seq:${sessionId}`;
  }

  private queueKey(sessionId: number): string {
    return `grab:queue:${sessionId}`;
  }

  private inflightQueueKey(sessionId: number): string {
    return `grab:queue:inflight:${sessionId}`;
  }

  private processedSeqKey(sessionId: number): string {
    return `grab:queue:processed:${sessionId}`;
  }

  private requestKey(requestId: string): string {
    return `grab:req:${requestId}`;
  }

  private requestKeyPlaceholder(): string {
    return 'grab:req:';
  }

  private activeSessionsKey(): string {
    return 'grab:active-sessions';
  }

  private metadataTtlSeconds(requestTtlSeconds: number | undefined): number {
    if (!requestTtlSeconds || requestTtlSeconds <= 0) return 0;
    return Math.max(requestTtlSeconds * 2, requestTtlSeconds + 300);
  }

  private parseNumber(value: string | undefined): number | null {
    if (value == null || value === '') return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
}
