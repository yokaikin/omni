import { Injectable, Logger, OnModuleDestroy, OnModuleInit, Optional } from '@nestjs/common';
import { randomUUID } from 'crypto';
import { GrabAdmissionService } from './grab-admission.service';
import { GrabQueueService } from './grab-queue.service';
import { GrabRepository } from './grab.repository';
import { GrabStockService } from './grab-stock.service';
import { GRAB_STATUS, isTerminalGrabStatus } from './grab-status';
import type { GrabAttemptSnapshot, GrabRequestRecord, GrabTicketPreference } from './grab.types';
import { OrderClientService } from './order-client.service';

type AttemptOutcome = 'ORDER_CREATED' | 'SOLD_OUT' | 'LIMITED' | 'FAILED' | 'CANCELLED' | 'PENDING_RECOVERY' | 'STALE_LEASE';
type IdempotentAdmissionOutcome = AttemptOutcome | 'CONTINUE';
type OrderLookupRecovery = 'RECOVERED' | 'MISSING' | 'UNKNOWN';
type LatencyPhase = 'claim' | 'lock' | 'order' | 'confirm' | 'ack';

class GrabWorkerLatencyTrace {
  private readonly startedAt = Date.now();
  private readonly phases: Record<LatencyPhase, number> = {
    claim: 0,
    lock: 0,
    order: 0,
    confirm: 0,
    ack: 0,
  };
  private queueWaitMillis = 0;

  setRecord(record: Pick<GrabRequestRecord, 'createdAt'>): void {
    const createdAt = record.createdAt instanceof Date ? record.createdAt.getTime() : new Date(record.createdAt).getTime();
    this.queueWaitMillis = Number.isFinite(createdAt) ? Math.max(0, Date.now() - createdAt) : 0;
  }

  async measure<T>(phase: LatencyPhase, action: () => Promise<T>): Promise<T> {
    const startedAt = Date.now();
    try {
      return await action();
    } finally {
      this.phases[phase] += Math.max(0, Date.now() - startedAt);
    }
  }

  format(record: Pick<GrabRequestRecord, 'requestId' | 'sessionId' | 'ticketTypeId' | 'queueSeq'>, outcome: string): string {
    return [
      '抢票链路耗时:',
      `requestId=${record.requestId}`,
      `sessionId=${record.sessionId}`,
      `ticketTypeId=${record.ticketTypeId}`,
      `queueSeq=${record.queueSeq ?? ''}`,
      `outcome=${outcome}`,
      `queueWaitMs=${this.queueWaitMillis}`,
      `claimMs=${this.phases.claim}`,
      `lockMs=${this.phases.lock}`,
      `orderMs=${this.phases.order}`,
      `confirmMs=${this.phases.confirm}`,
      `ackMs=${this.phases.ack}`,
      `totalMs=${Math.max(0, Date.now() - this.startedAt)}`,
    ].join(' ');
  }
}

@Injectable()
export class GrabWorkerService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(GrabWorkerService.name);
  private readonly requestTtlSeconds = 900;
  private readonly workerId = `grab-worker-${randomUUID()}`;
  private timer: NodeJS.Timeout | null = null;
  private pollInProgress = false;

  constructor(
    private readonly repository: GrabRepository,
    private readonly admissionService: GrabAdmissionService,
    private readonly orderClient: OrderClientService,
    private readonly queueService: GrabQueueService,
    @Optional() private readonly stockService?: GrabStockService,
  ) {}

  onModuleInit(): void {
    this.timer = setInterval(() => {
      void this.pollOnce().catch((error) => this.logger.error(error));
    }, 500);
  }

  onModuleDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async pollOnce(): Promise<void> {
    if (this.pollInProgress) return;
    this.pollInProgress = true;
    try {
      const sessionIds = await this.queueService.getActiveSessions();
      for (const sessionId of sessionIds) {
        const requestId = await this.queueService.dequeue(sessionId);
        if (requestId) {
          await this.processRequest(requestId, sessionId);
        }
        await this.queueService.removeActiveSessionIfQueueEmpty(sessionId);
      }
    } finally {
      this.pollInProgress = false;
    }
  }

  async processRequest(requestId: string, sessionId?: number): Promise<void> {
    const latencyTrace = new GrabWorkerLatencyTrace();
    const existing = await this.repository.findByRequestId(requestId);
    if (!existing) {
      if (sessionId != null) await this.queueService.ackOrphanInflight(sessionId, requestId);
      return;
    }

    const record = await latencyTrace.measure('claim', () => this.repository.claimForProcessing(requestId, this.workerId));
    if (!record) {
      if (existing.expireTime <= new Date()) {
        await this.repository.expireActiveRequest(requestId, '抢票请求处理前已过期', [existing.progressStatus]);
        await this.ackIfQueued(existing);
        return;
      }
      if (isTerminalGrabStatus(existing.progressStatus) || existing.orderId) {
        await this.ackIfQueued(existing);
        return;
      }
      return;
    }
    latencyTrace.setRecord(record);

    let shouldAck = true;
    let outcome: string = 'FAILED';
    try {
      if (record.requestType === 'TEAM_GRAB') {
        await this.repository.updateStatus(record.requestId, GRAB_STATUS.FAILED, '小队抢票功能已下线，请重新选择实名观演人并排队购票');
        outcome = 'FAILED';
      } else {
        outcome = await this.processAttempts(record, latencyTrace);
        shouldAck = outcome !== 'STALE_LEASE';
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '抢票处理失败';
      await this.repository.updateStatus(record.requestId, GRAB_STATUS.FAILED, message);
      outcome = 'FAILED';
      this.logger.error(error);
    } finally {
      if (shouldAck) await latencyTrace.measure('ack', () => this.ackIfQueued(record));
      this.logger.log(latencyTrace.format(record, outcome));
    }
  }

  private async processAttempts(record: GrabRequestRecord, latencyTrace: GrabWorkerLatencyTrace): Promise<AttemptOutcome> {
    const preferences = this.preferencesFor(record);
    const effectivePreferences = preferences.length ? preferences : [{
      ticketTypeId: record.ticketTypeId,
      name: null,
      maxPrice: null,
    }];
    let attempts = this.initialAttempts(effectivePreferences);

    for (let index = 0; index < effectivePreferences.length; index += 1) {
      const preference = effectivePreferences[index];
      if (index > 0) {
        const previous = effectivePreferences[index - 1];
        const downgraded = await this.repository.updateProgress(record.requestId, {
          status: GRAB_STATUS.DOWNGRADING,
          message: `${this.ticketLabel(previous)}已售罄，正在尝试${this.ticketLabel(preference)}`,
          currentTicketTypeId: preference.ticketTypeId,
          currentAttemptIndex: index,
          attempts,
          workerId: this.workerId,
        });
        if (!downgraded) return await this.isStillLeaseOwner(record.requestId) ? 'CANCELLED' : 'STALE_LEASE';
      }

      const outcome = await this.tryTicketType(record, preference, index, attempts, latencyTrace);
      if (outcome === 'STALE_LEASE') return 'STALE_LEASE';
      if (outcome !== 'SOLD_OUT') return outcome;

      attempts = this.markAttempt(attempts, index, 'SOLD_OUT', '当前票档已售罄');
      if (index === effectivePreferences.length - 1) {
        return await this.finishTerminalAttempt(record, GRAB_STATUS.SOLD_OUT, '当前票档已售罄', preference, index, attempts, 'SOLD_OUT')
          ? 'SOLD_OUT'
          : 'STALE_LEASE';
      }
    }
    return 'FAILED';
  }

  private async tryTicketType(
    record: GrabRequestRecord,
    preference: GrabTicketPreference,
    index: number,
    attempts: GrabAttemptSnapshot[],
    latencyTrace: GrabWorkerLatencyTrace,
  ): Promise<AttemptOutcome> {
    const tryingAttempts = this.markAttempt(attempts, index, 'TRYING', `正在尝试${this.ticketLabel(preference)}`);
    const tryingRecord = await this.repository.updateProgress(record.requestId, {
      status: GRAB_STATUS.TRYING_TICKET_TYPE,
      message: `正在尝试${this.ticketLabel(preference)}`,
      currentTicketTypeId: preference.ticketTypeId,
      currentAttemptIndex: index,
      attempts: tryingAttempts,
      workerId: this.workerId,
    });
    if (!tryingRecord) return await this.missingProgressOutcome(record.requestId);

    const lockingAttempts = this.markAttempt(attempts, index, 'LOCKING', `正在锁定${this.ticketLabel(preference)}`);
    const lockingRecord = await this.repository.updateProgress(record.requestId, {
      status: GRAB_STATUS.LOCKING,
      message: `正在锁定${this.ticketLabel(preference)}`,
      currentTicketTypeId: preference.ticketTypeId,
      currentAttemptIndex: index,
      attempts: lockingAttempts,
      workerId: this.workerId,
    });
    if (!lockingRecord) return await this.missingProgressOutcome(record.requestId);

    const admitInput = {
      requestId: record.requestId,
      userId: record.userId,
      sessionId: record.sessionId,
      ticketTypeId: preference.ticketTypeId,
      quantity: record.quantity,
      seatIds: record.seatIds,
      idempotencyKey: record.idempotencyKey,
      ttlSeconds: this.requestTtlSeconds,
    };
    let admission = await latencyTrace.measure('lock', () => this.admissionService.admit(admitInput));
    if (admission.outcome === 'STOCK_UNINITIALIZED') {
      const initializedStock = await latencyTrace.measure('lock', async () =>
        this.stockService?.ensureInitialized(record.sessionId, preference.ticketTypeId),
      );
      if (initializedStock != null) {
        admission = await latencyTrace.measure('lock', () => this.admissionService.admit(admitInput));
      }
    }

    if (admission.outcome === 'SOLD_OUT') {
      const refreshedStock = await latencyTrace.measure('lock', async () =>
        this.stockService?.syncFromTicketType?.(record.sessionId, preference.ticketTypeId),
      );
      if (refreshedStock != null && refreshedStock >= record.quantity) {
        admission = await latencyTrace.measure('lock', () => this.admissionService.admit(admitInput));
      }
    }

    if (admission.outcome === 'SOLD_OUT') {
      return 'SOLD_OUT';
    }
    if (admission.outcome === 'LIMITED') {
      const finished = await this.finishTerminalAttempt(
        record,
        GRAB_STATUS.LIMITED,
        '限购校验未通过或座位锁定冲突',
        preference,
        index,
        lockingAttempts,
        'LIMITED',
      );
      return finished ? 'LIMITED' : 'STALE_LEASE';
    }
    if (admission.outcome === 'STOCK_UNINITIALIZED') {
      const finished = await this.finishTerminalAttempt(
        record,
        GRAB_STATUS.FAILED,
        '抢票库存未初始化',
        preference,
        index,
        lockingAttempts,
        'FAILED',
      );
      return finished ? 'FAILED' : 'STALE_LEASE';
    }
    if (admission.outcome === 'IDEMPOTENT') {
      const idempotentOutcome = await this.handleIdempotentAdmission(
        record,
        preference,
        index,
        lockingAttempts,
        admission.existingRequestId,
      );
      if (idempotentOutcome !== 'CONTINUE') return idempotentOutcome;
    }

    const orderCreatingRecord = await this.repository.updateProgress(record.requestId, {
      status: GRAB_STATUS.ORDER_CREATING,
      message: `${this.ticketLabel(preference)}已锁定，正在创建订单`,
      currentTicketTypeId: preference.ticketTypeId,
      currentAttemptIndex: index,
      attempts: this.markAttempt(lockingAttempts, index, 'LOCKING', `${this.ticketLabel(preference)}已锁定`),
      workerId: this.workerId,
    });
    if (!orderCreatingRecord) {
      if (await this.isStillLeaseOwner(record.requestId)) {
        await this.releaseAdmission(record, preference);
        return 'CANCELLED';
      }
      return 'STALE_LEASE';
    }

    const orderInput = {
      userId: record.userId,
      sessionId: record.sessionId,
      ticketTypeId: preference.ticketTypeId,
      quantity: record.quantity,
      seatIds: record.seatIds,
      allocateRandom: record.allocateRandom,
      attendeeIds: record.attendeeIds,
      authorizedMaxUnitPrice: preference.maxPrice,
      grabRequestId: record.requestId,
      requestedTicketTypeId: record.ticketTypeId,
      matchedTicketTypeId: preference.ticketTypeId,
      autoDowngraded: preference.ticketTypeId !== record.ticketTypeId,
    };

    try {
      const order = await latencyTrace.measure('order', () => this.orderClient.createOrder(orderInput));
      const persisted = await latencyTrace.measure('confirm', () =>
        this.markOrderCreated(record, preference, index, lockingAttempts, order.id),
      );
      return persisted ? 'ORDER_CREATED' : await latencyTrace.measure('confirm', () =>
        this.markPendingRecovery(record, preference, index, lockingAttempts),
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : '订单创建失败';
      if (this.isStockError(message)) {
        await this.releaseAdmission(record, preference);
        return 'SOLD_OUT';
      }
      if (this.isLimitError(message)) {
        await this.releaseAdmission(record, preference);
        const finished = await this.finishTerminalAttempt(record, GRAB_STATUS.LIMITED, message, preference, index, lockingAttempts, 'LIMITED');
        return finished ? 'LIMITED' : 'STALE_LEASE';
      }

      const recovery = await latencyTrace.measure('confirm', () =>
        this.recoverOrderByLookup(record, preference, index, lockingAttempts),
      );
      if (recovery === 'RECOVERED') return 'ORDER_CREATED';
      if (recovery === 'UNKNOWN') return await latencyTrace.measure('confirm', () =>
        this.markPendingRecovery(record, preference, index, lockingAttempts),
      );

      await this.releaseAdmission(record, preference);
      const finished = await this.finishTerminalAttempt(record, GRAB_STATUS.FAILED, message, preference, index, lockingAttempts, 'FAILED');
      return finished ? 'FAILED' : 'STALE_LEASE';
    }
  }

  private async handleIdempotentAdmission(
    record: GrabRequestRecord,
    preference: GrabTicketPreference,
    index: number,
    attempts: GrabAttemptSnapshot[],
    existingRequestId: string | null,
  ): Promise<IdempotentAdmissionOutcome> {
    if (existingRequestId !== record.requestId) {
      const finished = await this.finishTerminalAttempt(
        record,
        GRAB_STATUS.FAILED,
        '当前幂等锁已被其他请求占用',
        preference,
        index,
        attempts,
        'FAILED',
      );
      return finished ? 'FAILED' : 'STALE_LEASE';
    }

    const recovery = await this.recoverOrderByLookup(record, preference, index, attempts, GRAB_STATUS.LOCKING);
    if (recovery === 'RECOVERED') return 'ORDER_CREATED';
    if (recovery === 'UNKNOWN') return await this.markPendingRecovery(record, preference, index, attempts);
    return 'CONTINUE';
  }

  private async markOrderCreated(
    record: GrabRequestRecord,
    preference: GrabTicketPreference,
    index: number,
    attempts: GrabAttemptSnapshot[],
    orderId: number,
  ): Promise<boolean> {
    const orderAttempts = this.markAttempt(attempts, index, 'ORDER_CREATED', `${this.ticketLabel(preference)}已创建订单`);
    try {
      const marked = await this.repository.markOrderCreated(
        record.requestId,
        orderId,
        preference.ticketTypeId,
        orderAttempts,
        GRAB_STATUS.ORDER_CREATING,
        this.workerId,
      );
      if (marked) return true;
    } catch (error) {
      this.logger.error(error);
    }

    return await this.recoverOrderByLookup(record, preference, index, attempts, GRAB_STATUS.ORDER_CREATING) === 'RECOVERED';
  }

  private async recoverOrderByLookup(
    record: GrabRequestRecord,
    preference: GrabTicketPreference,
    index: number,
    attempts: GrabAttemptSnapshot[],
    expectedProgressStatus: typeof GRAB_STATUS.LOCKING | typeof GRAB_STATUS.ORDER_CREATING = GRAB_STATUS.ORDER_CREATING,
  ): Promise<OrderLookupRecovery> {
    try {
      const existingOrder = await this.orderClient.findByGrabRequestId(record.requestId);
      if (!existingOrder) return 'MISSING';
      const recovered = await this.repository.markOrderCreated(
        record.requestId,
        existingOrder.id,
        preference.ticketTypeId,
        this.markAttempt(attempts, index, 'ORDER_CREATED', `${this.ticketLabel(preference)}已创建订单`),
        expectedProgressStatus,
        this.workerId,
      );
      return recovered ? 'RECOVERED' : 'UNKNOWN';
    } catch (error) {
      this.logger.error(error);
      return 'UNKNOWN';
    }
  }

  private async markPendingRecovery(
    record: GrabRequestRecord,
    preference: GrabTicketPreference,
    index: number,
    attempts: GrabAttemptSnapshot[],
  ): Promise<AttemptOutcome> {
    const marked = await this.repository.markPendingRecovery(record.requestId, {
      message: '订单确认中，请稍后刷新',
      currentTicketTypeId: preference.ticketTypeId,
      currentAttemptIndex: index,
      attempts: this.markAttempt(attempts, index, 'LOCKING', '订单确认中，请稍后刷新'),
      workerId: this.workerId,
    });
    if (marked) return 'PENDING_RECOVERY';

    const latest = await this.repository.findByRequestId(record.requestId);
    if (latest && (isTerminalGrabStatus(latest.progressStatus) || latest.orderId)) return 'CANCELLED';
    return 'STALE_LEASE';
  }

  private async releaseAdmission(record: GrabRequestRecord, preference: GrabTicketPreference): Promise<void> {
    await this.admissionService.release({
      requestId: record.requestId,
      userId: record.userId,
      sessionId: record.sessionId,
      ticketTypeId: preference.ticketTypeId,
      quantity: record.quantity,
      seatIds: record.seatIds,
      idempotencyKey: record.idempotencyKey,
      restoreStock: true,
    });
  }

  private async finishTerminalAttempt(
    record: GrabRequestRecord,
    status: typeof GRAB_STATUS.SOLD_OUT | typeof GRAB_STATUS.LIMITED | typeof GRAB_STATUS.FAILED,
    message: string,
    preference: GrabTicketPreference,
    index: number,
    attempts: GrabAttemptSnapshot[],
    attemptStatus: GrabAttemptSnapshot['status'],
  ): Promise<boolean> {
    const updated = await this.repository.updateProgress(record.requestId, {
      status,
      message,
      currentTicketTypeId: preference.ticketTypeId,
      currentAttemptIndex: index,
      attempts: this.markAttempt(attempts, index, attemptStatus, message),
      workerId: this.workerId,
    });
    if (!updated) return await this.isStillLeaseOwner(record.requestId);
    await this.repository.updateStatus(record.requestId, status, message);
    return true;
  }

  private preferencesFor(record: GrabRequestRecord): GrabTicketPreference[] {
    if (record.allowAutoDowngrade) return record.requestedTicketTypes;
    return record.requestedTicketTypes.slice(0, 1);
  }

  private initialAttempts(preferences: GrabTicketPreference[]): GrabAttemptSnapshot[] {
    return preferences.map((ticket) => ({
      ticketTypeId: ticket.ticketTypeId,
      name: ticket.name,
      status: 'PENDING',
      message: '待尝试',
    }));
  }

  private markAttempt(
    attempts: GrabAttemptSnapshot[],
    index: number,
    status: GrabAttemptSnapshot['status'],
    message: string,
  ): GrabAttemptSnapshot[] {
    return attempts.map((attempt, attemptIndex) => (
      attemptIndex === index ? { ...attempt, status, message } : attempt
    ));
  }

  private async ackIfQueued(record: Pick<GrabRequestRecord, 'sessionId' | 'requestId' | 'queueSeq'>): Promise<void> {
    if (record.queueSeq == null) return;
    await this.queueService.ackProcessed(record.sessionId, record.requestId, record.queueSeq);
  }

  private async isStillLeaseOwner(requestId: string): Promise<boolean> {
    const latest = await this.repository.findByRequestId(requestId);
    return latest?.workerId === this.workerId;
  }

  private async missingProgressOutcome(requestId: string): Promise<AttemptOutcome> {
    return await this.isStillLeaseOwner(requestId) ? 'CANCELLED' : 'STALE_LEASE';
  }

  private isStockError(message: string): boolean {
    const normalized = message.toLowerCase();
    return normalized.includes('stock') || normalized.includes('sold out') || message.includes('\u5e93\u5b58') || message.includes('\u552e\u7f44');
  }

  private isLimitError(message: string): boolean {
    const normalized = message.toLowerCase();
    return normalized.includes('limit') || message.includes('\u9650\u8d2d');
  }

  private ticketLabel(preference: GrabTicketPreference): string {
    return preference.name ?? `票档 ${preference.ticketTypeId}`;
  }
}
