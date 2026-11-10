import { Injectable } from '@nestjs/common';
import { requireEnv } from '../runtime-env';

export interface CreateOrderInput {
  userId: number;
  sessionId: number;
  ticketTypeId: number;
  quantity: number;
  seatIds: number[];
  allocateRandom: boolean;
  attendeeIds?: number[];
  authorizedMaxUnitPrice?: number | null;
  grabRequestId?: string | null;
  requestedTicketTypeId?: number | null;
  matchedTicketTypeId?: number | null;
  autoDowngraded?: boolean;
}

export interface CreatedOrderResponse {
  id: number;
  orderNo: string;
  amount: number;
}

export interface GrabOrderLookupResponse {
  id: number;
  orderNo: string;
  status: string | null;
  grabRequestId: string | null;
}

export interface OrderStatusResponse {
  id: number;
  status: number;
  userId: number;
  quantity: number;
}

export interface OrderSeatResponse {
  orderSeatId: number;
  sessionSeatId: number;
  seatLabel: string | null;
  status: number;
}

@Injectable()
export class OrderClientService {
  private readonly baseUrl = requireEnv('ORDER_SERVICE_URL', '订单服务地址未配置');
  private readonly internalToken = process.env.INTERNAL_API_TOKEN;

  async createOrder(input: CreateOrderInput): Promise<CreatedOrderResponse> {
    if (!this.internalToken) {
      throw new Error('订单内部接口令牌未配置');
    }

    const usesSeatEndpoint = input.seatIds.length > 0 || input.allocateRandom;
    const path = usesSeatEndpoint ? '/api/order/internal/create-with-seats' : '/api/order/internal/create';
    const grabMetadata = {
      authorizedMaxUnitPrice: input.authorizedMaxUnitPrice,
      grabRequestId: input.grabRequestId,
      requestedTicketTypeId: input.requestedTicketTypeId,
      matchedTicketTypeId: input.matchedTicketTypeId,
      autoDowngraded: Boolean(input.autoDowngraded),
    };
    const attendeePayload = input.attendeeIds?.length ? { attendeeIds: input.attendeeIds } : {};
    const body = usesSeatEndpoint
      ? {
          userId: input.userId,
          sessionId: input.sessionId,
          ticketTypeId: input.ticketTypeId,
          seatIds: input.seatIds,
          quantity: input.quantity,
          ...attendeePayload,
          ...grabMetadata,
        }
      : {
          userId: input.userId,
          sessionId: input.sessionId,
          ticketTypeId: input.ticketTypeId,
          quantity: input.quantity,
          ...attendeePayload,
          ...grabMetadata,
        };

    const response = await fetch(`${this.baseUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': this.internalToken },
      body: JSON.stringify(body),
    });
    const result = await response.json();
    if (!response.ok || result.code !== 200) {
      throw new Error(result.message || '订单创建失败');
    }
    return result.data;
  }

  async findByGrabRequestId(grabRequestId: string): Promise<GrabOrderLookupResponse | null> {
    if (!this.internalToken) {
      throw new Error('订单内部接口令牌未配置');
    }

    const response = await fetch(`${this.baseUrl}/api/order/internal/grab-requests/${encodeURIComponent(grabRequestId)}`, {
      method: 'GET',
      headers: { 'X-Internal-Token': this.internalToken },
    });
    if (response.status === 404) return null;
    const result = await response.json();
    if (!response.ok || (result.code !== 200 && result.code !== 404)) {
      throw new Error(result.message || '订单查询失败');
    }
    return result.data ?? null;
  }

  async createWaitlistOfferOrder(input: {
    userId: number;
    sessionId: number;
    ticketTypeId: number;
    quantity: number;
    attendeeIds?: number[];
    grabRequestId: string;
  }): Promise<CreatedOrderResponse> {
    return this.createOrder({
      userId: input.userId,
      sessionId: input.sessionId,
      ticketTypeId: input.ticketTypeId,
      quantity: input.quantity,
      attendeeIds: input.attendeeIds,
      seatIds: [],
      allocateRandom: true,
      grabRequestId: input.grabRequestId,
      requestedTicketTypeId: input.ticketTypeId,
      matchedTicketTypeId: input.ticketTypeId,
      autoDowngraded: false,
    });
  }

  async getOrder(orderId: number): Promise<OrderStatusResponse> {
    if (!this.internalToken) {
      throw new Error('订单内部接口令牌未配置');
    }

    const response = await fetch(`${this.baseUrl}/api/order/internal/${orderId}`, {
      method: 'GET',
      headers: { 'X-Internal-Token': this.internalToken },
    });
    const result = await response.json();
    if (!response.ok || result.code !== 200) {
      throw new Error(result.message || '订单查询失败');
    }
    return result.data;
  }

  async listOrderSeats(orderId: number): Promise<OrderSeatResponse[]> {
    if (!this.internalToken) {
      throw new Error('订单内部接口令牌未配置');
    }

    const response = await fetch(`${this.baseUrl}/api/order/internal/${orderId}/seats`, {
      method: 'GET',
      headers: { 'X-Internal-Token': this.internalToken },
    });
    const result = await response.json();
    if (!response.ok || result.code !== 200) {
      throw new Error(result.message || '订单座位查询失败');
    }
    return Array.isArray(result.data) ? result.data : [];
  }
}
