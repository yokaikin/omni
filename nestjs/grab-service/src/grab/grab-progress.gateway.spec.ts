import * as jwt from 'jsonwebtoken';
import { GrabProgressGateway } from './grab-progress.gateway';
import { GrabService } from './grab.service';
import type { GrabProgressResponse } from './grab.types';

function makeClient(handshake: Record<string, unknown> = {}): any {
  return {
    handshake,
    data: {},
    join: jest.fn().mockResolvedValue(undefined),
    leave: jest.fn().mockResolvedValue(undefined),
    disconnect: jest.fn(),
  };
}

function makeProgress(overrides: Partial<GrabProgressResponse> = {}): GrabProgressResponse {
  return {
    requestId: 'gr-1',
    sessionId: 101,
    status: 'QUEUED',
    orderId: null,
    failReason: null,
    queueSeq: 5,
    queueRank: 3,
    estimatedWaitSeconds: 10,
    currentTicketTypeId: 202,
    currentAttemptIndex: 0,
    requestedTicketTypes: [],
    allowAutoDowngrade: false,
    attempts: [],
    visibleStock: null,
    fairnessNotes: [],
    message: null,
    matchedTicketTypeId: null,
    updateTime: new Date('2026-01-01T00:00:00Z').toISOString(),
    ...overrides,
  } as GrabProgressResponse;
}

function makeGateway(grabService: GrabService): { gateway: GrabProgressGateway; server: any } {
  const gateway = new GrabProgressGateway(grabService);
  const to = jest.fn().mockReturnValue({ emit: jest.fn() });
  const server = { to };
  (gateway as any).server = server;
  return { gateway, server };
}

describe('GrabProgressGateway', () => {
  const originalJwtSecret = process.env.JWT_SECRET;

  beforeEach(() => {
    process.env.JWT_SECRET = '12345678901234567890123456789012';
  });

  afterAll(() => {
    if (originalJwtSecret == null) {
      delete process.env.JWT_SECRET;
    } else {
      process.env.JWT_SECRET = originalJwtSecret;
    }
  });

  describe('handleConnection', () => {
    it('accepts a valid auth token and records userId', async () => {
      const token = jwt.sign({ userId: 2004 }, process.env.JWT_SECRET!, { subject: '2004' });
      const client = makeClient({ auth: { token } });
      const { gateway } = makeGateway({} as GrabService);

      await gateway.handleConnection(client);

      expect(client.data.userId).toBe(2004);
      expect(client.disconnect).not.toHaveBeenCalled();
    });

    it('accepts a bearer token from handshake headers', async () => {
      const token = jwt.sign({ userId: 2005 }, process.env.JWT_SECRET!, { subject: '2005' });
      const client = makeClient({ headers: { authorization: `Bearer ${token}` } });
      const { gateway } = makeGateway({} as GrabService);

      await gateway.handleConnection(client);

      expect(client.data.userId).toBe(2005);
      expect(client.disconnect).not.toHaveBeenCalled();
    });

    it('disconnects clients without a token', async () => {
      const client = makeClient({});
      const { gateway } = makeGateway({} as GrabService);

      await gateway.handleConnection(client);

      expect(client.disconnect).toHaveBeenCalledWith(true);
    });

    it('disconnects clients with an invalid token', async () => {
      const client = makeClient({ auth: { token: 'not-a-jwt' } });
      const { gateway } = makeGateway({} as GrabService);

      await gateway.handleConnection(client);

      expect(client.disconnect).toHaveBeenCalledWith(true);
    });
  });

  describe('subscribe', () => {
    it('returns an error event for a missing requestId', async () => {
      const client = makeClient();
      client.data.userId = 2004;
      const { gateway } = makeGateway({} as GrabService);

      const result = await gateway.subscribe(client, {});

      expect(result.event).toBe('error');
      expect(client.join).not.toHaveBeenCalled();
    });

    it('returns an error event when the request cannot be accessed', async () => {
      const grabService = { getProgress: jest.fn().mockRejectedValue(new Error('forbidden')) } as unknown as GrabService;
      const client = makeClient();
      client.data.userId = 9999;
      const { gateway } = makeGateway(grabService);

      const result = await gateway.subscribe(client, { requestId: 'gr-1' });

      expect(result.event).toBe('error');
      expect(client.join).not.toHaveBeenCalled();
    });

    it('joins request and session rooms and returns the snapshot', async () => {
      const progress = makeProgress();
      const grabService = { getProgress: jest.fn().mockResolvedValue(progress) } as unknown as GrabService;
      const client = makeClient();
      client.data.userId = 2004;
      const { gateway } = makeGateway(grabService);

      const result = await gateway.subscribe(client, { requestId: 'gr-1' });

      expect(grabService.getProgress).toHaveBeenCalledWith(2004, 'gr-1');
      expect(result.event).toBe('subscribed');
      expect(result.data).toBe(progress);
      expect(client.join).toHaveBeenNthCalledWith(1, 'grab-request:gr-1');
      expect(client.join).toHaveBeenNthCalledWith(2, 'grab-session:101');
    });
  });

  describe('unsubscribe', () => {
    it('leaves the request room', async () => {
      const client = makeClient();
      const { gateway } = makeGateway({} as GrabService);

      const result = await gateway.unsubscribe(client, { requestId: 'gr-1' });

      expect(result.event).toBe('unsubscribed');
      expect(client.leave).toHaveBeenCalledWith('grab-request:gr-1');
    });
  });

  describe('publishProgress', () => {
    it('emits the snapshot to the request room', async () => {
      const progress = makeProgress();
      const grabService = {
        getProgressForVerifiedRequest: jest.fn().mockResolvedValue(progress),
      } as unknown as GrabService;
      const { gateway, server } = makeGateway(grabService);

      await gateway.publishProgress('gr-1');

      expect(server.to).toHaveBeenCalledWith('grab-request:gr-1');
      expect(server.to('grab-request:gr-1').emit).toHaveBeenCalledWith('progress', progress);
    });

    it('does not throw when the request is missing', async () => {
      const grabService = {
        getProgressForVerifiedRequest: jest.fn().mockRejectedValue(new Error('not found')),
      } as unknown as GrabService;
      const { gateway, server } = makeGateway(grabService);

      await expect(gateway.publishProgress('gr-missing')).resolves.toBeUndefined();
      expect(server.to).not.toHaveBeenCalled();
    });
  });

  describe('publishQueueAdvanced', () => {
    it('emits a queue-advanced event to the session room', () => {
      const { gateway, server } = makeGateway({} as GrabService);

      gateway.publishQueueAdvanced(101);

      expect(server.to).toHaveBeenCalledWith('grab-session:101');
      expect(server.to('grab-session:101').emit).toHaveBeenCalledWith('queue-advanced', { sessionId: 101 });
    });

    it('ignores invalid session ids', () => {
      const { gateway, server } = makeGateway({} as GrabService);

      gateway.publishQueueAdvanced(Number.NaN);

      expect(server.to).not.toHaveBeenCalled();
    });
  });
});
