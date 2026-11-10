'use client'

import { useEffect, useState } from 'react'
import { TicketFinderModal } from '@/components/ai/TicketFinderModal'
import { AI_TICKET_FINDER_OPEN_EVENT } from '@/lib/ai-ticket-finder-modal'

export function TicketFinderModalHost() {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handleOpen = () => setOpen(true)
    window.addEventListener(AI_TICKET_FINDER_OPEN_EVENT, handleOpen)
    return () => window.removeEventListener(AI_TICKET_FINDER_OPEN_EVENT, handleOpen)
  }, [])

  return <TicketFinderModal open={open} onClose={() => setOpen(false)} />
}
