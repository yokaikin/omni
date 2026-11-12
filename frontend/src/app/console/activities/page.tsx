'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import { usePathname, useSearchParams } from 'next/navigation'
import { getToken, getUser } from '@/lib/auth'
import { announceTourCities, deleteTourDraft, deactivateTour, getActivityStation, listAdminActivities, listAdminTours, deleteAdminActivity, updateActivityStatus, deactivateActivity, notifyActivityBuyers, publishStation, submitActivityRiskResolution, suspendActivityForRisk, privateAssetDownloadUrl, listCategories } from '@/lib/api'
import { getRealNameRequirementLabel, getTicketTransferAllowedLabel } from '@/lib/activity-flags'
import { canUseConsoleAction, hasConsolePermission, isPlatformAdminRole } from '@/lib/console-auth'
import { Bell, Trash2, Eye, EyeOff, RefreshCw, Search, FileDown, MoreHorizontal } from 'lucide-react'
import { globalAlert, globalConfirm } from '@/components/GlobalDialog'
import { GlobalPagination } from '@/components/Pagination'
import { SafeImage } from '@/components/SafeImage'
import { Modal } from '@/components/ui/Modal'
import type { ActivityBuyerNotificationResponse, ActivityEntity, CategoryVO, PageResult, RefundImpactResponse, TourEntity, UserRole } from '@/types/api'

const PAGE_SIZE = 10
const ADMIN_FETCH_SIZE = 500

function compareActivityRows(a: ActivityEntity, b: ActivityEntity) {
  const byId = a.id - b.id
  if (byId !== 0) return byId
  return (a.itemType || 'activity').localeCompare(b.itemType || 'activity')
}

function parsePrivateAssetRef(value?: string | null) {
  if (!value?.startsWith('private-asset:')) return null
  const id = Number(value.slice('private-asset:'.length))
  return Number.isInteger(id) && id > 0 ? id : null
}

function activityRowKey(activity: ActivityEntity) {
  return `${activity.itemType || 'activity'}-${activity.id}`
}

function getTourRowStatus(tour: { reviewStatus?: string | null; status: number }) {
  return tour.reviewStatus === 'deactivated' || tour.reviewStatus === 'risk_suspended' ? 0 : tour.status
}

function getActivityStatusText(activity: ActivityEntity) {
  if (activity.publishStatus === 'draft') return '草稿'
  if (activity.publishStatus === 'risk_suspended') return '风险停票'
  if (activity.publishStatus === 'deactivated') return '下架'
  if (activity.publishStatus === 'announced') return '上架'
  if (activity.status === 1) return '上架'
  if (activity.status === 0) return '下架'
  return '未知活动状态'
}

function getActivityStatusClass(activity: ActivityEntity) {
  if (activity.publishStatus === 'draft') return 'bg-[#fff7ed] text-[#f97316]'
  if (activity.publishStatus === 'risk_suspended') return 'bg-[#fff1f2] text-[#e11d48]'
  if (activity.status === 1) return 'bg-[#f0fff4] text-[#22c55e]'
  if (activity.status === 0) return 'bg-[#f5f5f5] text-[#999]'
  return 'bg-[#fff7ed] text-[#f97316]'
}

function isKnownActivitySaleStatus(status: number) {
  return status === 1 || status === 0
}

function getActivitySaleActionTitle(activity: ActivityEntity) {
  if (!isKnownActivitySaleStatus(activity.status)) return '状态待核对'
  return activity.itemType === 'tour' || activity.status === 1 ? '下架并退款' : '上架'
}

function nextActivitySaleStatus(status: number) {
  return status === 1 ? 0 : 1
}

function shouldShowSaleStatusControl(activity: ActivityEntity) {
  if (activity.publishStatus === 'draft' || activity.publishStatus === 'risk_suspended') return false
  if (activity.itemType === 'tour' && activity.publishStatus === 'deactivated') return false
  return true
}

function canToggleSaleStatus(activity: ActivityEntity) {
  if (!isKnownActivitySaleStatus(activity.status)) return false
  if (activity.publishStatus === 'draft' || activity.publishStatus === 'risk_suspended') return false
  if (activity.itemType === 'tour') {
    return activity.status === 1 && activity.publishStatus !== 'deactivated'
  }
  return true
}

function isBatchDeactivatableActivity(activity: ActivityEntity) {
  return canToggleSaleStatus(activity) && (activity.itemType === 'tour' || nextActivitySaleStatus(activity.status) === 0)
}

function getBatchDeactivatableActivities(activities: ActivityEntity[], selectedActivityKeys: Set<string>) {
  return activities.filter(activity => selectedActivityKeys.has(activityRowKey(activity)) && isBatchDeactivatableActivity(activity))
}

function isBatchNotifiableActivity(activity: ActivityEntity) {
  if (activity.itemType === 'tour') return false
  if (activity.publishStatus === 'draft' || activity.publishStatus === 'risk_suspended') return false
  return isKnownActivitySaleStatus(activity.status)
}

function getBatchNotifiableActivities(activities: ActivityEntity[], selectedActivityKeys: Set<string>) {
  return activities.filter(activity => selectedActivityKeys.has(activityRowKey(activity)) && isBatchNotifiableActivity(activity))
}

function isBatchSelectableActivity(activity: ActivityEntity) {
  return isBatchDeactivatableActivity(activity) || isBatchNotifiableActivity(activity)
}

function buildRefundImpactSummary(result: Pick<RefundImpactResponse,
  'deactivatedActivityCount' |
  'paidOrderCount' |
  'refundSuccessCount' |
  'refundFailedCount' |
  'refundUnknownCount' |
  'refundCompensationRequiredCount'
>) {
  return `已下架活动 ${result.deactivatedActivityCount} 个，已支付订单 ${result.paidOrderCount} 笔，退款成功 ${result.refundSuccessCount} 笔，退款失败 ${result.refundFailedCount} 笔，结果未知 ${result.refundUnknownCount} 笔，需人工处理 ${result.refundCompensationRequiredCount} 笔。`
}

function buildBuyerNotificationSummary(result: Pick<ActivityBuyerNotificationResponse,
  'paidOrderCount' |
  'notifiedUserCount' |
  'notificationCount' |
  'skippedOrderCount'
>) {
  return `已支付订单 ${result.paidOrderCount} 笔，通知用户 ${result.notifiedUserCount} 人，站内通知 ${result.notificationCount} 条，跳过订单 ${result.skippedOrderCount} 笔。`
}

function emptyPage<T>(): PageResult<T> {
  return {
    records: [],
    total: 0,
    size: ADMIN_FETCH_SIZE,
    current: 1,
    pages: 1,
  }
}

function getActivityCategoryLabel(activity: ActivityEntity, categoryNameById: Map<number, string>) {
  return categoryNameById.get(activity.categoryId) || '暂未分类'
}

type ActivityListFilters = {
  keyword?: string
  status?: string
  categoryId?: string
}

type ActivityActionDialog =
  | { action: 'notify_buyers'; title: string; activities: ActivityEntity[]; content: string; error: string }
  | { action: 'delete'; title: string; activity: ActivityEntity; reason: string; error: string }
  | { action: 'risk_suspend'; title: string; activity: ActivityEntity; reason: string; error: string }

export default function ActivitiesPage() {
  const searchParams = useSearchParams()
  const pathname = usePathname()
  const historicalView = pathname.endsWith('/history') || searchParams.get('view') === 'history'
  const [activities, setActivities] = useState<ActivityEntity[]>([])
  const [categories, setCategories] = useState<CategoryVO[]>([])
  const [userId, setUserId] = useState(0)
  const [role, setRole] = useState<UserRole | ''>('')
  const [permissionCodes, setPermissionCodes] = useState<string[]>([])
  const [checkingRole, setCheckingRole] = useState(true)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [keyword, setKeyword] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [publishingKey, setPublishingKey] = useState<string | null>(null)
  const [selectedActivityKeys, setSelectedActivityKeys] = useState<Set<string>>(() => new Set())
  const [openActionMenuKey, setOpenActionMenuKey] = useState<string | null>(null)
  const [activityActionDialog, setActivityActionDialog] = useState<ActivityActionDialog | null>(null)
  const [activityActionSubmitting, setActivityActionSubmitting] = useState(false)
  const loadDataRef = useRef(() => {})
  const lastRefreshRef = useRef(0)
  const isAdmin = isPlatformAdminRole(role)
  const canManageActivities = hasConsolePermission(role, permissionCodes, 'activity.manage')
  const canManageTours = hasConsolePermission(role, permissionCodes, 'tour.manage')
  const canReviewRisk = canUseConsoleAction('risk.review', permissionCodes)
  const categoryNameById = useMemo(() => new Map(categories.map(category => [category.id, category.name])), [categories])
  const batchSelectableActivities = activities.filter(isBatchSelectableActivity)
  const batchDeactivatableActivities = getBatchDeactivatableActivities(activities, selectedActivityKeys)
  const batchNotifiableActivities = getBatchNotifiableActivities(activities, selectedActivityKeys)
  const selectedCount = selectedActivityKeys.size
  const isMultiple = selectedCount > 1
  const alertText = `已勾选 ${selectedCount} 个活动`
  const offlineBtnText = isMultiple ? '批量下架' : '下架活动'
  const notifyBtnText = isMultiple ? '批量通知购票用户' : '通知购票用户'
  const refundBtnText = isMultiple ? '批量下架并退款' : '下架并退款'
  const allBatchSelectableSelected = batchSelectableActivities.length > 0 &&
    batchSelectableActivities.every(activity => selectedActivityKeys.has(activityRowKey(activity)))
  const canPublishDraft = (activity: ActivityEntity) => {
    const canManageRowType = activity.itemType === 'tour' ? canManageTours : canManageActivities
    return activity.publishStatus === 'draft' && (canManageRowType || activity.organizerId === userId)
  }

  const loadData = (nextPage = page, filters: ActivityListFilters = {}) => {
    const activeKeyword = filters.keyword ?? keyword
    const activeStatus = filters.status ?? status
    const activeCategoryId = filters.categoryId ?? categoryId
    const selectedCategoryId = activeCategoryId ? Number(activeCategoryId) : undefined
    const u = getUser()
    if (!u) {
      setCheckingRole(false)
      setLoading(false)
      return
    }
    setUserId(u.userId)
    setRole(u.role || 'user')
    const permissions = u.permissionCodes || []
    setPermissionCodes(permissions)
    setCheckingRole(false)
    const canLoadActivities = hasConsolePermission(u.role, permissions, 'activity.manage')
    const canLoadTours = hasConsolePermission(u.role, permissions, 'tour.manage')
    if (!canLoadActivities && !canLoadTours) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError('')
    Promise.all([
      canLoadActivities ? listAdminActivities({
        page: 1,
        size: ADMIN_FETCH_SIZE,
        keyword: activeKeyword,
        status: activeStatus === '' ? undefined : Number(activeStatus),
        categoryId: selectedCategoryId,
        historical: historicalView,
      }) : Promise.resolve(emptyPage<ActivityEntity>()),
      canLoadTours ? listAdminTours(u.userId, { page: 1, size: ADMIN_FETCH_SIZE, categoryId: selectedCategoryId, historical: historicalView }) : Promise.resolve(emptyPage<TourEntity>()),
    ]).then(([activityRes, tourRes]) => {
      const tourActivities: ActivityEntity[] = tourRes.records
        .filter(tour => !activeKeyword.trim() || tour.title.includes(activeKeyword.trim()))
        .filter(tour => activeStatus === '' || getTourRowStatus(tour) === Number(activeStatus))
        .map(tour => ({
          id: tour.id,
          itemType: 'tour' as const,
          categoryId: tour.categoryId ?? 0,
          artistId: tour.artistId ?? 0,
          organizerId: tour.organizerId,
          name: tour.title,
          description: tour.description ?? null,
          poster: tour.poster ?? null,
          publishStatus: tour.reviewStatus,
          status: getTourRowStatus(tour),
          createTime: tour.createTime || '',
        }))
      const records = [...activityRes.records.map(activity => ({ ...activity, itemType: 'activity' as const })), ...tourActivities]
        .filter(activity => historicalView
          ? activity.status === 0 || activity.publishStatus === 'deactivated'
          : activity.status !== 0 && activity.publishStatus !== 'deactivated')
        .sort(compareActivityRows)
      const nextTotal = records.length
      const nextPages = Math.ceil(nextTotal / PAGE_SIZE) || 1
      const safePage = Math.min(Math.max(1, nextPage), nextPages)
      const pageRecords = records.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE)
      const pageKeys = new Set(pageRecords.filter(isBatchSelectableActivity).map(activityRowKey))
      setActivities(pageRecords)
      setSelectedActivityKeys(previous => new Set([...previous].filter(key => pageKeys.has(key))))
      setOpenActionMenuKey(null)
      setTotal(nextTotal)
      setPage(safePage)
      setLoading(false)
    }).catch(err => {
      setError(err instanceof Error ? err.message : '加载活动失败')
      setLoading(false)
    })
  }

  useEffect(() => {
    loadDataRef.current = loadData
  })

  useEffect(() => {
    listCategories().then(setCategories).catch(() => setCategories([]))
  }, [historicalView])

  const refreshWhenVisible = () => {
    const now = Date.now()
    if (now - lastRefreshRef.current < 200) return
    lastRefreshRef.current = now
    loadDataRef.current()
  }

  useEffect(() => {
    const timer = window.setTimeout(() => loadDataRef.current(), 0)
    return () => window.clearTimeout(timer)
  }, [historicalView])

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

  const alertRefundImpact = async (targetLabel: string, result: RefundImpactResponse) => {
    const abnormalCount = result.refundFailedCount + result.refundUnknownCount + result.refundCompensationRequiredCount
    const summary = buildRefundImpactSummary(result)
    if (abnormalCount > 0) {
      await globalAlert(`${targetLabel}已下架并发起退款，但部分退款失败/结果未知/需人工处理。${summary}`)
    } else {
      await globalAlert(`${targetLabel}已下架并发起退款。${summary}`)
    }
  }

  const toggleActivitySelection = (activity: ActivityEntity, checked: boolean) => {
    if (!isBatchSelectableActivity(activity)) return
    const key = activityRowKey(activity)
    setSelectedActivityKeys(previous => {
      const next = new Set(previous)
      if (checked) {
        next.add(key)
      } else {
        next.delete(key)
      }
      return next
    })
  }

  const toggleAllBatchSelectable = (checked: boolean) => {
    setSelectedActivityKeys(previous => {
      const next = new Set(previous)
      for (const activity of batchSelectableActivities) {
        const key = activityRowKey(activity)
        if (checked) {
          next.add(key)
        } else {
          next.delete(key)
        }
      }
      return next
    })
  }

  const handleBatchDeactivate = async () => {
    const selected = getBatchDeactivatableActivities(activities, selectedActivityKeys)
    if (selected.length === 0) {
      await globalAlert('请先选择可下架的上架活动或巡演。')
      return
    }
    const multiple = selected.length > 1
    const actionText = multiple ? '批量下架并退款' : '下架并退款'
    const confirmed = await globalConfirm(`已选择 ${selected.length} 个活动/巡演。${actionText}后，所选活动、场次、票档将全部下架，并直接为所有已支付订单发起真实支付宝退款。“同意退款”表示你确认平台将对这些已支付订单执行退款，可能产生退款失败、结果未知或需人工处理的记录。请确认：同意${actionText}。`)
    if (!confirmed) return

    const impact = {
      deactivatedActivityCount: 0,
      paidOrderCount: 0,
      refundSuccessCount: 0,
      refundFailedCount: 0,
      refundUnknownCount: 0,
      refundCompensationRequiredCount: 0,
    }
    const failedMessages: string[] = []
    const completedKeys = new Set<string>()

    for (const activity of selected) {
      try {
        const result = activity.itemType === 'tour'
          ? await deactivateTour(activity.id, {
            userId,
            confirmRefund: true,
            reason: isAdmin ? `平台${actionText}巡演/多站点活动自动退款` : `主办方${actionText}巡演/多站点活动自动退款`,
          })
          : await deactivateActivity(activity.id, {
            userId,
            confirmRefund: true,
            reason: isAdmin ? `平台${actionText}活动自动退款` : `主办方${actionText}活动自动退款`,
          })
        impact.deactivatedActivityCount += result.deactivatedActivityCount
        impact.paidOrderCount += result.paidOrderCount
        impact.refundSuccessCount += result.refundSuccessCount
        impact.refundFailedCount += result.refundFailedCount
        impact.refundUnknownCount += result.refundUnknownCount
        impact.refundCompensationRequiredCount += result.refundCompensationRequiredCount
        completedKeys.add(activityRowKey(activity))
      } catch (err) {
        const message = err instanceof Error ? err.message : '处理失败'
        failedMessages.push(`${activity.name || '活动信息待同步'}：${message}`)
      }
    }

    if (completedKeys.size > 0) {
      setSelectedActivityKeys(previous => new Set([...previous].filter(key => !completedKeys.has(key))))
      loadData(page)
    }

    const abnormalCount = impact.refundFailedCount + impact.refundUnknownCount + impact.refundCompensationRequiredCount
    const outcome = `${actionText}处理完成：成功 ${completedKeys.size} 个，失败 ${failedMessages.length} 个。${buildRefundImpactSummary(impact)}`
    if (failedMessages.length > 0) {
      await globalAlert(`${outcome}失败明细：${failedMessages.slice(0, 3).join('；')}${failedMessages.length > 3 ? '；其余失败项请刷新后重试。' : ''}`)
    } else if (abnormalCount > 0) {
      await globalAlert(`${outcome}部分退款失败/结果未知/需人工处理，请进入退款审核页继续跟进。`)
    } else {
      await globalAlert(outcome)
    }
  }

  const handleBatchNotifyBuyers = async () => {
    const selected = getBatchNotifiableActivities(activities, selectedActivityKeys)
    if (selected.length === 0) {
      await globalAlert('请先选择可通知的普通活动。')
      return
    }
    const multiple = selected.length > 1
    const actionText = multiple ? '批量通知购票用户' : '通知购票用户'
    setActivityActionDialog({ action: 'notify_buyers', title: actionText, activities: selected, content: '', error: '' })
  }

  const submitBuyerNotification = async (dialog: Extract<ActivityActionDialog, { action: 'notify_buyers' }>) => {
    const trimmedContent = dialog.content.trim()
    if (!trimmedContent) {
      setActivityActionDialog({ ...dialog, error: '通知内容不能为空' })
      return
    }

    const impact = {
      paidOrderCount: 0,
      notifiedUserCount: 0,
      notificationCount: 0,
      skippedOrderCount: 0,
    }
    const failedMessages: string[] = []
    const completedKeys = new Set<string>()

    setActivityActionSubmitting(true)
    for (const activity of dialog.activities) {
      try {
        const result = await notifyActivityBuyers(activity.id, {
          userId,
          confirmNotify: true,
          content: trimmedContent,
        })
        impact.paidOrderCount += result.paidOrderCount
        impact.notifiedUserCount += result.notifiedUserCount
        impact.notificationCount += result.notificationCount
        impact.skippedOrderCount += result.skippedOrderCount
        completedKeys.add(activityRowKey(activity))
      } catch (err) {
        const message = err instanceof Error ? err.message : '通知失败'
        failedMessages.push(`${activity.name || '活动信息待同步'}：${message}`)
      }
    }
    setActivityActionSubmitting(false)
    setActivityActionDialog(null)

    if (completedKeys.size > 0) {
      setSelectedActivityKeys(previous => new Set([...previous].filter(key => !completedKeys.has(key))))
    }

    const outcome = `${dialog.title}处理完成：成功 ${completedKeys.size} 个，失败 ${failedMessages.length} 个。${buildBuyerNotificationSummary(impact)}`
    if (failedMessages.length > 0) {
      await globalAlert(`${outcome}失败明细：${failedMessages.slice(0, 3).join('；')}${failedMessages.length > 3 ? '；其余失败项请刷新后重试。' : ''}`)
    } else {
      await globalAlert(outcome)
    }
  }

  const handleToggleStatus = async (activity: ActivityEntity) => {
    if (!isKnownActivitySaleStatus(activity.status)) {
      await globalAlert('活动状态未知，请先核对后再操作。')
      return
    }
    if (activity.itemType === 'tour') {
      if (activity.status !== 1 || activity.publishStatus === 'deactivated') {
        await globalAlert('该巡演/多站点活动已下架，请进入巡演详情查看城市站点状态并按流程重新发布。')
        return
      }
      const confirmed = await globalConfirm('下架并退款后，巡演/多站点活动下已发布的城市站点、场次、票档将全部下架，并直接为所有已支付订单发起真实支付宝退款。“同意退款”表示你确认平台将对这些已支付订单执行退款，可能产生退款失败、结果未知或需人工处理的记录。请确认：同意下架并同意退款。')
      if (!confirmed) return
      const result = await deactivateTour(activity.id, {
        userId,
        confirmRefund: true,
        reason: isAdmin ? '平台下架巡演/多站点活动自动退款' : '主办方下架巡演/多站点活动自动退款',
      })
      await alertRefundImpact('巡演/多站点活动', result)
      loadData(page)
      return
    }
    const newStatus = nextActivitySaleStatus(activity.status)
    if (newStatus === 0) {
      const confirmed = await globalConfirm('下架并退款后，活动、场次、票档将全部下架，并直接为所有已支付订单发起真实支付宝退款。“同意退款”表示你确认平台将对这些已支付订单执行退款，可能产生退款失败、结果未知或需人工处理的记录。请确认：同意下架并同意退款。')
      if (!confirmed) return
      const result = await deactivateActivity(activity.id, {
        userId,
        confirmRefund: true,
        reason: isAdmin ? '平台下架活动自动退款' : '主办方下架活动自动退款',
      })
      await alertRefundImpact('活动', result)
    } else {
      await updateActivityStatus(activity.id, { userId, status: newStatus })
    }
    loadData(page)
  }

  const handleDelete = async (activity: ActivityEntity) => {
    if (activity.itemType === 'tour') {
      if (activity.publishStatus !== 'draft') {
        await globalAlert('已发布的巡演活动请先进入巡演详情，按城市站点完成下架/退款处理后再删除。')
        return
      }
      if (!(await globalConfirm('确认删除该巡演活动草稿？'))) return
      await deleteTourDraft(userId, activity.id)
      await globalAlert('巡演活动草稿已删除')
      loadData(page)
      return
    }
    if (activity.publishStatus === 'draft') {
      if (!(await globalConfirm('确认删除该活动草稿？'))) return
      const result = await deleteAdminActivity(activity.id, { userId, reason: '删除活动草稿' })
      await globalAlert(result.message || '活动草稿已删除')
      loadData(page)
      return
    }
    setActivityActionDialog({ action: 'delete', title: '删除活动', activity, reason: '', error: '' })
  }

  const submitDeleteActivity = async (dialog: Extract<ActivityActionDialog, { action: 'delete' }>) => {
    const reason = dialog.reason.trim()
    if (!reason) {
      setActivityActionDialog({ ...dialog, error: '删除原因不能为空' })
      return
    }
    setActivityActionSubmitting(true)
    try {
      const result = await deleteAdminActivity(dialog.activity.id, { userId, reason })
      setActivityActionDialog(null)
      await globalAlert(result.message || '活动已删除')
      loadData(page)
    } catch (err) {
      setActivityActionDialog({ ...dialog, error: err instanceof Error ? err.message : '删除活动失败' })
    } finally {
      setActivityActionSubmitting(false)
    }
  }

  const handlePublishDraft = async (activity: ActivityEntity) => {
    const key = activityRowKey(activity)
    if (activity.itemType === 'tour') {
      const confirmed = await globalConfirm('确认发布该巡演活动草稿并公开城市站点？场馆、时间、座位票档可继续按城市站点补齐。')
      if (!confirmed) return
      setPublishingKey(key)
      try {
        await announceTourCities(userId, activity.id)
        await globalAlert('巡演活动和城市站点已发布，后续请在巡演详情补齐各城市站点的场馆、时间和座位票档。')
        loadData(page)
      } catch (err) {
        await globalAlert(err instanceof Error ? err.message : '发布巡演活动失败')
      } finally {
        setPublishingKey(null)
      }
      return
    }
    const confirmed = await globalConfirm('确认发布该活动草稿？发布前请确认站点场馆审批已通过，座位票档已配置。')
    if (!confirmed) return
    setPublishingKey(key)
    try {
      const detail = await getActivityStation(activity.id)
      await publishStation(detail.station.id, {
        userId,
        scheduleTba: true,
      })
      await globalAlert('活动草稿已发布。若需要具体开演时间，请在活动配置中补齐站点排期并检查 SeatCraft 座位票档。')
      loadData(page)
    } catch (err) {
      await globalAlert(err instanceof Error ? err.message : '发布活动失败')
    } finally {
      setPublishingKey(null)
    }
  }

  const [riskResolutionDialog, setRiskResolutionDialog] = useState<ActivityEntity | null>(null)
  const [riskNote, setRiskNote] = useState('')
  const [riskType, setRiskType] = useState<'remove_artist' | 'reschedule' | 'refund' | 'explain'>('explain')
  const [riskSubmitting, setRiskSubmitting] = useState(false)

  const handleRiskResolution = (activity: ActivityEntity) => {
    setRiskResolutionDialog(activity)
    setRiskNote('')
    setRiskType('explain')
  }

  const closeRiskDialog = () => {
    if (riskSubmitting) return
    setRiskResolutionDialog(null)
    setRiskNote('')
    setRiskType('explain')
  }

  const submitRiskResolution = async () => {
    if (!riskResolutionDialog) return
    if (!riskNote.trim()) {
      await globalAlert('处理说明不能为空')
      return
    }
    const TYPE_PREFIX: Record<typeof riskType, string> = {
      remove_artist: '[移除阵容]',
      reschedule: '[改期]',
      refund: '[全额退款]',
      explain: '[补充说明]',
    }
    setRiskSubmitting(true)
    try {
      await submitActivityRiskResolution(riskResolutionDialog.id, {
        userId,
        resolutionNote: `${TYPE_PREFIX[riskType]} ${riskNote.trim()}`,
      })
      await globalAlert('已提交恢复售票申请，等待平台审核。')
      setRiskResolutionDialog(null)
      setRiskNote('')
      setRiskType('explain')
      loadData(page)
    } catch (err: unknown) {
      await globalAlert(err instanceof Error ? err.message : '提交失败')
    } finally {
      setRiskSubmitting(false)
    }
  }

  const handleAdminSuspend = async (activity: ActivityEntity) => {
    setActivityActionDialog({ action: 'risk_suspend', title: '风险停售', activity, reason: '', error: '' })
  }

  const submitRiskSuspend = async (dialog: Extract<ActivityActionDialog, { action: 'risk_suspend' }>) => {
    const reason = dialog.reason.trim()
    if (!reason) {
      setActivityActionDialog({ ...dialog, error: '停售原因不能为空' })
      return
    }
    setActivityActionSubmitting(true)
    try {
      await suspendActivityForRisk(dialog.activity.id, { userId, reason })
      setActivityActionDialog(null)
      await globalAlert('活动已被主动停售，已通知主办方处理。')
      loadData(page)
    } catch (err: unknown) {
      setActivityActionDialog({ ...dialog, error: err instanceof Error ? err.message : '停售失败' })
    } finally {
      setActivityActionSubmitting(false)
    }
  }

  const closeActivityActionDialog = () => {
    if (activityActionSubmitting) return
    setActivityActionDialog(null)
  }

  const updateActivityActionText = (value: string) => {
    setActivityActionDialog(current => {
      if (!current) return current
      if (current.action === 'notify_buyers') return { ...current, content: value, error: '' }
      return { ...current, reason: value, error: '' }
    })
  }

  const submitActivityAction = async () => {
    if (!activityActionDialog) return
    if (activityActionDialog.action === 'notify_buyers') {
      await submitBuyerNotification(activityActionDialog)
      return
    }
    if (activityActionDialog.action === 'delete') {
      await submitDeleteActivity(activityActionDialog)
      return
    }
    await submitRiskSuspend(activityActionDialog)
  }

  const handleSearch = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setPage(1)
    loadData(1)
  }

  const handleResetFilters = () => {
    setKeyword('')
    setCategoryId('')
    setStatus('')
    setPage(1)
    loadData(1, { keyword: '', categoryId: '', status: '' })
  }

  const downloadVenueApprovalAsset = async (activity: ActivityEntity) => {
    const assetId = parsePrivateAssetRef(activity.venueApprovalFileUrl)
    if (!assetId) return
    const token = getToken()
    if (!token) {
      await globalAlert('登录已失效，请重新登录后下载')
      return
    }

    let objectUrl: string | null = null
    try {
      const response = await fetch(privateAssetDownloadUrl(assetId), {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!response.ok) throw new Error('download failed')
      const blob = await response.blob()
      objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      const disposition = response.headers.get('Content-Disposition') || response.headers.get('content-disposition')
      const filename = disposition?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i)?.[1]
      link.href = objectUrl
      link.download = filename ? decodeURIComponent(filename) : `activity-${activity.id}-venue-proof`
      document.body.appendChild(link)
      link.click()
      link.remove()
    } catch {
      await globalAlert('场馆审核文件下载失败，请稍后重试')
    } finally {
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }

  if (checkingRole) {
    return <div className="py-20 text-center text-[14px] text-[#999]">加载中...</div>
  }

  if (!role) {
    return (
      <div className="max-w-[720px] rounded-xl border border-[#e5e5e5] bg-white p-6">
        <h1 className="mb-2 text-[22px] font-bold text-[#1a1a2e]">请先登录</h1>
        <p className="mb-5 text-[14px] text-[#666]">登录后可查看和管理活动。</p>
        <Link href="/login" className="inline-flex rounded-lg bg-[#ff1268] px-4 py-2 text-[14px] font-medium text-white">去登录</Link>
      </div>
    )
  }

  if (!canManageActivities && !canManageTours) {
    return <div className="rounded-xl border border-[#e5e5e5] bg-white py-16 text-center text-[14px] text-[#999]">无权限访问</div>
  }

  return (
    <div>
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-[22px] font-bold text-[#1a1a2e]">{historicalView ? '以往活动' : (isAdmin ? '活动发布管理' : '我的活动管理')}</h1>
          <p className="mt-1 text-[13px] text-[#999]">{historicalView ? '查看已下架活动的历史信息。' : (isAdmin ? '管理平台活动草稿、补齐配置并处理发布状态。' : '维护自己主办的活动草稿、发布申请与后续上下架。')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canManageTours && (
            <Link href="/console/tours" className="inline-flex h-10 items-center justify-center rounded-lg border border-[#e5e5e5] bg-white px-4 text-[14px] font-medium text-[#333] transition-colors hover:border-[#ff1268]/30 hover:bg-[#fff7fb] hover:text-[#ff1268]">
              巡演草稿箱
            </Link>
          )}
          {!historicalView && <Link href="/console/activities/new" className="inline-flex h-10 items-center justify-center rounded-lg bg-[#ff1268] px-4 text-[14px] font-medium text-white transition-colors hover:bg-[#e6005c]">+ 新建演出活动</Link>}
        </div>
      </div>

      <form onSubmit={handleSearch} className="mb-5 grid gap-3 rounded-xl border border-[#e5e5e5] bg-white p-4 lg:grid-cols-[minmax(220px,1fr)_180px_160px_auto_auto]">
        <label className="relative block">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#999]" />
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索活动名称"
            className="h-10 w-full rounded-lg border border-[#e5e5e5] pl-9 pr-3 text-[14px] outline-none focus:border-[#ff1268]"
          />
        </label>
        <select
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
          className="h-10 rounded-lg border border-[#e5e5e5] px-3 text-[14px] outline-none focus:border-[#ff1268]"
        >
          <option value="">全部类目</option>
          {categories.map(category => (
            <option key={category.id} value={category.id}>{category.name}</option>
          ))}
        </select>
        <select
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          className="h-10 rounded-lg border border-[#e5e5e5] px-3 text-[14px] outline-none focus:border-[#ff1268]"
        >
          <option value="">全部状态</option>
          {!historicalView && <option value="1">上架</option>}
          {historicalView && <option value="0">下架</option>}
        </select>
        <button
          type="submit"
          className="h-10 rounded-lg bg-[#1a1a2e] px-5 text-[14px] font-medium text-white transition-colors hover:bg-[#2a2a42]"
        >
          查询
        </button>
        <button
          type="button"
          onClick={handleResetFilters}
          className="h-10 rounded-lg border border-[#e5e5e5] bg-white px-5 text-[14px] font-medium text-[#666] transition-colors hover:border-[#ff1268]/30 hover:bg-[#fff7fb] hover:text-[#ff1268]"
        >
          重置
        </button>
      </form>

      {loading ? (
        <div className="text-center text-[#999] py-20 text-[14px]">加载中...</div>
      ) : error ? (
        <div className="rounded-xl border border-[#ffd9e6] bg-white py-16 text-center text-[14px] text-[#ff4d4f]">
          <div>{error}</div>
          <button
            onClick={() => loadData(page)}
            className="mt-4 inline-flex items-center gap-1.5 rounded-lg bg-[#ff1268] px-4 py-2 text-white"
          >
            <RefreshCw className="h-4 w-4" /> 重试
          </button>
        </div>
      ) : activities.length === 0 ? (
        <div className="text-center text-[#999] py-20 bg-white rounded-xl border border-[#e5e5e5] text-[14px]">
          暂无匹配活动，可调整筛选条件或点击右上角新建演出活动。
        </div>
      ) : (
        <>
        <div className="rounded-xl border border-[#e5e5e5] bg-white">
          {selectedCount > 0 && (
            <div className="border-b border-[#cfe5ff] bg-[#e8f3ff] px-4 py-3 text-[13px] text-[#1f3b57] transition-all duration-200">
              <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="font-semibold">{alertText}</span>
                  <span className="text-[#5f7892]">可{offlineBtnText} {batchDeactivatableActivities.length} 个，可{notifyBtnText} {batchNotifiableActivities.length} 个</span>
                </div>
                <div className="flex flex-wrap gap-2 whitespace-nowrap">
                  <button
                    type="button"
                    onClick={handleBatchNotifyBuyers}
                    disabled={batchNotifiableActivities.length === 0}
                    className="inline-flex h-8 items-center justify-center gap-1.5 rounded-lg border border-[#8bbdf4] bg-white px-3 text-[13px] font-medium text-[#2563eb] disabled:cursor-not-allowed disabled:border-[#c8dcf4] disabled:text-[#9bb4d0]"
                    title={notifyBtnText}
                  >
                    <Bell className="h-4 w-4" /> {notifyBtnText}
                  </button>
                  <button
                    type="button"
                    onClick={handleBatchDeactivate}
                    disabled={batchDeactivatableActivities.length === 0}
                    className="inline-flex h-8 items-center justify-center gap-1.5 rounded-lg bg-[#ff1268] px-3 text-[13px] font-medium text-white disabled:cursor-not-allowed disabled:bg-[#f3a1bf]"
                    title={refundBtnText}
                  >
                    <EyeOff className="h-4 w-4" /> {refundBtnText}
                  </button>
                  <button
                    type="button"
                    onClick={() => setSelectedActivityKeys(new Set())}
                    className="inline-flex h-8 items-center justify-center rounded-lg px-2 text-[13px] font-medium text-[#31506f] hover:bg-white/70"
                  >
                    取消选择
                  </button>
                </div>
              </div>
            </div>
          )}
          <table className="min-w-[960px] w-full text-[14px]">
            <thead>
              <tr className="border-b border-[#e5e5e5] bg-[#fafafa]">
                <th className="w-[52px] whitespace-nowrap p-3 text-left font-medium text-[#666]">
                  <input
                    type="checkbox"
                    checked={allBatchSelectableSelected}
                    disabled={historicalView || batchSelectableActivities.length === 0}
                    onChange={(event) => toggleAllBatchSelectable(event.target.checked)}
                    aria-label="选择当前页可批量操作活动"
                    className="h-4 w-4 accent-[#ff1268]"
                  />
                </th>
                <th className="whitespace-nowrap text-left p-3 font-medium text-[#666]">演出活动</th>
                <th className="whitespace-nowrap text-left p-3 font-medium text-[#666]">状态</th>
                <th className="whitespace-nowrap text-left p-3 font-medium text-[#666]">创建时间</th>
                <th className="whitespace-nowrap text-center p-3 font-medium text-[#666]">操作</th>
              </tr>
            </thead>
            <tbody>
              {activities.map(a => {
                const rowKey = activityRowKey(a)
                const isTour = a.itemType === 'tour'
                const canBatchSelect = isBatchSelectableActivity(a)
                const configHref = isTour ? `/console/tours/${a.id}` : `/console/activities/${a.id}/edit`
                const seatHref = isTour ? `/console/tours/${a.id}?mode=seatcraft` : `/console/sessions?activityId=${a.id}`
                const categoryLabel = getActivityCategoryLabel(a, categoryNameById)
                const menuOpen = openActionMenuKey === rowKey
                return (
                <tr key={rowKey} className="border-b border-[#f0f0f0] hover:bg-[#fafafa]">
                  <td className="p-3">
                    <input
                      type="checkbox"
                      checked={selectedActivityKeys.has(rowKey)}
                      disabled={historicalView || !canBatchSelect}
                      onChange={(event) => toggleActivitySelection(a, event.target.checked)}
                      aria-label={`选择活动 ${a.name || '活动信息待同步'}`}
                      className="h-4 w-4 accent-[#ff1268] disabled:cursor-not-allowed"
                    />
                  </td>
                  <td className="p-3">
                    <div className="flex min-w-[280px] items-center gap-3">
                      <SafeImage src={a.poster} alt={a.name || '活动海报'} className="h-16 w-12 shrink-0 rounded-lg bg-[#f5f5f5] object-cover" />
                      <div className="min-w-0">
                        <div className="truncate font-medium text-[#333]">{a.name || '活动信息待同步'}</div>
                        <div className="mt-1 flex flex-wrap gap-1">
                          <span className={`inline-flex rounded-full px-2 py-0.5 text-[12px] ${isTour ? 'bg-[#eff6ff] text-[#2563eb]' : 'bg-[#f5f5f5] text-[#666]'}`}>
                            {isTour ? '巡演 / 多站点活动' : '普通活动'}
                          </span>
                          <span className="inline-flex rounded-full bg-[#f7f7ff] px-2 py-0.5 text-[12px] text-[#6366f1]">类目 {categoryLabel}</span>
                          {!isTour && (
                            <>
                        <span className={`inline-flex rounded-full px-2 py-0.5 text-[12px] ${a.realNameRequired ? 'bg-[#fff7ed] text-[#f97316]' : 'bg-[#f5f5f5] text-[#999]'}`}>
                          {getRealNameRequirementLabel(a.realNameRequired)}
                        </span>
                        <span className={`inline-flex rounded-full px-2 py-0.5 text-[12px] ${a.ticketTransferAllowed === false ? 'bg-[#fef2f2] text-[#dc2626]' : 'bg-[#f0fff4] text-[#16a34a]'}`}>
                          {getTicketTransferAllowedLabel(a.ticketTransferAllowed)}
                        </span>
                            </>
                          )}
                        </div>
                        {a.artistName ? <div className="mt-1 truncate text-[12px] font-normal text-[#999]">阵容：{a.artistName}</div> : null}
                      </div>
                    </div>
                  </td>
                  <td className="whitespace-nowrap p-3">
                    <span className={`whitespace-nowrap text-[12px] px-2 py-0.5 rounded-full ${getActivityStatusClass(a)}`}>
                      {getActivityStatusText(a)}
                    </span>
                  </td>
                  <td className="whitespace-nowrap p-3 text-[#999]">{a.createTime?.substring(0, 10)}</td>
                  <td className="min-w-[180px] whitespace-nowrap p-3">
                    <div className="flex items-center justify-center gap-2 whitespace-nowrap">
                      {!historicalView && <Link href={configHref} className="rounded px-2 py-1 text-[12px] text-[#3b82f6] hover:bg-[#eff6ff]">继续配置</Link>}
                      {!historicalView && <Link href={seatHref} className="rounded px-2 py-1 text-[12px] text-[#ff1268] hover:bg-[#fff0f3]">座位票档</Link>}
                      {!historicalView && <div className="relative">
                        {!historicalView && <button
                          type="button"
                          onClick={() => setOpenActionMenuKey(menuOpen ? null : rowKey)}
                          className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-[#666] transition-colors hover:bg-[#f5f5f5] hover:text-[#333]"
                          aria-label={`更多操作 ${a.name || '活动信息待同步'}`}
                        >
                          <MoreHorizontal className="h-4 w-4" />
                        </button>}
                        {menuOpen && (
                          <div className="absolute right-0 z-20 mt-2 w-44 rounded-lg border border-[#e5e5e5] bg-white p-1 text-left shadow-lg">
                            {shouldShowSaleStatusControl(a) && (
                              <button
                                type="button"
                                onClick={() => {
                                  setOpenActionMenuKey(null)
                                  handleToggleStatus(a)
                                }}
                                disabled={!canToggleSaleStatus(a)}
                                className={`flex w-full items-center gap-2 rounded-md px-3 py-2 text-[12px] disabled:cursor-not-allowed disabled:text-[#aaa] ${a.itemType === 'tour' || a.status === 1 ? 'text-[#b91c1c] hover:bg-[#fef2f2]' : 'text-[#333] hover:bg-[#f5f5f5]'}`}
                                title={getActivitySaleActionTitle(a)}
                              >
                                {a.itemType === 'tour' || a.status === 1 ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                                {getActivitySaleActionTitle(a)}
                              </button>
                            )}
                            {!isTour && (
                              <Link
                                href={`/console/activities/${a.id}/marketing`}
                                onClick={() => setOpenActionMenuKey(null)}
                                className="flex items-center rounded-md px-3 py-2 text-[12px] text-[#333] hover:bg-[#f5f5f5]"
                              >
                                营销配置
                              </Link>
                            )}
                            {canPublishDraft(a) && (
                              <button
                                type="button"
                                onClick={() => {
                                  setOpenActionMenuKey(null)
                                  handlePublishDraft(a)
                                }}
                                disabled={publishingKey === rowKey}
                                className="flex w-full items-center rounded-md px-3 py-2 text-[12px] text-[#16a34a] hover:bg-[#f0fff4] disabled:cursor-not-allowed disabled:text-[#aaa]"
                                title="发布活动草稿"
                              >
                                {publishingKey === rowKey ? '发布中' : '发布活动'}
                              </button>
                            )}
                            {a.publishStatus === 'risk_suspended' && (
                              <button
                                type="button"
                                onClick={() => {
                                  setOpenActionMenuKey(null)
                                  handleRiskResolution(a)
                                }}
                                className="flex w-full items-center rounded-md px-3 py-2 text-[12px] text-[#ff1268] hover:bg-[#fff0f3]"
                                title="提交恢复售票申请"
                              >
                                申请恢复
                              </button>
                            )}
                            {parsePrivateAssetRef(a.venueApprovalFileUrl) && (
                              <button
                                type="button"
                                onClick={() => {
                                  setOpenActionMenuKey(null)
                                  downloadVenueApprovalAsset(a)
                                }}
                                className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-[12px] text-[#333] hover:bg-[#f5f5f5]"
                                title="下载场馆审核文件"
                              >
                                <FileDown className="h-4 w-4" /> 下载审核文件
                              </button>
                            )}
                            {canReviewRisk && isTour && a.publishStatus !== 'draft' && a.status === 1 && (
                              <Link
                                href={`/console/tours/${a.id}?mode=risk`}
                                onClick={() => setOpenActionMenuKey(null)}
                                className="flex items-center rounded-md px-3 py-2 text-[12px] text-[#b91c1c] hover:bg-[#fef2f2]"
                                title="风险停售"
                              >
                                风险停售
                              </Link>
                            )}
                            {canReviewRisk && !isTour && a.publishStatus === 'published' && a.status === 1 && (
                              <button
                                type="button"
                                onClick={() => {
                                  setOpenActionMenuKey(null)
                                  handleAdminSuspend(a)
                                }}
                                className="flex w-full items-center rounded-md px-3 py-2 text-[12px] text-[#b91c1c] hover:bg-[#fef2f2]"
                                title="风险停售"
                              >
                                风险停售
                              </button>
                            )}
                            <button
                              type="button"
                              onClick={() => {
                                setOpenActionMenuKey(null)
                                handleDelete(a)
                              }}
                              className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-[12px] text-[#b91c1c] hover:bg-[#fef2f2]"
                              title="删除"
                            >
                              <Trash2 className="h-4 w-4" /> 删除
                            </button>
                          </div>
                        )}
                      </div>}
                    </div>
                  </td>
                </tr>
              )})}
            </tbody>
          </table>
          <div className="border-t border-[#f0f0f0] px-4 pb-4">
            <GlobalPagination page={page} total={total} pageSize={PAGE_SIZE} loading={loading} onChange={loadData} />
          </div>
        </div>
        </>
      )}
      {activityActionDialog && (
        <Modal
          open={Boolean(activityActionDialog)}
          onClose={closeActivityActionDialog}
          title={activityActionDialog.title}
          size="md"
          danger={activityActionDialog.action !== 'notify_buyers'}
          loading={activityActionSubmitting}
          footer={(
            <>
              <button type="button" onClick={closeActivityActionDialog} disabled={activityActionSubmitting} className="rounded-lg border border-[#e5e5e5] bg-white px-4 py-2 text-[13px] text-[#666] hover:bg-[#fafafa] disabled:opacity-60">
                取消
              </button>
              <button type="button" onClick={() => void submitActivityAction()} disabled={activityActionSubmitting} className={`rounded-lg px-4 py-2 text-[13px] font-medium text-white disabled:opacity-60 ${activityActionDialog.action === 'notify_buyers' ? 'bg-[#ff1268]' : 'bg-[#f53f3f]'}`}>
                {activityActionSubmitting ? '提交中...' : activityActionDialog.action === 'notify_buyers' ? '发送通知' : '确认提交'}
              </button>
            </>
          )}
        >
          <div className="space-y-3">
            {activityActionDialog.action === 'notify_buyers' ? (
              <p className="text-[13px] leading-6 text-[#666]">通知内容将发送给 {activityActionDialog.activities.length} 个普通活动的已支付订单用户；该操作仅发送站内通知，不发送短信或邮件。</p>
            ) : (
              <div className="rounded-lg bg-[#fff1f2] px-3 py-2 text-[13px] leading-6 text-[#b91c1c]">
                {activityActionDialog.action === 'delete' ? '删除活动前必须填写原因。已发布且有订单的活动需先完成下架退款。' : '风险停售将记录到风险案例并通知主办方，请填写明确停售原因。'}
              </div>
            )}
            <textarea
              value={activityActionDialog.action === 'notify_buyers' ? activityActionDialog.content : activityActionDialog.reason}
              onChange={event => updateActivityActionText(event.target.value)}
              rows={activityActionDialog.action === 'notify_buyers' ? 6 : 4}
              placeholder={activityActionDialog.action === 'notify_buyers' ? '请输入通知内容（必填）' : activityActionDialog.action === 'delete' ? '请输入删除原因（必填）' : '请输入停售原因（必填）'}
              className="w-full resize-none rounded-lg border border-[#ddd] px-3 py-2 text-[14px] text-[#333] outline-none focus:border-[#ff1268]"
              maxLength={1000}
            />
            {activityActionDialog.error && <div className="rounded-lg bg-[#fff0f3] px-3 py-2 text-[13px] text-[#ff4d4f]">{activityActionDialog.error}</div>}
          </div>
        </Modal>
      )}
      <Modal
        open={Boolean(riskResolutionDialog)}
        onClose={closeRiskDialog}
        title="提交恢复售票申请"
        size="md"
        loading={riskSubmitting}
        footer={(
          <>
            <button type="button" onClick={closeRiskDialog} disabled={riskSubmitting} className="rounded-lg border border-[#e5e5e5] bg-white px-4 py-2 text-[13px] text-[#666] hover:bg-[#fafafa] disabled:opacity-60">
              取消
            </button>
            <button type="button" onClick={() => void submitRiskResolution()} disabled={riskSubmitting} className="rounded-lg bg-[#ff1268] px-4 py-2 text-[13px] font-medium text-white disabled:opacity-60">
              {riskSubmitting ? '提交中...' : '提交申请'}
            </button>
          </>
        )}
      >
        {riskResolutionDialog ? (
          <div className="space-y-4">
            <p className="text-[13px] leading-6 text-[#666]">
              活动：{riskResolutionDialog.name}<br />
              请选择处置方式并描述已完成的整改动作，平台审核通过后将恢复售票。
            </p>
            <div>
              <div className="mb-2 text-[13px] text-[#333]">处置方式</div>
              <div className="grid grid-cols-2 gap-2">
                {(
                  [
                    { value: 'remove_artist', label: '移除阵容' },
                    { value: 'reschedule', label: '改期' },
                    { value: 'refund', label: '全额退款' },
                    { value: 'explain', label: '补充说明' },
                  ] as const
                ).map(option => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => setRiskType(option.value)}
                    disabled={riskSubmitting}
                    className="cursor-pointer rounded border bg-white px-3 py-2 text-[13px] outline-none"
                    style={{
                      borderColor: riskType === option.value ? '#ff1268' : '#ddd',
                      color: riskType === option.value ? '#ff1268' : '#666',
                    }}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
            <textarea
              value={riskNote}
              onChange={(event) => setRiskNote(event.target.value)}
              placeholder="请描述具体处置动作，例如：已下线风险艺人 张三，并向 218 名购票用户发出阵容变更通知。"
              className="h-[120px] w-full resize-none rounded border border-[#ddd] px-3 py-2 text-[14px] text-[#333] outline-none focus:border-[#ff1268]"
              maxLength={500}
            />
          </div>
        ) : null}
      </Modal>
    </div>
  )
}
