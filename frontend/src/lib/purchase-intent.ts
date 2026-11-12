import type { TicketTypeEntity } from '@/types/api'

type SeatAllocationTicket = Pick<TicketTypeEntity, 'seatBlockId' | 'ticketGroupKey'> | null | undefined
type StockTicket = Pick<TicketTypeEntity, 'remainStock'> | null | undefined
type VisibleStockLike = { visibleStock: number | null } | null | undefined
export type PurchaseConfirmMode = 'purchase' | 'waitlist'
type GrabIntentPreference = {
  ticketTypeId: number
  name?: string | null
  maxPrice?: number | null
}

const TERMINAL_STATUSES_WITHOUT_ORDER = new Set(['SOLD_OUT', 'LIMITED', 'FAILED', 'EXPIRED'])

export function isSeatBoundTicket(ticket: SeatAllocationTicket) {
  return Boolean(ticket && (ticket.seatBlockId != null || ticket.ticketGroupKey))
}

export function buildSeatAllocationPayload(input: {
  ticket: SeatAllocationTicket
  seatSelectionVisible: boolean
  selectedSeatIds: number[]
}) {
  const seatIds = input.seatSelectionVisible ? input.selectedSeatIds : []
  return {
    seatIds,
    allocateRandom: isSeatBoundTicket(input.ticket) && seatIds.length === 0,
  }
}

export function buildGrabIdempotencyIntent(input: {
  userId: number
  sessionId: number
  selectedTicketId: number
  quantity: number
  seatIds: number[]
  attendeeIds?: number[]
  allocateRandom: boolean
  allowAutoDowngrade: boolean
  ticketTypePreferences: GrabIntentPreference[]
}) {
  const seatPart = input.seatIds.slice().sort((a, b) => a - b).join(',')
  const attendeePart = (input.attendeeIds ?? []).slice().sort((a, b) => a - b).join(',')
  const preferencesPart = input.ticketTypePreferences
    .map((ticket) => [
      ticket.ticketTypeId,
      ticket.maxPrice ?? '',
      ticket.name ?? '',
    ].join('|'))
    .join('>')
  return [
    input.userId,
    input.sessionId,
    input.selectedTicketId,
    input.quantity,
    seatPart,
    attendeePart,
    input.allocateRandom,
    input.allowAutoDowngrade,
    preferencesPart,
  ].join(':')
}

export function canShowPurchaseEntry(input: {
  ticket: StockTicket
  visibleStock?: VisibleStockLike
}) {
  if (!input.ticket) return false
  if (input.visibleStock?.visibleStock != null) return input.visibleStock.visibleStock > 0
  return input.ticket.remainStock == null || input.ticket.remainStock > 0
}

export function canShowWaitlistEntry(input: {
  ticket: StockTicket
  visibleStock?: VisibleStockLike
}) {
  return Boolean(input.ticket && !canShowPurchaseEntry(input))
}

export function getPurchaseConfirmCopy(mode: PurchaseConfirmMode) {
  if (mode === 'waitlist') {
    return {
      title: '确认候补',
      totalLabel: '候补金额',
      submitLabel: '确认加入候补',
      submittingLabel: '加入中...',
    }
  }

  return {
    title: '确认订单',
    totalLabel: '合计',
    submitLabel: '确认支付',
    submittingLabel: '提交中...',
  }
}

export function getPurchaseQuantityMax(input: {
  ticket: StockTicket
  visibleStock?: VisibleStockLike
}) {
  const visibleStock = input.visibleStock?.visibleStock
  if (visibleStock != null && visibleStock > 0) return visibleStock
  const remainStock = input.ticket?.remainStock
  return remainStock != null && remainStock > 0 ? remainStock : 1
}

export function shouldResetGrabIdempotencyForStatus(status: string | null | undefined) {
  return Boolean(status && TERMINAL_STATUSES_WITHOUT_ORDER.has(status))
}
