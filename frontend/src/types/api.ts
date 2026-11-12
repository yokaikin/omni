/**
 * 后端接口响应类型
 */

import type { SeatBlockDraft, SeatOverrideDraft, TicketGroupDraft } from '@/components/seatcraft/types'

/** 统一响应体 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

/** 登录请求 */
export interface LoginRequest {
  loginType: 'password' | 'sms'
  account: string
  password?: string
  smsCode?: string
}

/** 登录响应 */
export interface LoginResponse {
  userId: number
  phone: string
  nickname: string | null
  avatar?: string | null
  token: string
  role: UserRole
  permissionCodes?: string[]
}

/** 注册请求 */
export interface RegisterRequest {
  phone: string
  password: string
  confirmPassword: string
}

/** 找回密码重置请求 */
export interface ResetPasswordRequest {
  phone: string
  smsCode: string
  newPassword: string
  confirmPassword: string
}

/** 修改密码请求 */
export interface ChangePasswordRequest {
  oldPassword: string
  smsCode: string
  newPassword: string
  confirmPassword: string
}

/** 用户信息 */
export interface UserInfo {
  id: number
  phone: string
  nickname: string | null
  email: string | null
  avatar: string | null
  status: number
  role: UserRole
  organizerStatus: OrganizerStatus | null
  organizerName: string | null
  permissionCodes?: string[]
  createTime: string
  updateTime: string | null
}

export interface AssetUploadVO {
  id: number
  bizType: string
  publicUrl: string
  originalName: string | null
  mimeType: string
  sizeBytes: number
}

export interface PrivateAssetVO {
  id: number
  bizType: string
  bizId?: number | null
  originalFilename: string | null
  contentType: string | null
  fileSize: number
  status: string
  createTime?: string | null
}

export type UserRole = 'user' | 'organizer' | 'admin' | 'platform_super_admin' | 'support' | 'organizer_admin'
export type OrganizerApplicationStatus = 0 | 1 | 2
export type OrganizerApplicationQueryStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type OrganizerStatus = 0 | 1 | 2 | 3
export type SubjectType = 'personal' | 'enterprise'

export type GrabStatus =
  | 'QUEUED'
  | 'WAITING'
  | 'TRYING_TICKET_TYPE'
  | 'LOCKING'
  | 'DOWNGRADING'
  | 'PENDING'
  | 'ACCEPTED'
  | 'ORDER_CREATING'
  | 'ORDER_CREATED'
  | 'SOLD_OUT'
  | 'PENDING_RECOVERY'
  | 'LIMITED'
  | 'FAILED'
  | 'EXPIRED'

export interface TicketTypePreferencePayload {
  ticketTypeId: number
  name?: string
  maxPrice?: number
}

export interface SubmitGrabRequestPayload {
  sessionId: number
  ticketTypeId?: number
  quantity: number
  seatIds?: number[]
  attendeeIds?: number[]
  allocateRandom?: boolean
  idempotencyKey: string
  ticketTypePreferences?: TicketTypePreferencePayload[]
  allowAutoDowngrade?: boolean
}

export interface GrabRequestResult {
  requestId: string
  status: GrabStatus
  orderId: number | null
  failReason: string | null
  queueSeq?: number | null
  queueRank?: number | null
  estimatedWaitSeconds?: number | null
  message?: string | null
}

export interface GrabTicketPreference {
  ticketTypeId: number
  name: string | null
  maxPrice: number | null
}

export interface GrabAttemptProgress {
  ticketTypeId: number
  name: string | null
  status: 'PENDING' | 'TRYING' | 'LOCKING' | 'SOLD_OUT' | 'LIMITED' | 'FAILED' | 'ORDER_CREATED'
  message: string
}

export interface VisibleStockSnapshot {
  ticketTypeId: number
  visibleStock: number | null
  level: 'AVAILABLE' | 'LOW' | 'HOT' | 'SOLD_OUT' | 'UNKNOWN'
  snapshotTime?: string
}

export interface GrabProgressResult extends GrabRequestResult {
  sessionId: number
  queueSeq: number | null
  queueRank: number | null
  queueRankPrevious?: number | null
  estimatedWaitSeconds: number | null
  currentTicketTypeId: number | null
  currentAttemptIndex: number
  requestedTicketTypes: GrabTicketPreference[]
  allowAutoDowngrade: boolean
  attempts: GrabAttemptProgress[]
  visibleStock: VisibleStockSnapshot | null
  fairnessNotes?: string[]
  message: string | null
  matchedTicketTypeId: number | null
  updateTime: string
}

export interface SessionVisibleStockResult {
  sessionId: number
  ticketTypes: Array<{
    ticketTypeId: number
    name: string
    visibleStock: number | null
    level: VisibleStockSnapshot['level']
  }>
  snapshotTime: string
}

export type WaitlistEntryStatus =
  | 'WAITING'
  | 'ALLOCATING'
  | 'OFFERED'
  | 'PAID'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED'

export interface WaitlistEntryVO {
  id: number
  sessionId: number
  ticketTypeId: number
  activityId?: number | null
  activityName?: string | null
  activityPoster?: string | null
  ticketTypeName?: string | null
  venueName?: string | null
  sessionTime?: string | null
  quantity: number
  status: WaitlistEntryStatus
  rank: number | null
  estimatedChance?: 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'
  estimatedChanceText?: string | null
  estimatedWaitText?: string | null
  offerOrderId: number | null
  offerExpireTime: string | null
  failReason: string | null
}

export interface UserAttendeePayload {
  realName: string
  idType: 'ID_CARD' | string
  idNo: string
  phone?: string | null
  isDefault?: boolean | null
}

export interface UserAttendeeVO {
  id: number
  realName: string
  idType: string
  idNoMask: string
  phone?: string | null
  isDefault?: boolean | null
  createTime?: string | null
  updateTime?: string | null
}

export interface UserAttendeeExportVO {
  fileName: string
  contentType: string
  content: string
}

export interface UserBrowseHistoryPayload {
  activityId: number
  activityName: string
  poster?: string | null
  category?: string | null
  artist?: string | null
  city?: string | null
}

export interface UserBrowseHistoryVO extends UserBrowseHistoryPayload {
  id: number
  viewedAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface OrderAttendeeVO {
  id: number
  orderId: number
  orderSeatId?: number | null
  attendeeUserProfileId: number
  realName: string
  idType: string
  idNoMask: string
  phone?: string | null
  status: number
}

export interface OrganizerApplicationVO {
  id: number
  userId: number
  phone: string | null
  nickname: string | null
  role: UserRole | null
  organizerStatus: OrganizerStatus | null
  organizerName: string
  subjectType: SubjectType
  contactName: string
  contactPhone: string
  contactEmail: string | null
  licenseNo: string | null
  businessScope: string | null
  description: string | null
  status: OrganizerApplicationStatus
  reviewerId: number | null
  reviewNote: string | null
  createTime: string
  updateTime: string | null
  reviewTime: string | null
  materials?: OrganizerApplicationMaterialVO[]
}

export interface OrganizerApplicationMaterialVO {
  id: number
  materialType: string
  assetId: number
  publicUrl: string
  originalName: string | null
  mimeType: string
  sizeBytes: number
  createTime: string
}

export interface OrganizerApplicationListParams {
  page?: number
  size?: number
  keyword?: string
  status?: OrganizerApplicationQueryStatus
  subjectType?: SubjectType
}

export interface OrganizerDirectoryListParams {
  page?: number
  size?: number
  keyword?: string
  followUpOperator?: number | string
  cooperationStatus?: string
}

export interface OrganizerDirectoryVO {
  organizerId: number
  organizerName: string
  subjectType: SubjectType
  qualificationNo: string | null
  contactName: string
  contactPhone: string
  followUpOperatorId: number | null
  followUpOperatorName: string | null
  onsaleActivityCount: number | null
  cooperationStatus: string
}

export interface OrganizerOnsaleSummaryVO {
  organizerId: number
  onsaleActivityCount: number | null
  available: boolean
}

// ========== 票务/活动 ==========

export type ActivityArtistVisibility = 'public' | 'hidden'
export type ArtistReviewStatus = 'pending' | 'approved' | 'rejected'
export type ArtistRiskStatus = 'normal' | 'risky'

export interface ActivityArtistVO {
  artistId: number
  name?: string | null
  alias?: string | null
  artistType?: string | null
  countryOrRegion?: string | null
  agency?: string | null
  categoryTags?: string | null
  avatar?: string | null
  isPrimary?: boolean | null
  primary?: boolean | null
  roleType?: string | null
  roleName?: string | null
  visibility: ActivityArtistVisibility
  sort: number
}

export interface ArtistSearchVO {
  id: number
  name: string
  alias?: string | null
  artistType?: string | null
  countryOrRegion?: string | null
  agency?: string | null
  categoryTags?: string | null
  avatar?: string | null
  representativeWorks?: string | null
  reviewStatus?: ArtistReviewStatus | string | null
  reviewNote?: string | null
  riskStatus?: ArtistRiskStatus | string | null
}

export interface ArtistSubmissionRequest {
  name: string
  alias?: string | null
  artistType?: string | null
  countryOrRegion?: string | null
  agency?: string | null
  representativeWorks?: string | null
  categoryTags?: string | null
  description?: string | null
  sourceNote?: string | null
}

export interface ArtistUpdateRequest {
  name: string
  alias?: string | null
  artistType?: string | null
  countryOrRegion?: string | null
  agency?: string | null
  representativeWorks?: string | null
  categoryTags?: string | null
  description?: string | null
  avatar?: string | null
}

export interface ArtistReviewRequest {
  action: 'approve' | 'reject'
  note?: string | null
}

export interface ArtistRiskRequest {
  riskStatus: ArtistRiskStatus
  reason?: string | null
}

export interface ArtistListParams {
  page?: number
  size?: number
  keyword?: string
  category?: string
  status?: ArtistReviewStatus | 'all' | ''
  reviewStatus?: ArtistReviewStatus | ''
  riskStatus?: ArtistRiskStatus | ''
  qualificationStatus?: 'uploaded' | 'pending' | ''
}

export interface ActivityRiskResolutionRequest {
  userId: number
  resolutionNote?: string | null
}

export interface ActivityRiskResolutionReviewRequest {
  userId: number
  action: 'approve' | 'reject'
  reviewNote?: string | null
}

export interface ActivityRiskResolutionVO {
  id: number
  activityId: number
  activityName?: string | null
  activityPoster?: string | null
  organizerId: number
  riskArtistId?: number | null
  status: 'awaiting_response' | 'pending' | 'approved' | 'rejected' | string
  riskSuspendedReason?: string | null
  riskSuspendedAt?: string | null
  resolutionNote?: string | null
  reviewNote?: string | null
  submittedBy?: number | null
  reviewedBy?: number | null
  reviewedAt?: string | null
  createTime?: string | null
  updateTime?: string | null
  attachmentUrls?: string[] | null
}

export interface RiskResolutionListParams {
  page?: number
  size?: number
  keyword?: string
  reasonType?: string
  status?: 'pending' | 'approved' | 'rejected' | string
}

export interface NotificationVO {
  id: number
  userId: number
  orderId?: number | null
  type: string
  content: string
  status: number
  createTime?: string | null
  readTime?: string | null
  deletedTime?: string | null
  updateTime?: string | null
  actionHref?: string | null
  actionLabel?: string | null
  aggregateKey?: string | null
  aggregateCount?: number | null
}

export interface NotificationSummaryVO {
  unreadCount: number
  visibleCount: number
  readCount: number
  typeCounts?: Record<string, number> | null
}

export type NotificationPreferenceChannel = 'IN_APP' | 'SMS'

export interface NotificationPreferenceVO {
  channel: NotificationPreferenceChannel
  label: string
  enabled: boolean
  locked: boolean
  statusText: string
  description: string
}

export interface ActivityRiskCaseVO {
  activityId: number
  activityName: string
  organizerId: number
  riskSuspendedReason?: string | null
  riskSuspendedAt?: string | null
  latestResolutionId?: number | null
  latestResolutionStatus?: string | null
  latestResolutionNote?: string | null
  latestSubmittedBy?: number | null
}

/** 分页结果 */
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 活动列表项 */
export interface ActivityVO {
  id: number
  itemType?: 'activity' | 'tour'
  name: string
  poster: string | null
  categoryName: string
  artistName: string
  venueCity: string
  startTime: string
  minPrice: number | null
  status: number
  seatMapVisibility?: 'published' | 'hidden' | string | null
  realNameRequired?: boolean | null
  ticketTransferAllowed?: boolean | null
  artists?: ActivityArtistVO[]
  averageRating?: number | null
  reviewCount?: number | null
}

export interface FinderQueryRequest {
  query: string
}

export interface TicketIntent {
  keyword: string | null
  city: string | null
  dateFrom: string | null
  dateTo: string | null
  preferredDate: string | null
  minPrice: number | null
  maxPrice: number | null
  peopleCount: number | null
  needAdjacentSeats: boolean | null
  saleStatus: string | null
  isSupportSeat: boolean | null
  realNameRequired: boolean | null
  sortPreference: string | null
}

export interface FinderClarification {
  required: boolean
  questions: string[]
}

export interface TicketFinderResult {
  activityId: number
  activityName: string
  sessionId: number
  sessionStartTime: string
  venueId: number
  venueName: string
  city: string
  ticketTypeId: number
  ticketTypeName: string
  price: number
  availableQuantity: number
  saleStatus: string
  explanation?: string | null
}

export interface FinderResponse {
  requestId: string | null
  parsedIntent: TicketIntent | null
  clarification: FinderClarification | null
  results: TicketFinderResult[]
  explanation: string | null
}

export type SearchTrendingTagType = 'BURST' | 'HOT' | 'NEW' | 'NONE' | string
export type SearchTrendingTargetType = 'EVENT' | 'KEYWORD' | string

export interface SearchTrendingItem {
  id?: number | null
  rank: number
  keyword: string
  tagType: SearchTrendingTagType
  targetType: SearchTrendingTargetType
  targetId?: number | null
  itemType?: 'activity' | 'tour' | string | null
}

/** 分类 */
export interface CategoryVO {
  id: number
  name: string
  icon: string | null
  sort: number
  status: number
}

/** 活动实体 */
export interface ActivityEntity {
  id: number
  itemType?: 'activity' | 'tour'
  categoryId: number
  artistId: number
  artistName?: string | null
  artists?: ActivityArtistVO[]
  organizerId?: number | null
  venueApplicationId?: number | null
  venueApprovalNo?: string | null
  venueApprovalFileUrl?: string | null
  venueApprovalNote?: string | null
  name: string
  description: string | null
  poster: string | null
  publishStatus?: string | null
  riskSuspendedReason?: string | null
  riskSuspendedAt?: string | null
  riskRestoredAt?: string | null
  seatMapVisibility?: 'published' | 'hidden' | null
  realNameRequired?: boolean | null
  ticketTransferAllowed?: boolean | null
  refundFullDaysBefore?: number | null
  refundPartialDaysBefore?: number | null
  refundPartialRate?: number | null
  status: number
  createTime: string
  averageRating?: number | null
  reviewCount?: number | null
}

export interface DeleteActivityResponse {
  activityId: number
  publishStatus: string
  status: number
  deleted: boolean
  refundBlocked: boolean
  message?: string
}

/** 艺人 */
export interface ArtistEntity {
  id: number
  name: string
  alias?: string | null
  artistType?: string | null
  countryOrRegion?: string | null
  agency?: string | null
  categoryTags?: string | null
  representativeWorks?: string | null
  sourceNote?: string | null
  reviewStatus?: ArtistReviewStatus | string | null
  reviewNote?: string | null
  submittedBy?: number | null
  reviewedBy?: number | null
  reviewedAt?: string | null
  riskStatus?: ArtistRiskStatus | string | null
  riskReason?: string | null
  avatar: string | null
  description: string | null
}

/** 场次 */
export interface SessionEntity {
  id: number
  activityId: number
  venueId: number
  startTime: string
  endTime: string
  status: number
}

export interface SessionAdminVO extends SessionEntity {
  activityName: string | null
  venueName: string | null
  venueCity: string | null
  ticketTypeCount: number
  totalStock: number
  soldStock: number
  remainStock: number
  ticketTypes?: TicketTypeEntity[]
}

export interface AdminSummaryVO {
  activityCount: number
  ticketTypeCount: number
  paidOrderCount: number
  orderCount?: number
  paymentTimeoutCount?: number
  refundRequestCount?: number
  refundAbnormalCount?: number
  riskCheckCount?: number
  riskHitCount?: number
  hotActivities?: HotActivityVO[]
}

export interface CheckInOverviewVO {
  sessionId: number
  totalTickets: number
  checkedInCount: number
  unusedCount: number
  failedCount: number
  duplicateCount: number
}

export interface CheckInRecordVO {
  id: number
  requestId: string
  ticketId?: number | null
  ticketNo?: string | null
  orderId?: number | null
  userId?: number | null
  sessionId?: number | null
  ticketTypeId?: number | null
  deviceCode?: string | null
  operatorUserId?: number | null
  channel: string
  result: 'SUCCESS' | 'DUPLICATE' | 'FAILED'
  failureReason?: string | null
  checkedInAt?: string | null
  createTime?: string | null
}

export interface SearchIndexHealthVO {
  available: boolean
  aliasName: string
  indexNames?: string[] | null
  docCount?: number | null
  message?: string | null
  checkedAt?: string | null
}

export interface SearchIndexConsistencyVO {
  dbCount: number
  indexCount: number
  missingInIndex: string[]
  staleInIndex: string[]
  consistent: boolean
  checkedAt?: string | null
}

export interface SearchIndexRebuildResultVO {
  indexedCount: number
  indexName: string
  aliasName: string
  startedAt?: string | null
  finishedAt?: string | null
}

export interface SearchIndexDeadLetterVO {
  id: number
  sourceQueue?: string | null
  deadLetterQueue?: string | null
  payload?: string | null
  failureMessage?: string | null
  status: 'PENDING' | 'REPLAYED' | string
  replayCount?: number | null
  replayedAt?: string | null
  createdTime?: string | null
  updatedTime?: string | null
}

export interface HotActivityVO {
  activityId: number
  activityName: string
  orderCount: number
  paidOrderCount: number
}

export type ActivityMarketingDiscountType = 'NONE' | 'FULL_REDUCTION' | 'DIRECT_REDUCTION' | string

export interface ActivityMarketingRuleVO {
  enabled: boolean
  couponName?: string | null
  discountType: ActivityMarketingDiscountType
  thresholdAmount?: number | null
  discountAmount?: number | null
  maxCouponCount?: number | null
  perUserLimit?: number | null
  claimedCount?: number | null
  usedCount?: number | null
  status: number
  startTime?: string | null
  endTime?: string | null
}

export interface ActivityMarketingRulePayload {
  userId?: number
  enabled: boolean
  couponName?: string | null
  discountType: ActivityMarketingDiscountType
  thresholdAmount?: number | null
  discountAmount?: number | null
  maxCouponCount?: number | null
  perUserLimit?: number | null
  startTime?: string | null
  endTime?: string | null
}

export interface ActivityFunnelStepVO {
  key: string
  label: string
  count: number
}

export interface ActivityMarketingOverviewVO {
  activityId: number
  activityName: string
  rule: ActivityMarketingRuleVO
  funnelSteps: ActivityFunnelStepVO[]
}

export interface GrabOpsSummaryVO {
  failureReasons: Array<{ reason: string; count: number }>
  waitlist: {
    totalCount: number
    paidCount: number
    conversionRate: number
  }
}

export interface PlatformOpsSummaryVO {
  generatedAt?: string | null
  funnelSteps: ActivityFunnelStepVO[]
  ticket: AdminSummaryVO
  refund: {
    totalCount: number
    abnormalCount: number
  }
  grab: GrabOpsSummaryVO
  workbench: {
    pendingExceptionCount: number
    latestBatch?: ReconciliationBatchVO | null
    latestAudit?: OperationAuditLogVO | null
  }
  infrastructureHealth?: PlatformInfrastructureHealthVO | null
  errors: Array<{ source: string; message: string }>
}

export type PlatformInfrastructureHealthStatus = 'ok' | 'degraded' | 'not_configured' | string

export interface PlatformInfrastructureHealthVO {
  generatedAt?: string | null
  items: Array<{
    key: string
    label: string
    status: PlatformInfrastructureHealthStatus
    message?: string | null
  }>
}

export interface HelpFaqVO {
  category: string
  question: string
  answer: string
}

export type SupportConversationStatus = 'OPEN' | 'WAITING_AGENT' | 'ASSIGNED' | 'CLOSE_REQUESTED' | 'CLOSED' | string
export type SupportMessageSenderType = 'USER' | 'AI' | 'AGENT' | 'SYSTEM' | string
export type SupportTagCode = 'REFUND' | 'TICKET' | 'ADMISSION' | 'ACCOUNT' | 'PAYMENT_EXCEPTION'

export interface SupportConversationVO {
  id: number
  userId: number
  userNickname?: string | null
  userPhoneMask?: string | null
  subject: string
  status: SupportConversationStatus
  sourceType: 'AI' | 'HUMAN' | string
  assignedAgentId?: number | null
  lastMessage?: string | null
  createTime?: string | null
  updateTime?: string | null
  closedAt?: string | null
  firstResponseDueAt?: string | null
  firstAgentRepliedAt?: string | null
  lastUserMessageAt?: string | null
  lastAgentMessageAt?: string | null
  userWaitingSeconds?: number | null
  slaOverdue?: boolean | null
  tags?: string[] | null
  closeRequestReason?: string | null
  closeRequestedBy?: number | null
  closeRequestedAt?: string | null
  escalatedToAdmin?: boolean | null
  escalationReason?: string | null
  escalatedAt?: string | null
}

export interface SupportMessageVO {
  id: number
  conversationId: number
  senderUserId?: number | null
  senderType: SupportMessageSenderType
  senderDisplayName?: string | null
  content: string
  createTime?: string | null
}

export interface SupportAccountVO {
  id: number
  phone: string
  nickname: string | null
  role: 'support'
  supportRole?: 'support_manager' | 'support_agent' | string | null
  status: number
  createTime?: string | null
  updateTime?: string | null
}

export interface SupportNoteVO {
  id: number
  conversationId: number
  authorUserId?: number | null
  authorDisplayName?: string | null
  content: string
  createTime?: string | null
}

export interface SupportQuickReplyVO {
  id: number
  category: string
  title: string
  content: string
  sortOrder?: number | null
}

export interface SupportAuditVO {
  id: number
  conversationId: number
  actorUserId?: number | null
  actorDisplayName?: string | null
  action: string
  fromStatus?: string | null
  toStatus?: string | null
  detail?: string | null
  createTime?: string | null
}

export type CsSessionStatus = 'ACTIVE' | 'CLOSED' | 'NEED_AUDIT'
export type CsSessionSort = 'latest' | 'sla_waiting' | 'quality'
export type SupportAiSuggestionStatus =
  | 'GENERATING'
  | 'READY'
  | 'ACCEPTED'
  | 'ACCEPTED_EDITED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'FAILED'

export interface CsCopilotSourceEvidenceResponse {
  factKey: string
  text: string
}

export interface SupportAiSuggestion {
  suggestionId: number
  conversationId: number
  agentId: number
  messageCutoff: number | null
  contextDigest: string | null
  status: SupportAiSuggestionStatus
  suggestionText: string | null
  summary: string | null
  issueType: string | null
  recommendedAction: string | null
  missingInformation: string[]
  sourceEvidence: CsCopilotSourceEvidenceResponse[]
  editedText: string | null
  createTime: string | null
  updateTime: string | null
  acceptedAt: string | null
  editedAt: string | null
  rejectedAt: string | null
}

export interface CsCopilotEditRequest {
  editedText: string
}

export interface CsCopilotRejectRequest {
  reason?: string | null
}

export interface CsAgentVO {
  userId: number
  agentName: string
  agentStatus: number
  activeSessionCount: number
  totalCount: number
}

export interface CsSkillGroupVO {
  id: number
  groupCode: string
  groupName: string
  leaderUserId?: number | null
  leaderName?: string | null
  activeCount: number
  totalCount: number
  waitingCount: number
  overdueCount: number
  agents: CsAgentVO[]
}

export interface CsOrgTreeVO {
  activeCount: number
  totalCount: number
  publicPoolCount: number
  publicPoolTimeoutCount: number
  aiResolvedCount: number
  aiHumanCount: number
  groups: CsSkillGroupVO[]
}

export interface CsSessionVO {
  id: number
  userId: number
  userNickname?: string | null
  userPhoneMask?: string | null
  subject?: string | null
  status: CsSessionStatus | string
  sourceType?: 'AI' | 'HUMAN' | string | null
  assignedAgentId?: number | null
  assignedAgentName?: string | null
  skillGroupId?: number | null
  skillGroupName?: string | null
  lastMessage?: string | null
  createTime?: string | null
  updateTime?: string | null
  closedAt?: string | null
  slaTimeoutFlag?: boolean | null
  slaOverdue?: boolean | null
  userWaitingSeconds?: number | null
  needAudit?: boolean | null
  latestAuditScore?: number | null
  escalatedToAdmin?: boolean | null
}

export interface CsSessionMessageVO {
  id: number
  sessionId: number
  senderUserId?: number | null
  senderType: SupportMessageSenderType
  senderDisplayName?: string | null
  content: string
  createTime?: string | null
}

export interface CsSessionQueryParams {
  groupId?: number
  agentId?: number
  unassignedOnly?: boolean
  sourceType?: string
  status?: CsSessionStatus
  slaTimeoutOnly?: boolean
  keyword?: string
  page?: number
  size?: number
  sort?: CsSessionSort
}

export interface CsTransferRequest {
  targetGroupId: number
  targetAgentId: number
  transferNote: string
}

export interface CsAuditRequest {
  score: number
  comments: string
  isResolved: boolean
}

export interface CsInternalNoteRequest {
  content: string
}

export interface CsInternalNoteVO extends CsInternalNoteRequest {
  id?: number
  sessionId?: number
  authorUserId?: number | null
  authorDisplayName?: string | null
  createTime?: string | null
}

export interface CsRecentOrderContextVO {
  orderId?: number | string | null
  orderNo?: string | null
  activityName?: string | null
  venueName?: string | null
  sessionTime?: string | null
  ticketName?: string | null
  seatLabels?: string | null
  status?: number | null
  fulfillmentStatus?: string | null
}

export interface CsUserSessionHistoryVO {
  sessionId: number
  createdAt?: string | null
  agentName?: string | null
  category?: string | null
  status: 'ACTIVE' | 'CLOSED' | string
  closedAt?: string | null
  closeReason?: string | null
}

export interface SupportContextVO {
  conversationId: number
  user: {
    userId: number
    nickname?: string | null
    phoneMask?: string | null
  }
  orders: Array<{
    id: number
    orderNo?: string | null
    status?: number | null
    amount?: number | null
    activityName?: string | null
    sessionTime?: string | null
    href?: string | null
  }>
  refunds: Array<{
    id: number
    orderId?: number | null
    orderNo?: string | null
    status?: number | null
    reason?: string | null
    href?: string | null
  }>
  tickets: Array<{
    ticketId: number
    orderId?: number | null
    activityName?: string | null
    sessionTime?: string | null
    status?: number | null
    checkedIn?: boolean | null
    href?: string | null
  }>
  waitlist: Array<{
    id: number
    sessionId?: number | null
    ticketTypeId?: number | null
    quantity?: number | null
    status?: string | null
    rank?: number | null
    estimatedWaitText?: string | null
  }>
  grabRequests: Array<{
    requestId: string
    sessionId?: number | null
    ticketTypeId?: number | null
    quantity?: number | null
    status?: string | null
    queueRank?: number | null
    progressMessage?: string | null
    failReason?: string | null
    href?: string | null
  }>
  notifications: Array<{
    id: number
    title?: string | null
    content?: string | null
    type?: string | null
    read?: boolean | null
    createTime?: string | null
  }>
  errors: Array<{
    section: string
    message: string
  }>
}

export interface RbacRoleVO {
  code: string
  name: string
  status: number
  permissionCodes: string[]
}

export interface RbacPermissionVO {
  code: string
  name: string
  description?: string | null
}

export interface RbacUserPermissionSummaryVO {
  userId: number
  nickname: string | null
  phone: string | null
  role: string | null
  effectiveRole: string
  baseRoleName: string
}

export interface RbacUserPermissionVO extends RbacUserPermissionSummaryVO {
  inheritedPermissionCodes: string[]
  allowPermissionCodes: string[]
  denyPermissionCodes: string[]
  effectivePermissionCodes: string[]
}

export interface RbacUserPermissionOverridePayload {
  allowPermissionCodes: string[]
  denyPermissionCodes: string[]
  reason?: string | null
}

export interface OrganizerAdminAccountVO {
  id: number
  phone: string
  nickname: string | null
  role: 'organizer_admin'
  status: number
  createTime?: string | null
  updateTime?: string | null
}

export interface OrganizerOpsAssignmentVO {
  organizerUserId: number
  assignedOperatorId?: number | null
  riskLevel: string
  status: string
  nextFollowAt?: string | null
  lastFollowAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface OrganizerOpsAssignmentPayload {
  assignedOperatorId?: number | null
  riskLevel?: string | null
  status?: string | null
  nextFollowAt?: string | null
}

export interface OrganizerOpsFollowUpVO {
  id: number
  organizerUserId: number
  operatorId: number
  followType: string
  content: string
  nextFollowAt?: string | null
  createTime?: string | null
}

export interface OrganizerOpsFollowUpPayload {
  followType: string
  content: string
  nextFollowAt?: string | null
}

export interface OperationAuditLogVO {
  id: number
  operatorId: number
  operatorRole: string
  action: string
  targetType: string
  targetId?: number | null
  targetRef?: string | null
  reason?: string | null
  result?: string | null
  success: boolean
  errorMessage?: string | null
  traceId?: string | null
  createTime?: string | null
}

export interface ExceptionTaskVO {
  id: number
  taskType: string
  businessNo?: string | null
  orderNo?: string | null
  paymentNo?: string | null
  refundNo?: string | null
  ticketNo?: string | null
  severity: string
  status: string
  reason?: string | null
  result?: string | null
  operatorId?: number | null
  operatorRole?: string | null
  traceId?: string | null
  createTime?: string | null
}

export interface ExceptionTaskCreatePayload {
  taskType: string
  businessNo?: string | null
  orderNo?: string | null
  paymentNo?: string | null
  refundNo?: string | null
  ticketNo?: string | null
  severity: string
  reason?: string | null
  evidenceUrls?: string[]
}

export interface ExceptionTaskActionPayload {
  result: string
}

export interface ReconciliationBatchVO {
  id: number
  batchNo: string
  bizDate: string
  sourceType: string
  status: string
  summaryJson?: string | null
  createTime?: string | null
}

export interface ReconciliationDetailVO {
  id: number
  batchNo: string
  businessNo: string
  businessType: string
  expectedAmount?: number | null
  actualAmount?: number | null
  status: string
  createTime?: string | null
}

export interface ReconciliationDifferenceVO {
  id: number
  batchNo: string
  diffType: string
  businessNo?: string | null
  expectedAmount?: number | null
  actualAmount?: number | null
  diffAmount?: number | null
  reason?: string | null
  status: string
  createTime?: string | null
}

export interface ReconciliationBatchDetailVO {
  batch: ReconciliationBatchVO
  details: ReconciliationDetailVO[]
  differences: ReconciliationDifferenceVO[]
}

export interface TourEntity {
  id: number
  title: string
  artistId?: number | null
  categoryId?: number | null
  poster?: string | null
  description?: string | null
  organizerId: number
  reviewStatus: string
  status: number
  createTime?: string | null
  updateTime?: string | null
}

export interface TourStationCityPayload {
  city: string
  stationName?: string | null
}

export interface TourDraftCreatePayload {
  userId?: number
  title: string
  categoryId?: number | null
  artistId?: number | null
  poster?: string | null
  description?: string | null
  organizerId?: number | null
  cities: TourStationCityPayload[]
}

export type StationPublishStatus =
  | 'draft'
  | 'city_announced'
  | 'venue_pending'
  | 'venue_rejected'
  | 'venue_approved'
  | 'venue_confirmed'
  | 'publishing'
  | 'published'
  | 'risk_suspended'
  | 'deactivated'
  | 'cancelled'
  | string

export type StationSaleStatus =
  | 'unannounced'
  | 'coming_soon'
  | 'ticket_tba'
  | 'to_be_scheduled'
  | 'on_sale'
  | 'sold_out'
  | 'suspended'
  | 'deactivated'
  | string

export type StationConfigChangeType =
  | 'create'
  | 'update_city'
  | 'set_venue'
  | 'change_venue'
  | 'set_schedule'
  | 'change_schedule'
  | 'delete_station'
  | string

export type StationConfigVersionStatus =
  | 'draft'
  | 'submitted'
  | 'applied'
  | 'rejected'
  | 'withdrawn'
  | string

export interface StationEntity {
  id: number
  tourId?: number | null
  activityId?: number | null
  city: string | null
  stationName: string | null
  poster?: string | null
  description?: string | null
  venueApplicationId?: number | null
  publishStatus: StationPublishStatus
  status: number
  createTime?: string | null
  updateTime?: string | null
}

export interface StationConfigVersionVO {
  id: number
  stationId: number
  activityId?: number | null
  tourId?: number | null
  versionNo?: number | null
  changeType: StationConfigChangeType
  status: StationConfigVersionStatus
  city?: string | null
  stationName?: string | null
  venueId?: number | null
  venueApplicationId?: number | null
  venueName?: string | null
  venueAddress?: string | null
  startTime?: string | null
  endTime?: string | null
  scheduleTba?: boolean | null
  seatTemplateSourceType?: string | null
  seatTemplateSourceId?: number | null
  reason?: string | null
  reviewerId?: number | null
  reviewNote?: string | null
  reviewTime?: string | null
  createdBy?: number | null
  createdAt?: string | null
  updatedAt?: string | null
  appliedAt?: string | null
}

export interface StationConfigReviewListParams {
  page?: number
  size?: number
  keyword?: string
  city?: string
  changeType?: string
  status?: StationConfigVersionStatus | ''
}

export interface StationConfigReviewSnapshotVO {
  stationId?: number | null
  activityId?: number | null
  tourId?: number | null
  city?: string | null
  stationName?: string | null
  venueId?: number | null
  venueName?: string | null
  venueAddress?: string | null
  startTime?: string | null
  endTime?: string | null
  scheduleTba?: boolean | null
  seatTemplateSourceType?: string | null
  seatTemplateSourceId?: number | null
  totalStock?: number | null
}

export interface StationConfigReviewDiffVO {
  current?: StationConfigReviewSnapshotVO | null
  target?: StationConfigReviewSnapshotVO | null
  version: StationConfigVersionVO
  highRisk?: boolean | null
  warning?: string | null
}

export interface ActivityDraftPayload {
  categoryId?: number | null
  artistId?: number | null
  artists?: Array<{
    artistId: number
    isPrimary?: boolean | null
    primary?: boolean | null
    roleType?: string | null
    roleName?: string | null
    visibility?: string | null
    sort?: number | null
  }> | null
  name?: string | null
  description?: string | null
  poster?: string | null
  seatMapVisibility?: 'published' | 'hidden' | null
  realNameRequired?: boolean | null
  ticketTransferAllowed?: boolean | null
  refundFullDaysBefore?: number | null
  refundPartialDaysBefore?: number | null
  refundPartialRate?: number | null
}

export interface StationConfigVersionPayload {
  userId?: number | null
  changeType: StationConfigChangeType
  city?: string | null
  stationName?: string | null
  venueId?: number | null
  venueApplicationId?: number | null
  venueName?: string | null
  venueAddress?: string | null
  startTime?: string | null
  endTime?: string | null
  scheduleTba?: boolean | null
  seatTemplateSourceType?: string | null
  seatTemplateSourceId?: number | null
  reason?: string | null
}

export interface ActivityDraftResponseVO {
  activity: ActivityEntity
  station: StationEntity
}

export interface StationConfigVersionDetailVO {
  station: StationEntity
  versions: StationConfigVersionVO[]
}

export interface TourDetailVO {
  tour: TourEntity
  stations: StationEntity[]
  stationDetails?: StationPurchaseDetail[]
}

export interface TourAdminDetailVO extends TourDetailVO {
  stationDetails: StationPurchaseDetail[]
}

export interface StationPurchaseDetail {
  station: StationEntity
  activity?: ActivityEntity | null
  sessions: Array<SessionEntity | SessionDetail>
  venueName?: string | null
  venueAddress?: string | null
  priceMin?: number | null
  priceMax?: number | null
  remainStock?: number | null
  saleStatus?: StationSaleStatus | null
  saleStatusText?: string | null
  primaryAction?: 'buy' | 'none' | null
}

/** 场馆 */
export interface VenueEntity {
  id: number
  name: string
  venueNameEn?: string | null
  venueType?: string | null
  address: string
  city: string
  province?: string | null
  district?: string | null
  capacity?: number | null
  status?: number
}

export type VenueApplicationStatus = 0 | 1 | 2
export type VenueApplicationReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type VenueApplicationCapacityScale = 'EXTRA_LARGE' | 'LARGE' | 'MEDIUM' | 'SMALL'
export type VenueApplicationMaterialType = 'FIRE_SAFETY_PERMIT' | 'VENUE_LEASE_AGREEMENT' | 'LEGACY_GENERAL_PROOF'

export interface VenueApplicationMaterialVO {
  id?: number | null
  materialType: VenueApplicationMaterialType | string
  assetId?: number | null
  note?: string | null
  validFrom?: string | null
  validTo?: string | null
  asset?: PrivateAssetVO | null
  label: string
  legacy?: boolean | null
}

export interface VenueApplicationVO {
  id: number
  applicantId: number
  venueId: number | null
  venueName: string
  venueNameEn?: string | null
  venueType?: string | null
  city: string
  province?: string | null
  district?: string | null
  address: string
  capacity: number | null
  contactName: string
  contactPhone: string
  qualificationNo: string | null
  businessScope: string | null
  description: string | null
  validFrom?: string | null
  validTo?: string | null
  proofNote?: string | null
  proofFileUrl?: string | null
  proofAssetId?: number | null
  proofAsset?: PrivateAssetVO | null
  materials?: VenueApplicationMaterialVO[]
  legacyProof?: VenueApplicationMaterialVO | null
  capacityScale?: VenueApplicationCapacityScale | null
  materialCompleteness?: string | null
  status: VenueApplicationStatus
  reviewerId: number | null
  reviewNote: string | null
  createTime: string
  updateTime: string | null
  reviewTime: string | null
}

export interface VenueAreaVO {
  id: number
  venueId: number
  name: string
  rowCount: number
  seatsPerRow: number
  rowStart: number
  seatStart: number
  color: string | null
  sort: number
  status: number
}

export interface VenueSeatVO {
  id: number
  venueId: number
  areaId: number
  rowNo: number
  seatNo: number
  seatLabel?: string | null
  x?: number | null
  y?: number | null
  status: number
  createTime?: string | null
}

export interface VenueSeatRequest {
  userId: number
  venueId?: number
  areaId: number
  rowNo: number
  seatNo: number
  seatLabel?: string | null
  x?: number | null
  y?: number | null
  status: number
}

export interface SeatTemplateSyncResponseVO {
  syncedSessionCount: number
  skippedSessionCount: number
  skippedSessionIds: number[]
}

export interface SessionAreaStockVO extends VenueAreaVO {
  availableSeatCount: number
}

export interface SessionSeatVO {
  id: number
  sessionId: number
  venueId: number
  areaId: number | null
  venueSeatId: number | null
  rowNo: number
  seatNo: number
  seatLabel: string
  status: number
  lockExpireTime: string | null
  orderId: number | null
  ticketTypeId: number | null
  layoutSectionId?: number | null
  seatBlockId?: number | null
  ticketGroupKey?: string | null
  generatedRowNo?: number | null
  generatedSeatNo?: number | null
}

export type SeatCraftTemplateType = 'concert' | 'cinema' | 'custom'
export type SeatCraftSectionType = 'core' | 'stand' | 'zone'
export type SeatCraftSectionLayout = 'grid' | 'curved'
export type SeatCraftBlockType = 'gridBlock' | 'arcBlock' | 'standingBlock' | 'polygonBlock'

export interface SeatCraftBindingVO {
  blockKey: string
  groupKey: string
  bindingRole?: string | null
  sort?: number | null
}

export interface SeatCraftBlockVO {
  id: string | number
  blockKey: string
  name: string
  blockType: SeatCraftBlockType
  ticketGroupKey: string
  x: number
  y: number
  rotation: number
  scale: number
  rows?: number | null
  cols?: number | null
  seatsPerRow?: number | null
  rowSpacing?: number | null
  seatSpacing?: number | null
  innerRadius?: number | null
  arcStartAngle?: number | null
  arcEndAngle?: number | null
  width?: number | null
  height?: number | null
  capacity?: number | null
  polygonPoints?: Array<{ x: number; y: number }> | string | null
  color: string
  sort: number
}

export interface SeatOverrideVO {
  blockKey: string
  rowNo: number
  seatNo: number
  status: 'visible' | 'hidden' | 'deleted'
  dx?: number | null
  dy?: number | null
  customLabel?: string | null
}

export interface TicketGroupVO {
  groupKey: string
  name: string
  defaultPrice?: number | null
  activityPrice?: number | null
  sourceBlockKeys: string[]
  sort: number
}

export interface SeatCraftSectionVO {
  id: number
  sectionKey: string
  name: string
  rows: number
  cols: number
  x: number
  y: number
  color: string
  type: SeatCraftSectionType
  layout: SeatCraftSectionLayout
  radius?: number | null
  arcSpan?: number | null
  rotation?: number | null
  primeRowStart?: number | null
  primeRowEnd?: number | null
  primeColStart?: number | null
  primeColEnd?: number | null
  seatCount?: number | null
  ticketTypeId?: number | null
  price?: number | null
}

export interface SeatCraftLayoutVO {
  id: number
  versionId?: number | null
  versionNo?: number | null
  versionStatus?: string | null
  venueId?: number | null
  activityId?: number | null
  sessionId?: number | null
  name: string
  templateType: SeatCraftTemplateType
  stageTitle: string
  stageX: number
  stageY: number
  canvasWidth: number
  canvasHeight: number
  sections: SeatCraftSectionVO[]
  seats?: SessionSeatVO[]
  blocks?: SeatCraftBlockVO[]
  overrides?: SeatOverrideVO[]
  ticketGroups?: TicketGroupVO[]
  bindings?: SeatCraftBindingVO[]
  blockLayout?: {
    versionId?: number | null
    versionNo?: number | null
    versionStatus?: string | null
    name?: string | null
    canvasWidth?: number | null
    canvasHeight?: number | null
    blocks?: SeatCraftBlockVO[]
    overrides?: SeatOverrideVO[]
    ticketGroups?: TicketGroupVO[]
    bindings?: SeatCraftBindingVO[]
  } | null
}

export interface SeatCraftVersionedLayoutVO {
  versionId?: number | null
  versionNo?: number | null
  versionStatus?: string | null
  name: string
  canvasWidth: number
  canvasHeight: number
  blocks?: SeatCraftBlockVO[]
  overrides?: SeatOverrideVO[]
  ticketGroups?: TicketGroupVO[]
  bindings?: SeatCraftBindingVO[]
  id?: number | null
  venueId?: number | null
  activityId?: number | null
  sessionId?: number | null
  templateType?: SeatCraftTemplateType | null
  stageTitle?: string | null
  stageX?: number | null
  stageY?: number | null
  sections?: SeatCraftSectionVO[] | null
}

export interface SeatCraftVersionedLayoutRequest {
  versionId?: number | null
  versionNo?: number | null
  versionStatus?: string | null
  name: string
  templateType: SeatCraftTemplateType
  stageTitle: string
  stageX: number
  stageY: number
  canvasWidth: number
  canvasHeight: number
  blocks: SeatCraftVersionedBlockRequest[]
  overrides: SeatOverrideVO[]
  ticketGroups: TicketGroupVO[]
  bindings: SeatCraftBindingVO[]
}

export type SeatCraftVersionedBlockRequest = Omit<SeatCraftBlockVO, 'polygonPoints'> & {
  polygonPoints?: string | null
}

export interface SeatCraftVersionSummaryVO {
  id: number
  versionNo?: number | null
  versionStatus?: string | null
  name?: string | null
  baseVersionId?: number | null
  publishedAt?: string | null
  publishedBy?: number | null
  createTime?: string | null
  updateTime?: string | null
}

export interface SeatLayoutTemplateCandidateVO {
  sourceType: 'venue_application' | 'legacy_venue_default'
  sourceId: number
  name: string
  createTime?: string | null
  layout: SeatCraftLayoutVO
}

export interface SeatMapResponse {
  sessionId: number
  ticketTypeId: number
  ticketTypeName: string
  price: number
  stageLabel: string
  areas: VenueAreaVO[]
  seats: SessionSeatVO[]
  layout?: SeatCraftLayoutVO | null
}

export interface SeatTemplateResponse {
  area: VenueAreaVO
  generatedSeatCount: number
  syncResult?: SeatTemplateSyncResponseVO | null
}

/** 票档 */
export interface TicketTypeEntity {
  id: number
  sessionId: number
  name: string
  price: number
  totalStock: number
  remainStock: number
  seatBlockId?: number | null
  ticketGroupKey?: string | null
  status: number
}

export interface AdminTicketTypeCreateRequest {
  userId: number
  sessionId: number
  name: string
  price: number
  totalStock?: number
  layoutSectionIds?: number[]
  sourceBlockKeys?: string[]
  areaIds?: number[]
}

export type TicketTypeBatchUpdateAction = 'UPDATE_PRICE' | 'SET_STATUS' | 'ADJUST_STOCK'

export interface TicketTypeBatchUpdateRequest {
  ids: number[]
  action: TicketTypeBatchUpdateAction
  price?: number
  status?: number
  totalStock?: number
}

export interface SessionTicketBindingRequest {
  userId: number
  bindings: Array<{
    ticketTypeId: number
    blockKeys: string[]
  }>
}

/** 场次详情（含票档和场馆） */
export interface SessionDetail {
  session: SessionEntity
  venue: VenueEntity
  ticketTypes: TicketTypeEntity[]
}

/** 活动详情 */
export interface ActivityDetailVO {
  activity: ActivityEntity
  category: CategoryVO
  artist: ArtistEntity
  artists?: ActivityArtistVO[]
  sessions: SessionDetail[]
  isTour?: boolean
  eventType?: 'TOUR' | 'SINGLE' | string
  tour?: TourEntity | null
  stationDetails?: StationPurchaseDetail[]
}

export interface ActivityReviewSummaryVO {
  reviewCount: number
  averageRating: number
  ratingDistribution: Record<string, number>
}

export interface ActivityReviewVO {
  id?: number | null
  activityId: number
  userId: number
  orderId?: number | null
  rating: number
  content?: string | null
  images?: string | null
  likeCount?: number | null
  status?: number | null
  createTime?: string | null
}

export interface ActivityReviewReportVO {
  id?: number | null
  reviewId: number
  activityId: number
  userId: number
  reason: string
  status: 'PENDING' | 'RESOLVED' | 'REJECTED' | string
  handledBy?: number | null
  handleNote?: string | null
  createTime?: string | null
  handledAt?: string | null
}

export interface ActivityReviewModerationRequest {
  action: 'APPROVE' | 'HIDE' | 'RESTORE'
  note?: string | null
}

export interface ActivityReviewReportModerationRequest {
  action: 'RESOLVE' | 'HIDE' | 'REJECT'
  note?: string | null
}

export interface ActivityReviewListVO {
  summary: ActivityReviewSummaryVO
  reviews: ActivityReviewVO[]
}

export interface ActivityQuestionVO {
  id?: number | null
  activityId: number
  userId: number
  content: string
  answer?: string | null
  answeredBy?: number | null
  replyIdentity?: 'OFFICIAL_SUPPORT' | 'ORGANIZER_PROXY' | string | null
  status: 'PENDING' | 'ANSWERED' | 'HIDDEN' | string
  createTime?: string | null
  answeredAt?: string | null
}

export interface ActivityQuestionModerationRequest {
  action: 'ANSWER' | 'HIDE' | 'RESTORE'
  answer?: string | null
}

export type ActivityEngagementTargetType = 'ACTIVITY' | 'TOUR' | string

export interface ActivityEngagementOverviewVO {
  targetId: number
  targetType: ActivityEngagementTargetType
  activityName: string
  poster?: string | null
  organizerId?: number | null
  organizerName?: string | null
  pendingReviewCount?: number | null
  publishedReviewCount?: number | null
  hiddenReviewCount?: number | null
  totalReviewCount?: number | null
  pendingQuestionCount?: number | null
  answeredQuestionCount?: number | null
  hiddenQuestionCount?: number | null
  totalQuestionCount?: number | null
  pendingReportCount?: number | null
  totalReportCount?: number | null
  averageRating?: number | null
}

export interface ActivityQuestionReplyRequest {
  itemType?: ActivityEngagementTargetType
  answer: string
  replyIdentity: 'OFFICIAL_SUPPORT' | 'ORGANIZER_PROXY'
}

export interface ActivityQuestionUpdateRequest {
  itemType?: ActivityEngagementTargetType
  answer?: string | null
  replyIdentity?: 'OFFICIAL_SUPPORT' | 'ORGANIZER_PROXY' | null
  status?: 'PENDING' | 'ANSWERED' | 'HIDDEN' | string | null
  reason: string
}

export interface ActivityReviewStatusRequest {
  itemType?: ActivityEngagementTargetType
  status: number
  reason?: string | null
}

export interface ActivityReviewReportStatusRequest {
  itemType?: ActivityEngagementTargetType
  action: 'RESOLVE' | 'HIDE' | 'REJECT'
  reason?: string | null
}

/** 预约 */
export interface ReservationEntity {
  id: number
  userId: number
  sessionId: number
  createTime: string
}

export type SubscriptionTargetType =
  | 'ACTIVITY_WANT'
  | 'SALE_REMINDER'
  | 'WAITLIST_REMINDER'
  | 'TOUR_CITY_REMINDER'
  | 'ARTIST_FOLLOW'
  | 'CITY_FOLLOW'
  | string

export interface SubscriptionPayload {
  targetType: SubscriptionTargetType
  targetId?: number | null
  targetValue?: string | null
  activityId?: number | null
  artistId?: number | null
  city?: string | null
  remindBeforeMinutes?: number | null
}

export interface SubscriptionVO {
  id: number
  userId: number
  targetType: SubscriptionTargetType
  targetId?: number | null
  targetValue?: string | null
  targetName?: string | null
  activityId?: number | null
  activityName?: string | null
  activityPoster?: string | null
  artistId?: number | null
  artistName?: string | null
  city?: string | null
  sessionId?: number | null
  startTime?: string | null
  venueName?: string | null
  saleStatusText?: string | null
  readyChecklist?: string[] | null
  remindBeforeMinutes?: number | null
  status: number
  createTime?: string | null
}

/** 订单 */
export interface OrderEntity {
  id: number
  orderNo: string
  userId: number
  userPhoneMask?: string | null
  sessionId: number
  ticketTypeId: number
  quantity: number
  amount: number
  status: number
  userHidden?: boolean | null
  userDeletedAt?: string | null
  userDeleteExpiresAt?: string | null
  createTime: string
  activityId?: number | null
  activityName?: string | null
  activityPoster?: string | null
  venueName?: string | null
  sessionTime?: string | null
  ticketName?: string | null
  unitPrice?: number | null
  seatLabels?: string | null
  seatSelectionMode?: 'NONE' | 'RANDOM' | 'EXPLICIT' | 'TEAM' | string | null
  grabRequestId?: string | null
  requestedTicketTypeId?: number | null
  matchedTicketTypeId?: number | null
  autoDowngraded?: boolean | null
  attendees?: OrderAttendeeVO[]
}

export interface TicketWalletItemVO {
  ticketId: number
  ticketNo: string
  orderId: number
  orderSeatId?: number | null
  sessionId: number
  ticketTypeId: number
  activityName?: string | null
  activityPoster?: string | null
  venueName?: string | null
  sessionTime?: string | null
  ticketName?: string | null
  seatLabel?: string | null
  realName?: string | null
  idNoMask?: string | null
  status: 1 | 2 | 3 | 4 | number
  statusText?: string | null
  checkedInAt?: string | null
}

export interface TicketEntryCodeVO {
  ticketId: number
  entryCode: string
  expiresAt: string
}

export interface TicketTransferCreateVO {
  transferId?: number | null
  ticketId: number
  transferCode: string
  status: number
  statusText?: string | null
  expiresAt: string
}

export interface TicketTransferClaimVO {
  transferId?: number | null
  originalTicketId: number
  ticketId: number
  status: number
  statusText?: string | null
}

export interface TicketTransferRevokeVO {
  transferId?: number | null
  ticketId: number
  status: number
  statusText?: string | null
}

export interface PagePayResponse {
  orderId: number
  orderNo: string
  payForm: string
}

export interface QrPayResponse {
  orderId: number
  orderNo: string
  amount: number
  subject: string
  qrCode: string
  qrCodeUrl?: string
}

export interface PaymentStatusResponse {
  orderId: number
  orderNo: string
  orderStatus: number
  paymentStatus: number
  tradeNo: string | null
  message: string
}

export type RefundStatus = 0 | 1 | 2 | 3 | 4

export interface RefundRequestVO {
  id: number
  orderId: number
  orderNo: string
  activityName?: string | null
  orderName?: string | null
  userId: number
  paymentId: number
  refundNo: string
  amount: number
  reason: string | null
  status: RefundStatus
  reviewerId: number | null
  reviewNote: string | null
  alipayRefundNo: string | null
  quantity?: number | null
  orderSeatIds?: string | null
  refundType?: 'full' | 'partial' | string | null
  createTime: string
  reviewTime: string | null
  refundTime: string | null
}

export interface RefundSeatOptionVO {
  orderSeatId: number
  sessionSeatId: number
  sessionId: number
  ticketTypeId: number
  seatLabel?: string | null
}

export interface RefundOptionsVO {
  orderId: number
  totalQuantity: number
  refundedQuantity: number
  refundableQuantity: number
  unitPrice: number
  seats: RefundSeatOptionVO[]
}

export interface DirectRefundResponse {
  orderId: number | null
  orderNo: string | null
  status: 'SUCCESS' | 'FAILED' | 'UNKNOWN' | 'COMPENSATION_REQUIRED'
  success: boolean
  message: string | null
}

export interface RefundImpactResponse {
  activityId: number
  activityName: string
  deactivatedActivityCount: number
  deactivatedSessionCount: number
  deactivatedTicketTypeCount: number
  paidOrderCount: number
  refundSuccessCount: number
  refundFailedCount: number
  refundUnknownCount: number
  refundCompensationRequiredCount: number
  failures: DirectRefundResponse[]
}

export interface ActivityBuyerNotificationResponse {
  activityId: number
  activityName: string
  paidOrderCount: number
  notifiedUserCount: number
  notificationCount: number
  skippedOrderCount: number
}

export interface DeactivateOrganizerRequest {
  userId: number
  organizerId: number
  confirmRefund: boolean
  reason?: string
}

/* ============ 管理端写请求 DTO（收敛原 Record<string, unknown> 参数） ============ */

export interface ActivityStatusUpdateRequest {
  userId?: number
  status: number
}

export interface AdminActivityArtistPayload {
  artistId: number
  isPrimary?: boolean
  roleType?: string
  roleName?: string
  visibility?: string
  sort?: number
}

export interface AdminActivityUpsertRequest {
  userId?: number
  name: string
  categoryId: number
  artists: AdminActivityArtistPayload[]
  poster?: string | null
  description?: string | null
  realNameRequired?: boolean
  ticketTransferAllowed?: boolean
  refundFullDaysBefore?: number
  refundPartialDaysBefore?: number
  refundPartialRate?: number
  tourId?: number
  stationId?: number
  venueApplicationId?: number | null
  venueApprovalNo?: string | null
  venueApprovalFileUrl?: string | null
  venueApprovalNote?: string | null
  publishStatus?: string
  seatMapVisibility?: string
}

export interface AdminSessionUpsertRequest {
  userId?: number
  activityId?: number
  venueId?: number
  startTime?: string
  endTime?: string | null
  status?: number
}

export interface AdminTicketTypeUpdateRequest {
  name?: string
  price?: number
  totalStock?: number
  status?: number
}

export interface AdminVenueUpsertRequest {
  userId?: number
  name: string
  city?: string
  address?: string
  capacity?: number | null
}

export interface VenueAreaCreateRequest {
  userId?: number
  name: string
  rowCount: number
  seatsPerRow: number
  rowStart?: number
  seatStart?: number
  color?: string
  sort?: number
}

export interface StationDraftCreateRequest {
  userId?: number
  city: string
  stationName?: string | null
  poster?: string | null
  description?: string | null
  announceOnly?: boolean
  venueApplicationId?: number | null
}

export interface StationPublishRequest {
  userId?: number
  scheduleTba?: boolean
  startTime?: string | null
  endTime?: string | null
}

export interface StationPublishResponse {
  tour?: TourEntity | null
  station: StationEntity
  activity: ActivityEntity
  session: SessionEntity | null
}

export interface VenueApplicationMaterialPayload {
  materialType: VenueApplicationMaterialType
  assetId: number
  validFrom?: string
  validTo?: string
}

export interface VenueApplicationLayoutSectionPayload {
  id: number
  sectionKey: string
  name: string
  rows: number
  cols: number
  x: number
  y: number
  color: string
  type: string
  layout: string
  radius?: number | null
  arcSpan?: number | null
  rotation?: number | null
  primeRowStart?: number | null
  primeRowEnd?: number | null
  primeColStart?: number | null
  primeColEnd?: number | null
  ticketTypeId?: number | null
}

export interface VenueApplicationLayoutPayload {
  id: number
  name: string
  templateType: string
  stageTitle: string
  stageX: number
  stageY: number
  canvasWidth: number
  canvasHeight: number
  sections: VenueApplicationLayoutSectionPayload[]
  blocks?: SeatBlockDraft[]
  overrides?: SeatOverrideDraft[]
  ticketGroups?: TicketGroupDraft[]
}

export interface VenueApplicationSubmitRequest {
  userId?: number
  venueId?: number | null
  venueName?: string
  venueNameEn?: string
  venueType?: string
  city?: string
  province?: string
  district?: string
  address?: string
  capacity?: number | null
  contactName?: string
  contactPhone?: string
  qualificationNo?: string | null
  businessScope?: string | null
  description?: string | null
  validFrom?: string
  validTo?: string
  proofNote?: string | null
  proofFileUrl?: string | null
  proofAssetId?: number | null
  layoutSnapshot?: string
  layout?: VenueApplicationLayoutPayload
  materials?: VenueApplicationMaterialPayload[]
}

export interface VenueApplicationReviewRequest {
  userId?: number
  action?: string
  mode?: string
  venueId?: number | null
  reviewNote?: string | null
}
