import { Injectable } from '@nestjs/common';
import { requireEnv } from '../runtime-env';

export interface TicketTypeVisibleInfo {
  ticketTypeId: number;
  name: string;
  price: number;
  totalStock?: number | null;
  remainStock: number | null;
}

export interface PurchaseContextInfo {
  sessionId: number;
  ticketTypeId: number;
  activityId?: number | null;
  activityName?: string | null;
  activityPoster?: string | null;
  ticketTypeName?: string | null;
  venueName?: string | null;
  sessionTime?: string | null;
}

@Injectable()
export class TicketClientService {
  private readonly baseUrl = requireEnv('TICKET_SERVICE_URL', '票务服务地址未配置');
  private readonly internalToken = process.env.INTERNAL_API_TOKEN;

  async listVisibleTicketTypes(sessionId: number, ticketTypeIds: number[]): Promise<TicketTypeVisibleInfo[]> {
    if (!this.internalToken) throw new Error('票务内部接口令牌未配置');
    const response = await fetch(`${this.baseUrl}/api/ticket/internal/sales/ticket-types-visible`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': this.internalToken },
      body: JSON.stringify({ sessionId, ticketTypeIds }),
    });
    const result = await response.json();
    if (!response.ok || result.code !== 200) {
      throw new Error(result.message || '票务服务暂不可用');
    }
    return Array.isArray(result.data) ? result.data : [];
  }

  async getPurchaseContext(sessionId: number, ticketTypeId: number): Promise<PurchaseContextInfo> {
    if (!this.internalToken) throw new Error('票务内部接口令牌未配置');
    const response = await fetch(`${this.baseUrl}/api/ticket/internal/sales/purchase-context`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': this.internalToken },
      body: JSON.stringify({ sessionId, ticketTypeId }),
    });
    const result = await response.json();
    if (!response.ok || result.code !== 200) {
      throw new Error(result.message || '票务上下文暂不可用');
    }
    return result.data;
  }

}
