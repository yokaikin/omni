import { GrabOrderReleaseService } from './grab-order-release.service';
import { GRAB_STATUS } from './grab-status';

describe('GrabOrderReleaseService', () => {
  it('releases the matching order admission and synchronizes stock', async () => {
    const request = {
      requestId: 'GRAB-1',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      currentTicketTypeId: 203,
      quantity: 2,
      seatIds: [301, 302],
      idempotencyKey: 'idem-1',
      orderId: 9001,
      progressStatus: GRAB_STATUS.ORDER_CREATED,
    };
    const repository = { findByOrderId: jest.fn().mockResolvedValue([request]) };
    const admission = { release: jest.fn().mockResolvedValue(undefined) };
    const stock = { syncFromTicketType: jest.fn().mockResolvedValue(8) };
    const service = new GrabOrderReleaseService(repository as any, admission as any, stock as any);

    await service.releaseForOrder({ sourceOrderId: 9001, sessionId: 101, ticketTypeId: 203, quantity: 2 });

    expect(admission.release).toHaveBeenCalledWith({
      requestId: 'GRAB-1',
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 203,
      quantity: 2,
      seatIds: [301, 302],
      idempotencyKey: 'idem-1',
      restoreStock: true,
    });
    expect(stock.syncFromTicketType).toHaveBeenCalledWith(101, 203);
  });

  it('does not release an unrelated or already non-order-created request', async () => {
    const repository = {
      findByOrderId: jest.fn().mockResolvedValue([
        { orderId: 9001, sessionId: 101, progressStatus: GRAB_STATUS.FAILED },
        { orderId: 9001, sessionId: 101, ticketTypeId: 204, progressStatus: GRAB_STATUS.ORDER_CREATED },
        { orderId: 9002, sessionId: 101, ticketTypeId: 203, progressStatus: GRAB_STATUS.ORDER_CREATED },
      ]),
    };
    const admission = { release: jest.fn() };
    const stock = { syncFromTicketType: jest.fn().mockResolvedValue(8) };
    const service = new GrabOrderReleaseService(repository as any, admission as any, stock as any);

    await service.releaseForOrder({ sourceOrderId: 9001, sessionId: 101, ticketTypeId: 203, quantity: 1 });

    expect(admission.release).not.toHaveBeenCalled();
    expect(stock.syncFromTicketType).toHaveBeenCalledWith(101, 203);
  });
});
