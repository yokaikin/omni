import { BadRequestException, ConflictException, NotFoundException } from '@nestjs/common';
import { WaitlistService } from './waitlist.service';

describe('WaitlistService', () => {
  const entry = {
    id: 1,
    userId: 2004,
    sessionId: 101,
    ticketTypeId: 202,
    quantity: 1,
    seatPreference: null,
    status: 'WAITING',
    priorityNo: 8,
    offerOrderId: null,
    offerExpireTime: null,
    failReason: null,
    createTime: new Date('2026-05-31T00:00:00Z'),
    updateTime: new Date('2026-05-31T00:00:00Z'),
  } as const;

  it('creates a waitlist entry with attendee ids and returns rank', async () => {
    const repository = {
      createEntry: jest.fn().mockResolvedValue({ entry, rank: 3 }),
    };
    const visibleStock = { getSessionVisibleStock: jest.fn().mockResolvedValue({ ticketTypes: [{ ticketTypeId: 202, visibleStock: 0 }] }) };
    const service = new WaitlistService(repository as any, undefined, visibleStock as any);

    const result = await service.createEntry(2004, { sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501] });

    expect(repository.createEntry).toHaveBeenCalledWith({ userId: 2004, sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501] });
    expect(visibleStock.getSessionVisibleStock).toHaveBeenCalledWith(101, [202]);
    expect(result).toMatchObject({
      id: 1,
      status: 'WAITING',
      rank: 3,
      estimatedChance: 'HIGH',
      estimatedChanceText: '机会较高',
      estimatedWaitText: '排位靠前，释放票后会优先通知',
    });
  });

  it('lists user waitlist entries with a bounded repository limit', async () => {
    const repository = {
      listByUser: jest.fn().mockResolvedValue([{ ...entry, rank: 3 }]),
    };
    const service = new WaitlistService(repository as any);

    const result = await service.listByUser(2004, 50);

    expect(repository.listByUser).toHaveBeenCalledWith(2004, 20);
    expect(result).toMatchObject([
      {
        id: 1,
        status: 'WAITING',
        rank: 3,
        estimatedChance: 'HIGH',
      },
    ]);
  });

  it('adds readable ticket context to user waitlist entries', async () => {
    const repository = {
      listByUser: jest.fn().mockResolvedValue([{ ...entry, rank: 3 }]),
    };
    const ticketClient = {
      getPurchaseContext: jest.fn().mockResolvedValue({
        sessionId: 101,
        ticketTypeId: 202,
        activityId: 303,
        activityName: '周末演唱会',
        activityPoster: '/poster.jpg',
        ticketTypeName: '看台 A',
        venueName: '万象体育馆',
        sessionTime: '2026-07-18T19:30:00',
      }),
    };
    const service = new (WaitlistService as any)(repository, ticketClient);

    const result = await service.listByUser(2004, 5);

    expect(ticketClient.getPurchaseContext).toHaveBeenCalledWith(101, 202);
    expect(result[0]).toMatchObject({
      activityId: 303,
      activityName: '周末演唱会',
      activityPoster: '/poster.jpg',
      ticketTypeName: '看台 A',
      venueName: '万象体育馆',
      sessionTime: '2026-07-18T19:30:00',
    });
  });

  it('keeps waitlist entries visible when ticket context enrichment fails', async () => {
    const repository = {
      listByUser: jest.fn().mockResolvedValue([{ ...entry, rank: 3 }]),
    };
    const ticketClient = {
      getPurchaseContext: jest.fn().mockRejectedValue(new Error('ticket unavailable')),
    };
    const service = new (WaitlistService as any)(repository, ticketClient);

    const result = await service.listByUser(2004, 5);

    expect(result[0]).toMatchObject({
      id: 1,
      sessionId: 101,
      ticketTypeId: 202,
      rank: 3,
    });
    expect(result[0].activityName).toBeUndefined();
  });

  it('rejects invalid quantity', async () => {
    const service = new WaitlistService({ createEntry: jest.fn() } as any);

    await expect(service.createEntry(2004, { sessionId: 101, ticketTypeId: 202, quantity: 0 })).rejects.toMatchObject({
      message: '候补数量必须为 1-6 张',
    });
  });

  it('rejects attendee ids when count does not match quantity', async () => {
    const service = new WaitlistService({ createEntry: jest.fn() } as any);

    await expect(service.createEntry(2004, { sessionId: 101, ticketTypeId: 202, quantity: 2, attendeeIds: [501] })).rejects.toMatchObject({
      message: '请选择与候补数量一致的实名观演人',
    });
  });

  it('maps active duplicate entries to conflict', async () => {
    const repository = {
      createEntry: jest.fn().mockRejectedValue({ code: '23505' }),
    };
    const visibleStock = { getSessionVisibleStock: jest.fn().mockResolvedValue({ ticketTypes: [{ ticketTypeId: 202, visibleStock: 0 }] }) };
    const service = new WaitlistService(repository as any, undefined, visibleStock as any);

    await expect(service.createEntry(2004, { sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501] })).rejects.toMatchObject({
      message: '已加入该场次票档候补',
    });
  });

  it('only accepts a waitlist entry after the ticket type is sold out', async () => {
    const repository = { createEntry: jest.fn() };
    const visibleStock = { getSessionVisibleStock: jest.fn().mockResolvedValue({ ticketTypes: [{ ticketTypeId: 202, visibleStock: 1 }] }) };
    const service = new WaitlistService(repository as any, undefined, visibleStock as any);

    await expect(service.createEntry(2004, { sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501] })).rejects.toMatchObject({
      message: '当前票档尚未售罄，暂不能加入候补',
    });
    expect(repository.createEntry).not.toHaveBeenCalled();
  });

  it('only cancels waiting entries owned by the user', async () => {
    const repository = {
      cancelWaitingEntry: jest.fn().mockResolvedValue(null),
    };
    const service = new WaitlistService(repository as any);

    await expect(service.cancelEntry(2004, 1)).rejects.toMatchObject({
      message: '未找到可取消的候补记录',
    });
    expect(repository.cancelWaitingEntry).toHaveBeenCalledWith(1, 2004);
  });
});
