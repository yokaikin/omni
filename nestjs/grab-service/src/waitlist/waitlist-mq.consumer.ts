import { Injectable, OnModuleInit, OnModuleDestroy, Logger } from '@nestjs/common';
import * as amqp from 'amqp-connection-manager';
import { ConfirmChannel } from 'amqplib';
import { requireEnv, requireIntegerEnv } from '../runtime-env';
import { WaitlistAllocatorService } from './waitlist-allocator.service';
import { TicketReleasedEventDto } from './waitlist.types';
import { GrabOrderReleaseService } from '../grab/grab-order-release.service';

const WAITLIST_EXCHANGE = 'omni.waitlist';
const WAITLIST_RETRY_EXCHANGE = 'omni.waitlist.retry';
const WAITLIST_DLX = 'omni.waitlist.dlx';
const RK_WAITLIST_RELEASED = 'waitlist.released';
const RK_WAITLIST_RELEASED_RETRY = 'waitlist.released.retry';
const RK_WAITLIST_RELEASED_DLQ = 'waitlist.released.dlq';
const RK_WAITLIST_ORDER_PAID = 'waitlist.order-paid';
const RK_WAITLIST_ORDER_PAID_RETRY = 'waitlist.order-paid.retry';
const RK_WAITLIST_ORDER_PAID_DLQ = 'waitlist.order-paid.dlq';
const Q_WAITLIST_RELEASED = 'waitlist.released.queue';
const Q_WAITLIST_RELEASED_RETRY = 'waitlist.released.retry.queue';
const Q_WAITLIST_RELEASED_DLQ = 'waitlist.released.dlq';
const Q_WAITLIST_ORDER_PAID = 'waitlist.order-paid.queue';
const Q_WAITLIST_ORDER_PAID_RETRY = 'waitlist.order-paid.retry.queue';
const Q_WAITLIST_ORDER_PAID_DLQ = 'waitlist.order-paid.dlq';
const RETRY_TTL_MILLIS = 10_000;
const MAX_RETRY_COUNT = 3;

class InvalidMqMessageError extends Error {}

@Injectable()
export class WaitlistMqConsumer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(WaitlistMqConsumer.name);
  private connection: amqp.AmqpConnectionManager;
  private channelWrapper: amqp.ChannelWrapper;

  constructor(
    private readonly allocator: WaitlistAllocatorService,
    private readonly grabOrderReleaseService?: GrabOrderReleaseService,
  ) {}

  async onModuleInit() {
    const host = requireEnv('RABBITMQ_HOST', 'RabbitMQ 地址未配置');
    const port = requireIntegerEnv('RABBITMQ_PORT', 'RabbitMQ 端口未配置', 'RabbitMQ 端口配置无效');
    const user = requireEnv('RABBITMQ_USER', 'RabbitMQ 用户未配置');
    const pass = requireEnv('RABBITMQ_PASSWORD', 'RabbitMQ 密码未配置');
    const url = `amqp://${user}:${pass}@${host}:${port}`;

    this.connection = amqp.connect([url]);
    this.connection.on('connect', () => this.logger.log('Connected to RabbitMQ'));
    this.connection.on('disconnect', err => this.logger.error('Disconnected from RabbitMQ', err));

    this.channelWrapper = this.connection.createChannel({
      setup: async (channel: ConfirmChannel) => {
        await this.assertTopology(channel);

        await channel.consume(Q_WAITLIST_RELEASED, async (msg) => {
          if (msg) await this.handleReleasedMessage(channel, msg);
        });

        await channel.consume(Q_WAITLIST_ORDER_PAID, async (msg) => {
          if (msg) await this.handleOrderPaidMessage(channel, msg);
        });
      }
    });
  }

  async handleReleasedMessage(channel: ConfirmChannel, msg: any): Promise<void> {
    try {
      const event: TicketReleasedEventDto = JSON.parse(msg.content.toString());
      this.assertReleasedPayload(event);
      this.logger.log(`收到候补释放消息: eventKey=${event.eventKey}`);
      await this.grabOrderReleaseService?.releaseForOrder(event);
      await this.allocator.allocate(event);
      channel.ack(msg);
    } catch (err) {
      await this.handleProcessingError(channel, msg, {
        retryQueue: Q_WAITLIST_RELEASED_RETRY,
        retryRoutingKey: RK_WAITLIST_RELEASED_RETRY,
        dlqRoutingKey: RK_WAITLIST_RELEASED_DLQ,
        error: err,
      });
    }
  }

  async handleOrderPaidMessage(channel: ConfirmChannel, msg: any): Promise<void> {
    try {
      const payload = JSON.parse(msg.content.toString());
      if (!payload || typeof payload !== 'object' || !this.isPositiveNumber(payload.orderId)) {
        throw new InvalidMqMessageError('候补支付消息缺少 orderId');
      }
      const orderId = Number(payload.orderId);
      this.logger.log(`收到候补支付消息: orderId=${orderId}`);
      await this.allocator.markPaidByOrder(orderId);
      channel.ack(msg);
    } catch (err) {
      await this.handleProcessingError(channel, msg, {
        retryQueue: Q_WAITLIST_ORDER_PAID_RETRY,
        retryRoutingKey: RK_WAITLIST_ORDER_PAID_RETRY,
        dlqRoutingKey: RK_WAITLIST_ORDER_PAID_DLQ,
        error: err,
      });
    }
  }

  private async assertTopology(channel: ConfirmChannel): Promise<void> {
    await channel.assertExchange(WAITLIST_EXCHANGE, 'topic', { durable: true });
    await channel.assertExchange(WAITLIST_RETRY_EXCHANGE, 'topic', { durable: true });
    await channel.assertExchange(WAITLIST_DLX, 'topic', { durable: true });

    await channel.assertQueue(Q_WAITLIST_RELEASED, {
      durable: true,
      deadLetterExchange: WAITLIST_RETRY_EXCHANGE,
      deadLetterRoutingKey: RK_WAITLIST_RELEASED_RETRY,
    });
    await channel.bindQueue(Q_WAITLIST_RELEASED, WAITLIST_EXCHANGE, RK_WAITLIST_RELEASED);
    await channel.assertQueue(Q_WAITLIST_RELEASED_RETRY, {
      durable: true,
      messageTtl: RETRY_TTL_MILLIS,
      deadLetterExchange: WAITLIST_EXCHANGE,
      deadLetterRoutingKey: RK_WAITLIST_RELEASED,
    });
    await channel.bindQueue(Q_WAITLIST_RELEASED_RETRY, WAITLIST_RETRY_EXCHANGE, RK_WAITLIST_RELEASED_RETRY);
    await channel.assertQueue(Q_WAITLIST_RELEASED_DLQ, { durable: true });
    await channel.bindQueue(Q_WAITLIST_RELEASED_DLQ, WAITLIST_DLX, RK_WAITLIST_RELEASED_DLQ);

    await channel.assertQueue(Q_WAITLIST_ORDER_PAID, {
      durable: true,
      deadLetterExchange: WAITLIST_RETRY_EXCHANGE,
      deadLetterRoutingKey: RK_WAITLIST_ORDER_PAID_RETRY,
    });
    await channel.bindQueue(Q_WAITLIST_ORDER_PAID, WAITLIST_EXCHANGE, RK_WAITLIST_ORDER_PAID);
    await channel.assertQueue(Q_WAITLIST_ORDER_PAID_RETRY, {
      durable: true,
      messageTtl: RETRY_TTL_MILLIS,
      deadLetterExchange: WAITLIST_EXCHANGE,
      deadLetterRoutingKey: RK_WAITLIST_ORDER_PAID,
    });
    await channel.bindQueue(Q_WAITLIST_ORDER_PAID_RETRY, WAITLIST_RETRY_EXCHANGE, RK_WAITLIST_ORDER_PAID_RETRY);
    await channel.assertQueue(Q_WAITLIST_ORDER_PAID_DLQ, { durable: true });
    await channel.bindQueue(Q_WAITLIST_ORDER_PAID_DLQ, WAITLIST_DLX, RK_WAITLIST_ORDER_PAID_DLQ);
  }

  private async handleProcessingError(
    channel: ConfirmChannel,
    msg: any,
    options: { retryQueue: string; retryRoutingKey: string; dlqRoutingKey: string; error: unknown },
  ): Promise<void> {
    const error = options.error instanceof Error ? options.error : new Error(String(options.error));
    if (this.isInvalidMessageError(error) || this.retryCount(msg, options.retryQueue) >= MAX_RETRY_COUNT) {
      this.logger.error(`候补 MQ 消息进入死信队列: routingKey=${options.dlqRoutingKey}, message=${error.message}`);
      channel.publish(WAITLIST_DLX, options.dlqRoutingKey, msg.content, {
        persistent: true,
        contentType: msg.properties?.contentType || 'application/json',
        headers: {
          ...(msg.properties?.headers || {}),
          failedAt: new Date().toISOString(),
          failureMessage: error.message,
        },
      });
      channel.ack(msg);
      return;
    }

    this.logger.warn(`候补 MQ 消息处理失败，进入重试队列: routingKey=${options.retryRoutingKey}, message=${error.message}`);
    channel.reject(msg, false);
  }

  private retryCount(msg: any, retryQueue: string): number {
    const deaths = msg.properties?.headers?.['x-death'];
    if (!Array.isArray(deaths)) return 0;
    const death = deaths.find(item => item?.queue === retryQueue);
    return Number(death?.count || 0);
  }

  private assertReleasedPayload(event: TicketReleasedEventDto): void {
    if (
      !event ||
      typeof event !== 'object' ||
      !event.eventKey ||
      !['ORDER_TIMEOUT', 'ORDER_CANCEL', 'REFUND', 'MANUAL'].includes(event.source) ||
      !this.isPositiveNumber(event.sessionId) ||
      !this.isPositiveNumber(event.ticketTypeId) ||
      !this.isPositiveNumber(event.quantity)
    ) {
      throw new InvalidMqMessageError('候补释放消息缺少必要字段');
    }
  }

  private isPositiveNumber(value: unknown): boolean {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) && numberValue > 0;
  }

  private isInvalidMessageError(error: Error): boolean {
    return error instanceof SyntaxError || error instanceof InvalidMqMessageError;
  }

  async onModuleDestroy() {
    if (this.channelWrapper) {
      await this.channelWrapper.close();
    }
    if (this.connection) {
      await this.connection.close();
    }
  }
}
