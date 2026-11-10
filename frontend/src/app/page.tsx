'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { Header } from '@/components/Header'
import { CategoryNav } from '@/components/CategoryNav'
import { Banner, type BannerSlide } from '@/components/Banner'
import { SectionRow } from '@/components/SectionRow'
import { Footer } from '@/components/Footer'
import { listActivities, listCategories } from '@/lib/api'
import { ALL_CITY_VALUE, resolveActivityCityParam } from '@/lib/city-selection'
import { createHomeResumeRefreshHandlers, createLatestRequestGate } from '@/lib/home-resume-refresh'
import { ACTIVITY_VIEW_SIGNAL_KEY, buildPersonalizedActivities, parseActivityViewSignals, type ActivityViewSignal } from '@/lib/personalized-recommendations'
import { toActivitySaleStatus } from '@/lib/activity-sale-status'
import { resolveImageSrc } from '@/lib/image-url'
import type { CategoryVO, ActivityVO } from '@/types/api'
import type { SectionData, Activity } from '@/types/omni'

function toActivity(vo: ActivityVO): Activity {
  return {
    id: String(vo.id),
    itemType: vo.itemType || 'activity',
    title: vo.name,
    categoryId: vo.categoryName,
    poster: resolveImageSrc(vo.poster),
    venue: vo.venueCity || '待定',
    showTime: vo.startTime ? vo.startTime.slice(0, 10) : '待定',
    priceRange: vo.minPrice ? `¥${vo.minPrice}起` : '待定',
    price: vo.minPrice || 0,
    status: toActivitySaleStatus(vo.status),
  }
}

function groupByCategory(activities: Activity[]): SectionData[] {
  const map = new Map<string, Activity[]>()
  for (const a of activities) {
    const list = map.get(a.categoryId) || []
    list.push(a)
    map.set(a.categoryId, list)
  }
  return Array.from(map.entries()).map(([name, items]) => ({
    id: name,
    title: name,
    category: name,
    viewAllUrl: `/search?category=${encodeURIComponent(name)}`,
    items,
  }))
}

const HOME_BANNER_SLIDES: BannerSlide[] = [
  {
    id: 'home-concert',
    title: '热门演唱会',
    subtitle: '现场开唱，锁定近期热门场次',
    imageUrl: '/images/banners/home-concert.jpg',
    linkUrl: '/search?category=%E6%BC%94%E5%94%B1%E4%BC%9A',
    bgColor: '#0b1730',
  },
  {
    id: 'home-festival',
    title: '音乐节与现场派对',
    subtitle: '户外舞台、音乐节与周末现场',
    imageUrl: '/images/banners/home-festival.jpg',
    linkUrl: '/search?keyword=%E9%9F%B3%E4%B9%90%E8%8A%82',
    bgColor: '#15241c',
  },
  {
    id: 'home-theatre',
    title: '剧场演出',
    subtitle: '话剧、音乐剧、舞剧与剧院现场',
    imageUrl: '/images/banners/home-theatre.jpg',
    linkUrl: '/search?category=%E8%AF%9D%E5%89%A7%E6%AD%8C%E5%89%A7',
    bgColor: '#2b0508',
  },
]

export default function HomePage() {
  const [categories, setCategories] = useState<CategoryVO[]>([])
  const [sections, setSections] = useState<SectionData[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [viewSignals, setViewSignals] = useState<ActivityViewSignal[]>([])
  const [currentCity, setCurrentCity] = useState(ALL_CITY_VALUE)
  const [cityHydrated, setCityHydrated] = useState(false)
  const [requestGate] = useState(() => createLatestRequestGate())
  const fetchDataRef = useRef(() => {})
  const lastRefreshRef = useRef(0)

  const fetchData = useCallback(async () => {
    const requestId = requestGate.next()
    setLoading(true)
    try {
      const [catData, actData] = await Promise.all([
        listCategories(),
        listActivities({ page: 1, size: 50, city: resolveActivityCityParam(currentCity) }),
      ])
      if (!requestGate.isCurrent(requestId)) return
      setLoadError(null)
      setCategories(catData)
      const mappedActivities = actData.records.map(toActivity)
      const personalized = buildPersonalizedActivities(mappedActivities, viewSignals)
      setSections([
        ...(personalized.length ? [{ id: 'personalized', title: '猜你喜欢', category: 'personalized', viewAllUrl: '/search', items: personalized }] : []),
        ...groupByCategory(mappedActivities),
      ])
    } catch {
      if (!requestGate.isCurrent(requestId)) return
      setLoadError('活动加载失败，请稍后重试')
      setCategories([])
      setSections([])
    } finally {
      if (requestGate.isCurrent(requestId)) {
        setLoading(false)
      }
    }
  }, [requestGate, currentCity, viewSignals])

  fetchDataRef.current = fetchData

  const refreshWhenVisible = useCallback(() => {
    const now = Date.now()
    if (now - lastRefreshRef.current < 200) return
    lastRefreshRef.current = now
    fetchDataRef.current()
  }, [])

  useEffect(() => {
    if (!cityHydrated) return
    fetchData()
  }, [cityHydrated, fetchData])

  useEffect(() => {
    if (typeof window !== 'undefined') {
      setViewSignals(parseActivityViewSignals(localStorage.getItem(ACTIVITY_VIEW_SIGNAL_KEY)))
    }
  }, [])

  useEffect(() => {
    setCurrentCity(ALL_CITY_VALUE)
    setCityHydrated(true)
  }, [])

  useEffect(() => {
    const handleCityUpdate = (event: Event) => {
      const detail = (event as CustomEvent<string>).detail
      setCurrentCity(detail || ALL_CITY_VALUE)
    }
    window.addEventListener('omni-city-updated', handleCityUpdate)
    return () => window.removeEventListener('omni-city-updated', handleCityUpdate)
  }, [])

  useEffect(() => {
    const handlers = createHomeResumeRefreshHandlers(
      refreshWhenVisible,
      () => document.visibilityState,
    )

    window.addEventListener('pageshow', handlers.handlePageShow)
    window.addEventListener('popstate', handlers.handlePopState)
    document.addEventListener('visibilitychange', handlers.handleVisibilityChange)
    return () => {
      window.removeEventListener('pageshow', handlers.handlePageShow)
      window.removeEventListener('popstate', handlers.handlePopState)
      document.removeEventListener('visibilitychange', handlers.handleVisibilityChange)
    }
  }, [refreshWhenVisible])

  const navCategories = categories.map((c) => ({ id: String(c.id), name: c.name }))

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <Header />
      
      {/* Hero Section with Banner and Nav */}
      <div className="relative bg-white pb-6 rounded-b-[40px] shadow-[0_10px_40px_-20px_rgba(0,0,0,0.05)] z-10">
        <CategoryNav categories={navCategories} />
        <Banner slides={HOME_BANNER_SLIDES} />
      </div>

      <main className="flex-1 pb-20 pt-10">
        {loading ? (
          <section className="py-20">
            <div className="max-w-[1200px] mx-auto px-5">
              <div className="flex flex-col items-center justify-center gap-4 text-gray-400">
                <div className="w-8 h-8 border-4 border-[#ff1268]/20 border-t-[#ff1268] rounded-full animate-spin" />
                <div className="text-sm font-medium tracking-wider">正在加载精彩演出...</div>
              </div>
            </div>
          </section>
        ) : sections.length === 0 ? (
          <section className="py-20">
            <div className="max-w-[1200px] mx-auto px-5">
              <div className="text-center text-gray-400 py-20 text-sm font-medium tracking-wider">
                {loadError || '暂无演出活动'}
              </div>
            </div>
          </section>
        ) : (
          <div className="space-y-2">
            {sections.map((section) => (
              <SectionRow key={section.id} section={section} />
            ))}
          </div>
        )}
      </main>
      <Footer />
    </div>
  )
}
