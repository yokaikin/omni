'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import {
  Check,
  Headphones,
  MessageSquareText,
  Pencil,
  Plus,
  RefreshCw,
  Shield,
  ShieldOff,
  Trash2,
  UserRoundCog,
  Users,
  X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { ConsoleTable, CONSOLE_TABLE_HEADER_CLASS } from '@/components/ConsoleTable'
import { ConsoleTableSkeleton } from '@/components/Skeleton'
import { globalConfirm } from '@/components/GlobalDialog'
import { Modal } from '@/components/ui/Modal'
import { PasswordMatchInput } from '@/components/PasswordMatchInput'
import {
  createOrganizerAdminAccount,
  createSupportAccount,
  deactivateOrganizerAdminAccount,
  deactivateSupportAccount,
  deleteOrganizerAdminAccount,
  deleteSupportAccount,
  getUserInfo,
  listOrganizerAdminAccounts,
  listSupportAccounts,
  updateOrganizerAdminAccount,
  updateSupportAccount,
} from '@/lib/api'
import { hasConsolePermission } from '@/lib/console-auth'
import { validatePasswordPair } from '@/lib/password-validation'
import type { OrganizerAdminAccountVO, SupportAccountVO } from '@/types/api'

type SupportRole = 'support_manager' | 'support_agent'
type AccountTab = 'MANAGER' | 'AGENT' | 'ORGANIZER'
type AccountRecord = SupportAccountVO | OrganizerAdminAccountVO
type AccountDialog = { mode: 'create'; tab: AccountTab } | { mode: 'edit'; tab: AccountTab; accountId: number }

interface AccountTabConfig {
  type: AccountTab
  label: string
  createLabel: string
  emptyText: string
  permission: 'support.account.manage' | 'organizer.account.manage'
  description: string
  supportRole?: SupportRole
}

interface AccountVisualMeta {
  avatarClassName: string
  icon: LucideIcon
  roleLabel: string
  roleClassName: string
  roleIcon: LucideIcon
}

interface AccountForm {
  phone: string
  nickname: string
  password: string
  confirmPassword: string
  status: number
}

const PHONE_PATTERN = /^1\d{10}$/

const ACCOUNT_TABS: AccountTabConfig[] = [
  {
    type: 'MANAGER',
    label: '客服主管',
    createLabel: '新建客服主管',
    emptyText: '暂无客服主管账号',
    permission: 'support.account.manage',
    supportRole: 'support_manager',
    description: '客服主管可维护客服人员、查看客服会话记录，并承担会话升级、质检协同和账号状态管理职责。',
  },
  {
    type: 'AGENT',
    label: '普通客服',
    createLabel: '新建普通客服',
    emptyText: '暂无普通客服账号',
    permission: 'support.account.manage',
    supportRole: 'support_agent',
    description: '普通客服主要处理在线咨询、跟进用户问题和补充内部备注，不具备平台账号配置权限。',
  },
  {
    type: 'ORGANIZER',
    label: '主办方运营员',
    createLabel: '新建主办方运营员',
    emptyText: '暂无主办方运营员账号',
    permission: 'organizer.account.manage',
    description: '主办方运营员负责主办方入驻、跟进分配和合作状态维护，不等同于普通主办方商户账号。',
  },
]

const emptyForm: AccountForm = { phone: '', nickname: '', password: '', confirmPassword: '', status: 1 }

function getTabConfig(tab: AccountTab) {
  return ACCOUNT_TABS.find(item => item.type === tab) || ACCOUNT_TABS[0]
}

function isSupportTab(tab: AccountTab | null) {
  return tab === 'MANAGER' || tab === 'AGENT'
}

function getAvailableTabs(role: string, permissionCodes: string[]) {
  return ACCOUNT_TABS.filter(tab => hasConsolePermission(role, permissionCodes, tab.permission))
}

function getPreferredTab(rawType: string | null, availableTabs: AccountTabConfig[]) {
  const normalized = rawType?.toLowerCase()
  const preferredType =
    normalized === 'organizer' ? 'ORGANIZER'
      : normalized === 'agent' ? 'AGENT'
        : normalized === 'manager' || normalized === 'support' ? 'MANAGER'
          : null

  return availableTabs.find(tab => tab.type === preferredType)?.type || availableTabs[0]?.type || null
}

function isKnownAccountStatus(status: number | null | undefined) {
  return status === 1 || status === 0
}

function isEnabledAccountStatus(status: number | null | undefined) {
  return status === 1
}

function formatAccountStatus(status: number | null | undefined) {
  if (status === 1) return '启用中'
  if (status === 0) return '已停用'
  return '未知账号状态'
}

function formatAccountStatusAction(status: number | null | undefined) {
  if (status === 1) return '停用'
  if (status === 0) return '启用'
  return '状态待核对'
}

function formatTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function getAccountName(account: AccountRecord, tab: AccountTab) {
  if (account.nickname?.trim()) return account.nickname
  return tab === 'ORGANIZER' ? '未命名运营员' : '未命名客服'
}

function getAccountUpdatedAt(account: AccountRecord) {
  return account.updateTime || account.createTime || null
}

function getVisualMeta(tab: AccountTab): AccountVisualMeta {
  if (tab === 'MANAGER') {
    return {
      avatarClassName: 'bg-purple-100 text-purple-700 ring-2 ring-purple-200',
      icon: Shield,
      roleLabel: '客服主管',
      roleClassName: 'border-purple-200 bg-purple-50 text-purple-700',
      roleIcon: Shield,
    }
  }
  if (tab === 'AGENT') {
    return {
      avatarClassName: 'bg-pink-50 text-[var(--omni-brand,#ff1268)]',
      icon: Headphones,
      roleLabel: '普通客服',
      roleClassName: 'border-pink-100 bg-pink-50 text-[var(--omni-brand,#ff1268)]',
      roleIcon: Headphones,
    }
  }
  return {
    avatarClassName: 'bg-blue-50 text-blue-600',
    icon: Users,
    roleLabel: '平台主办方运营员',
    roleClassName: 'border-blue-100 bg-blue-50 text-blue-600',
    roleIcon: Users,
  }
}

function getSupportRoleForTab(tab: AccountTab): SupportRole {
  return tab === 'MANAGER' ? 'support_manager' : 'support_agent'
}

export default function PlatformAccountsPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const initialType = searchParams.get('type')

  const [role, setRole] = useState('')
  const [permissionCodes, setPermissionCodes] = useState<string[]>([])
  const [activeTab, setActiveTab] = useState<AccountTab | null>(null)
  const [supportAccounts, setSupportAccounts] = useState<SupportAccountVO[]>([])
  const [organizerAccounts, setOrganizerAccounts] = useState<OrganizerAdminAccountVO[]>([])
  const [checking, setChecking] = useState(true)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [accountDialog, setAccountDialog] = useState<AccountDialog | null>(null)
  const [form, setForm] = useState<AccountForm>(emptyForm)

  const availableTabs = useMemo(() => getAvailableTabs(role, permissionCodes), [role, permissionCodes])
  const activeConfig = activeTab ? getTabConfig(activeTab) : null
  const canManageActive = Boolean(activeConfig && hasConsolePermission(role, permissionCodes, activeConfig.permission))

  const currentAccounts = useMemo<AccountRecord[]>(() => {
    if (!activeTab) return []
    if (activeTab === 'ORGANIZER') return organizerAccounts
    const supportRole = getSupportRoleForTab(activeTab)
    return supportAccounts.filter(account => account.supportRole === supportRole)
  }, [activeTab, organizerAccounts, supportAccounts])

  const loadAccounts = useCallback(async (tab: AccountTab) => {
    setLoading(true)
    setError('')
    try {
      if (tab === 'ORGANIZER') {
        setOrganizerAccounts(await listOrganizerAdminAccounts())
      } else {
        setSupportAccounts(await listSupportAccounts())
      }
    } catch (err) {
      const config = getTabConfig(tab)
      setError(err instanceof Error ? err.message : `加载${config.label}账号失败`)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let mounted = true
    setChecking(true)
    getUserInfo()
      .then(info => {
        if (!mounted) return
        const permissions = info.permissionCodes || []
        const tabs = getAvailableTabs(info.role || '', permissions)
        if (tabs.length === 0) {
          router.replace('/console')
          return
        }
        setRole(info.role || '')
        setPermissionCodes(permissions)
        setActiveTab(getPreferredTab(initialType, tabs))
      })
      .catch(() => router.replace('/login?ru=/console/accounts'))
      .finally(() => {
        if (mounted) setChecking(false)
      })

    return () => {
      mounted = false
    }
  }, [initialType, router])

  useEffect(() => {
    if (!activeTab) return
    void loadAccounts(activeTab)
  }, [activeTab, loadAccounts])

  const openCreateDialog = () => {
    if (!activeTab || !canManageActive) return
    setMessage('')
    setError('')
    setForm(emptyForm)
    setAccountDialog({ mode: 'create', tab: activeTab })
  }

  const startEdit = (account: AccountRecord) => {
    if (!activeTab || !canManageActive) return
    setMessage('')
    setError('')
    if (!isKnownAccountStatus(account.status)) {
      setError('账号状态待核对，请刷新后再操作')
      return
    }
    setForm({
      phone: account.phone,
      nickname: account.nickname || '',
      password: '',
      confirmPassword: '',
      status: account.status,
    })
    setAccountDialog({ mode: 'edit', tab: activeTab, accountId: account.id })
  }

  const closeDialog = () => {
    setAccountDialog(null)
    setForm(emptyForm)
  }

  const validateForm = () => {
    if (!form.nickname.trim()) return '请填写姓名 / 业务昵称'
    if (!PHONE_PATTERN.test(form.phone.trim())) return '登录手机号需为 11 位手机号'
    return validatePasswordPair({
      password: form.password.trim(),
      confirmPassword: form.confirmPassword.trim(),
      required: accountDialog?.mode === 'create',
    })
  }

  const submitDialog = async () => {
    if (!accountDialog || saving) return
    const config = getTabConfig(accountDialog.tab)
    if (!hasConsolePermission(role, permissionCodes, config.permission)) {
      setError('当前账号无权操作该类型账号')
      return
    }
    const validationError = validateForm()
    setMessage('')
    setError(validationError)
    if (validationError) return

    setSaving(true)
    try {
      const payload = {
        phone: form.phone.trim(),
        nickname: form.nickname.trim(),
        password: form.password.trim(),
      }
      if (accountDialog.tab === 'ORGANIZER') {
        if (accountDialog.mode === 'create') {
          await createOrganizerAdminAccount(payload)
        } else {
          await updateOrganizerAdminAccount(accountDialog.accountId, {
            phone: payload.phone,
            nickname: payload.nickname,
            password: payload.password || undefined,
            status: form.status,
          })
        }
      } else if (accountDialog.mode === 'create') {
        await createSupportAccount({ ...payload, supportRole: getSupportRoleForTab(accountDialog.tab) })
      } else {
        await updateSupportAccount(accountDialog.accountId, {
          phone: payload.phone,
          nickname: payload.nickname,
          password: payload.password || undefined,
          status: form.status,
          supportRole: getSupportRoleForTab(accountDialog.tab),
        })
      }
      closeDialog()
      await loadAccounts(accountDialog.tab)
      setMessage(`${config.label}账号已${accountDialog.mode === 'create' ? '创建' : '更新'}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : `${accountDialog.mode === 'create' ? '创建' : '保存'}${config.label}账号失败`)
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (account: AccountRecord) => {
    if (!activeTab || !canManageActive) return
    const config = getTabConfig(activeTab)
    setMessage('')
    setError('')
    if (!isKnownAccountStatus(account.status)) {
      setError('账号状态待核对，请刷新后再操作')
      return
    }
    const enabled = isEnabledAccountStatus(account.status)
    if (enabled) {
      const confirmed = await globalConfirm({
        type: 'danger',
        title: `停用${config.label}账号`,
        content: `确认停用「${getAccountName(account, activeTab)}」吗？停用后该账号将不能继续进入对应后台。`,
        confirmText: '停用',
        cancelText: '取消',
      })
      if (!confirmed) return
    }
    setSaving(true)
    try {
      if (activeTab === 'ORGANIZER') {
        if (enabled) {
          await deactivateOrganizerAdminAccount(account.id)
        } else {
          await updateOrganizerAdminAccount(account.id, {
            phone: account.phone,
            nickname: account.nickname || '',
            status: 1,
          })
        }
      } else if (enabled) {
        await deactivateSupportAccount(account.id)
      } else {
        await updateSupportAccount(account.id, {
          phone: account.phone,
          nickname: account.nickname || '',
          status: 1,
          supportRole: getSupportRoleForTab(activeTab),
        })
      }
      await loadAccounts(activeTab)
      setMessage(`${config.label}账号已${enabled ? '停用' : '启用'}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '更新账号状态失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (account: AccountRecord) => {
    if (!activeTab || !canManageActive) return
    const config = getTabConfig(activeTab)
    const confirmed = await globalConfirm({
      type: 'danger',
      title: `删除${config.label}账号`,
      content: `确认删除「${getAccountName(account, activeTab)}」吗？删除后该账号将不再出现在平台账号管理列表中。`,
      confirmText: '删除',
      cancelText: '取消',
    })
    if (!confirmed) return
    setMessage('')
    setError('')
    setSaving(true)
    try {
      if (activeTab === 'ORGANIZER') {
        await deleteOrganizerAdminAccount(account.id)
      } else {
        await deleteSupportAccount(account.id)
      }
      await loadAccounts(activeTab)
      setMessage(`${config.label}账号已删除`)
    } catch (err) {
      setError(err instanceof Error ? err.message : `删除${config.label}账号失败`)
    } finally {
      setSaving(false)
    }
  }

  const renderStatusBadge = (status: number | null | undefined) => {
    if (isEnabledAccountStatus(status)) {
      return (
        <span className="inline-flex items-center gap-2 rounded-full bg-emerald-50 px-2.5 py-1 text-[12px] font-medium text-emerald-700">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
          </span>
          启用中
        </span>
      )
    }
    if (status === 0) {
      return <span className="inline-flex rounded-full bg-gray-100 px-2.5 py-1 text-[12px] font-medium text-gray-500">已停用</span>
    }
    return <span className="inline-flex rounded-full bg-amber-50 px-2.5 py-1 text-[12px] font-medium text-amber-600">{formatAccountStatus(status)}</span>
  }

  const dialogConfig = accountDialog ? getTabConfig(accountDialog.tab) : activeConfig
  const newAccountButtonText = activeConfig?.createLabel || '新建账号'

  if (checking) {
    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-[24px] font-bold text-[#111]">平台账号管理</h1>
          <p className="mt-2 text-[14px] text-gray-500">正在校验账号管理权限...</p>
        </div>
        <ConsoleTableSkeleton rows={6} columns={6} />
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-[24px] font-bold text-[#111]">平台账号管理</h1>
          <p className="mt-2 text-[14px] text-gray-500">统一维护客服主管、普通客服和平台主办方运营员账号，按权限展示可管理范围。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {isSupportTab(activeTab) ? (
            <Link href="/console/support-conversations" className="inline-flex h-10 items-center gap-2 rounded-lg border border-gray-200 bg-white px-4 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]">
              <MessageSquareText className="h-4 w-4" />
              查看会话记录
            </Link>
          ) : null}
          <button
            type="button"
            onClick={() => activeTab && loadAccounts(activeTab)}
            disabled={!activeTab || loading || saving}
            className="inline-flex h-10 items-center gap-2 rounded-lg border border-gray-200 bg-white px-4 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-50"
          >
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </button>
          <button
            type="button"
            onClick={openCreateDialog}
            disabled={!activeTab || !canManageActive || saving}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-[#ff1268] px-4 text-[13px] font-medium text-white hover:bg-[#e0105a] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Plus className="h-4 w-4" />
            {newAccountButtonText}
          </button>
        </div>
      </div>

      {(message || error) && (
        <div className={`rounded-xl px-4 py-3 text-[13px] ${error ? 'bg-red-50 text-red-500' : 'bg-green-50 text-green-600'}`}>
          {error || message}
        </div>
      )}

      <div className="flex flex-wrap gap-2 rounded-xl border border-gray-200 bg-white p-2 shadow-sm">
        {availableTabs.map(tab => {
          const active = activeTab === tab.type
          return (
            <button
              key={tab.type}
              type="button"
              onClick={() => {
                setMessage('')
                setError('')
                setActiveTab(tab.type)
              }}
              className={`inline-flex h-10 items-center gap-2 rounded-lg px-4 text-[13px] font-medium transition-colors ${
                active
                  ? 'bg-[#ff1268] text-white shadow-sm'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-[#111]'
              }`}
            >
              {tab.label}
            </button>
          )
        })}
      </div>

      <ConsoleTable loading={loading} skeletonRows={6} skeletonColumns={6}>
        <table className="w-full table-fixed text-left text-[13px]">
          <thead>
            <tr>
              <th className={`${CONSOLE_TABLE_HEADER_CLASS} w-[26%] whitespace-nowrap px-4 py-3 text-left`}>人员名称</th>
              <th className={`${CONSOLE_TABLE_HEADER_CLASS} w-[17%] whitespace-nowrap px-4 py-3 text-left`}>登录手机号</th>
              <th className={`${CONSOLE_TABLE_HEADER_CLASS} w-[20%] whitespace-nowrap px-4 py-3 text-left`}>职务/权限角色</th>
              <th className={`${CONSOLE_TABLE_HEADER_CLASS} w-[13%] whitespace-nowrap px-4 py-3 text-left`}>账号状态</th>
              <th className={`${CONSOLE_TABLE_HEADER_CLASS} w-[14%] whitespace-nowrap px-4 py-3 text-left`}>最后更新</th>
              <th className={`${CONSOLE_TABLE_HEADER_CLASS} w-[10%] whitespace-nowrap px-4 py-3 text-right`}>操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {!activeTab || !activeConfig ? (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center text-[13px] text-gray-400">暂无可管理的账号类型</td>
              </tr>
            ) : currentAccounts.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center text-[13px] text-gray-400">{activeConfig.emptyText}</td>
              </tr>
            ) : currentAccounts.map(account => {
              const meta = getVisualMeta(activeTab)
              const AvatarIcon = meta.icon
              const RoleIcon = meta.roleIcon
              const enabled = isEnabledAccountStatus(account.status)
              return (
                <tr key={`${activeTab}-${account.id}`} className="align-middle hover:bg-gray-50/80">
                  <td className="px-4 py-4">
                    <div className="flex min-w-0 items-center gap-3">
                      <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${meta.avatarClassName}`}>
                        <AvatarIcon className="h-5 w-5" />
                      </div>
                      <div className="min-w-0">
                        <div className="flex min-w-0 items-center gap-2">
                          <span className="truncate text-[14px] font-semibold text-[#111]">{getAccountName(account, activeTab)}</span>
                          {activeTab === 'MANAGER' ? <span className="shrink-0 rounded-full bg-gradient-to-r from-purple-600 to-fuchsia-500 px-2 py-0.5 text-[11px] font-semibold text-white">主管</span> : null}
                        </div>
                        <div className="mt-1 truncate text-[12px] text-gray-400">账号 ID：{account.id}</div>
                      </div>
                    </div>
                  </td>
                  <td className="whitespace-nowrap px-4 py-4 font-mono text-[13px] text-gray-700">{account.phone}</td>
                  <td className="px-4 py-4">
                    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[12px] font-medium ${meta.roleClassName}`}>
                      <RoleIcon className="h-3.5 w-3.5" />
                      {meta.roleLabel}
                    </span>
                  </td>
                  <td className="whitespace-nowrap px-4 py-4">{renderStatusBadge(account.status)}</td>
                  <td className="whitespace-nowrap px-4 py-4 text-gray-500">{formatTime(getAccountUpdatedAt(account))}</td>
                  <td className="px-4 py-4">
                    <div className="flex items-center justify-end gap-2 whitespace-nowrap">
                      <button
                        type="button"
                        onClick={() => startEdit(account)}
                        disabled={saving || !canManageActive}
                        className="inline-flex items-center gap-1 rounded-md border border-gray-200 px-2.5 py-1.5 text-[12px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        <Pencil className="h-3.5 w-3.5" />
                        编辑
                      </button>
                      <button
                        type="button"
                        onClick={() => toggleStatus(account)}
                        disabled={saving || !canManageActive || !isKnownAccountStatus(account.status)}
                        className={`inline-flex items-center gap-1 rounded-md border px-2.5 py-1.5 text-[12px] disabled:cursor-not-allowed disabled:opacity-50 ${
                          enabled
                            ? 'border-amber-200 text-amber-600 hover:bg-amber-50'
                            : 'border-emerald-200 text-emerald-600 hover:bg-emerald-50'
                        }`}
                      >
                        {enabled ? <ShieldOff className="h-3.5 w-3.5" /> : isKnownAccountStatus(account.status) ? <Check className="h-3.5 w-3.5" /> : <X className="h-3.5 w-3.5" />}
                        {formatAccountStatusAction(account.status)}
                      </button>
                      <button
                        type="button"
                        onClick={() => remove(account)}
                        disabled={saving || !canManageActive}
                        className="inline-flex items-center gap-1 rounded-md border border-red-100 px-2.5 py-1.5 text-[12px] text-red-500 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </ConsoleTable>

      {accountDialog && dialogConfig ? (
        <Modal
          open={Boolean(accountDialog)}
          onClose={closeDialog}
          title={`${accountDialog.mode === 'edit' ? '编辑' : '新建'}${dialogConfig.label}账号`}
          size="md"
          loading={saving}
          footer={(
            <>
              <button type="button" onClick={closeDialog} disabled={saving} className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268] disabled:opacity-60">
                取消
              </button>
              <button type="button" onClick={submitDialog} disabled={saving} className="rounded-lg bg-[#ff1268] px-4 py-2 text-[13px] font-medium text-white hover:bg-[#e0105a] disabled:opacity-60">
                {saving ? '提交中...' : accountDialog.mode === 'edit' ? '确认保存' : '确认创建'}
              </button>
            </>
          )}
        >
          <form
            autoComplete="off"
            onSubmit={event => {
              event.preventDefault()
              void submitDialog()
            }}
          >
            <input type="text" name="prevent_autofill_user" className="hidden" aria-hidden="true" />
            <input type="password" name="prevent_autofill_pass" className="hidden" aria-hidden="true" />
            <div className="grid gap-4">
              <label className="grid gap-2 text-[13px] font-medium text-gray-700">
                <span>姓名 / 业务昵称 <span className="text-red-500">*</span></span>
                <input
                  value={form.nickname}
                  autoComplete="off"
                  onChange={event => setForm({ ...form, nickname: event.target.value })}
                  placeholder="请输入姓名或业务昵称"
                  className="h-10 rounded-lg border border-gray-200 px-3 text-[13px] font-normal outline-none focus:border-[#ff1268]"
                />
              </label>
              <label className="grid gap-2 text-[13px] font-medium text-gray-700">
                <span>登录手机号 <span className="text-red-500">*</span></span>
                <input
                  value={form.phone}
                  autoComplete="off"
                  onChange={event => setForm({ ...form, phone: event.target.value })}
                  placeholder="请输入 11 位手机号"
                  className="h-10 rounded-lg border border-gray-200 px-3 text-[13px] font-normal outline-none focus:border-[#ff1268]"
                />
                <span className="text-[12px] font-normal text-gray-400">登录手机号需为 11 位手机号</span>
              </label>
              <label className="grid gap-2 text-[13px] font-medium text-gray-700">
                <span>登录密码 {accountDialog.mode === 'create' ? <span className="text-red-500">*</span> : null}</span>
                <PasswordMatchInput
                  value={form.password}
                  onChange={value => setForm({ ...form, password: value })}
                  placeholder={accountDialog.mode === 'edit' ? '重置登录密码，留空则不修改' : '请输入初始登录密码'}
                  name="password"
                  ariaLabel="设置密码"
                  caretClassName="caret-gray-900"
                  className="rounded-lg bg-white"
                />
              </label>
              <label className="grid gap-2 text-[13px] font-medium text-gray-700">
                <span>确认密码 {accountDialog.mode === 'create' ? <span className="text-red-500">*</span> : null}</span>
                <PasswordMatchInput
                  value={form.confirmPassword}
                  onChange={value => setForm({ ...form, confirmPassword: value })}
                  compareValue={form.password}
                  placeholder="请再次输入密码"
                  name="confirmPassword"
                  ariaLabel="确认密码"
                  caretClassName="caret-gray-900"
                  className="rounded-lg bg-white"
                />
              </label>
              <label className="grid gap-2 text-[13px] font-medium text-gray-700">
                <span>职能说明</span>
                <textarea
                  readOnly
                  value={dialogConfig.description}
                  className="min-h-[86px] resize-none rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-[13px] font-normal leading-6 text-gray-600 outline-none"
                />
              </label>
            </div>
          </form>
        </Modal>
      ) : null}
    </div>
  )
}
