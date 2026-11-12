import { randomInt, randomUUID } from 'crypto';

import { GrabAdmissionService } from './grab-admission.service';
import { GrabQueueService } from './grab-queue.service';
import { RedisService } from './redis.service';

const describeRedisIntegration = process.env.RUN_GRAB_REDIS_INTEGRATION === '1' ? describe : describe.skip;

interface TestIds {
  sessionId: number;
  requestId: string;
  secondRequestId: string;
  ticketTypeId: number;
  userId: number;
  seatId: number;
  idempotencyKey: string;
}

const testRunId = randomUUID();
const testCaseIdStride = 100_000;
const numericRunBase = randomInt(1_000_000, 90_000_000) * 100_000_000;
let sequence = 0;

describeRedisIntegration('grab redis integration', () => {
  let redis: RedisService;
  let queue: GrabQueueService;
  let admission: GrabAdmissionService;
  let ids: TestIds;

  beforeAll(() => {
    redis = new RedisService();
    queue = new GrabQueueService(redis);
    admission = new GrabAdmissionService(redis);
  });

  beforeEach(async () => {
    ids = createIds();
    await cleanup(ids);
  });

  afterEach(async () => {
    await cleanup(ids);
  });

  afterAll(async () => {
    await redis?.onModuleDestroy();
  });

  it('uses real queue Lua scripts for monotonic seq, processed rank, inflight, and ack cleanup', async () => {
    const first = await queue.enqueue({
      requestId: ids.requestId,
      sessionId: ids.sessionId,
      userId: ids.userId,
      ttlSeconds: 60,
    });
    const second = await queue.enqueue({
      requestId: ids.secondRequestId,
      sessionId: ids.sessionId,
      userId: ids.userId + 1,
      ttlSeconds: 60,
    });

    expect(first).toEqual({ queueSeq: 1, queueRank: 0 });
    expect(second).toEqual({ queueSeq: 2, queueRank: 1 });
    await expect(queue.calculateQueueRank(ids.sessionId, second.queueSeq)).resolves.toBe(1);

    await expect(queue.dequeue(ids.sessionId)).resolves.toBe(ids.requestId);
    await expect(redis.lrange(queueKey(ids.sessionId), 0, -1)).resolves.toEqual([ids.secondRequestId]);
    await expect(redis.lrange(inflightQueueKey(ids.sessionId), 0, -1)).resolves.toEqual([ids.requestId]);
    await expect(queue.getRequestMetadata(ids.requestId)).resolves.toEqual(
      expect.objectContaining({
        requestId: ids.requestId,
        sessionId: ids.sessionId,
        userId: ids.userId,
        queueSeq: 1,
        status: 'INFLIGHT',
      }),
    );

    await queue.ackProcessed(ids.sessionId, ids.requestId, first.queueSeq);

    await expect(redis.get(processedSeqKey(ids.sessionId))).resolves.toBe('1');
    await expect(queue.getRequestMetadata(ids.requestId)).resolves.toBeNull();
    await expect(redis.lrange(inflightQueueKey(ids.sessionId), 0, -1)).resolves.toEqual([]);
    await expect(queue.calculateQueueRank(ids.sessionId, second.queueSeq)).resolves.toBe(0);
  });

  it('requeues an inflight request to the queue front and keeps the session active', async () => {
    await queue.enqueue({
      requestId: ids.requestId,
      sessionId: ids.sessionId,
      userId: ids.userId,
      ttlSeconds: 60,
    });
    await queue.enqueue({
      requestId: ids.secondRequestId,
      sessionId: ids.sessionId,
      userId: ids.userId + 1,
      ttlSeconds: 60,
    });
    await expect(queue.dequeue(ids.sessionId)).resolves.toBe(ids.requestId);

    await queue.requeueInflight(ids.sessionId, ids.requestId);

    await expect(redis.lrange(queueKey(ids.sessionId), 0, -1)).resolves.toEqual([ids.requestId, ids.secondRequestId]);
    await expect(redis.lrange(inflightQueueKey(ids.sessionId), 0, -1)).resolves.toEqual([]);
    await expect(queue.getActiveSessions()).resolves.toContain(ids.sessionId);
  });

  it('restores admission stock once from the durable marker after short hold keys disappear', async () => {
    await redis.set(stockKey(ids), '3');

    await expect(
      admission.admit({
        requestId: ids.requestId,
        userId: ids.userId,
        sessionId: ids.sessionId,
        ticketTypeId: ids.ticketTypeId,
        quantity: 2,
        seatIds: [ids.seatId],
        idempotencyKey: ids.idempotencyKey,
        ttlSeconds: 60,
      }),
    ).resolves.toEqual({ outcome: 'ACCEPTED', existingRequestId: ids.requestId });
    await expect(redis.get(stockKey(ids))).resolves.toBe('1');

    await redis.del([idempotencyKey(ids), userHoldKey(ids), seatHoldKey(ids.seatId)]);

    await admission.release({
      requestId: ids.requestId,
      userId: ids.userId,
      sessionId: ids.sessionId,
      ticketTypeId: ids.ticketTypeId,
      quantity: 2,
      seatIds: [ids.seatId],
      idempotencyKey: ids.idempotencyKey,
    });
    await expect(redis.get(stockKey(ids))).resolves.toBe('3');

    await admission.release({
      requestId: ids.requestId,
      userId: ids.userId,
      sessionId: ids.sessionId,
      ticketTypeId: ids.ticketTypeId,
      quantity: 2,
      seatIds: [ids.seatId],
      idempotencyKey: ids.idempotencyKey,
    });
    await expect(redis.get(stockKey(ids))).resolves.toBe('3');
  });

  async function cleanup(target: TestIds | undefined): Promise<void> {
    if (!target) return;

    await redis.del([
      queueSeqKey(target.sessionId),
      queueKey(target.sessionId),
      inflightQueueKey(target.sessionId),
      processedSeqKey(target.sessionId),
      requestKey(target.requestId),
      requestKey(target.secondRequestId),
      stockKey(target),
      idempotencyKey(target),
      userHoldKey(target),
      admissionKey(target.requestId),
      admissionKey(target.secondRequestId),
      seatHoldKey(target.seatId),
    ]);
    await redis.srem(activeSessionsKey(), String(target.sessionId));
  }
});

function createIds(): TestIds {
  sequence += 1;
  const base = numericRunBase + sequence * testCaseIdStride;

  return {
    sessionId: base,
    requestId: `it-${testRunId}-${sequence}-a`,
    secondRequestId: `it-${testRunId}-${sequence}-b`,
    ticketTypeId: base + 20_000,
    userId: base + 30_000,
    seatId: base + 40_000,
    idempotencyKey: `idem-${testRunId}-${sequence}`,
  };
}

function queueSeqKey(sessionId: number): string {
  return `grab:queue:seq:${sessionId}`;
}

function queueKey(sessionId: number): string {
  return `grab:queue:${sessionId}`;
}

function inflightQueueKey(sessionId: number): string {
  return `grab:queue:inflight:${sessionId}`;
}

function processedSeqKey(sessionId: number): string {
  return `grab:queue:processed:${sessionId}`;
}

function requestKey(requestId: string): string {
  return `grab:req:${requestId}`;
}

function activeSessionsKey(): string {
  return 'grab:active-sessions';
}

function stockKey(ids: Pick<TestIds, 'sessionId' | 'ticketTypeId'>): string {
  return `grab:stock:${ids.sessionId}:${ids.ticketTypeId}`;
}

function idempotencyKey(ids: Pick<TestIds, 'userId' | 'idempotencyKey'>): string {
  return `grab:idempotency:${ids.userId}:${ids.idempotencyKey}`;
}

function userHoldKey(ids: Pick<TestIds, 'userId' | 'sessionId' | 'ticketTypeId'>): string {
  return `grab:user-hold:${ids.userId}:${ids.sessionId}:${ids.ticketTypeId}`;
}

function admissionKey(requestId: string): string {
  return `grab:admission:${requestId}`;
}

function seatHoldKey(seatId: number): string {
  return `grab:seat-hold:${seatId}`;
}
