"use client";

import { useState, useEffect, useRef } from "react";
import Link from "next/link";
import { useRouter, usePathname } from "next/navigation";
import { Search, MapPin, ChevronDown, User, Menu, X, Trash2, Flame, Sparkles } from "lucide-react";
import { AUTH_UPDATED_EVENT, getUser, isAuthenticated, logout } from "@/lib/auth";
import { CITY_KEY, filterCityOptions, formatCityDisplay, resolveRouteCity, resolveStoredCity, ALL_CITY_VALUE } from "@/lib/city-selection";
import { canEnterConsole, getDefaultConsolePath } from "@/lib/console-auth";
import { NotificationBell } from "@/components/NotificationBell";
import { SafeImage } from "@/components/SafeImage";
import { addSearchHistory, clearSearchHistory, getSearchHistory, getSearchTrending, listActivities } from "@/lib/api";
import { requestOpenAiTicketFinder } from "@/lib/ai-ticket-finder-modal";
import { SEARCH_HISTORY_KEY, addSearchHistoryTerm, getSearchTrendingTagMeta, readSearchHistoryFromStorage, writeSearchHistoryToStorage } from "@/lib/search-experience";
import type { ActivityVO, SearchTrendingItem, UserRole } from "@/types/api";

// 未登录搜索历史本地缓存 key: search_history_records

export const HOT_CITIES = ['北京', '上海', '广州', '深圳', '杭州', '南京', '成都', '武汉', '天津', '沈阳', '西安', '苏州'];
export const OTHER_CITIES = Array.from(new Set([
  '阿坝', '阿克苏', '阿拉善', '安康', '安庆', '鞍山', '安顺', '安阳', '澳门', '巴中', '白城', '百色', '白山', '保定', '宝鸡', '保山', '包头', '北海', '本溪', '蚌埠', '毕节', '滨州', '博尔塔拉', '亳州', '沧州', '长春', '常德', '昌吉', '长沙', '长治', '常州', '朝阳', '潮州', '郴州', '承德', '赤峰', '池州', '重庆', '崇左', '楚雄', '滁州', '大理', '大连', '大庆', '大同', '大兴安岭', '达州', '丹东', '儋州', '德宏', '德阳', '德州', '迪庆', '定西', '东方', '东莞', '东营', '鄂尔多斯', '鄂州', '恩施', '佛山', '抚顺', '阜新', '阜阳', '福州', '抚州', '甘南', '赣州', '甘孜', '高雄', '固原', '广安', '广元', '广州', '贵港', '桂林', '贵阳', '哈尔滨', '哈密', '海北', '海口', '海南州', '海外', '海西', '邯郸', '汉中', '杭州', '鹤壁', '河池', '合肥', '和田', '河源', '菏泽', '贺州', '黑河', '衡水', '衡阳', '红河', '呼和浩特', '葫芦岛', '呼伦贝尔', '湖州', '淮安', '怀化', '淮南', '黄冈', '黄山', '黄石', '惠州', '吉安', '吉林', '济南', '济宁', '鸡西', '佳木斯', '嘉兴', '嘉峪关', '江门', '焦作', '揭阳', '晋城', '金华', '晋中', '锦州', '景德镇', '荆门', '荆州', '金昌', '九江', '酒泉', '喀什', '开封', '克拉玛依', '昆明', '兰州', '廊坊', '拉萨', '乐山', '凉山', '连云港', '聊城', '辽阳', '辽源', '丽江', '临沧', '临汾', '临夏', '临沂', '林芝', '丽水', '六盘水', '柳州', '六安', '陇南', '龙岩', '娄底', '六安', '漯河', '洛阳', '泸州', '吕梁', '马鞍山', '茂名', '眉山', '梅州', '绵阳', '牡丹江', '南昌', '南充', '南京', '南宁', '南平', '南通', '南阳', '内江', '宁波', '宁德', '怒江', '盘锦', '攀枝花', '平顶山', '平凉', '萍乡', '莆田', '濮阳', '齐齐哈尔', '黔东南', '潜江', '黔南', '黔西南', '钦州', '秦皇岛', '青岛', '庆阳', '清远', '泉州', '曲靖', '衢州', '日喀则', '日照', '三门峡', '三明', '三亚', '商洛', '商丘', '上饶', '山南', '汕头', '汕尾', '韶关', '绍兴', '邵阳', '神农架', '沈阳', '深圳', '十堰', '石家庄', '石嘴山', '双鸭山', '朔州', '四平', '松原', '绥化', '随州', '遂宁', '苏州', '宿迁', '宿州', '塔城', '泰安', '台北', '太原', '台州', '泰州', '唐山', '天津', '天门', '天水', '铁岭', '铜川', '通化', '通辽', '铜陵', '铜仁', '吐鲁番', '威海', '潍坊', '渭南', '文山', '温州', '乌海', '乌兰察布', '乌鲁木齐', '无锡', '吴忠', '梧州', '芜湖', '武威', '武汉', '西安', '项城', '香港', '湘潭', '湘西', '襄阳', '咸宁', '咸阳', '孝感', '锡林郭勒', '兴安', '邢台', '西宁', '新乡', '信阳', '新余', '忻州', '西双版纳', '宣城', '许昌', '徐州', '雅安', '延安', '延边', '盐城', '阳江', '阳泉', '扬州', '烟台', '宜宾', '宜昌', '伊春', '宜春', '伊犁', '银川', '营口', '鹰潭', '益阳', '永州', '岳阳', '榆林', '玉林', '运城', '云浮', '玉树', '玉溪', '枣庄', '张家界', '张家口', '张掖', '漳州', '湛江', '肇庆', '昭通', '郑州', '镇江', '中山', '中卫', '周口', '舟山', '珠海', '驻马店', '株洲', '淄博', '自贡', '资阳', '遵义'
]));

function renderHighlightedText(text: string | null | undefined, keyword: string) {
  const source = text || ''
  const term = keyword.trim()
  if (!source || !term) return source

  const sourceLower = source.toLowerCase()
  const termLower = term.toLowerCase()
  const nodes = []
  let cursor = 0
  let index = sourceLower.indexOf(termLower)
  let key = 0

  while (index >= 0) {
    if (index > cursor) nodes.push(source.slice(cursor, index))
    nodes.push(
      <span key={`hit-${key++}`} className="font-semibold text-[#FF1475]">
        {source.slice(index, index + term.length)}
      </span>
    )
    cursor = index + term.length
    index = sourceLower.indexOf(termLower, cursor)
  }

  if (cursor < source.length) nodes.push(source.slice(cursor))
  return nodes.length > 0 ? nodes : source
}

export function Header() {
  const router = useRouter();
  const pathname = usePathname();
  const [showCityDropdown, setShowCityDropdown] = useState(false);
  const [showUserDropdown, setShowUserDropdown] = useState(false);
  const [showMobileMenu, setShowMobileMenu] = useState(false);
  const [loggedIn, setLoggedIn] = useState(false);
  const [nickname, setNickname] = useState("");
  const [avatar, setAvatar] = useState<string | null>(null);
  const [role, setRole] = useState<UserRole | null>(null);
  const [permissionCodes, setPermissionCodes] = useState<string[]>([]);
  const [searchText, setSearchText] = useState("");
  const [showSearchPopover, setShowSearchPopover] = useState(false);
  const [searchHistory, setSearchHistory] = useState<string[]>([]);
  const [trendingItems, setTrendingItems] = useState<SearchTrendingItem[]>([]);
  const [trendingLoading, setTrendingLoading] = useState(false);
  const [trendingError, setTrendingError] = useState("");
  const [suggestionItems, setSuggestionItems] = useState<ActivityVO[]>([]);
  const [suggestionLoading, setSuggestionLoading] = useState(false);
  const [suggestionError, setSuggestionError] = useState("");
  const [currentCity, setCurrentCity] = useState(ALL_CITY_VALUE);
  const [citySearch, setCitySearch] = useState("");
  const citySelectorRef = useRef<HTMLDivElement>(null);
  const searchSelectorRef = useRef<HTMLDivElement>(null);
  const cityOptions = citySearch ? filterCityOptions(citySearch, HOT_CITIES, OTHER_CITIES) : OTHER_CITIES;
  const displayedCity = formatCityDisplay(currentCity);

  const readLocalSearchHistory = () => (
    typeof window === 'undefined' ? [] : readSearchHistoryFromStorage(window.localStorage)
  )

  const writeLocalSearchHistory = (history: string[]) => {
    if (typeof window !== 'undefined') writeSearchHistoryToStorage(window.localStorage, history)
  }

  const loadSearchPopoverData = async () => {
    const localHistory = readLocalSearchHistory()
    setSearchHistory(localHistory)

    if (loggedIn) {
      try {
        const remoteHistory = await getSearchHistory()
        setSearchHistory(remoteHistory)
        writeLocalSearchHistory(remoteHistory)
      } catch {
        setSearchHistory(localHistory)
      }
    }

    setTrendingLoading(true)
    setTrendingError('')
    try {
      setTrendingItems(await getSearchTrending())
    } catch {
      setTrendingItems([])
      setTrendingError('热门榜单暂时不可用')
    } finally {
      setTrendingLoading(false)
    }
  }

  const persistSearchHistory = async (keyword: string) => {
    const normalized = keyword.trim()
    if (!normalized) return

    const localNext = addSearchHistoryTerm(readLocalSearchHistory(), normalized)
    if (loggedIn) {
      try {
        const remoteNext = await addSearchHistory(normalized)
        setSearchHistory(remoteNext)
        writeLocalSearchHistory(remoteNext)
        return
      } catch {
        // 接口异常时仅降级本地历史，不影响搜索跳转。
      }
    }

    setSearchHistory(localNext)
    writeLocalSearchHistory(localNext)
  }

  const handleSearch = async (value = searchText) => {
    const keyword = value.trim()
    await persistSearchHistory(keyword)
    const params = new URLSearchParams()
    if (keyword) params.set('keyword', keyword)
    if (currentCity && currentCity !== ALL_CITY_VALUE) params.set('city', currentCity)
    const qs = params.toString()
    setShowSearchPopover(false)
    router.push(`/search${qs ? `?${qs}` : ''}`)
  }

  const handleClearSearchHistory = async () => {
    if (loggedIn) {
      try {
        await clearSearchHistory()
      } catch {
        // 离线或接口异常时仍清空本地历史，保证前端交互可用。
      }
    }
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify([]))
    }
    setSearchHistory([])
  }

  const handleTrendingClick = async (item: SearchTrendingItem) => {
    const keyword = item.keyword.trim()
    await persistSearchHistory(keyword)
    setShowSearchPopover(false)
    if (String(item.targetType).toUpperCase() === 'EVENT' && item.targetId) {
      router.push(`${item.itemType === 'tour' ? '/tour' : '/activity'}/${item.targetId}`)
      return
    }
    const params = new URLSearchParams()
    params.set('keyword', keyword)
    if (currentCity && currentCity !== ALL_CITY_VALUE) params.set('city', currentCity)
    router.push(`/search?${params.toString()}`)
  }

  const handleSuggestionClick = async (item: ActivityVO) => {
    await persistSearchHistory(searchText.trim() || item.name)
    setShowSearchPopover(false)
    router.push(`${item.itemType === 'tour' ? '/tour' : '/activity'}/${item.id}`)
  }

  const selectCity = (city: string) => {
    setCurrentCity(city)
    setShowCityDropdown(false)
    setCitySearch('')
    if (typeof window !== 'undefined') {
      if (city === ALL_CITY_VALUE) {
        localStorage.removeItem(CITY_KEY)
      } else {
        localStorage.setItem(CITY_KEY, city)
      }
      window.dispatchEvent(new CustomEvent('omni-city-updated', { detail: city }))
    }
    if (pathname.startsWith('/search')) {
      const params = new URLSearchParams(window.location.search)
      if (city === ALL_CITY_VALUE) params.delete('city')
      else params.set('city', city)
      const qs = params.toString()
      router.replace(`/search${qs ? `?${qs}` : ''}`)
    }
  }

  useEffect(() => {
    const checkAuth = () => {
      const auth = isAuthenticated()
      setLoggedIn(auth)
      if (auth) {
        const user = getUser()
        setNickname(user?.nickname || user?.phone || "")
        setAvatar(user?.avatar || null)
        setRole(user?.role || null)
        setPermissionCodes(user?.permissionCodes || [])
      } else {
        setNickname("")
        setAvatar(null)
        setRole(null)
        setPermissionCodes([])
      }
    }
    const handleCityUpdate = (event: Event) => {
      const detail = (event as CustomEvent<string>).detail
      if (detail) setCurrentCity(detail)
    }
    const routeCity = pathname.startsWith('/search') ? new URLSearchParams(window.location.search).get('city') : null
    const storedCity = pathname.startsWith('/search') ? null : resolveStoredCity(localStorage.getItem(CITY_KEY))
    setCurrentCity(resolveRouteCity(pathname, routeCity, storedCity))
    checkAuth()
    // 监听路由变化重新检查
    window.addEventListener("focus", checkAuth)
    window.addEventListener(AUTH_UPDATED_EVENT, checkAuth)
    window.addEventListener('omni-city-updated', handleCityUpdate)
    return () => {
      window.removeEventListener("focus", checkAuth)
      window.removeEventListener(AUTH_UPDATED_EVENT, checkAuth)
      window.removeEventListener('omni-city-updated', handleCityUpdate)
    }
  }, [pathname])

  useEffect(() => {
    setShowMobileMenu(false)
  }, [pathname])

  useEffect(() => {
    if (!showCityDropdown) return
    const handlePointerDown = (event: MouseEvent) => {
      if (!citySelectorRef.current?.contains(event.target as Node)) {
        setShowCityDropdown(false)
        setCitySearch('')
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setShowCityDropdown(false)
        setCitySearch('')
      }
    }
    document.addEventListener('mousedown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [showCityDropdown])

  useEffect(() => {
    if (!showSearchPopover) return
    void loadSearchPopoverData()
    const handlePointerDown = (event: MouseEvent) => {
      if (!searchSelectorRef.current?.contains(event.target as Node)) {
        setShowSearchPopover(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setShowSearchPopover(false)
      }
    }
    document.addEventListener('mousedown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [showSearchPopover, loggedIn])

  useEffect(() => {
    const keyword = searchText.trim()
    if (!showSearchPopover || !keyword) {
      setSuggestionItems([])
      setSuggestionLoading(false)
      setSuggestionError('')
      return
    }

    let cancelled = false
    const timer = window.setTimeout(() => {
      setSuggestionLoading(true)
      setSuggestionError('')
      void listActivities({
        page: 1,
        size: 6,
        keyword,
        city: currentCity && currentCity !== ALL_CITY_VALUE ? currentCity : undefined,
        sort: 'relevance',
      })
        .then(data => {
          if (cancelled) return
          setSuggestionItems(data.records || [])
        })
        .catch(() => {
          if (cancelled) return
          setSuggestionItems([])
          setSuggestionError('搜索暂时不可用')
        })
        .finally(() => {
          if (!cancelled) setSuggestionLoading(false)
        })
    }, 260)

    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [searchText, showSearchPopover, currentCity])

  const handleLogout = () => {
    setShowUserDropdown(false)
    logout()
  }

  const activeSearchKeyword = searchText.trim()

  return (
    <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-md border-b border-gray-100 shadow-[0_2px_10px_-4px_rgba(0,0,0,0.02)]">
      <div className="max-w-[1200px] mx-auto flex items-center h-[72px] px-5 gap-6">
        {/* Logo */}
        <Link href="/" className="flex-shrink-0 flex items-center gap-2">
          <div className="h-[40px] w-[40px] flex items-center justify-center bg-transparent">
            <img
              src="/logo.svg"
              alt="万象"
              className="h-full w-full object-contain"
            />
          </div>
          <span className="text-xl font-bold text-[#ff1268] tracking-wider">万象</span>
        </Link>

        {/* City Selector */}
        <div ref={citySelectorRef} className="relative flex-shrink-0 h-full flex items-center">
          <button
            type="button"
            aria-expanded={showCityDropdown}
            onClick={() => setShowCityDropdown(open => !open)}
            className="flex items-center gap-1 text-sm text-[#111] hover:text-[#ff1268] h-full transition-colors duration-200"
          >
            <MapPin className="w-4 h-4 text-[#ff1268]" />
            <span className="font-medium">{displayedCity}</span>
            <ChevronDown className={`w-3 h-3 transition-transform duration-200 ${showCityDropdown ? 'rotate-180' : ''}`} />
          </button>
          
          {showCityDropdown && (
            <div className="absolute left-0 top-full mt-3 w-[420px] max-w-[calc(100vw-32px)] overflow-hidden rounded-2xl border border-gray-100 bg-white p-5 shadow-[0_20px_60px_-18px_rgba(15,23,42,0.35)] z-[100] animate-in fade-in slide-in-from-top-2 duration-200">
              {/* Search */}
              <div className="relative mb-5">
                <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input 
                  type="text"
                  placeholder="搜索城市..."
                  value={citySearch}
                  onChange={(e) => setCitySearch(e.target.value)}
                  className="w-full bg-gray-50 border border-transparent rounded-full py-2 pl-9 pr-4 text-sm outline-none focus:bg-white focus:border-[#ff1268]/30 focus:ring-4 focus:ring-[#ff1268]/10 transition-all placeholder:text-gray-400"
                />
              </div>

              {/* Current City */}
              <div className="mb-5 flex items-center gap-3">
                <span className="text-xs text-gray-400 font-medium tracking-wider">当前</span>
                <button className="flex items-center justify-center bg-[#ff1268] text-white px-4 py-1.5 rounded-full text-xs font-medium shadow-sm shadow-[#ff1268]/20">
                  <MapPin className="w-3 h-3 mr-1" />
                  {displayedCity}
                </button>
                {currentCity !== ALL_CITY_VALUE && (
                  <button
                    type="button"
                    onClick={() => selectCity(ALL_CITY_VALUE)}
                    className="flex items-center justify-center bg-gray-50 px-4 py-1.5 rounded-full text-xs font-medium text-gray-600 transition-colors hover:bg-[#fff4f8] hover:text-[#ff1268]"
                  >
                    全国
                  </button>
                )}
              </div>

              {/* Hot Cities */}
              {!citySearch && (
                <div className="mb-5">
                  <span className="block text-xs text-gray-400 font-medium tracking-wider mb-3">热门</span>
                  <div className="grid grid-cols-4 gap-2">
                    {HOT_CITIES.map(city => (
                      <button 
                        key={city} 
                        onClick={() => selectCity(city)}
                        className="text-xs text-gray-700 bg-gray-50/80 hover:bg-[#fff4f8] hover:text-[#ff1268] py-2 rounded-xl transition-all hover:scale-105 active:scale-95"
                      >
                        {city}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* All / Filtered Cities */}
              <div className="flex flex-col">
                <span className="block text-xs text-gray-400 font-medium tracking-wider mb-3">
                  {citySearch ? '搜索结果' : '全部'}
                </span>
                <div className="grid max-h-[240px] grid-cols-5 gap-x-1 gap-y-2 overflow-y-auto pr-2 custom-scrollbar">
                  {cityOptions.map((city, index) => (
                    <button 
                      key={`${city}-${index}`} 
                      onClick={() => selectCity(city)}
                      className="text-[13px] text-gray-600 hover:text-[#ff1268] hover:bg-gray-50 py-1.5 rounded-lg transition-colors text-center truncate"
                    >
                      {city}
                    </button>
                  ))}
                  {cityOptions.length === 0 && (
                    <div className="col-span-5 text-center text-xs text-gray-400 py-6">
                      未能找到匹配的城市
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Nav Links */}
        <nav className="ml-2 hidden items-center gap-6 lg:flex">
          <Link
            href="/"
            className={`text-[16px] font-medium transition-colors ${
              pathname === '/' ? 'text-[#ff1268]' : 'text-[#111] hover:text-[#ff1268]'
            }`}
          >
            首页
          </Link>
          <Link
            href="/search"
            className={`text-[16px] font-medium transition-colors ${
              pathname.startsWith('/search') ? 'text-[#ff1268]' : 'text-[#111] hover:text-[#ff1268]'
            }`}
          >
            分类
          </Link>
          <button
            type="button"
            onClick={requestOpenAiTicketFinder}
            className={`inline-flex items-center gap-1.5 border-none bg-transparent p-0 text-[16px] font-medium transition-colors ${
              pathname.startsWith('/ai/ticket-finder') ? 'text-[#ff1268]' : 'text-[#111] hover:text-[#ff1268]'
            }`}
          >
            <Sparkles className="h-4 w-4" />
            AI 找票
          </button>
        </nav>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Search */}
        <div ref={searchSelectorRef} className="relative hidden md:block">
          <form
            onSubmit={(event) => {
              event.preventDefault()
              void handleSearch()
            }}
            className={`flex h-11 w-[340px] items-center rounded-2xl border bg-[#FAFBFD] px-3 transition-all duration-200 lg:w-[380px] ${
              showSearchPopover
                ? 'border-[#FF1475] bg-white shadow-[0_8px_24px_rgba(255,20,117,0.12)]'
                : 'border-gray-200'
            }`}
          >
            <button
              type="submit"
              aria-label="搜索"
              className="flex h-8 w-8 items-center justify-center rounded-full text-gray-400 transition-colors hover:text-[#FF1475]"
            >
              <Search className="h-4 w-4" />
            </button>
            <input
              type="text"
              value={searchText}
              onFocus={() => setShowSearchPopover(true)}
              onChange={(e) => {
                setSearchText(e.target.value)
                setShowSearchPopover(true)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Escape') setShowSearchPopover(false)
              }}
              placeholder="搜索演出、艺人、场馆..."
              className="min-w-0 flex-1 truncate bg-transparent text-sm text-[#111] outline-none placeholder:text-gray-400"
            />
            {searchText ? (
              <button
                type="button"
                aria-label="清除搜索内容"
                onClick={() => setSearchText('')}
                className="ml-2 flex h-7 w-7 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-100 hover:text-[#FF1475]"
              >
                <X className="h-4 w-4" />
              </button>
            ) : (
              <span className="ml-2 shrink-0 whitespace-nowrap rounded-md border border-gray-200 bg-white px-2 py-1 text-[11px] font-medium text-gray-400">
                Enter ↵
              </span>
            )}
          </form>

          {showSearchPopover && (
            <div className="absolute right-0 top-full z-[100] mt-3 w-[420px] max-w-[calc(100vw-32px)] rounded-2xl border border-gray-100 bg-white p-5 shadow-xl animate-in fade-in slide-in-from-top-2 duration-200">
              {activeSearchKeyword ? (
                <section>
                  <div className="mb-2 flex items-center">
                    <h3 className="text-[14px] font-bold text-gray-900">相关推荐</h3>
                  </div>
                  <button
                    type="button"
                    onClick={() => void handleSearch(activeSearchKeyword)}
                    className="mb-3 flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-gray-50"
                  >
                    <Search className="h-4 w-4 shrink-0 text-gray-400" />
                    <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-gray-700">
                      搜索“{renderHighlightedText(activeSearchKeyword, activeSearchKeyword)}”相关结果
                    </span>
                  </button>
                  {suggestionLoading ? (
                    <div className="rounded-xl bg-gray-50 px-4 py-6 text-center text-[13px] text-gray-400">正在匹配相关内容...</div>
                  ) : suggestionItems.length > 0 ? (
                    <div className="space-y-1">
                      {suggestionItems.map(item => {
                        const meta = [item.artistName, item.categoryName, item.venueCity]
                          .filter(Boolean)
                          .join(' · ') || '相关活动'
                        return (
                          <button
                            key={`${item.itemType || 'activity'}-${item.id}`}
                            type="button"
                            onClick={() => void handleSuggestionClick(item)}
                            className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-gray-50"
                          >
                            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gray-50 text-[13px] font-bold text-[#FF1475]">
                              {item.itemType === 'tour' ? '巡' : '演'}
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-[13px] font-semibold text-gray-800">
                                {renderHighlightedText(item.name, activeSearchKeyword)}
                              </span>
                              <span className="mt-0.5 block truncate text-[12px] text-gray-400">
                                {renderHighlightedText(meta, activeSearchKeyword)}
                              </span>
                            </span>
                            <span className="shrink-0 text-[12px] font-semibold text-[#FF1475]">
                              {item.minPrice != null ? `¥${item.minPrice}起` : '待定'}
                            </span>
                          </button>
                        )
                      })}
                    </div>
                  ) : (
                    <div className="rounded-xl bg-gray-50 px-4 py-6 text-center text-[13px] text-gray-400">
                      {suggestionError || `暂无匹配结果，可按 Enter 搜索「${activeSearchKeyword}」`}
                    </div>
                  )}
                </section>
              ) : (
                <>
                  <section>
                    <div className="mb-3 flex items-center justify-between">
                      <h3 className="text-[14px] font-bold text-gray-900">历史搜索</h3>
                      <button
                        type="button"
                        onClick={() => void handleClearSearchHistory()}
                        className="inline-flex items-center gap-1 text-[12px] font-medium text-gray-400 transition-colors hover:text-[#FF1475]"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                        清空
                      </button>
                    </div>
                    {searchHistory.length > 0 ? (
                      <div className="flex flex-wrap gap-2">
                        {searchHistory.map(term => (
                          <button
                            key={term}
                            type="button"
                            onClick={() => {
                              setSearchText(term)
                              void handleSearch(term)
                            }}
                            className="max-w-full truncate rounded-lg bg-gray-50 px-3 py-1.5 text-[13px] text-gray-600 transition-colors hover:bg-gray-100 hover:text-[#FF1475]"
                          >
                            {term}
                          </button>
                        ))}
                      </div>
                    ) : (
                      <div className="rounded-xl bg-gray-50 px-4 py-3 text-[13px] text-gray-400">暂无历史搜索记录</div>
                    )}
                  </section>

                  <section className="mt-5 border-t border-gray-100 pt-5">
                    <div className="mb-3 flex items-center justify-between">
                      <h3 className="inline-flex items-center gap-1.5 text-[14px] font-bold text-gray-900">
                        <Flame className="h-4 w-4 text-[#FF1475]" />
                        热门榜单
                      </h3>
                      <span className="text-[12px] text-gray-400">实时热度更新</span>
                    </div>
                    {trendingLoading ? (
                      <div className="rounded-xl bg-gray-50 px-4 py-6 text-center text-[13px] text-gray-400">榜单加载中...</div>
                    ) : trendingItems.length > 0 ? (
                      <div className="space-y-1">
                        {trendingItems.slice(0, 10).map((item, index) => {
                          const tagMeta = getSearchTrendingTagMeta(item.tagType)
                          const rank = item.rank || index + 1
                          return (
                            <button
                              key={`${item.id}-${item.keyword}`}
                              type="button"
                              onClick={() => void handleTrendingClick(item)}
                              className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-[#FFF0F5]"
                            >
                              <span className={`w-5 shrink-0 text-center text-[15px] font-extrabold ${rank <= 3 ? 'text-[#FF1475]' : 'text-gray-400'}`}>
                                {rank}
                              </span>
                              <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-gray-700">{item.keyword}</span>
                              {tagMeta.label && (
                                <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-[11px] font-bold ${tagMeta.className}`}>
                                  {tagMeta.label}
                                </span>
                              )}
                            </button>
                          )
                        })}
                      </div>
                    ) : (
                      <div className="rounded-xl bg-gray-50 px-4 py-6 text-center text-[13px] text-gray-400">
                        {trendingError || '暂无热门榜单'}
                      </div>
                    )}
                  </section>
                </>
              )}
            </div>
          )}
        </div>
        {loggedIn && <NotificationBell />}

        {/* User / Login */}
        <div className="relative flex-shrink-0 h-full flex items-center" onMouseLeave={() => setShowUserDropdown(false)}>
          {loggedIn ? (
            <button
              onMouseEnter={() => setShowUserDropdown(true)}
              onClick={() => setShowUserDropdown(open => !open)}
              className="flex items-center gap-1 text-sm text-[#111] hover:text-[#ff1268] bg-transparent border-none cursor-pointer outline-none h-full"
            >
              {avatar ? (
                <SafeImage src={avatar} alt={nickname || '用户头像'} className="h-8 w-8 rounded-full object-cover" />
              ) : (
                <User className="w-5 h-5" />
              )}
              <span className="max-w-[80px] truncate">{nickname}</span>
              <ChevronDown className="w-3 h-3" />
            </button>
          ) : (
            <button
              onMouseEnter={() => setShowUserDropdown(true)}
              onClick={() => router.push("/login?ru=/")}
              className="flex items-center gap-1 text-sm text-[#111] hover:text-[#ff1268] flex-shrink-0 bg-transparent border-none cursor-pointer outline-none h-full"
            >
              <User className="w-5 h-5" />
              <span>登录</span>
              <ChevronDown className="w-3 h-3" />
            </button>
          )}

          {showUserDropdown && (
            <div
              className="absolute right-0 top-[60px] z-50 min-w-[140px] rounded-2xl border border-gray-100 bg-white/95 backdrop-blur-xl shadow-[0_12px_40px_-10px_rgba(0,0,0,0.1)] py-2 text-center overflow-hidden"
            >
              {!loggedIn && (
                <>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/login?ru=/") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    登录
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/register") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    注册
                  </button>
                </>
              )}
              {loggedIn && (
                <>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/profile") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    个人中心
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/profile/attendees") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    实名观演人
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/tickets") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    我的票夹
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/subscriptions") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    想看与提醒
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/history") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    浏览记录
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/orders") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    订单管理
                  </button>
                  <button
                    onClick={() => { setShowUserDropdown(false); router.push("/waitlist") }}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                  >
                    我的候补
                  </button>
                  {canEnterConsole(role, permissionCodes) && (
                    <button
                      onClick={() => { setShowUserDropdown(false); router.push(getDefaultConsolePath(role, permissionCodes)) }}
                      className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-[#fff4f8] hover:text-[#ff1268] border-none bg-transparent outline-none transition-colors"
                    >
                      进入后台
                    </button>
                  )}
                  <button
                    onClick={handleLogout}
                    className="block w-full px-5 py-3 text-[13px] font-medium text-gray-700 hover:bg-gray-50 hover:text-red-500 border-none bg-transparent outline-none border-t border-gray-50 transition-colors mt-1 pt-3"
                  >
                    退出登录
                  </button>
                </>
              )}
            </div>
          )}
        </div>

        {/* Mobile Menu */}
        <button
          type="button"
          aria-label={showMobileMenu ? '关闭菜单' : '打开菜单'}
          aria-expanded={showMobileMenu}
          onClick={() => setShowMobileMenu(open => !open)}
          className="flex h-10 w-10 items-center justify-center rounded-lg text-[#111] transition-colors hover:bg-[#fff0f5] hover:text-[#ff1268] lg:hidden"
        >
          {showMobileMenu ? <X className="h-6 w-6" /> : <Menu className="h-6 w-6" />}
        </button>
      </div>
      {showMobileMenu && (
        <div className="border-t border-gray-100 bg-white px-5 py-3 shadow-sm lg:hidden">
          <nav className="mx-auto flex max-w-[1200px] flex-col gap-1">
            <Link
              href="/"
              className="rounded-lg px-3 py-2.5 text-[14px] font-medium text-[#111] hover:bg-[#fff4f8] hover:text-[#ff1268]"
            >
              首页
            </Link>
            <Link
              href="/search"
              className="rounded-lg px-3 py-2.5 text-[14px] font-medium text-[#111] hover:bg-[#fff4f8] hover:text-[#ff1268]"
            >
              分类
            </Link>
            <button
              type="button"
              onClick={requestOpenAiTicketFinder}
              className="inline-flex items-center gap-2 rounded-lg border-none bg-[#fff7fa] px-3 py-2.5 text-[14px] font-semibold text-[#e6005c] hover:bg-[#fff0f5]"
            >
              <Sparkles className="h-4 w-4" />
              AI 找票
            </button>
          </nav>
        </div>
      )}
    </header>
  );
}
