'use client'

import { useEffect } from 'react'
import { X, Sparkles } from 'lucide-react'
import { TicketFinderContent } from '@/components/ai/TicketFinderContent'

interface TicketFinderModalProps {
  open: boolean
  onClose: () => void
}

export function TicketFinderModal({ open, onClose }: TicketFinderModalProps) {
  useEffect(() => {
    if (!open) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [onClose, open])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-[120] flex items-center justify-center bg-black/45 px-3 py-4 backdrop-blur-[2px] sm:px-5"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="ticket-finder-modal-title"
        className="flex max-h-[90vh] w-full max-w-[760px] flex-col overflow-hidden rounded-[26px] bg-white shadow-[0_24px_80px_rgba(18,22,33,0.24)]"
        onClick={event => event.stopPropagation()}
      >
        <div className="flex shrink-0 items-start justify-between gap-4 border-b border-[#f0f1f3] px-5 py-5 sm:px-7">
          <div>
            <div className="flex items-center gap-2 text-[18px] font-bold text-[#17191d]">
              <span className="flex h-8 w-8 items-center justify-center rounded-xl bg-[#fff0f5] text-[#ff1268]">
                <Sparkles className="h-4 w-4" />
              </span>
              <h2 id="ticket-finder-modal-title">AI 智能找票</h2>
            </div>
            <p className="mt-2 text-[13px] text-[#737780]">告诉我你想看的演出，我帮你筛选真正可买的票</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[#8a8e97] transition-colors hover:bg-[#f7f8fa] hover:text-[#17191d]"
            aria-label="关闭 AI 找票弹窗"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="min-h-0 overflow-y-auto px-5 py-5 sm:px-7 sm:py-6">
          <TicketFinderContent onClose={onClose} showHeader={false} />
        </div>
      </div>
    </div>
  )
}
