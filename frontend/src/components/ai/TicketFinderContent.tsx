'use client'

import { useEffect, useRef, useState } from 'react'
import { ArrowRight, Check, CircleAlert, LoaderCircle, Search, Sparkles } from 'lucide-react'
import { useRouter } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { interpretAiTicketFinder, searchAiTicketFinder } from '@/lib/api'
import {
  AI_TICKET_FINDER_QUICK_EXAMPLES,
  buildFinderPurchaseHref,
  formatFinderConditions,
  getFinderErrorMessage,
} from '@/lib/ai-ticket-finder'
import { isAuthenticated } from '@/lib/auth'
import type { FinderClarification, TicketFinderResult, TicketIntent } from '@/types/api'

const EXAMPLE_QUERY = '例如：帮我找广州的天鹅湖演出，预算700元以内'

type FinderPhase = 'input' | 'interpreting' | 'clarification' | 'searching' | 'results'

interface TicketFinderContentProps {
  onClose?: () => void
  showHeader?: boolean
}

function formatSessionTime(value: string | null | undefined) {
  return value ? value.replace('T', ' ').slice(0, 16) : '场次时间待同步'
}

function formatPrice(value: number | null | undefined) {
  return value == null ? '价格待同步' : `¥${Number(value).toFixed(2).replace(/\.00$/, '')}`
}

function formatSaleStatus(value: string | null | undefined) {
  if (value === 'on_sale') return '售票中'
  if (value === 'coming_soon') return '待开票'
  if (value === 'sold_out') return '已售罄'
  return '销售状态待确认'
}

function saleStatusClass(value: string | null | undefined) {
  if (value === 'on_sale') return 'bg-[#ecfdf3] text-[#16803c]'
  if (value === 'sold_out') return 'bg-[#fef2f2] text-[#c24141]'
  return 'bg-[#fff8e8] text-[#a16207]'
}

function ResultCard({ result, onPurchase }: { result: TicketFinderResult; onPurchase: (result: TicketFinderResult) => void }) {
  return (
    <article className="border-b border-[#f0f1f3] py-5 first:pt-0 last:border-b-0 last:pb-0">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0">
          <h3 className="truncate text-[16px] font-semibold text-[#17191d]">{result.activityName}</h3>
          <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-[#737780]">
            <span>{result.city || '城市待同步'}</span>
            <span className="text-[#d4d6da]">·</span>
            <span>{result.venueName || '场馆待同步'}</span>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-[13px] text-[#555a63]">
            <span>{formatSessionTime(result.sessionStartTime)}</span>
            <span>{result.ticketTypeName || '票档待同步'}</span>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <span className="text-[20px] font-bold text-[#ff1268]">{formatPrice(result.price)}</span>
            <span className={`rounded-full px-2.5 py-1 text-[12px] font-medium ${saleStatusClass(result.saleStatus)}`}>
              {formatSaleStatus(result.saleStatus)}
            </span>
            {result.availableQuantity != null && (
              <span className="text-[12px] text-[#737780]">可售 {result.availableQuantity}</span>
            )}
          </div>
          {result.explanation && (
            <p className="mt-3 text-[13px] leading-6 text-[#737780]">{result.explanation}</p>
          )}
        </div>
        <Button
          type="button"
          onClick={() => onPurchase(result)}
          className="h-10 w-full bg-[#ff1268] px-5 text-white hover:bg-[#e50f5d] lg:w-auto"
        >
          去购票
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </article>
  )
}

export function TicketFinderContent({ onClose, showHeader = true }: TicketFinderContentProps) {
  const router = useRouter()
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const requestInFlightRef = useRef(false)
  const [query, setQuery] = useState('')
  const [clarificationAnswer, setClarificationAnswer] = useState('')
  const [intent, setIntent] = useState<TicketIntent | null>(null)
  const [clarification, setClarification] = useState<FinderClarification | null>(null)
  const [results, setResults] = useState<TicketFinderResult[]>([])
  const [explanation, setExplanation] = useState('')
  const [error, setError] = useState('')
  const [phase, setPhase] = useState<FinderPhase>('input')
  const [hasSearched, setHasSearched] = useState(false)

  const busy = phase === 'interpreting' || phase === 'searching'

  const resetFinderState = (clearQuery = true) => {
    requestInFlightRef.current = false
    if (clearQuery) setQuery('')
    setClarificationAnswer('')
    setIntent(null)
    setClarification(null)
    setResults([])
    setExplanation('')
    setError('')
    setPhase('input')
    setHasSearched(false)
  }

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const submitFinderRequest = async (nextQuery: string) => {
    const normalized = nextQuery.trim()
    if (!normalized) {
      setError('请先告诉我你想看的演出或场次')
      inputRef.current?.focus()
      return
    }
    if (requestInFlightRef.current || busy) return
    if (!isAuthenticated()) {
      setError('请先登录后使用 AI 智能找票')
      return
    }

    requestInFlightRef.current = true
    setError('')
    setIntent(null)
    setClarification(null)
    setClarificationAnswer('')
    setResults([])
    setExplanation('')
    setHasSearched(false)
    setPhase('interpreting')

    try {
      const interpreted = await interpretAiTicketFinder(normalized)
      const nextClarification = interpreted.clarification?.required ? interpreted.clarification : null
      setIntent(interpreted.parsedIntent)
      setClarification(nextClarification)

      if (nextClarification) {
        setPhase('clarification')
        return
      }

      setPhase('searching')
      setHasSearched(true)
      const searched = await searchAiTicketFinder(normalized)
      setResults(searched.results || [])
      setExplanation(searched.explanation || '')
      if (searched.parsedIntent) setIntent(searched.parsedIntent)
      setPhase('results')
    } catch (requestError) {
      setResults([])
      setExplanation('')
      setError(getFinderErrorMessage(requestError))
      setPhase('input')
    } finally {
      requestInFlightRef.current = false
    }
  }

  const handleClarification = () => {
    const answer = clarificationAnswer.trim()
    if (!answer) {
      setError('请补充确认条件后再继续')
      return
    }
    const nextQuery = `${query.trim()}；补充条件：${answer}`
    setQuery(nextQuery)
    void submitFinderRequest(nextQuery)
  }

  const handleAdjust = () => {
    resetFinderState(false)
    inputRef.current?.focus()
  }

  const handlePurchase = (result: TicketFinderResult) => {
    onClose?.()
    router.push(buildFinderPurchaseHref(result))
  }

  const conditions = formatFinderConditions(intent)
  const showLoginAction = error === '请先登录后使用 AI 智能找票'

  return (
    <div className="text-[#17191d]">
      {showHeader && (
        <div className="mb-7">
          <div className="mb-4 inline-flex items-center gap-2 rounded-full bg-[#fff0f5] px-3 py-1.5 text-[12px] font-semibold text-[#e6005c]">
            <Sparkles className="h-3.5 w-3.5" />
            AI 智能找票
          </div>
          <h1 className="text-[30px] font-bold tracking-tight md:text-[38px]">告诉我你想看的演出，我帮你筛选真正可买的票</h1>
          <p className="mt-3 text-[14px] leading-6 text-[#737780]">描述演出、城市、时间、人数或预算，不需要填写复杂筛选项。</p>
        </div>
      )}

      <div>
        <label htmlFor="ticket-finder-query" className="sr-only">描述你的找票需求</label>
        <div className="rounded-2xl border border-[#e3e5e8] bg-[#fbfcfd] p-3 transition-colors focus-within:border-[#ff1268] focus-within:bg-white focus-within:ring-4 focus-within:ring-[#ff1268]/10">
          <textarea
            id="ticket-finder-query"
            ref={inputRef}
            value={query}
            onChange={event => setQuery(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                void submitFinderRequest(query)
              }
            }}
            rows={3}
            maxLength={500}
            disabled={busy}
            placeholder={EXAMPLE_QUERY}
            className="min-h-[84px] w-full resize-none bg-transparent px-2 py-1 text-[16px] leading-7 text-[#17191d] outline-none placeholder:text-[#a4a8b0] disabled:cursor-not-allowed disabled:opacity-70"
          />
          <div className="flex flex-col gap-3 border-t border-[#eef0f2] px-2 pt-3 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-[12px] text-[#a0a4ab]">按 Enter 提交，Shift + Enter 换行</span>
            <Button
              type="button"
              onClick={() => void submitFinderRequest(query)}
              disabled={busy}
              className="h-10 w-full bg-[#ff1268] px-5 text-white hover:bg-[#e50f5d] sm:w-auto"
            >
              {busy ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
              {phase === 'interpreting'
                ? '正在理解你的需求……'
                : phase === 'searching'
                  ? '正在为你查找可售票……'
                  : '开始找票'}
            </Button>
          </div>
        </div>
        <div className="mt-4">
          <div className="mb-2 text-[12px] font-medium text-[#8a8e97]">快捷示例</div>
          <div className="flex flex-wrap gap-2">
            {AI_TICKET_FINDER_QUICK_EXAMPLES.map(example => (
              <button
                key={example}
                type="button"
                onClick={() => setQuery(example)}
                disabled={busy}
                className="max-w-full truncate rounded-full border border-[#eceef1] bg-white px-3 py-1.5 text-left text-[12px] text-[#646872] transition-colors hover:border-[#ff1268] hover:bg-[#fff7fa] hover:text-[#e6005c] disabled:cursor-not-allowed disabled:opacity-60"
              >
                {example}
              </button>
            ))}
          </div>
        </div>
      </div>

      {error && (
        <div className="mt-5 flex flex-col gap-3 rounded-2xl border border-[#ffd8df] bg-[#fff7f8] px-4 py-3 text-[13px] text-[#c24155] sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-start gap-2">
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
          {showLoginAction && (
            <Button
              type="button"
              variant="outline"
              onClick={() => router.push('/login?ru=/')}
              className="h-9 border-[#ff1268] text-[#e6005c] hover:bg-[#fff0f5]"
            >
              去登录
            </Button>
          )}
        </div>
      )}

      {phase === 'clarification' && clarification?.required && (
        <section className="mt-6 rounded-2xl border border-[#f2e4b7] bg-[#fffdf5] p-5">
          <div className="flex items-center gap-2 text-[16px] font-semibold text-[#6b5312]">
            <CircleAlert className="h-4 w-4" />
            需要补充一点信息
          </div>
          <ul className="mt-3 space-y-2 text-[13px] leading-6 text-[#806d32]">
            {clarification.questions.map((question, index) => (
              <li key={`${question}-${index}`} className="flex gap-2">
                <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-[#d5a72b]" />
                <span>{question}</span>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex flex-col gap-3 sm:flex-row">
            <input
              value={clarificationAnswer}
              onChange={event => setClarificationAnswer(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault()
                  handleClarification()
                }
              }}
              placeholder="补充你的确认条件"
              className="h-10 min-w-0 flex-1 rounded-lg border border-[#eadfae] bg-white px-3 text-[13px] text-[#555] outline-none focus:border-[#d5a72b] focus:ring-2 focus:ring-[#d5a72b]/10"
            />
            <Button
              type="button"
              onClick={handleClarification}
              disabled={busy}
              className="h-10 bg-[#b78300] px-5 text-white hover:bg-[#986d00]"
            >
              {busy ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />}
              继续查找
            </Button>
          </div>
        </section>
      )}

      {phase === 'searching' && (
        <section className="mt-6 rounded-2xl border border-[#f0f1f3] bg-white px-5 py-10 text-center">
          <LoaderCircle className="mx-auto mb-3 h-7 w-7 animate-spin text-[#ff1268]" />
          <div className="text-[14px] font-medium text-[#373b42]">正在为你查找可售票……</div>
          <div className="mt-2 text-[12px] text-[#9a9ea6]">正在结合场次、票档和实时可售状态筛选</div>
        </section>
      )}

      {intent && phase === 'clarification' && conditions.length > 0 && (
        <div className="mt-5 flex flex-wrap gap-2">
          {conditions.map(condition => (
            <span key={condition.label} className="rounded-full bg-[#f7f8fa] px-3 py-1.5 text-[12px] text-[#646872]">
              {condition.label}：{condition.value}
            </span>
          ))}
        </div>
      )}

      {hasSearched && phase === 'results' && (
        <section className="mt-6 rounded-2xl border border-[#eceef1] bg-white px-5 py-6">
          <div className="flex flex-col gap-2 border-b border-[#f0f1f3] pb-4 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h2 className="text-[19px] font-semibold text-[#17191d]">
                {results.length > 0 ? `为你找到 ${results.length} 个可售选择` : '暂时没有找到符合条件的可售票'}
              </h2>
              {results.length === 0 && <p className="mt-1 text-[13px] text-[#9a9ea6]">可以尝试调整城市、日期或预算</p>}
            </div>
            <button
              type="button"
              onClick={handleAdjust}
              className="text-left text-[13px] font-medium text-[#e6005c] hover:text-[#ff1268] sm:text-right"
            >
              重新修改需求
            </button>
          </div>

          {results.length === 0 ? (
            <div className="py-10 text-center text-[13px] text-[#9a9ea6]">
              调整需求后，重新告诉我你想看的演出。
            </div>
          ) : (
            <>
              {explanation && (
                <div className="border-b border-[#f0f1f3] py-4 text-[13px] leading-6 text-[#555a63]">
                  {explanation}
                </div>
              )}
              <div className="pt-5">
                {results.map(result => (
                  <ResultCard
                    key={`${result.activityId}-${result.sessionId}-${result.ticketTypeId}`}
                    result={result}
                    onPurchase={handlePurchase}
                  />
                ))}
              </div>
            </>
          )}
        </section>
      )}
    </div>
  )
}

export function TicketFinderRouteContent() {
  return <TicketFinderContent showHeader />
}
