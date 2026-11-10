'use client'

import { useState, useEffect, Suspense, useRef } from 'react'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { ArrowRight, Sparkles } from 'lucide-react'
import { Header, HOT_CITIES, OTHER_CITIES } from '@/components/Header'
import { Footer } from '@/components/Footer'
import { TicketCard } from '@/components/TicketCard'
import { SearchResultsSkeleton } from '@/components/Skeleton'
import { SafeImage } from '@/components/SafeImage'
import { listActivities, listCategories } from '@/lib/api'
import { captureAnalyticsEvent } from '@/lib/analytics'
import { requestOpenAiTicketFinder } from '@/lib/ai-ticket-finder-modal'
import { resolveActivityCityParam, resolveInitialCity } from '@/lib/city-selection'
import { ACTIVITY_VIEW_SIGNAL_KEY, parseActivityViewSignals, type ActivityViewSignal } from '@/lib/personalized-recommendations'
import { toActivitySaleStatus } from '@/lib/activity-sale-status'
import { buildPaginationItems, normalizePageRequest } from '@/lib/pagination'
import { addSearchHistoryTerm, buildEmptySearchRecommendations, buildSearchSidebarRecommendations, buildSearchSuggestions, formatSearchLoadFailure, readSearchHistoryFromStorage, shouldRenderSearchSuggestionStrip, writeSearchHistoryToStorage } from '@/lib/search-experience'
import { restoreSearchScrollIfPending, saveSearchReturnState } from '@/lib/search-return-state'
import { resolveImageSrc } from '@/lib/image-url'
import type { CategoryVO, ActivityVO } from '@/types/api'
import type { Activity } from '@/types/omni'

type SortType = 'recommend' | 'relevance' | 'recent' | 'newest' | 'price_asc' | 'price_desc'
type TimeFilter = 'all' | 'today' | 'tomorrow' | 'weekend' | 'month' | 'custom'
type SaleStatusFilter = '' | 'on_sale' | 'coming_soon' | 'sold_out'
type BooleanFilter = '' | 'true' | 'false'

const SORT_LABELS: Record<SortType, string> = {
  recommend: '推荐排序',
  relevance: '相关度排序',
  recent: '最近开场',
  newest: '最新上架',
  price_asc: '价格升序',
  price_desc: '价格降序',
}

const TIME_LABELS: Record<Exclude<TimeFilter, 'custom'>, string> = {
  all: '全部',
  today: '今天',
  tomorrow: '明天',
  weekend: '本周末',
  month: '一个月内',
}

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

// ========== 筛选栏组件 ==========
function FactorTitle({ children }: { children: React.ReactNode }) {
  return (
    <span className="w-16 text-right text-[13px] text-gray-400 font-medium tracking-wider leading-8 shrink-0 mr-6">
      {children}
    </span>
  )
}

function FilterItem({ active, onClick, children, className }: {
  active: boolean; onClick?: () => void; children: React.ReactNode; className?: string
}) {
  return (
    <button
      onClick={onClick}
      className={`px-4 py-1.5 text-[13px] rounded-full transition-all duration-200 whitespace-nowrap ${
        active 
          ? 'bg-[#ff1268] text-white shadow-sm shadow-[#ff1268]/20 font-medium' 
          : 'text-gray-600 hover:bg-[#fff4f8] hover:text-[#ff1268]'
      } ${className || ''}`}
    >
      {children}
    </button>
  )
}

// ========== 分页组件 ==========
function Pagination({ page, totalPages, onPageChange }: {
  page: number
  totalPages: number
  onPageChange: (p: number) => void
}) {
  const [editingJumpIndex, setEditingJumpIndex] = useState<number | null>(null)
  const [jumpValue, setJumpValue] = useState('')
  const jumpInputRef = useRef<HTMLInputElement>(null)
  const ignoreNextJumpBlurRef = useRef(false)

  useEffect(() => {
    setEditingJumpIndex(null)
    setJumpValue('')
  }, [page, totalPages])

  useEffect(() => {
    if (editingJumpIndex != null) {
      jumpInputRef.current?.focus()
      jumpInputRef.current?.select()
    }
  }, [editingJumpIndex])

  if (totalPages <= 1) return null

  const pages = buildPaginationItems(page, totalPages)
  const closeJumpInput = () => {
    setEditingJumpIndex(null)
    setJumpValue('')
  }
  const cancelJumpInput = () => {
    ignoreNextJumpBlurRef.current = true
    closeJumpInput()
  }
  const commitJumpInput = () => {
    if (ignoreNextJumpBlurRef.current) {
      ignoreNextJumpBlurRef.current = false
      return
    }
    const value = jumpValue.trim()
    if (!value) {
      closeJumpInput()
      return
    }
    const nextPage = normalizePageRequest(value, totalPages)
    ignoreNextJumpBlurRef.current = true
    closeJumpInput()
    if (nextPage !== page) onPageChange(nextPage)
  }

  return (
    <div className="mt-12 mb-8 flex flex-wrap items-center justify-center gap-2">
      <button
        disabled={page <= 1}
        onClick={() => onPageChange(page - 1)}
        className="px-4 py-2 text-[13px] rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium"
      >
        上一页
      </button>
      {pages.map((p, i) =>
        p === 'jump-prev' || p === 'jump-next' ? (
          editingJumpIndex === i ? (
            <input
              key={`jump-input-${i}`}
              ref={jumpInputRef}
              type="text"
              inputMode="numeric"
              value={jumpValue}
              onChange={(event) => setJumpValue(event.target.value.replace(/\D/g, ''))}
              onBlur={commitJumpInput}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  commitJumpInput()
                }
                if (event.key === 'Escape') {
                  event.preventDefault()
                  cancelJumpInput()
                }
              }}
              className="h-9 w-14 rounded-lg border border-[#ff2d55] bg-white px-2 text-center text-[13px] font-medium text-gray-700 outline-none shadow-sm shadow-[#ff2d55]/10"
              placeholder="页码"
              aria-label="输入页码跳转"
            />
          ) : (
            <button
              key={`${p}-${i}`}
              type="button"
              onClick={() => {
                ignoreNextJumpBlurRef.current = false
                setEditingJumpIndex(i)
                setJumpValue('')
              }}
              className="h-9 min-w-9 rounded-lg border border-transparent px-2 text-[13px] font-medium text-gray-400 transition-all hover:border-gray-200 hover:bg-gray-50 hover:text-[#ff2d55]"
              aria-label="输入页码跳转"
              title="输入页码跳转"
            >
              ...
            </button>
          )
        ) : (
          <button
            key={p}
            onClick={() => onPageChange(p)}
            className={`w-9 h-9 flex items-center justify-center text-[13px] rounded-lg transition-all font-medium ${
              p === page 
                ? 'bg-[#ff2d55] text-white shadow-sm shadow-[#ff2d55]/20' 
                : 'border border-gray-200 text-gray-600 hover:bg-gray-50'
            }`}
          >
            {p}
          </button>
        )
      )}
      <button
        disabled={page >= totalPages}
        onClick={() => onPageChange(page + 1)}
        className="px-4 py-2 text-[13px] rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium"
      >
        下一页
      </button>
    </div>
  )
}

// ========== 排序栏组件 ==========
function SortBar({ sort, onSortChange, page, totalPages }: {
  sort: SortType
  onSortChange: (s: SortType) => void
  page: number
  totalPages: number
}) {
  return (
    <div className="flex items-center justify-between bg-white rounded-2xl p-2 shadow-[0_2px_10px_rgb(0,0,0,0.02)] mb-6 border border-gray-100">
      <div className="flex items-center gap-1">
        {(Object.entries(SORT_LABELS) as [SortType, string][]).map(([key, label]) => (
          <button
            key={key}
            onClick={() => onSortChange(key)}
            className={`px-5 py-2 text-[14px] rounded-xl transition-all duration-200 ${
              sort === key ? 'bg-[#fff4f8] text-[#ff1268] font-bold' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900 font-medium'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="pr-4 text-[13px] text-gray-400 font-medium">
        <span className="text-[#ff1268]">{page}</span> / {totalPages || 1}
      </div>
    </div>
  )
}

// ========== 主内容 ==========
function SearchContent() {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const initialCategory = searchParams.get('category') || ''
  const initialKeyword = searchParams.get('keyword') || ''
  const initialCity = searchParams.get('city') || ''

  const [categories, setCategories] = useState<CategoryVO[]>([])
  const [activities, setActivities] = useState<Activity[]>([])
  const [fallbackActivities, setFallbackActivities] = useState<Activity[]>([])
  const [errorMessage, setErrorMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(1)
  const [activeCategory, setActiveCategory] = useState(initialCategory)
  const [activeTime, setActiveTime] = useState<TimeFilter>('all')
  const [sort, setSort] = useState<SortType>('recommend')
  const [activeCity, setActiveCity] = useState(() => resolveInitialCity(initialCity))
  const [searchHistory, setSearchHistory] = useState<string[]>([])
  const [viewSignals, setViewSignals] = useState<ActivityViewSignal[]>([])
  const [showAllCities, setShowAllCities] = useState(false)
  const [citySearchKeyword, setCitySearchKeyword] = useState('')
  const [customDate, setCustomDate] = useState('')
  const [minPrice, setMinPrice] = useState('')
  const [maxPrice, setMaxPrice] = useState('')
  const [saleStatus, setSaleStatus] = useState<SaleStatusFilter>('')
  const [seatMapOnly, setSeatMapOnly] = useState(false)
  const [realNameFilter, setRealNameFilter] = useState<BooleanFilter>('')
  const dateInputRef = useRef<HTMLInputElement>(null)

  const toLocalDateStr = (d: Date) => {
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${y}-${m}-${day}`
  }

  const getDateRange = () => {
    const now = new Date()
    const todayStr = toLocalDateStr(now)
    if (activeTime === 'today') return { dateFrom: todayStr, dateTo: todayStr }
    if (activeTime === 'tomorrow') {
      const tomorrow = new Date(now); tomorrow.setDate(tomorrow.getDate() + 1)
      const value = toLocalDateStr(tomorrow)
      return { dateFrom: value, dateTo: value }
    }
    if (activeTime === 'weekend') {
      const day = now.getDay()
      const satOffset = day === 0 ? -1 : 6 - day
      const sat = new Date(now); sat.setDate(now.getDate() + satOffset)
      const sun = new Date(sat); sun.setDate(sat.getDate() + 1)
      return { dateFrom: toLocalDateStr(sat), dateTo: toLocalDateStr(sun) }
    }
    if (activeTime === 'month') {
      const monthLater = new Date(now); monthLater.setMonth(monthLater.getMonth() + 1)
      return { dateFrom: todayStr, dateTo: toLocalDateStr(monthLater) }
    }
    if (activeTime === 'custom' && customDate) return { dateFrom: customDate, dateTo: customDate }
    return {}
  }

  const fetchActivities = async (cat: string, p: number) => {
    setLoading(true)
    setErrorMessage('')
    try {
      const loadFallbackActivities = async () => {
        try {
          const fallback = await listActivities({
            page: 1,
            size: 6,
            keyword: initialKeyword || undefined,
            sort: sort === 'recommend' ? 'recent' : sort,
          })
          setFallbackActivities(fallback.records.map(toActivity))
        } catch {
          setFallbackActivities([])
        }
      }

      let currentCats = categories
      if (currentCats.length === 0) {
        // 如果没有指定分类，可以并行请求分类和活动以提升首屏速度
        if (!cat) {
          const dateRange = getDateRange()
          const [catData, actData] = await Promise.all([
            listCategories(),
            listActivities({
              page: p,
              size: 20,
              keyword: initialKeyword,
              city: resolveActivityCityParam(activeCity),
              ...dateRange,
              minPrice: minPrice ? Number(minPrice) : undefined,
              maxPrice: maxPrice ? Number(maxPrice) : undefined,
              saleStatus: saleStatus || undefined,
              isSupportSeat: seatMapOnly || undefined,
              realNameRequired: realNameFilter === '' ? undefined : realNameFilter === 'true',
              sort: sort === 'recommend' ? undefined : sort,
            })
          ])
          setCategories(catData)
          setActivities(actData.records.map(toActivity))
          setTotal(actData.total)
          setTotalPages(actData.pages)
          if (actData.total === 0) {
            captureAnalyticsEvent('omni_search_empty_result_seen', {
              city: activeCity,
              result_count_bucket: '0',
            })
            await loadFallbackActivities()
          } else {
            setFallbackActivities([])
          }
          setLoading(false)
          return
        } else {
          currentCats = await listCategories()
          setCategories(currentCats)
        }
      }

      let categoryId: number | undefined
      if (cat) {
        const found = currentCats.find((c) => c.name === cat)
        if (found) categoryId = found.id
      }

      const dateRange = getDateRange()
      const data = await listActivities({
        page: p,
        size: 20,
        categoryId,
        keyword: initialKeyword,
        city: resolveActivityCityParam(activeCity),
        ...dateRange,
        minPrice: minPrice ? Number(minPrice) : undefined,
        maxPrice: maxPrice ? Number(maxPrice) : undefined,
        saleStatus: saleStatus || undefined,
        isSupportSeat: seatMapOnly || undefined,
        realNameRequired: realNameFilter === '' ? undefined : realNameFilter === 'true',
        sort: sort === 'recommend' ? undefined : sort,
      })
      setActivities(data.records.map(toActivity))
      setTotal(data.total)
      setTotalPages(data.pages)
      if (data.total === 0) {
        captureAnalyticsEvent('omni_search_empty_result_seen', {
          city: activeCity,
          category_id: categoryId,
          result_count_bucket: '0',
        })
        await loadFallbackActivities()
      } else {
        setFallbackActivities([])
      }
    } catch (error) {
      const failure = formatSearchLoadFailure(error)
      setActivities([])
      setFallbackActivities([])
      setTotal(0)
      setTotalPages(1)
      setErrorMessage(failure.description)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (typeof window !== 'undefined') {
      setSearchHistory(readSearchHistoryFromStorage(localStorage))
      setViewSignals(parseActivityViewSignals(localStorage.getItem(ACTIVITY_VIEW_SIGNAL_KEY)))
      restoreSearchScrollIfPending()
    }
  }, [])

  useEffect(() => {
    setActiveCity(resolveInitialCity(initialCity))
  }, [initialCity])

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('omni-city-updated', { detail: activeCity }))
  }, [activeCity])

  useEffect(() => {
    if (initialKeyword && typeof window !== 'undefined') {
      const next = addSearchHistoryTerm(readSearchHistoryFromStorage(localStorage), initialKeyword)
      writeSearchHistoryToStorage(localStorage, next)
      setSearchHistory(next)
    }
  }, [initialKeyword])

  useEffect(() => {
    fetchActivities(activeCategory, page)
  }, [activeCategory, page, activeCity, activeTime, customDate, sort, minPrice, maxPrice, saleStatus, seatMapOnly, realNameFilter, initialKeyword])

  const matchTime = (showTime: string): boolean => {
    if (activeTime === 'all') return true
    const now = new Date()
    const todayStr = toLocalDateStr(now)
    const tomorrow = new Date(now); tomorrow.setDate(tomorrow.getDate() + 1)
    const tomorrowStr = toLocalDateStr(tomorrow)
    // 提取 showTime 中的日期部分 (支持 "2026.05.16" / "2026-05-16" / "2026.05.16-05.17" 等格式)
    const dateMatch = showTime.match(/(\d{4})[.\-](\d{2})[.\-](\d{2})/)
    const actDateStr = dateMatch ? `${dateMatch[1]}-${dateMatch[2]}-${dateMatch[3]}` : ''
    if (!actDateStr) return true // 无法解析日期时不过滤

    // 对于跨天活动 (如 "2026.05.16-05.17")，同时提取结束日期
    const endMatch = showTime.match(/(\d{4})[.\-](\d{2})[.\-](\d{2}).*?(\d{2})[.\-](\d{2})$/)
    const actEndStr = endMatch ? `${endMatch[1]}-${endMatch[4]}-${endMatch[5]}` : actDateStr

    if (activeTime === 'today') return actDateStr <= todayStr && actEndStr >= todayStr
    if (activeTime === 'tomorrow') return actDateStr <= tomorrowStr && actEndStr >= tomorrowStr
    if (activeTime === 'weekend') {
      const day = now.getDay()
      const satOffset = day === 0 ? -1 : 6 - day
      const sat = new Date(now); sat.setDate(sat.getDate() + satOffset)
      const sun = new Date(sat); sun.setDate(sun.getDate() + 1)
      const satStr = toLocalDateStr(sat)
      const sunStr = toLocalDateStr(sun)
      return actEndStr >= satStr && actDateStr <= sunStr
    }
    if (activeTime === 'month') {
      const monthLater = new Date(now); monthLater.setMonth(monthLater.getMonth() + 1)
      return actEndStr >= todayStr && actDateStr <= toLocalDateStr(monthLater)
    }
    if (activeTime === 'custom' && customDate) return actDateStr <= customDate && actEndStr >= customDate
    return true
  }

  const keyword = searchParams.get('keyword') || ''
  const displayTotal = total
  const pageData = activities
  const displayTotalPages = totalPages
  const searchLoadFailure = errorMessage ? formatSearchLoadFailure(new Error(errorMessage)) : null
  const resultTerms = Array.from(new Set(activities.flatMap(item => [item.title, item.venue].filter(Boolean) as string[])))
  const suggestions = buildSearchSuggestions({
    keyword,
    history: searchHistory,
    popular: [],
    resultTerms,
    viewSignals,
    limit: 8,
  })
  const showSuggestionStrip = shouldRenderSearchSuggestionStrip({
    pathname,
    keyword,
    suggestionCount: suggestions.length,
  })
  const emptyRecommendations = buildEmptySearchRecommendations({
    keyword,
    activeCity,
    activities: fallbackActivities,
    viewSignals,
    cities: [...HOT_CITIES, ...OTHER_CITIES],
    limit: 6,
  })
  const sidebarRecommendations = buildSearchSidebarRecommendations({
    activities,
    viewSignals,
    limit: 4,
  })

  const handlePageChange = (p: number) => {
    setPage(p)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const handleCategoryChange = (catName: string) => {
    const newCat = activeCategory === catName ? '' : catName
    setActiveCategory(newCat)
    setPage(1)
    const params = new URLSearchParams()
    if (newCat) params.set('category', newCat)
    if (initialKeyword) params.set('keyword', initialKeyword)
    if (activeCity !== '全部') params.set('city', activeCity)
    router.replace(`/search${params.toString() ? `?${params.toString()}` : ''}`)
  }

  const searchWithKeyword = (nextKeyword: string) => {
    captureAnalyticsEvent('omni_search_submitted', {
      keyword_present: Boolean(nextKeyword.trim()),
      city: activeCity,
      category_id: activeCategory ? categories.find((category) => category.name === activeCategory)?.id : undefined,
      source: 'search_page',
    })
    const params = new URLSearchParams()
    params.set('keyword', nextKeyword)
    if (activeCity !== '全部') params.set('city', activeCity)
    router.push(`/search?${params.toString()}`)
  }

  const searchWithCity = (nextCity: string) => {
    setActiveCity(nextCity)
    setPage(1)
    const params = new URLSearchParams()
    if (keyword) params.set('keyword', keyword)
    params.set('city', nextCity)
    router.push(`/search?${params.toString()}`)
  }

  const handleTimeChange = (time: TimeFilter) => {
    setActiveTime(time)
    if (time !== 'custom') setCustomDate('')
    setPage(1)
  }

  const clearFilters = () => {
    setActiveCategory('')
    setActiveCity('全部')
    setActiveTime('all')
    setCustomDate('')
    setMinPrice('')
    setMaxPrice('')
    setSaleStatus('')
    setSeatMapOnly(false)
    setRealNameFilter('')
    setSort('recommend')
    setPage(1)
    router.replace('/search')
  }

  return (
    <div className="max-w-[1200px] mx-auto w-full px-5 py-8 flex-1">
      {/* 结果计数 */}
      <div className="text-[13px] text-gray-500 mb-6 font-medium tracking-wide">
        {keyword ? (
          <>搜索 “<span className="text-[#ff1268]">{keyword}</span>” 共 <span className="text-[#ff1268] mx-1">{displayTotal}</span> 个商品</>
        ) : (
          <>共 <span className="text-[#ff1268] mx-1">{displayTotal}</span> 个商品</>
        )}
      </div>

      <div className="flex gap-8 items-start">
        {/* 左侧主体 */}
        <div className="flex-1 min-w-0">
          {/* 筛选面板 */}
          <div className="bg-white rounded-3xl p-6 shadow-[0_8px_30px_rgb(0,0,0,0.04)] mb-6 border border-gray-100">
            {/* 城市筛选 */}
            <div className="flex items-start py-4 border-b border-gray-100 border-dashed">
              <FactorTitle>城 市：</FactorTitle>
              <div className="flex-1 min-w-0">
                <div className="mb-4 flex items-center gap-4">
                  <div className="text-[13px] text-gray-500 font-medium">
                    当前选中城市
                    <span className="text-[#ff1268] ml-2 font-bold">{activeCity === '全部' ? '全国' : activeCity}</span>
                  </div>
                  <input 
                    type="text"
                    placeholder="输入城市名快速搜索"
                    value={citySearchKeyword}
                    onChange={(e) => setCitySearchKeyword(e.target.value)}
                    className="px-4 py-1.5 text-[13px] border border-gray-200 rounded-full outline-none w-48 text-gray-700 focus:border-[#ff1268]/40 focus:ring-2 focus:ring-[#ff1268]/10 transition-all placeholder:text-gray-400 bg-gray-50 focus:bg-white"
                  />
                </div>
                <div className={`flex flex-wrap gap-y-3 gap-x-2 custom-scrollbar transition-all ${showAllCities || citySearchKeyword ? 'max-h-[300px] overflow-y-auto pr-2' : ''}`}>
                  <FilterItem 
                    active={activeCity === '全部'}
                    onClick={() => { setActiveCity('全部'); setPage(1); }}
                  >
                    全部
                  </FilterItem>
                  {((showAllCities || citySearchKeyword) ? [...HOT_CITIES, ...OTHER_CITIES] : HOT_CITIES)
                    .filter(city => !citySearchKeyword || city.includes(citySearchKeyword))
                    .map((city, index) => (
                    <FilterItem 
                      key={`${city}-${index}`} 
                      active={activeCity === city}
                      onClick={() => { setActiveCity(city); setPage(1); }}
                    >
                      {city}
                    </FilterItem>
                  ))}
                  <button
                    onClick={() => setShowAllCities(!showAllCities)}
                    className="text-[#ff1268] text-[13px] font-medium px-2 py-1.5 hover:bg-[#fff4f8] rounded-full transition-colors ml-1"
                  >
                    {showAllCities ? '收起 ∧' : '更多...'}
                  </button>
                </div>
              </div>
            </div>

            {/* 分类筛选 */}
            <div className="flex items-start py-4 border-b border-gray-100 border-dashed">
              <FactorTitle>分 类：</FactorTitle>
              <div className="flex-1 flex flex-wrap gap-y-3 gap-x-2">
                <FilterItem
                  active={!activeCategory}
                  onClick={() => handleCategoryChange('')}
                >
                  全部
                </FilterItem>
                {categories.map((cat) => (
                  <FilterItem
                    key={cat.id}
                    active={activeCategory === cat.name}
                    onClick={() => handleCategoryChange(cat.name)}
                  >
                    {cat.name}
                  </FilterItem>
                ))}
              </div>
            </div>

            {/* 时间筛选 */}
            <div className="flex items-start pt-4">
              <FactorTitle>时 间：</FactorTitle>
              <div className="flex-1 flex flex-wrap items-center gap-y-3 gap-x-2">
                {(Object.entries(TIME_LABELS) as [TimeFilter, string][]).map(([key, label]) => (
                  <FilterItem
                    key={key}
                    active={activeTime === key}
                    onClick={() => handleTimeChange(key)}
                  >
                    {label}
                  </FilterItem>
                ))}
                <div className="relative inline-block ml-2">
                  <button
                    onClick={() => dateInputRef.current?.showPicker()}
                    className={`px-4 py-1.5 text-[13px] rounded-full transition-all duration-200 whitespace-nowrap border ${
                      activeTime === 'custom'
                        ? 'bg-[#ff1268] border-[#ff1268] text-white shadow-sm shadow-[#ff1268]/20 font-medium'
                        : 'bg-white border-gray-200 text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]'
                    }`}
                  >
                    按日历{customDate ? ` (${customDate})` : ''}
                  </button>
                  <input
                    ref={dateInputRef}
                    type="date"
                    value={customDate}
                    onChange={(e) => {
                      if (e.target.value) {
                        setCustomDate(e.target.value);
                        setActiveTime('custom');
                        setPage(1);
                      } else {
                        setCustomDate('');
                        setActiveTime('all');
                        setPage(1);
                      }
                    }}
                    className="absolute left-0 bottom-0 w-px h-px opacity-0 pointer-events-none"
                  />
                </div>
              </div>
            </div>

            <div className="flex items-start py-4 border-t border-gray-100 border-dashed">
              <FactorTitle>价 格：</FactorTitle>
              <div className="flex flex-1 flex-wrap items-center gap-2">
                <input
                  type="number"
                  min={0}
                  value={minPrice}
                  onChange={(event) => { setMinPrice(event.target.value); setPage(1) }}
                  placeholder="最低价"
                  className="h-8 w-28 rounded-full border border-gray-200 px-3 text-[13px] outline-none focus:border-[#ff1268]"
                />
                <span className="text-[13px] text-gray-400">至</span>
                <input
                  type="number"
                  min={0}
                  value={maxPrice}
                  onChange={(event) => { setMaxPrice(event.target.value); setPage(1) }}
                  placeholder="最高价"
                  className="h-8 w-28 rounded-full border border-gray-200 px-3 text-[13px] outline-none focus:border-[#ff1268]"
                />
                {[
                  { label: '180以下', min: '', max: '180' },
                  { label: '180-580', min: '180', max: '580' },
                  { label: '580以上', min: '580', max: '' },
                ].map(option => (
                  <FilterItem
                    key={option.label}
                    active={minPrice === option.min && maxPrice === option.max}
                    onClick={() => { setMinPrice(option.min); setMaxPrice(option.max); setPage(1) }}
                  >
                    {option.label}
                  </FilterItem>
                ))}
              </div>
            </div>

            <div className="flex items-start pt-4 border-t border-gray-100 border-dashed">
              <FactorTitle>条 件：</FactorTitle>
              <div className="flex flex-1 flex-wrap items-center gap-2">
                {[
                  { value: '', label: '全部状态' },
                  { value: 'on_sale', label: '售票中' },
                  { value: 'coming_soon', label: '待开票' },
                  { value: 'sold_out', label: '已售罄' },
                ].map(option => (
                  <FilterItem
                    key={option.value || 'all-sale'}
                    active={saleStatus === option.value}
                    onClick={() => { setSaleStatus(option.value as SaleStatusFilter); setPage(1) }}
                  >
                    {option.label}
                  </FilterItem>
                ))}
                <label className="ml-1 inline-flex h-8 cursor-pointer items-center gap-2 rounded-full border border-gray-200 px-4 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]">
                  <input type="checkbox" checked={seatMapOnly} onChange={(event) => { setSeatMapOnly(event.target.checked); setPage(1) }} className="accent-[#ff1268]" />
                  可选座
                </label>
                <select
                  value={realNameFilter}
                  onChange={(event) => { setRealNameFilter(event.target.value as BooleanFilter); setPage(1) }}
                  className="h-8 rounded-full border border-gray-200 px-3 text-[13px] text-gray-600 outline-none focus:border-[#ff1268]"
                >
                  <option value="">实名不限</option>
                  <option value="true">实名制</option>
                  <option value="false">非实名制</option>
                </select>
              </div>
            </div>
          </div>

          {/* 排序栏 */}
          <SortBar sort={sort} onSortChange={(value) => { setSort(value); setPage(1) }} page={page} totalPages={displayTotalPages} />

          {showSuggestionStrip && (
            <div className="mb-5 flex flex-wrap items-center gap-2 text-[13px] text-gray-500">
              <span>搜索联想</span>
              {suggestions.map((text) => (
                <button
                  key={text}
                  onClick={() => searchWithKeyword(text)}
                  className="rounded-full border border-gray-200 bg-white px-3 py-1.5 text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]"
                >
                  {text}
                </button>
              ))}
            </div>
          )}

          {/* 结果网格 */}
          {loading ? (
            <SearchResultsSkeleton />
          ) : searchLoadFailure ? (
            <div className="rounded-3xl border border-red-100 bg-white px-6 py-20 text-center text-[14px] text-gray-500">
              <div className="font-medium text-gray-700">{searchLoadFailure.title}</div>
              <div className="mt-2 text-[13px] text-gray-400">{searchLoadFailure.description}</div>
              <button
                onClick={() => fetchActivities(activeCategory, page)}
                className="mt-5 rounded-lg bg-[#ff1268] px-4 py-2 text-[13px] text-white"
              >
                {searchLoadFailure.retryLabel}
              </button>
            </div>
          ) : pageData.length === 0 ? (
            <div className="rounded-3xl border border-gray-100 bg-white px-6 py-20 text-center text-[14px] text-gray-500">
              <div className="font-medium text-gray-600">暂无符合条件的演出</div>
              <div className="mt-2 text-[13px] text-gray-400">可以放宽筛选条件，或先关注城市；无票票档可在购票区加入候补。</div>
              {keyword.trim() && (
                <button
                  type="button"
                  onClick={requestOpenAiTicketFinder}
                  className="mx-auto mt-6 inline-flex max-w-full items-center gap-2 rounded-xl border border-[#ffd6e7] bg-[#fff8fb] px-4 py-3 text-left text-[13px] font-semibold text-[#e6005c] transition-colors hover:border-[#ff1268] hover:bg-[#fff0f5]"
                >
                  <Sparkles className="h-4 w-4 shrink-0" />
                  <span className="min-w-0 truncate">找不到合适的票？试试 AI 智能找票</span>
                  <ArrowRight className="h-4 w-4 shrink-0" />
                </button>
              )}
              {(emptyRecommendations.terms.length > 0 || emptyRecommendations.recentTerms.length > 0 || emptyRecommendations.cities.length > 0) && (
                <div className="mx-auto mt-5 max-w-[640px] rounded-2xl bg-gray-50 px-4 py-4 text-left">
                  {emptyRecommendations.terms.length > 0 && (
                    <div>
                      <div className="mb-2 text-[12px] font-medium text-gray-500">相关演出</div>
                      <div className="flex flex-wrap gap-2">
                        {emptyRecommendations.terms.map(term => (
                          <button key={term} onClick={() => searchWithKeyword(term)} className="rounded-full border border-gray-200 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]">
                            {term}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                  {emptyRecommendations.recentTerms.length > 0 && (
                    <div className={emptyRecommendations.terms.length > 0 ? 'mt-4' : ''}>
                      <div className="mb-2 text-[12px] font-medium text-gray-500">最近浏览</div>
                      <div className="flex flex-wrap gap-2">
                        {emptyRecommendations.recentTerms.map(term => (
                          <button key={term} onClick={() => searchWithKeyword(term)} className="rounded-full border border-gray-200 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]">
                            {term}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                  {emptyRecommendations.cities.length > 0 && (
                    <div className={emptyRecommendations.terms.length > 0 || emptyRecommendations.recentTerms.length > 0 ? 'mt-4' : ''}>
                      <div className="mb-2 text-[12px] font-medium text-gray-500">相邻城市</div>
                      <div className="flex flex-wrap gap-2">
                        {emptyRecommendations.cities.map(city => (
                          <button key={city} onClick={() => searchWithCity(city)} className="rounded-full border border-gray-200 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-[#ff1268] hover:text-[#ff1268]">
                            {city}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
              <div className="mt-5 flex flex-wrap justify-center gap-2">
                <button onClick={clearFilters} className="rounded-lg border border-[#ff1268] bg-white px-4 py-2 text-[13px] text-[#ff1268]">清空筛选</button>
                <button onClick={() => router.push('/subscriptions')} className="rounded-lg bg-[#ff1268] px-4 py-2 text-[13px] text-white">关注提醒</button>
                <button onClick={() => router.push('/waitlist')} className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-[13px] text-gray-600">查看候补</button>
              </div>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-4 gap-5">
                {pageData.map((activity) => (
                  <TicketCard key={activity.id} activity={activity} onNavigate={() => saveSearchReturnState()} />
                ))}
              </div>
              <Pagination page={page} totalPages={displayTotalPages} onPageChange={handlePageChange} />
            </>
          )}
        </div>

        {/* 右侧推荐 */}
        {sidebarRecommendations.length > 0 && (
        <div className="w-[280px] shrink-0 hidden lg:block">
          <div className="bg-white rounded-3xl p-6 shadow-[0_8px_30px_rgb(0,0,0,0.04)] sticky top-[96px] border border-gray-100">
            <h3 className="text-[18px] font-extrabold text-gray-900 mb-6 tracking-tight flex items-center">
              <span className="w-1 h-4 bg-[#ff1268] rounded-full mr-2"></span>
              您可能还喜欢
            </h3>
            <div className="flex flex-col gap-6">
              {sidebarRecommendations.map((a) => (
                <a
                  key={a.id}
                  href={a.itemType === 'tour' ? `/tour/${a.id}` : `/activity/${a.id}`}
                  onClick={() => saveSearchReturnState()}
                  className="flex gap-4 group"
                >
                  <div className="w-[84px] h-[112px] shrink-0 bg-gray-100 rounded-xl overflow-hidden relative shadow-sm">
                    <SafeImage
                      src={a.poster}
                      alt={a.title}
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                    />
                  </div>
                  <div className="flex-1 min-w-0 py-1 flex flex-col justify-between">
                    <div className="text-[14px] text-gray-800 font-bold leading-snug line-clamp-2 group-hover:text-[#ff1268] transition-colors">
                      {a.title}
                    </div>
                    <div className="text-[16px] font-extrabold text-[#ff1268] tracking-tight">
                      {a.priceRange}
                    </div>
                  </div>
                </a>
              ))}
            </div>
          </div>
        </div>
        )}
      </div>
    </div>
  )
}

// ========== 页面入口 ==========
export default function SearchPage() {
  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <Header />
      <Suspense fallback={
        <div className="mx-auto w-full max-w-[1200px] flex-1 px-5 py-8">
          <SearchResultsSkeleton />
        </div>
      }>
        <SearchContent />
      </Suspense>
      <Footer />
    </div>
  )
}
