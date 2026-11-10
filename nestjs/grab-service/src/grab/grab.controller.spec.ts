import { GrabController, GrabInternalController, GrabSessionController } from './grab.controller';
import { GRAB_STATUS } from './grab-status';
import { BadRequestException, UnauthorizedException } from '@nestjs/common';

describe('GrabController', () => {
  it('submits grab request using authenticated user id', async () => {
    const queuedResponse = {
      requestId: 'GRAB1',
      status: GRAB_STATUS.QUEUED,
      orderId: null,
      failReason: null,
      queueSeq: 10,
      queueRank: 3,
      estimatedWaitSeconds: null,
      message: 'queued',
    };
    const service: any = {
      submitRequest: jest.fn().mockResolvedValue(queuedResponse),
    };
    const controller = new GrabController(service);

    const result = await controller.submit({ user: { userId: 2004 } } as any, {
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      idempotencyKey: 'idem-1',
    });

    expect(service.submitRequest).toHaveBeenCalledWith(2004, {
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      idempotencyKey: 'idem-1',
    });
    expect(result).toEqual({ code: 200, message: '成功', data: queuedResponse });
  });

  it('ignores body userId and always uses authenticated user id', async () => {
    const queuedResponse = {
      requestId: 'GRAB2',
      status: GRAB_STATUS.QUEUED,
      orderId: null,
      failReason: null,
      queueSeq: 11,
      queueRank: 4,
      estimatedWaitSeconds: null,
      message: 'queued',
    };
    const service: any = {
      submitRequest: jest.fn().mockResolvedValue(queuedResponse),
    };
    const controller = new GrabController(service);

    const result = await controller.submit({ user: { userId: 2004 } } as any, {
      userId: 9999,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      idempotencyKey: 'idem-body-user',
    } as any);

    expect(service.submitRequest).toHaveBeenCalledWith(2004, {
      userId: 9999,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      idempotencyKey: 'idem-body-user',
    });
    expect(service.submitRequest).not.toHaveBeenCalledWith(9999, expect.anything());
    expect(result).toEqual({ code: 200, message: '成功', data: queuedResponse });
  });

  it('routes progress lookups through the authenticated user id', async () => {
    const progressResponse = {
      requestId: 'GRAB1',
      status: GRAB_STATUS.WAITING,
      queueRank: 3,
    };
    const service: any = {
      getProgress: jest.fn().mockResolvedValue(progressResponse),
    };
    const controller = new GrabController(service);

    const result = await controller.progress({ user: { userId: 2004 } } as any, 'GRAB1');

    expect(service.getProgress).toHaveBeenCalledWith(2004, 'GRAB1');
    expect(result).toEqual({ code: 200, message: '成功', data: progressResponse });
  });

  it('routes visible stock lookup with parsed ticket ids', async () => {
    const stockResponse = {
      sessionId: 101,
      ticketTypes: [{ ticketTypeId: 1, name: 'A', visibleStock: 87, level: 'AVAILABLE' }],
      snapshotTime: '2026-05-29T12:00:00.000Z',
    };
    const visibleStockService: any = {
      getSessionVisibleStock: jest.fn().mockResolvedValue(stockResponse),
    };
    const controller = new GrabSessionController(visibleStockService);

    const result = await controller.stockVisible('101', '1,2,bad,0');

    expect(visibleStockService.getSessionVisibleStock).toHaveBeenCalledWith(101, [1, 2]);
    expect(result).toEqual({ code: 200, message: '成功', data: stockResponse });
  });

  it('rejects visible stock lookup when session id or ticket ids are invalid', async () => {
    const visibleStockService: any = {
      getSessionVisibleStock: jest.fn(),
    };
    const controller = new GrabSessionController(visibleStockService);

    await expect(controller.stockVisible('0', '1')).rejects.toBeInstanceOf(BadRequestException);
    await expect(controller.stockVisible('101', '')).rejects.toBeInstanceOf(BadRequestException);
    expect(visibleStockService.getSessionVisibleStock).not.toHaveBeenCalled();
  });

  it('rejects internal user grab list when internal token is invalid', async () => {
    const previous = process.env.INTERNAL_API_TOKEN;
    process.env.INTERNAL_API_TOKEN = 'internal-token';
    const service: any = {
      listByUser: jest.fn(),
    };
    const controller = new GrabInternalController(service);

    await expect(controller.internalListByUser('wrong-token', '2004', '5')).rejects.toBeInstanceOf(UnauthorizedException);
    expect(service.listByUser).not.toHaveBeenCalled();
    process.env.INTERNAL_API_TOKEN = previous;
  });

  it('lists user grab requests through internal token', async () => {
    const previous = process.env.INTERNAL_API_TOKEN;
    process.env.INTERNAL_API_TOKEN = 'internal-token';
    const response = [{ requestId: 'GRAB1', status: GRAB_STATUS.QUEUED }];
    const service: any = {
      listByUser: jest.fn().mockResolvedValue(response),
    };
    const controller = new GrabInternalController(service);

    const result = await controller.internalListByUser('internal-token', '2004', '1');

    expect(service.listByUser).toHaveBeenCalledWith(2004, 1);
    expect(result).toEqual({ code: 200, message: '成功', data: response });
    process.env.INTERNAL_API_TOKEN = previous;
  });
});
