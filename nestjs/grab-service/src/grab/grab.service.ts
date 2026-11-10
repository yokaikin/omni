import { BadRequestException, ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { randomBytes } from 'crypto';
import { GrabAdmissionService } from './grab-admission.service';
import { GrabQueueService } from './grab-queue.service';
import { ACTIVE_ASYNC_PROGRESS_STATUSES, GrabRepository, isUniqueViolation } from './grab.repository';
import { GRAB_STATUS } from './grab-status';
import type { GrabStatus } from './grab-status';
import type { GrabProgressResponse, GrabRequestRecord, GrabRequestResponse, GrabTicketPreference, SubmitGrabRequestDto } from './grab.types';
import { OrderClientService } from './order-client.service';
import { TicketClientService, TicketTypeVisibleInfo } from './ticket-client.service';
import { VisibleStockService } from './visible-stock.service';

const ACTIVE_ASYNC_PROGRESS_STATUS_SET = new Set<string>(ACTIVE_ASYNC_PROGRESS_STATUSES);
const CANCELABLE_PROGRESS_STATUSES = new Set<string>([
  GRAB_STATUS.QUEUED,
  GRAB_STATUS.WAITING,
  GRAB_STATUS.TRYING_TICKET_TYPE,
  GRAB_STATUS.LOCKING,
]);
const RELEASEABLE_PROGRESS_STATUSES = new Set(['LOCKING', 'ORDER_CREATING']);
const TERMINAL_CANCEL_STATUSES = new Set<string>([
  GRAB_STATUS.ORDER_CREATED,
  GRAB_STATUS.SOLD_OUT,
  GRAB_STATUS.LIMITED,
  GRAB_STATUS.FAILED,
  GRAB_STATUS.EXPIRED,
]);

@Injectable()
export class GrabService {
  private readonly requestTtlSeconds = 900;

  constructor(
    private readonly repository: GrabRepository,
    private readonly admissionService: GrabAdmissionService,
    private readonly orderClient: OrderClientService,
    private readonly queueService: GrabQueueService,
    private readonly ticketClient: TicketClientService,
    private readonly visibleStockService: VisibleStockService,
  ) {}

  async submitRequest(userId: number, dto: SubmitGrabRequestDto): Promise<GrabRequestResponse> {
    this.validateSubmitRequest(dto);
    const existing = await this.repository.findByUserAndIdempotency(userId, dto.idempotencyKey);
    if (existing) return this.toResponse(existing);

    const requestedTicketTypes = await this.normalizePreferences(dto);
    const firstPreference = requestedTicketTypes[0];
    const allowAutoDowngrade = Boolean(dto.allowAutoDowngrade) && requestedTicketTypes.length > 1;

    const requestId = this.generateRequestId();
    const seatIds = [...(dto.seatIds ?? [])].sort((a, b) => a - b);
    const attendeeIds = [...(dto.attendeeIds ?? [])].sort((a, b) => a - b);
    const allocateRandom = Boolean(dto.allocateRandom);
    const active = await this.repository.findActiveByIntent({
      userId,
      sessionId: dto.sessionId,
      ticketTypeId: firstPreference.ticketTypeId,
      quantity: dto.quantity,
      seatIds,
      attendeeIds,
      allocateRandom,
      requestedTicketTypes,
      allowAutoDowngrade,
    });
    if (active) return this.toResponse(active);

    const queued = await this.queueService.enqueue({
      requestId,
      sessionId: dto.sessionId,
      userId,
      ttlSeconds: this.requestTtlSeconds,
    });

    let created: GrabRequestRecord;
    try {
      created = await this.repository.createQueued({
        requestId,
        idempotencyKey: dto.idempotencyKey,
        userId,
        sessionId: dto.sessionId,
        ticketTypeId: firstPreference.ticketTypeId,
        quantity: dto.quantity,
        seatIds,
        attendeeIds,
        allocateRandom,
        expireTime: new Date(Date.now() + this.requestTtlSeconds * 1000),
        queueSeq: queued.queueSeq,
        requestedTicketTypes,
        allowAutoDowngrade,
      });
    } catch (error) {
      await this.queueService.removeQueuedRequest(dto.sessionId, requestId);
      if (isUniqueViolation(error)) {
        const record = await this.repository.findByUserAndIdempotency(userId, dto.idempotencyKey);
        if (record) return this.toResponse(record);
      }
      throw error;
    }

    return this.toResponse(created, queued.queueRank);
  }

  async getRequest(userId: number, requestId: string): Promise<GrabRequestResponse> {
    const record = await this.repository.findByRequestId(requestId);
    if (!record) throw new NotFoundException('抢票请求不存在');
    if (record.userId !== userId) throw new ForbiddenException('不能查看其他用户的抢票请求');
    return this.toResponse(record);
  }

  async getProgress(userId: number, requestId: string): Promise<GrabProgressResponse> {
    const record = await this.repository.findByRequestId(requestId);
    if (!record) throw new NotFoundException('抢票请求不存在');
    if (record.userId !== userId) throw new ForbiddenException('不能查看其他用户的抢票请求');
    return this.toProgressResponse(record);
  }

  async getProgressForVerifiedRequest(requestId: string): Promise<GrabProgressResponse> {
    const record = await this.repository.findByRequestId(requestId);
    if (!record) throw new NotFoundException('抢票请求不存在');
    return this.toProgressResponse(record);
  }

  async listByUser(userId: number, limit = 5): Promise<GrabRequestResponse[]> {
    const records = await this.repository.listByUser(userId, this.normalizeLimit(limit));
    return Promise.all(records.map((record) => this.toResponse(record)));
  }

  private async toProgressResponse(record: GrabRequestRecord): Promise<GrabProgressResponse> {
    const queueRank = record.queueSeq == null ? null : await this.queueService.calculateQueueRank(record.sessionId, record.queueSeq);
    const visibleStock = await this.resolveProgressVisibleStock(record);

    return {
      requestId: record.requestId,
      sessionId: record.sessionId,
      status: record.progressStatus,
      orderId: record.orderId,
      failReason: record.failReason,
      queueSeq: record.queueSeq,
      queueRank,
      estimatedWaitSeconds: null,
      currentTicketTypeId: record.currentTicketTypeId,
      currentAttemptIndex: record.currentAttemptIndex,
      requestedTicketTypes: record.requestedTicketTypes,
      allowAutoDowngrade: Boolean(record.allowAutoDowngrade),
      attempts: record.attemptsSnapshot,
      visibleStock,
      fairnessNotes: this.buildFairnessNotes(),
      message: record.progressMessage,
      matchedTicketTypeId: record.matchedTicketTypeId,
      updateTime: record.updatedAt.toISOString(),
    };
  }

  private buildFairnessNotes(): string[] {
    return [
      '同一账号相同购票意图会复用已有排队请求，避免重复挤占队列。',
      '活动限购规则会在锁票和创建订单阶段校验。',
      '异常高频请求会触发风控拦截，请使用当前页面正常刷新。',
    ];
  }

  async cancelRequest(userId: number, requestId: string): Promise<GrabRequestResponse> {
    const record = await this.repository.findByRequestId(requestId);
    if (!record) throw new NotFoundException('抢票请求不存在');
    if (record.userId !== userId) throw new ForbiddenException('不能取消其他用户的抢票请求');
    if (TERMINAL_CANCEL_STATUSES.has(record.status)) return this.toResponse(record);
    if (record.orderId) return this.toResponse(record);
    const progressStatus = record.progressStatus;
    const hasCancelableProgress = CANCELABLE_PROGRESS_STATUSES.has(progressStatus);
    const hasLegacyCancelableStatus = !ACTIVE_ASYNC_PROGRESS_STATUS_SET.has(progressStatus) && record.status === GRAB_STATUS.ACCEPTED;
    if (!hasCancelableProgress && !hasLegacyCancelableStatus) return this.toResponse(record);
    const expired = hasCancelableProgress
      ? await this.repository.expireActiveRequest(requestId, '抢票请求已取消', [progressStatus as GrabStatus])
      : await this.repository.updateStatus(requestId, GRAB_STATUS.EXPIRED, '抢票请求已取消');
    if (!expired) {
      const latest = await this.repository.findByRequestId(requestId);
      return this.toResponse(latest ?? record);
    }
    if (RELEASEABLE_PROGRESS_STATUSES.has(progressStatus) || (!hasCancelableProgress && hasLegacyCancelableStatus)) {
      await this.admissionService.release({
        requestId: record.requestId,
        userId: record.userId,
        sessionId: record.sessionId,
        ticketTypeId: record.currentTicketTypeId ?? record.ticketTypeId,
        quantity: record.quantity,
        seatIds: record.seatIds,
        idempotencyKey: record.idempotencyKey,
      });
    }
    return this.toResponse(expired);
  }

  private validateSubmitRequest(dto: SubmitGrabRequestDto): void {
    if (!Number.isInteger(dto.sessionId) || dto.sessionId <= 0) throw new BadRequestException('场次不能为空');
    if (!Number.isInteger(dto.quantity) || dto.quantity <= 0) throw new BadRequestException('购买数量不正确');
    if (!dto.idempotencyKey?.trim()) throw new BadRequestException('幂等请求标识不能为空');
    if (dto.seatIds && dto.seatIds.length > 0 && dto.seatIds.length !== dto.quantity) throw new BadRequestException('座位数量必须与购买数量一致');
    if (!dto.attendeeIds || dto.attendeeIds.length !== dto.quantity) {
      throw new BadRequestException('请选择与购票数量一致的实名观演人');
    }
    const unique = new Set(dto.attendeeIds);
    if (unique.size !== dto.attendeeIds.length || dto.attendeeIds.some((id) => !Number.isInteger(id) || id <= 0)) {
      throw new BadRequestException('实名观演人信息无效');
    }
  }

  private async normalizePreferences(dto: SubmitGrabRequestDto): Promise<GrabTicketPreference[]> {
    const preferences = dto.ticketTypePreferences?.length
      ? dto.ticketTypePreferences
      : dto.ticketTypeId == null
        ? []
        : [{ ticketTypeId: dto.ticketTypeId }];

    if (preferences.length === 0) throw new BadRequestException('票档不能为空');

    const requestedIds = preferences.map((preference) => {
      if (!Number.isInteger(preference.ticketTypeId) || preference.ticketTypeId <= 0) {
        throw new BadRequestException('票档信息无效');
      }

      return preference.ticketTypeId;
    });

    if (dto.seatIds?.length && requestedIds.length > 1) {
      throw new BadRequestException('选座模式不支持自动降档');
    }

    const metadata = await this.ticketClient.listVisibleTicketTypes(dto.sessionId, requestedIds);
    const metadataById = new Map<number, TicketTypeVisibleInfo>(
      metadata.map((ticket) => [ticket.ticketTypeId, ticket]),
    );
    const canonical = requestedIds.map((ticketTypeId) => {
      const ticket = metadataById.get(ticketTypeId);
      if (!ticket) throw new BadRequestException('当前场次不可购买该票档');
      return {
        ticketTypeId,
        name: ticket.name,
        maxPrice: ticket.price,
      };
    });

    if (!dto.allowAutoDowngrade) return [canonical[0]];

    const requestedPrice = canonical[0].maxPrice;
    for (const preference of canonical.slice(1)) {
      if (preference.maxPrice > requestedPrice) {
        throw new BadRequestException('自动降档不能提高票价');
      }
    }

    return canonical;
  }

  private generateRequestId(): string {
    return `GRAB${randomBytes(12).toString('hex')}`;
  }

  private normalizeLimit(limit: number): number {
    if (!Number.isFinite(limit)) return 5;
    return Math.max(0, Math.min(Math.trunc(limit), 20));
  }

  private async resolveProgressVisibleStock(record: GrabRequestRecord) {
    const ticketTypeId = record.currentTicketTypeId ?? record.requestedTicketTypes[record.currentAttemptIndex]?.ticketTypeId ?? record.ticketTypeId;
    if (!ticketTypeId) return null;
    try {
      const snapshot = await this.visibleStockService.getSessionVisibleStock(record.sessionId, [ticketTypeId]);
      const ticket = snapshot.ticketTypes.find((item) => item.ticketTypeId === ticketTypeId);
      if (!ticket) return null;
      return {
        ticketTypeId: ticket.ticketTypeId,
        visibleStock: ticket.visibleStock,
        level: ticket.level,
        snapshotTime: snapshot.snapshotTime,
      };
    } catch {
      return null;
    }
  }

  private async toResponse(
    record: Pick<GrabRequestRecord, 'requestId' | 'status' | 'orderId' | 'failReason'> &
      Partial<Pick<GrabRequestRecord, 'sessionId' | 'progressStatus' | 'progressMessage' | 'queueSeq'>>,
    queueRank?: number | null,
  ): Promise<GrabRequestResponse> {
    const rank = queueRank ?? (
      record.queueSeq != null && record.sessionId != null
        ? await this.queueService.calculateQueueRank(record.sessionId, record.queueSeq)
        : null
    );

    return {
      requestId: record.requestId,
      status: record.progressStatus ?? record.status,
      orderId: record.orderId,
      failReason: record.failReason,
      queueSeq: record.queueSeq ?? null,
      queueRank: rank,
      estimatedWaitSeconds: null,
      message: record.progressMessage ?? null,
    };
  }
}
