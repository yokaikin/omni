'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { getUser } from '@/lib/auth'
import { createStationConfigVersion, getActivityStation, getAdminActivity, listAdminSessions, listAdminVenues, listCategories, submitStationConfigVersion, submitVenueApplication, updateAdminActivity, uploadPrivateAsset, uploadTicketAsset } from '@/lib/api'
import { isPlatformAdminRole } from '@/lib/console-auth'
import { ActivityArtistSelector } from '@/components/activity-artist/ActivityArtistSelector'
import { Drawer } from '@/components/ui/Drawer'
import { LocalFileUpload } from '@/components/LocalFileUpload'
import { StationVenueApprovalForm, createEmptyStationVenueApprovalValue, validateStationVenueApproval, type StationVenueApprovalValue } from '@/components/station-config/StationVenueApprovalForm'
import type { ActivityArtistVO, CategoryVO, PrivateAssetVO, SessionAdminVO, StationConfigVersionDetailVO, UserRole, VenueEntity } from '@/types/api'

type ActivityForm = {
  name: string
  categoryId: string
  artists: ActivityArtistVO[]
  poster: string
  description: string
  ticketTransferAllowed: boolean
  refundFullDaysBefore: string
  refundPartialDaysBefore: string
  refundPartialRate: string
}

const emptyForm: ActivityForm = {
  name: '',
  categoryId: '',
  artists: [],
  poster: '',
  description: '',
  ticketTransferAllowed: true,
  refundFullDaysBefore: '30',
  refundPartialDaysBefore: '7',
  refundPartialRate: '80',
}

export default function EditActivityPage() {
  const params = useParams<{ id: string }>()
  const activityId = Number(params.id)
  const [userId, setUserId] = useState(0)
  const [role, setRole] = useState<UserRole>('user')
  const [isAuthed, setIsAuthed] = useState(false)
  const [categories, setCategories] = useState<CategoryVO[]>([])
  const [form, setForm] = useState<ActivityForm>(emptyForm)
  const [loading, setLoading] = useState(true)
  const [sessions, setSessions] = useState<SessionAdminVO[]>([])
  const [stationDetail, setStationDetail] = useState<StationConfigVersionDetailVO | null>(null)
  const [venues, setVenues] = useState<VenueEntity[]>([])
  const [showVenueChangeForm, setShowVenueChangeForm] = useState(false)
  const [venueChangeValue, setVenueChangeValue] = useState<StationVenueApprovalValue>(createEmptyStationVenueApprovalValue())
  const [saving, setSaving] = useState(false)
  const [submittingVenueChange, setSubmittingVenueChange] = useState(false)
  const [uploadingProof, setUploadingProof] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    if (!Number.isInteger(activityId) || activityId <= 0) {
      setError('活动编号不正确')
      setLoading(false)
      return
    }

    const u = getUser()
    if (!u) {
      setIsAuthed(false)
      setError('请先登录')
      setLoading(false)
      return
    }
    setIsAuthed(true)
    setUserId(u.userId)
    setRole(u.role || 'user')
    setLoading(true)
    Promise.all([
      getAdminActivity(activityId),
      listCategories().catch(() => [] as CategoryVO[]),
      listAdminSessions(u.userId, { activityId, size: 50 }).catch(() => ({ records: [] as SessionAdminVO[] })),
      getActivityStation(activityId).catch(() => null),
      listAdminVenues(u.userId).catch(() => [] as VenueEntity[]),
    ]).then(([activity, categoryList, sessionPage, stationConfigDetail, venueList]) => {
      setForm({
        name: activity.name || '',
        categoryId: String(activity.categoryId || ''),
        artists: activity.artists || [],
        poster: activity.poster || '',
        description: activity.description || '',
        ticketTransferAllowed: activity.ticketTransferAllowed !== false,
        refundFullDaysBefore: String(activity.refundFullDaysBefore ?? 30),
        refundPartialDaysBefore: String(activity.refundPartialDaysBefore ?? 7),
        refundPartialRate: String(activity.refundPartialRate ?? 80),
      })
      setCategories(categoryList)
      setSessions(sessionPage.records || [])
      setStationDetail(stationConfigDetail)
      setVenues(venueList)
      if (stationConfigDetail?.station?.city) {
        setVenueChangeValue(createEmptyStationVenueApprovalValue(stationConfigDetail.station.city))
      }
      setLoading(false)
    }).catch(err => {
      setError(err instanceof Error ? err.message : '加载活动失败')
      setLoading(false)
    })
  }, [activityId])

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isAuthed || !userId) {
      setError('请先登录')
      return
    }
    if (!Number.isInteger(activityId) || activityId <= 0) {
      setError('活动编号不正确')
      return
    }
    if (!form.name.trim()) {
      setError('请填写活动名称')
      return
    }
    if (!isPositiveInteger(form.categoryId)) {
      setError('请选择分类')
      return
    }
    if (form.artists.length === 0) {
      setError('请至少选择一个活动艺人')
      return
    }
    const fullDays = Number(form.refundFullDaysBefore)
    const partialDays = Number(form.refundPartialDaysBefore)
    const partialRate = Number(form.refundPartialRate)
    if (![fullDays, partialDays, partialRate].every(Number.isInteger)
      || fullDays <= partialDays || partialDays <= 0 || partialRate <= 0 || partialRate > 100) {
      setError('请检查退票规则：天数必须为正整数，全额退款天数须大于折扣退款天数，折扣比例为1至100%')
      return
    }

    setSaving(true)
    setError('')
    setMessage('')
    try {
      await updateAdminActivity(activityId, {
        userId,
        name: form.name.trim(),
        categoryId: Number(form.categoryId),
        artists: form.artists.map((artist, index) => ({
          artistId: artist.artistId,
          isPrimary: Boolean(artist.isPrimary || artist.primary),
          roleType: artist.roleType || 'performer',
          roleName: artist.roleName || '参演艺人',
          visibility: artist.visibility || 'public',
          sort: index + 1,
        })),
        poster: form.poster.trim() || null,
        description: form.description.trim() || null,
        realNameRequired: true,
        ticketTransferAllowed: form.ticketTransferAllowed,
        refundFullDaysBefore: fullDays,
        refundPartialDaysBefore: partialDays,
        refundPartialRate: partialRate,
      })
      setMessage(isPlatformAdminRole(role) ? '平台活动基础信息已保存' : '我的活动基础信息已保存')
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存活动失败')
    } finally {
      setSaving(false)
    }
  }

  const handleVenueProofUpload = async (file: File): Promise<PrivateAssetVO> => {
    if (!userId) throw new Error('请先登录')
    setUploadingProof(true)
    try {
      return await uploadPrivateAsset({ userId, bizType: 'venue-change-proof', file })
    } finally {
      setUploadingProof(false)
    }
  }

  const handleSubmitVenueChange = async () => {
    if (!userId || !stationDetail?.station?.id) {
      setError('活动站点不存在，无法提交场地变更')
      return
    }
    const lockedCity = stationDetail.station.city || ''
    if (!lockedCity.trim()) {
      setError('当前活动站点城市为空，无法提交场地变更')
      return
    }
    if (venueChangeValue.mode === 'tba') {
      setError('场地变更必须选择已有场馆或填写新场馆')
      return
    }
    const validationError = validateStationVenueApproval({ ...venueChangeValue, city: lockedCity })
    if (validationError) {
      setError(validationError)
      return
    }
    setSubmittingVenueChange(true)
    setError('')
    setMessage('')
    try {
      const application = await submitVenueApplication({
        venueId: venueChangeValue.mode === 'existing' ? venueChangeValue.venueId : null,
        venueName: venueChangeValue.venueName.trim(),
        city: lockedCity.trim(),
        address: venueChangeValue.venueAddress.trim(),
        capacity: venueChangeValue.capacity ? Number(venueChangeValue.capacity) : null,
        contactName: venueChangeValue.contactName.trim(),
        contactPhone: venueChangeValue.contactPhone.trim(),
        qualificationNo: venueChangeValue.qualificationNo.trim() || null,
        businessScope: venueChangeValue.businessScope.trim() || null,
        description: venueChangeValue.description.trim() || null,
        validFrom: venueChangeValue.validFrom,
        validTo: venueChangeValue.validTo,
        proofNote: venueChangeValue.proofNote.trim() || null,
        proofAssetId: venueChangeValue.proofAsset?.id ?? null,
        layoutSnapshot: '{}',
      })
      const version = await createStationConfigVersion(stationDetail.station.id, {
        userId,
        changeType: 'change_venue',
        city: lockedCity.trim(),
        stationName: stationDetail.station.stationName || `${lockedCity.trim()}站`,
        venueId: venueChangeValue.mode === 'existing' ? venueChangeValue.venueId : null,
        venueApplicationId: application.id,
        venueName: venueChangeValue.venueName.trim(),
        venueAddress: venueChangeValue.venueAddress.trim(),
        startTime: venueChangeValue.startTime || null,
        endTime: venueChangeValue.endTime || null,
        scheduleTba: !venueChangeValue.startTime,
        reason: '场地临时变动申请',
      })
      await submitStationConfigVersion(version.id)
      setMessage('场地变更申请已提交审核。审核通过前不会影响当前场次；通过后请重新检查/配置 SeatCraft 座位票档。')
      setShowVenueChangeForm(false)
      setVenueChangeValue(createEmptyStationVenueApprovalValue(lockedCity))
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交场地变更申请失败')
    } finally {
      setSubmittingVenueChange(false)
    }
  }

  if (loading) {
    return <div className="py-20 text-center text-[14px] text-[#999]">加载中...</div>
  }

  if (!isAuthed || !Number.isInteger(activityId) || activityId <= 0) {
    return (
      <div className="rounded-xl border border-[#ffd9e6] bg-white p-6 text-[14px] text-[#666]">
        <div className="text-[#ff4d4f]">{error || '请先登录'}</div>
        <div className="mt-4 flex gap-2">
          <Link href="/console/activities" className="rounded-lg border border-[#e5e5e5] px-4 py-2 text-[14px] text-[#666]">
            返回列表
          </Link>
        </div>
      </div>
    )
  }

  const isAdmin = isPlatformAdminRole(role)
  const primarySession = sessions[0]

  return (
    <div>
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-[22px] font-bold text-[#1a1a2e]">{isAdmin ? '编辑平台活动基础信息' : '编辑我的活动基础信息'}</h1>
          <p className="mt-1 text-[13px] text-[#999]">{isAdmin ? '维护平台活动名称、分类、艺人、海报和描述；场次与票档请到场次管理维护。' : '维护自己主办活动的名称、分类、艺人、海报和描述；场次与票档请到我的场次管理维护。'}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link href="/console/activities" className="rounded-lg border border-[#e5e5e5] px-4 py-2 text-[14px] text-[#666] hover:bg-[#fafafa]">
            {isAdmin ? '返回平台活动列表' : '返回我的活动列表'}
          </Link>
          <Link href={`/console/sessions?activityId=${activityId}`} className="rounded-lg bg-[#1a1a2e] px-4 py-2 text-[14px] font-medium text-white hover:bg-[#2a2a42]">
            {isAdmin ? '管理场次/票档' : '管理我的场次/票档'}
          </Link>
        </div>
      </div>

      <div className="mb-5 grid gap-3 md:grid-cols-2">
        <Link href={primarySession ? `/console/sessions/${primarySession.id}/seat-layout` : `/console/activities/${activityId}/seat-layout`} className="rounded-xl border border-[#ffd0df] bg-white p-4 text-[14px] font-medium text-[#ff1268] hover:bg-[#fff7fb]">
          进入 SeatCraft 座位/票档编辑器
          <span className="mt-1 block text-[12px] font-normal text-[#999]">{primarySession ? '已有活动使用场次级座位图，种子活动座位图和票档在这里。' : '新草稿活动可先配置活动级座位图，后续场次可复制调整。'}</span>
        </Link>
      </div>

      <div className="mb-5 rounded-xl border border-[#e5e5e5] bg-white p-5">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="text-[15px] font-semibold text-[#1a1a2e]">场地临时变更申请</div>
            <p className="mt-1 text-[13px] text-[#999]">普通活动可申请更换场馆，城市锁定为当前活动城市；若整个活动已有已支付订单，后端会拒绝提交。</p>
          </div>
          <button type="button" disabled={!stationDetail?.station?.id} onClick={() => setShowVenueChangeForm(true)} className="rounded-lg border border-[#ff1268] px-4 py-2 text-[13px] font-medium text-[#ff1268] disabled:border-[#ddd] disabled:text-[#aaa]">
            申请场地变更
          </button>
        </div>
        {stationDetail?.station?.city && (
          <div className="mt-3 rounded-lg bg-[#fafafa] px-3 py-2 text-[12px] text-[#666]">当前城市：{stationDetail.station.city}，城市不可变更。</div>
        )}
      </div>

      <Drawer
        open={showVenueChangeForm && Boolean(stationDetail?.station?.city)}
        onClose={() => setShowVenueChangeForm(false)}
        title="场地临时变更申请"
        width="w-[600px]"
        loading={submittingVenueChange || uploadingProof}
        footer={(
          <div className="flex justify-end gap-2">
            <button type="button" onClick={() => setShowVenueChangeForm(false)} disabled={submittingVenueChange || uploadingProof} className="rounded-lg border border-[#e5e5e5] px-4 py-2 text-[14px] text-[#666] disabled:opacity-60">取消</button>
            <button type="button" disabled={submittingVenueChange || uploadingProof} onClick={handleSubmitVenueChange} className="rounded-lg bg-[#ff1268] px-4 py-2 text-[14px] font-medium text-white disabled:opacity-50">
              {submittingVenueChange ? '提交中...' : '提交场地变更申请'}
            </button>
          </div>
        )}
      >
        {stationDetail?.station?.city ? (
          <StationVenueApprovalForm
            value={{ ...venueChangeValue, city: stationDetail.station.city }}
            venues={venues}
            submitting={submittingVenueChange}
            uploading={uploadingProof}
            cityLocked
            onUploadProof={handleVenueProofUpload}
            onChange={value => setVenueChangeValue({ ...value, city: stationDetail.station.city || '' })}
          />
        ) : null}
      </Drawer>

      <form onSubmit={handleSubmit} className="max-w-[720px] rounded-xl border border-[#e5e5e5] bg-white p-6">
        <div className="grid gap-4">
          <label className="block text-[13px] font-medium text-[#333]">
            活动名称 *
            <input value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} className="mt-1.5 h-10 w-full rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="例：2026 XX演唱会北京站" />
          </label>
          <div className="rounded-lg border border-[#e5e5e5] bg-[#fafafa] p-3 text-[13px] text-[#333]">
            <div className="font-medium">退票规则</div>
            <div className="mt-2 grid gap-2 sm:grid-cols-3">
              <label>全额退款提前天数<input type="number" min={1} value={form.refundFullDaysBefore} onChange={event => setForm({ ...form, refundFullDaysBefore: event.target.value })} className="mt-1 w-full rounded border border-[#ddd] px-2 py-1.5" /></label>
              <label>折扣退款提前天数<input type="number" min={1} value={form.refundPartialDaysBefore} onChange={event => setForm({ ...form, refundPartialDaysBefore: event.target.value })} className="mt-1 w-full rounded border border-[#ddd] px-2 py-1.5" /></label>
              <label>折扣退款比例（%）<input type="number" min={1} max={100} value={form.refundPartialRate} onChange={event => setForm({ ...form, refundPartialRate: event.target.value })} className="mt-1 w-full rounded border border-[#ddd] px-2 py-1.5" /></label>
            </div>
            <p className="mt-2 text-[12px] text-[#999]">全额退款提前天数必须大于折扣退款提前天数。</p>
          </div>

          <label className="block text-[13px] font-medium text-[#333]">
            分类 *
            <select value={form.categoryId} onChange={event => setForm({ ...form, categoryId: event.target.value })} className="mt-1.5 h-10 w-full rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]">
              <option value="">请选择分类</option>
              {categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
            </select>
          </label>

          <ActivityArtistSelector value={form.artists} onChange={artists => setForm({ ...form, artists })} />

          <LocalFileUpload
            label="活动海报"
            value={form.poster}
            accept="image/jpeg,image/png,image/webp,image/gif"
            uploading={saving}
            onUpload={async (file) => {
              if (!userId) throw new Error('请先登录')
              const asset = await uploadTicketAsset({ userId, bizType: 'activity-poster', file })
              return asset.publicUrl
            }}
            onChange={(poster) => setForm({ ...form, poster })}
            hint="支持 JPG、PNG、WEBP、GIF，上传后自动写入海报地址。"
          />

          <label className="block text-[13px] font-medium text-[#333]">
            描述
            <textarea value={form.description} onChange={event => setForm({ ...form, description: event.target.value })} rows={5} className="mt-1.5 w-full resize-none rounded-lg border border-[#ddd] px-3 py-2 text-[14px] outline-none focus:border-[#ff1268]" placeholder="描述活动内容..." />
          </label>

          <div className="rounded-lg border border-[#e5e5e5] bg-[#fafafa] p-3 text-[13px] text-[#333]">实名制购票：平台活动默认开启，每张票均需绑定实名观演人。</div>
          <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-[#e5e5e5] bg-[#fafafa] p-3 text-[13px] text-[#333]">
            <input type="checkbox" checked={form.ticketTransferAllowed} onChange={event => setForm({ ...form, ticketTransferAllowed: event.target.checked })} className="mt-0.5 accent-[#ff1268]" />
            <span>
              <span className="font-medium">允许电子票转赠</span>
              <span className="mt-1 block text-[#999]">关闭后，已出票用户不能发起转赠；已固化到订单快照的历史订单不受影响。</span>
            </span>
          </label>
        </div>

        {error && <div className="mt-4 rounded-lg bg-[#fff0f3] px-3 py-2 text-[13px] text-[#ff4d4f]">{error}</div>}
        {message && <div className="mt-4 rounded-lg bg-[#f0fff4] px-3 py-2 text-[13px] text-[#16a34a]">{message}</div>}

        <div className="mt-5 flex justify-end gap-2">
          <Link href="/console/activities" className="rounded-lg border border-[#e5e5e5] px-4 py-2 text-[14px] text-[#666]">
            {isAdmin ? '取消编辑平台活动' : '取消编辑我的活动'}
          </Link>
          <button disabled={saving || !isAuthed || !userId} className="rounded-lg bg-[#ff1268] px-4 py-2 text-[14px] font-medium text-white disabled:opacity-50">
            {saving ? '保存中...' : isAdmin ? '保存平台活动信息' : '保存我的活动信息'}
          </button>
        </div>
      </form>
    </div>
  )
}

function isPositiveInteger(value: string) {
  if (!/^[1-9]\d*$/.test(value)) return false
  return Number.isInteger(Number(value)) && Number(value) > 0
}
