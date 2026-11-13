import { WaitlistMqConsumer } from './waitlist-mq.consumer';
import * as amqp from 'amqp-connection-manager';

jest.mock('amqp-connection-manager', () => ({
  connect: jest.fn(),
}));

function message(content: unknown, headers: Record<string, unknown> = {}) {
  return {
    content: Buffer.from(typeof content === 'string' ? content : JSON.stringify(content)),
    properties: { headers },
  } as any;
}

describe('WaitlistMqConsumer', () => {
  const originalEnv = process.env;
  const amqpConnect = amqp.connect as jest.Mock;

  beforeEach(() => {
    process.env = {
      ...originalEnv,
      RABBITMQ_HOST: 'rabbit.local',
      RABBITMQ_PORT: '5672',
      RABBITMQ_USER: 'grab',
      RABBITMQ_PASSWORD: 'grab-password',
    };
    amqpConnect.mockReset();
    amqpConnect.mockReturnValue({
      on: jest.fn(),
      createChannel: jest.fn().mockReturnValue({ close: jest.fn() }),
      close: jest.fn(),
    });
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  it('fails before connecting when RabbitMQ environment is missing', async () => {
    delete process.env.RABBITMQ_HOST;
    const allocator = { allocate: jest.fn(), markPaidByOrder: jest.fn() };
    const consumer = new WaitlistMqConsumer(allocator as any);

    await expect(consumer.onModuleInit()).rejects.toThrow('RabbitMQ 地址未配置');
    expect(amqpConnect).not.toHaveBeenCalled();
  });

  it('rejects a transient waitlist release failure into the retry topology', async () => {
    const allocator = { allocate: jest.fn().mockRejectedValue(new Error('temporary database outage')) };
    const consumer = new WaitlistMqConsumer(allocator as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const msg = message({ eventKey: 'release-1', source: 'ORDER_TIMEOUT', sessionId: 101, ticketTypeId: 202, quantity: 1 });

    await (consumer as any).handleReleasedMessage(channel, msg);

    expect(channel.reject).toHaveBeenCalledWith(msg, false);
    expect(channel.publish).not.toHaveBeenCalled();
    expect(channel.ack).not.toHaveBeenCalled();
  });

  it('processes an order cancellation release before waitlist allocation', async () => {
    const allocator = { allocate: jest.fn().mockResolvedValue({ status: 'NO_ENTRY' }), markPaidByOrder: jest.fn() };
    const grabRelease = { releaseForOrder: jest.fn().mockResolvedValue(undefined) };
    const consumer = new WaitlistMqConsumer(allocator as any, grabRelease as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const event = { eventKey: 'order-cancel:9001:session:101:ticket-type:202', source: 'ORDER_CANCEL', sourceOrderId: 9001, sessionId: 101, ticketTypeId: 202, quantity: 1 };

    await (consumer as any).handleReleasedMessage(channel, message(event));

    expect(grabRelease.releaseForOrder).toHaveBeenCalledWith(event);
    expect(allocator.allocate).toHaveBeenCalledWith(event);
    expect(channel.ack).toHaveBeenCalled();
  });

  it('moves unknown release sources to the dead-letter queue', async () => {
    const allocator = { allocate: jest.fn() };
    const consumer = new WaitlistMqConsumer(allocator as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const msg = message({ eventKey: 'release-1', source: 'UNKNOWN', sessionId: 101, ticketTypeId: 202, quantity: 1 });

    await (consumer as any).handleReleasedMessage(channel, msg);

    expect(allocator.allocate).not.toHaveBeenCalled();
    expect(channel.publish).toHaveBeenCalledWith(
      'omni.waitlist.dlx',
      'waitlist.released.dlq',
      msg.content,
      expect.objectContaining({ persistent: true }),
    );
  });

  it('moves a waitlist release message to dlq after retry limit is reached', async () => {
    const allocator = { allocate: jest.fn().mockRejectedValue(new Error('still failing')) };
    const consumer = new WaitlistMqConsumer(allocator as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const msg = message(
      { eventKey: 'release-1', source: 'ORDER_TIMEOUT', sessionId: 101, ticketTypeId: 202, quantity: 1 },
      { 'x-death': [{ queue: 'waitlist.released.retry.queue', count: 3 }] },
    );

    await (consumer as any).handleReleasedMessage(channel, msg);

    expect(channel.publish).toHaveBeenCalledWith(
      'omni.waitlist.dlx',
      'waitlist.released.dlq',
      msg.content,
      expect.objectContaining({ persistent: true }),
    );
    expect(channel.ack).toHaveBeenCalledWith(msg);
    expect(channel.reject).not.toHaveBeenCalled();
  });

  it('moves invalid order-paid payloads to dlq without retrying', async () => {
    const allocator = { markPaidByOrder: jest.fn() };
    const consumer = new WaitlistMqConsumer(allocator as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const msg = message('{bad json');

    await (consumer as any).handleOrderPaidMessage(channel, msg);

    expect(channel.publish).toHaveBeenCalledWith(
      'omni.waitlist.dlx',
      'waitlist.order-paid.dlq',
      msg.content,
      expect.objectContaining({ persistent: true }),
    );
    expect(channel.ack).toHaveBeenCalledWith(msg);
    expect(channel.reject).not.toHaveBeenCalled();
  });

  it('moves incomplete order-paid payloads to dlq without retrying', async () => {
    const allocator = { markPaidByOrder: jest.fn() };
    const consumer = new WaitlistMqConsumer(allocator as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const msg = message({});

    await (consumer as any).handleOrderPaidMessage(channel, msg);

    expect(allocator.markPaidByOrder).not.toHaveBeenCalled();
    expect(channel.publish).toHaveBeenCalledWith(
      'omni.waitlist.dlx',
      'waitlist.order-paid.dlq',
      msg.content,
      expect.objectContaining({ persistent: true }),
    );
    expect(channel.ack).toHaveBeenCalledWith(msg);
    expect(channel.reject).not.toHaveBeenCalled();
  });

  it('moves incomplete waitlist release payloads to dlq without retrying', async () => {
    const allocator = { allocate: jest.fn() };
    const consumer = new WaitlistMqConsumer(allocator as any);
    const channel = { ack: jest.fn(), reject: jest.fn(), publish: jest.fn() };
    const msg = message({ eventKey: 'release-1', source: 'ORDER_TIMEOUT', sessionId: 101, quantity: 1 });

    await (consumer as any).handleReleasedMessage(channel, msg);

    expect(allocator.allocate).not.toHaveBeenCalled();
    expect(channel.publish).toHaveBeenCalledWith(
      'omni.waitlist.dlx',
      'waitlist.released.dlq',
      msg.content,
      expect.objectContaining({ persistent: true }),
    );
    expect(channel.ack).toHaveBeenCalledWith(msg);
    expect(channel.reject).not.toHaveBeenCalled();
  });
});
