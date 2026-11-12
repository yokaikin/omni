const PLATFORM_OPS_PERMISSIONS = ['compensation.execute', 'reconcile.view', 'audit.view']

type PlatformOpsError = {
  source?: string | null
  message?: string | null
}

export type PlatformOpsHealthItem = {
  key: string
  label: string
  status: 'ok' | 'degraded'
  message: string
}

type InfrastructureHealth = {
  items?: Array<{
    key?: string | null
    label?: string | null
    status?: string | null
    message?: string | null
  }> | null
} | null | undefined

export type InfrastructureHealthItem = {
  key: string
  label: string
  status: 'ok' | 'degraded' | 'not_configured'
  statusLabel: string
  message: string
}

const PLATFORM_OPS_HEALTH_SOURCES = [
  { key: 'ticket', label: '票务摘要链路' },
  { key: 'payment', label: '退款摘要链路' },
  { key: 'grab', label: '抢票摘要链路' },
  { key: 'workbench', label: '工作台摘要链路' },
] as const

const INFRASTRUCTURE_HEALTH_SOURCES = [
  { key: 'nacos', label: 'Nacos 注册中心' },
  { key: 'redis', label: 'Redis 缓存' },
  { key: 'rabbitmq', label: 'RabbitMQ 消息队列' },
  { key: 'seata', label: 'Seata 事务协调器' },
] as const

export function canLoadPlatformOpsSummary(_role: string | null | undefined, permissionCodes: string[] = []): boolean {
  return PLATFORM_OPS_PERMISSIONS.every(permission => permissionCodes.includes(permission))
}

export function buildPlatformOpsHealthItems(errors: PlatformOpsError[] = []): PlatformOpsHealthItem[] {
  const knownSources = new Set<string>(PLATFORM_OPS_HEALTH_SOURCES.map(item => item.key))
  const errorBySource = new Map<string, string>()
  const unknownErrors: PlatformOpsHealthItem[] = []

  for (const error of errors) {
    const source = (error.source || 'unknown').trim().toLowerCase() || 'unknown'
    const message = (error.message || '').trim() || '摘要链路待核对'

    if (knownSources.has(source)) {
      errorBySource.set(source, message)
      continue
    }

    unknownErrors.push({
      key: source,
      label: '其他摘要链路',
      status: 'degraded',
      message,
    })
  }

  const items = PLATFORM_OPS_HEALTH_SOURCES.map(({ key, label }) => {
    const message = errorBySource.get(key)
    return {
      key,
      label,
      status: message ? 'degraded' : 'ok',
      message: message || '摘要链路正常',
    } satisfies PlatformOpsHealthItem
  })

  return [...items, ...dedupeHealthItems(unknownErrors)]
}

export function buildInfrastructureHealthItems(health: InfrastructureHealth): InfrastructureHealthItem[] {
  const sourceByKey = new Map<string, string>(INFRASTRUCTURE_HEALTH_SOURCES.map(item => [item.key, item.label]))
  const rawItems = health?.items || []

  if (rawItems.length === 0) {
    return INFRASTRUCTURE_HEALTH_SOURCES.map(item => ({
      key: item.key,
      label: item.label,
      status: 'not_configured',
      statusLabel: '未配置',
      message: '基础设施探针未配置',
    }))
  }

  return rawItems.map((item, index) => {
    const key = (item.key || `infrastructure-${index + 1}`).trim().toLowerCase() || `infrastructure-${index + 1}`
    const label = (item.label || sourceByKey.get(key) || '其他基础设施探针').trim()
    const status = normalizeInfrastructureStatus(item.status)
    return {
      key,
      label,
      status,
      statusLabel: formatInfrastructureStatusLabel(status),
      message: (item.message || '').trim() || fallbackInfrastructureMessage(status),
    }
  })
}

export function getInfrastructureHealthClassName(status: InfrastructureHealthItem['status']): string {
  if (status === 'ok') return 'bg-[#f0fff4] text-[#15803d]'
  if (status === 'not_configured') return 'bg-gray-100 text-gray-500'
  return 'bg-[#fff7e6] text-[#ad6800]'
}

function normalizeInfrastructureStatus(status?: string | null): InfrastructureHealthItem['status'] {
  if (status === 'ok' || status === 'degraded' || status === 'not_configured') {
    return status
  }
  return 'degraded'
}

function formatInfrastructureStatusLabel(status: InfrastructureHealthItem['status']): string {
  if (status === 'ok') return '正常'
  if (status === 'not_configured') return '未配置'
  return '异常'
}

function fallbackInfrastructureMessage(status: InfrastructureHealthItem['status']): string {
  if (status === 'ok') return '基础设施探针正常'
  if (status === 'not_configured') return '基础设施探针未配置'
  return '基础设施状态待核对'
}

function dedupeHealthItems(items: PlatformOpsHealthItem[]): PlatformOpsHealthItem[] {
  const usedKeys = new Set<string>()
  return items.map((item) => {
    if (!usedKeys.has(item.key)) {
      usedKeys.add(item.key)
      return item
    }
    let index = 2
    let key = `${item.key}-${index}`
    while (usedKeys.has(key)) {
      index += 1
      key = `${item.key}-${index}`
    }
    usedKeys.add(key)
    return { ...item, key }
  })
}
