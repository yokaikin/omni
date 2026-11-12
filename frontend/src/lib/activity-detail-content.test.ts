import assert from 'node:assert/strict'
import { test } from 'node:test'

import { buildActivityDetailTabs } from './activity-detail-content.ts'
import type { ActivityDetailVO } from '@/types/api'

const detail = {
  activity: {
    id: 8,
    categoryId: 3,
    artistId: 9,
    name: 'LPL 英雄联盟职业联赛总决赛 深圳站',
    description: '冠军争夺战将在深圳开赛，现场包含开幕式、决赛对阵与颁奖环节。',
    poster: '/seed-posters/activity-08.jpg',
    realNameRequired: true,
    ticketTransferAllowed: false,
    status: 1,
    createTime: '2026-06-01T00:00:00',
  },
  category: {
    id: 3,
    name: '体育赛事',
    icon: null,
    sort: 1,
    status: 1,
  },
  artist: {
    id: 9,
    name: 'LPL',
    avatar: null,
    description: null,
  },
  artists: [{ artistId: 9, name: 'LPL', sort: 1, isPrimary: true, roleType: '主办', visibility: 'public' }],
  sessions: [
    {
      session: {
        id: 88,
        activityId: 8,
        venueId: 6,
        startTime: '2026-07-20T19:30:00',
        endTime: '2026-07-20T22:00:00',
        status: 1,
      },
      venue: {
        id: 6,
        name: '深圳湾体育中心',
        city: '深圳',
        address: '深圳市南山区滨海大道',
        capacity: 12000,
        status: 1,
      },
      ticketTypes: [
        { id: 101, sessionId: 88, name: '内场票', price: 880, totalStock: 100, remainStock: 12, status: 1 },
        { id: 102, sessionId: 88, name: '看台票', price: 380, totalStock: 500, remainStock: 0, status: 1 },
      ],
    },
  ],
} satisfies ActivityDetailVO

test('builds project details from activity copy, venue, session and ticket data', () => {
  const tabs = buildActivityDetailTabs(detail)

  const projectText = tabs.project.sections.flatMap(section => section.items).join('\n')
  assert.match(projectText, /冠军争夺战将在深圳开赛/)
  assert.match(projectText, /LPL/)
  assert.match(projectText, /深圳湾体育中心/)
  assert.match(projectText, /2026-07-20 19:30/)
  assert.match(projectText, /内场票 880 元/)
})

test('builds purchase and attendance notices from activity rules', () => {
  const tabs = buildActivityDetailTabs(detail)

  const purchaseText = tabs.purchase.sections.flatMap(section => section.items).join('\n')
  assert.match(purchaseText, /本项目实行实名购票/)
  assert.match(purchaseText, /每个账号限购 2 张/)
  assert.match(purchaseText, /不支持转赠/)

  const attendanceText = tabs.attendance.sections.flatMap(section => section.items).join('\n')
  assert.match(attendanceText, /人、票、证信息保持一致/)
  assert.match(attendanceText, /深圳湾体育中心/)
  assert.match(attendanceText, /建议提前 60 分钟到达/)
})
