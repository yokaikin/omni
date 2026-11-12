'use client'

import { useCallback, useEffect, useState } from 'react'
import {
  checkSearchIndexConsistency,
  getSearchIndexHealth,
  listSearchIndexDeadLetters,
  rebuildSearchIndex,
  replaySearchIndexDeadLetter,
} from '@/lib/api'
import type {
  SearchIndexConsistencyVO,
  SearchIndexDeadLetterVO,
  SearchIndexHealthVO,
  SearchIndexRebuildResultVO,
} from '@/types/api'
import { Activity, ClipboardCheck, RefreshCw, RotateCcw, Zap } from 'lucide-react'

const DEAD_LETTER_STATUS_TABS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待重放' },
  { value: 'REPLAYED', label: '已重放' },
]

function formatTime(value?: string | null) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

export default function SearchOpsPage() {
  const [health, setHealth] = useState<SearchIndexHealthVO | null>(null)
  const [healthLoading, setHealthLoading] = useState(false)
  const [consistency, setConsistency] = useState<SearchIndexConsistencyVO | null>(null)
  const [consistencyLoading, setConsistencyLoading] = useState(false)
  const [consistencyError, setConsistencyError] = useState('')
  const [rebuildConfirming, setRebuildConfirming] = useState(false)
  const [rebuilding, setRebuilding] = useState(false)
  const [rebuildResult, setRebuildResult] = useState<SearchIndexRebuildResultVO | null>(null)
  const [rebuildMessage, setRebuildMessage] = useState('')
  const [deadLetters, setDeadLetters] = useState<SearchIndexDeadLetterVO[]>([])
  const [deadLetterStatus, setDeadLetterStatus] = useState('PENDING')
  const [deadLetterLoading, setDeadLetterLoading] = useState(false)
  const [replayingId, setReplayingId] = useState<number | null>(null)
  const [replayMessage, setReplayMessage] = useState('')

  const loadHealth = useCallback(async () => {
    setHealthLoading(true)
    try {
      const result = await getSearchIndexHealth()
      setHealth(result)
    } catch (err) {
      setHealth({ available: false, aliasName: '-', message: err instanceof Error ? err.message : '健康检查失败' })
    } finally {
      setHealthLoading(false)
    }
  }, [])

  const loadDeadLetters = useCallback(async (status: string) => {
    setDeadLetterLoading(true)
    try {
      const records = await listSearchIndexDeadLetters({ status: status || undefined, limit: 100 })
      setDeadLetters(records)
    } catch {
      setDeadLetters([])
    } finally {
      setDeadLetterLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadHealth()
    void loadDeadLetters('PENDING')
  }, [loadHealth, loadDeadLetters])

  const runConsistencyCheck = async () => {
    setConsistencyLoading(true)
    setConsistencyError('')
    setConsistency(null)
    try {
      const result = await checkSearchIndexConsistency()
      setConsistency(result)
    } catch (err) {
      setConsistencyError(err instanceof Error ? err.message : '一致性校验失败')
    } finally {
      setConsistencyLoading(false)
    }
  }

  const runRebuild = async () => {
    setRebuilding(true)
    setRebuildMessage('')
    setRebuildResult(null)
    try {
      const result = await rebuildSearchIndex()
      setRebuildResult(result)
      setRebuildMessage(`重建完成：已写入 ${result.indexedCount} 条文档到新索引 ${result.indexName}`)
      await loadHealth()
      setConsistency(null)
    } catch (err) {
      setRebuildMessage(err instanceof Error ? `重建失败：${err.message}` : '重建失败')
    } finally {
      setRebuilding(false)
      setRebuildConfirming(false)
    }
  }

  const replayDeadLetter = async (id: number) => {
    setReplayingId(id)
    setReplayMessage('')
    try {
      await replaySearchIndexDeadLetter(id)
      setReplayMessage(`死信记录 #${id} 已重放到搜索索引队列`)
      await loadDeadLetters(deadLetterStatus)
    } catch (err) {
      setReplayMessage(err instanceof Error ? `重放失败：${err.message}` : '重放失败')
    } finally {
      setReplayingId(null)
    }
  }

  const statusBadge = (status: string) => {
    if (status === 'PENDING') {
      return <span className="inline-flex items-center rounded px-1.5 py-0.5 text-[12px] font-medium bg-[#fff7e6] text-[#ad6800]">待重放</span>
    }
    if (status === 'REPLAYED') {
      return <span className="inline-flex items-center rounded px-1.5 py-0.5 text-[12px] font-medium bg-[#f0fdf4] text-[#16a34a]">已重放</span>
    }
    return <span className="inline-flex items-center rounded px-1.5 py-0.5 text-[12px] font-medium bg-gray-100 text-gray-600">{status}</span>
  }

  return (
    <div>
      <h1 className="mb-5 text-[22px] font-bold text-[#1a1a2e]">搜索索引运维</h1>

      {/* 健康检查 */}
      <div className="mb-4 rounded-xl border border-[#e5e5e5] bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-[#ff1268]" />
            <span className="text-[15px] font-semibold text-[#1a1a2e]">Elasticsearch 索引健康检查</span>
          </div>
          <button
            type="button"
            onClick={() => void loadHealth()}
            disabled={healthLoading}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-[#e5e5e5] bg-white px-3 text-[13px] font-medium text-[#333] transition hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <RefreshCw className={`h-4 w-4 ${healthLoading ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
        {health ? (
          <div className="mt-3 grid gap-3 md:grid-cols-4">
            <div className="rounded-lg border border-[#e5e5e5] p-3">
              <div className="text-[12px] text-[#999]">索引状态</div>
              <div className={`mt-1 text-[15px] font-semibold ${health.available ? 'text-[#16a34a]' : 'text-[#dc2626]'}`}>
                {health.available ? '可用' : '不可用'}
              </div>
              {health.message ? <div className="mt-1 text-[12px] text-[#dc2626]">{health.message}</div> : null}
            </div>
            <div className="rounded-lg border border-[#e5e5e5] p-3">
              <div className="text-[12px] text-[#999]">读写别名</div>
              <div className="mt-1 text-[15px] font-semibold text-[#1a1a2e]">{health.aliasName}</div>
            </div>
            <div className="rounded-lg border border-[#e5e5e5] p-3">
              <div className="text-[12px] text-[#999]">文档总数</div>
              <div className="mt-1 text-[15px] font-semibold text-[#1a1a2e]">{health.docCount ?? '-'}</div>
            </div>
            <div className="rounded-lg border border-[#e5e5e5] p-3">
              <div className="text-[12px] text-[#999]">物理索引</div>
              <div className="mt-1 text-[13px] text-[#1a1a2e] break-all">
                {health.indexNames && health.indexNames.length > 0 ? health.indexNames.join('、') : '-'}
              </div>
            </div>
          </div>
        ) : (
          <div className="mt-3 text-[13px] text-[#999]">正在获取索引健康状态...</div>
        )}
      </div>

      {/* 一致性校验 + 重建 */}
      <div className="mb-4 grid gap-3 lg:grid-cols-2">
        <div className="rounded-xl border border-[#e5e5e5] bg-white p-4">
          <div className="flex items-center gap-2">
            <ClipboardCheck className="h-4 w-4 text-[#ff1268]" />
            <span className="text-[15px] font-semibold text-[#1a1a2e]">数据库与索引一致性校验</span>
          </div>
          <div className="mt-1 text-[12px] text-[#999]">
            以在售活动收录口径为基准，比对索引文档，输出缺失（需补偿同步）与多余（需重建清理）清单。
          </div>
          <button
            type="button"
            onClick={() => void runConsistencyCheck()}
            disabled={consistencyLoading}
            className="mt-3 inline-flex h-10 items-center gap-2 rounded-lg bg-[#ff1268] px-4 text-[14px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-60"
          >
            {consistencyLoading ? '校验中...' : '立即校验'}
          </button>
          {consistencyError ? <div className="mt-3 text-[13px] text-[#dc2626]">{consistencyError}</div> : null}
          {consistency ? (
            <div className="mt-3 space-y-3">
              <div className={`inline-flex items-center rounded px-2 py-1 text-[13px] font-semibold ${consistency.consistent ? 'bg-[#f0fdf4] text-[#16a34a]' : 'bg-[#fff1f6] text-[#dc2626]'}`}>
                {consistency.consistent ? '数据一致' : '发现差异'}
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="rounded-lg border border-[#e5e5e5] p-3">
                  <div className="text-[12px] text-[#999]">数据库基准文档数</div>
                  <div className="mt-1 text-[18px] font-bold text-[#1a1a2e]">{consistency.dbCount}</div>
                </div>
                <div className="rounded-lg border border-[#e5e5e5] p-3">
                  <div className="text-[12px] text-[#999]">索引实际文档数</div>
                  <div className="mt-1 text-[18px] font-bold text-[#1a1a2e]">{consistency.indexCount}</div>
                </div>
              </div>
              <div>
                <div className="text-[13px] font-medium text-[#ad6800]">索引缺失（{consistency.missingInIndex.length}）</div>
                <div className="mt-1 max-h-[120px] overflow-y-auto rounded-lg bg-[#fffaf0] p-2 text-[12px] leading-5 text-[#666]">
                  {consistency.missingInIndex.length > 0 ? consistency.missingInIndex.join('、') : '无'}
                </div>
              </div>
              <div>
                <div className="text-[13px] font-medium text-[#dc2626]">索引多余（{consistency.staleInIndex.length}）</div>
                <div className="mt-1 max-h-[120px] overflow-y-auto rounded-lg bg-[#fff1f6] p-2 text-[12px] leading-5 text-[#666]">
                  {consistency.staleInIndex.length > 0 ? consistency.staleInIndex.join('、') : '无'}
                </div>
              </div>
              <div className="text-[12px] text-[#999]">校验时间：{formatTime(consistency.checkedAt)}</div>
            </div>
          ) : null}
        </div>

        <div className="rounded-xl border border-[#e5e5e5] bg-white p-4">
          <div className="flex items-center gap-2">
            <RotateCcw className="h-4 w-4 text-[#ff1268]" />
            <span className="text-[15px] font-semibold text-[#1a1a2e]">索引重建任务</span>
          </div>
          <div className="mt-1 text-[12px] text-[#999]">
            创建全新物理索引并全量写入在售活动，完成后自动切换读写别名，旧索引停止承接流量。
          </div>
          {rebuildConfirming ? (
            <div className="mt-3 rounded-lg border border-[#ffd591] bg-[#fffaf0] p-3">
              <div className="text-[13px] text-[#ad6800]">确认执行全量重建？重建期间搜索流量自动切换到新索引，不会中断服务。</div>
              <div className="mt-2 flex gap-2">
                <button
                  type="button"
                  onClick={() => void runRebuild()}
                  disabled={rebuilding}
                  className="inline-flex h-9 items-center rounded-lg bg-[#ff1268] px-3 text-[13px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {rebuilding ? '重建中...' : '确认重建'}
                </button>
                <button
                  type="button"
                  onClick={() => setRebuildConfirming(false)}
                  disabled={rebuilding}
                  className="inline-flex h-9 items-center rounded-lg border border-[#e5e5e5] bg-white px-3 text-[13px] font-medium text-[#333] disabled:cursor-not-allowed disabled:opacity-60"
                >
                  取消
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setRebuildConfirming(true)}
              disabled={rebuilding}
              className="mt-3 inline-flex h-10 items-center gap-2 rounded-lg border border-[#e5e5e5] bg-white px-4 text-[14px] font-medium text-[#333] transition hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-60"
            >
              <RotateCcw className={`h-4 w-4 ${rebuilding ? 'animate-spin' : ''}`} />
              {rebuilding ? '重建中...' : '全量重建索引'}
            </button>
          )}
          {rebuildMessage ? (
            <div className={`mt-3 text-[13px] ${rebuildResult ? 'text-[#16a34a]' : 'text-[#dc2626]'}`}>{rebuildMessage}</div>
          ) : null}
          {rebuildResult ? (
            <div className="mt-2 text-[12px] text-[#999]">
              别名 {rebuildResult.aliasName} → {rebuildResult.indexName}，开始 {formatTime(rebuildResult.startedAt)}，完成 {formatTime(rebuildResult.finishedAt)}
            </div>
          ) : null}
        </div>
      </div>

      {/* 死信重放 */}
      <div className="rounded-xl border border-[#e5e5e5] bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Zap className="h-4 w-4 text-[#ff1268]" />
            <span className="text-[15px] font-semibold text-[#1a1a2e]">索引同步失败消息与重放</span>
          </div>
          <div className="flex flex-wrap gap-2">
            {DEAD_LETTER_STATUS_TABS.map(tab => (
              <button
                key={tab.value || 'ALL'}
                type="button"
                onClick={() => {
                  setDeadLetterStatus(tab.value)
                  void loadDeadLetters(tab.value)
                }}
                className={`h-9 rounded-lg border px-3 text-[13px] transition ${
                  deadLetterStatus === tab.value
                    ? 'border-[#ff1268] bg-[#fff1f6] text-[#ff1268]'
                    : 'border-[#e5e5e5] bg-white text-[#666] hover:border-[#ff1268] hover:text-[#ff1268]'
                }`}
              >
                {tab.label}
              </button>
            ))}
            <button
              type="button"
              onClick={() => void loadDeadLetters(deadLetterStatus)}
              disabled={deadLetterLoading}
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-[#e5e5e5] bg-white px-3 text-[13px] font-medium text-[#333] transition hover:border-[#ff1268] hover:text-[#ff1268] disabled:cursor-not-allowed disabled:opacity-60"
            >
              <RefreshCw className={`h-4 w-4 ${deadLetterLoading ? 'animate-spin' : ''}`} />
              刷新
            </button>
          </div>
        </div>
        {replayMessage ? <div className="mt-3 text-[13px] text-[#16a34a]">{replayMessage}</div> : null}
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="border-b border-[#e5e5e5] text-left text-[#999]">
                <th className="py-2 pr-3 font-medium">记录ID</th>
                <th className="py-2 pr-3 font-medium">来源队列</th>
                <th className="py-2 pr-3 font-medium">失败原因</th>
                <th className="py-2 pr-3 font-medium">状态</th>
                <th className="py-2 pr-3 font-medium">重放次数</th>
                <th className="py-2 pr-3 font-medium">进入死信时间</th>
                <th className="py-2 pr-3 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {deadLetters.map(record => (
                <tr key={record.id} className="border-b border-[#f5f5f5]">
                  <td className="py-2 pr-3 text-[#333]">#{record.id}</td>
                  <td className="py-2 pr-3 text-[#666]">{record.sourceQueue || '-'}</td>
                  <td className="py-2 pr-3 text-[#666]">{record.failureMessage || '-'}</td>
                  <td className="py-2 pr-3">{statusBadge(record.status)}</td>
                  <td className="py-2 pr-3 text-[#666]">{record.replayCount ?? 0}</td>
                  <td className="py-2 pr-3 text-[#666]">{formatTime(record.createdTime)}</td>
                  <td className="py-2 pr-3">
                    {record.status === 'PENDING' ? (
                      <button
                        type="button"
                        onClick={() => void replayDeadLetter(record.id)}
                        disabled={replayingId === record.id}
                        className="inline-flex h-8 items-center rounded-lg bg-[#ff1268] px-3 text-[12px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        {replayingId === record.id ? '重放中...' : '重放'}
                      </button>
                    ) : (
                      <span className="text-[12px] text-[#999]">-</span>
                    )}
                  </td>
                </tr>
              ))}
              {deadLetters.length === 0 ? (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-[#999]">
                    {deadLetterLoading ? '加载中...' : '暂无死信记录'}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
