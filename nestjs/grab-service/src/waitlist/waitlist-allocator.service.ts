import { Injectable } from '@nestjs/common';
import { createHash } from 'crypto';
import { OrderClientService } from '../grab/order-client.service';
import { WaitlistNotificationService } from './waitlist-notification.service';
import { WaitlistRepository } from './waitlist.repository';
import { TicketReleasedEventDto, WaitlistEntryRecord } from './waitlist.types';

@Injectable()
export class WaitlistAllocatorService {
  constructor(
    private readonly repository: WaitlistRepository,
    private readonly orderClient: OrderClientService,
    private readonly notifications: WaitlistNotificationService,
  ) {}

  async allocate(event: TicketReleasedEventDto): Promise<{ status: string; entryId?: number; orderId?: number }> {
    this.validateEvent(event);
    const started = await this.repository.beginAllocationEvent(event.eventKey, event.sessionId, event.ticketTypeId, event.quantity, event.sourceOrderId ?? null);
    if (!started) return { status: 'DUPLICATE' };

    if (event.sourceOrderId) {
      const expired = await this.repository.markOfferExpiredByOrder(event.sourceOrderId);
      if (expired) {
        await this.notifications.notifyExpired({
          userId: expired.userId,
          orderId: expired.orderId,
          content: '候补付款已过期，名额已释放。',
        });
      }
    }

    for (let attemptNo = 1; attemptNo <= 10; attemptNo++) {
      const entry = await this.repository.claimNextEntry({
        sessionId: event.sessionId,
        ticketTypeId: event.ticketTypeId,
        releasedQuantity: event.quantity,
      });
      if (!entry) {
        await this.repository.logAllocationAttempt({
          eventKey: event.eventKey,
          attemptNo,
          sessionId: event.sessionId,
          ticketTypeId: event.ticketTypeId,
          releasedQuantity: event.quantity,
          entryId: null,
          orderId: null,
          sourceOrderId: event.sourceOrderId ?? null,
          status: 'NO_MATCH',
          message: '没有符合条件的候补记录',
        });
        return { status: 'NO_MATCH' };
      }

      const grabRequestId = this.waitlistGrabRequestId(entry, event.eventKey);
      try {
        const existing = await this.orderClient.findByGrabRequestId(grabRequestId);
        const order = existing ?? await this.orderClient.createWaitlistOfferOrder({
          userId: entry.userId,
          sessionId: entry.sessionId,
          ticketTypeId: entry.ticketTypeId,
          quantity: entry.quantity,
          attendeeIds: entry.attendeeIds,
          grabRequestId,
        });
        const expireTime = new Date(Date.now() + 15 * 60 * 1000);
        await this.repository.markEntryOffered(entry.id, order.id, expireTime);
        await this.repository.createOffer({ entry, orderId: order.id, expireTime });
        await this.repository.logAllocationAttempt({
          eventKey: event.eventKey,
          attemptNo,
          sessionId: event.sessionId,
          ticketTypeId: event.ticketTypeId,
          releasedQuantity: event.quantity,
          entryId: entry.id,
          orderId: order.id,
          sourceOrderId: event.sourceOrderId ?? null,
          status: 'OFFERED',
          message: '已生成候补待支付订单',
        });
        await this.notifications.notifyOffered({
          userId: entry.userId,
          orderId: order.id,
          content: `候补成功，请在 ${expireTime.toLocaleString()} 前完成支付。`,
        });
        return { status: 'OFFERED', entryId: entry.id, orderId: order.id };
      } catch (error) {
        const message = (error as Error).message || '候补订单创建失败';
        if (this.isBusinessFailure(message)) {
          await this.repository.markEntryFailed(entry.id, message);
          await this.repository.logAllocationAttempt({
            eventKey: event.eventKey,
            attemptNo,
            sessionId: event.sessionId,
            ticketTypeId: event.ticketTypeId,
            releasedQuantity: event.quantity,
            entryId: entry.id,
            orderId: null,
            sourceOrderId: event.sourceOrderId ?? null,
            status: 'FAILED',
            message,
          });
          continue;
        }
        await this.repository.restoreAllocatingEntry(entry.id, message);
        await this.repository.logAllocationAttempt({
          eventKey: event.eventKey,
          attemptNo,
          sessionId: event.sessionId,
          ticketTypeId: event.ticketTypeId,
          releasedQuantity: event.quantity,
          entryId: entry.id,
          orderId: null,
          sourceOrderId: event.sourceOrderId ?? null,
          status: 'FAILED',
          message,
        });
        await this.repository.markAllocationEventFailed(event.eventKey, message);
        throw error instanceof Error ? error : new Error(message);
      }
    }

    return { status: 'NO_MATCH' };
  }

  async markPaidByOrder(orderId: number): Promise<{ status: string }> {
    const offer = await this.repository.markOfferPaidByOrder(orderId);
    if (offer) {
      await this.notifications.notifyPaid({
        userId: offer.userId,
        orderId: offer.orderId,
        content: '候补订单已支付成功。',
      });
    }
    return { status: 'PAID' };
  }

  async scanExpiredOffers(): Promise<{ scanned: number }> {
    const offers = await this.repository.findExpiredOffers(new Date(), 50);
    for (const offer of offers) {
      await this.repository.markOfferExpiredByOrder(offer.orderId);
      await this.notifications.notifyExpired({
        userId: offer.userId,
        orderId: offer.orderId,
        content: '候补付款已过期，名额已释放。',
      });
    }
    return { scanned: offers.length };
  }

  private validateEvent(event: TicketReleasedEventDto): void {
    if (!event?.eventKey || !event.sessionId || !event.ticketTypeId || !event.quantity || event.quantity <= 0) {
      throw new Error('候补释放事件无效');
    }
  }

  private waitlistGrabRequestId(entry: Pick<WaitlistEntryRecord, 'id'>, eventKey: string): string {
    const hash = createHash('sha256').update(eventKey).digest('hex').slice(0, 16);
    return `WAITLIST-${entry.id}-${hash}`;
  }

  private isBusinessFailure(message: string): boolean {
    return /限购|已购买|重复|库存不足|不可售|不存在|already|duplicate|sold out|not enough/i.test(message);
  }
}
