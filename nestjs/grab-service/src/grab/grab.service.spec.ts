import { BadRequestException, ForbiddenException } from '@nestjs/common';
import { GRAB_STATUS } from './grab-status';
import { GrabService } from './grab.service';

function createService(overrides: {
  repository?: any;
  admission?: any;
  orderClient?: any;
  queue?: any;
  ticketClient?: any;
  visibleStock?: any;
  waitEstimator?: any;
} = {}): GrabService {
  return new GrabService(
    overrides.repository ?? {},
    overrides.admission ?? { admit: jest.fn(), release: jest.fn() },
    overrides.orderClient ?? { createOrder: jest.fn() },
    overrides.queue ?? { enqueue: jest.fn(), calculateQueueRank: jest.fn() },
    overrides.ticketClient ?? {
      listVisibleTicketTypes: jest.fn((sessionId: number, ids: number[]) => Promise.resolve(ids.map((id) => ({
        ticketTypeId: id,
        name: `Ticket ${id}`,
        price: id,
        remainStock: null,
      })))),
    },
    overrides.visibleStock ?? { getSessionVisibleStock: jest.fn() },
    overrides.waitEstimator ?? { recordProcessingMs: jest.fn(), estimateWaitSeconds: jest.fn().mockReturnValue(null) },
  );
}

describe('GrabService', () => {
  it('generates unique request ids under high concurrency volume', () => {
    const service = createService();
    const ids = Array.from({ length: 5000 }, () => service['generateRequestId']());

    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.every((id) => /^GRAB[0-9a-f]{24}$/.test(id))).toBe(true);
  });

  it('lists user grab requests with a bounded repository limit', async () => {
    const repository: any = {
      listByUser: jest.fn().mockResolvedValue([
        {
          requestId: 'GRAB-LIST-1',
          status: GRAB_STATUS.QUEUED,
          progressStatus: GRAB_STATUS.QUEUED,
          progressMessage: 'queued',
          orderId: null,
          failReason: null,
          queueSeq: null,
          sessionId: 101,
        },
      ]),
    };
    const service = createService({ repository });

    const result = await service.listByUser(2004, 50);

    expect(repository.listByUser).toHaveBeenCalledWith(2004, 20);
    expect(result).toEqual([
      {
        requestId: 'GRAB-LIST-1',
        status: GRAB_STATUS.QUEUED,
        orderId: null,
        failReason: null,
        queueSeq: null,
        queueRank: null,
        estimatedWaitSeconds: null,
        message: 'queued',
      },
    ]);
  });

  it('enqueues a grab request and does not create an order during submit', async () => {
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(null),
      findActiveByIntent: jest.fn().mockResolvedValue(null),
      createQueued: jest.fn().mockImplementation((input) => Promise.resolve({
        requestId: input.requestId,
        userId: 2004,
        sessionId: 101,
        ticketTypeId: 202,
        quantity: 2,
        seatIds: [301, 302],
        allocateRandom: false,
        idempotencyKey: 'idem-1',
        status: GRAB_STATUS.QUEUED,
        progressStatus: GRAB_STATUS.QUEUED,
        progressMessage: 'queued',
        orderId: null,
        failReason: null,
        queueSeq: input.queueSeq,
      })),
    };
    const admission: any = {
      admit: jest.fn(),
      release: jest.fn(),
    };
    const orderClient: any = {
      createOrder: jest.fn(),
    };
    const queue: any = {
      enqueue: jest.fn().mockResolvedValue({ queueSeq: 12, queueRank: 4 }),
      calculateQueueRank: jest.fn(),
    };
    const service = createService({ repository, admission, orderClient, queue });

    const result = await service.submitRequest(2004, {
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 2,
      seatIds: [302, 301],
      attendeeIds: [501, 502],
      allocateRandom: false,
      idempotencyKey: 'idem-1',
    });

    expect(admission.admit).not.toHaveBeenCalled();
    expect(orderClient.createOrder).not.toHaveBeenCalled();
    expect(queue.enqueue).toHaveBeenCalledWith({
      requestId: expect.stringMatching(/^GRAB/),
      sessionId: 101,
      userId: 2004,
      ttlSeconds: 900,
    });
    expect(repository.createQueued).toHaveBeenCalledWith(expect.objectContaining({
      queueSeq: 12,
      requestedTicketTypes: [{ ticketTypeId: 202, name: 'Ticket 202', maxPrice: 202 }],
      allowAutoDowngrade: false,
      seatIds: [301, 302],
      attendeeIds: [501, 502],
    }));
    expect(result).toEqual({
      requestId: expect.stringMatching(/^GRAB/),
      status: GRAB_STATUS.QUEUED,
      orderId: null,
      failReason: null,
      queueSeq: 12,
      queueRank: 4,
      estimatedWaitSeconds: null,
      message: 'queued',
    });
  });

  it('rejects auto downgrade when explicit seats are selected', async () => {
    const service = createService({
      repository: {
        findByUserAndIdempotency: jest.fn(),
        findActiveByIntent: jest.fn(),
        createQueued: jest.fn(),
      },
      queue: { enqueue: jest.fn(), calculateQueueRank: jest.fn() },
    });

    await expect(service.submitRequest(2004, {
      sessionId: 101,
      quantity: 2,
      seatIds: [301, 302],
      idempotencyKey: 'idem-seats',
      ticketTypePreferences: [
        { ticketTypeId: 202, name: 'A', maxPrice: 100 },
        { ticketTypeId: 203, name: 'B', maxPrice: 80 },
      ],
      allowAutoDowngrade: true,
    })).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects attendee ids when their count does not match quantity', async () => {
    const service = createService({
      repository: {
        findByUserAndIdempotency: jest.fn(),
        findActiveByIntent: jest.fn(),
        createQueued: jest.fn(),
      },
      queue: { enqueue: jest.fn(), calculateQueueRank: jest.fn() },
    });

    await expect(service.submitRequest(2004, {
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 2,
      attendeeIds: [501],
      idempotencyKey: 'idem-attendees',
    })).rejects.toBeInstanceOf(BadRequestException);
  });

  it('does not persist auto downgrade authorization when there is no later ticket type', async () => {
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(null),
      findActiveByIntent: jest.fn().mockResolvedValue(null),
      createQueued: jest.fn().mockImplementation((input) => Promise.resolve({
        requestId: input.requestId,
        userId: 2004,
        sessionId: 101,
        ticketTypeId: 202,
        quantity: 1,
        seatIds: [],
        allocateRandom: true,
        idempotencyKey: 'idem-single',
        status: GRAB_STATUS.QUEUED,
        progressStatus: GRAB_STATUS.QUEUED,
        progressMessage: null,
        orderId: null,
        failReason: null,
        queueSeq: input.queueSeq,
      })),
    };
    const queue: any = {
      enqueue: jest.fn().mockResolvedValue({ queueSeq: 13, queueRank: 5 }),
      calculateQueueRank: jest.fn(),
    };
    const ticketClient: any = {
      listVisibleTicketTypes: jest.fn().mockResolvedValue([{ ticketTypeId: 202, name: 'A', price: 100, remainStock: 1 }]),
    };
    const service = createService({ repository, queue, ticketClient });

    await service.submitRequest(2004, {
      sessionId: 101,
      quantity: 1,
      attendeeIds: [501],
      allocateRandom: true,
      idempotencyKey: 'idem-single',
      ticketTypePreferences: [{ ticketTypeId: 202, name: 'A', maxPrice: 100 }],
      allowAutoDowngrade: true,
    });

    expect(repository.createQueued).toHaveBeenCalledWith(expect.objectContaining({
      requestedTicketTypes: [{ ticketTypeId: 202, name: 'A', maxPrice: 100 }],
      allowAutoDowngrade: false,
    }));
  });

  it('cleans up the redis queue item when database creation fails after enqueue', async () => {
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(null),
      findActiveByIntent: jest.fn().mockResolvedValue(null),
      createQueued: jest.fn().mockRejectedValue(new Error('database unavailable')),
    };
    const queue: any = {
      enqueue: jest.fn().mockResolvedValue({ queueSeq: 12, queueRank: 4 }),
      removeQueuedRequest: jest.fn(),
      calculateQueueRank: jest.fn(),
    };
    const service = createService({ repository, queue });

    await expect(service.submitRequest(2004, {
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      allocateRandom: true,
      idempotencyKey: 'idem-db-fail',
    })).rejects.toThrow('database unavailable');

    const generatedRequestId = repository.createQueued.mock.calls[0][0].requestId;
    expect(queue.removeQueuedRequest).toHaveBeenCalledWith(101, generatedRequestId);
  });

  it('canonicalizes downgrade preferences from ticket metadata instead of trusting client prices', async () => {
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(null),
      findActiveByIntent: jest.fn().mockResolvedValue(null),
      createQueued: jest.fn().mockImplementation((input) => Promise.resolve({
        requestId: input.requestId,
        userId: 2004,
        sessionId: 101,
        ticketTypeId: 202,
        quantity: 1,
        seatIds: [],
        allocateRandom: true,
        idempotencyKey: 'idem-canonical',
        status: GRAB_STATUS.QUEUED,
        progressStatus: GRAB_STATUS.QUEUED,
        progressMessage: null,
        orderId: null,
        failReason: null,
        queueSeq: input.queueSeq,
      })),
    };
    const queue: any = {
      enqueue: jest.fn().mockResolvedValue({ queueSeq: 14, queueRank: 6 }),
      calculateQueueRank: jest.fn(),
    };
    const ticketClient: any = {
      listVisibleTicketTypes: jest.fn().mockResolvedValue([
        { ticketTypeId: 202, name: 'A档', price: 1280, remainStock: 1 },
        { ticketTypeId: 203, name: 'B档', price: 980, remainStock: 1 },
      ]),
    };
    const service = createService({ repository, queue, ticketClient });

    await service.submitRequest(2004, {
      sessionId: 101,
      quantity: 1,
      allocateRandom: true,
      idempotencyKey: 'idem-canonical',
      attendeeIds: [501],
      ticketTypePreferences: [
        { ticketTypeId: 202, name: 'client A', maxPrice: 1 },
        { ticketTypeId: 203, name: 'client B', maxPrice: 1 },
      ],
      allowAutoDowngrade: true,
    });

    expect(ticketClient.listVisibleTicketTypes).toHaveBeenCalledWith(101, [202, 203]);
    expect(repository.createQueued).toHaveBeenCalledWith(expect.objectContaining({
      requestedTicketTypes: [
        { ticketTypeId: 202, name: 'A档', maxPrice: 1280 },
        { ticketTypeId: 203, name: 'B档', maxPrice: 980 },
      ],
      allowAutoDowngrade: true,
    }));
  });

  it('rejects downgrade preferences that increase actual ticket price', async () => {
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(null),
      findActiveByIntent: jest.fn(),
      createQueued: jest.fn(),
    };
    const queue: any = {
      enqueue: jest.fn(),
      calculateQueueRank: jest.fn(),
    };
    const ticketClient: any = {
      listVisibleTicketTypes: jest.fn().mockResolvedValue([
        { ticketTypeId: 202, name: 'B档', price: 980, remainStock: 1 },
        { ticketTypeId: 203, name: 'A档', price: 1280, remainStock: 1 },
      ]),
    };
    const service = createService({ repository, queue, ticketClient });

    await expect(service.submitRequest(2004, {
      sessionId: 101,
      quantity: 1,
      allocateRandom: true,
      idempotencyKey: 'idem-increase',
      ticketTypePreferences: [
        { ticketTypeId: 202, name: 'B档', maxPrice: 980 },
        { ticketTypeId: 203, name: 'A档', maxPrice: 1280 },
      ],
      allowAutoDowngrade: true,
    })).rejects.toBeInstanceOf(BadRequestException);
    expect(queue.enqueue).not.toHaveBeenCalled();
    expect(repository.createQueued).not.toHaveBeenCalled();
  });

  it('returns existing idempotent queued request without enqueueing again', async () => {
    const existing = {
      requestId: 'GRAB-EXISTING',
      userId: 2004,
      sessionId: 101,
      status: GRAB_STATUS.QUEUED,
      progressStatus: GRAB_STATUS.QUEUED,
      progressMessage: 'waiting',
      orderId: null,
      failReason: null,
      queueSeq: 7,
    };
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(existing),
      findActiveByIntent: jest.fn(),
      createQueued: jest.fn(),
    };
    const queue: any = {
      enqueue: jest.fn(),
      calculateQueueRank: jest.fn().mockResolvedValue(2),
    };
    const service = createService({ repository, queue });

    const result = await service.submitRequest(2004, {
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      idempotencyKey: 'idem-1',
    });

    expect(repository.findActiveByIntent).not.toHaveBeenCalled();
    expect(queue.enqueue).not.toHaveBeenCalled();
    expect(repository.createQueued).not.toHaveBeenCalled();
    expect(queue.calculateQueueRank).toHaveBeenCalledWith(101, 7);
    expect(result).toEqual({
      requestId: 'GRAB-EXISTING',
      status: GRAB_STATUS.QUEUED,
      orderId: null,
      failReason: null,
      queueSeq: 7,
      queueRank: 2,
      estimatedWaitSeconds: null,
      message: 'waiting',
    });
  });

  it('returns existing active request for the same grab intent without enqueueing again', async () => {
    const existing = {
      requestId: 'GRAB-ACTIVE',
      userId: 2004,
      sessionId: 101,
      status: GRAB_STATUS.QUEUED,
      progressStatus: GRAB_STATUS.QUEUED,
      progressMessage: 'queued',
      orderId: null,
      failReason: null,
      queueSeq: 8,
    };
    const repository: any = {
      findByUserAndIdempotency: jest.fn().mockResolvedValue(null),
      findActiveByIntent: jest.fn().mockResolvedValue(existing),
      createQueued: jest.fn(),
    };
    const queue: any = {
      enqueue: jest.fn(),
      calculateQueueRank: jest.fn().mockResolvedValue(3),
    };
    const ticketClient: any = {
      listVisibleTicketTypes: jest.fn().mockResolvedValue([
        { ticketTypeId: 202, name: 'A', price: 100, remainStock: 1 },
        { ticketTypeId: 203, name: 'B', price: 80, remainStock: 1 },
      ]),
    };
    const service = createService({ repository, queue, ticketClient });

    const result = await service.submitRequest(2004, {
      sessionId: 101,
      ticketTypePreferences: [
        { ticketTypeId: 202, name: 'A', maxPrice: 100 },
        { ticketTypeId: 203, name: 'B', maxPrice: 80 },
      ],
      allowAutoDowngrade: true,
      quantity: 2,
      attendeeIds: [501, 502],
      allocateRandom: false,
      idempotencyKey: 'new-key',
    });

    expect(repository.findActiveByIntent).toHaveBeenCalledWith({
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 2,
      seatIds: [],
      attendeeIds: [501, 502],
      allocateRandom: false,
      requestedTicketTypes: [
        { ticketTypeId: 202, name: 'A', maxPrice: 100 },
        { ticketTypeId: 203, name: 'B', maxPrice: 80 },
      ],
      allowAutoDowngrade: true,
    });
    expect(queue.enqueue).not.toHaveBeenCalled();
    expect(repository.createQueued).not.toHaveBeenCalled();
    expect(result).toEqual({
      requestId: 'GRAB-ACTIVE',
      status: GRAB_STATUS.QUEUED,
      orderId: null,
      failReason: null,
      queueSeq: 8,
      queueRank: 3,
      estimatedWaitSeconds: null,
      message: 'queued',
    });
  });

  it('returns progress with queue rank for the owner', async () => {
    const record: any = {
      requestId: 'GRAB-PROGRESS',
      userId: 2004,
      sessionId: 101,
      status: GRAB_STATUS.WAITING,
      progressStatus: GRAB_STATUS.WAITING,
      progressMessage: 'waiting',
      orderId: null,
      failReason: null,
      queueSeq: 10,
      currentTicketTypeId: 202,
      currentAttemptIndex: 0,
      requestedTicketTypes: [{ ticketTypeId: 202, name: 'A', maxPrice: 100 }],
      allowAutoDowngrade: true,
      attemptsSnapshot: [{ ticketTypeId: 202, name: 'A', status: 'PENDING', message: 'pending' }],
      matchedTicketTypeId: null,
      updatedAt: new Date('2026-05-29T12:00:00.000Z'),
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
    };
    const queue: any = {
      calculateQueueRank: jest.fn().mockResolvedValue(3),
    };
    const service = createService({ repository, queue });

    const result = await service.getProgress(2004, 'GRAB-PROGRESS');

    expect(repository.findByRequestId).toHaveBeenCalledWith('GRAB-PROGRESS');
    expect(queue.calculateQueueRank).toHaveBeenCalledWith(101, 10);
    expect(result).toEqual({
      requestId: 'GRAB-PROGRESS',
      sessionId: 101,
      status: GRAB_STATUS.WAITING,
      orderId: null,
      failReason: null,
      queueSeq: 10,
      queueRank: 3,
      estimatedWaitSeconds: null,
      currentTicketTypeId: 202,
      currentAttemptIndex: 0,
      requestedTicketTypes: [{ ticketTypeId: 202, name: 'A', maxPrice: 100 }],
      allowAutoDowngrade: true,
      attempts: [{ ticketTypeId: 202, name: 'A', status: 'PENDING', message: 'pending' }],
      visibleStock: null,
      fairnessNotes: [
        '同一账号相同购票意图会复用已有排队请求，避免重复挤占队列。',
        '活动限购规则会在锁票和创建订单阶段校验。',
        '异常高频请求会触发风控拦截，请使用当前页面正常刷新。',
      ],
      message: 'waiting',
      matchedTicketTypeId: null,
      updateTime: '2026-05-29T12:00:00.000Z',
    });
  });

  it('does not release redis holds when cancelling a request that never entered redis admission', async () => {
    const record = {
      requestId: 'GRAB-FAILED',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-failed',
      status: GRAB_STATUS.FAILED,
      orderId: null,
      failReason: 'stock unavailable',
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
      updateStatus: jest.fn(),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-FAILED');

    expect(admission.release).not.toHaveBeenCalled();
    expect(repository.updateStatus).not.toHaveBeenCalled();
    expect(result).toMatchObject({ requestId: 'GRAB-FAILED', status: GRAB_STATUS.FAILED, orderId: null, failReason: 'stock unavailable' });
  });

  it('cancels queued async progress without releasing redis holds', async () => {
    const record = {
      requestId: 'GRAB-QUEUED',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-queued',
      status: GRAB_STATUS.ACCEPTED,
      progressStatus: GRAB_STATUS.QUEUED,
      orderId: null,
      failReason: null,
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
      expireActiveRequest: jest.fn().mockResolvedValue({
        ...record,
        status: GRAB_STATUS.EXPIRED,
        progressStatus: GRAB_STATUS.EXPIRED,
        failReason: '抢票请求已取消',
      }),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-QUEUED');

    expect(admission.release).not.toHaveBeenCalled();
    expect(repository.expireActiveRequest).toHaveBeenCalledWith('GRAB-QUEUED', '抢票请求已取消', [GRAB_STATUS.QUEUED]);
    expect(result).toMatchObject({ requestId: 'GRAB-QUEUED', status: GRAB_STATUS.EXPIRED, orderId: null, failReason: '抢票请求已取消' });
  });

  it('returns current ticket visible stock in progress when available', async () => {
    const record: any = {
      requestId: 'GRAB-PROGRESS',
      userId: 2004,
      sessionId: 101,
      status: GRAB_STATUS.LOCKING,
      progressStatus: GRAB_STATUS.LOCKING,
      progressMessage: 'locking',
      orderId: null,
      failReason: null,
      queueSeq: 10,
      currentTicketTypeId: 202,
      currentAttemptIndex: 0,
      requestedTicketTypes: [{ ticketTypeId: 202, name: 'A', maxPrice: 100 }],
      attemptsSnapshot: [{ ticketTypeId: 202, name: 'A', status: 'LOCKING', message: 'locking' }],
      matchedTicketTypeId: null,
      updatedAt: new Date('2026-05-29T12:00:00.000Z'),
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
    };
    const queue: any = {
      calculateQueueRank: jest.fn().mockResolvedValue(3),
    };
    const visibleStock: any = {
      getSessionVisibleStock: jest.fn().mockResolvedValue({
        sessionId: 101,
        ticketTypes: [{ ticketTypeId: 202, name: 'A', visibleStock: 87, level: 'AVAILABLE' }],
        snapshotTime: '2026-05-29T12:00:01.000Z',
      }),
    };
    const service = createService({ repository, queue, visibleStock });

    const result = await service.getProgress(2004, 'GRAB-PROGRESS');

    expect(visibleStock.getSessionVisibleStock).toHaveBeenCalledWith(101, [202]);
    expect(result.visibleStock).toEqual({
      ticketTypeId: 202,
      visibleStock: 87,
      level: 'AVAILABLE',
      snapshotTime: '2026-05-29T12:00:01.000Z',
    });
  });

  it('does not cancel already expired requests', async () => {
    const record = {
      requestId: 'GRAB-EXPIRED',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-expired',
      status: GRAB_STATUS.EXPIRED,
      progressStatus: GRAB_STATUS.EXPIRED,
      orderId: null,
      failReason: 'previous expiry',
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
      updateStatus: jest.fn(),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-EXPIRED');

    expect(admission.release).not.toHaveBeenCalled();
    expect(repository.updateStatus).not.toHaveBeenCalled();
    expect(result).toMatchObject({ requestId: 'GRAB-EXPIRED', status: GRAB_STATUS.EXPIRED, orderId: null, failReason: 'previous expiry' });
  });

  it('rejects reading another user grab request', async () => {
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue({
        requestId: 'GRAB-OTHER',
        userId: 2005,
        status: GRAB_STATUS.ORDER_CREATED,
        orderId: 9001,
        failReason: null,
      }),
    };
    const service = createService({ repository });

    await expect(service.getRequest(2004, 'GRAB-OTHER')).rejects.toBeInstanceOf(ForbiddenException);
    expect(repository.findByRequestId).toHaveBeenCalledWith('GRAB-OTHER');
  });

  it('rejects cancelling another user grab request', async () => {
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue({
        requestId: 'GRAB-OTHER',
        userId: 2005,
        sessionId: 101,
        ticketTypeId: 202,
        quantity: 1,
        seatIds: [],
        allocateRandom: false,
        idempotencyKey: 'idem-other',
        status: GRAB_STATUS.ACCEPTED,
        orderId: null,
        failReason: null,
      }),
      updateStatus: jest.fn(),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    await expect(service.cancelRequest(2004, 'GRAB-OTHER')).rejects.toBeInstanceOf(ForbiddenException);
    expect(admission.release).not.toHaveBeenCalled();
    expect(repository.updateStatus).not.toHaveBeenCalled();
  });

  it('returns existing order-created request when cancelling after order creation', async () => {
    const record = {
      requestId: 'GRAB-ORDERED',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-ordered',
      status: GRAB_STATUS.ORDER_CREATED,
      orderId: 9001,
      failReason: null,
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
      updateStatus: jest.fn(),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-ORDERED');

    expect(admission.release).not.toHaveBeenCalled();
    expect(repository.updateStatus).not.toHaveBeenCalled();
    expect(result).toMatchObject({ requestId: 'GRAB-ORDERED', status: GRAB_STATUS.ORDER_CREATED, orderId: 9001, failReason: null });
  });

  it('expires locking request and releases redis hold when cancelling an in-flight request that may hold stock', async () => {
    const record = {
      requestId: 'GRAB-LOCKING',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-locking',
      status: GRAB_STATUS.ACCEPTED,
      progressStatus: 'LOCKING',
      currentTicketTypeId: 203,
      orderId: null,
      failReason: null,
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
      expireActiveRequest: jest.fn().mockResolvedValue({
        ...record,
        status: GRAB_STATUS.EXPIRED,
        progressStatus: GRAB_STATUS.EXPIRED,
        failReason: '抢票请求已取消',
      }),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-LOCKING');

    expect(admission.release).toHaveBeenCalledWith({
      requestId: 'GRAB-LOCKING',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 203,
      quantity: 1,
      seatIds: [],
      idempotencyKey: 'idem-locking',
    });
    expect(repository.expireActiveRequest).toHaveBeenCalledWith('GRAB-LOCKING', '抢票请求已取消', [GRAB_STATUS.LOCKING]);
    expect(result).toMatchObject({ requestId: 'GRAB-LOCKING', status: GRAB_STATUS.EXPIRED, orderId: null, failReason: '抢票请求已取消' });
  });

  it('does not release redis holds when conditional cancellation loses the race', async () => {
    const record = {
      requestId: 'GRAB-RACE',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-race',
      status: GRAB_STATUS.LOCKING,
      progressStatus: GRAB_STATUS.LOCKING,
      currentTicketTypeId: 202,
      orderId: null,
      failReason: null,
    };
    const ordered = {
      ...record,
      status: GRAB_STATUS.ORDER_CREATED,
      progressStatus: GRAB_STATUS.ORDER_CREATED,
      orderId: 9001,
    };
    const repository: any = {
      findByRequestId: jest.fn()
        .mockResolvedValueOnce(record)
        .mockResolvedValueOnce(ordered),
      expireActiveRequest: jest.fn().mockResolvedValue(null),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-RACE');

    expect(repository.expireActiveRequest).toHaveBeenCalledWith('GRAB-RACE', '抢票请求已取消', [GRAB_STATUS.LOCKING]);
    expect(admission.release).not.toHaveBeenCalled();
    expect(result).toMatchObject({ requestId: 'GRAB-RACE', status: GRAB_STATUS.ORDER_CREATED, orderId: 9001 });
  });

  it('does not cancel once order creation has started', async () => {
    const record = {
      requestId: 'GRAB-ORDER-CREATING',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      idempotencyKey: 'idem-order-creating',
      status: GRAB_STATUS.ORDER_CREATING,
      progressStatus: GRAB_STATUS.ORDER_CREATING,
      orderId: null,
      failReason: null,
    };
    const repository: any = {
      findByRequestId: jest.fn().mockResolvedValue(record),
      updateStatus: jest.fn(),
    };
    const admission: any = { release: jest.fn() };
    const service = createService({ repository, admission });

    const result = await service.cancelRequest(2004, 'GRAB-ORDER-CREATING');

    expect(admission.release).not.toHaveBeenCalled();
    expect(repository.updateStatus).not.toHaveBeenCalled();
    expect(result).toMatchObject({ requestId: 'GRAB-ORDER-CREATING', status: GRAB_STATUS.ORDER_CREATING, orderId: null, failReason: null });
  });
});
