'use client'

import { useEffect, useState } from 'react'
import { getUser } from '@/lib/auth'
import { listMyVenueApplications, submitVenueApplication, uploadPrivateAsset } from '@/lib/api'
import { PrivateFileUpload } from '@/components/PrivateFileUpload'
import { SeatLayoutDesigner } from '@/components/seatcraft/SeatLayoutDesigner'
import { toSeatCraftLayoutDraft, type SeatCraftLayoutDraft } from '@/components/seatcraft/types'
import type { PrivateAssetVO, VenueApplicationVO } from '@/types/api'

const statusText: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已驳回' }

function formatVenueApplicationStatus(status: number) {
  return statusText[status] || '未知场馆审核状态'
}

function createDefaultLayout(name: string): SeatCraftLayoutDraft {
  return {
    name: `${name} 座位图`,
    templateType: 'concert',
    stage: { title: '舞台', x: 0, y: 0 },
    canvasWidth: 800,
    canvasHeight: 600,
    sections: [],
    blocks: [],
    overrides: [],
    ticketGroups: [],
  }
}

export default function VenueApplyPage() {
  const [userId, setUserId] = useState(0)
  const [applications, setApplications] = useState<VenueApplicationVO[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [uploadingProof, setUploadingProof] = useState(false)
  const [uploadingFire, setUploadingFire] = useState(false)
  const [uploadingLease, setUploadingLease] = useState(false)
  const [message, setMessage] = useState('')
  const [applicationsLoading, setApplicationsLoading] = useState(true)
  const [applicationsError, setApplicationsError] = useState('')
  const [proofAsset, setProofAsset] = useState<PrivateAssetVO | null>(null)
  const [fireAsset, setFireAsset] = useState<PrivateAssetVO | null>(null)
  const [leaseAsset, setLeaseAsset] = useState<PrivateAssetVO | null>(null)
  const [form, setForm] = useState({
    venueName: '',
    venueNameEn: '',
    venueType: '',
    city: '',
    province: '',
    district: '',
    address: '',
    capacity: '',
    contactName: '',
    contactPhone: '',
    qualificationNo: '',
    businessScope: '',
    description: '',
    validFrom: '',
    validTo: '',
    proofNote: '',
  })
  const [layoutDraft, setLayoutDraft] = useState<SeatCraftLayoutDraft | null>(null)

  const loadApplications = (nextUserId = userId) => {
    if (!nextUserId) return
    setApplicationsLoading(true)
    setApplicationsError('')
    listMyVenueApplications()
      .then(setApplications)
      .catch(err => setApplicationsError(err instanceof Error ? err.message : '加载我的场馆审核资料失败'))
      .finally(() => setApplicationsLoading(false))
  }

  useEffect(() => {
    const u = getUser()
    if (!u) return
    setUserId(u.userId)
    loadApplications(u.userId)
  }, [])

  useEffect(() => {
    if (form.venueName.trim() && !layoutDraft) {
      setLayoutDraft(createDefaultLayout(form.venueName))
    }
  }, [form.venueName])

  const validate = () => {
    if (!form.venueName.trim()) return '请填写场馆名称'
    if (!form.city.trim()) return '请填写城市'
    if (!form.address.trim()) return '请填写地址'
    if (!form.contactName.trim()) return '请填写联系人姓名'
    if (!form.contactPhone.trim()) return '请填写联系电话'
    if (!form.validFrom) return '请选择凭证有效开始时间'
    if (!form.validTo) return '请选择凭证有效结束时间'
    if (form.validTo <= form.validFrom) return '凭证有效结束时间必须晚于开始时间'
    if (!form.proofNote.trim() && !proofAsset && !fireAsset && !leaseAsset) return '请填写场馆审批文件说明或上传审核附件'
    if (!layoutDraft || ((layoutDraft.blocks?.length ?? 0) === 0 && layoutDraft.sections.length === 0)) return '请绘制至少一个座位区域'
    if ((layoutDraft.blocks?.length ?? 0) > 0 && (layoutDraft.ticketGroups?.length ?? 0) === 0) return '请至少配置一个票档组'
    return ''
  }

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const error = validate()
    if (error) {
      setMessage(error)
      return
    }
    if (uploadingProof || uploadingFire || uploadingLease) {
      setMessage('附件上传中，请稍后提交')
      return
    }
    setSubmitting(true)
    setMessage('')
    try {
      const layoutPayload = layoutDraft ? {
        id: 0,
        name: layoutDraft.name,
        templateType: layoutDraft.templateType,
        stageTitle: layoutDraft.stage.title,
        stageX: layoutDraft.stage.x,
        stageY: layoutDraft.stage.y,
        canvasWidth: layoutDraft.canvasWidth,
        canvasHeight: layoutDraft.canvasHeight,
        sections: layoutDraft.sections.map(s => ({
          id: Number(s.id),
          sectionKey: s.sectionKey,
          name: s.name,
          rows: s.rows,
          cols: s.cols,
          x: s.x,
          y: s.y,
          color: s.color,
          type: s.type,
          layout: s.layout,
          radius: s.radius ?? null,
          arcSpan: s.arcSpan ?? null,
          rotation: s.rotation ?? null,
          primeRowStart: s.primeRowStart ?? null,
          primeRowEnd: s.primeRowEnd ?? null,
          primeColStart: s.primeColStart ?? null,
          primeColEnd: s.primeColEnd ?? null,
          ticketTypeId: s.ticketTypeId ?? null,
        })),
        blocks: layoutDraft.blocks ?? [],
        overrides: layoutDraft.overrides ?? [],
        ticketGroups: layoutDraft.ticketGroups ?? [],
      } : undefined

      const materialPayloads: import('@/types/api').VenueApplicationMaterialPayload[] = []
      if (fireAsset) {
        materialPayloads.push({ materialType: 'FIRE_SAFETY_PERMIT', assetId: fireAsset.id, validFrom: form.validFrom, validTo: form.validTo })
      }
      if (leaseAsset) {
        materialPayloads.push({ materialType: 'VENUE_LEASE_AGREEMENT', assetId: leaseAsset.id, validFrom: form.validFrom, validTo: form.validTo })
      }
      await submitVenueApplication({
        ...form,
        capacity: form.capacity ? Number(form.capacity) : null,
        proofFileUrl: null,
        proofAssetId: proofAsset?.id ?? null,
        materials: materialPayloads,
        layout: layoutPayload,
      })
      setMessage('场馆审核资料已提交，等待平台审核')
      setForm({ venueName: '', venueNameEn: '', venueType: '', city: '', province: '', district: '', address: '', capacity: '', contactName: '', contactPhone: '', qualificationNo: '', businessScope: '', description: '', validFrom: '', validTo: '', proofNote: '' })
      setProofAsset(null)
      setFireAsset(null)
      setLeaseAsset(null)
      setLayoutDraft(null)
      loadApplications(userId)
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '提交失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleProofUpload = async (file: File) => {
    setUploadingProof(true)
    try {
      return await uploadPrivateAsset({ userId, bizType: 'venue-proof', file })
    } finally {
      setUploadingProof(false)
    }
  }

  const handleMaterialUpload = async (file: File, materialType: 'FIRE_SAFETY_PERMIT' | 'VENUE_LEASE_AGREEMENT') => {
    const setter = materialType === 'FIRE_SAFETY_PERMIT' ? setUploadingFire : setUploadingLease
    setter(true)
    try {
      return await uploadPrivateAsset({
        userId,
        bizType: materialType === 'FIRE_SAFETY_PERMIT' ? 'venue-fire-safety' : 'venue-lease-agreement',
        file,
      })
    } finally {
      setter(false)
    }
  }

  return (
    <div>
      <h1 className="mb-1 text-[22px] font-bold text-[#1a1a2e]">提交场馆审核资料</h1>
      <p className="mb-5 text-[13px] text-[#999]">上传场馆资料和场地审批文件供平台核验真伪，避免虚假材料造成后续纠纷；平台不拥有场馆，也不授予场地使用权。</p>

      <form onSubmit={handleSubmit} className="mb-6 rounded-xl border border-[#e5e5e5] bg-white p-5">
        <div className="grid gap-3 lg:grid-cols-2">
          <input value={form.venueName} onChange={e => setForm({ ...form, venueName: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="场馆名称 *" />
          <input value={form.venueNameEn} onChange={e => setForm({ ...form, venueNameEn: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="场馆英文名（可选）" />
          <select value={form.venueType} onChange={e => setForm({ ...form, venueType: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]">
            <option value="">场馆类型（默认综合演艺场馆）</option>
            <option value="室内体育馆">室内体育馆</option>
            <option value="室外体育场">室外体育场</option>
            <option value="演艺大剧院">演艺大剧院</option>
            <option value="Livehouse">Livehouse</option>
          </select>
          <input value={form.city} onChange={e => setForm({ ...form, city: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="城市 *" />
          <input value={form.province} onChange={e => setForm({ ...form, province: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="省份（可选）" />
          <input value={form.district} onChange={e => setForm({ ...form, district: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="区县（可选）" />
          <input value={form.address} onChange={e => setForm({ ...form, address: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268] lg:col-span-2" placeholder="地址 *" />
          <input type="number" value={form.capacity} onChange={e => setForm({ ...form, capacity: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="容量" />
          <input value={form.qualificationNo} onChange={e => setForm({ ...form, qualificationNo: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="资质编号" />
          <input value={form.contactName} onChange={e => setForm({ ...form, contactName: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="联系人姓名 *" />
          <input value={form.contactPhone} onChange={e => setForm({ ...form, contactPhone: e.target.value })} className="h-10 rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" placeholder="联系电话 *" />
          <label className="text-[12px] text-[#666]">
            凭证有效开始时间 *
            <input type="datetime-local" value={form.validFrom} onChange={e => setForm({ ...form, validFrom: e.target.value })} className="mt-1 h-10 w-full rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" />
          </label>
          <label className="text-[12px] text-[#666]">
            凭证有效结束时间 *
            <input type="datetime-local" value={form.validTo} onChange={e => setForm({ ...form, validTo: e.target.value })} className="mt-1 h-10 w-full rounded-lg border border-[#ddd] px-3 text-[14px] outline-none focus:border-[#ff1268]" />
          </label>
          <textarea value={form.businessScope} onChange={e => setForm({ ...form, businessScope: e.target.value })} rows={3} className="rounded-lg border border-[#ddd] px-3 py-2 text-[14px] outline-none focus:border-[#ff1268] lg:col-span-2" placeholder="经营范围" />
          <textarea value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} rows={3} className="rounded-lg border border-[#ddd] px-3 py-2 text-[14px] outline-none focus:border-[#ff1268] lg:col-span-2" placeholder="资料说明/备注" />
          <textarea value={form.proofNote} onChange={e => setForm({ ...form, proofNote: e.target.value })} rows={3} className="rounded-lg border border-[#ddd] px-3 py-2 text-[14px] outline-none focus:border-[#ff1268] lg:col-span-2" placeholder="场馆审批文件说明（与审核附件至少填写一项）" />
          <div className="lg:col-span-2">
            <PrivateFileUpload
              label="场馆审核资料附件"
              value={proofAsset}
              accept="application/pdf,image/jpeg,image/png,image/webp"
              uploading={uploadingProof || submitting}
              onUpload={handleProofUpload}
              onChange={setProofAsset}
              hint="支持 PDF、JPEG、PNG、WEBP。附件以私有文件保存，仅供平台审核，不会公开展示。"
            />
          </div>
          <div>
            <PrivateFileUpload label="消防安全检查合格证明" value={fireAsset} accept="application/pdf,image/jpeg,image/png,image/webp" uploading={uploadingFire || submitting} onUpload={file => handleMaterialUpload(file, 'FIRE_SAFETY_PERMIT')} onChange={setFireAsset} hint="结构化材料，可选；支持 PDF、JPEG、PNG、WEBP。" />
          </div>
          <div>
            <PrivateFileUpload label="场地租赁/运营授权协议" value={leaseAsset} accept="application/pdf,image/jpeg,image/png,image/webp" uploading={uploadingLease || submitting} onUpload={file => handleMaterialUpload(file, 'VENUE_LEASE_AGREEMENT')} onChange={setLeaseAsset} hint="结构化材料，可选；支持 PDF、JPEG、PNG、WEBP。" />
          </div>
        </div>

        <div className="mt-5">
          <h3 className="mb-2 text-[15px] font-semibold text-[#1a1a2e]">本次场馆 SeatCraft 草图</h3>
          <div className="rounded-lg border border-[#e5e5e5] overflow-hidden">
            {layoutDraft && (
              <SeatLayoutDesigner layout={layoutDraft} onChange={setLayoutDraft} />
            )}
          </div>
        </div>

        {message && <div className="mt-3 text-[13px] text-[#666]">{message}</div>}
        <button
          disabled={submitting || uploadingProof || uploadingFire || uploadingLease || !layoutDraft || ((layoutDraft.blocks?.length ?? 0) === 0 && layoutDraft.sections.length === 0)}
          className="mt-4 rounded-lg bg-[#ff1268] px-5 py-2 text-[14px] font-medium text-white disabled:opacity-50"
        >
          {uploadingProof || uploadingFire || uploadingLease ? '附件上传中...' : submitting ? '提交中...' : '提交场馆审核资料'}
        </button>
      </form>

      <div className="rounded-xl border border-[#e5e5e5] bg-white p-5">
        <h2 className="mb-3 text-[16px] font-bold text-[#1a1a2e]">我的场馆审核资料</h2>
        {applicationsError ? <div className="text-[14px] text-[#ff4d4f]">{applicationsError}</div> : applicationsLoading ? <div className="text-[14px] text-[#999]">加载中...</div> : applications.length === 0 ? <div className="text-[14px] text-[#999]">暂无申请记录</div> : (
          <div className="space-y-3">
            {applications.map(item => (
              <div key={item.id} className="rounded-lg border border-[#f0f0f0] p-3 text-[14px]">
                <div className="flex items-center justify-between gap-3">
                  <div className="font-medium text-[#333]">{item.venueName}</div>
                  <span className="rounded-full bg-[#f5f5f5] px-2 py-0.5 text-[12px] text-[#666]">{formatVenueApplicationStatus(item.status)}</span>
                </div>
                <div className="mt-1 text-[#666]">{item.city} · {item.address}</div>
                {item.reviewNote && <div className="mt-1 text-[13px] text-[#999]">审核备注：{item.reviewNote}</div>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
