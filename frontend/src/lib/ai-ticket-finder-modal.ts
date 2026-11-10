export const AI_TICKET_FINDER_OPEN_EVENT = 'omni-open-ai-ticket-finder'

export function requestOpenAiTicketFinder() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(AI_TICKET_FINDER_OPEN_EVENT))
  }
}
