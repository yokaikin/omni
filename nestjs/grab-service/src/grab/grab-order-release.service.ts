import { Injectable } from '@nestjs/common';
import { GrabAdmissionService } from './grab-admission.service';
import { GrabRepository } from './grab.repository';
import { GRAB_STATUS } from './grab-status';
import type { GrabRequestRecord } from './grab.types';
import { GrabStockService } from './grab-stock.service';

export interface GrabOrderReleaseEvent {
  sourceOrderId?: number | null;
  sessionId: number;
  ticketTypeId: number;
  quantity: number;
}

@Injectable()
export class GrabOrderReleaseService {
  constructor(
    private readonly repository: GrabRepository,
    private readonly admissionService: GrabAdmissionService,
    private readonly stockService: GrabStockService,
  ) {}

  async releaseForOrder(event: GrabOrderReleaseEvent): Promise<void> {
    if (!event.sourceOrderId) return;

    const requests = await this.repository.findByOrderId(event.sourceOrderId);
    for (const request of requests) {
      if (!this.matchesEvent(request, event)) continue;
      await this.admissionService.release({
        requestId: request.requestId,
        userId: request.userId,
        sessionId: request.sessionId,
        ticketTypeId: request.currentTicketTypeId ?? request.ticketTypeId,
        quantity: request.quantity,
        seatIds: request.seatIds,
        idempotencyKey: request.idempotencyKey,
        restoreStock: true,
      });
    }

    await this.stockService.syncFromTicketType(event.sessionId, event.ticketTypeId);
  }

  private matchesEvent(request: GrabRequestRecord, event: GrabOrderReleaseEvent): boolean {
    return request.orderId === event.sourceOrderId
      && request.sessionId === event.sessionId
      && (request.currentTicketTypeId ?? request.ticketTypeId) === event.ticketTypeId
      && request.progressStatus === GRAB_STATUS.ORDER_CREATED;
  }
}
