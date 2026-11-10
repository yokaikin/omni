import { BadRequestException, ConflictException, Injectable, NotFoundException, Optional } from '@nestjs/common';
import { TicketClientService, PurchaseContextInfo } from '../grab/ticket-client.service';
import { VisibleStockService } from '../grab/visible-stock.service';
import { WaitlistRepository } from './waitlist.repository';
import { CreateWaitlistEntryDto, WaitlistEntryRecord, WaitlistEntryResponse } from './waitlist.types';

@Injectable()
export class WaitlistService {
  constructor(
    private readonly repository: WaitlistRepository,
    @Optional() private readonly ticketClient?: TicketClientService,
    @Optional() private readonly visibleStockService?: VisibleStockService,
  ) {}

  async createEntry(userId: number, dto: CreateWaitlistEntryDto): Promise<WaitlistEntryResponse> {
    this.validateCreateDto(dto);
    if (!this.visibleStockService) throw new BadRequestException('候补库存状态暂不可用');
    const stock = await this.visibleStockService.getSessionVisibleStock(dto.sessionId, [dto.ticketTypeId]);
    const ticket = stock.ticketTypes.find((item) => item.ticketTypeId === dto.ticketTypeId);
    if (!ticket || ticket.visibleStock == null || ticket.visibleStock > 0) {
      throw new BadRequestException('当前票档尚未售罄，暂不能加入候补');
    }
    try {
      const result = await this.repository.createEntry({
        userId,
        sessionId: dto.sessionId,
        ticketTypeId: dto.ticketTypeId,
        quantity: dto.quantity,
        attendeeIds: [...(dto.attendeeIds ?? [])].sort((a, b) => a - b),
      });
      return this.toResponseWithContext(result.entry, result.rank);
    } catch (error: any) {
      if (error?.code === '23505') {
        throw new ConflictException('已加入该场次票档候补');
      }
      throw error;
    }
  }

  async listMine(userId: number): Promise<WaitlistEntryResponse[]> {
    const entries = await this.repository.listByUser(userId);
    return Promise.all(entries.map((entry) => this.toResponseWithContext(entry, entry.rank ?? null)));
  }

  async listByUser(userId: number, limit = 5): Promise<WaitlistEntryResponse[]> {
    const entries = await this.repository.listByUser(userId, this.normalizeLimit(limit));
    return Promise.all(entries.map((entry) => this.toResponseWithContext(entry, entry.rank ?? null)));
  }

  async cancelEntry(userId: number, entryId: number): Promise<WaitlistEntryResponse> {
    const entry = await this.repository.cancelWaitingEntry(entryId, userId);
    if (!entry) throw new NotFoundException('未找到可取消的候补记录');
    return this.toResponseWithContext(entry, null);
  }

  private validateCreateDto(dto: CreateWaitlistEntryDto): void {
    if (!dto || !Number.isInteger(dto.sessionId) || dto.sessionId <= 0) throw new BadRequestException('场次不能为空');
    if (!Number.isInteger(dto.ticketTypeId) || dto.ticketTypeId <= 0) throw new BadRequestException('票档不能为空');
    if (!Number.isInteger(dto.quantity) || dto.quantity <= 0 || dto.quantity > 6) throw new BadRequestException('候补数量必须为 1-6 张');
    if (!dto.attendeeIds || dto.attendeeIds.length !== dto.quantity) {
      throw new BadRequestException('请选择与候补数量一致的实名观演人');
    }
    const unique = new Set(dto.attendeeIds);
    if (unique.size !== dto.attendeeIds.length || dto.attendeeIds.some((id) => !Number.isInteger(id) || id <= 0)) {
      throw new BadRequestException('实名观演人信息无效');
    }
  }

  private normalizeLimit(limit: number): number {
    if (!Number.isFinite(limit)) return 5;
    return Math.max(0, Math.min(Math.trunc(limit), 20));
  }

  private async toResponseWithContext(entry: WaitlistEntryRecord, rank: number | null): Promise<WaitlistEntryResponse> {
    const response = this.toResponse(entry, rank);
    const context = await this.loadPurchaseContext(entry.sessionId, entry.ticketTypeId);
    if (!context) return response;
    return this.applyPurchaseContext(response, context);
  }

  private toResponse(entry: WaitlistEntryRecord, rank: number | null): WaitlistEntryResponse {
    const estimate = this.estimateChance(entry, rank);
    return {
      id: entry.id,
      sessionId: entry.sessionId,
      ticketTypeId: entry.ticketTypeId,
      quantity: entry.quantity,
      status: entry.status,
      rank,
      estimatedChance: estimate.estimatedChance,
      estimatedChanceText: estimate.estimatedChanceText,
      estimatedWaitText: estimate.estimatedWaitText,
      offerOrderId: entry.offerOrderId,
      offerExpireTime: entry.offerExpireTime ? entry.offerExpireTime.toISOString() : null,
      failReason: entry.failReason,
    };
  }

  private async loadPurchaseContext(sessionId: number, ticketTypeId: number): Promise<PurchaseContextInfo | null> {
    if (!this.ticketClient) return null;
    try {
      return await this.ticketClient.getPurchaseContext(sessionId, ticketTypeId);
    } catch {
      return null;
    }
  }

  private applyPurchaseContext(response: WaitlistEntryResponse, context: PurchaseContextInfo): WaitlistEntryResponse {
    return {
      ...response,
      activityId: context.activityId,
      activityName: context.activityName,
      activityPoster: context.activityPoster,
      ticketTypeName: context.ticketTypeName,
      venueName: context.venueName,
      sessionTime: context.sessionTime,
    };
  }

  private estimateChance(entry: WaitlistEntryRecord, rank: number | null): Pick<WaitlistEntryResponse, 'estimatedChance' | 'estimatedChanceText' | 'estimatedWaitText'> {
    if (entry.status === 'OFFERED') {
      return {
        estimatedChance: 'HIGH',
        estimatedChanceText: '已获得资格',
        estimatedWaitText: '请在截止时间前完成支付',
      };
    }
    if (entry.status !== 'WAITING' && entry.status !== 'ALLOCATING') {
      return {
        estimatedChance: 'UNKNOWN',
        estimatedChanceText: '已结束',
        estimatedWaitText: '候补已不再等待释放票',
      };
    }
    if (rank == null) {
      return {
        estimatedChance: 'UNKNOWN',
        estimatedChanceText: '计算中',
        estimatedWaitText: '等待系统刷新排位',
      };
    }
    if (rank <= 10) {
      return {
        estimatedChance: 'HIGH',
        estimatedChanceText: '机会较高',
        estimatedWaitText: '排位靠前，释放票后会优先通知',
      };
    }
    if (rank <= 50) {
      return {
        estimatedChance: 'MEDIUM',
        estimatedChanceText: '机会中等',
        estimatedWaitText: '仍在有效候补范围内，请留意通知',
      };
    }
    return {
      estimatedChance: 'LOW',
      estimatedChanceText: '机会较低',
      estimatedWaitText: '排位靠后，建议关注其他日期或票档',
    };
  }
}
