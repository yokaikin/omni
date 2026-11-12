'use client'

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ArrowRightLeft, BookOpen, ClipboardList, FileText, Headphones, LogOut, Send, ShieldAlert, Tags } from 'lucide-react'
import {
  addSupportNote,
  claimSupportConversation,
  closeSupportConversation,
  escalateSupportConversation,
  getSupportConversationContext,
  getUserInfo,
  listAgentSupportConversations,
  listEnabledSupportAgents,
  listSupportAudits,
  listSupportMessages,
  listSupportNotes,
  listSupportQuickReplies,
  sendSupportMessage,
  transferSupportConversation,
  updateSupportTags,
} from '@/lib/api'
import { logout } from '@/lib/auth'
import { canUseConsoleAction } from '@/lib/console-auth'
import { openSupportStream } from '@/lib/support-stream'
import { appendQuickReply, buildCloseRequestMessage, canClaimSupportConversation, canEditSupportConversation, canReplySupportConversation, canRequestSupportClose, formatSupportAuditAction, formatSupportContextSectionCount, formatSupportConversationStatus, formatSupportConversationWriteBlockedMessage, formatSupportMessageSender, formatSupportSlaText, formatSupportTagLabel, getSupportQueueTabs, getSupportTagOptions, hasSupportContextData, mergeSupportConversations, pickLatestSupportConversation, shouldPollSupportConversation, sortSupportConversationsForQueue, type SupportQueueFilter } from '@/lib/support-tools'
import type { SupportAccountVO, SupportAuditVO, SupportContextVO, SupportConversationVO, SupportMessageVO, SupportNoteVO, SupportQuickReplyVO } from '@/types/api'

function getConversationUserDisplay(conversation: SupportConversationVO) {
  return conversation.userNickname || conversation.userPhoneMask || `用户编号：${conversation.userId}`
}

function formatTime(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export default function SupportWorkbenchPage() {
  const router = useRouter()
  const [checking, setChecking] = useState(true)
  const [conversations, setConversations] = useState<SupportConversationVO[]>([])
  const [active, setActive] = useState<SupportConversationVO | null>(null)
  const [messages, setMessages] = useState<SupportMessageVO[]>([])
  const [notes, setNotes] = useState<SupportNoteVO[]>([])
  const [audits, setAudits] = useState<SupportAuditVO[]>([])
  const [context, setContext] = useState<SupportContextVO | null>(null)
  const [quickReplies, setQuickReplies] = useState<SupportQuickReplyVO[]>([])
  const [supportAgents, setSupportAgents] = useState<SupportAccountVO[]>([])
  const [conversationFilter, setConversationFilter] = useState<SupportQueueFilter>('pending')
  const [text, setText] = useState('')
  const [noteText, setNoteText] = useState('')
  const [selectedTags, setSelectedTags] = useState<string[]>([])
  const [transferTarget, setTransferTarget] = useState('')
  const [transferReason, setTransferReason] = useState('')
  const [escalationReason, setEscalationReason] = useState('')
  const [closeReason, setCloseReason] = useState('')
  const [status, setStatus] = useState('')
  const [error, setError] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const [contextLoading, setContextLoading] = useState(false)
  const activeLoadIdRef = useRef(0)
  const activeRef = useRef<SupportConversationVO | null>(null)
  activeRef.current = active
  const [polling, setPolling] = useState(false)

  const loadConversations = async () => {
    const [pending, inProgress, overdue, closeRequested, closed] = await Promise.all([
      listAgentSupportConversations({ queue: 'pending' }),
      listAgentSupportConversations({ queue: 'in_progress' }),
      listAgentSupportConversations({ queue: 'overdue' }),
      listAgentSupportConversations({ queue: 'close_requested' }),
      listAgentSupportConversations({ queue: 'closed' }),
    ])
    const data = sortSupportConversationsForQueue(mergeSupportConversations([
      ...(pending || []),
      ...(inProgress || []),
      ...(overdue || []),
      ...(closeRequested || []),
      ...(closed || []),
    ]))
    setConversations(data)
    setActive(current => pickLatestSupportConversation(current, data))
  }

  const visibleConversations = useMemo(() => {
    return sortSupportConversationsForQueue(conversations.filter(item => {
      if (conversationFilter === 'closed') return item.status === 'CLOSED'
      if (conversationFilter === 'overdue') return item.status !== 'CLOSED' && Boolean(item.slaOverdue)
      if (conversationFilter === 'pending') return item.status === 'WAITING_AGENT' && !item.slaOverdue
      if (conversationFilter === 'close_requested') return item.status === 'CLOSE_REQUESTED' && !item.slaOverdue
      return item.status === 'ASSIGNED' && !item.slaOverdue
    }))
  }, [conversations, conversationFilter])

  const filterTabs = getSupportQueueTabs(conversations)
  const canClaimActiveConversation = canClaimSupportConversation(active?.status)
  const canReply = canReplySupportConversation(active?.status)
  const canEditActiveConversation = canEditSupportConversation(active?.status)
  const canCloseActiveConversation = canRequestSupportClose(active?.status)
  const tagOptions = getSupportTagOptions()
  const transferTargets = supportAgents.filter(agent => agent.id !== active?.assignedAgentId)
  const quickReplyGroups = useMemo(() => {
    const map = new Map<string, SupportQuickReplyVO[]>()
    for (const item of quickReplies) {
      map.set(item.category, [...(map.get(item.category) || []), item])
    }
    return Array.from(map.entries())
  }, [quickReplies])
  const contextCounts = useMemo(() => {
    if (!context) return []
    return [
      ['orders', context.orders.length],
      ['refunds', context.refunds.length],
      ['tickets', context.tickets.length],
      ['waitlist', context.waitlist.length],
      ['grabRequests', context.grabRequests.length],
      ['notifications', context.notifications.length],
    ] as Array<[string, number]>
  }, [context])

  const loadMessages = async (conversationId: number, loadId: number) => {
    const data = await listSupportMessages(conversationId)
    if (activeLoadIdRef.current === loadId) {
      setMessages(data || [])
    }
  }

  const loadOperationData = async (conversationId: number, loadId: number) => {
    const [noteData, auditData, quickReplyData, agentData] = await Promise.all([
      listSupportNotes(conversationId),
      listSupportAudits(conversationId),
      listSupportQuickReplies(),
      listEnabledSupportAgents(),
    ])
    if (activeLoadIdRef.current === loadId) {
      setNotes(noteData || [])
      setAudits(auditData || [])
      setQuickReplies(quickReplyData || [])
      setSupportAgents(agentData || [])
    }
  }

  const loadSupportContext = async (conversationId: number, loadId: number) => {
    if (activeLoadIdRef.current === loadId) {
      setContextLoading(true)
    }
    try {
      const data = await getSupportConversationContext(conversationId)
      if (activeLoadIdRef.current === loadId) {
        setContext(data || null)
      }
    } catch {
      if (activeLoadIdRef.current === loadId) {
        setContext(null)
      }
    } finally {
      if (activeLoadIdRef.current === loadId) {
        setContextLoading(false)
      }
    }
  }

  const reloadActiveConversation = async (conversationId: number, loadId: number) => {
    await Promise.all([
      loadMessages(conversationId, loadId),
      loadOperationData(conversationId, loadId),
      loadSupportContext(conversationId, loadId),
    ])
  }

  useEffect(() => {
    getUserInfo()
      .then(info => {
        if (info.role !== 'support' && !canUseConsoleAction('support.conversation.view', info.permissionCodes || [])) {
          router.replace('/')
          return
        }
        return loadConversations()
      })
      .catch(() => router.replace('/login?ru=/support'))
      .finally(() => setChecking(false))
  }, [router])

  useLayoutEffect(() => {
    if (!active) {
      setMessages([])
      setNotes([])
      setAudits([])
      setContext(null)
      setSelectedTags([])
      setDetailLoading(false)
      setContextLoading(false)
      return
    }
    const loadId = activeLoadIdRef.current + 1
    activeLoadIdRef.current = loadId
    setDetailLoading(true)
    setMessages([])
    setNotes([])
    setAudits([])
    setContext(null)
    setSelectedTags(active.tags || [])
    setTransferTarget('')
    setTransferReason('')
    setEscalationReason('')
    setCloseReason(active.closeRequestReason || '')
    reloadActiveConversation(active.id, loadId)
      .catch(() => {
        if (activeLoadIdRef.current === loadId) {
          setMessages([])
          setNotes([])
          setAudits([])
        }
      })
      .finally(() => {
        if (activeLoadIdRef.current === loadId) {
          setDetailLoading(false)
        }
      })
  }, [active?.id])

  // SSE 推送优先：会话/消息事件到达时定向刷新；连续重连失败后降级为 3s 轮询。
  useEffect(() => {
    if (checking || polling) return

    const dispose = openSupportStream('agent', {
      onEvent: event => {
        void loadConversations().catch(() => undefined)
        const current = activeRef.current
        if (current && event.conversationId === current.id) {
          const loadId = activeLoadIdRef.current
          void loadMessages(current.id, loadId).catch(() => undefined)
        }
      },
      onFallback: () => setPolling(true),
    })
    return dispose
  }, [checking, polling])

  useEffect(() => {
    if (checking || !polling) return

    let cancelled = false
    const poll = async () => {
      try {
        await loadConversations()
        if (!cancelled && active && shouldPollSupportConversation(active.status)) {
          const loadId = activeLoadIdRef.current
          await loadMessages(active.id, loadId)
        }
      } catch {
        // 工作台保持在线轮询，短暂失败不打断客服处理。
      }
    }

    poll()
    const timer = window.setInterval(poll, 3000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [checking, polling, active?.id, active?.status])

  useEffect(() => {
    if (visibleConversations.length === 0) {
      setActive(null)
      return
    }
    setActive(current => current && visibleConversations.some(item => item.id === current.id) ? current : visibleConversations[0])
  }, [conversationFilter, conversations])

  const refreshAfterOperation = async (updated?: SupportConversationVO) => {
    if (updated) setActive(updated)
    await loadConversations()
    const targetId = updated?.id || active?.id
    if (targetId) {
      const loadId = activeLoadIdRef.current
      await reloadActiveConversation(targetId, loadId)
    }
  }

  const canProceedWithActiveWrite = (allowed: boolean) => {
    if (allowed) return true
    setError(formatSupportConversationWriteBlockedMessage(active?.status) || '当前会话暂不能执行该操作，请刷新后再操作')
    return false
  }

  const claim = async () => {
    if (!active) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canClaimSupportConversation(active.status))) return
    try {
      const updated = await claimSupportConversation(active.id)
      await refreshAfterOperation(updated)
      setStatus('已接入该会话')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '接入失败')
    }
  }

  const send = async () => {
    if (!active || !text.trim()) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canReplySupportConversation(active.status))) return
    try {
      await sendSupportMessage(active.id, text.trim())
      setText('')
      await refreshAfterOperation()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '发送失败')
    }
  }

  const addNote = async () => {
    if (!active || !noteText.trim()) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canEditSupportConversation(active.status))) return
    try {
      await addSupportNote(active.id, noteText)
      setNoteText('')
      await refreshAfterOperation()
      setStatus('已添加内部备注')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '添加备注失败')
    }
  }

  const toggleTag = (tag: string) => {
    setSelectedTags(current => current.includes(tag) ? current.filter(item => item !== tag) : [...current, tag])
  }

  const saveTags = async () => {
    if (!active) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canEditSupportConversation(active.status))) return
    try {
      const updated = await updateSupportTags(active.id, selectedTags)
      await refreshAfterOperation(updated)
      setStatus('已保存用户标签')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '保存标签失败')
    }
  }

  const transfer = async () => {
    if (!active || !transferTarget) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canEditSupportConversation(active.status))) return
    try {
      const updated = await transferSupportConversation(active.id, Number(transferTarget), transferReason)
      setTransferTarget('')
      setTransferReason('')
      await refreshAfterOperation(updated)
      setStatus('已转接会话')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '转接失败')
    }
  }

  const escalate = async () => {
    if (!active) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canEditSupportConversation(active.status))) return
    try {
      const updated = await escalateSupportConversation(active.id, escalationReason)
      setEscalationReason('')
      await refreshAfterOperation(updated)
      setStatus('已升级给管理员')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '升级失败')
    }
  }

  const close = async () => {
    if (!active) return
    setStatus('')
    setError('')
    if (!canProceedWithActiveWrite(canRequestSupportClose(active.status))) return
    try {
      const updated = await closeSupportConversation(active.id, closeReason)
      await refreshAfterOperation(updated)
      setStatus('已向用户发送结束确认')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '结束会话失败')
    }
  }

  if (checking) {
    return <div className="flex min-h-screen items-center justify-center bg-gray-50 text-[14px] text-gray-500">正在进入客服工作台...</div>
  }

  return (
    <div className="flex min-h-screen flex-col bg-[#f6f7fb]">
      <header className="flex h-16 items-center justify-between border-b border-gray-200 bg-white px-6">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#fff0f5] text-[#ff1268]">
            <Headphones className="h-5 w-5" />
          </div>
          <div>
            <div className="text-[18px] font-bold text-[#111]">客服工作台</div>
            <div className="text-[12px] text-gray-500">普通客服处理在线咨询；具备后台权限的账号可进入对应管理后台</div>
          </div>
        </div>
        <button onClick={logout} className="inline-flex items-center gap-2 rounded-lg border border-gray-200 px-3 py-2 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]">
          <LogOut className="h-4 w-4" />
          退出登录
        </button>
      </header>

      <main className="grid min-h-0 flex-1 grid-cols-[320px_minmax(0,1fr)_340px]">
        <aside className="min-h-0 border-r border-gray-200 bg-white">
          <div className="border-b border-gray-100 px-4 py-3 text-[13px] font-medium text-gray-500">待处理会话</div>
          <div className="flex flex-wrap gap-2 border-b border-gray-100 px-4 py-3">
            {filterTabs.map(tab => (
              <button
                key={tab.value}
                type="button"
                onClick={() => setConversationFilter(tab.value)}
                className={`rounded-lg border px-3 py-1.5 text-[12px] ${
                  conversationFilter === tab.value
                    ? 'border-[#ff1268] bg-[#fff0f5] text-[#ff1268]'
                    : 'border-gray-200 bg-white text-gray-500 hover:border-[#ff1268] hover:text-[#ff1268]'
                }`}
              >
                {tab.label} {tab.count}
              </button>
            ))}
          </div>
          <div className="h-[calc(100vh-166px)] overflow-y-auto">
            {visibleConversations.length === 0 ? (
              <div className="px-5 py-10 text-center text-[13px] text-gray-400">暂无待处理会话</div>
            ) : visibleConversations.map(item => (
              <button
                key={item.id}
                onClick={() => setActive(item)}
                className={`block w-full border-b border-gray-100 px-4 py-4 text-left hover:bg-[#fff7fb] ${active?.id === item.id ? 'bg-[#fff7fb]' : 'bg-white'}`}
              >
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="truncate text-[14px] font-semibold text-[#111]">{getConversationUserDisplay(item)}</span>
                  <span className="shrink-0 rounded-full bg-gray-100 px-2 py-0.5 text-[11px] text-gray-500">{formatSupportConversationStatus(item.status)}</span>
                </div>
                <div className="truncate text-[12px] text-gray-500">{item.subject}</div>
                <div className="mt-2 flex flex-wrap gap-1">
                  {(item.tags || []).map(tag => (
                    <span key={tag} className="rounded-full bg-[#fff0f5] px-2 py-0.5 text-[11px] text-[#ff1268]">{formatSupportTagLabel(tag)}</span>
                  ))}
                  {item.escalatedToAdmin && <span className="rounded-full bg-orange-50 px-2 py-0.5 text-[11px] text-orange-600">已升级</span>}
                </div>
                <div className="line-clamp-2 text-[12px] leading-5 text-gray-500">{item.lastMessage || '暂无消息'}</div>
                <div className={`mt-2 text-[12px] ${item.slaOverdue ? 'text-red-500' : 'text-gray-400'}`}>
                  {formatSupportSlaText(item)}
                </div>
              </button>
            ))}
          </div>
        </aside>

        <section className="flex min-h-0 flex-col">
          {active ? (
            <>
              <div className="flex min-h-16 items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
                <div>
                  <div className="text-[16px] font-bold text-[#111]">{active.subject}</div>
                  <div className="mt-1 text-[12px] text-gray-500">用户：{getConversationUserDisplay(active)} · 用户编号：{active.userId} · {formatSupportConversationStatus(active.status)}</div>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {(active.tags || []).map(tag => (
                      <span key={tag} className="rounded-full bg-[#fff0f5] px-2 py-0.5 text-[11px] text-[#ff1268]">{formatSupportTagLabel(tag)}</span>
                    ))}
                    {active.escalatedToAdmin && <span className="rounded-full bg-orange-50 px-2 py-0.5 text-[11px] text-orange-600">已升级</span>}
                  </div>
                  <div className={`mt-1 text-[12px] ${active.slaOverdue ? 'text-red-500' : 'text-gray-500'}`}>{formatSupportSlaText(active)}</div>
                </div>
                <button onClick={claim} disabled={!canClaimActiveConversation} className="rounded-lg bg-[#ff1268] px-4 py-2 text-[13px] font-medium text-white disabled:opacity-50">接入</button>
              </div>

              <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
                {detailLoading ? (
                  <div className="flex h-full min-h-[200px] items-center justify-center text-[13px] text-gray-400">正在加载会话内容...</div>
                ) : (
                  <div className="space-y-4">
                    {messages.map(item => {
                      const mine = item.senderType === 'AGENT' || item.senderType === 'SYSTEM'
                      return (
                        <div key={item.id || `${item.senderType}-${item.content}`} className={`flex ${mine ? 'justify-end' : 'justify-start'}`}>
                          <div className={`max-w-[68%] rounded-2xl px-4 py-3 text-[13px] leading-6 ${mine ? 'bg-[#1a1a2e] text-white' : item.senderType === 'AI' ? 'bg-[#fff0f5] text-gray-700' : 'bg-white text-gray-700 shadow-sm'}`}>
                            <div className={`mb-1 text-[11px] ${mine ? 'text-white/70' : 'text-gray-400'}`}>{formatSupportMessageSender(item, 'agent')}</div>
                            {item.content}
                          </div>
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>

              <div className="border-t border-gray-200 bg-white p-4">
                {(status || error) && <div className={`mb-3 rounded-lg px-3 py-2 text-[12px] ${error ? 'bg-red-50 text-red-500' : 'bg-green-50 text-green-600'}`}>{error || status}</div>}
                <div className="flex gap-2">
                  <input
                    value={text}
                    onChange={event => setText(event.target.value)}
                    onKeyDown={event => { if (event.key === 'Enter') send() }}
                    disabled={!canReply}
                    placeholder={canReply ? '输入回复内容' : '请先接入该会话'}
                    className="h-11 min-w-0 flex-1 rounded-xl border border-gray-200 px-3 text-[14px] outline-none focus:border-[#ff1268] disabled:bg-gray-100"
                  />
                  <button onClick={send} disabled={!canReply || !text.trim()} className="flex h-11 w-11 items-center justify-center rounded-xl bg-[#ff1268] text-white disabled:opacity-50" title="发送">
                    <Send className="h-4 w-4" />
                  </button>
                </div>
              </div>
            </>
          ) : (
            <div className="flex flex-1 items-center justify-center text-[14px] text-gray-400">请选择左侧会话</div>
          )}
        </section>

        <aside className="min-h-0 overflow-y-auto border-l border-gray-200 bg-white">
          {active ? (
            <div className="divide-y divide-gray-100">
              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <ClipboardList className="h-4 w-4 text-[#ff1268]" />
                  用户上下文
                </div>
                {contextLoading ? (
                  <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">正在加载用户上下文...</div>
                ) : !context ? (
                  <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">暂无用户上下文</div>
                ) : (
                  <div className="space-y-3">
                    <div className="grid grid-cols-3 gap-2">
                      {contextCounts.map(([section, count]) => (
                        <div key={section} className="rounded-lg bg-gray-50 px-2 py-2 text-center text-[11px] text-gray-500">
                          {formatSupportContextSectionCount(section, count)}
                        </div>
                      ))}
                    </div>
                    {!hasSupportContextData(context) && context.errors.length === 0 && (
                      <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">暂无业务上下文</div>
                    )}
                    {context.orders.length > 0 && (
                      <div>
                        <div className="mb-2 text-[12px] font-medium text-gray-500">最近订单</div>
                        <div className="space-y-2">
                          {context.orders.slice(0, 3).map(order => (
                            <a key={order.id} href={order.href || `/orders/${order.id}`} className="block rounded-lg border border-gray-200 px-3 py-2 hover:border-[#ff1268] hover:bg-[#fff7fb]">
                              <div className="truncate text-[12px] font-medium text-[#111]">{order.orderNo || `订单编号：${order.id}`}</div>
                              <div className="mt-1 truncate text-[12px] text-gray-500">{order.activityName || '活动信息待同步'}{order.amount != null ? ` · ¥${order.amount}` : ''}</div>
                            </a>
                          ))}
                        </div>
                      </div>
                    )}
                    {context.refunds.length > 0 && (
                      <div>
                        <div className="mb-2 text-[12px] font-medium text-gray-500">退款</div>
                        <div className="space-y-2">
                          {context.refunds.slice(0, 2).map(refund => (
                            <a key={refund.id} href={refund.href || `/refunds/${refund.id}`} className="block rounded-lg border border-gray-200 px-3 py-2 hover:border-[#ff1268] hover:bg-[#fff7fb]">
                              <div className="truncate text-[12px] font-medium text-[#111]">{refund.orderNo || `退款编号：${refund.id}`}</div>
                              <div className="mt-1 truncate text-[12px] text-gray-500">{refund.reason || '退款原因未填写'}</div>
                            </a>
                          ))}
                        </div>
                      </div>
                    )}
                    {context.tickets.length > 0 && (
                      <div>
                        <div className="mb-2 text-[12px] font-medium text-gray-500">票夹</div>
                        <div className="space-y-2">
                          {context.tickets.slice(0, 2).map(ticket => (
                            <a key={ticket.ticketId} href={ticket.href || '/tickets'} className="block rounded-lg border border-gray-200 px-3 py-2 hover:border-[#ff1268] hover:bg-[#fff7fb]">
                              <div className="truncate text-[12px] font-medium text-[#111]">{ticket.activityName || `票券编号：${ticket.ticketId}`}</div>
                              <div className="mt-1 text-[12px] text-gray-500">{ticket.checkedIn ? '已核验' : '未核验'}</div>
                            </a>
                          ))}
                        </div>
                      </div>
                    )}
                    {(context.grabRequests.length > 0 || context.waitlist.length > 0 || context.notifications.length > 0) && (
                      <div className="space-y-2">
                        {context.grabRequests.slice(0, 2).map(request => (
                          <a key={request.requestId} href={request.href || `/grab/${request.requestId}`} className="block rounded-lg border border-gray-200 px-3 py-2 hover:border-[#ff1268] hover:bg-[#fff7fb]">
                            <div className="truncate text-[12px] font-medium text-[#111]">抢票请求号：{request.requestId}</div>
                            <div className="mt-1 truncate text-[12px] text-gray-500">{request.progressMessage || '状态待同步'}</div>
                          </a>
                        ))}
                        {context.waitlist.slice(0, 2).map(item => (
                          <div key={item.id} className="rounded-lg border border-gray-200 px-3 py-2">
                            <div className="truncate text-[12px] font-medium text-[#111]">候补编号：{item.id}</div>
                            <div className="mt-1 truncate text-[12px] text-gray-500">{item.estimatedWaitText || '等待释放票'}</div>
                          </div>
                        ))}
                        {context.notifications.slice(0, 2).map(item => (
                          <div key={item.id} className="rounded-lg border border-gray-200 px-3 py-2">
                            <div className="truncate text-[12px] font-medium text-[#111]">{item.title || `通知编号：${item.id}`}</div>
                            <div className="mt-1 line-clamp-2 text-[12px] leading-5 text-gray-500">{item.content || '通知内容为空'}</div>
                          </div>
                        ))}
                      </div>
                    )}
                    {context.errors.length > 0 && (
                      <div className="space-y-1">
                        {context.errors.map(error => (
                          <div key={`${error.section}-${error.message}`} className="rounded-lg bg-orange-50 px-3 py-2 text-[12px] leading-5 text-orange-700">{error.message}</div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </section>

              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <Tags className="h-4 w-4 text-[#ff1268]" />
                  用户标签
                </div>
                <div className="flex flex-wrap gap-2">
                  {tagOptions.map(option => {
                    const selected = selectedTags.includes(option.value)
                    return (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => toggleTag(option.value)}
                        disabled={!canEditActiveConversation}
                        className={`rounded-full border px-3 py-1.5 text-[12px] ${
                          selected
                            ? 'border-[#ff1268] bg-[#fff0f5] text-[#ff1268]'
                            : 'border-gray-200 bg-white text-gray-500 hover:border-[#ff1268] hover:text-[#ff1268]'
                        } disabled:cursor-not-allowed disabled:opacity-50`}
                      >
                        {option.label}
                      </button>
                    )
                  })}
                </div>
                <button type="button" onClick={saveTags} disabled={!canEditActiveConversation} className="mt-3 inline-flex h-9 items-center gap-2 rounded-lg bg-[#ff1268] px-3 text-[13px] font-medium text-white disabled:opacity-50">
                  <Tags className="h-4 w-4" />
                  保存标签
                </button>
              </section>

              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <FileText className="h-4 w-4 text-[#ff1268]" />
                  内部备注
                </div>
                <textarea value={noteText} onChange={event => setNoteText(event.target.value)} disabled={!canEditActiveConversation} placeholder="输入内部备注，用户不可见" className="h-20 w-full resize-none rounded-lg border border-gray-200 px-3 py-2 text-[13px] leading-5 outline-none focus:border-[#ff1268] disabled:bg-gray-100" />
                <button type="button" onClick={addNote} disabled={!canEditActiveConversation || !noteText.trim()} className="mt-2 inline-flex h-9 items-center gap-2 rounded-lg border border-gray-200 px-3 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-50">
                  <FileText className="h-4 w-4" />
                  添加备注
                </button>
                <div className="mt-3 max-h-40 space-y-2 overflow-y-auto">
                  {detailLoading ? (
                    <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">正在加载备注...</div>
                  ) : notes.length === 0 ? (
                    <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">暂无内部备注</div>
                  ) : notes.map(note => (
                    <div key={note.id} className="rounded-lg bg-gray-50 px-3 py-2">
                      <div className="mb-1 flex items-center justify-between gap-2 text-[11px] text-gray-400">
                        <span className="truncate">{note.authorDisplayName || '客服'}</span>
                        <span className="shrink-0">{formatTime(note.createTime)}</span>
                      </div>
                      <div className="whitespace-pre-line text-[12px] leading-5 text-gray-600">{note.content}</div>
                    </div>
                  ))}
                </div>
              </section>

              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <BookOpen className="h-4 w-4 text-[#ff1268]" />
                  快捷话术
                </div>
                <div className="max-h-48 space-y-3 overflow-y-auto">
                  {quickReplyGroups.length === 0 ? (
                    <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">暂无快捷话术</div>
                  ) : quickReplyGroups.map(([category, items]) => (
                    <div key={category}>
                      <div className="mb-2 text-[12px] font-medium text-gray-500">{category}</div>
                      <div className="space-y-2">
                        {items.map(item => (
                          <button key={item.id} type="button" onClick={() => setText(current => appendQuickReply(current, item.content))} disabled={!canReply} className="block w-full rounded-lg border border-gray-200 px-3 py-2 text-left hover:border-[#ff1268] hover:bg-[#fff7fb] disabled:cursor-not-allowed disabled:opacity-50">
                            <div className="text-[12px] font-medium text-[#111]">{item.title}</div>
                            <div className="mt-1 line-clamp-2 text-[12px] leading-5 text-gray-500">{item.content}</div>
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </section>

              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <ArrowRightLeft className="h-4 w-4 text-[#ff1268]" />
                  转接与升级
                </div>
                <select value={transferTarget} onChange={event => setTransferTarget(event.target.value)} disabled={!canEditActiveConversation} className="h-10 w-full rounded-lg border border-gray-200 px-3 text-[13px] outline-none focus:border-[#ff1268] disabled:bg-gray-100">
                  <option value="">选择转接客服</option>
                  {transferTargets.map(agent => (
                    <option key={agent.id} value={agent.id}>{agent.nickname || agent.phone}</option>
                  ))}
                </select>
                <input value={transferReason} onChange={event => setTransferReason(event.target.value)} disabled={!canEditActiveConversation} placeholder="转接原因（选填）" className="mt-2 h-10 w-full rounded-lg border border-gray-200 px-3 text-[13px] outline-none focus:border-[#ff1268] disabled:bg-gray-100" />
                <button type="button" onClick={transfer} disabled={!canEditActiveConversation || !transferTarget} className="mt-2 inline-flex h-9 items-center gap-2 rounded-lg border border-gray-200 px-3 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-50">
                  <ArrowRightLeft className="h-4 w-4" />
                  转接客服
                </button>

                <input value={escalationReason} onChange={event => setEscalationReason(event.target.value)} disabled={!canEditActiveConversation} placeholder="升级原因（选填）" className="mt-4 h-10 w-full rounded-lg border border-gray-200 px-3 text-[13px] outline-none focus:border-[#ff1268] disabled:bg-gray-100" />
                <button type="button" onClick={escalate} disabled={!canEditActiveConversation} className="mt-2 inline-flex h-9 items-center gap-2 rounded-lg border border-orange-200 px-3 text-[13px] text-orange-600 hover:border-orange-300 hover:bg-orange-50 disabled:cursor-not-allowed disabled:opacity-50">
                  <ShieldAlert className="h-4 w-4" />
                  升级管理员
                </button>
                {active.escalatedToAdmin && (
                  <div className="mt-2 rounded-lg bg-orange-50 px-3 py-2 text-[12px] leading-5 text-orange-700">
                    已升级给管理员{active.escalationReason ? `：${active.escalationReason}` : ''}
                  </div>
                )}
              </section>

              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <ClipboardList className="h-4 w-4 text-[#ff1268]" />
                  结束申请
                </div>
                <textarea value={closeReason} onChange={event => setCloseReason(event.target.value)} disabled={!canEditActiveConversation} placeholder="结束原因（选填）" className="h-20 w-full resize-none rounded-lg border border-gray-200 px-3 py-2 text-[13px] leading-5 outline-none focus:border-[#ff1268] disabled:bg-gray-100" />
                <div className="mt-2 rounded-lg bg-gray-50 px-3 py-2 text-[12px] leading-5 text-gray-500">
                  {buildCloseRequestMessage(closeReason)}
                </div>
                {active.status === 'CLOSE_REQUESTED' && active.closeRequestReason && (
                  <div className="mt-2 rounded-lg bg-[#fff0f5] px-3 py-2 text-[12px] leading-5 text-[#ff1268]">
                    当前结束原因：{active.closeRequestReason}
                  </div>
                )}
                <button type="button" onClick={close} disabled={!canCloseActiveConversation} className="mt-2 inline-flex h-9 items-center gap-2 rounded-lg bg-[#1a1a2e] px-3 text-[13px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-50">
                  <ClipboardList className="h-4 w-4" />
                  申请结束
                </button>
              </section>

              <section className="p-4">
                <div className="mb-3 flex items-center gap-2 text-[14px] font-semibold text-[#111]">
                  <ClipboardList className="h-4 w-4 text-[#ff1268]" />
                  审计记录
                </div>
                <div className="max-h-52 space-y-2 overflow-y-auto">
                  {detailLoading ? (
                    <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">正在加载审计记录...</div>
                  ) : audits.length === 0 ? (
                    <div className="rounded-lg bg-gray-50 px-3 py-3 text-[12px] text-gray-400">暂无审计记录</div>
                  ) : audits.map(audit => (
                    <div key={audit.id} className="rounded-lg bg-gray-50 px-3 py-2">
                      <div className="mb-1 flex items-center justify-between gap-2">
                        <span className="text-[12px] font-medium text-[#111]">{formatSupportAuditAction(audit.action)}</span>
                        <span className="text-[11px] text-gray-400">{formatTime(audit.createTime)}</span>
                      </div>
                      <div className="text-[11px] text-gray-400">{audit.actorDisplayName || '系统'}</div>
                      {audit.detail && <div className="mt-1 whitespace-pre-line text-[12px] leading-5 text-gray-600">{audit.detail}</div>}
                    </div>
                  ))}
                </div>
              </section>
            </div>
          ) : (
            <div className="flex h-full items-center justify-center px-6 text-center text-[13px] leading-6 text-gray-400">
              选择会话后可查看备注、标签、话术和审计记录
            </div>
          )}
        </aside>
      </main>
    </div>
  )
}
