'use client'

import { useEffect, useMemo, useState } from 'react'
import { useRouter, usePathname } from 'next/navigation'
import Link from 'next/link'
import { SafeImage } from '@/components/SafeImage'
import { getUser, isAuthenticated, logout, updateStoredUser } from '@/lib/auth'
import { getUserInfo } from '@/lib/api'
import { canAccessConsolePath, canEnterConsole, getConsoleBrandLabel, getConsoleRoleLabel, getDefaultConsolePath, isPlatformAdminRole } from '@/lib/console-auth'
import { isConsolePathAllowedForRole } from '@/lib/console-paths'
import { CalendarDays, ChevronDown, LayoutDashboard, LogOut, Menu, ShieldCheck, ShoppingCart, Sliders, UserCircle2, Users, X } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

type ConsoleMenuRole = 'admin' | 'organizer' | 'organizer_admin' | 'support'

export interface ConsoleSubItem {
  href: string
  label: string
  icon?: LucideIcon
  roles?: ConsoleMenuRole[]
}

export interface ConsoleMenuGroup {
  id: string
  title: string
  icon: LucideIcon
  roles?: ConsoleMenuRole[]
  children: ConsoleSubItem[]
}

type VisibleConsoleMenuGroup = Omit<ConsoleMenuGroup, 'children'> & {
  children: ConsoleSubItem[]
}

export const consoleMenuGroups: ConsoleMenuGroup[] = [
  {
    id: 'dashboard',
    title: '概览与看板',
    icon: LayoutDashboard,
    children: [
      { href: '/console', label: '工作台概览' },
    ],
  },
  {
    id: 'events',
    title: '演出与票务管理',
    icon: CalendarDays,
    roles: ['admin', 'organizer'],
    children: [
      { href: '/console/activities', label: '活动发布管理' },
      { href: '/console/activities/history', label: '以往活动' },
      { href: '/console/tours', label: '巡演草稿管理' },
      { href: '/console/sessions', label: '场次与票档管理' },
      { href: '/console/venue', label: '场馆记录管理' },
      { href: '/console/artists', label: '艺人档案管理' },
    ],
  },
  {
    id: 'orders',
    title: '订单与履约中心',
    icon: ShoppingCart,
    children: [
      { href: '/console/orders', label: '订单综合查询' },
      { href: '/console/check-in', label: '现场入场核验' },
      { href: '/console/refunds', label: '退款售后审核' },
    ],
  },
  {
    id: 'operations',
    title: '运营、客服与审核',
    icon: ShieldCheck,
    roles: ['admin', 'organizer_admin', 'support'],
    children: [
      { href: '/console/organizer-ops', label: '运营工作台' },
      { href: '/console/activity-engagement', label: '评价问答管理' },
      { href: '/console/organizer-applications', label: '主办方入驻审核和管理' },
      { href: '/console/venue/applications', label: '场馆资料审核' },
      { href: '/console/artists/pending', label: '艺人档案审核' },
      { href: '/console/risk-resolutions', label: '恢复售票审核' },
      { href: '/console/station-config-reviews', label: '站点变更审核' },
      { href: '/console/customer-service/sessions', label: '客服会话工作台' },
      { href: '/console/risk-cases', label: '风险案例管理' },
    ],
  },
  {
    id: 'system',
    title: '系统、安全与财务',
    icon: Sliders,
    roles: ['admin'],
    children: [
      { href: '/console/rbac/roles', label: '角色权限配置' },
      { href: '/console/accounts', label: '平台账号管理', icon: Users, roles: ['admin'] },
      { href: '/console/reconciliation', label: '日结对账报表' },
      { href: '/console/exception-tasks', label: '异常补偿任务' },
      { href: '/console/search-ops', label: '搜索索引运维' },
      { href: '/console/audit-logs', label: '操作审计日志' },
    ],
  },
]

const ORGANIZER_CONSOLE_LABELS: Record<string, string> = {
  '/console/activities': '我的活动管理',
  '/console/tours': '我的巡演草稿',
  '/console/sessions': '我的场次与票档',
  '/console/venue': '我的场馆记录',
  '/console/artists': '我的艺人档案',
  '/console/orders': '我的订单查询',
  '/console/check-in': '现场入场核验',
  '/console/refunds': '主办方退款处理',
}

const CONSOLE_COMPATIBILITY_PATHS = [
  '/console/accounts',
  '/console/support-accounts',
  '/console/organizer-admins',
  '/console/support-conversations',
]

function normalizeConsoleMenuRole(role: string | null | undefined): ConsoleMenuRole | null {
  if (isPlatformAdminRole(role)) return 'admin'
  if (role === 'organizer' || role === 'organizer_admin' || role === 'support') return role
  return null
}

function canUseConsoleMenuRole(roles: ConsoleMenuRole[] | undefined, role: string | null | undefined) {
  if (!roles) return true
  const menuRole = normalizeConsoleMenuRole(role)
  return menuRole ? roles.includes(menuRole) : false
}

function getOrganizerConsoleSubItem(child: ConsoleSubItem): ConsoleSubItem {
  return {
    ...child,
    label: ORGANIZER_CONSOLE_LABELS[child.href] || child.label,
  }
}

function canAccessConsoleMenuChild(child: ConsoleSubItem, role: string, permissionCodes: string[]) {
  if (!canUseConsoleMenuRole(child.roles, role)) return false
  if (role === 'organizer') return isConsolePathAllowedForRole(role, child.href)
  return canAccessConsolePath(child.href, permissionCodes)
}

export function buildVisibleConsoleMenuGroups(role: string, permissionCodes: string[]): VisibleConsoleMenuGroup[] {
  return consoleMenuGroups
    .filter(group => canUseConsoleMenuRole(group.roles, role))
    .map(group => {
      const children = group.children
        .map(child => role === 'organizer' ? getOrganizerConsoleSubItem(child) : child)
        .filter(child => canAccessConsoleMenuChild(child, role, permissionCodes))

      return { ...group, children }
    })
    .filter(group => group.children.length > 0)
}

function isConsoleMenuHrefActive(pathname: string, href: string) {
  if (pathname === href) return true
  if (href === '/console') return false
  return pathname.startsWith(`${href}/`)
}

function getActiveConsoleSubItemHref(groups: VisibleConsoleMenuGroup[], pathname: string) {
  let bestMatch = ''

  for (const group of groups) {
    for (const child of group.children) {
      if (pathname === child.href) return child.href
      if (child.href === '/console') continue
      if (pathname.startsWith(`${child.href}/`) && child.href.length > bestMatch.length) {
        bestMatch = child.href
      }
    }
  }

  return bestMatch
}

function findActiveConsoleGroupId(groups: VisibleConsoleMenuGroup[], pathname: string) {
  let activeGroupId = ''
  let activeHrefLength = -1

  for (const group of groups) {
    for (const child of group.children) {
      if (isConsoleMenuHrefActive(pathname, child.href) && child.href.length > activeHrefLength) {
        activeGroupId = group.id
        activeHrefLength = child.href.length
      }
    }
  }

  return activeGroupId
}

function canOpenConsolePath(role: string, pathname: string, permissionCodes: string[]) {
  if (role === 'organizer') return isConsolePathAllowedForRole(role, pathname)
  if (!canAccessConsolePath(pathname, permissionCodes)) return false
  if (pathname === '/console' || isConsoleMenuHrefActive(pathname, '/console/profile')) return true
  if (CONSOLE_COMPATIBILITY_PATHS.some(path => isConsoleMenuHrefActive(pathname, path))) return true

  return buildVisibleConsoleMenuGroups(role, permissionCodes)
    .some(group => group.children.some(child => isConsoleMenuHrefActive(pathname, child.href)))
}

function getConsoleRedirectPath(role: string, permissionCodes: string[]) {
  const defaultPath = getDefaultConsolePath(role, permissionCodes)
  if (canOpenConsolePath(role, defaultPath, permissionCodes)) return defaultPath

  const firstVisibleChild = buildVisibleConsoleMenuGroups(role, permissionCodes)[0]?.children[0]
  return firstVisibleChild?.href || '/console'
}

export default function ConsoleLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter()
  const pathname = usePathname()
  const [nickname, setNickname] = useState('')
  const [avatar, setAvatar] = useState('')
  const [role, setRole] = useState('')
  const [permissionCodes, setPermissionCodes] = useState<string[]>([])
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [openGroups, setOpenGroups] = useState<string[]>([])
  const [checking, setChecking] = useState(true)
  const [redirecting, setRedirecting] = useState(false)

  const visibleMenuGroups = useMemo(() => {
    if (checking || !role) return []
    return buildVisibleConsoleMenuGroups(role, permissionCodes)
  }, [checking, role, permissionCodes])

  const activeMenuHref = useMemo(() => {
    return getActiveConsoleSubItemHref(visibleMenuGroups, pathname)
  }, [pathname, visibleMenuGroups])

  const activeGroupId = useMemo(() => {
    return findActiveConsoleGroupId(visibleMenuGroups, pathname)
  }, [pathname, visibleMenuGroups])

  useEffect(() => {
    if (!activeGroupId) return
    setOpenGroups(groups => (groups.includes(activeGroupId) ? groups : [...groups, activeGroupId]))
  }, [activeGroupId])

  useEffect(() => {
    if (!isAuthenticated()) { router.push('/login'); return }
    let active = true
    setRedirecting(false)
    ;(async () => {
      const cached = getUser()
      try {
        const latest = await getUserInfo()
        if (!active) return
        const latestPermissions = latest.permissionCodes || []
        if (!canEnterConsole(latest.role, latestPermissions)) {
          router.push('/')
          return
        }
        updateStoredUser({ nickname: latest.nickname, role: latest.role, permissionCodes: latestPermissions })
        setNickname(latest.nickname || latest.phone || '')
        setAvatar(latest.avatar || '')
        setRole(latest.role)
        setPermissionCodes(latestPermissions)
        if (!canOpenConsolePath(latest.role, pathname, latestPermissions)) {
          setRedirecting(true)
          router.replace(getConsoleRedirectPath(latest.role, latestPermissions))
          return
        }
      } catch {
        if (!active) return
        const cachedPermissions = cached?.permissionCodes || []
        if (!cached || !canEnterConsole(cached.role, cachedPermissions)) {
          router.push('/')
          return
        }
        const cachedRole = cached.role || ''
        setNickname(cached.nickname || cached.phone || '')
        setAvatar(cached.avatar || '')
        setRole(cachedRole)
        setPermissionCodes(cachedPermissions)
        if (!canOpenConsolePath(cachedRole, pathname, cachedPermissions)) {
          setRedirecting(true)
          router.replace(getConsoleRedirectPath(cachedRole, cachedPermissions))
          return
        }
      } finally {
        if (active) setChecking(false)
      }
    })()
    return () => {
      active = false
    }
  }, [router, pathname])

  const handleLogout = () => logout()
  const toggleGroup = (groupId: string) => {
    setOpenGroups(groups => groups.includes(groupId)
      ? groups.filter(item => item !== groupId)
      : [...groups, groupId],
    )
  }
  const roleReady = !checking && Boolean(role)
  const brandLabel = roleReady ? getConsoleBrandLabel(role, permissionCodes) : '后台'
  const roleLabel = roleReady ? getConsoleRoleLabel(role, permissionCodes) : '校验中'
  const profileActive = isConsoleMenuHrefActive(pathname, '/console/profile')

  return (
    <div className="min-h-screen bg-gray-50 flex font-sans">
      {/* 侧边栏 */}
      <aside className={`w-[260px] bg-white border-r border-gray-200 flex-shrink-0 flex flex-col ${sidebarOpen ? 'fixed inset-y-0 left-0 z-50' : 'hidden'} lg:flex lg:relative transition-all duration-300`}>
        <div className="h-[64px] px-6 flex items-center justify-between">
          <Link href="/console" className="text-[18px] font-bold text-[var(--omni-brand)] tracking-tight">{brandLabel}</Link>
          <button onClick={() => setSidebarOpen(false)} className="lg:hidden text-gray-500 hover:text-gray-900 transition-colors" aria-label="关闭后台菜单">
            <X className="w-5 h-5" />
          </button>
        </div>
        <nav className="flex-1 px-3 py-4 overflow-y-auto custom-scrollbar">
          <div className="space-y-1">
            {visibleMenuGroups.map(group => {
              const GroupIcon = group.icon
              const groupActive = group.id === activeGroupId
              const expanded = openGroups.includes(group.id)
              return (
                <div key={group.id}>
                  <button
                    type="button"
                    onClick={() => toggleGroup(group.id)}
                    className={`flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-[14px] transition-all duration-200 ${
                      groupActive
                        ? 'bg-[var(--omni-brand)]/10 text-[var(--omni-brand)] font-semibold'
                        : 'text-gray-600 font-medium hover:bg-gray-100 hover:text-gray-900'
                    }`}
                  >
                    <GroupIcon className="h-5 w-5 shrink-0" />
                    <span className="flex-1 text-left">{group.title}</span>
                    <ChevronDown className={`h-4 w-4 shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`} />
                  </button>
                  {expanded && (
                    <div className="mt-1 space-y-1 pb-1">
                      {group.children.map(child => {
                        const ChildIcon = child.icon
                        const active = child.href === activeMenuHref
                        return (
                          <Link
                            key={child.href}
                            href={child.href}
                            onClick={() => setSidebarOpen(false)}
                            className={`flex items-center gap-2 rounded-lg py-2 pl-10 pr-3 text-[13px] leading-5 transition-all duration-200 ${
                              active
                                ? 'bg-[var(--omni-brand)]/10 text-[var(--omni-brand)] font-semibold'
                                : 'text-gray-600 font-medium hover:bg-gray-100 hover:text-gray-900'
                            }`}
                          >
                            {ChildIcon ? (
                              <ChildIcon className="h-4 w-4 shrink-0" />
                            ) : (
                              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-current opacity-60" />
                            )}
                            <span className="truncate">{child.label}</span>
                          </Link>
                        )
                      })}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </nav>
        <div className="p-4 border-t border-gray-200 bg-gray-50/50">
          <div className="flex flex-col mb-4 px-2">
            <span className="text-[12px] text-gray-500 font-medium mb-2">{roleLabel}</span>
            <div className="flex items-center gap-2.5">
              {avatar ? (
                <SafeImage src={avatar} alt="用户头像" className="w-8 h-8 rounded-full object-cover flex-shrink-0" />
              ) : (
                <div className="w-8 h-8 rounded-full bg-gray-200 flex items-center justify-center text-gray-500 flex-shrink-0">
                  <UserCircle2 className="w-5 h-5" />
                </div>
              )}
              <span className="text-[14px] text-gray-900 font-semibold truncate">{nickname}</span>
            </div>
          </div>
          <div className="flex flex-col gap-1">
            <Link
              href="/console/profile"
              onClick={() => setSidebarOpen(false)}
              className={`flex items-center gap-2.5 px-3 py-2 rounded-lg text-[13px] transition-all ${
                profileActive
                  ? 'bg-[var(--omni-brand)]/10 text-[var(--omni-brand)] font-semibold'
                  : 'font-medium text-gray-600 hover:bg-gray-200 hover:text-gray-900'
              }`}
            >
              <UserCircle2 className="w-4 h-4" />
              个人中心
            </Link>
            <button
              onClick={handleLogout}
              className="flex items-center gap-2.5 px-3 py-2 rounded-lg text-[13px] font-medium text-gray-600 hover:bg-gray-200 hover:text-gray-900 transition-all outline-none w-full text-left"
            >
              <LogOut className="w-4 h-4" />
              退出登录
            </button>
          </div>
        </div>
      </aside>

      {/* 遮罩 */}
      {sidebarOpen && (
        <div className="fixed inset-0 bg-black/50 z-40 lg:hidden" onClick={() => setSidebarOpen(false)} />
      )}

      {/* 主内容 */}
      <div className="flex-1 flex flex-col min-w-0 bg-gray-50">
        {/* 顶部栏 */}
        <header className="bg-white h-[64px] border-b border-gray-200 flex items-center px-6 flex-shrink-0 sticky top-0 z-30">
          <button onClick={() => setSidebarOpen(true)} className="lg:hidden mr-4 p-2 -ml-2 text-gray-500 hover:text-gray-900 rounded-lg hover:bg-gray-100 transition-colors outline-none" aria-label="打开后台菜单">
            <Menu className="w-5 h-5" />
          </button>
          <div className="flex-1" />
          <Link
            href="/"
            className="flex items-center justify-center px-4 py-1.5 rounded-md border border-gray-200 text-[13px] font-medium text-gray-600 hover:text-[var(--omni-brand)] hover:border-[var(--omni-brand)]/30 hover:bg-[var(--omni-brand)]/10 transition-all bg-white"
          >
            返回前台
          </Link>
        </header>
        <main className={`flex-1 min-w-0 ${pathname === '/console/customer-service/sessions' ? 'overflow-hidden p-0' : 'overflow-y-auto p-6 sm:p-8'}`}>
          <div className={`w-full max-w-[1680px] min-w-0 mx-auto ${pathname === '/console/customer-service/sessions' ? 'h-full' : ''}`}>
            {checking || redirecting ? <div className="text-[14px] text-[#666] flex items-center justify-center py-20">正在校验后台权限...</div> : children}
          </div>
        </main>
      </div>
    </div>
  )
}
