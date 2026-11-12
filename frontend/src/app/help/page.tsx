'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Bot, Headphones, Send, UserRound } from 'lucide-react'
import { Header } from '@/components/Header'
import { Footer } from '@/components/Footer'
import {
  clearSupportHelpPresence,
  confirmCloseSupportConversation,
  handoffSupportConversation,
  listHelpFaqs,
  listMySupportConversations,
  listSupportMessages,
  markSupportHelpPresence,
  sendSupportMessageStream,
  startSupportConversation,
} from '@/lib/api'
import { getToken, getUser } from '@/lib/auth'
import { openSupportStream } from '@/lib/support-stream'
import { buildSupportSubject, canConfirmSupportConversationClose, canEditSupportConversation, canRequestSupportHandoff, formatSupportConversationStatus, formatSupportConversationWriteBlockedMessage, formatSupportHandoffActionLabel, formatSupportMessageSender, isSupportHelpConversationPath, pickDefaultUserSupportConversation, shouldPollSupportConversation } from '@/lib/support-tools'
import type { HelpFaqVO, SupportConversationVO, SupportMessageVO } from '@/types/api'

export default function HelpPage() {
  const pathname = usePathname()
  const inSupportWindow = isSupportHelpConversationPath(pathname)
  const preferredConversationId = useMemo(() => {
    if (typeof window === 'undefined') return null
    const raw = new URLSearchParams(window.location.search).get('conversationId')
    const id = raw ? Number(raw) : 0
    return Number.isSafeInteger(id) && id > 0 ? id : null
  }, [pathname])
  const [faqs, setFaqs] = useState<HelpFaqVO[]>([])
  const [conversation, setConversation] = useState<SupportConversationVO | null>(null)
  const [messages, setMessages] = useState<SupportMessageVO[]>([])
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(false)
  const [aiThinking, setAiThinking] = useState(false)
  const [message, setMessage] = useState('')
  const [loggedIn, setLoggedIn] = useState(false)
  const [currentUserId, setCurrentUserId] = useState(0)
  const messagesEndRef = useRef<HTMLDivElement | null>(null)
  const streamingRef = useRef(false)
  const streamingAiMessageIdRef = useRef(-1)
  const conversationRef = useRef<SupportConversationVO | null>(null)
  conversationRef.current = conversation
  const [polling, setPolling] = useState(false)

  useEffect(() => {
    const hasToken = Boolean(getToken())
    setLoggedIn(hasToken)
    const user = getUser()
    const uid = Number(user?.userId || 0)
    setCurrentUserId(uid)
    listHelpFaqs()
      .then(setFaqs)
      .catch(() => setFaqs([]))
    if (hasToken) {
      loadMyConversation(preferredConversationId, uid).catch(() => undefined)
    }
  }, [preferredConversationId])

  const groupedFaqs = useMemo(() => {
    const map = new Map<string, HelpFaqVO[]>()
    for (const item of faqs) {
      map.set(item.category, [...(map.get(item.category) || []), item])
    }
    return Array.from(map.entries())
  }, [faqs])
  const conversationWriteBlockedMessage = conversation ? formatSupportConversationWriteBlockedMessage(conversation.status) : ''
  const canSendSupportMessage = !conversation || canEditSupportConversation(conversation.status)
  const canConfirmCloseConversation = Boolean(conversation && canConfirmSupportConversationClose(conversation.status))
  const supportInputPlaceholder = conversationWriteBlockedMessage
    || (conversation?.status === 'CLOSE_REQUESTED' ? '如需继续咨询，请直接输入问题' : '输入订单、票夹、退款等问题')

  const refreshMessages = async (id: number) => {
    const data = await listSupportMessages(id)
    setMessages(data)
    if (data.length > 0) {
      setMessage(current => {
        if (current.includes('服务暂不可用') || current.includes('服务响应超时') || current.includes('消息发送失败')) {
          return ''
        }
        return current
      })
    }
    return data
  }

  async function loadMyConversation(preferredId?: number | null, ownerUserId = currentUserId) {
    const conversations = await listMySupportConversations()
    const next = pickDefaultUserSupportConversation(conversations, ownerUserId, preferredId)
    setConversation(next)
    if (next) {
      await refreshMessages(next.id)
    } else {
      setMessages([])
    }
    return next
  }

  // SSE 推送优先：客服/AI 消息事件到达时定向刷新；连续重连失败后降级为 3s 轮询。
  useEffect(() => {
    if (!loggedIn || !inSupportWindow || polling) return

    const dispose = openSupportStream('user', {
      onEvent: event => {
        if (streamingRef.current) return
        const current = conversationRef.current
        if (event.conversationId && current && event.conversationId !== current.id) return
        loadMyConversation(current?.id ?? null).catch(() => undefined)
      },
      onFallback: () => setPolling(true),
    })
    return dispose
  }, [loggedIn, inSupportWindow, polling])

  useEffect(() => {
    if (!inSupportWindow || !conversation || !polling || !shouldPollSupportConversation(conversation.status)) return

    let cancelled = false
    const poll = async () => {
      if (streamingRef.current) return
      try {
        const next = await loadMyConversation(conversation.id)
        if (cancelled || !next) return
      } catch {
        // 轮询失败时保留当前会话，下一轮继续尝试。
      }
    }

    const timer = window.setInterval(poll, 3000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [conversation?.id, conversation?.status, inSupportWindow, polling])

  useEffect(() => {
    if (!loggedIn) return

    const markVisible = () => {
      if (document.visibilityState === 'visible') {
        markSupportHelpPresence().catch(() => undefined)
      }
    }
    const clearPresence = () => {
      clearSupportHelpPresence({ keepalive: true }).catch(() => undefined)
    }
    const handleVisibilityChange = () => {
      if (inSupportWindow && document.visibilityState === 'visible') {
        markSupportHelpPresence().catch(() => undefined)
      } else {
        clearPresence()
      }
    }
    const handlePageHide = () => {
      clearPresence()
    }

    if (inSupportWindow) {
      markVisible()
    } else {
      clearPresence()
    }
    const timer = window.setInterval(() => {
      if (inSupportWindow) {
        markVisible()
      } else {
        clearPresence()
      }
    }, 20_000)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('pagehide', handlePageHide)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('pagehide', handlePageHide)
      clearPresence()
    }
  }, [loggedIn, inSupportWindow])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ block: 'end' })
  }, [messages, conversation?.id, aiThinking])

  const hasSubmittedUserMessage = (items: SupportMessageVO[], content: string) => {
    return items.some(item => item.senderType === 'USER' && item.content.trim() === content)
  }

  const appendStreamMessage = (message: SupportMessageVO) => {
    setMessages(current => current.some(item => item.id === message.id) ? current : [...current, message])
  }

  const ensureStreamingAiMessage = (conversationId: number, messageId: number) => {
    setMessages(current => {
      if (current.some(item => item.id === messageId)) return current
      return [
        ...current,
        {
          id: messageId,
          conversationId,
          senderUserId: null,
          senderType: 'AI',
          senderDisplayName: 'AI 客服',
          content: '',
          createTime: new Date().toISOString(),
        },
      ]
    })
  }

  const appendStreamingAiDelta = (conversationId: number, messageId: number, delta: string) => {
    setMessages(current => {
      const exists = current.some(item => item.id === messageId)
      const next = exists ? current : [
        ...current,
        {
          id: messageId,
          conversationId,
          senderUserId: null,
          senderType: 'AI',
          senderDisplayName: 'AI 客服',
          content: '',
          createTime: new Date().toISOString(),
        },
      ]
      return next.map(item => item.id === messageId ? { ...item, content: `${item.content}${delta}` } : item)
    })
  }

  const finishStreamingAiMessage = (messageId: number, assistantMessage?: SupportMessageVO | null) => {
    setMessages(current => {
      if (assistantMessage) {
        return current.map(item => item.id === messageId ? assistantMessage : item)
      }
      return current.filter(item => item.id !== messageId || item.content.trim())
    })
  }

  const send = async () => {
    const content = text.trim()
    if (!content || loading) return
    if (!loggedIn) {
      setMessage('请先登录后再使用在线客服')
      return
    }
    if (conversation && !canEditSupportConversation(conversation.status)) {
      setMessage(formatSupportConversationWriteBlockedMessage(conversation.status) || '当前会话已结束，不能继续发送消息')
      return
    }
    setLoading(true)
    setAiThinking(false)
    setMessage('')
    let current = conversation
    let submitted = false
    let aiDraftId = streamingAiMessageIdRef.current
    try {
      if (!current) {
        current = await startSupportConversation({
          subject: buildSupportSubject(content),
          initialMessage: null,
        })
        setConversation(current)
      }
      const conversationId = current.id
      aiDraftId = streamingAiMessageIdRef.current - 1
      streamingAiMessageIdRef.current = aiDraftId
      streamingRef.current = true
      await sendSupportMessageStream(conversationId, content, {
        onUserMessage: userMessage => {
          submitted = true
          setText('')
          appendStreamMessage(userMessage)
        },
        onThinking: () => {
          setAiThinking(true)
          ensureStreamingAiMessage(conversationId, aiDraftId)
        },
        onDelta: delta => {
          setAiThinking(false)
          appendStreamingAiDelta(conversationId, aiDraftId, delta)
        },
        onDone: assistantMessage => {
          setAiThinking(false)
          finishStreamingAiMessage(aiDraftId, assistantMessage)
        },
      })
      await loadMyConversation(conversationId)
    } catch (err: unknown) {
      try {
        const recovered = await loadMyConversation(current?.id ?? null)
        if (recovered) {
          const latestMessages = await refreshMessages(recovered.id)
          if (hasSubmittedUserMessage(latestMessages, content)) {
            setText('')
            setMessage('')
            return
          }
        }
      } catch {
        // 保留原始发送错误，避免二次同步失败覆盖用户可见提示。
      }
      if (!submitted) {
        setText(content)
      }
      finishStreamingAiMessage(aiDraftId, null)
      setMessage(err instanceof Error ? err.message : '消息发送失败')
    } finally {
      streamingRef.current = false
      setAiThinking(false)
      setLoading(false)
    }
  }

  const confirmClose = async () => {
    if (!conversation || loading) return
    if (!canConfirmSupportConversationClose(conversation.status)) {
      setMessage(formatSupportConversationWriteBlockedMessage(conversation.status) || '当前会话暂不能结束，请刷新后再操作')
      return
    }
    setLoading(true)
    setMessage('')
    try {
      const updated = await confirmCloseSupportConversation(conversation.id)
      setConversation(updated)
      await refreshMessages(updated.id)
    } catch (err: unknown) {
      setMessage(err instanceof Error ? err.message : '结束会话失败')
    } finally {
      setLoading(false)
    }
  }

  const handoff = async () => {
    if (loading) return
    if (!conversation) return
    if (!canRequestSupportHandoff(conversation)) {
      setMessage(formatSupportConversationWriteBlockedMessage(conversation.status) || '当前会话暂不能转人工，请刷新后再操作')
      return
    }
    setLoading(true)
    setMessage('')
    try {
      const updated = await handoffSupportConversation(conversation.id)
      setConversation(updated)
      await refreshMessages(updated.id)
    } catch (err: unknown) {
      setMessage(err instanceof Error ? err.message : '转人工失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-gray-50 pb-16 md:pb-0">
      <Header />
      <main className="mx-auto grid w-full max-w-[1200px] flex-1 gap-6 px-5 py-8 lg:grid-cols-[1fr_420px]">
        <section className="min-w-0">
          <div className="mb-6">
            <h1 className="text-[26px] font-bold text-[#111]">帮助中心</h1>
            <p className="mt-2 text-[14px] leading-6 text-gray-500">常见问题、AI 客服和人工客服在同一个入口处理，客服对话会保留记录。</p>
          </div>

          <div className="space-y-5">
            {groupedFaqs.map(([category, items]) => (
              <section key={category} className="rounded-2xl border border-gray-100 bg-white p-5 shadow-[0_8px_30px_rgb(0,0,0,0.03)]">
                <h2 className="mb-4 text-[17px] font-bold text-[#111]">{category}</h2>
                <div className="divide-y divide-gray-100">
                  {items.map(item => (
                    <details key={item.question} className="group py-4 first:pt-0 last:pb-0">
                      <summary className="cursor-pointer list-none text-[14px] font-medium text-gray-800 group-open:text-[#ff1268]">
                        {item.question}
                      </summary>
                      <p className="mt-3 text-[13px] leading-6 text-gray-500">{item.answer}</p>
                    </details>
                  ))}
                </div>
              </section>
            ))}
          </div>
        </section>

        <aside className="min-w-0 rounded-2xl border border-gray-100 bg-white p-5 shadow-[0_8px_30px_rgb(0,0,0,0.04)] lg:sticky lg:top-[96px] lg:self-start">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <h2 className="text-[18px] font-bold text-[#111]">在线客服</h2>
              <p className="mt-1 text-[12px] text-gray-500">
                {conversation ? formatSupportConversationStatus(conversation.status) : 'AI 优先回答'}
              </p>
            </div>
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-[#fff0f5] text-[#ff1268]">
              <Headphones className="h-5 w-5" />
            </div>
          </div>

          {!loggedIn ? (
            <div className="rounded-xl bg-gray-50 p-4 text-[13px] leading-6 text-gray-600">
              登录后可以发起在线客服会话并保留历史记录。
              <Link href="/login?ru=/help" className="ml-2 font-medium text-[#ff1268]">去登录</Link>
            </div>
          ) : (
            <>
              <div className="mb-4 h-[360px] overflow-y-auto rounded-xl bg-gray-50 p-3">
                {messages.length === 0 ? (
                  <div className="flex h-full flex-col items-center justify-center text-center text-[13px] leading-6 text-gray-400">
                    <Bot className="mb-3 h-8 w-8 text-[#ff1268]" />
                    输入问题后，AI 客服会先根据项目规则回答。
                  </div>
                ) : (
                  <div className="space-y-3">
                    {messages.map(item => {
                      const mine = item.senderType === 'USER'
                      const streamingAi = item.id === streamingAiMessageIdRef.current && item.senderType === 'AI' && loading
                      return (
                        <div key={item.id || `${item.senderType}-${item.content}`} className={`flex ${mine ? 'justify-end' : 'justify-start'}`}>
                          <div className={`max-w-[86%] rounded-2xl px-4 py-3 text-[13px] leading-6 ${mine ? 'bg-[#ff1268] text-white' : 'bg-white text-gray-700 shadow-sm'}`}>
                            <div className={`mb-1 flex items-center gap-1 text-[11px] ${mine ? 'text-white/80' : 'text-gray-400'}`}>
                              {mine ? <UserRound className="h-3 w-3" /> : <Bot className="h-3 w-3" />}
                              {formatSupportMessageSender(item, 'customer')}
                            </div>
                            {item.content ? (
                              <>
                                {item.content}
                                {streamingAi && <span className="ml-1 inline-block h-3 w-1 animate-pulse rounded-full bg-[#ff1268] align-[-2px]" />}
                              </>
                            ) : streamingAi || aiThinking ? (
                              <span className="inline-flex items-center gap-1 text-gray-400">
                                客服正在思考
                                <span className="inline-flex gap-0.5">
                                  <span className="h-1 w-1 animate-bounce rounded-full bg-gray-400 [animation-delay:-0.2s]" />
                                  <span className="h-1 w-1 animate-bounce rounded-full bg-gray-400 [animation-delay:-0.1s]" />
                                  <span className="h-1 w-1 animate-bounce rounded-full bg-gray-400" />
                                </span>
                              </span>
                            ) : null}
                          </div>
                        </div>
                      )
                    })}
                    <div ref={messagesEndRef} />
                  </div>
                )}
              </div>

              {message && <div className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-[12px] text-red-500">{message}</div>}

              <div className="flex gap-2">
                <input
                  value={text}
                  onChange={event => setText(event.target.value)}
                  onKeyDown={event => { if (event.key === 'Enter') send() }}
                  disabled={!canSendSupportMessage}
                  placeholder={supportInputPlaceholder}
                  className="h-11 min-w-0 flex-1 rounded-xl border border-gray-200 px-3 text-[13px] outline-none focus:border-[#ff1268]"
                />
                <button
                  type="button"
                  onClick={send}
                  disabled={loading || !canSendSupportMessage}
                  className="flex h-11 w-11 items-center justify-center rounded-xl bg-[#ff1268] text-white disabled:opacity-60"
                  title="发送"
                >
                  <Send className="h-4 w-4" />
                </button>
              </div>
              <button
                type="button"
                onClick={handoff}
                disabled={loading || !canRequestSupportHandoff(conversation)}
                className="mt-3 w-full rounded-xl border border-gray-200 py-2.5 text-[13px] font-medium text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {formatSupportHandoffActionLabel(conversation?.status)}
              </button>
              {canConfirmCloseConversation && (
                <button
                  type="button"
                  onClick={confirmClose}
                  disabled={loading}
                  className="mt-2 w-full rounded-xl bg-[#1a1a2e] py-2.5 text-[13px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                >
                  确认结束会话
                </button>
              )}
            </>
          )}
        </aside>
      </main>
      <Footer />
    </div>
  )
}
