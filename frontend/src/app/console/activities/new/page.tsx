'use client'

import { Suspense, useState, useEffect } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { getUser } from '@/lib/auth'
import { listCategories, listAdminVenues, createActivityDraft, createStationConfigVersion, createTourDraft, getAdminTourDetail, listVenueSeatLayoutTemplates, submitStationConfigVersion, submitVenueApplication, uploadPrivateAsset, uploadTicketAsset } from '@/lib/api'
import { hasConsolePermission, isPlatformAdminRole } from '@/lib/console-auth'
import { ChevronLeft, ChevronRight, Check } from 'lucide-react'
import { ActivityArtistSelector } from '@/components/activity-artist/ActivityArtistSelector'
import { LocalFileUpload } from '@/components/LocalFileUpload'
import { StationVenueApprovalForm, createEmptyStationVenueApprovalValue, validateStationVenueApproval, type StationVenueApprovalValue } from '@/components/station-config/StationVenueApprovalForm'
import { globalAlert } from '@/components/GlobalDialog'
import type { CategoryVO, VenueEntity, SeatLayoutTemplateCandidateVO, UserRole, ActivityArtistVO } from '@/types/api'

type SeatMapVisibility = 'published' | 'hidden'
type ActivityMode = 'single' | 'tour'

type TourStationDraft = {
  key: string
  value: StationVenueApprovalValue
}

export default function NewActivityPage() {
  return (
    <Suspense fallback={<div className="py-20 text-center text-[14px] text-[#999]">加载中...</div>}>
      <NewActivityPageContent />
    </Suspense>
  )
}

function NewActivityPageContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const [step, setStep] = useState(1)
  const [submitting, setSubmitting] = useState(false)
  const [role, setRole] = useState<UserRole | ''>('')
  const [permissionCodes, setPermissionCodes] = useState<string[]>([])
  const [checkingRole, setCheckingRole] = useState(true)

  // 分类和场馆记录
  const [categories, setCategories] = useState<CategoryVO[]>([])
  const [venues, setVenues] = useState<VenueEntity[]>([])

  // 步骤1：基本信息
  const [name, setName] = useState('')
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [artists, setArtists] = useState<ActivityArtistVO[]>([])
  const [description, setDescription] = useState('')
  const [poster, setPoster] = useState('')
  const [activityMode, setActivityMode] = useState<ActivityMode>('single')
  const [seatMapVisibility, setSeatMapVisibility] = useState<SeatMapVisibility>('hidden')
  const [perUserLimit, setPerUserLimit] = useState('')
  const [realNameRequired, setRealNameRequired] = useState(false)
  const [ticketTransferAllowed, setTicketTransferAllowed] = useState(true)
  const [refundFullDaysBefore, setRefundFullDaysBefore] = useState('30')
  const [refundPartialDaysBefore, setRefundPartialDaysBefore] = useState('7')
  const [refundPartialRate, setRefundPartialRate] = useState('80')

  // 步骤2：站点配置。普通活动一个站点，巡演多个站点。
  const [stationConfig, setStationConfig] = useState(() => createEmptyStationVenueApprovalValue())
  const [templateCandidates, setTemplateCandidates] = useState<SeatLayoutTemplateCandidateVO[]>([])
  const [selectedTemplateSource, setSelectedTemplateSource] = useState('')
  const [loadingTemplates, setLoadingTemplates] = useState(false)
  const [tourStations, setTourStations] = useState<TourStationDraft[]>([{ key: 'tc1', value: createEmptyStationVenueApprovalValue() }])
  const [uploadingProof, setUploadingProof] = useState(false)

  useEffect(() => {
    const u = getUser()
    if (!u) {
      setCheckingRole(false)
      return
    }
    setRole(u.role || 'user')
    const permissions = u.permissionCodes || []
    setPermissionCodes(permissions)
    if ((searchParams.get('type') === 'tour' && hasConsolePermission(u.role, permissions, 'tour.manage'))
      || (!hasConsolePermission(u.role, permissions, 'activity.manage') && hasConsolePermission(u.role, permissions, 'tour.manage'))) {
      setActivityMode('tour')
    }
    setCheckingRole(false)
    listCategories().then(setCategories).catch(() => {})
    listAdminVenues(u.userId).then(setVenues).catch(() => {})
  }, [searchParams])

  const primaryVenueId = stationConfig.mode === 'existing' ? stationConfig.venueId : null

  useEffect(() => {
    const u = getUser()
    if (!u || !primaryVenueId) {
      setTemplateCandidates([])
      setSelectedTemplateSource('')
      return
    }
    let cancelled = false
    setLoadingTemplates(true)
    listVenueSeatLayoutTemplates(primaryVenueId, u.userId)
      .then(candidates => {
        if (cancelled) return
        setTemplateCandidates(candidates)
        setSelectedTemplateSource('')
      })
      .catch(() => {
        if (cancelled) return
        setTemplateCandidates([])
        setSelectedTemplateSource('')
      })
      .finally(() => {
        if (!cancelled) setLoadingTemplates(false)
      })

    return () => { cancelled = true }
  }, [primaryVenueId])

  const addTourStation = () => {
    setTourStations(prev => [...prev, { key: `tc${Date.now()}`, value: createEmptyStationVenueApprovalValue() }])
  }

  const removeTourStation = (key: string) => {
    setTourStations(prev => prev.length <= 1 ? prev : prev.filter(item => item.key !== key))
  }

  const updateTourStation = (key: string, value: StationVenueApprovalValue) => {
    setTourStations(prev => prev.map(item => item.key === key ? { ...item, value } : item))
  }

  const handleProofUpload = async (file: File) => {
    const u = getUser()
    if (!u?.userId) throw new Error('请先登录')
    setUploadingProof(true)
    try {
      return await uploadPrivateAsset({ userId: u.userId, bizType: 'venue-proof', file })
    } finally {
      setUploadingProof(false)
    }
  }

  const submitVenueMaterial = async (value: StationVenueApprovalValue) => {
    if (value.mode === 'tba') return null
    const application = await submitVenueApplication({
      venueId: value.mode === 'existing' ? value.venueId : null,
      venueName: value.venueName.trim(),
      city: value.city.trim(),
      address: value.venueAddress.trim(),
      capacity: value.capacity ? Number(value.capacity) : null,
      contactName: value.contactName.trim(),
      contactPhone: value.contactPhone.trim(),
      qualificationNo: value.qualificationNo.trim() || null,
      businessScope: value.businessScope.trim() || null,
      description: value.description.trim() || null,
      validFrom: value.validFrom,
      validTo: value.validTo,
      proofNote: value.proofNote.trim() || null,
      proofAssetId: value.proofAsset?.id ?? null,
      layoutSnapshot: '{}',
    })
    return application.id
  }

  const handleSubmit = async () => {
    const u = getUser()
    if (!u || !categoryId || !name.trim() || artists.length === 0) return
    const permissions = u.permissionCodes || []
    const canSubmitActivity = hasConsolePermission(u.role, permissions, 'activity.manage')
    const canSubmitTour = hasConsolePermission(u.role, permissions, 'tour.manage')
    if (activityMode === 'single' && !canSubmitActivity) {
      await globalAlert('当前账号没有普通活动管理权限')
      return
    }
    if (activityMode === 'tour' && !canSubmitTour) {
      await globalAlert('当前账号没有巡演管理权限')
      return
    }
    const limitText = perUserLimit.trim()
    if (limitText && (!/^\d+$/.test(limitText) || Number(limitText) <= 0)) {
      await globalAlert('个人限购张数必须为正整数')
      return
    }
    const refundFullDays = Number(refundFullDaysBefore)
    const refundPartialDays = Number(refundPartialDaysBefore)
    const refundRate = Number(refundPartialRate)
    if (![refundFullDays, refundPartialDays, refundRate].every(Number.isInteger)
      || refundFullDays <= refundPartialDays || refundPartialDays <= 0 || refundRate <= 0 || refundRate > 100) {
      await globalAlert('请检查退票规则：天数必须为正整数，全额退款天数须大于折扣退款天数，折扣比例为1至100%')
      return
    }
    if (activityMode === 'tour') {
      for (const item of tourStations) {
        const error = validateStationVenueApproval(item.value)
        if (error) {
          await globalAlert(error)
          return
        }
      }
      const cities = tourStations
        .map(item => ({ city: item.value.city.trim(), stationName: item.value.stationName.trim() }))
        .filter(item => item.city)
      if (cities.length === 0) {
        await globalAlert('请至少添加一个巡演城市站点')
        return
      }
      setSubmitting(true)
      try {
        const primaryArtist = artists.find(artist => artist.isPrimary || artist.primary) || artists[0]
        const tour = await createTourDraft({
          userId: u.userId,
          title: name.trim(),
          categoryId,
          artistId: primaryArtist.artistId,
          poster: poster || null,
          description: description.trim() || null,
          cities: cities.map(item => ({
            city: item.city,
            stationName: item.stationName || `${item.city}站`,
          })),
        })
        const detail = await getAdminTourDetail(u.userId, tour.id)
        for (let index = 0; index < tourStations.length; index += 1) {
          const value = tourStations[index].value
          const station = detail.stations[index]
          if (!station) continue
          const venueApplicationId = await submitVenueMaterial(value)
          await createStationConfigVersion(station.id, {
            userId: u.userId,
            changeType: 'set_venue',
            city: value.city.trim(),
            stationName: value.stationName.trim() || `${value.city.trim()}站`,
            venueId: value.mode === 'existing' ? value.venueId : null,
            venueApplicationId,
            venueName: value.venueName.trim() || null,
            venueAddress: value.venueAddress.trim() || null,
            startTime: value.startTime || null,
            endTime: value.endTime || null,
            scheduleTba: !value.startTime,
            reason: '新建巡演站点初始配置',
          })
        }
        await globalAlert('巡演草稿已创建。已填写的站点配置已保存为草稿，请在巡演详情逐站点提交审核；座位票档请在巡演详情进入 SeatCraft 配置。')
        router.push(`/console/tours/${tour.id}`)
      } catch (err) {
        await globalAlert('创建失败: ' + (err instanceof Error ? err.message : '未知错误'))
      } finally {
        setSubmitting(false)
      }
      return
    }
    const stationError = validateStationVenueApproval(stationConfig)
    if (stationError) {
      await globalAlert(stationError)
      return
    }
    setSubmitting(true)
    try {
      const selectedTemplate = templateCandidates.find(candidate => `${candidate.sourceType}:${candidate.sourceId}` === selectedTemplateSource)
      const city = stationConfig.city.trim()
      const draft = await createActivityDraft({
        categoryId,
        artists: artists.map((artist, index) => ({
          artistId: artist.artistId,
          isPrimary: Boolean(artist.isPrimary || artist.primary),
          roleType: artist.roleType || 'performer',
          roleName: artist.roleName || '参演艺人',
          visibility: artist.visibility || 'public',
          sort: index + 1,
        })),
        name: name.trim(),
        description,
        poster,
        seatMapVisibility,
        perUserLimit: limitText ? Number(limitText) : null,
        realNameRequired,
        ticketTransferAllowed,
        refundFullDaysBefore: Number(refundFullDaysBefore),
        refundPartialDaysBefore: Number(refundPartialDaysBefore),
        refundPartialRate: Number(refundPartialRate),
      })

      let version
      try {
        const venueApplicationId = await submitVenueMaterial(stationConfig)
        version = await createStationConfigVersion(draft.station.id, {
          userId: u.userId,
          changeType: 'set_venue',
          city,
          stationName: stationConfig.stationName.trim() || `${city}站`,
          venueId: stationConfig.mode === 'existing' ? stationConfig.venueId : null,
          venueApplicationId,
          venueName: stationConfig.venueName.trim() || null,
          venueAddress: stationConfig.venueAddress.trim() || null,
          startTime: stationConfig.startTime || null,
          endTime: stationConfig.endTime || null,
          scheduleTba: !stationConfig.startTime,
          seatTemplateSourceType: selectedTemplate?.sourceType || null,
          seatTemplateSourceId: selectedTemplate?.sourceId || null,
          reason: '新建活动初始站点配置',
        })
      } catch (configErr) {
        await globalAlert(`活动草稿已创建，但站点配置保存失败：${configErr instanceof Error ? configErr.message : '未知错误'}。请在活动管理中继续补齐站点配置。`)
        router.push('/console/activities')
        return
      }

      let message = '活动草稿已保存，站点配置仍为草稿，可后续补齐/提交。票档请后续在活动管理中补齐。'
      try {
        await submitStationConfigVersion(version.id)
        message = '活动草稿已创建，已提交站点配置审核。票档请后续在活动管理中补齐。'
      } catch {
        message = '活动草稿已保存，站点配置仍为草稿，可后续补齐/提交。票档请后续在活动管理中补齐。'
      }

      await globalAlert(message)

      router.push(`/console/activities/${draft.activity.id}/edit`)
    } catch (err) {
      await globalAlert('创建失败: ' + (err instanceof Error ? err.message : '未知错误'))
    } finally {
      setSubmitting(false)
    }
  }

  const steps = activityMode === 'tour' ? ['活动信息', '巡演站点', '确认提交'] : ['活动信息', '站点配置', '确认提交']
  const isAdmin = isPlatformAdminRole(role)
  const canManageActivities = hasConsolePermission(role, permissionCodes, 'activity.manage')
  const canManageTours = hasConsolePermission(role, permissionCodes, 'tour.manage')

  if (checkingRole) {
    return <div className="py-20 text-center text-[14px] text-[#999]">加载中...</div>
  }

  if (!role) {
    return (
      <div className="max-w-[720px] rounded-xl border border-[#e5e5e5] bg-white p-6">
        <h1 className="mb-2 text-[22px] font-bold text-[#1a1a2e]">请先登录</h1>
        <p className="mb-5 text-[14px] text-[#666]">登录后可创建活动草稿并保存站点配置。</p>
        <Link href="/login" className="inline-flex rounded-lg bg-[#ff1268] px-4 py-2 text-[14px] font-medium text-white">去登录</Link>
      </div>
    )
  }

  if (!canManageActivities && !canManageTours) {
    return <div className="rounded-xl border border-[#e5e5e5] bg-white py-16 text-center text-[14px] text-[#999]">无权限访问</div>
  }

  return (
    <div>
      <div className="mb-5">
        <h1 className="text-[22px] font-bold text-[#1a1a2e]">{isAdmin ? '新建平台活动' : '新建我的活动'}</h1>
        <p className="mt-1 text-[13px] text-[#999]">选择普通活动或巡演活动；巡演可先添加城市站点，场馆、时间和票档后续补齐。</p>
      </div>

      {/* 步骤条 */}
      <div className="flex items-center gap-0 mb-8">
        {steps.map((label, i) => (
          <div key={i} className="flex items-center">
            <div className={`flex items-center gap-2 ${i + 1 <= step ? 'text-[#ff1268]' : 'text-[#999]'}`}>
              <div className={`w-8 h-8 rounded-full flex items-center justify-center text-[13px] font-bold border-2 ${
                i + 1 < step
                  ? 'bg-[#ff1268] border-[#ff1268] text-white'
                  : i + 1 === step
                  ? 'border-[#ff1268] text-[#ff1268] bg-white'
                  : 'border-[#ddd] text-[#999] bg-white'
              }`}>
                {i + 1 < step ? <Check className="w-4 h-4" /> : i + 1}
              </div>
              <span className="text-[14px] font-medium">{label}</span>
            </div>
            {i < steps.length - 1 && <div className={`w-12 h-0.5 mx-3 ${i + 1 < step ? 'bg-[#ff1268]' : 'bg-[#ddd]'}`} />}
          </div>
        ))}
      </div>

      <div className="bg-white rounded-xl border border-[#e5e5e5] p-6 max-w-[680px]">
        {/* 步骤1：基本信息 */}
        {step === 1 && (
          <div>
            <div className="mb-4">
              <label className="block text-[13px] font-medium text-[#333] mb-1.5">活动名称 *</label>
              <input value={name} onChange={e => setName(e.target.value)} className="w-full px-3 py-2 border border-[#ddd] rounded-lg text-[14px] outline-none focus:border-[#ff1268]" placeholder="例：2026 XX演唱会北京站" />
            </div>
            <div className="mb-4">
              <label className="block text-[13px] font-medium text-[#333] mb-1.5">分类 *</label>
              <select value={categoryId ?? ''} onChange={e => setCategoryId(e.target.value ? Number(e.target.value) : null)} className="w-full px-3 py-2 border border-[#ddd] rounded-lg text-[14px] outline-none focus:border-[#ff1268]">
                <option value="">请选择分类</option>
                {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
            <div className="mb-4">
              <ActivityArtistSelector value={artists} onChange={setArtists} />
            </div>
            <div className="mb-4">
              <label className="block text-[13px] font-medium text-[#333] mb-1.5">活动简介</label>
              <textarea value={description} onChange={e => setDescription(e.target.value)} rows={3} className="w-full px-3 py-2 border border-[#ddd] rounded-lg text-[14px] outline-none focus:border-[#ff1268] resize-none" placeholder="描述活动内容..." />
            </div>
            <div className="mb-4 rounded-xl border border-[#e5e5e5] bg-[#fafafa] p-4">
              <div className="mb-2 text-[14px] font-semibold text-[#1a1a2e]">活动类型 *</div>
              <div className="space-y-2 text-[13px] text-[#333]">
                <label className={`flex items-start gap-2 ${canManageActivities ? 'cursor-pointer' : 'cursor-not-allowed opacity-50'}`}>
                  <input type="radio" name="activityMode" value="single" checked={activityMode === 'single'} disabled={!canManageActivities} onChange={() => setActivityMode('single')} className="mt-0.5 accent-[#ff1268]" />
                  <span><span className="font-medium">普通活动</span>：创建一个活动草稿，并在下一步填写单个活动站点配置。</span>
                </label>
                <label className={`flex items-start gap-2 ${canManageTours ? 'cursor-pointer' : 'cursor-not-allowed opacity-50'}`}>
                  <input type="radio" name="activityMode" value="tour" checked={activityMode === 'tour'} disabled={!canManageTours} onChange={() => setActivityMode('tour')} className="mt-0.5 accent-[#ff1268]" />
                  <span><span className="font-medium">巡演活动</span>：创建巡演草稿，下一步先添加城市站点，场馆和时间可后续补齐。</span>
                </label>
              </div>
            </div>
            <div className="mb-4">
              <LocalFileUpload
                label="活动海报"
                value={poster}
                accept="image/jpeg,image/png,image/webp,image/gif"
                uploading={submitting}
                onUpload={async (file) => {
                  const u = getUser()
                  if (!u?.userId) throw new Error('请先登录')
                  const asset = await uploadTicketAsset({ userId: u.userId, bizType: 'activity-poster', file })
                  return asset.publicUrl
                }}
                onChange={setPoster}
                hint="支持 JPG、PNG、WEBP、GIF，上传后自动写入海报地址。"
              />
            </div>
            <div className="mb-4">
              <label className="block text-[13px] font-medium text-[#333] mb-1.5">个人限购</label>
              <input type="number" min={1} value={perUserLimit} onChange={e => setPerUserLimit(e.target.value)} className="w-full px-3 py-2 border border-[#ddd] rounded-lg text-[14px] outline-none focus:border-[#ff1268]" placeholder="留空表示不限购，例如 2" />
              <p className="mt-1 text-[12px] text-[#999]">巡演城市站按每个城市站单独限购，不按整轮巡演累计。</p>
            </div>
            {activityMode === 'single' && (
              <div className="mb-4 grid gap-3">
                <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-[#e5e5e5] bg-[#fafafa] p-3 text-[13px] text-[#333]">
                  <input type="checkbox" checked={realNameRequired} onChange={e => setRealNameRequired(e.target.checked)} className="mt-0.5 accent-[#ff1268]" />
                  <span>
                    <span className="font-medium">实名制购票</span>
                    <span className="mt-1 block text-[#999]">开启后，用户下单和候补时需要选择对应数量的实名观演人。</span>
                  </span>
                </label>
                <div className="rounded-lg border border-[#e5e5e5] bg-[#fafafa] p-3 text-[13px] text-[#333]">
                  <div className="font-medium">退票规则</div>
                  <div className="mt-2 grid gap-2 sm:grid-cols-3">
                    <label>全额退款提前天数<input type="number" min={1} value={refundFullDaysBefore} onChange={e => setRefundFullDaysBefore(e.target.value)} className="mt-1 w-full rounded border border-[#ddd] px-2 py-1.5" /></label>
                    <label>折扣退款提前天数<input type="number" min={1} value={refundPartialDaysBefore} onChange={e => setRefundPartialDaysBefore(e.target.value)} className="mt-1 w-full rounded border border-[#ddd] px-2 py-1.5" /></label>
                    <label>折扣退款比例（%）<input type="number" min={1} max={100} value={refundPartialRate} onChange={e => setRefundPartialRate(e.target.value)} className="mt-1 w-full rounded border border-[#ddd] px-2 py-1.5" /></label>
                  </div>
                  <p className="mt-2 text-[12px] text-[#999]">全额退款提前天数必须大于折扣退款提前天数。</p>
                </div>
                <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-[#e5e5e5] bg-[#fafafa] p-3 text-[13px] text-[#333]">
                  <input type="checkbox" checked={ticketTransferAllowed} onChange={e => setTicketTransferAllowed(e.target.checked)} className="mt-0.5 accent-[#ff1268]" />
                  <span>
                    <span className="font-medium">允许电子票转赠</span>
                    <span className="mt-1 block text-[#999]">关闭后，已出票用户不能发起转赠。</span>
                  </span>
                </label>
              </div>
            )}
            <div className="mt-4 rounded-xl border border-[#e5e5e5] bg-[#fafafa] p-4">
              <div className="mb-2 text-[14px] font-semibold text-[#1a1a2e]">座位图展示策略</div>
              <div className="space-y-2 text-[13px] text-[#333]">
                <label className="flex cursor-pointer items-start gap-2">
                  <input type="radio" name="seatMapVisibility" value="hidden" checked={seatMapVisibility === 'hidden'} onChange={() => setSeatMapVisibility('hidden')} className="mt-0.5 accent-[#ff1268]" />
                  <span><span className="font-medium">暂不展示</span>：活动创建后先隐藏座位图，配置确认后再开放。</span>
                </label>
                <label className="flex cursor-pointer items-start gap-2">
                  <input type="radio" name="seatMapVisibility" value="published" checked={seatMapVisibility === 'published'} onChange={() => setSeatMapVisibility('published')} className="mt-0.5 accent-[#ff1268]" />
                  <span><span className="font-medium">立即展示</span>：创建完成后 C 端可查看活动座位图。</span>
                </label>
              </div>
            </div>
          </div>
        )}

        {/* 步骤2：站点配置 */}
        {step === 2 && activityMode === 'single' && (
          <div>
            {primaryVenueId && (
              <div className="mb-5 rounded-xl border border-[#e5e5e5] bg-[#fafafa] p-4">
                <div className="mb-2 text-[14px] font-semibold text-[#1a1a2e]">地点历史座位模板</div>
                {loadingTemplates ? (
                  <div className="text-[13px] text-[#999]">加载中...</div>
                ) : templateCandidates.length > 0 ? (
                  <div className="space-y-3 text-[13px] text-[#333]">
                    <div>检测到该地点有历史座位模板，可复制为本活动初始座位图后再调整。</div>
                    <select value={selectedTemplateSource} onChange={e => setSelectedTemplateSource(e.target.value)} className="w-full rounded-lg border border-[#ddd] px-3 py-2 text-[14px] outline-none focus:border-[#ff1268]">
                      <option value="">不使用模板，稍后配置</option>
                      {templateCandidates.map(candidate => <option key={`${candidate.sourceType}:${candidate.sourceId}`} value={`${candidate.sourceType}:${candidate.sourceId}`}>{candidate.name}</option>)}
                    </select>
                  </div>
                ) : (
                  <div className="text-[13px] text-[#999]">该地点暂无可复用历史模板，可在活动创建后配置。</div>
                )}
              </div>
            )}

            <StationVenueApprovalForm
              value={stationConfig}
              venues={venues}
              submitting={submitting}
              uploading={uploadingProof}
              onUploadProof={handleProofUpload}
              onChange={setStationConfig}
            />
          </div>
        )}

        {step === 2 && activityMode === 'tour' && (
          <div>
            <div className="mb-4 rounded-lg bg-[#fff7fb] px-4 py-3 text-[13px] text-[#666]">
              先添加巡演城市站点即可创建草稿；站点名可留空，系统会默认生成“城市 + 站”。场馆、时间、座位图和票档后续在巡演详情中补齐；绑定场馆时同样需要已通过的场馆审核资料。
            </div>
            {tourStations.map((item, index) => (
              <div key={item.key} className="mb-4 rounded-lg border border-[#f0f0f0] bg-[#fafafa] p-4">
                <div className="mb-3 flex items-center justify-between">
                  <div className="text-[14px] font-medium text-[#333]">城市站点 {index + 1}</div>
                  {tourStations.length > 1 && <button onClick={() => removeTourStation(item.key)} className="border-none bg-transparent text-[12px] text-[#ef4444]">删除</button>}
                </div>
                <StationVenueApprovalForm
                  value={item.value}
                  venues={venues}
                  submitting={submitting}
                  uploading={uploadingProof}
                  onUploadProof={handleProofUpload}
                  onChange={value => updateTourStation(item.key, value)}
                />
              </div>
            ))}
            <button onClick={addTourStation} className="w-full rounded-lg border border-dashed border-[#ff1268] bg-transparent px-4 py-2 text-[13px] text-[#ff1268] hover:bg-[#fff0f3]">
              + 添加城市站点
            </button>
          </div>
        )}

        {/* 步骤3：确认提交 */}
        {step === 3 && (
          <div className="space-y-4 text-[14px] text-[#333]">
            <div className="rounded-lg bg-[#fff7fb] px-4 py-3 text-[13px] text-[#666]">
              {activityMode === 'tour' ? '当前将创建巡演草稿，并添加城市站点。场馆、时间、座位图和票档后续在巡演详情中补齐。' : '当前将创建活动草稿，并保存一个默认站点配置草稿。票档、库存和更多场次请在活动管理中继续补齐，避免当前填写后丢失。'}
            </div>
            <div className="rounded-xl border border-[#f0f0f0] bg-[#fafafa] p-4">
              <div className="mb-2 font-semibold text-[#1a1a2e]">提交内容确认</div>
              <div className="grid gap-1 text-[13px] text-[#666] sm:grid-cols-2">
                <div>活动名称：{name.trim() || '未填写'}</div>
                <div>活动类型：{activityMode === 'tour' ? '巡演活动' : '普通活动'}</div>
                {activityMode === 'single' && <div>实名制购票：{realNameRequired ? '开启' : '关闭'}</div>}
                {activityMode === 'single' && <div>电子票转赠：{ticketTransferAllowed ? '允许' : '禁止'}</div>}
                {activityMode === 'tour' ? (
                  <>
                    <div className="sm:col-span-2">城市站点：{tourStations.map(item => item.value.city.trim()).filter(Boolean).join('、') || '未填写'}</div>
                    <div>场馆时间：后续补齐</div>
                    <div>票档库存：后续补齐</div>
                  </>
                ) : (
                  <>
                    <div>城市：{stationConfig.city.trim() || '未填写'}</div>
                    <div>场馆：{stationConfig.venueName.trim() || '待定'}</div>
                    <div>时间：{stationConfig.startTime ? stationConfig.startTime : '待定'}</div>
                    <div>票档库存：后续补齐</div>
                    <div>更多场次：后续在活动管理中补齐</div>
                  </>
                )}
              </div>
            </div>
          </div>
        )}

        {/* 底部按钮 */}
        <div className="flex items-center justify-between mt-8 pt-5 border-t border-[#f0f0f0]">
          <button
            onClick={() => setStep(Math.max(1, step - 1))}
            disabled={step === 1}
            className="flex items-center gap-1 text-[14px] text-[#666] bg-transparent border-none cursor-pointer disabled:opacity-30 hover:text-[#333] transition-colors"
          >
            <ChevronLeft className="w-4 h-4" /> 上一步
          </button>
          {step < 3 ? (
            <button
              onClick={() => setStep(step + 1)}
              className="flex items-center gap-1 bg-[#ff1268] text-white px-5 py-2 rounded-lg text-[14px] font-medium border-none cursor-pointer hover:bg-[#e0105a] transition-colors"
            >
              下一步 <ChevronRight className="w-4 h-4" />
            </button>
          ) : (
            <button
              onClick={handleSubmit}
              disabled={submitting}
              className="flex items-center gap-1 bg-[#22c55e] text-white px-6 py-2 rounded-lg text-[14px] font-medium border-none cursor-pointer hover:bg-[#16a34a] transition-colors disabled:opacity-50"
            >
              {submitting ? '提交中...' : isAdmin ? '提交平台活动' : '提交我的活动'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
