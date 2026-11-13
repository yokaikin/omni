export const WAITLIST_ENTRY_STATUS = {
  WAITING: 'WAITING',
  ALLOCATING: 'ALLOCATING',
  OFFERED: 'OFFERED',
  PAID: 'PAID',
  CANCELLED: 'CANCELLED',
  EXPIRED: 'EXPIRED',
  FAILED: 'FAILED',
} as const;

export type WaitlistEntryStatus = typeof WAITLIST_ENTRY_STATUS[keyof typeof WAITLIST_ENTRY_STATUS];

export const WAITLIST_OFFER_STATUS = {
  OFFERED: 'OFFERED',
  PAID: 'PAID',
  EXPIRED: 'EXPIRED',
  CANCELLED: 'CANCELLED',
} as const;

export type WaitlistOfferStatus = typeof WAITLIST_OFFER_STATUS[keyof typeof WAITLIST_OFFER_STATUS];

export interface CreateWaitlistEntryDto {
  sessionId: number;
  ticketTypeId: number;
  quantity: number;
  attendeeIds?: number[];
}

export interface WaitlistEntryRecord {
  id: number;
  userId: number;
  sessionId: number;
  ticketTypeId: number;
  quantity: number;
  attendeeIds: number[];
  seatPreference: unknown | null;
  status: WaitlistEntryStatus;
  priorityNo: number;
  offerOrderId: number | null;
  offerExpireTime: Date | null;
  failReason: string | null;
  createTime: Date;
  updateTime: Date;
}

export interface WaitlistEntryResponse {
  id: number;
  sessionId: number;
  ticketTypeId: number;
  activityId?: number | null;
  activityName?: string | null;
  activityPoster?: string | null;
  ticketTypeName?: string | null;
  venueName?: string | null;
  sessionTime?: string | null;
  quantity: number;
  status: WaitlistEntryStatus;
  rank: number | null;
  estimatedChance: 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN';
  estimatedChanceText: string;
  estimatedWaitText: string;
  offerOrderId: number | null;
  offerExpireTime: string | null;
  failReason: string | null;
}

export interface TicketReleasedEventDto {
  eventKey: string;
  source: 'ORDER_TIMEOUT' | 'ORDER_CANCEL' | 'REFUND' | 'MANUAL';
  sourceOrderId?: number | null;
  sessionId: number;
  ticketTypeId: number;
  quantity: number;
  seatIds?: number[];
}

export interface WaitlistOfferRecord {
  id: number;
  entryId: number;
  userId: number;
  sessionId: number;
  ticketTypeId: number;
  quantity: number;
  orderId: number;
  status: WaitlistOfferStatus;
  expireTime: Date;
  createTime: Date;
  updateTime: Date;
}
