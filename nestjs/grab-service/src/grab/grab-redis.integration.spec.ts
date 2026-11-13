import { randomInt, randomUUID } from 'crypto';

import { GrabAdmissionService, type AdmitInput } from './grab-admission.service';
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
  let concurrentInputs: AdmitInput[];

  beforeAll(() => {
    redis = new RedisService();
    queue = new GrabQueueService(redis);
    admission = new GrabAdmissionService(redis);
  });

  beforeEach(async () => {
    ids = createIds();
    concurrentInputs = [];
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

    await redis.del([
      idempotencyKey(ids.userId, ids.idempotencyKey),
      userHoldKey(ids.userId, ids.sessionId, ids.ticketTypeId),
      seatHoldKey(ids.seatId),
    ]);

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

  it('admits no more buyers than stock under 100 concurrent requests and keeps retries idempotent', async () => {
    const inventory = 10;
    const contenderCount = 100;
    await redis.set(stockKey(ids), String(inventory));

    concurrentInputs = Array.from({ length: contenderCount }, (_, index) => ({
      requestId: `${ids.requestId}-buyer-${index}`,
      userId: ids.userId + index,
      sessionId: ids.sessionId,
      ticketTypeId: ids.ticketTypeId,
      quantity: 1,
      seatIds: [],
      idempotencyKey: `${ids.idempotencyKey}-buyer-${index}`,
      ttlSeconds: 60,
    }));

    const startedAt = process.hrtime.bigint();
    const results = await Promise.all(concurrentInputs.map((input) => admission.admit(input)));
    const elapsedMs = Number(process.hrtime.bigint() - startedAt) / 1_000_000;
    const accepted = results.filter((result) => result.outcome === 'ACCEPTED');
    const soldOut = results.filter((result) => result.outcome === 'SOLD_OUT');

    expect(results).toHaveLength(contenderCount);
    expect(accepted).toHaveLength(inventory);
    expect(soldOut).toHaveLength(contenderCount - inventory);
    expect(results.every((result) => result.outcome === 'ACCEPTED' || result.outcome === 'SOLD_OUT')).toBe(true);
    await expect(redis.get(stockKey(ids))).resolves.toBe('0');

    const acceptedInputs = concurrentInputs.filter((input) => accepted.some((result) => result.existingRequestId === input.requestId));
    const retries = await Promise.all(acceptedInputs.map((input) => admission.admit(input)));
    expect(retries).toHaveLength(inventory);
    expect(retries.every((result) => result.outcome === 'IDEMPOTENT')).toBe(true);
    expect(new Set(retries.map((result) => result.existingRequestId)).size).toBe(inventory);
    await expect(redis.get(stockKey(ids))).resolves.toBe('0');

    console.info(`并发准入验收：请求=${contenderCount}，库存=${inventory}，成功=${accepted.length}，售罄=${soldOut.length}，耗时=${elapsedMs.toFixed(1)}ms`);
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
      idempotencyKey(target.userId, target.idempotencyKey),
      userHoldKey(target.userId, target.sessionId, target.ticketTypeId),
      admissionKey(target.requestId),
      admissionKey(target.secondRequestId),
      seatHoldKey(target.seatId),
      ...concurrentInputs.flatMap((input) => [
        idempotencyKey(input.userId, input.idempotencyKey),
        userHoldKey(input.userId, input.sessionId, input.ticketTypeId),
        admissionKey(input.requestId),
      ]),
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

function idempotencyKey(userId: number, key: string): string {
  return `grab:idempotency:${userId}:${key}`;
}

function userHoldKey(userId: number, sessionId: number, ticketTypeId: number): string {
  return `grab:user-hold:${userId}:${sessionId}:${ticketTypeId}`;
}

function admissionKey(requestId: string): string {
  return `grab:admission:${requestId}`;
}

function seatHoldKey(seatId: number): string {
  return `grab:seat-hold:${seatId}`;
}
