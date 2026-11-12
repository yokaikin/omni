import { WaitlistAllocatorService } from './waitlist-allocator.service';

describe('WaitlistAllocatorService', () => {
  it('creates one offered order for the earliest eligible entry', async () => {
    const entry = { id: 10, userId: 2004, sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501], status: 'ALLOCATING' };
    const repository = {
      beginAllocationEvent: jest.fn().mockResolvedValue(true),
      markOfferExpiredByOrder: jest.fn(),
      claimNextEntry: jest.fn().mockResolvedValue(entry),
      markEntryOffered: jest.fn().mockResolvedValue({ ...entry, status: 'OFFERED', offerOrderId: 9001, offerExpireTime: new Date('2026-05-31T00:15:00Z') }),
      createOffer: jest.fn().mockResolvedValue({ id: 99, orderId: 9001 }),
      logAllocationAttempt: jest.fn(),
    };
    const orderClient = {
      findByGrabRequestId: jest.fn().mockResolvedValue(null),
      createWaitlistOfferOrder: jest.fn().mockResolvedValue({ id: 9001, orderNo: 'O1', amount: 100 }),
    };
    const notifications = { notifyOffered: jest.fn(), notifyExpired: jest.fn(), notifyPaid: jest.fn() };
    const service = new WaitlistAllocatorService(repository as any, orderClient as any, notifications as any);

    const result = await service.allocate({ eventKey: 'release-1', source: 'ORDER_TIMEOUT', sourceOrderId: 1, sessionId: 101, ticketTypeId: 202, quantity: 1 });

    expect(result.status).toBe('OFFERED');
    expect(orderClient.createWaitlistOfferOrder).toHaveBeenCalledWith(expect.objectContaining({ userId: 2004, attendeeIds: [501], grabRequestId: expect.stringContaining('WAITLIST-10-') }));
    expect(notifications.notifyOffered).toHaveBeenCalled();
  });

  it('ignores duplicate release events', async () => {
    const repository = { beginAllocationEvent: jest.fn().mockResolvedValue(false), logAllocationAttempt: jest.fn() };
    const service = new WaitlistAllocatorService(repository as any, {} as any, {} as any);

    const result = await service.allocate({ eventKey: 'release-1', source: 'ORDER_TIMEOUT', sessionId: 101, ticketTypeId: 202, quantity: 1 });

    expect(result.status).toBe('DUPLICATE');
  });

  it('notifies users when a waitlist offer is paid', async () => {
    const repository = {
      markOfferPaidByOrder: jest.fn().mockResolvedValue({ orderId: 9001, userId: 2004 }),
    };
    const notifications = { notifyPaid: jest.fn() };
    const service = new WaitlistAllocatorService(repository as any, {} as any, notifications as any);

    await service.markPaidByOrder(9001);

    expect(notifications.notifyPaid).toHaveBeenCalledWith(expect.objectContaining({ userId: 2004, orderId: 9001 }));
  });

  it('notifies users when a released source order expires a previous offer', async () => {
    const entry = { id: 11, userId: 2005, sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [], status: 'ALLOCATING' };
    const repository = {
      beginAllocationEvent: jest.fn().mockResolvedValue(true),
      markOfferExpiredByOrder: jest.fn().mockResolvedValue({ orderId: 9001, userId: 2004 }),
      claimNextEntry: jest.fn().mockResolvedValue(entry),
      markEntryOffered: jest.fn().mockResolvedValue({ ...entry, status: 'OFFERED', offerOrderId: 9002 }),
      createOffer: jest.fn().mockResolvedValue({ id: 100, orderId: 9002 }),
      logAllocationAttempt: jest.fn(),
    };
    const orderClient = {
      findByGrabRequestId: jest.fn().mockResolvedValue(null),
      createWaitlistOfferOrder: jest.fn().mockResolvedValue({ id: 9002, orderNo: 'O2', amount: 100 }),
    };
    const notifications = { notifyOffered: jest.fn(), notifyExpired: jest.fn(), notifyPaid: jest.fn() };
    const service = new WaitlistAllocatorService(repository as any, orderClient as any, notifications as any);

    await service.allocate({ eventKey: 'release-2', source: 'ORDER_TIMEOUT', sourceOrderId: 9001, sessionId: 101, ticketTypeId: 202, quantity: 1 });

    expect(notifications.notifyExpired).toHaveBeenCalledWith(expect.objectContaining({ userId: 2004, orderId: 9001 }));
    expect(notifications.notifyOffered).toHaveBeenCalledWith(expect.objectContaining({ userId: 2005, orderId: 9002 }));
  });

  it('skips entries that already bought the session and continues allocating', async () => {
    const firstEntry = { id: 12, userId: 2004, sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501], status: 'ALLOCATING' };
    const secondEntry = { id: 13, userId: 2005, sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [502], status: 'ALLOCATING' };
    const repository = {
      beginAllocationEvent: jest.fn().mockResolvedValue(true),
      markOfferExpiredByOrder: jest.fn(),
      claimNextEntry: jest.fn()
        .mockResolvedValueOnce(firstEntry)
        .mockResolvedValueOnce(secondEntry),
      markEntryFailed: jest.fn(),
      restoreAllocatingEntry: jest.fn(),
      markEntryOffered: jest.fn().mockResolvedValue({ ...secondEntry, status: 'OFFERED', offerOrderId: 9002 }),
      createOffer: jest.fn().mockResolvedValue({ id: 101, orderId: 9002 }),
      logAllocationAttempt: jest.fn(),
    };
    const orderClient = {
      findByGrabRequestId: jest.fn().mockResolvedValue(null),
      createWaitlistOfferOrder: jest.fn()
        .mockRejectedValueOnce(new Error('该观演人已购买本场次门票'))
        .mockResolvedValueOnce({ id: 9002, orderNo: 'O2', amount: 100 }),
    };
    const notifications = { notifyOffered: jest.fn(), notifyExpired: jest.fn(), notifyPaid: jest.fn() };
    const service = new WaitlistAllocatorService(repository as any, orderClient as any, notifications as any);

    const result = await service.allocate({ eventKey: 'release-3', source: 'ORDER_TIMEOUT', sessionId: 101, ticketTypeId: 202, quantity: 1 });

    expect(result).toMatchObject({ status: 'OFFERED', entryId: 13, orderId: 9002 });
    expect(repository.markEntryFailed).toHaveBeenCalledWith(12, '该观演人已购买本场次门票');
    expect(repository.restoreAllocatingEntry).not.toHaveBeenCalled();
    expect(orderClient.createWaitlistOfferOrder).toHaveBeenCalledTimes(2);
    expect(notifications.notifyOffered).toHaveBeenCalledWith(expect.objectContaining({ userId: 2005, orderId: 9002 }));
  });

  it('marks the allocation event failed and rethrows on transient order service failure for MQ retry', async () => {
    const entry = { id: 14, userId: 2006, sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [503], status: 'ALLOCATING' };
    const repository = {
      beginAllocationEvent: jest.fn().mockResolvedValue(true),
      markOfferExpiredByOrder: jest.fn(),
      claimNextEntry: jest.fn().mockResolvedValue(entry),
      markEntryOffered: jest.fn(),
      createOffer: jest.fn(),
      markEntryFailed: jest.fn(),
      restoreAllocatingEntry: jest.fn(),
      logAllocationAttempt: jest.fn(),
      markAllocationEventFailed: jest.fn(),
    };
    const orderClient = {
      findByGrabRequestId: jest.fn().mockResolvedValue(null),
      createWaitlistOfferOrder: jest.fn().mockRejectedValue(new Error('connect ECONNREFUSED')),
    };
    const notifications = { notifyOffered: jest.fn(), notifyExpired: jest.fn(), notifyPaid: jest.fn() };
    const service = new WaitlistAllocatorService(repository as any, orderClient as any, notifications as any);

    await expect(
      service.allocate({ eventKey: 'release-4', source: 'ORDER_TIMEOUT', sessionId: 101, ticketTypeId: 202, quantity: 1 }),
    ).rejects.toThrow('connect ECONNREFUSED');

    expect(repository.restoreAllocatingEntry).toHaveBeenCalledWith(14, 'connect ECONNREFUSED');
    expect(repository.markAllocationEventFailed).toHaveBeenCalledWith('release-4', 'connect ECONNREFUSED');
    expect(repository.markEntryFailed).not.toHaveBeenCalled();
    expect(notifications.notifyOffered).not.toHaveBeenCalled();
  });
});
