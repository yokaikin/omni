import { TicketClientService } from './ticket-client.service';

describe('TicketClientService', () => {
  const originalFetch = global.fetch;
  const originalEnv = process.env;

  beforeEach(() => {
    process.env = { ...originalEnv, TICKET_SERVICE_URL: 'http://ticket.local', INTERNAL_API_TOKEN: 'internal-token' };
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: jest.fn().mockResolvedValue({
        code: 200,
        data: [{ ticketTypeId: 1, name: 'A', price: 1280, remainStock: 87 }],
      }),
    } as any);
  });

  afterEach(() => {
    process.env = originalEnv;
    global.fetch = originalFetch;
  });

  it('loads ticket metadata through the internal ticket endpoint', async () => {
    const service = new TicketClientService();

    const result = await service.listVisibleTicketTypes(101, [1]);

    expect(global.fetch).toHaveBeenCalledWith('http://ticket.local/api/ticket/internal/sales/ticket-types-visible', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': 'internal-token' },
      body: JSON.stringify({ sessionId: 101, ticketTypeIds: [1] }),
    }));
    expect(result).toEqual([{ ticketTypeId: 1, name: 'A', price: 1280, remainStock: 87 }]);
  });

  it('loads readable purchase context through the internal ticket endpoint', async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: jest.fn().mockResolvedValue({
        code: 200,
        data: {
          sessionId: 101,
          ticketTypeId: 202,
          activityId: 303,
          activityName: '周末演唱会',
          activityPoster: '/poster.jpg',
          ticketTypeName: '看台 A',
          venueName: '万象体育馆',
          sessionTime: '2026-07-18T19:30:00',
        },
      }),
    } as any);
    const service = new TicketClientService();

    const result = await (service as any).getPurchaseContext(101, 202);

    expect(global.fetch).toHaveBeenCalledWith('http://ticket.local/api/ticket/internal/sales/purchase-context', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': 'internal-token' },
      body: JSON.stringify({ sessionId: 101, ticketTypeId: 202 }),
    }));
    expect(result).toEqual({
      sessionId: 101,
      ticketTypeId: 202,
      activityId: 303,
      activityName: '周末演唱会',
      activityPoster: '/poster.jpg',
      ticketTypeName: '看台 A',
      venueName: '万象体育馆',
      sessionTime: '2026-07-18T19:30:00',
    });
  });

  it('fails during initialization when ticket service URL is missing', () => {
    delete process.env.TICKET_SERVICE_URL;
    process.env.ORDER_SERVICE_URL = 'http://order.local';

    expect(() => new TicketClientService()).toThrow('票务服务地址未配置');
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
