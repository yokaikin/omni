import { OrderClientService } from './order-client.service';

describe('OrderClientService', () => {
  const originalFetch = global.fetch;
  const originalEnv = process.env;

  beforeEach(() => {
    jest.resetModules();
    process.env = { ...originalEnv, ORDER_SERVICE_URL: 'http://order.local', INTERNAL_API_TOKEN: 'internal-token' };
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: jest.fn().mockResolvedValue({ code: 200, data: { id: 14, orderNo: 'DM1', amount: 160 } }),
    } as any);
  });

  afterEach(() => {
    global.fetch = originalFetch;
    process.env = originalEnv;
  });

  it('creates normal orders through internal endpoint with token', async () => {
    const service = new OrderClientService();

    await service.createOrder({ userId: 2004, sessionId: 7, ticketTypeId: 21, quantity: 1, seatIds: [], allocateRandom: false });

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/create', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': 'internal-token' },
    }));
  });

  it('creates seat orders through internal endpoint with token', async () => {
    const service = new OrderClientService();

    await service.createOrder({ userId: 2004, sessionId: 7, ticketTypeId: 21, quantity: 1, seatIds: [301], attendeeIds: [501], allocateRandom: false });

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/create-with-seats', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': 'internal-token' },
    }));
  });

  it('creates waitlist offer orders through the random seat order endpoint', async () => {
    const service = new OrderClientService();

    await service.createWaitlistOfferOrder({
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 202,
      quantity: 1,
      attendeeIds: [501],
      grabRequestId: 'WAITLIST-10-abcdef',
    });

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/create-with-seats', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        userId: 2004,
        sessionId: 101,
        ticketTypeId: 202,
        seatIds: [],
        quantity: 1,
        attendeeIds: [501],
        authorizedMaxUnitPrice: undefined,
        grabRequestId: 'WAITLIST-10-abcdef',
        requestedTicketTypeId: 202,
        matchedTicketTypeId: 202,
        autoDowngraded: false,
      }),
    }));
  });

  it('passes grab authorization and matched ticket metadata to order service', async () => {
    const service = new OrderClientService();

    await service.createOrder({
      userId: 2004,
      sessionId: 101,
      ticketTypeId: 2,
      quantity: 1,
      seatIds: [],
      allocateRandom: false,
      attendeeIds: [501],
      authorizedMaxUnitPrice: 980,
      grabRequestId: 'GRAB1',
      requestedTicketTypeId: 1,
      matchedTicketTypeId: 2,
      autoDowngraded: true,
    });

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/create', expect.objectContaining({
      body: JSON.stringify({
        userId: 2004,
        sessionId: 101,
        ticketTypeId: 2,
        quantity: 1,
        attendeeIds: [501],
        authorizedMaxUnitPrice: 980,
        grabRequestId: 'GRAB1',
        requestedTicketTypeId: 1,
        matchedTicketTypeId: 2,
        autoDowngraded: true,
      }),
    }));
  });

  it('loads an existing order by grab request id through the internal endpoint', async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: jest.fn().mockResolvedValue({
        code: 200,
        data: { id: 9001, orderNo: 'O1', status: 'PENDING', grabRequestId: 'GRAB1' },
      }),
    } as any);
    const service = new OrderClientService();

    const result = await service.findByGrabRequestId('GRAB1');

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/grab-requests/GRAB1', {
      method: 'GET',
      headers: { 'X-Internal-Token': 'internal-token' },
    });
    expect(result).toEqual({ id: 9001, orderNo: 'O1', status: 'PENDING', grabRequestId: 'GRAB1' });
  });

  it('returns null when the grab request id has no order', async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: jest.fn().mockResolvedValue({ code: 200, data: null }),
    } as any);
    const service = new OrderClientService();

    await expect(service.findByGrabRequestId('GRAB-MISSING')).resolves.toBeNull();
  });

  it('loads order status from the internal order detail endpoint with token', async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: jest.fn().mockResolvedValue({
        code: 200,
        data: { id: 9001, status: 2, userId: 100, quantity: 2 },
      }),
    } as any);
    const service = new OrderClientService();

    await expect(service.getOrder(9001)).resolves.toEqual({ id: 9001, status: 2, userId: 100, quantity: 2 });

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/9001', {
      method: 'GET',
      headers: { 'X-Internal-Token': 'internal-token' },
    });
  });

  it('loads order seats from the internal endpoint with token', async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: jest.fn().mockResolvedValue({
        code: 200,
        data: [
          { orderSeatId: 7001, sessionSeatId: 501, seatLabel: 'A-1', status: 2 },
          { orderSeatId: 7002, sessionSeatId: 502, seatLabel: 'A-2', status: 2 },
        ],
      }),
    } as any);
    const service = new OrderClientService();

    await expect(service.listOrderSeats(9001)).resolves.toEqual([
      { orderSeatId: 7001, sessionSeatId: 501, seatLabel: 'A-1', status: 2 },
      { orderSeatId: 7002, sessionSeatId: 502, seatLabel: 'A-2', status: 2 },
    ]);

    expect(global.fetch).toHaveBeenCalledWith('http://order.local/api/order/internal/9001/seats', {
      method: 'GET',
      headers: { 'X-Internal-Token': 'internal-token' },
    });
  });

  it('fails during initialization when order service URL is missing', async () => {
    delete process.env.ORDER_SERVICE_URL;

    expect(() => new OrderClientService()).toThrow('订单服务地址未配置');
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('fails before calling order service when internal token is missing', async () => {
    delete process.env.INTERNAL_API_TOKEN;
    const service = new OrderClientService();

    await expect(service.createOrder({ userId: 2004, sessionId: 7, ticketTypeId: 21, quantity: 1, seatIds: [], attendeeIds: [501], allocateRandom: false }))
      .rejects.toThrow('订单内部接口令牌未配置');
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
