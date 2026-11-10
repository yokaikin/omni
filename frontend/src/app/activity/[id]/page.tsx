'use client'

import { useState, useEffect, use, useMemo, useRef } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { Ban, Bell, CalendarDays, Check, Info, Clock3, Heart, MapPin, MessageCircle, ShieldCheck, Sparkles, Star, Ticket, UserCheck, UserRound, UsersRound } from 'lucide-react'
import { Header } from '@/components/Header'
import { Footer } from '@/components/Footer'
import { FloatingBackButton } from '@/components/FloatingBackButton'
import { SeatCraftSelector } from '@/components/seatcraft-unified/SeatCraftSelector'
import { AlipayQrPayModal } from '@/components/AlipayQrPayModal'
import { globalAlert, globalConfirm, globalPrompt } from '@/components/GlobalDialog'
import { SafeImage } from '@/components/SafeImage'
import { cancelGrabRequest, cancelSubscription, createActivityQuestion, createAlipayPagePay, createSubscription, createUserAttendee, createWaitlistEntry, deleteUserAttendee, getActivityDetail, getGrabProgress, getGrabVisibleStock, getSeatMap, listActivities, listActivityQuestions, listActivityReviews, listSubscriptions, listUserAttendees, recordUserBrowseHistory, reportActivityReview, submitGrabRequest } from '@/lib/api'
import { captureAnalyticsEvent } from '@/lib/analytics'
import { getUser, isAuthenticated } from '@/lib/auth'
import { buildGrabIdempotencyIntent, buildSeatAllocationPayload, canShowPurchaseEntry, canShowWaitlistEntry, getPurchaseConfirmCopy, getPurchaseQuantityMax, getWaitlistQuantityMax, shouldResetGrabIdempotencyForStatus, type PurchaseConfirmMode } from '@/lib/purchase-intent'
import { startGrabProgressPolling } from '@/lib/grab-progress-polling'
import { getCountdownText } from '@/lib/subscription'
import { buildZoomTargetFromTicketGroup, toSeatCraftSelectionModel } from '@/components/seatcraft-unified/adapters'
import { getAutoDowngradeDisplay, getGrabProgressDisplayMessage, getQueueRankTrendLabel, localizeGrabProgressMessage } from '@/lib/grab-progress'
import { canJoinWaitlistFromGrabStatus } from '@/lib/waitlist'
import { toActivitySaleStatus } from '@/lib/activity-sale-status'
import { formatAttendeeSummary, getAttendeeIdTypeLabel, normalizeChineseIdCard, removeAttendeeById, validateAttendeeSelection } from '@/lib/attendees'
import { ACTIVITY_VIEW_SIGNAL_KEY, addActivityViewSignal, parseActivityViewSignals } from '@/lib/personalized-recommendations'
import { findActivitySubscriptionAction, getActivitySubscriptionActionLabel, getActivitySubscriptionActions, removeActivitySubscriptionById, upsertActivitySubscription, type ActivitySubscriptionActionType, type ActivitySubscriptionLike } from '@/lib/activity-actions'
import { buildActivityDetailTabs, type ActivityDetailTabKey } from '@/lib/activity-detail-content'
import { buildActivityDetailRecommendations, type ActivityDetailRecommendation } from '@/lib/activity-recommendations'
import { resolveInitialPurchaseSelection } from '@/lib/activity-detail-selection'
import { requestOpenAiTicketFinder } from '@/lib/ai-ticket-finder-modal'
import type { ActivityDetailVO, ActivityQuestionVO, ActivityReviewListVO, ActivityVO, GrabProgressResult, PagePayResponse, SeatMapResponse, SessionDetail, SessionSeatVO, SessionVisibleStockResult, StationPurchaseDetail, TicketTypeEntity, UserAttendeeVO } from '@/types/api'

const TERMINAL_GRAB_STATUSES = new Set(['ORDER_CREATED', 'SOLD_OUT', 'LIMITED', 'FAILED', 'PENDING_RECOVERY', 'EXPIRED'])
const GRAB_STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  WAITING: '等待处理',
  TRYING_TICKET_TYPE: '正在尝试票档',
  LOCKING: '正在锁票',
  ORDER_CREATING: '正在生成订单',
  ORDER_CREATED: '已生成订单',
  SOLD_OUT: '已售罄',
  DOWNGRADING: '正在尝试后续票档',
  PENDING_RECOVERY: '订单确认中',
  FAILED: '抢票失败',
  LIMITED: '限购失败',
  EXPIRED: '已结束',
}

function formatGrabStatusLabel(status: string) {
  return GRAB_STATUS_LABELS[status] || '状态同步中'
}

function formatActivityQuestionAnswerFallback(status: ActivityQuestionVO['status'] | null | undefined) {
  if (status === 'PENDING') return '已提交，等待回复'
  if (status === 'ANSWERED' || status === 'HIDDEN') return '暂无回复'
  return '问答状态同步中'
}

const GRAB_ATTEMPT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待尝试',
  TRYING: '正在尝试',
  LOCKING: '正在锁票',
  ORDER_CREATED: '已生成订单',
  SOLD_OUT: '已售罄',
  FAILED: '抢票失败',
  LIMITED: '限购失败',
}

function artistSummaryFromDetail(detail: ActivityDetailVO) {
  return detail.artists?.length
    ? detail.artists.map(item => item.name).filter(Boolean).join('、')
    : detail.artist?.name || ''
}

function getActivityDetailMinPrice(detail: ActivityDetailVO) {
  const prices = detail.sessions.flatMap(session => session.ticketTypes.map(ticket => ticket.price)).filter(price => price > 0)
  return prices.length > 0 ? Math.min(...prices) : null
}

function formatCompactDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 16) : '时间待公布'
}

function formatPriceText(value: number) {
  return Number.isInteger(value) ? `¥${value}` : `¥${value.toFixed(2)}`
}

function getTicketPriceRange(sessions: SessionDetail[]) {
  const prices = sessions.flatMap(session => session.ticketTypes.map(ticket => ticket.price)).filter(price => price > 0)
  if (!prices.length) return '票档待公布'
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  return min === max ? formatPriceText(min) : `${formatPriceText(min)} - ${formatPriceText(max)}`
}

function getVenueAddressText(session?: SessionDetail | null) {
  if (!session?.venue) return '场馆地址待公布'
  const parts = [session.venue.city, session.venue.name].filter(Boolean)
  const location = parts.length ? parts.join(' · ') : '场馆待公布'
  return session.venue.address ? `${location}，${session.venue.address}` : location
}

function getRatingStars(rating?: number | null) {
  const normalized = Math.max(0, Math.min(5, Math.round(Number(rating) || 0)))
  return normalized || 5
}

function parseCalendarReminderIds(value: string | null) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    if (!Array.isArray(parsed)) return []
    return parsed.map(Number).filter(item => Number.isSafeInteger(item) && item > 0)
  } catch {
    return []
  }
}

function dedupeActivityCandidates(candidates: ActivityVO[]) {
  const map = new Map<number, ActivityVO>()
  for (const candidate of candidates) {
    if (!map.has(candidate.id)) map.set(candidate.id, candidate)
  }
  return Array.from(map.values())
}

type StationPurchaseState = 'ACTIVE' | 'RESERVING' | 'PENDING' | 'SOLD_OUT'
type StationSessionLike = StationPurchaseDetail['sessions'][number]

function toFiniteNumber(value: unknown) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function isStationSessionDetail(session: StationSessionLike): session is SessionDetail {
  return Boolean(session && typeof session === 'object' && 'session' in session && 'ticketTypes' in session)
}

function getStationSessionId(session: StationSessionLike) {
  return isStationSessionDetail(session) ? session.session.id : session.id
}

function getStationSessionStartTime(session?: StationSessionLike | null) {
  if (!session) return null
  return isStationSessionDetail(session) ? session.session.startTime : session.startTime
}

function isTourEvent(detail: ActivityDetailVO) {
  return detail.isTour === true
    || String(detail.eventType || '').toUpperCase() === 'TOUR'
    || detail.activity.itemType === 'tour'
    || Boolean(detail.tour)
    || Boolean(detail.stationDetails?.length)
}

function getActivityStationDetails(detail: ActivityDetailVO): StationPurchaseDetail[] {
  if (detail.stationDetails?.length) return detail.stationDetails
  if (!isTourEvent(detail)) return []

  const firstSession = detail.sessions[0]
  const city = firstSession?.venue?.city || null
  const prices = detail.sessions.flatMap(session => session.ticketTypes.map(ticket => ticket.price)).filter(price => price > 0)
  const remainStock = detail.sessions
    .flatMap(session => session.ticketTypes)
    .reduce((sum, ticket) => sum + Math.max(0, Number(ticket.remainStock) || 0), 0)
  const status = Number(detail.activity.status)
  const saleStatus = status === 1 ? 'on_sale' : status === 3 ? 'sold_out' : status === 2 ? 'coming_soon' : 'unannounced'

  return [{
    station: {
      id: detail.activity.id,
      tourId: detail.tour?.id ?? null,
      activityId: detail.activity.id,
      city,
      stationName: city ? `${city}站` : null,
      poster: detail.activity.poster,
      description: detail.activity.description,
      publishStatus: detail.activity.publishStatus || (status === 1 ? 'published' : 'city_announced'),
      status,
      createTime: detail.activity.createTime,
    },
    activity: detail.activity,
    sessions: detail.sessions,
    venueName: firstSession?.venue?.name ?? null,
    venueAddress: firstSession?.venue?.address ?? null,
    priceMin: prices.length ? Math.min(...prices) : null,
    priceMax: prices.length ? Math.max(...prices) : null,
    remainStock,
    saleStatus,
    saleStatusText: status === 1 ? '售票中' : status === 3 ? '已售罄' : status === 2 ? '预约中' : '待公布',
    primaryAction: status === 1 ? 'buy' : 'none',
  }]
}

function getStationSessionDetails(detail: ActivityDetailVO, stationDetail: StationPurchaseDetail) {
  const directDetails = stationDetail.sessions.filter(isStationSessionDetail)
  if (directDetails.length) return directDetails

  const stationActivityId = toFiniteNumber(stationDetail.activity?.id ?? stationDetail.station.activityId)
  if (stationActivityId === detail.activity.id) return detail.sessions

  const stationSessionIds = new Set(stationDetail.sessions.map(getStationSessionId).filter(Boolean))
  return detail.sessions.filter(session => stationSessionIds.has(session.session.id))
}

function getStationPurchaseState(stationDetail: StationPurchaseDetail): StationPurchaseState {
  const explicitStatus = String((stationDetail as StationPurchaseDetail & { status?: string | null }).status || '').toUpperCase()
  if (explicitStatus === 'ACTIVE') return 'ACTIVE'
  if (explicitStatus === 'RESERVING') return 'RESERVING'
  if (explicitStatus === 'PENDING') return 'PENDING'

  const saleStatus = String(stationDetail.saleStatus || '').toLowerCase()
  const publishStatus = String(stationDetail.station.publishStatus || '').toLowerCase()
  if (saleStatus === 'sold_out') return 'SOLD_OUT'
  if (saleStatus === 'on_sale' || stationDetail.primaryAction === 'buy') {
    return stationDetail.remainStock === 0 ? 'SOLD_OUT' : 'ACTIVE'
  }
  if (saleStatus === 'coming_soon') return 'RESERVING'
  if (['unannounced', 'ticket_tba', 'to_be_scheduled'].includes(saleStatus)) return 'PENDING'
  if (['draft', 'city_announced', 'venue_pending', 'venue_rejected', 'venue_approved'].includes(publishStatus)) return 'PENDING'
  if (publishStatus === 'published' && stationDetail.activity) return Number(stationDetail.activity.status) === 1 ? 'ACTIVE' : 'RESERVING'
  return 'PENDING'
}

function getStationStatusBadge(stationDetail: StationPurchaseDetail) {
  const state = getStationPurchaseState(stationDetail)
  if (state === 'ACTIVE') return { label: '售票中', className: 'border-[#D6F5E2] bg-[#E8F8EE] text-[#28C76F]' }
  if (state === 'RESERVING') return { label: '预约中', className: 'border-amber-100 bg-amber-50 text-[#F59E0B]' }
  if (state === 'SOLD_OUT') return { label: '缺货登记', className: 'border-[#e5e7eb] bg-[#F3F4F6] text-[#6b7280]' }
  return { label: '待公布', className: 'border-[#e5e7eb] bg-[#F3F4F6] text-[#9CA3AF]' }
}

function getStationCityLabel(stationDetail: StationPurchaseDetail) {
  const label = stationDetail.station.stationName || stationDetail.station.city || '城市待定'
  return label.includes('站') ? label : `${label}站`
}

function formatStationDate(stationDetail: StationPurchaseDetail) {
  const dateParts = stationDetail.sessions
    .map(getStationSessionStartTime)
    .filter(Boolean)
    .map(value => {
      const matched = String(value).match(/^\d{4}-(\d{2})-(\d{2})/)
      return matched ? { month: matched[1], day: matched[2] } : null
    })
    .filter((value): value is { month: string; day: string } => Boolean(value))

  if (!dateParts.length) return '时间待定'
  const [first, ...rest] = dateParts
  return [`${Number(first.month)}.${first.day}`, ...rest.map(item => (
    item.month === first.month ? item.day : `${Number(item.month)}.${item.day}`
  ))].join('/')
}

export default function ActivityDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  const router = useRouter()
  const searchParams = useSearchParams()
  const [detail, setDetail] = useState<ActivityDetailVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedStationId, setSelectedStationId] = useState<number | null>(null)
  const [selectedSession, setSelectedSession] = useState<SessionDetail | null>(null)
  const [selectedTicket, setSelectedTicket] = useState<TicketTypeEntity | null>(null)
  const [quantity, setQuantity] = useState(1)
  const [showConfirm, setShowConfirm] = useState(false)
  const [confirmMode, setConfirmMode] = useState<PurchaseConfirmMode>('purchase')
  const [refundPolicyAccepted, setRefundPolicyAccepted] = useState(false)
  const [ordering, setOrdering] = useState(false)
  const [orderError, setOrderError] = useState('')
  const [showSuccess, setShowSuccess] = useState(false)
  const [successOrderNo, setSuccessOrderNo] = useState('')
  const [pagePay, setPagePay] = useState<PagePayResponse | null>(null)
  const [seatMap, setSeatMap] = useState<SeatMapResponse | null>(null)
  const [seatMapLoading, setSeatMapLoading] = useState(false)
  const [selectedSeatIds, setSelectedSeatIds] = useState<number[]>([])
  const [grabIdempotency, setGrabIdempotency] = useState<{ intent: string; key: string } | null>(null)
  const [allowAutoDowngrade, setAllowAutoDowngrade] = useState(false)
  const [grabProgress, setGrabProgress] = useState<GrabProgressResult | null>(null)
  const [grabProgressOpen, setGrabProgressOpen] = useState(false)
  const [progressPaymentOpening, setProgressPaymentOpening] = useState(false)
  const [waitlistSubmitting, setWaitlistSubmitting] = useState(false)
  const [waitlistMessage, setWaitlistMessage] = useState('')
  const [subscriptionLoading, setSubscriptionLoading] = useState<string | null>(null)
  const [subscriptions, setSubscriptions] = useState<ActivitySubscriptionLike[]>([])
  const [calendarJoinedActivityIds, setCalendarJoinedActivityIds] = useState<number[]>([])
  const [visibleStock, setVisibleStock] = useState<SessionVisibleStockResult | null>(null)
  const [attendees, setAttendees] = useState<UserAttendeeVO[]>([])
  const [attendeesLoading, setAttendeesLoading] = useState(false)
  const [attendeeError, setAttendeeError] = useState('')
  const [selectedAttendeeIds, setSelectedAttendeeIds] = useState<number[]>([])
  const [attendeeSaving, setAttendeeSaving] = useState(false)
  const [attendeeDeletingId, setAttendeeDeletingId] = useState<number | null>(null)
  const [attendeeForm, setAttendeeForm] = useState({ realName: '', idNo: '', phone: '' })
  const [reviewData, setReviewData] = useState<ActivityReviewListVO | null>(null)
  const [questions, setQuestions] = useState<ActivityQuestionVO[]>([])
  const [questionContent, setQuestionContent] = useState('')
  const [questionComposerOpen, setQuestionComposerOpen] = useState(false)
  const [activeDetailTab, setActiveDetailTab] = useState<ActivityDetailTabKey>('project')
  const [recommendations, setRecommendations] = useState<ActivityDetailRecommendation[]>([])
  const [questionSubmitting, setQuestionSubmitting] = useState(false)
  const [reportingReviewId, setReportingReviewId] = useState<number | null>(null)
  const [centerToast, setCenterToast] = useState<{ id: number; message: string } | null>(null)
  const centerToastIdRef = useRef(0)
  const seatMapRequestIdRef = useRef(0)
  const progressPaymentOrderIdRef = useRef<number | null>(null)
  const progressPaymentInFlightOrderIdRef = useRef<number | null>(null)
  const hydratedGrabRequestRef = useRef<string | null>(null)
  const initialPurchaseSelectionAppliedRef = useRef<string | null>(null)
  const loadDetailRef = useRef(() => {})
  const lastRefreshRef = useRef(0)
  const toastTimerRef = useRef<number | null>(null)
  const actionLockRef = useRef<Set<ActivitySubscriptionActionType>>(new Set())

  const seatCraftSelectionModel = useMemo(() => seatMap ? toSeatCraftSelectionModel(seatMap) : null, [seatMap])
  const seatCraftFocusTarget = useMemo(() => {
    if (!seatMap?.layout || selectedTicket?.id == null) return null
    return buildZoomTargetFromTicketGroup(seatMap.layout, selectedTicket.id)
  }, [seatMap?.layout, selectedTicket?.id])
  const detailTabs = useMemo(() => detail ? buildActivityDetailTabs(detail) : null, [detail])
  const detailTabItems = useMemo(() => detailTabs ? [
    ['project', detailTabs.project],
    ['purchase', detailTabs.purchase],
    ['attendance', detailTabs.attendance],
  ] as const : [], [detailTabs])
  const activeDetailContent = detailTabs?.[activeDetailTab]
  const isCurrentTourEvent = useMemo(() => detail ? isTourEvent(detail) : false, [detail])
  const stationDetails = useMemo(() => detail ? getActivityStationDetails(detail) : [], [detail])
  const selectedStationDetail = useMemo(() => {
    if (!stationDetails.length) return null
    return stationDetails.find(item => item.station.id === selectedStationId) || stationDetails[0]
  }, [stationDetails, selectedStationId])
  const stationPurchaseState: StationPurchaseState = selectedStationDetail ? getStationPurchaseState(selectedStationDetail) : 'ACTIVE'
  const activePurchaseSessions = useMemo(() => {
    if (!detail) return []
    if (!isCurrentTourEvent || !selectedStationDetail) return detail.sessions
    return getStationSessionDetails(detail, selectedStationDetail)
  }, [detail, isCurrentTourEvent, selectedStationDetail])
  const showsSeatCraftSelection = Boolean(seatMap?.layout && (seatMap.layout.blockLayout?.blocks?.length || seatMap.layout.blocks?.length) && seatCraftSelectionModel)
  const availableSeatIdSet = useMemo(() => {
    if (!seatMap) return null
    const ids = seatCraftSelectionModel?.availableSeatIds
      ?? seatMap.seats
        .filter(seat => seat.status === 1 && (seat.ticketTypeId == null || seat.ticketTypeId === selectedTicket?.id))
        .map(seat => seat.id)
    return new Set(ids)
  }, [seatCraftSelectionModel, seatMap, selectedTicket?.id])
  const validSelectedSeatIds = useMemo(
    () => availableSeatIdSet ? selectedSeatIds.filter(id => availableSeatIdSet.has(id)) : selectedSeatIds,
    [availableSeatIdSet, selectedSeatIds],
  )
  const seatMapPublished = detail?.activity.seatMapVisibility === 'published'
  const buildTicketTypePreferences = () => {
    if (!selectedSession || !selectedTicket) return []
    const sorted = selectedSession.ticketTypes
      .filter(ticket => ticket.id === selectedTicket.id || (allowAutoDowngrade && !showsSeatCraftSelection && ticket.price <= selectedTicket.price))
      .sort((a, b) => {
        if (a.id === selectedTicket.id) return -1
        if (b.id === selectedTicket.id) return 1
        return b.price - a.price
      })
    return sorted.map(ticket => ({ ticketTypeId: ticket.id, name: ticket.name, maxPrice: ticket.price }))
  }
  const downgradeCandidates = selectedSession && selectedTicket && !showsSeatCraftSelection
    ? selectedSession.ticketTypes
      .filter(ticket => ticket.id !== selectedTicket.id && ticket.price <= selectedTicket.price)
      .sort((a, b) => b.price - a.price)
    : []
  const currentProgressStock = grabProgress?.currentTicketTypeId != null && visibleStock
    ? visibleStock.ticketTypes.find(ticket => ticket.ticketTypeId === grabProgress.currentTicketTypeId)
    : null
  const realNameRequired = true
  const selectedAttendeeSummary = useMemo(
    () => formatAttendeeSummary(attendees, selectedAttendeeIds),
    [attendees, selectedAttendeeIds],
  )
  const selectedTicketVisibleStock = selectedTicket && visibleStock
    ? visibleStock.ticketTypes.find(ticket => ticket.ticketTypeId === selectedTicket.id)
    : null
  const showPurchaseEntry = canShowPurchaseEntry({
    ticket: selectedTicket,
    visibleStock: selectedTicketVisibleStock,
  })
  const showWaitlistEntry = canShowWaitlistEntry({
    ticket: selectedTicket,
    visibleStock: selectedTicketVisibleStock,
  })
  const purchaseQuantityMax = getPurchaseQuantityMax({
    ticket: selectedTicket,
    visibleStock: selectedTicketVisibleStock,
  })
  const waitlistQuantityMax = getWaitlistQuantityMax(detail?.activity.perUserLimit)
  const actionQuantityMax = showWaitlistEntry ? waitlistQuantityMax : purchaseQuantityMax
  const confirmCopy = getPurchaseConfirmCopy(confirmMode)
  const confirmSubmitting = confirmMode === 'waitlist' ? waitlistSubmitting : ordering
  const getActiveGrabStorageKey = () => {
    const user = getUser()
    return user ? `grab:active-request:${user.userId}:${id}` : null
  }
  const rememberActiveGrabRequest = (requestId: string) => {
    const key = getActiveGrabStorageKey()
    if (key) window.localStorage.setItem(key, requestId)
  }
  const forgetActiveGrabRequest = () => {
    const key = getActiveGrabStorageKey()
    if (key) window.localStorage.removeItem(key)
  }
  const getCalendarReminderStorageKey = () => {
    const user = getUser()
    return user ? `activity-calendar-reminders:${user.userId}` : null
  }
  const persistCalendarReminderIds = (activityIds: number[]) => {
    const key = getCalendarReminderStorageKey()
    if (!key) return
    window.localStorage.setItem(key, JSON.stringify(activityIds))
  }
  const showCenterToast = (message: string) => {
    if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current)
    centerToastIdRef.current += 1
    setCenterToast({ id: centerToastIdRef.current, message })
    toastTimerRef.current = window.setTimeout(() => {
      setCenterToast(null)
      toastTimerRef.current = null
    }, 1500)
  }

  const loadRecommendations = async (data: ActivityDetailVO) => {
    const city = data.sessions[0]?.venue?.city || ''
    const viewSignals = typeof window !== 'undefined'
      ? parseActivityViewSignals(localStorage.getItem(ACTIVITY_VIEW_SIGNAL_KEY))
      : []

    try {
      const requests = [
        listActivities({ page: 1, size: 40, categoryId: data.activity.categoryId, city }),
        listActivities({ page: 1, size: 40, categoryId: data.activity.categoryId }),
      ]
      if (city) {
        requests.push(listActivities({ page: 1, size: 40, city }))
      }

      const results = await Promise.allSettled(requests)
      const candidates = dedupeActivityCandidates(results.flatMap(result => result.status === 'fulfilled' ? result.value.records : []))
      const nextRecommendations = buildActivityDetailRecommendations(candidates, {
        activityId: data.activity.id,
        categoryName: data.category?.name || null,
        artistName: artistSummaryFromDetail(data) || null,
        city: city || null,
        startTime: data.sessions[0]?.session.startTime || null,
        minPrice: getActivityDetailMinPrice(data),
        viewSignals,
      })
      setRecommendations(nextRecommendations)
    } catch {
      setRecommendations([])
    }
  }

  const loadDetail = async () => {
    setLoading(true)
    setError('')
    setRecommendations([])
    setSelectedStationId(null)
    setSelectedSession(null)
    setSelectedTicket(null)
    setAllowAutoDowngrade(false)
    try {
      const data = await getActivityDetail(Number(id))
      setDetail(data)
      void loadRecommendations(data)
      if (typeof window !== 'undefined') {
        const firstVenue = data.sessions[0]?.venue?.city || ''
        captureAnalyticsEvent('omni_activity_detail_viewed', {
          activity_id: data.activity.id,
          city: firstVenue,
          category_id: data.category?.id,
          sale_status: toActivitySaleStatus(data.activity.status),
        })
        const next = addActivityViewSignal(parseActivityViewSignals(localStorage.getItem(ACTIVITY_VIEW_SIGNAL_KEY)), {
          activityId: String(data.activity.id),
          title: data.activity.name,
          poster: data.activity.poster,
          category: data.category?.name || null,
          artist: artistSummaryFromDetail(data) || null,
          city: firstVenue || null,
          status: data.activity.status,
          viewedAt: new Date().toISOString(),
        })
        localStorage.setItem(ACTIVITY_VIEW_SIGNAL_KEY, JSON.stringify(next))
        if (isAuthenticated()) {
          void recordUserBrowseHistory({
            activityId: data.activity.id,
            activityName: data.activity.name,
            poster: data.activity.poster,
            category: data.category?.name || null,
            artist: artistSummaryFromDetail(data) || null,
            city: firstVenue || null,
          }).catch(() => undefined)
        }
      }
      const initialStationDetail = isTourEvent(data) ? getActivityStationDetails(data)[0] ?? null : null
      const initialStationState = initialStationDetail ? getStationPurchaseState(initialStationDetail) : 'ACTIVE'
      const initialPurchaseSessions = initialStationDetail ? getStationSessionDetails(data, initialStationDetail) : data.sessions
      setSelectedStationId(initialStationDetail?.station.id ?? null)
      if (initialStationState !== 'PENDING' && initialPurchaseSessions.length > 0) {
        const initialSelection = initialPurchaseSelectionAppliedRef.current === id
          ? resolveInitialPurchaseSelection(initialPurchaseSessions, null, null)
          : resolveInitialPurchaseSelection(
            initialPurchaseSessions,
            searchParams.get('sessionId'),
            searchParams.get('ticketTypeId'),
          )
        setSelectedSession(initialSelection.session)
        setSelectedTicket(initialSelection.ticket)
      }
      initialPurchaseSelectionAppliedRef.current = id
    } catch (err: unknown) {
      setDetail(null)
      setRecommendations([])
      setError(err instanceof Error ? err.message : '加载活动失败')
    } finally {
      setLoading(false)
    }
  }

  loadDetailRef.current = loadDetail

  const refreshWhenVisible = () => {
    const now = Date.now()
    if (now - lastRefreshRef.current < 200) return
    lastRefreshRef.current = now
    void loadDetailRef.current()
  }

  useEffect(() => {
    void loadDetail()
  }, [id])

  useEffect(() => {
    return () => {
      if (toastTimerRef.current) {
        window.clearTimeout(toastTimerRef.current)
        toastTimerRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    if (!detail || !isAuthenticated()) {
      setCalendarJoinedActivityIds([])
      return
    }
    const key = getCalendarReminderStorageKey()
    setCalendarJoinedActivityIds(key ? parseCalendarReminderIds(window.localStorage.getItem(key)) : [])
  }, [detail?.activity.id])

  useEffect(() => {
    let cancelled = false

    if (!detail || !isAuthenticated()) {
      setSubscriptions([])
      return
    }

    listSubscriptions()
      .then(data => {
        if (!cancelled) setSubscriptions(data || [])
      })
      .catch(() => {
        if (!cancelled) setSubscriptions([])
      })

    return () => {
      cancelled = true
    }
  }, [detail?.activity.id])

  const loadReviewsAndQuestions = async () => {
    const activityId = Number(id)
    try {
      const [reviews, questionList] = await Promise.all([
        listActivityReviews(activityId),
        listActivityQuestions(activityId),
      ])
      setReviewData(reviews)
      setQuestions(questionList)
    } catch {
      setReviewData(null)
      setQuestions([])
    }
  }

  useEffect(() => {
    void loadReviewsAndQuestions()
  }, [id])

  useEffect(() => {
    const handlePageShow = (event: PageTransitionEvent) => {
      if (event.persisted) refreshWhenVisible()
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') refreshWhenVisible()
    }

    window.addEventListener('pageshow', handlePageShow)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
      window.removeEventListener('pageshow', handlePageShow)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [])

  useEffect(() => {
    const requestId = ++seatMapRequestIdRef.current
    let cancelled = false

    if (!selectedSession || !selectedTicket || !seatMapPublished) {
      setSeatMap(null)
      setSelectedSeatIds([])
      setSeatMapLoading(false)
      return
    }
    setSeatMapLoading(true)
    setSelectedSeatIds([])
    getSeatMap(selectedSession.session.id, selectedTicket.id)
      .then((data) => {
        if (cancelled || seatMapRequestIdRef.current !== requestId) return
        setSeatMap(data)
      })
      .catch(() => {
        if (cancelled || seatMapRequestIdRef.current !== requestId) return
        setSeatMap(null)
      })
      .finally(() => {
        if (cancelled || seatMapRequestIdRef.current !== requestId) return
        setSeatMapLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [selectedSession, selectedTicket, seatMapPublished])

  useEffect(() => {
    if (!selectedSession?.ticketTypes.length) {
      setVisibleStock(null)
      return
    }

    let cancelled = false
    const ids = selectedSession.ticketTypes.map(ticket => ticket.id)
    getGrabVisibleStock(selectedSession.session.id, ids)
      .then((stock) => {
        if (!cancelled) setVisibleStock(stock)
      })
      .catch(() => {
        if (!cancelled) setVisibleStock(null)
      })

    return () => {
      cancelled = true
    }
  }, [selectedSession])

  useEffect(() => {
    if (!grabProgressOpen || !grabProgress?.requestId || TERMINAL_GRAB_STATUSES.has(grabProgress.status)) return

    let cancelled = false
    const fetchProgress = () => {
      getGrabProgress(grabProgress.requestId)
        .then((progress) => {
          if (!cancelled) {
            setGrabProgress((prev) => ({
              ...progress,
              queueRankPrevious: prev?.queueRank ?? null,
            }))
          }
        })
        .catch((err: unknown) => {
          if (!cancelled) setOrderError(err instanceof Error ? err.message : '抢票进度查询失败')
        })
    }

    const stopPolling = startGrabProgressPolling(fetchProgress)
    return () => {
      cancelled = true
      stopPolling()
    }
  }, [grabProgressOpen, grabProgress?.requestId, grabProgress?.status])

  const resetGrabIdempotencyKey = () => setGrabIdempotency(null)

  const handleSelectStation = (stationDetail: StationPurchaseDetail) => {
    if (!detail) return
    const nextState = getStationPurchaseState(stationDetail)
    const nextSessions = getStationSessionDetails(detail, stationDetail)
    setSelectedStationId(stationDetail.station.id)
    setQuantity(1)
    setSelectedSeatIds([])
    setSelectedAttendeeIds([])
    setAllowAutoDowngrade(false)
    setOrderError('')
    setWaitlistMessage('')
    resetGrabIdempotencyKey()

    if (nextState === 'PENDING' || nextSessions.length === 0) {
      setSelectedSession(null)
      setSelectedTicket(null)
      return
    }

    const nextSession = nextSessions[0]
    setSelectedSession(nextSession)
    setSelectedTicket(nextSession.ticketTypes[0] ?? null)
  }

  const openProgressPayment = async (orderId: number) => {
    if (progressPaymentOrderIdRef.current === orderId || progressPaymentInFlightOrderIdRef.current === orderId) return

    progressPaymentInFlightOrderIdRef.current = orderId
    setProgressPaymentOpening(true)
    setOrderError('')
    try {
      const pay = await createAlipayPagePay(orderId)
      progressPaymentOrderIdRef.current = orderId
      setPagePay(pay)
      setGrabProgressOpen(false)
      setShowConfirm(false)
      resetGrabIdempotencyKey()
      forgetActiveGrabRequest()
    } catch (err: unknown) {
      setOrderError(err instanceof Error ? err.message : '支付创建失败')
    } finally {
      progressPaymentInFlightOrderIdRef.current = null
      setProgressPaymentOpening(false)
    }
  }

  const goToOrdersFromProgress = () => {
    forgetActiveGrabRequest()
    router.push('/orders')
  }

  useEffect(() => {
    if (!grabProgressOpen || grabProgress?.status !== 'ORDER_CREATED' || !grabProgress.orderId) return

    void openProgressPayment(grabProgress.orderId)
  }, [grabProgressOpen, grabProgress?.status, grabProgress?.orderId])

  useEffect(() => {
    if (showsSeatCraftSelection && allowAutoDowngrade) {
      setAllowAutoDowngrade(false)
    }
  }, [showsSeatCraftSelection, allowAutoDowngrade])

  useEffect(() => {
    if (quantity <= actionQuantityMax) return
    setQuantity(actionQuantityMax)
    setSelectedSeatIds(ids => ids.slice(0, actionQuantityMax))
    setSelectedAttendeeIds(ids => ids.slice(0, actionQuantityMax))
    resetGrabIdempotencyKey()
  }, [actionQuantityMax, quantity])

  useEffect(() => {
    setSelectedAttendeeIds(ids => ids.slice(0, quantity))
    resetGrabIdempotencyKey()
  }, [quantity])

  useEffect(() => {
    if (!realNameRequired || !isAuthenticated()) {
      setAttendees([])
      setSelectedAttendeeIds([])
      setAttendeesLoading(false)
      return
    }

    let cancelled = false
    setAttendeesLoading(true)
    setAttendeeError('')
    listUserAttendees()
      .then((data) => {
        if (cancelled) return
        setAttendees(data)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setAttendeeError(err instanceof Error ? err.message : '实名观演人加载失败')
      })
      .finally(() => {
        if (!cancelled) setAttendeesLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [realNameRequired])

  useEffect(() => {
    const available = new Set(attendees.map(attendee => attendee.id))
    setSelectedAttendeeIds(ids => ids.filter(id => available.has(id)).slice(0, quantity))
  }, [attendees, quantity])

  useEffect(() => {
    if (!detail || grabProgress?.requestId) return
    const key = getActiveGrabStorageKey()
    const requestId = key ? window.localStorage.getItem(key) : null
    if (!requestId || hydratedGrabRequestRef.current === requestId) return

    hydratedGrabRequestRef.current = requestId
    getGrabProgress(requestId)
      .then((progress) => {
        const belongsToActivity = [...detail.sessions, ...activePurchaseSessions].some((session) => session.session.id === progress.sessionId)
        if (!belongsToActivity) {
          forgetActiveGrabRequest()
          return
        }
        setGrabProgress(progress)
        setGrabProgressOpen(true)
      })
      .catch(() => {
        forgetActiveGrabRequest()
      })
  }, [detail, activePurchaseSessions, grabProgress?.requestId])

  useEffect(() => {
    if (!grabProgress?.requestId) return
    if (shouldResetGrabIdempotencyForStatus(grabProgress.status)) {
      forgetActiveGrabRequest()
      resetGrabIdempotencyKey()
      return
    }
    rememberActiveGrabRequest(grabProgress.requestId)
  }, [grabProgress?.requestId, grabProgress?.status])

  const handleBuy = () => {
    if (!isAuthenticated()) {
      router.push(`/login?ru=/activity/${id}`)
      return
    }
    if (!selectedTicket) return
    setOrderError('')
    setWaitlistMessage('')
    setConfirmMode('purchase')
    setRefundPolicyAccepted(false)
    captureAnalyticsEvent('omni_order_create_clicked', {
      activity_id: detail?.activity.id ?? Number(id),
      ticket_type_id: selectedTicket.id,
      source: 'activity_detail',
    })
    setShowConfirm(true)
  }

  const handleWaitlistEntry = () => {
    if (!isAuthenticated()) {
      router.push(`/login?ru=/activity/${id}`)
      return
    }
    if (!selectedTicket) return
    setOrderError('')
    setWaitlistMessage('')
    setConfirmMode('waitlist')
    setRefundPolicyAccepted(true)
    captureAnalyticsEvent('omni_waitlist_clicked', {
      activity_id: detail?.activity.id ?? Number(id),
      ticket_type_id: selectedTicket.id,
      source: 'activity_detail',
    })
    setShowConfirm(true)
  }

  const closeConfirmDialog = () => {
    if (ordering || waitlistSubmitting) return
    setShowConfirm(false)
    setConfirmMode('purchase')
    setRefundPolicyAccepted(false)
    resetGrabIdempotencyKey()
  }

  const handleAutoSelectSeats = () => {
    if (!seatMap) return
    const available = seatMap.seats.filter(seat => seat.status === 1 && (seat.ticketTypeId == null || seat.ticketTypeId === selectedTicket?.id))
    if (seatMap.layout && seatMap.layout.sections.length > 0) {
      const bySection = new Map<string, SessionSeatVO[]>()
      for (const seat of available) {
        const key = seat.layoutSectionId != null ? `L-${seat.layoutSectionId}-${seat.rowNo}` : `${seat.areaId}-${seat.rowNo}`
        bySection.set(key, [...(bySection.get(key) || []), seat])
      }
      for (const rowSeats of bySection.values()) {
        const sorted = rowSeats.sort((a, b) => a.seatNo - b.seatNo)
        for (let i = 0; i <= sorted.length - quantity; i++) {
          const candidate = sorted.slice(i, i + quantity)
          const continuous = candidate.every((seat, index) => index === 0 || seat.seatNo === candidate[index - 1].seatNo + 1)
          if (continuous) {
            setSelectedSeatIds(candidate.map(seat => seat.id))
            resetGrabIdempotencyKey()
            return
          }
        }
      }
    }
    setSelectedSeatIds(available.slice(0, quantity).map(seat => seat.id))
    resetGrabIdempotencyKey()
  }

  const buildGrabIntent = (userId: number) => {
    const allocation = buildSeatAllocationPayload({
      ticket: selectedTicket,
      seatSelectionVisible: showsSeatCraftSelection,
      selectedSeatIds: validSelectedSeatIds,
    })
    const ticketTypePreferences = buildTicketTypePreferences()
    return buildGrabIdempotencyIntent({
      userId,
      sessionId: selectedSession?.session.id ?? 0,
      selectedTicketId: selectedTicket?.id ?? 0,
      quantity,
      seatIds: allocation.seatIds,
      attendeeIds: selectedAttendeeIds,
      allocateRandom: allocation.allocateRandom,
      allowAutoDowngrade: allowAutoDowngrade && !showsSeatCraftSelection,
      ticketTypePreferences,
    })
  }

  const getOrCreateGrabIdempotencyKey = (userId: number) => {
    const intent = buildGrabIntent(userId)
    if (grabIdempotency?.intent === intent) return grabIdempotency.key
    const key = `${intent}:${Date.now()}:${Math.random().toString(36).slice(2)}`
    setGrabIdempotency({ intent, key })
    return key
  }

  const toggleAttendeeSelection = (attendeeId: number) => {
    setSelectedAttendeeIds(current => {
      if (current.includes(attendeeId)) return current.filter(id => id !== attendeeId)
      if (current.length >= quantity) return current
      return [...current, attendeeId]
    })
    resetGrabIdempotencyKey()
  }

  const handleCreateAttendee = async () => {
    const realName = attendeeForm.realName.trim()
    const idNo = normalizeChineseIdCard(attendeeForm.idNo)
    const phone = attendeeForm.phone.trim()
    if (!realName || !idNo) {
      setAttendeeError('请填写观演人姓名和证件号')
      return
    }

    setAttendeeSaving(true)
    setAttendeeError('')
    try {
      const created = await createUserAttendee({
        realName,
        idType: 'ID_CARD',
        idNo,
        phone: phone || null,
      })
      setAttendees(current => [created, ...current.filter(attendee => attendee.id !== created.id)])
      setSelectedAttendeeIds(current => (
        current.includes(created.id) || current.length >= quantity
          ? current
          : [...current, created.id]
      ))
      setAttendeeForm({ realName: '', idNo: '', phone: '' })
      resetGrabIdempotencyKey()
    } catch (err: unknown) {
      setAttendeeError(err instanceof Error ? err.message : '新增实名观演人失败')
    } finally {
      setAttendeeSaving(false)
    }
  }

  const handleDeleteAttendee = async (attendee: UserAttendeeVO) => {
    const confirmed = await globalConfirm(`确认删除实名观演人“${attendee.realName}”？删除后不会影响已生成订单。`, '删除实名观演人')
    if (!confirmed) return

    setAttendeeDeletingId(attendee.id)
    setAttendeeError('')
    try {
      await deleteUserAttendee(attendee.id)
      setAttendees(current => removeAttendeeById(current, [], attendee.id).attendees)
      setSelectedAttendeeIds(current => removeAttendeeById([], current, attendee.id).selectedAttendeeIds)
      resetGrabIdempotencyKey()
    } catch (err: unknown) {
      setAttendeeError(err instanceof Error ? err.message : '删除实名观演人失败')
    } finally {
      setAttendeeDeletingId(null)
    }
  }

  const handleConfirmOrder = async () => {
    if (!selectedSession || !selectedTicket) return
    const user = getUser()
    if (!user) { router.push(`/login?ru=/activity/${id}`); return }

    setOrdering(true)
    setOrderError('')
    setWaitlistMessage('')
    try {
      const allocation = buildSeatAllocationPayload({
        ticket: selectedTicket,
        seatSelectionVisible: showsSeatCraftSelection,
        selectedSeatIds: validSelectedSeatIds,
      })
      if (showsSeatCraftSelection && validSelectedSeatIds.length !== quantity) {
        setOrderError('请选择对应数量的座位')
        return
      }
      const attendeeValidation = validateAttendeeSelection(realNameRequired, selectedAttendeeIds, quantity)
      if (attendeeValidation) {
        setOrderError(attendeeValidation)
        return
      }
      const idempotencyKey = getOrCreateGrabIdempotencyKey(user.userId)
      const ticketTypePreferences = buildTicketTypePreferences()
      const grab = await submitGrabRequest({
        sessionId: selectedSession.session.id,
        ticketTypeId: selectedTicket.id,
        ticketTypePreferences,
        allowAutoDowngrade: allowAutoDowngrade && !showsSeatCraftSelection,
        seatIds: allocation.seatIds,
        attendeeIds: selectedAttendeeIds,
        quantity,
        allocateRandom: allocation.allocateRandom,
        idempotencyKey,
      })
      setGrabProgress({
        ...grab,
        sessionId: selectedSession.session.id,
        queueSeq: grab.queueSeq ?? null,
        queueRank: grab.queueRank ?? null,
        estimatedWaitSeconds: grab.estimatedWaitSeconds ?? null,
        currentTicketTypeId: selectedTicket.id,
        currentAttemptIndex: 0,
        requestedTicketTypes: ticketTypePreferences.map(ticket => ({
          ticketTypeId: ticket.ticketTypeId,
          name: ticket.name ?? null,
          maxPrice: ticket.maxPrice ?? null,
        })),
        allowAutoDowngrade: allowAutoDowngrade && !showsSeatCraftSelection,
        attempts: ticketTypePreferences.map((ticket, index) => ({
          ticketTypeId: ticket.ticketTypeId,
          name: ticket.name ?? null,
          status: index === 0 && grab.status !== 'QUEUED' ? 'TRYING' : 'PENDING',
          message: index === 0 ? `等待尝试 ${ticket.name ?? '票档信息待同步'}` : '待尝试',
        })),
        visibleStock: null,
        fairnessNotes: [
          '同一账号相同购票意图会复用已有排队请求，避免重复挤占队列。',
          '活动限购规则会在锁票和创建订单阶段校验。',
          '异常高频请求会触发风控拦截，请使用当前页面正常刷新。',
        ],
        message: grab.message ?? null,
        matchedTicketTypeId: null,
        updateTime: new Date().toISOString(),
      })
      progressPaymentOrderIdRef.current = null
      progressPaymentInFlightOrderIdRef.current = null
      rememberActiveGrabRequest(grab.requestId)
      if (grab.orderId || grab.status === 'ORDER_CREATED') {
        captureAnalyticsEvent('omni_order_created', {
          activity_id: detail?.activity.id ?? Number(id),
          payment_required: true,
          source: 'activity_detail',
        })
      }
      setGrabProgressOpen(true)
      setShowConfirm(false)
    } catch (err: unknown) {
      setOrderError(err instanceof Error ? err.message : '下单失败，请确认已登录并重试')
    } finally {
      setOrdering(false)
    }
  }

  const handleCancelGrabProgress = async () => {
    if (!grabProgress?.requestId) return
    try {
      const cancelled = await cancelGrabRequest(grabProgress.requestId)
      setGrabProgress((prev) => prev ? {
        ...prev,
        ...cancelled,
        queueSeq: cancelled.queueSeq ?? prev.queueSeq,
        queueRank: cancelled.queueRank ?? prev.queueRank,
        estimatedWaitSeconds: cancelled.estimatedWaitSeconds ?? prev.estimatedWaitSeconds,
        message: cancelled.message ?? cancelled.failReason ?? '已取消抢票',
        updateTime: new Date().toISOString(),
      } : prev)
    } catch (err: unknown) {
      setOrderError(err instanceof Error ? err.message : '取消抢票失败')
    }
  }

  // 加载态
  const handleJoinWaitlist = async (options: { closeConfirm?: boolean } = {}) => {
    if (!selectedSession || !selectedTicket) return
    const user = getUser()
    if (!user) { router.push(`/login?ru=/activity/${id}`); return }

    setWaitlistSubmitting(true)
    setOrderError('')
    setWaitlistMessage('')
    try {
      const attendeeValidation = validateAttendeeSelection(realNameRequired, selectedAttendeeIds, quantity)
      if (attendeeValidation) {
        setOrderError(attendeeValidation)
        return
      }
      const entry = await createWaitlistEntry({
        sessionId: selectedSession.session.id,
        ticketTypeId: selectedTicket.id,
        quantity,
        attendeeIds: selectedAttendeeIds,
      })
      setWaitlistMessage(entry.rank != null ? `已加入候补，当前约第 ${entry.rank} 位` : '已加入候补')
      if (options.closeConfirm) {
        setShowConfirm(false)
        setConfirmMode('purchase')
      }
    } catch (err: unknown) {
      setOrderError(err instanceof Error ? err.message : '加入候补失败')
    } finally {
      setWaitlistSubmitting(false)
    }
  }

  const handleSubscription = async (
    targetType: Exclude<ActivitySubscriptionActionType, 'CALENDAR'>,
    existingSubscription?: ActivitySubscriptionLike | null,
    messages?: { success?: string; cancel?: string },
  ) => {
    if (!isAuthenticated()) {
      router.push(`/login?ru=/activity/${id}`)
      return
    }
    if (!detail) return
    if (targetType === 'ARTIST_FOLLOW' && !detail.artist?.id) {
      showCenterToast('当前活动暂无可关注艺人')
      return
    }
    if (actionLockRef.current.has(targetType)) return
    actionLockRef.current.add(targetType)
    setSubscriptionLoading(targetType)
    try {
      if (existingSubscription?.id) {
        await cancelSubscription(existingSubscription.id)
        setSubscriptions(prev => removeActivitySubscriptionById(prev, existingSubscription.id))
        const message = targetType === 'ACTIVITY_WANT'
          ? '已取消想看'
          : targetType === 'SALE_REMINDER'
            ? messages?.cancel ?? '开售提醒已关闭'
            : '已取消关注'
        showCenterToast(message)
        return
      }

      const subscription = await createSubscription({
        targetType,
        targetId: targetType === 'ARTIST_FOLLOW' ? detail.artist.id : detail.activity.id,
        activityId: targetType === 'ARTIST_FOLLOW' ? null : detail.activity.id,
        artistId: detail.artist?.id ?? null,
      })
      setSubscriptions(prev => upsertActivitySubscription(prev, subscription))
      if (targetType === 'ACTIVITY_WANT') {
        captureAnalyticsEvent('omni_interest_clicked', {
          activity_id: detail.activity.id,
          source: 'activity_detail',
        })
      } else if (targetType === 'SALE_REMINDER') {
        captureAnalyticsEvent('omni_sale_reminder_clicked', {
          activity_id: detail.activity.id,
          source: 'activity_detail',
        })
      }
      const message = targetType === 'ACTIVITY_WANT'
        ? '已标记想看'
        : targetType === 'SALE_REMINDER'
          ? messages?.success ?? '开售提醒已开启'
          : '关注成功'
      showCenterToast(message)
    } catch (err) {
      showCenterToast(err instanceof Error ? err.message : '操作失败')
    } finally {
      actionLockRef.current.delete(targetType)
      setSubscriptionLoading(null)
    }
  }

  const handleCalendarReminder = () => {
    if (!isAuthenticated()) {
      router.push(`/login?ru=/activity/${id}`)
      return
    }
    if (!detail) return
    if (actionLockRef.current.has('CALENDAR')) return
    actionLockRef.current.add('CALENDAR')
    setSubscriptionLoading('CALENDAR')
    try {
      const activityId = detail.activity.id
      const exists = calendarJoinedActivityIds.includes(activityId)
      const next = exists
        ? calendarJoinedActivityIds.filter(item => item !== activityId)
        : [...calendarJoinedActivityIds, activityId]
      setCalendarJoinedActivityIds(next)
      persistCalendarReminderIds(next)
      showCenterToast(exists ? '已移出日程提醒' : '已加入日程提醒')
    } catch {
      showCenterToast('日程提醒更新失败')
    } finally {
      actionLockRef.current.delete('CALENDAR')
      setSubscriptionLoading(null)
    }
  }

  const handleSubmitQuestion = async () => {
    if (!isAuthenticated()) {
      router.push(`/login?ru=/activity/${id}`)
      return
    }
    const content = questionContent.trim()
    if (!content) {
      await globalAlert('请填写问题内容')
      return
    }
    setQuestionSubmitting(true)
    try {
      await createActivityQuestion(Number(id), content)
      setQuestionContent('')
      setQuestionComposerOpen(false)
      await loadReviewsAndQuestions()
      showCenterToast('问题已提交，等待主办方回复')
    } catch (err) {
      await globalAlert(err instanceof Error ? err.message : '提交问题失败')
    } finally {
      setQuestionSubmitting(false)
    }
  }

  const handleReportReview = async (reviewId?: number | null) => {
    if (!reviewId) return
    if (!isAuthenticated()) {
      router.push(`/login?ru=/activity/${id}`)
      return
    }
    const reason = await globalPrompt('请填写举报原因')
    if (!reason?.trim()) return
    setReportingReviewId(reviewId)
    try {
      await reportActivityReview(Number(id), reviewId, reason.trim())
      await globalAlert('举报已提交，平台会尽快处理')
    } catch (err) {
      await globalAlert(err instanceof Error ? err.message : '举报提交失败')
    } finally {
      setReportingReviewId(null)
    }
  }

  if (loading) {
    return (
      <>
        <Header />
        <div className="max-w-[1200px] mx-auto px-5 py-20 text-center text-[#999] text-sm">加载中...</div>
        <Footer />
      </>
    )
  }

  // 错误态
  if (error || !detail) {
    return (
      <>
        <Header />
        <div className="max-w-[1200px] mx-auto px-5 py-20 text-center">
          <p className="text-[#999] text-sm mb-4">{error || '活动不存在'}</p>
          <button onClick={() => router.back()} className="text-[#ff1268] text-sm cursor-pointer border-none bg-transparent outline-none">返回</button>
        </div>
        <Footer />
      </>
    )
  }

  const { activity, category, artist, sessions } = detail
  const artistSummary = detail.artists?.length
    ? detail.artists.map(item => item.roleName ? `${item.name}（${item.roleName}）` : item.name).filter(Boolean).join('、')
    : artist?.name
  const subscriptionActions = getActivitySubscriptionActions(activity)
  const saleReminderSubscription = findActivitySubscriptionAction('SALE_REMINDER', subscriptions, {
    activityId: activity.id,
    artistId: artist?.id ?? null,
  })
  const saleReminderActive = Boolean(saleReminderSubscription)
  const activeTicketPriceRange = getTicketPriceRange(activePurchaseSessions.length ? activePurchaseSessions : sessions)
  const stationCityLabel = selectedStationDetail ? getStationCityLabel(selectedStationDetail) : ''
  const showStationPendingEmptyState = isCurrentTourEvent && stationPurchaseState === 'PENDING'
  const purchaseCtaLabel = stationPurchaseState === 'RESERVING' ? '立即预约' : '立即购买'
  const primarySession = selectedSession ?? activePurchaseSessions[0] ?? sessions[0] ?? null
  const displayTitle = isCurrentTourEvent ? detail.tour?.title || activity.name : activity.name
  const displayPoster = selectedStationDetail?.station.poster || selectedStationDetail?.activity?.poster || detail.tour?.poster || activity.poster
  const displayDescription = (isCurrentTourEvent ? detail.tour?.description || activity.description : activity.description)?.trim()
  const projectIntro = displayDescription
    || activeDetailContent?.sections.find(section => section.title === '演出介绍')?.items[0]
    || `${displayTitle} 的演出信息正在同步，请以现场安排和票面信息为准。`
  const projectInfoCards = [
    { title: '场馆地址', value: getVenueAddressText(primarySession), icon: MapPin },
    { title: '演出时间', value: formatCompactDateTime(primarySession?.session.startTime), icon: Clock3 },
    { title: '演出阵容 / 类型', value: `${artistSummary || '演出阵容待公布'} · ${category?.name || '暂未分类'}`, icon: UsersRound },
    { title: '票档区间', value: activeTicketPriceRange, icon: Ticket },
  ]
  const purchaseRulePills = [
    { title: '限购政策', value: activity.perUserLimit && activity.perUserLimit > 0 ? `每单限 ${activity.perUserLimit} 张` : '以提交订单为准' },
    { title: '实名规则', value: '强制实名' },
    { title: '转赠支持', value: activity.ticketTransferAllowed ? '支持电子转赠' : '暂不支持转赠' },
    { title: '退换说明', value: '不支持退换' },
  ]
  const purchaseRuleRows = [
    {
      title: '购票规则',
      value: activity.perUserLimit && activity.perUserLimit > 0
        ? `同一账号当前每单最多购买 ${activity.perUserLimit} 张，实名要求以提交订单时校验为准。`
        : '下单前请确认场次、票档、数量和观演人信息，实际限购以提交订单时校验为准。',
    },
    {
      title: '票档库存',
      value: `当前票档区间为 ${activeTicketPriceRange}，库存实时变化，以锁票结果和订单确认为准。`,
    },
    {
      title: '支付出票',
      value: '订单提交后请在支付时限内完成支付，支付成功后可在订单详情或票夹查看电子票信息。',
    },
    {
      title: '转赠规则',
      value: activity.ticketTransferAllowed
        ? '本项目支持电子票转赠，具体可操作时间和限制以票夹页面提示为准。'
        : '本项目当前不支持电子票转赠，请确认观演人信息后再提交订单。',
    },
  ]
  const attendanceTimeline = [
    { title: '提前 60 分钟到达', value: '预留取票、验票、安检和寻位时间，避免高峰排队影响入场。' },
    { title: '入场核验', value: '请准备电子票二维码和一致身份证件，配合现场核验。' },
    { title: '迟到观众安排', value: '演出开始后可能根据现场秩序分批入场，请服从工作人员安排。' },
  ]
  const averageRating = reviewData?.summary.averageRating
  const averageRatingText = averageRating ? Number(averageRating).toFixed(1) : '暂无'

  return (
    <>
      <Header />
      <FloatingBackButton
        pendingInteraction={showConfirm || grabProgressOpen || Boolean(pagePay)}
        analyticsEvent="omni_activity_detail_back_clicked"
        analyticsPayload={{ activity_id: activity.id }}
      />
      <main className="bg-[#F8F9FA] px-5 py-8">
        <div className="mx-auto grid max-w-[1200px] gap-5 items-start lg:grid-cols-[minmax(0,1fr)_300px]">
          {/* 左侧：占比约 2/3 */}
          <div className="flex-1 flex flex-col gap-5 min-w-0">
            {/* 顶部：活动基本信息与购票 */}
            <div className="rounded-2xl border border-[#f0f1f3] bg-white p-6 shadow-[0_4px_12px_rgba(0,0,0,0.03)]">
              <div className="mb-10 flex flex-col gap-8 md:flex-row">
          {/* 海报 */}
          <div className="relative aspect-[3/4] w-full max-w-[280px] flex-shrink-0 overflow-hidden rounded-2xl bg-gray-100">
            <SafeImage
              src={displayPoster}
              alt={displayTitle}
              className="h-full w-full object-cover"
            />
            <span className="absolute left-3 top-3 rounded-full bg-black/65 px-3 py-1 text-[12px] font-medium text-white">
              {category?.name || '演出'}
            </span>
          </div>

          {/* 信息 */}
          <div className="flex-1">
            {isCurrentTourEvent && (
              <div className="mb-3 inline-flex items-center rounded-full border border-[#FFD6E4] bg-[#FFF0F5] px-3 py-1 text-[12px] font-semibold text-[#E6005C]">
                巡演项目
              </div>
            )}
            <h1 className="mb-3 text-[24px] font-semibold text-[#111]">{displayTitle}</h1>
            {isCurrentTourEvent && selectedStationDetail && (
              <p className="mb-2 text-[14px] text-[#666]">
                当前选站：<span className="font-medium text-[#E6005C]">{stationCityLabel}</span>
                <span className="ml-2 text-[#999]">{formatStationDate(selectedStationDetail)}</span>
              </p>
            )}
            {artistSummary && (
              <p className="text-[14px] text-[#666] mb-2">
                艺人：<span className="text-[#ff1268]">{artistSummary}</span>
              </p>
            )}
            {category && (
              <p className="text-[14px] text-[#666] mb-2">类型：{category.name}</p>
            )}
            {displayDescription && (
              <p className="mt-4 text-[14px] leading-relaxed text-[#999]">{displayDescription}</p>
            )}
            <div className="mt-5 flex flex-wrap items-center gap-2">
              {subscriptionActions.map((action) => {
                const activeSubscription = action.type === 'CALENDAR'
                  ? null
                  : findActivitySubscriptionAction(action.type, subscriptions, {
                    activityId: activity.id,
                    artistId: artist?.id ?? null,
                  })
                const isActive = action.type === 'CALENDAR'
                  ? calendarJoinedActivityIds.includes(activity.id)
                  : Boolean(activeSubscription)
                const isActionLoading = subscriptionLoading === action.type
                const Icon = action.type === 'ACTIVITY_WANT'
                  ? Heart
                  : action.type === 'SALE_REMINDER'
                    ? Bell
                    : action.type === 'ARTIST_FOLLOW'
                      ? (isActive ? UserCheck : UserRound)
                      : CalendarDays
                const actionClass = action.type === 'ACTIVITY_WANT'
                  ? isActive
                    ? 'border-[#FF1475] bg-[#FF1475] text-white shadow-sm hover:bg-[#E00D65]'
                    : 'border-[#FF1475] bg-white text-[#FF1475] hover:bg-[#FFF0F5]'
                  : isActive
                    ? 'border-[#FFD6E4] bg-[#FFF0F5] text-[#E6005C] shadow-sm hover:border-[#FF1475]'
                    : 'border-gray-200 bg-white text-[#666] hover:border-[#FFD6E4] hover:bg-[#FFF0F5] hover:text-[#E6005C]'
                return (
                  <button
                    key={action.type}
                    type="button"
                    onClick={() => {
                      if (action.type === 'CALENDAR') {
                        handleCalendarReminder()
                      } else {
                        void handleSubscription(action.type, activeSubscription)
                      }
                    }}
                    disabled={isActionLoading}
                    className={`inline-flex h-10 min-w-[112px] items-center justify-center gap-2 rounded-full border px-4 text-[14px] font-medium transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-60 ${actionClass}`}
                  >
                    <Icon className={`h-4 w-4 ${action.type === 'ACTIVITY_WANT' && isActive ? 'fill-current' : ''}`} />
                    {getActivitySubscriptionActionLabel(action, { active: isActive, loading: isActionLoading })}
                  </button>
                )
              })}
            </div>
            <div className="mt-4 rounded-lg bg-[#fafafa] px-4 py-3 text-[13px] text-[#666]">
              {showStationPendingEmptyState
                ? '倒计时：当前站点排期待公布'
                : selectedSession?.session.startTime ? `倒计时：${getCountdownText(selectedSession.session.startTime)}` : '倒计时：场次时间待定'}
            </div>
            <button
              type="button"
              onClick={requestOpenAiTicketFinder}
              className="mt-3 inline-flex items-center gap-2 rounded-lg border border-[#ffd6e7] bg-[#fff8fb] px-3.5 py-2.5 text-[13px] font-medium text-[#e6005c] transition-colors hover:border-[#ff1268] hover:bg-[#fff0f5]"
            >
              <Sparkles className="h-4 w-4" />
              想找更合适的场次？让 AI 帮你找票
            </button>
          </div>
        </div>

              {isCurrentTourEvent && (
                <div className="mb-8">
                  {/* Tour Stations Selector */}
                  <div className="mb-3 flex items-center justify-between gap-3">
                    <h2 className="text-[18px] font-semibold text-[#111]">选择城市</h2>
                    <span className="text-[13px] text-[#999]">{stationDetails.length} 个站点</span>
                  </div>
                  <div className="-mx-2 overflow-x-auto scrollbar-hide px-2 pb-1" aria-label="巡演城市站点">
                    <div className="flex min-w-max gap-3">
                      {stationDetails.map((stationDetail) => {
                        const active = selectedStationDetail?.station.id === stationDetail.station.id
                        const badge = getStationStatusBadge(stationDetail)
                        return (
                          <button
                            key={stationDetail.station.id}
                            type="button"
                            aria-pressed={active}
                            onClick={() => handleSelectStation(stationDetail)}
                            className={`relative min-h-[108px] w-[164px] rounded-2xl border p-4 text-left transition-all duration-200 ${
                              active
                                ? 'border-[#FF1475] bg-[#FFF0F5] shadow-[0_4px_12px_rgba(255,20,117,0.12)]'
                                : 'border-[#eef0f3] bg-white hover:border-[#FFD6E4] hover:bg-[#fffafd]'
                            }`}
                          >
                            <span className={`absolute right-3 top-3 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${badge.className}`}>
                              {badge.label}
                            </span>
                            <div className={`mt-7 text-[20px] font-semibold ${active ? 'text-[#E6005C]' : 'text-[#111]'}`}>
                              {getStationCityLabel(stationDetail)}
                            </div>
                            <div className="mt-2 text-[13px] text-[#999]">{formatStationDate(stationDetail)}</div>
                          </button>
                        )
                      })}
                      <button
                        type="button"
                        onClick={() => showCenterToast('已登记加场意向')}
                        className="flex min-h-[108px] w-[144px] items-center justify-center rounded-2xl border border-dashed border-[#FFD6E4] bg-[#fffafd] text-[15px] font-semibold text-[#E6005C] transition-colors hover:bg-[#FFF0F5]"
                      >
                        + 求加场
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* 场次和票档 */}
              <div>
                <h2 className="text-[18px] text-[#111] font-medium mb-6">选择场次</h2>

          {showStationPendingEmptyState ? (
            <div className="flex flex-col items-center rounded-2xl border border-[#eef0f3] bg-[#FAFBFD] px-6 py-10 text-center">
              <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-[#FFF0F5] text-[#E6005C]">
                <Clock3 className="h-7 w-7" />
              </div>
              <div className="flex flex-wrap items-center justify-center gap-2">
                <h3 className="text-[20px] font-semibold text-[#111]">{stationCityLabel} · 演出筹备中</h3>
                <span className="rounded-full bg-[#F3F4F6] px-3 py-1 text-[12px] font-medium text-[#9CA3AF]">时间待公布</span>
              </div>
              <p className="mt-3 max-w-[560px] text-[13px] leading-6 text-[#666]">
                本站演出场馆、具体时间及票档区间正由主办方积极筹备中。开启开售提醒，第一时间接收最新开票排期通知。
              </p>
              <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center">
                <button
                  type="button"
                  onClick={() => void handleSubscription('SALE_REMINDER', saleReminderSubscription, {
                    success: '已成功订阅，开票前将短信提醒！',
                    cancel: '已关闭开售提醒',
                  })}
                  disabled={subscriptionLoading === 'SALE_REMINDER'}
                  className={`inline-flex h-11 min-w-[172px] items-center justify-center gap-2 rounded-full px-5 text-[14px] font-semibold text-white transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-60 ${
                    saleReminderActive ? 'bg-[#28C76F] hover:bg-[#23ad61]' : 'bg-[#FF1475] hover:bg-[#E00D65]'
                  }`}
                >
                  <Bell className="h-4 w-4" />
                  {subscriptionLoading === 'SALE_REMINDER'
                    ? saleReminderActive ? '取消中...' : '开启中...'
                    : saleReminderActive ? '已开启开售提醒' : '开启开售提醒'}
                </button>
                <button
                  type="button"
                  onClick={() => void handleSubscription('ACTIVITY_WANT', findActivitySubscriptionAction('ACTIVITY_WANT', subscriptions, {
                    activityId: activity.id,
                    artistId: artist?.id ?? null,
                  }))}
                  disabled={subscriptionLoading === 'ACTIVITY_WANT'}
                  className="inline-flex h-11 min-w-[144px] items-center justify-center gap-2 rounded-full border border-[#FF1475] bg-white px-5 text-[14px] font-semibold text-[#FF1475] transition-colors hover:bg-[#FFF0F5] disabled:cursor-not-allowed disabled:opacity-60"
                >
                  <Heart className="h-4 w-4" />
                  登记想看意向
                </button>
              </div>
            </div>
          ) : activePurchaseSessions.length === 0 ? (
            <p className="text-[#999] text-sm py-8 text-center">暂无可用场次</p>
          ) : (
            <>
              {/* 场次列表 */}
              <div className="flex flex-wrap gap-3 mb-6">
                {activePurchaseSessions.map((sd) => (
                  <button
                    key={sd.session.id}
                    onClick={() => {
                      setSelectedSession(sd)
                      setAllowAutoDowngrade(false)
                      resetGrabIdempotencyKey()
                      if (sd.ticketTypes.length > 0) {
                        setSelectedTicket(sd.ticketTypes[0])
                      } else {
                        setSelectedTicket(null)
                      }
                    }}
                    className="cursor-pointer border outline-none px-5 py-2.5 rounded text-sm transition-colors"
                    style={{
                      backgroundColor: selectedSession?.session.id === sd.session.id ? '#fff0f5' : '#fff',
                      borderColor: selectedSession?.session.id === sd.session.id ? '#ff1268' : '#e5e5e5',
                      color: selectedSession?.session.id === sd.session.id ? '#ff1268' : '#333',
                    }}
                  >
                    <div className="font-medium">
                      {sd.session.startTime ? sd.session.startTime.slice(0, 16).replace('T', ' ') : ''}
                    </div>
                    {sd.venue && (
                      <div className="text-xs mt-1 opacity-70">{sd.venue.name} - {sd.venue.city}</div>
                    )}
                  </button>
                ))}
              </div>

              {/* 票档列表 */}
              {selectedSession && (
                <>
                  <h3 className="text-[16px] text-[#111] font-medium mb-4">选择票档</h3>
                  <div className="flex flex-wrap gap-3 mb-6">
                    {selectedSession.ticketTypes.length === 0 ? (
                      <p className="text-[#999] text-sm">票档待公布</p>
                    ) : (
                      selectedSession.ticketTypes.map((tt) => {
                        const stockHint = visibleStock?.ticketTypes.find(ticket => ticket.ticketTypeId === tt.id)
                        return (
                          <button
                            key={tt.id}
                            onClick={() => { setSelectedTicket(tt); setQuantity(1); setSelectedSeatIds([]); setAllowAutoDowngrade(false); resetGrabIdempotencyKey() }}
                            className="cursor-pointer border outline-none px-5 py-3 rounded text-sm transition-colors min-w-[100px]"
                            style={{
                              backgroundColor: selectedTicket?.id === tt.id ? '#fff0f5' : '#fff',
                              borderColor: selectedTicket?.id === tt.id ? '#ff1268' : '#e5e5e5',
                              color: selectedTicket?.id === tt.id ? '#ff1268' : '#333',
                            }}
                          >
                            <div className="font-medium">{tt.name}</div>
                            <div className="text-[18px] text-[#ff1268] font-medium mt-1">
                              ¥{tt.price}
                            </div>
                            <div className="text-xs text-[#999] mt-0.5">
                              {stockHint?.visibleStock == null
                                ? '库存变化较快'
                                : stockHint.visibleStock > 0 ? `剩余约 ${stockHint.visibleStock} 张` : '当前可见库存紧张'}
                            </div>
                          </button>
                        )
                      })
                    )}
                  </div>
                  <div className="mb-6 text-[12px] text-[#999]">库存变化较快，以锁票结果为准。</div>

                  {selectedTicket && (
                    <div className="mb-6">
                      {seatMapPublished && seatMapLoading ? (
                        <div className="rounded-2xl border border-[#eef0f3] bg-[#FAFBFD] p-5 text-center text-[13px] text-[#999]">正在加载座位图...</div>
                      ) : seatMapPublished && showsSeatCraftSelection && seatCraftSelectionModel && showPurchaseEntry ? (
                        <div className="rounded-2xl border border-[#eef0f3] bg-[#FAFBFD] p-4">
                          <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                            <div>
                              <div className="text-[14px] font-medium text-[#333]">选座区域</div>
                              <div className="mt-1 text-[12px] text-[#888]">可预览座位图并选择本次购买座位，已选 {validSelectedSeatIds.length} / {quantity} 座。</div>
                            </div>
                            <button
                              type="button"
                              onClick={handleAutoSelectSeats}
                              className="rounded-full border border-[#FF1475] bg-white px-4 py-1.5 text-[13px] font-medium text-[#FF1475] transition-colors hover:bg-[#FFF0F5]"
                            >
                              自动分配
                            </button>
                          </div>
                          <SeatCraftSelector
                            selectionModel={seatCraftSelectionModel}
                            selectedSeatIds={validSelectedSeatIds}
                            onChange={(ids) => { setSelectedSeatIds(ids); resetGrabIdempotencyKey() }}
                            maxSelectable={quantity}
                            focusTarget={seatCraftFocusTarget}
                          />
                        </div>
                      ) : (
                        <div className="flex items-start gap-2 rounded-2xl border border-[#eef0f3] bg-[#FAFBFD] px-4 py-3 text-[13px] leading-relaxed text-[#666]">
                          <Info className="mt-0.5 h-4 w-4 flex-shrink-0 text-[#9aa3af]" />
                          <span>座位暂不公布，座位将在下单后由系统自动分配。</span>
                        </div>
                      )}
                    </div>
                  )}

                  {/* 数量选择 + 购买按钮 */}
                  {selectedTicket && showPurchaseEntry && (
                    <>
                      <div className="flex flex-col gap-4 border-t border-[#f0f0f0] pt-4 sm:flex-row sm:items-center">
                        <div className="flex items-center gap-3">
                          <span className="text-[14px] text-[#666]">数量</span>
                          <div className="flex items-center rounded border border-[#e5e5e5]">
                            <button
                              onClick={() => { setQuantity(Math.max(1, quantity - 1)); setSelectedSeatIds(ids => ids.slice(0, Math.max(1, quantity - 1))); resetGrabIdempotencyKey() }}
                              className="flex h-9 w-9 cursor-pointer items-center justify-center border-none bg-[#f5f5f5] text-lg text-[#333] outline-none"
                            >
                              -
                            </button>
                            <span className="w-12 text-center text-[14px] text-[#111]">{quantity}</span>
                            <button
                              onClick={() => { setQuantity(Math.min(purchaseQuantityMax, quantity + 1)); resetGrabIdempotencyKey() }}
                              className="flex h-9 w-9 cursor-pointer items-center justify-center border-none bg-[#f5f5f5] text-lg text-[#333] outline-none"
                            >
                              +
                            </button>
                          </div>
                        </div>
                        <div className="text-[14px] text-[#666] sm:ml-2">
                          合计：<span className="text-[24px] text-[#ff1268] font-medium">¥{(selectedTicket.price * quantity).toFixed(2)}</span>
                        </div>
                        <button
                          onClick={handleBuy}
                          disabled={Boolean(showsSeatCraftSelection) && validSelectedSeatIds.length !== quantity}
                          className="h-11 w-full cursor-pointer rounded border-none px-10 text-[16px] font-medium text-white outline-none disabled:cursor-not-allowed disabled:opacity-50 sm:ml-auto sm:w-auto"
                          style={{ backgroundColor: '#ff1268' }}
                        >
                          {purchaseCtaLabel}
                        </button>
                      </div>
                    </>
                  )}
                  {selectedTicket && showWaitlistEntry && (
                    <div className="rounded-lg border border-[#ffd6e7] bg-[#fff7fb] px-4 py-4">
                      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                        <div>
                          <div className="text-[14px] font-medium text-[#333]">当前票档暂不可购买</div>
                          <div className="mt-1 text-[12px] leading-relaxed text-[#777]">
                            可先加入候补，释放名额后系统会按顺序生成待支付订单并通知你限时付款。
                          </div>
                        </div>
                        <div className="flex w-full flex-col gap-3 sm:w-auto sm:flex-row sm:items-center sm:justify-end">
                          <div className="flex w-full items-center justify-between gap-2 sm:w-auto sm:justify-start">
                            <span className="text-[14px] text-[#666]">数量</span>
                            <div className="flex items-center rounded border border-[#e5e5e5] bg-white">
                              <button
                                type="button"
                                onClick={() => { setQuantity(Math.max(1, quantity - 1)); setSelectedAttendeeIds(ids => ids.slice(0, Math.max(1, quantity - 1))); resetGrabIdempotencyKey() }}
                                className="flex h-9 w-9 cursor-pointer items-center justify-center border-none bg-[#f5f5f5] text-lg text-[#333] outline-none"
                              >
                                -
                              </button>
                              <span className="w-12 text-center text-[14px] text-[#111]">{quantity}</span>
                              <button
                                type="button"
                                onClick={() => { setQuantity(Math.min(waitlistQuantityMax, quantity + 1)); resetGrabIdempotencyKey() }}
                                className="flex h-9 w-9 cursor-pointer items-center justify-center border-none bg-[#f5f5f5] text-lg text-[#333] outline-none"
                              >
                                +
                              </button>
                            </div>
                          </div>
                          <button
                            type="button"
                            onClick={handleWaitlistEntry}
                            disabled={waitlistSubmitting}
                            className="h-10 w-full cursor-pointer rounded border-none bg-[#ff1268] px-8 text-[15px] font-medium text-white outline-none disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto"
                          >
                            加入候补
                          </button>
                        </div>
                      </div>
                      {waitlistMessage && (
                        <div className="mt-3 rounded border border-[#b7eb8f] bg-[#f6ffed] p-2.5 text-[13px] text-[#389e0d]">
                          {waitlistMessage}
                        </div>
                      )}
                      {orderError && !showConfirm && (
                        <div className="mt-3 rounded border border-[#ffcccc] bg-[#fff0f0] p-2.5 text-[13px] text-[#e74c3c]">
                          {orderError}
                        </div>
                      )}
                    </div>
                  )}
                </>
              )}
                </>
              )}
            </div>
            </div>

            {/* 下方：项目详情 / 购票须知 / 观演须知 */}
            <div className="overflow-hidden rounded-2xl border border-[#f0f1f3] bg-white shadow-[0_4px_12px_rgba(0,0,0,0.03)]">
              <div className="px-6 pt-6">
                <div className="inline-flex w-full gap-1 overflow-x-auto rounded-full bg-[#F4F5F7] p-1 sm:w-auto">
                  {detailTabItems.map(([key, tab]) => (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setActiveDetailTab(key)}
                      className={`shrink-0 rounded-full px-5 py-2 text-[14px] font-medium outline-none transition-all duration-200 ${
                        activeDetailTab === key
                          ? 'bg-white text-[#E6005C] shadow-sm'
                          : 'text-[#666] hover:text-[#E6005C]'
                      }`}
                    >
                      {tab.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="p-6 transition-opacity duration-200">
                {activeDetailTab === 'project' && (
                  <div className="space-y-6">
                    <div className="grid gap-3 sm:grid-cols-2">
                      {projectInfoCards.map(({ title, value, icon: Icon }) => (
                        <div key={title} className="rounded-2xl border border-[#eef0f3] bg-[#FAFBFD] p-4">
                          <div className="mb-3 flex h-9 w-9 items-center justify-center rounded-xl bg-[#FFF0F5] text-[#E6005C]">
                            <Icon className="h-4 w-4" />
                          </div>
                          <div className="text-[12px] font-medium text-[#999]">{title}</div>
                          <div className="mt-1 text-[14px] leading-6 text-[#333]">{value}</div>
                        </div>
                      ))}
                    </div>

                    <section>
                      <h2 className="mb-3 text-[18px] font-semibold text-[#111]">演出介绍</h2>
                      <p className="whitespace-pre-line text-[13px] leading-[1.7] text-[#444]">{projectIntro}</p>
                      <div className="mt-4 rounded-2xl border border-amber-100 bg-amber-50 px-4 py-3 text-[13px] leading-6 text-amber-700">
                        重要提示：演出阵容、入场时间、座位分配及现场安排可能随主办方通知调整，请以订单页和现场指引为准。
                      </div>
                    </section>
                  </div>
                )}

                {activeDetailTab === 'purchase' && (
                  <div className="space-y-6">
                    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                      {purchaseRulePills.map(rule => (
                        <div key={rule.title} className="rounded-full border border-[#FFD6E4] bg-[#FFF0F5] px-4 py-3 text-center">
                          <div className="text-[12px] font-medium text-[#E6005C]">{rule.title}</div>
                          <div className="mt-1 text-[13px] font-semibold text-[#333]">{rule.value}</div>
                        </div>
                      ))}
                    </div>
                    <div className="divide-y divide-[#eef0f3]">
                      {purchaseRuleRows.map(row => (
                        <div key={row.title} className="grid gap-2 py-4 sm:grid-cols-[120px_minmax(0,1fr)]">
                          <div className="text-[14px] font-semibold text-[#111]">{row.title}</div>
                          <div className="text-[13px] leading-6 text-[#555]">{row.value}</div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {activeDetailTab === 'attendance' && (
                  <div className="space-y-6">
                    <div className="relative space-y-5 pl-5 before:absolute before:left-[7px] before:top-2 before:h-[calc(100%-16px)] before:w-px before:bg-[#FFD6E4]">
                      {attendanceTimeline.map((item, index) => (
                        <div key={item.title} className="relative">
                          <span className="absolute -left-5 top-1 flex h-4 w-4 items-center justify-center rounded-full bg-[#E6005C] text-[10px] font-semibold text-white">{index + 1}</span>
                          <div className="text-[14px] font-semibold text-[#111]">{item.title}</div>
                          <div className="mt-1 text-[13px] leading-6 text-[#555]">{item.value}</div>
                        </div>
                      ))}
                    </div>
                    <div className="grid gap-3 md:grid-cols-2">
                      <div className="rounded-2xl border border-red-100 bg-red-50 p-4">
                        <div className="mb-2 flex items-center gap-2 text-[14px] font-semibold text-red-600">
                          <Ban className="h-4 w-4" />
                          严禁携带物品
                        </div>
                        <p className="text-[13px] leading-6 text-red-700">易燃易爆、管制器具、专业摄影摄像设备等现场禁止物品请勿带入。</p>
                      </div>
                      <div className="rounded-2xl border border-[#FFD6E4] bg-[#FFF0F5] p-4">
                        <div className="mb-2 flex items-center gap-2 text-[14px] font-semibold text-[#E6005C]">
                          <ShieldCheck className="h-4 w-4" />
                          安全文明观演
                        </div>
                        <p className="text-[13px] leading-6 text-[#9f1746]">请保管好随身物品，遵守场馆秩序和现场安全提示。</p>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>

          <div className="overflow-hidden rounded-2xl border border-[#f0f1f3] bg-white shadow-[0_4px_12px_rgba(0,0,0,0.03)]">
            <div className="flex flex-col gap-3 border-b border-[#eef0f3] px-6 py-5 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h2 className="text-[18px] font-semibold text-[#111]">观众热评</h2>
                <p className="mt-1 text-[13px] text-[#999]">精选真实观演反馈，购票前可查看常见问答</p>
              </div>
              <div className="inline-flex items-center gap-2 rounded-full bg-[#FFF0F5] px-4 py-2 text-[#E6005C]">
                <Star className="h-4 w-4 fill-current" />
                <span className="text-[18px] font-bold">{averageRatingText}</span>
                <span className="text-[13px] text-[#E6005C]">分</span>
              </div>
            </div>

            <div className="grid gap-0 lg:grid-cols-[minmax(0,1.35fr)_minmax(280px,0.65fr)]">
              <section className="border-b border-[#f0f0f0] p-6 lg:border-b-0 lg:border-r">
                <div className="mb-5 flex items-center justify-between">
                  <h3 className="text-[16px] font-semibold text-[#111]">观众精选热评</h3>
                  <span className="text-[13px] text-[#999]">{reviewData?.summary.reviewCount ?? 0} 条评价</span>
                </div>

                <div className="columns-1 gap-4 xl:columns-2">
                  {(reviewData?.reviews || []).slice(0, 6).map((item, index) => (
                    <div key={item.id || `${index}-${item.createTime || item.content}`} className="mb-4 break-inside-avoid rounded-2xl border border-[#eef0f3] bg-white p-4 shadow-[0_4px_12px_rgba(0,0,0,0.03)]">
                      <div className="mb-3 flex items-start justify-between gap-3">
                        <div className="flex items-center gap-3">
                          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-[#FFF0F5] text-[14px] font-semibold text-[#E6005C]">匿</div>
                          <div>
                            <div className="text-[14px] font-semibold text-[#333]">匿名用户</div>
                            <div className="mt-1 flex items-center gap-1 text-[#FF1475]">
                              {Array.from({ length: getRatingStars(item.rating) }).map((_, starIndex) => <Star key={starIndex} className="h-3.5 w-3.5 fill-current" />)}
                            </div>
                          </div>
                        </div>
                        <span className="rounded-full bg-[#E8F8EE] px-2.5 py-1 text-[12px] font-medium text-[#28C76F]">{item.orderId ? '已购实名票' : '观演用户'}</span>
                      </div>
                      <div className="mb-3 rounded-xl bg-[#FAFBFD] px-3 py-2 text-[12px] text-[#777]">
                        观演场次：{formatCompactDateTime(primarySession?.session.startTime)} · {primarySession?.venue?.name || '场馆待公布'}
                      </div>
                      <p className="text-[13px] leading-6 text-[#555]">{item.content || '用户未填写文字评价'}</p>
                      {item.images && (
                        <div className="mt-3 flex flex-wrap gap-2">
                          {item.images.split(',').map(url => url.trim()).filter(Boolean).slice(0, 3).map(url => (
                            <SafeImage key={url} src={url} alt="评价图片" className="h-16 w-16 rounded-xl object-cover" />
                          ))}
                        </div>
                      )}
                      {item.id && (
                        <div className="mt-3 flex justify-end">
                          <button
                            type="button"
                            onClick={() => void handleReportReview(item.id)}
                            disabled={reportingReviewId === item.id}
                            className="rounded-full border border-gray-200 px-3 py-1 text-[12px] text-gray-500 transition-colors hover:border-[#FFD6E4] hover:text-[#E6005C] disabled:opacity-60"
                          >
                            {reportingReviewId === item.id ? '提交中...' : '举报'}
                          </button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
                {(!reviewData || reviewData.reviews.length === 0) && (
                  <div className="rounded-2xl bg-[#FAFBFD] py-8 text-center text-[13px] text-[#999]">暂无热评，完成观演后的评价会在这里展示</div>
                )}
              </section>

              <section className="p-6">
                <div className="mb-5 flex items-center justify-between gap-3">
                  <div>
                    <h3 className="text-[16px] font-semibold text-[#111]">演出问答区</h3>
                    <p className="mt-1 text-[12px] text-[#999]">检票时间、儿童入场政策等问题会在这里同步</p>
                  </div>
                  <MessageCircle className="h-5 w-5 text-[#E6005C]" />
                </div>
                <button
                  type="button"
                  onClick={() => {
                    if (!isAuthenticated()) {
                      router.push(`/login?ru=/activity/${id}`)
                      return
                    }
                    setQuestionComposerOpen(open => !open)
                  }}
                  className="mb-4 w-full rounded-full border border-[#FF1475] bg-white px-4 py-2 text-[13px] font-semibold text-[#FF1475] transition-colors hover:bg-[#FFF0F5]"
                >
                  我要提问
                </button>
                {questionComposerOpen && (
                  <div className="mb-5 rounded-2xl border border-[#FFD6E4] bg-[#FFF0F5] p-4">
                    <textarea
                      value={questionContent}
                      onChange={event => setQuestionContent(event.target.value)}
                      placeholder="例如：几点检票、是否可带相机、儿童是否需购票"
                      className="mb-3 h-20 w-full resize-none rounded-xl border border-[#FFD6E4] bg-white p-3 text-[13px] outline-none focus:border-[#FF1475]"
                    />
                    <div className="flex justify-end">
                      <button
                        type="button"
                        onClick={() => void handleSubmitQuestion()}
                        disabled={questionSubmitting}
                        className="rounded-full border border-[#FF1475] bg-[#FF1475] px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-60"
                      >
                        {questionSubmitting ? '提交中...' : '提交问题'}
                      </button>
                    </div>
                  </div>
                )}
                <div className="space-y-3">
                  {questions.slice(0, 6).map((item, index) => (
                    <div key={item.id || `${index}-${item.createTime || item.content}`} className="rounded-2xl border border-[#eef0f3] bg-[#FAFBFD] p-4">
                      <div className="mb-2 text-[13px] font-semibold text-[#333]">问：{item.content}</div>
                      <div className="text-[13px] leading-6 text-[#666]">答：{item.answer || formatActivityQuestionAnswerFallback(item.status)}</div>
                    </div>
                  ))}
                  {questions.length === 0 && (
                    <div className="rounded-2xl bg-[#FAFBFD] py-8 text-center text-[13px] text-[#999]">暂无问答，购票前可以先提一个问题</div>
                  )}
                </div>
              </section>
            </div>
          </div>
          </div>

          {/* 右侧：占比约 1/3 */}
          <div className="flex w-full flex-col gap-5">
            {/* 购票保障 / 服务说明 */}
            <div className="bg-white rounded-lg p-5 border border-[#e5e5e5] text-[#666] text-[12px] space-y-4">
              <div>
                <div className="flex items-center text-[#ff1268] font-medium mb-1 text-[13px]">
                  <span className="w-3 h-3 rounded-full border border-[#ff1268] flex items-center justify-center mr-1.5 font-bold text-[10px]">×</span>
                  不支持退
                </div>
                <div className="leading-relaxed">票品为有价票券，非普通商品，其背后承载的文化服务具有时效性，稀缺性等特征，不支持退换。</div>
              </div>
              <div>
                <div className="flex items-center text-[#ff1268] font-medium mb-1 text-[13px]">
                  <span className="w-3 h-3 rounded-full border border-[#ff1268] flex items-center justify-center mr-1.5 font-bold text-[10px]">✓</span>
                  可选座
                </div>
                <div className="leading-relaxed">本项目支持自主选座<br/>1.选择演出时间，并点击“选座购票”进入选座页面<br/>2.选座后，在确认订单页支付成功，则选座生效</div>
              </div>
              <div>
                <div className="flex items-center text-[#ff1268] font-medium mb-1 text-[13px]">
                  <span className="w-3 h-3 rounded-full border border-[#ff1268] flex items-center justify-center mr-1.5 font-bold text-[10px]">✓</span>
                  自助换票
                </div>
                <div className="leading-relaxed">需要您在指定的取票地点取票，下单后可通过票夹中的二维码或身份证换取纸质票</div>
              </div>
              <div>
                <div className="flex items-center text-[#ff1268] font-medium mb-1 text-[13px]">
                  <span className="w-3 h-3 rounded-full border border-[#ff1268] flex items-center justify-center mr-1.5 font-bold text-[10px]">✓</span>
                  电子发票
                </div>
                <div className="leading-relaxed">发票开具方：万象票务<br/>本项目支持开具电子发票。需要在演出开始前在订单详情页提交发票申请，一般演出结束后1个月左右开具并发送至您的邮箱。</div>
              </div>
              <div className="mt-4 border-t border-[#e5e5e5] pt-4 text-[12px] leading-5 text-[#888]">
                下单前请确认场次、票档、实名观演人和退款规则，支付结果以订单状态为准。
              </div>
            </div>

            <div className="bg-white rounded-lg p-5 border border-[#e5e5e5]">
              <h2 className="text-[16px] font-medium text-[#111] mb-4">排队购票准备检查</h2>
              <div className="space-y-3 text-[13px] text-[#666]">
                <div className="flex items-center justify-between gap-3">
                  <span>登录状态</span>
                  <span className={isAuthenticated() ? 'text-[#16a34a]' : 'text-[#ff1268]'}>{isAuthenticated() ? '已登录' : '待登录'}</span>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <span>实名观演人</span>
                  <span className="text-[#ff1268]">需提前维护</span>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <span>场次</span>
                  <span className={selectedSession ? 'text-[#16a34a]' : 'text-[#999]'}>{selectedSession ? '已选择' : '待选择'}</span>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <span>票档</span>
                  <span className={selectedTicket ? 'text-[#16a34a]' : 'text-[#999]'}>{selectedTicket ? '已选择' : '待选择'}</span>
                </div>
              </div>
              <button
                type="button"
                onClick={() => router.push('/subscriptions')}
                className="mt-4 w-full rounded-lg border border-[#ff1268] bg-white px-4 py-2 text-[13px] font-medium text-[#ff1268] hover:bg-[#fff0f5]"
              >
                管理想看与提醒
              </button>
            </div>

            {recommendations.length > 0 && (
              <div className="bg-white rounded-lg p-5 border border-[#e5e5e5]">
                <h2 className="text-[16px] font-medium text-[#111] mb-5">为你推荐</h2>
                <div className="space-y-6">
                  {recommendations.map(item => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => router.push(item.href)}
                      className="group flex w-full gap-3 text-left"
                    >
                      <div className="h-[106px] w-[80px] flex-shrink-0 overflow-hidden rounded bg-gray-100">
                        <SafeImage src={item.poster} alt={item.title} className="h-full w-full object-cover transition-transform group-hover:scale-105" />
                      </div>
                      <div className="flex flex-1 flex-col">
                        <div className="line-clamp-2 text-[14px] font-medium leading-snug text-[#333] transition-colors group-hover:text-[#ff1268]">{item.title}</div>
                        <div className="mt-2 text-[12px] text-[#999]">{item.time} · {item.city}</div>
                        <div className="mt-1 text-[12px] text-[#999]">{item.reason}</div>
                        <div className="mt-auto text-[16px] font-medium text-[#ff1268]">
                          {item.price != null ? <>¥{item.price}<span className="text-[12px]">起</span></> : '价格待定'}
                        </div>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </main>
      <Footer />

      {centerToast && (
        <div className="pointer-events-none fixed inset-0 z-[60] flex items-center justify-center bg-black/30">
          <div key={centerToast.id} className="flex min-w-[180px] animate-[activity-center-toast_1.5s_ease-in-out_forwards] flex-col items-center rounded-2xl bg-black/80 px-6 py-4 text-white shadow-2xl">
            <div className="mb-2 flex h-9 w-9 items-center justify-center rounded-full bg-white/15">
              <Check className="h-5 w-5" />
            </div>
            <div className="text-[14px] font-medium">{centerToast.message}</div>
          </div>
        </div>
      )}
      <style jsx global>{`
        @keyframes activity-center-toast {
          0% { opacity: 0; transform: scale(0.92); }
          12% { opacity: 1; transform: scale(1); }
          82% { opacity: 1; transform: scale(1); }
          100% { opacity: 0; transform: scale(0.96); }
        }
        .scrollbar-hide {
          -ms-overflow-style: none;
          scrollbar-width: none;
        }
        .scrollbar-hide::-webkit-scrollbar {
          display: none;
        }
      `}</style>

      {(() => {
        const modals = (
          <>
            {/* 订单/候补确认弹窗 */}
            {showConfirm && selectedSession && selectedTicket && (
              <div
                className="fixed inset-0 z-50 flex items-center justify-center"
                style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}
                onClick={closeConfirmDialog}
              >
                <div
                  className="max-h-[90vh] w-full max-w-[520px] overflow-y-auto rounded-lg bg-white p-6"
                  onClick={(e) => e.stopPropagation()}
                >
                  <h3 className="text-[18px] text-[#111] font-medium mb-4">{confirmCopy.title}</h3>

                  <div className="text-[14px] text-[#333] space-y-2 mb-4">
                    <div className="flex justify-between">
                      <span className="text-[#999]">活动</span>
                      <span className="text-right flex-1 ml-4">{activity.name}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#999]">场次</span>
                      <span>{selectedSession.session.startTime?.slice(0, 16).replace('T', ' ')}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#999]">场馆</span>
                      <span>{selectedSession.venue?.name}{selectedSession.venue?.city ? ` - ${selectedSession.venue.city}` : ''}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#999]">票档</span>
                      <span>{selectedTicket.name} × {quantity}张</span>
                    </div>
                    {confirmMode === 'purchase' && !showsSeatCraftSelection && downgradeCandidates.length > 0 && (
                      <label className="flex items-start gap-2 rounded border border-[#f0f0f0] p-3 text-[13px] text-[#666]">
                        <input
                          type="checkbox"
                          checked={allowAutoDowngrade}
                          onChange={(event) => {
                            setAllowAutoDowngrade(event.target.checked)
                            resetGrabIdempotencyKey()
                          }}
                          className="mt-0.5"
                        />
                        <span className="leading-relaxed">
                          允许自动尝试后续低价票档：
                          {[selectedTicket, ...downgradeCandidates]
                            .map(ticket => `${ticket.name} ¥${ticket.price}`)
                            .join(' / ')}
                        </span>
                      </label>
                    )}
                    {confirmMode === 'purchase' && showsSeatCraftSelection && validSelectedSeatIds.length > 0 && (
                      <div className="flex justify-between">
                        <span className="text-[#999]">座位</span>
                        <div className="text-right flex-1 ml-4 text-[13px] leading-relaxed">
                          {validSelectedSeatIds.map(id => {
                            const seat = seatMap?.seats.find(s => s.id === id)
                            if (!seat) return ''
                            const sectionName = seatMap?.layout?.sections.find(s => s.id === seat.layoutSectionId)?.name || seat.areaId || ''
                            return `${sectionName ? sectionName + ' ' : ''}${seat.rowNo}排${seat.seatNo}座`
                          }).join('，')}
                        </div>
                      </div>
                    )}
                    {realNameRequired && (
                      <div className="rounded border border-[#f0f0f0] bg-[#fafafa] p-3">
                        <div className="mb-2 flex items-center justify-between">
                          <span className="font-medium text-[#333]">实名观演人</span>
                          <span className="text-[12px] text-[#999]">{selectedAttendeeIds.length}/{quantity}</span>
                        </div>
                        {attendeesLoading ? (
                          <div className="py-3 text-[13px] text-[#999]">加载中...</div>
                        ) : attendees.length > 0 ? (
                          <div className="max-h-[160px] space-y-2 overflow-y-auto pr-1">
                            {attendees.map(attendee => {
                              const checked = selectedAttendeeIds.includes(attendee.id)
                              const disabled = !checked && selectedAttendeeIds.length >= quantity
                              return (
                                <div key={attendee.id} className={`flex items-start gap-2 rounded border bg-white p-2 text-[13px] ${checked ? 'border-[#ff1268]' : 'border-[#eee]'} ${disabled ? 'opacity-50' : ''}`}>
                                  <label className="flex min-w-0 flex-1 cursor-pointer items-start gap-2">
                                    <input
                                      type="checkbox"
                                      checked={checked}
                                      disabled={disabled}
                                      onChange={() => toggleAttendeeSelection(attendee.id)}
                                      className="mt-1"
                                    />
                                    <span className="min-w-0 flex-1">
                                      <span className="block text-[#333]">{attendee.realName}</span>
                                      <span className="block break-all text-[12px] text-[#999]">{getAttendeeIdTypeLabel(attendee.idType)} {attendee.idNoMask}</span>
                                    </span>
                                  </label>
                                  <button
                                    type="button"
                                    onClick={() => void handleDeleteAttendee(attendee)}
                                    disabled={attendeeDeletingId === attendee.id}
                                    className="shrink-0 cursor-pointer rounded border border-[#ddd] bg-white px-2 py-1 text-[12px] text-[#666] outline-none hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-60"
                                  >
                                    {attendeeDeletingId === attendee.id ? '删除中...' : '删除'}
                                  </button>
                                </div>
                              )
                            })}
                          </div>
                        ) : (
                          <div className="py-2 text-[13px] text-[#999]">暂无实名观演人，请先新增。</div>
                        )}
                        {selectedAttendeeSummary && (
                          <div className="mt-2 rounded bg-white px-2 py-1.5 text-[12px] text-[#666]">
                            已选：{selectedAttendeeSummary}
                          </div>
                        )}
                        <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-3">
                          <input
                            value={attendeeForm.realName}
                            onChange={(event) => setAttendeeForm(form => ({ ...form, realName: event.target.value }))}
                            placeholder="姓名"
                            className="h-9 rounded border border-[#ddd] px-3 text-[13px] outline-none focus:border-[#ff1268]"
                          />
                          <input
                            value={attendeeForm.idNo}
                            onChange={(event) => setAttendeeForm(form => ({ ...form, idNo: event.target.value }))}
                            placeholder="身份证号"
                            className="h-9 rounded border border-[#ddd] px-3 text-[13px] outline-none focus:border-[#ff1268]"
                          />
                          <input
                            value={attendeeForm.phone}
                            onChange={(event) => setAttendeeForm(form => ({ ...form, phone: event.target.value }))}
                            placeholder="手机号可选"
                            className="h-9 rounded border border-[#ddd] px-3 text-[13px] outline-none focus:border-[#ff1268]"
                          />
                        </div>
                        <div className="mt-2 flex items-center justify-between gap-3">
                          <span className="text-[12px] text-[#999]">下单后会快照保存，入场核验以订单观演人为准。</span>
                          <button
                            type="button"
                            onClick={handleCreateAttendee}
                            disabled={attendeeSaving}
                            className="shrink-0 cursor-pointer rounded border border-[#ff1268] bg-white px-3 py-1.5 text-[12px] text-[#ff1268] outline-none disabled:cursor-not-allowed disabled:opacity-60"
                          >
                            {attendeeSaving ? '保存中...' : '新增'}
                          </button>
                        </div>
                        {attendeeError && (
                          <div className="mt-2 rounded border border-[#ffcccc] bg-[#fff0f0] p-2 text-[12px] text-[#e74c3c]">
                            {attendeeError}
                          </div>
                        )}
                      </div>
                    )}
                    {confirmMode === 'waitlist' && (
                      <div className="rounded border border-[#f0f0f0] bg-[#fafafa] p-3 text-[13px] leading-relaxed text-[#666]">
                        候补成功后不会立即扣款；有名额释放时系统会生成待支付订单，请在通知时间内完成支付。
                      </div>
                    )}
                    {confirmMode === 'purchase' && (
                      <label className="flex items-start gap-2 rounded border border-[#f0f0f0] bg-[#fafafa] p-3 text-[13px] leading-relaxed text-[#666]">
                        <input
                          type="checkbox"
                          checked={refundPolicyAccepted}
                          onChange={(event) => setRefundPolicyAccepted(event.target.checked)}
                          className="mt-1"
                        />
                        <span>
                          我已阅读退票规则：开演前{detail?.activity.refundFullDaysBefore ?? 30}天及以上可退全额，开演前{detail?.activity.refundPartialDaysBefore ?? 7}至{Math.max((detail?.activity.refundFullDaysBefore ?? 30) - 1, detail?.activity.refundPartialDaysBefore ?? 7)}天可退票款的{detail?.activity.refundPartialRate ?? 80}%，截止后不可退；演出当天停止售票。改期、取消或阵容变更可选择专属原因提交。
                        </span>
                      </label>
                    )}
                    <div className="flex justify-between text-[16px] font-medium pt-3 border-t border-[#f0f0f0]">
                      <span>{confirmCopy.totalLabel}</span>
                      <span className="text-[#ff1268]">¥{(selectedTicket.price * quantity).toFixed(2)}</span>
                    </div>
                  </div>

                  {orderError && (
                    <div className="mb-4 p-2.5 bg-[#fff0f0] border border-[#ffcccc] rounded text-[#e74c3c] text-[13px]">
                      {orderError}
                    </div>
                  )}

                  <div className="flex gap-3 justify-end">
                    <button
                      onClick={closeConfirmDialog}
                      disabled={confirmSubmitting}
                      className="cursor-pointer border border-[#ddd] bg-white text-[#666] text-[14px] px-6 py-2 rounded outline-none"
                    >
                      取消
                    </button>
                    <button
                      onClick={() => {
                        if (confirmMode === 'waitlist') {
                          void handleJoinWaitlist({ closeConfirm: true })
                          return
                        }
                        void handleConfirmOrder()
                      }}
                      disabled={confirmSubmitting || (confirmMode === 'purchase' && !refundPolicyAccepted)}
                      className="cursor-pointer border-none outline-none text-white text-[14px] px-6 py-2 rounded disabled:cursor-not-allowed"
                      style={{ backgroundColor: '#ff1268', opacity: confirmSubmitting || (confirmMode === 'purchase' && !refundPolicyAccepted) ? 0.7 : 1 }}
                    >
                      {confirmSubmitting ? confirmCopy.submittingLabel : confirmCopy.submitLabel}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* 支付成功弹窗 */}
            {showSuccess && (
              <div
                className="fixed inset-0 z-50 flex items-center justify-center"
                style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}
              >
                <div className="bg-white rounded-2xl p-8 flex flex-col items-center" style={{ width: 360 }}>
                  {/* 成功图标 */}
                  <div className="w-16 h-16 rounded-full flex items-center justify-center mb-4" style={{ backgroundColor: '#f6ffed', border: '2px solid #52c41a' }}>
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
                      <path d="M5 13l4 4L19 7" stroke="#52c41a" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                  </div>
                  <h3 className="text-[20px] font-medium text-[#111] mb-2">支付成功</h3>
                  <p className="text-[13px] text-[#999] mb-1">订单号</p>
                  <p className="text-[14px] text-[#333] font-medium mb-6">{successOrderNo}</p>
                  <div className="flex gap-3 w-full">
                    <button
                      onClick={() => setShowSuccess(false)}
                      className="flex-1 cursor-pointer border border-[#ddd] bg-white text-[#666] text-[14px] py-2.5 rounded-lg outline-none"
                    >
                      继续浏览
                    </button>
                    <button
                      onClick={() => router.push('/orders')}
                      className="flex-1 cursor-pointer border-none outline-none text-white text-[14px] py-2.5 rounded-lg"
                      style={{ backgroundColor: '#ff1268' }}
                    >
                      查看订单
                    </button>
                  </div>
                </div>
              </div>
            )}

            {grabProgressOpen && grabProgress && (
              <div
                className="fixed inset-0 z-50 flex items-center justify-center px-4"
                style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}
              >
                <div className="w-full max-w-[480px] rounded-lg bg-white p-6" onClick={(e) => e.stopPropagation()}>
                  <div className="mb-4 flex items-start justify-between gap-4">
                    <div>
                      <h3 className="text-[18px] font-medium text-[#111]">抢票进度</h3>
                      <p className="mt-1 text-[12px] text-[#999]">库存变化较快，以最终锁票结果为准。</p>
                    </div>
                    <span className="rounded-full bg-[#fff1f6] px-3 py-1 text-[12px] text-[#ff1268]">
                      {formatGrabStatusLabel(grabProgress.status)}
                    </span>
                  </div>

                  <div className="mb-4 rounded border border-[#f0f0f0] p-3">
                    <div className="text-[12px] text-[#999]">当前状态</div>
                    <div className="mt-1 text-[14px] text-[#333]">
                      {getGrabProgressDisplayMessage(grabProgress)}
                    </div>
                  </div>

                  <div className="mb-4 grid grid-cols-2 gap-3 text-[13px]">
                    <div className="rounded border border-[#f0f0f0] p-3">
                      <div className="text-[#999]">排队信息</div>
                      <div className="mt-1 text-[#333]">
                        {grabProgress.queueRank != null ? `前方 ${grabProgress.queueRank} 人` : `序号 ${grabProgress.queueSeq ?? '-'}`}
                      </div>
                      {getQueueRankTrendLabel(grabProgress.queueRank, grabProgress.queueRankPrevious) && (
                        <div className="mt-1 text-[12px] text-[#999]">
                          {getQueueRankTrendLabel(grabProgress.queueRank, grabProgress.queueRankPrevious)}
                        </div>
                      )}
                    </div>
                    <div className="rounded border border-[#f0f0f0] p-3">
                      <div className="text-[#999]">预计等待</div>
                      <div className="mt-1 text-[#333]">
                        {grabProgress.estimatedWaitSeconds != null ? `${grabProgress.estimatedWaitSeconds} 秒` : '计算中'}
                      </div>
                    </div>
                  </div>

                  <div className="mb-4 rounded border border-[#f0f0f0] p-3 text-[13px]">
                    <div className="text-[#999]">自动降档</div>
                    <div className="mt-1 text-[#333]">{getAutoDowngradeDisplay(grabProgress)}</div>
                  </div>

                  <div className="mb-4">
                    <div className="mb-2 text-[13px] font-medium text-[#333]">档位尝试列表</div>
                    <div className="space-y-2">
                      {(grabProgress.attempts.length > 0 ? grabProgress.attempts : grabProgress.requestedTicketTypes.map(ticket => ({
                        ticketTypeId: ticket.ticketTypeId,
                        name: ticket.name,
                        status: 'PENDING',
                        message: '待尝试',
                      }))).map((attempt) => (
                        <div key={attempt.ticketTypeId} className="flex items-center justify-between rounded border border-[#f0f0f0] px-3 py-2 text-[13px]">
                          <span className="text-[#333]">{attempt.name ?? '票档信息待同步'}</span>
                          <span className={
                            attempt.status === 'SOLD_OUT' || attempt.status === 'FAILED' || attempt.status === 'LIMITED'
                              ? 'text-[#999]'
                              : attempt.status === 'TRYING' || attempt.status === 'LOCKING'
                                ? 'text-[#ff1268]'
                                : attempt.status === 'ORDER_CREATED'
                                  ? 'text-[#22c55e]'
                                  : 'text-[#666]'
                          }>
                            {localizeGrabProgressMessage(attempt.message) || GRAB_ATTEMPT_STATUS_LABELS[attempt.status] || '状态更新中'}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>

                  <div className="mb-5 rounded bg-[#fafafa] p-3 text-[12px] text-[#777]">
                    <div className="font-medium text-[#555]">库存提示</div>
                    <div className="mt-1">
                      {grabProgress.visibleStock?.visibleStock != null
                        ? `当前档位剩余约 ${grabProgress.visibleStock.visibleStock} 张`
                      : currentProgressStock?.visibleStock != null
                          ? `${currentProgressStock.name} 剩余约 ${currentProgressStock.visibleStock} 张`
                          : '当前档位库存变化较快'}
                    </div>
                  </div>

                  {grabProgress.fairnessNotes && grabProgress.fairnessNotes.length > 0 && (
                    <div className="mb-5 rounded bg-[#fafafa] p-3 text-[12px] text-[#777]">
                      <div className="font-medium text-[#555]">公平规则</div>
                      <ul className="mt-1 space-y-1">
                        {grabProgress.fairnessNotes.map((note) => (
                          <li key={note}>{note}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {orderError && (
                    <div className="mb-4 rounded border border-[#ffcccc] bg-[#fff0f0] p-2.5 text-[13px] text-[#e74c3c]">
                      {orderError}
                    </div>
                  )}
                  {waitlistMessage && (
                    <div className="mb-4 rounded border border-[#b7eb8f] bg-[#f6ffed] p-2.5 text-[13px] text-[#389e0d]">
                      {waitlistMessage}
                    </div>
                  )}

                  <div className="flex flex-wrap justify-end gap-3">
                    {!TERMINAL_GRAB_STATUSES.has(grabProgress.status) && (
                      <button
                        type="button"
                        onClick={handleCancelGrabProgress}
                        className="cursor-pointer rounded border border-[#ddd] bg-white px-4 py-2 text-[14px] text-[#666] outline-none"
                      >
                        取消抢票
                      </button>
                    )}
                    {grabProgress.status === 'ORDER_CREATED' && grabProgress.orderId && (
                      <>
                        <button
                          type="button"
                          onClick={() => void openProgressPayment(grabProgress.orderId!)}
                          disabled={progressPaymentOpening}
                          className="cursor-pointer rounded border-none bg-[#ff1268] px-4 py-2 text-[14px] text-white outline-none disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {progressPaymentOpening ? '打开支付中...' : '重新打开支付'}
                        </button>
                        <button
                          type="button"
                          onClick={goToOrdersFromProgress}
                          className="cursor-pointer rounded border border-[#ddd] bg-white px-4 py-2 text-[14px] text-[#666] outline-none"
                        >
                          查看订单
                        </button>
                      </>
                    )}
                    {grabProgress.status === 'PENDING_RECOVERY' && (
                      <button
                        type="button"
                        onClick={goToOrdersFromProgress}
                        className="cursor-pointer rounded border border-[#ddd] bg-white px-4 py-2 text-[14px] text-[#666] outline-none"
                      >
                        查看订单
                      </button>
                    )}
                    {canJoinWaitlistFromGrabStatus(grabProgress.status) && (
                      <button
                        type="button"
                        onClick={() => void handleJoinWaitlist()}
                        disabled={waitlistSubmitting}
                        className="cursor-pointer rounded border-none bg-[#ff1268] px-4 py-2 text-[14px] text-white outline-none disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        {waitlistSubmitting ? '加入中...' : '加入候补'}
                      </button>
                    )}
                    {TERMINAL_GRAB_STATUSES.has(grabProgress.status) && grabProgress.status !== 'ORDER_CREATED' && (
                      <button
                        type="button"
                        onClick={() => setGrabProgressOpen(false)}
                        className="cursor-pointer rounded border border-[#ddd] bg-white px-4 py-2 text-[14px] text-[#666] outline-none"
                      >
                        关闭
                      </button>
                    )}
                  </div>
                </div>
              </div>
            )}

            {pagePay && (
              <AlipayQrPayModal
                pay={pagePay}
                productName={activity.name}
                onClose={() => setPagePay(null)}
                onPaid={(result) => {
                  setSuccessOrderNo(result.orderNo || pagePay.orderNo)
                  setPagePay(null)
                  setShowSuccess(true)
                }}
              />
            )}
          </>
        )
        return modals
      })()}
    </>
  )
}
