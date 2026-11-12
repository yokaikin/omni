import type { ActivityDetailVO } from '@/types/api'

export interface ActivityDetailTabContent {
  label: string
  sections: Array<{
    title: string
    items: string[]
  }>
}

export interface ActivityDetailTabs {
  project: ActivityDetailTabContent
  purchase: ActivityDetailTabContent
  attendance: ActivityDetailTabContent
}

export type ActivityDetailTabKey = keyof ActivityDetailTabs

function compactText(value?: string | null) {
  return value?.replace(/\s+/g, ' ').trim() || ''
}

function unique(values: string[]) {
  return Array.from(new Set(values.filter(Boolean)))
}

function formatDateTime(value?: string | null) {
  if (!value) return '时间待公布'
  return value.replace('T', ' ').slice(0, 16)
}

function formatPrice(value?: number | null) {
  if (value == null) return '价格待公布'
  return Number.isInteger(value) ? `${value} 元` : `${value.toFixed(2)} 元`
}

function getArtistNames(detail: ActivityDetailVO) {
  const names = detail.artists?.map(item => compactText(item.name)) ?? []
  const fallback = compactText(detail.artist?.name)
  return unique(fallback ? [...names, fallback] : names)
}

function getVenueText(detail: ActivityDetailVO) {
  const venues = unique(detail.sessions.map(item => {
    const city = compactText(item.venue?.city)
    const name = compactText(item.venue?.name)
    const address = compactText(item.venue?.address)
    if (!city && !name && !address) return ''
    const location = [city, name].filter(Boolean).join(' · ')
    return address ? `${location}，地址：${address}` : location
  }))
  return venues.length ? venues : ['场馆信息以页面展示和票面信息为准。']
}

function getTicketSummary(detail: ActivityDetailVO) {
  const tickets = detail.sessions.flatMap(session => session.ticketTypes)
  if (tickets.length === 0) return ['票档信息待公布，请关注后续开售安排。']
  return unique(tickets.map(ticket => `${ticket.name} ${formatPrice(ticket.price)}`))
}

function getSessionSummary(detail: ActivityDetailVO) {
  if (detail.sessions.length === 0) return ['场次时间待公布，请关注页面更新。']
  return detail.sessions.map(item => {
    const venueName = compactText(item.venue?.name) || '场馆待公布'
    const ticketText = item.ticketTypes.length
      ? item.ticketTypes.map(ticket => `${ticket.name} ${formatPrice(ticket.price)}`).join('、')
      : '票档待公布'
    return `${formatDateTime(item.session.startTime)} ${venueName}，票档：${ticketText}`
  })
}

function buildProjectContent(detail: ActivityDetailVO): ActivityDetailTabContent {
  const description = compactText(detail.activity.description)
    || `${detail.activity.name} 为${detail.category?.name || '现场演出'}项目，具体演出内容以现场安排为准。`
  const artists = getArtistNames(detail)

  return {
    label: '项目详情',
    sections: [
      {
        title: '演出介绍',
        items: [description],
      },
      {
        title: '阵容与类型',
        items: [
          `演出阵容：${artists.length ? artists.join('、') : '以现场公布为准'}`,
          `项目类型：${detail.category?.name || '暂未分类'}`,
        ],
      },
      {
        title: '场馆信息',
        items: getVenueText(detail),
      },
      {
        title: '场次与票档',
        items: getSessionSummary(detail),
      },
    ],
  }
}

function buildPurchaseContent(detail: ActivityDetailVO): ActivityDetailTabContent {
  const realNameCopy = detail.activity.realNameRequired
    ? '本项目实行实名购票，购票人需准确填写实际入场观演人身份信息。'
    : '本项目暂未强制实名购票，仍建议购票时填写真实有效的联系人信息。'
  const limitCopy = '不设置个人限购，数量以场次库存和下单校验为准。'
  const transferCopy = detail.activity.ticketTransferAllowed
    ? '本项目支持转赠，转赠规则和可操作时间以票夹页面提示为准。'
    : '本项目不支持转赠，请确认观演人信息后再提交订单。'

  return {
    label: '购票须知',
    sections: [
      {
        title: '购票规则',
        items: [realNameCopy, limitCopy, transferCopy],
      },
      {
        title: '票档说明',
        items: [
          `当前可选票档：${getTicketSummary(detail).join('、')}`,
          '票价、库存和座位分配以提交订单时的页面展示为准。',
        ],
      },
      {
        title: '支付与出票',
        items: [
          '订单提交后请在支付时限内完成支付，超时未支付订单将自动取消。',
          '支付成功后可在订单详情或票夹查看电子票信息。',
        ],
      },
    ],
  }
}

function buildAttendanceContent(detail: ActivityDetailVO): ActivityDetailTabContent {
  const venueNames = unique(detail.sessions.map(item => compactText(item.venue?.name)))
  const venueCopy = venueNames.length
    ? `请前往 ${venueNames.join('、')} 入场，具体入口以现场指引和票面信息为准。`
    : '请按票面和现场指引前往对应入口入场。'
  const credentialCopy = detail.activity.realNameRequired
    ? '入场时需确保人、票、证信息保持一致，并携带与电子票信息一致的身份证件。'
    : '入场时请出示电子票二维码，并按现场要求配合身份核验。'

  return {
    label: '观演须知',
    sections: [
      {
        title: '入场凭证',
        items: [credentialCopy, venueCopy],
      },
      {
        title: '到场时间',
        items: [
          '建议提前 60 分钟到达现场，预留取票、验票、安检和寻位时间。',
          '演出开始后可能根据现场秩序分批入场，请服从工作人员安排。',
        ],
      },
      {
        title: '现场要求',
        items: [
          '请勿携带易燃易爆、管制器具、专业摄影摄像设备等现场禁止物品。',
          '请保管好随身物品，观演期间遵守场馆秩序和安全提示。',
        ],
      },
    ],
  }
}

export function buildActivityDetailTabs(detail: ActivityDetailVO): ActivityDetailTabs {
  return {
    project: buildProjectContent(detail),
    purchase: buildPurchaseContent(detail),
    attendance: buildAttendanceContent(detail),
  }
}
