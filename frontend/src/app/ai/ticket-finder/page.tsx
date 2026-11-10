'use client'

import { TicketFinderRouteContent } from '@/components/ai/TicketFinderContent'
import { Footer } from '@/components/Footer'
import { Header } from '@/components/Header'
export default function TicketFinderPage() {
  return (
    <div className="min-h-screen bg-[#f7f8fa] text-[#17191d]">
      <Header />
      <main className="mx-auto w-full max-w-[1040px] px-5 py-10 md:py-14">
        <section className="rounded-[28px] border border-[#eceef1] bg-white px-6 py-8 shadow-[0_16px_50px_rgba(22,27,38,0.06)] md:px-10 md:py-11">
          <TicketFinderRouteContent />
        </section>
      </main>
      <Footer />
    </div>
  )
}
