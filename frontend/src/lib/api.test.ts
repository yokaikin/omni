import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError, addSupportNote, approveOrganizerApplication, batchOrganizerOnsaleSummary, batchReviewRefunds, claimExceptionTask, closeExceptionTask, closeSupportConversation, createActivityReview, createAlipayQrPay, createOrganizerAdminAccount, createOrganizerOpsFollowUp, createReconciliationBatch, createWaitlistEntry, deactivateOrganizerAdminAccount, deleteOrganizerAdminAccount, escalateSupportConversation, exportUserAttendees, getActivityMarketing, getCheckInOverview, getGrabOpsSummary, getGrabProgress, getGrabVisibleStock, getPlatformOpsSummary, getRbacUserPermissions, getReconciliationBatchDetail, getSeatCraftDraft, getSessionSeatLayout, getSupportConversationContext, ignoreReconciliationDifference, listActivities, listAdminActivityEngagements, listAdminActivityQuestions, listAdminActivityReviewReports, listAdminActivityReviews, listCheckInRecords, listEnabledSupportAgents, listExceptionTasks, listOperationAuditLogs, listOrganizerAdminAccounts, listOrganizerApplications, listOrganizerOpsAssignments, listOrganizerOpsFollowUps, listOrganizers, listRbacPermissions, listRbacRoles, listReconciliationBatches, listSupportAudits, listSupportNotes, listSupportQuickReplies, manualCheckInTicket, moderateAdminActivityQuestion, moderateAdminActivityReview, moderateAdminActivityReviewReport, notifyActivityBuyers, rejectCloseSupportConversation, rejectOrganizerApplication, replyAdminActivityQuestion, reportActivityReview, resolveExceptionTask, resolveReconciliationDifference, revokeOrganizer, searchRbacUsers, sendSupportMessage, startSupportConversation, transferSupportConversation, updateActivityMarketing, updateAdminActivityQuestion, updateAdminActivityReportStatus, updateAdminActivityReviewStatus, updateOrganizerAdminAccount, updateOrganizerOpsAssignment, updateRbacRolePermissions, updateRbacUserPermissionOverrides, updateSupportTags, uploadOrganizerApplicationMaterial } from './api.ts'

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

test('parameter validation errors use Chinese identifier wording', async () => {
  await assert.rejects(() => getActivityMarketing(0), { message: '活动编号不正确' })
  await assert.rejects(() => getCheckInOverview(0), { message: '场次编号不正确' })
  await assert.rejects(() => listCheckInRecords({ sessionId: 0 }), { message: '场次编号不正确' })
  await assert.rejects(() => getSessionSeatLayout(0, 1), { message: '场次编号不正确' })
  await assert.rejects(() => getSessionSeatLayout(1, 0), { message: '用户编号不正确' })
  await assert.rejects(() => getSeatCraftDraft('session', 0), { message: 'SeatCraft 归属编号不正确' })
})

test('allows alipay qr pay response that takes longer than the default request timeout', async () => {
  const originalFetch = globalThis.fetch
  let aborted = false
  globalThis.fetch = (async (_input: RequestInfo | URL, init?: RequestInit) => {
    init?.signal?.addEventListener('abort', () => {
      aborted = true
    })
    await wait(6000)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: {
        orderId: 9001,
        orderNo: 'O1',
        amount: 200,
        qrCode: 'qr-code',
        qrCodeUrl: 'https://example.invalid/qr',
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await createAlipayQrPay(9001)

    assert.equal(aborted, false)
    assert.equal(result.orderId, 9001)
    assert.equal(result.qrCode, 'qr-code')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('allows support AI replies that take longer than the default request timeout', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  let aborted = false
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    init?.signal?.addEventListener('abort', () => {
      aborted = true
    })
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    await wait(6000)
    const url = String(input)
    const data = url.endsWith('/messages')
      ? { id: 2, conversationId: 88, senderType: 'USER', content: '然后如何转赠' }
      : { id: 88, status: 'OPEN', sourceType: 'AI' }
    return new Response(JSON.stringify({ code: 200, message: 'success', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const conversation = await startSupportConversation({ subject: '转赠', initialMessage: '如何转赠' })
    const message = await sendSupportMessage(88, '然后如何转赠')

    assert.equal(aborted, false)
    assert.equal(conversation.id, 88)
    assert.equal(message.conversationId, 88)
    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/user/support/conversations', 'POST', JSON.stringify({ subject: '转赠', initialMessage: '如何转赠' })],
      ['/api/user/support/conversations/88/messages', 'POST', JSON.stringify({ content: '然后如何转赠' })],
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads support conversation context through conversation scoped endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: {
        conversationId: 1001,
        user: { userId: 2004, nickname: '普通用户', phoneMask: '139****0001' },
        orders: [],
        refunds: [],
        tickets: [],
        waitlist: [],
        grabRequests: [],
        notifications: [],
        errors: [],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await getSupportConversationContext(1001)

    assert.equal(requestedUrl, '/api/user/support/agent/conversations/1001/context')
    assert.equal(result.conversationId, 1001)
    assert.equal(result.user.userId, 2004)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads grab progress by request id', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: { requestId: 'GRAB1', status: 'WAITING', queueRank: 3 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await getGrabProgress('GRAB1')

    assert.equal(requestedUrl, '/api/grab/requests/GRAB1/progress')
    assert.equal(result.status, 'WAITING')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads visible stock with ticket type query params', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: { sessionId: 101, ticketTypes: [], snapshotTime: '2026-05-29T12:00:00.000Z' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await getGrabVisibleStock(101, [1, 2])

    assert.equal(requestedUrl, '/api/grab/sessions/101/stock-visible?ticketTypeIds=1%2C2')
    assert.equal(result.sessionId, 101)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads activity marketing overview through admin endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: {
        activityId: 101,
        activityName: '测试演唱会',
        rule: { enabled: true, discountType: 'FULL_REDUCTION', thresholdAmount: 300, discountAmount: 30 },
        funnelSteps: [],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await getActivityMarketing(101)

    assert.equal(requestedUrl, '/api/ticket/admin/activities/101/marketing')
    assert.equal(result.activityId, 101)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('updates activity marketing without leaking body user id', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  let requestedMethod = ''
  let requestedBody = ''
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requestedUrl = String(input)
    requestedMethod = init?.method ?? 'GET'
    requestedBody = String(init?.body ?? '')
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: {
        activityId: 101,
        activityName: '测试演唱会',
        rule: { enabled: true, discountType: 'FULL_REDUCTION', thresholdAmount: 300, discountAmount: 30 },
        funnelSteps: [],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await updateActivityMarketing(101, {
      userId: 9999,
      enabled: true,
      couponName: '开票满减',
      discountType: 'FULL_REDUCTION',
      thresholdAmount: 300,
      discountAmount: 30,
      maxCouponCount: 500,
      perUserLimit: 1,
    })

    assert.equal(requestedUrl, '/api/ticket/admin/activities/101/marketing')
    assert.equal(requestedMethod, 'PUT')
    assert.equal(requestedBody.includes('userId'), false)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('notifies activity buyers through activity admin endpoint without leaking body user id', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  let requestedMethod = ''
  let requestedBody = ''
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requestedUrl = String(input)
    requestedMethod = init?.method ?? 'GET'
    requestedBody = String(init?.body ?? '')
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: {
        activityId: 101,
        activityName: '测试演唱会',
        paidOrderCount: 3,
        notifiedUserCount: 2,
        notificationCount: 3,
        skippedOrderCount: 0,
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await notifyActivityBuyers(101, {
      userId: 9999,
      confirmNotify: true,
      content: '演出入场时间有调整，请查看订单详情。',
    })

    assert.equal(result.notificationCount, 3)
    assert.equal(requestedUrl, '/api/ticket/admin/activities/101/buyer-notifications')
    assert.equal(requestedMethod, 'POST')
    assert.equal(requestedBody, JSON.stringify({
      confirmNotify: true,
      content: '演出入场时间有调整，请查看订单详情。',
    }))
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads grab operations summary for platform dashboard', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: {
        failureReasons: [{ reason: '票档售罄', count: 7 }],
        waitlist: { totalCount: 10, paidCount: 4, conversionRate: 0.4 },
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await getGrabOpsSummary()

    assert.equal(requestedUrl, '/api/grab/admin/ops-summary')
    assert.equal(result.failureReasons[0].count, 7)
    assert.equal(result.waitlist.conversionRate, 0.4)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads platform operations summary through user console aggregate endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: {
        generatedAt: '2026-06-09T09:00:00',
        funnelSteps: [{ key: 'paid', label: '支付', count: 7 }],
        ticket: { orderCount: 10, paidOrderCount: 7, hotActivities: [] },
        refund: { totalCount: 2, abnormalCount: 1 },
        grab: { failureReasons: [], waitlist: { totalCount: 10, paidCount: 4, conversionRate: 0.4 } },
        workbench: { pendingExceptionCount: 1, latestBatch: null, latestAudit: null },
        infrastructureHealth: {
          items: [{ key: 'nacos', label: 'Nacos 注册中心', status: 'ok', message: 'Nacos 控制台可达' }],
        },
        errors: [],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await getPlatformOpsSummary()

    assert.equal(requestedUrl, '/api/user/console/ops-summary')
    assert.equal(result.funnelSteps[0].key, 'paid')
    assert.equal(result.workbench.pendingExceptionCount, 1)
    assert.equal(result.infrastructureHealth?.items[0].label, 'Nacos 注册中心')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads exception tasks through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: [{ id: 1, taskType: 'abnormal_refund', businessNo: 'RF1', severity: 'high', status: 'pending', reason: '退款结果未知' }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await listExceptionTasks()

    assert.equal(requestedUrl, '/api/user/console/exception-tasks')
    assert.equal(result[0].taskType, 'abnormal_refund')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('creates exception task through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: { id: 9, taskType: 'refund_failed', businessNo: 'BIZ-1', orderNo: 'ORD-1', severity: 'high', status: 'pending', reason: '退款失败' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const { createExceptionTask } = await import('./api.ts')
    const created = await createExceptionTask({
      taskType: 'refund_failed',
      businessNo: 'BIZ-1',
      orderNo: 'ORD-1',
      severity: 'high',
      reason: '退款失败',
    })

    assert.equal(requested[0].url, '/api/user/console/exception-tasks')
    assert.equal(requested[0].method, 'POST')
    assert.equal(requested[0].body, JSON.stringify({
      taskType: 'refund_failed',
      businessNo: 'BIZ-1',
      orderNo: 'ORD-1',
      severity: 'high',
      reason: '退款失败',
    }))
    assert.equal(created.id, 9)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('updates exception task status through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const url = String(input)
    const status = url.endsWith('/claim') ? 'processing' : url.endsWith('/resolve') ? 'resolved' : 'closed'
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: { id: 9, taskType: 'refund_failed', businessNo: 'BIZ-1', severity: 'high', status, result: '已处理' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const claimed = await claimExceptionTask(9)
    const resolved = await resolveExceptionTask(9, '已处理')
    const closed = await closeExceptionTask(9, '重复任务')

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/user/console/exception-tasks/9/claim', 'POST', ''],
      ['/api/user/console/exception-tasks/9/resolve', 'POST', JSON.stringify({ result: '已处理' })],
      ['/api/user/console/exception-tasks/9/close', 'POST', JSON.stringify({ result: '重复任务' })],
    ])
    assert.equal(claimed.status, 'processing')
    assert.equal(resolved.status, 'resolved')
    assert.equal(closed.status, 'closed')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads and creates reconciliation batches through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: init?.method === 'POST'
        ? { id: 2, batchNo: 'REC20260602-00000002', bizDate: '2026-06-02', sourceType: 'local', status: 'generated' }
        : [{ id: 1, batchNo: 'REC20260601-00000001', bizDate: '2026-06-01', sourceType: 'local', status: 'generated' }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const batches = await listReconciliationBatches()
    const created = await createReconciliationBatch('2026-06-02')

    assert.equal(requested[0].url, '/api/user/console/reconciliation/batches')
    assert.equal(requested[1].url, '/api/user/console/reconciliation/batches')
    assert.equal(requested[1].method, 'POST')
    assert.equal(requested[1].body, JSON.stringify({ bizDate: '2026-06-02' }))
    assert.equal(batches[0].batchNo, 'REC20260601-00000001')
    assert.equal(created.batchNo, 'REC20260602-00000002')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('updates reconciliation differences through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const url = String(input)
    const status = url.endsWith('/resolve') ? 'resolved' : 'ignored'
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: { id: 4, batchNo: 'REC20260603-363F0A8A', diffType: 'amount_mismatch', businessNo: 'RF20260603001', diffAmount: 6, status },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const resolved = await resolveReconciliationDifference('REC20260603-363F0A8A', 4)
    const ignored = await ignoreReconciliationDifference('REC20260603-363F0A8A', 4)

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/user/console/reconciliation/batches/REC20260603-363F0A8A/differences/4/resolve', 'POST', ''],
      ['/api/user/console/reconciliation/batches/REC20260603-363F0A8A/differences/4/ignore', 'POST', ''],
    ])
    assert.equal(resolved.status, 'resolved')
    assert.equal(ignored.status, 'ignored')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('submits refund batch review through the atomic admin endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: [
        { id: 1001, orderId: 9001, orderNo: 'O-1', userId: 2004, paymentId: 8001, refundNo: 'RF-1', amount: 188, reason: '重复支付', status: 1, reviewerId: 7, reviewNote: '系统核销异常统一批量退款', alipayRefundNo: null, createTime: '2026-06-01T10:00:00', reviewTime: '2026-06-01T10:01:00', refundTime: null },
      ],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await batchReviewRefunds([1001, 1002], 'APPROVE', '系统核销异常统一批量退款')

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/payment/refunds/admin/batch-review', 'POST', JSON.stringify({
        ids: [1001, 1002],
        action: 'APPROVE',
        reason: '系统核销异常统一批量退款',
      })],
    ])
    assert.equal(result[0].id, 1001)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('creates activity review with paid order id', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  let requestedBody = ''
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requestedUrl = String(input)
    requestedBody = String(init?.body ?? '')
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: { id: 77, activityId: 10, userId: 2004, orderId: 9001, rating: 5, status: 0 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await createActivityReview(10, { orderId: 9001, rating: 5, content: '观演体验很好' })

    assert.equal(requestedUrl, '/api/ticket/activities/10/reviews')
    assert.equal(requestedBody, JSON.stringify({ orderId: 9001, rating: 5, content: '观演体验很好' }))
    assert.equal(result.status, 0)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('reports an activity review', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  let requestedBody = ''
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requestedUrl = String(input)
    requestedBody = String(init?.body ?? '')
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: { id: 9, reviewId: 77, activityId: 10, userId: 2005, reason: '包含辱骂内容', status: 'PENDING' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await reportActivityReview(10, 77, '包含辱骂内容')

    assert.equal(requestedUrl, '/api/ticket/activities/10/reviews/77/reports')
    assert.equal(requestedBody, JSON.stringify({ reason: '包含辱骂内容' }))
    assert.equal(result.status, 'PENDING')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads and moderates admin activity engagement endpoints', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    requested.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const data = url.includes('/review-reports')
      ? [{ id: 9, reviewId: 77, status: 'PENDING' }]
      : url.includes('/questions')
        ? [{ id: 3, activityId: 10, userId: 2004, content: '几点检票', status: 'ANSWERED' }]
        : [{ id: 77, activityId: 10, userId: 2004, rating: 5, status: 0 }]
    return new Response(JSON.stringify({ code: 200, message: '成功', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await listAdminActivityReviews({ activityId: 10, status: 0 })
    await moderateAdminActivityReview(77, 'APPROVE')
    await listAdminActivityReviewReports('PENDING')
    await moderateAdminActivityReviewReport(9, 'RESOLVE', '已隐藏')
    await listAdminActivityQuestions({ activityId: 10, status: 'PENDING' })
    await moderateAdminActivityQuestion(3, { action: 'ANSWER', answer: '19:00 开始检票' })

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/ticket/admin/activity-engagement/reviews?activityId=10&status=0', 'GET', ''],
      ['/api/ticket/admin/activity-engagement/reviews/77/moderation', 'POST', JSON.stringify({ action: 'APPROVE' })],
      ['/api/ticket/admin/activity-engagement/review-reports?status=PENDING', 'GET', ''],
      ['/api/ticket/admin/activity-engagement/review-reports/9/moderation', 'POST', JSON.stringify({ action: 'RESOLVE', note: '已隐藏' })],
      ['/api/ticket/admin/activity-engagement/questions?activityId=10&status=PENDING', 'GET', ''],
      ['/api/ticket/admin/activity-engagement/questions/3/moderation', 'POST', JSON.stringify({ action: 'ANSWER', answer: '19:00 开始检票' })],
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads activity-scoped engagement management endpoints for drawer workflow', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    requested.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const data = url.includes('/activity-engagements')
      ? { records: [{ targetId: 10, targetType: 'ACTIVITY', activityName: '测试演出', pendingReviewCount: 1 }], total: 1, size: 20, current: 2, pages: 1 }
      : url.includes('/reports')
        ? [{ id: 9, reviewId: 77, activityId: 10, status: 'PENDING' }]
        : url.includes('/questions')
          ? { id: 3, activityId: 10, userId: 2004, content: '几点检票', status: 'ANSWERED', replyIdentity: 'OFFICIAL_SUPPORT' }
          : { id: 77, activityId: 10, userId: 2004, rating: 5, status: 1 }
    return new Response(JSON.stringify({ code: 200, message: '成功', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await listAdminActivityEngagements({ page: 2, size: 20, keyword: '周杰伦', itemType: 'TOUR', todoOnly: true })
    await listAdminActivityReviews(10, { itemType: 'ACTIVITY', status: 0 })
    await updateAdminActivityReviewStatus(10, 77, { itemType: 'ACTIVITY', status: 1 })
    await updateAdminActivityReviewStatus(10, 77, { itemType: 'ACTIVITY', status: 2, reason: '评价包含违规内容' })
    await listAdminActivityQuestions(10, { itemType: 'ACTIVITY', status: 'PENDING' })
    await replyAdminActivityQuestion(10, 3, { itemType: 'ACTIVITY', answer: '19:00 开始检票', replyIdentity: 'OFFICIAL_SUPPORT' })
    await updateAdminActivityQuestion(10, 3, { itemType: 'ACTIVITY', answer: '请以现场公告为准', status: 'HIDDEN', reason: '主办方回复需改写' })
    await listAdminActivityReviewReports(10, { itemType: 'ACTIVITY', status: 'PENDING' })
    await updateAdminActivityReportStatus(10, 9, { itemType: 'ACTIVITY', action: 'RESOLVE', reason: '确认违规并隐藏' })

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/ticket/admin/activity-engagements?page=2&size=20&keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&itemType=TOUR&todoOnly=true', 'GET', ''],
      ['/api/ticket/admin/activities/10/reviews?itemType=ACTIVITY&status=0', 'GET', ''],
      ['/api/ticket/admin/activities/10/reviews/77/status?itemType=ACTIVITY', 'PUT', JSON.stringify({ status: 1 })],
      ['/api/ticket/admin/activities/10/reviews/77/status?itemType=ACTIVITY', 'PUT', JSON.stringify({ status: 2, reason: '评价包含违规内容' })],
      ['/api/ticket/admin/activities/10/questions?itemType=ACTIVITY&status=PENDING', 'GET', ''],
      ['/api/ticket/admin/activities/10/questions/3/reply?itemType=ACTIVITY', 'POST', JSON.stringify({ answer: '19:00 开始检票', replyIdentity: 'OFFICIAL_SUPPORT' })],
      ['/api/ticket/admin/activities/10/questions/3?itemType=ACTIVITY', 'PUT', JSON.stringify({ answer: '请以现场公告为准', status: 'HIDDEN', reason: '主办方回复需改写' })],
      ['/api/ticket/admin/activities/10/reports?itemType=ACTIVITY&status=PENDING', 'GET', ''],
      ['/api/ticket/admin/activities/10/reports/9/status?itemType=ACTIVITY', 'PUT', JSON.stringify({ action: 'RESOLVE', reason: '确认违规并隐藏' })],
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads reconciliation batch detail through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: {
        batch: { id: 1, batchNo: 'REC20260603-363F0A8A', bizDate: '2026-06-03', sourceType: 'local', status: 'generated' },
        details: [{ id: 3, batchNo: 'REC20260603-363F0A8A', businessNo: 'PAY20260603001', businessType: 'payment', expectedAmount: 128, actualAmount: 128, status: 'matched' }],
        differences: [{ id: 4, batchNo: 'REC20260603-363F0A8A', diffType: 'amount_mismatch', businessNo: 'RF20260603001', diffAmount: 6, status: 'open' }],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const detail = await getReconciliationBatchDetail('REC20260603-363F0A8A')

    assert.equal(requestedUrl, '/api/user/console/reconciliation/batches/REC20260603-363F0A8A')
    assert.equal(detail.batch.batchNo, 'REC20260603-363F0A8A')
    assert.equal(detail.details[0].businessNo, 'PAY20260603001')
    assert.equal(detail.differences[0].diffType, 'amount_mismatch')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads and updates rbac roles through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const url = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: url.endsWith('/rbac/permissions')
          ? [{ code: 'rbac.manage', name: '角色权限管理', description: '管理后台角色授权' }]
          : url.includes('/rbac/roles/') && url.endsWith('/permissions')
            ? undefined
            : [{ code: 'platform_super_admin', name: '平台超管', status: 1, permissionCodes: ['rbac.manage'] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const roles = await listRbacRoles()
    const permissions = await listRbacPermissions()
    await updateRbacRolePermissions('support_manager', ['support.account.manage'])

    assert.equal(requested[0].url, '/api/user/console/rbac/roles')
    assert.equal(requested[1].url, '/api/user/console/rbac/permissions')
    assert.equal(requested[2].url, '/api/user/console/rbac/roles/support_manager/permissions')
    assert.equal(requested[2].method, 'PUT')
    assert.equal(requested[2].body, JSON.stringify({ permissionCodes: ['support.account.manage'] }))
    assert.equal(roles[0].code, 'platform_super_admin')
    assert.equal(permissions[0].code, 'rbac.manage')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('manages organizer admin accounts through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: init?.method === 'POST'
        ? { id: 12, phone: '13900000004', nickname: '平台主办方运营员', role: 'organizer_admin', status: 1 }
        : [{ id: 11, phone: '13900000003', nickname: '平台主办方运营员', role: 'organizer_admin', status: 1 }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const accounts = await listOrganizerAdminAccounts()
    const created = await createOrganizerAdminAccount({ phone: '13900000004', nickname: '平台主办方运营员', password: 'admin123' })
    await updateOrganizerAdminAccount(11, { phone: '13900000003', nickname: '平台主办方运营员', status: 1 })
    await deactivateOrganizerAdminAccount(11)
    await deleteOrganizerAdminAccount(11)

    assert.equal(requested[0].url, '/api/user/console/organizer-admins')
    assert.equal(requested[1].url, '/api/user/console/organizer-admins')
    assert.equal(requested[1].method, 'POST')
    assert.equal(requested[1].body, JSON.stringify({ phone: '13900000004', nickname: '平台主办方运营员', password: 'admin123' }))
    assert.equal(requested[2].url, '/api/user/console/organizer-admins/11')
    assert.equal(requested[2].method, 'PUT')
    assert.equal(requested[2].body, JSON.stringify({ phone: '13900000003', nickname: '平台主办方运营员', status: 1 }))
    assert.equal(requested[3].url, '/api/user/console/organizer-admins/11/deactivate')
    assert.equal(requested[3].method, 'POST')
    assert.equal(requested[4].url, '/api/user/console/organizer-admins/11')
    assert.equal(requested[4].method, 'DELETE')
    assert.equal(accounts[0].role, 'organizer_admin')
    assert.equal(created.id, 12)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads and updates rbac user permission overrides through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const url = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: url.includes('/permissions')
        ? {
            userId: 7,
            nickname: '张晓',
            phone: '13900000007',
            role: 'support',
            effectiveRole: 'support_agent',
            baseRoleName: '普通客服',
            inheritedPermissionCodes: ['support.conversation.view'],
            allowPermissionCodes: ['order.view'],
            denyPermissionCodes: [],
            effectivePermissionCodes: ['support.conversation.view', 'order.view'],
          }
        : [{ userId: 7, nickname: '张晓', phone: '13900000007', role: 'support', effectiveRole: 'support_agent', baseRoleName: '普通客服' }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const users = await searchRbacUsers('张晓')
    const permissions = await getRbacUserPermissions(7)
    await updateRbacUserPermissionOverrides(7, {
      allowPermissionCodes: ['order.view'],
      denyPermissionCodes: ['audit.view'],
      reason: '临时支援订单查询',
    })

    assert.equal(requested[0].url, '/api/user/console/rbac/users?keyword=%E5%BC%A0%E6%99%93')
    assert.equal(requested[1].url, '/api/user/console/rbac/users/7/permissions')
    assert.equal(requested[2].url, '/api/user/console/rbac/users/7/permissions')
    assert.equal(requested[2].method, 'PUT')
    assert.equal(requested[2].body, JSON.stringify({
      allowPermissionCodes: ['order.view'],
      denyPermissionCodes: ['audit.view'],
      reason: '临时支援订单查询',
    }))
    assert.equal(users[0].baseRoleName, '普通客服')
    assert.deepEqual(permissions.effectivePermissionCodes, ['support.conversation.view', 'order.view'])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('manages organizer ops follow-up records through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const url = String(input)
    const data = url.endsWith('/follow-ups')
      ? init?.method === 'POST'
        ? { id: 8, organizerUserId: 3003, operatorId: 2002, followType: 'phone', content: '已电话确认资质材料', nextFollowAt: '2026-06-09T10:00:00' }
        : [{ id: 7, organizerUserId: 3003, operatorId: 2002, followType: 'note', content: '等待补充授权书' }]
      : [{ organizerUserId: 3003, assignedOperatorId: 2002, riskLevel: 'watch', status: 'pending_material' }]
    return new Response(JSON.stringify({ code: 200, message: '成功', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const assignments = await listOrganizerOpsAssignments()
    await updateOrganizerOpsAssignment(3003, {
      assignedOperatorId: 2002,
      riskLevel: 'high',
      status: 'restricted',
      nextFollowAt: '2026-06-10T09:30:00',
    })
    const followUps = await listOrganizerOpsFollowUps(3003)
    const created = await createOrganizerOpsFollowUp(3003, {
      followType: 'phone',
      content: '已电话确认资质材料',
      nextFollowAt: '2026-06-09T10:00:00',
    })

    assert.equal(requested[0].url, '/api/user/console/organizer-ops/assignments')
    assert.equal(requested[1].url, '/api/user/console/organizer-ops/assignments/3003')
    assert.equal(requested[1].method, 'PUT')
    assert.equal(requested[1].body, JSON.stringify({
      assignedOperatorId: 2002,
      riskLevel: 'high',
      status: 'restricted',
      nextFollowAt: '2026-06-10T09:30:00',
    }))
    assert.equal(requested[2].url, '/api/user/console/organizer-ops/assignments/3003/follow-ups')
    assert.equal(requested[3].url, '/api/user/console/organizer-ops/assignments/3003/follow-ups')
    assert.equal(requested[3].method, 'POST')
    assert.equal(requested[3].body, JSON.stringify({
      followType: 'phone',
      content: '已电话确认资质材料',
      nextFollowAt: '2026-06-09T10:00:00',
    }))
    assert.equal(assignments[0].riskLevel, 'watch')
    assert.equal(followUps[0].content, '等待补充授权书')
    assert.equal(created.id, 8)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads operation audit logs through user console endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: [{ id: 1, operatorId: 7, operatorRole: 'platform_super_admin', action: 'rbac.role_permission.update', targetType: 'rbac_role', success: true }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await listOperationAuditLogs({
      operatorId: 7,
      action: 'rbac.role_permission.update',
      targetType: 'rbac_role',
      success: true,
      traceId: 'trace-abc',
      limit: 50,
    })

    assert.equal(requestedUrl, '/api/user/console/audit-logs?operatorId=7&action=rbac.role_permission.update&targetType=rbac_role&success=true&traceId=trace-abc&limit=50')
    assert.equal(result[0].operatorRole, 'platform_super_admin')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('loads check-in overview and records through ticket admin endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: string[] = []
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requested.push(String(input))
    const data = requested.length === 1
      ? { sessionId: 910002, totalTickets: 10, checkedInCount: 6, unusedCount: 4, failedCount: 1, duplicateCount: 2 }
      : [{ id: 1, requestId: 'REQ-1', result: 'SUCCESS', ticketNo: 'ET1' }]
    return new Response(JSON.stringify({ code: 200, message: '成功', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await getCheckInOverview(910002)
    await listCheckInRecords({ sessionId: 910002, result: 'SUCCESS', page: 1, size: 20 })

    assert.equal(requested[0], '/api/ticket/admin/check-in/overview?sessionId=910002')
    assert.equal(requested[1], '/api/ticket/admin/check-in/records?sessionId=910002&result=SUCCESS&page=1&size=20')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('submits manual emergency check-in through ticket admin endpoint', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    return new Response(JSON.stringify({
      code: 200,
      message: '成功',
      data: { id: 1, requestId: 'REQ-1', result: 'SUCCESS', ticketNo: 'ET1', sessionId: 910002 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await manualCheckInTicket({ sessionId: 910002, entryCode: ' 1234567890123456 ', deviceCode: ' GATE-1 ' })

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/ticket/admin/check-in/manual', 'POST', JSON.stringify({
        sessionId: 910002,
        entryCode: '1234567890123456',
        deviceCode: 'GATE-1',
      })],
    ])
    assert.equal(result.result, 'SUCCESS')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('exposes notification preferences without technical delivery fields', async () => {
  const api = await import('./api.ts') as unknown as {
    getNotificationPreferences?: () => Promise<Array<Record<string, unknown>>>
  }
  const loader = api.getNotificationPreferences

  assert.equal(typeof loader, 'function')
  if (typeof loader !== 'function') return

  const preferences = await loader()

  assert.deepEqual(preferences, [
    {
      channel: 'IN_APP',
      label: '站内通知',
      enabled: true,
      locked: true,
      statusText: '已开启',
      description: '订单、候补、抢票、退款、改期和客服回复都会通过站内消息提醒。',
    },
    {
      channel: 'SMS',
      label: '短信通知',
      enabled: false,
      locked: true,
      statusText: '暂不可用',
      description: '当前仅提供站内消息提醒；短信通知开放后可在这里开启。',
    },
  ])
  assert.equal(preferences.some((item) => 'provider' in item || 'eventId' in item || 'dlq' in item), false)
})

test('uses support conversation operation endpoints', async () => {
  const originalFetch = globalThis.fetch
  const requested: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requested.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    const url = String(input)
    const data = url.endsWith('/notes')
      ? init?.method === 'POST'
        ? { id: 9, conversationId: 88, content: '内部备注' }
        : []
      : url.endsWith('/audits') || url.endsWith('/quick-replies') || url.endsWith('/accounts')
        ? []
        : { id: 88, status: 'ASSIGNED' }
    return new Response(JSON.stringify({ code: 200, message: '成功', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await listSupportNotes(88)
    await addSupportNote(88, '内部备注')
    await updateSupportTags(88, ['REFUND'])
    await listSupportQuickReplies()
    await listEnabledSupportAgents()
    await transferSupportConversation(88, 31, '需要专员处理')
    await escalateSupportConversation(88, '疑似异常退款')
    await listSupportAudits(88)
    await closeSupportConversation(88, '已解决')
    await rejectCloseSupportConversation(88, '继续处理')

    assert.deepEqual(requested.map(item => [item.url, item.method, item.body]), [
      ['/api/user/support/agent/conversations/88/notes', 'GET', ''],
      ['/api/user/support/agent/conversations/88/notes', 'POST', JSON.stringify({ content: '内部备注' })],
      ['/api/user/support/agent/conversations/88/tags', 'PUT', JSON.stringify({ tags: ['REFUND'] })],
      ['/api/user/support/agent/quick-replies', 'GET', ''],
      ['/api/user/support/agent/accounts', 'GET', ''],
      ['/api/user/support/agent/conversations/88/transfer', 'POST', JSON.stringify({ targetAgentId: 31, reason: '需要专员处理' })],
      ['/api/user/support/agent/conversations/88/escalate', 'POST', JSON.stringify({ reason: '疑似异常退款' })],
      ['/api/user/support/agent/conversations/88/audits', 'GET', ''],
      ['/api/user/support/agent/conversations/88/close', 'POST', JSON.stringify({ reason: '已解决' })],
      ['/api/user/support/conversations/88/close/reject', 'POST', JSON.stringify({ reason: '继续处理' })],
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('exports user attendees through the masked export endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: {
        fileName: '实名观演人-2004.csv',
        contentType: 'text/csv;charset=UTF-8',
        content: '姓名,证件类型,脱敏证件号\nAlice,ID_CARD,110***********011\n',
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    const result = await exportUserAttendees()

    assert.equal(requestedUrl, '/api/user/attendees/export')
    assert.equal(result.fileName, '实名观演人-2004.csv')
    assert.equal(result.content.includes('110***********011'), true)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('serializes activity search filters into query params', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: { records: [], total: 0, size: 20, current: 1, pages: 0 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await listActivities({
      page: 1,
      size: 20,
      keyword: '周杰伦',
      city: '上海',
      dateFrom: '2026-06-01',
      dateTo: '2026-06-30',
      minPrice: 180,
      maxPrice: 880,
      saleStatus: 'on_sale',
      seatMapOnly: true,
      realNameRequired: false,
      sort: 'price_asc',
    })

    assert.equal(requestedUrl, '/api/ticket/activities?page=1&size=20&keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&city=%E4%B8%8A%E6%B5%B7&dateFrom=2026-06-01&dateTo=2026-06-30&minPrice=180&maxPrice=880&saleStatus=on_sale&isSupportSeat=true&realNameRequired=false&sort=price_asc')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('serializes ES activity search keyword city flags and sort params', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: { records: [], total: 0, size: 20, current: 2, pages: 0 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await listActivities({
      page: 2,
      size: 20,
      keyword: '周杰伦',
      city: '北京',
      saleStatus: 'on_sale',
      seatMapOnly: true,
      realNameRequired: true,
      sort: 'price_asc',
    })

    assert.equal(
      requestedUrl,
      '/api/ticket/activities?page=2&size=20&keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&city=%E5%8C%97%E4%BA%AC&saleStatus=on_sale&isSupportSeat=true&realNameRequired=true&sort=price_asc'
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('serializes support-seat search filter with the public query alias', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      code: 200,
      message: 'success',
      data: { records: [], total: 0, size: 20, current: 1, pages: 0 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await listActivities({
      page: 1,
      size: 20,
      isSupportSeat: true,
    })

    assert.equal(requestedUrl, '/api/ticket/activities?page=1&size=20&isSupportSeat=true')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('localizes english API errors before displaying them', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = (async () => {
    return new Response(JSON.stringify({
      code: 409,
      message: 'ticket type sold out',
      data: null,
    }), { status: 409, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  try {
    await assert.rejects(
      () => createWaitlistEntry({ sessionId: 101, ticketTypeId: 202, quantity: 1, attendeeIds: [501] }),
      (error) => error instanceof ApiError && error.message === '当前票档已售罄',
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})

function jsonResponse(data: unknown) {
  return new Response(JSON.stringify({ code: 200, message: '成功', data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

test('builds organizer application pagination query with enum filters', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    requestedUrl = String(input)
    return jsonResponse({ records: [], total: 0, size: 10, current: 2, pages: 0 })
  }) as typeof fetch

  try {
    const result = await listOrganizerApplications({ page: 2, size: 10, keyword: '星河', status: 'PENDING', subjectType: 'enterprise' })
    assert.equal(requestedUrl, '/api/user/organizer/applications/admin?page=2&size=10&keyword=%E6%98%9F%E6%B2%B3&status=PENDING&subjectType=enterprise')
    assert.equal(result.current, 2)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('keeps the no-argument organizer application call compatible with existing array consumers', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = (async () => jsonResponse({ records: [{ id: 1 }], total: 1, size: 10, current: 1, pages: 1 })) as typeof fetch

  try {
    assert.deepEqual(await listOrganizerApplications(), [{ id: 1 }])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('builds organizer directory query and revoke request', async () => {
  const originalFetch = globalThis.fetch
  const requests: Array<{ url: string; method: string; body: string }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? '') })
    return jsonResponse({ records: [], total: 0, size: 10, current: 1, pages: 0 })
  }) as typeof fetch

  try {
    await listOrganizers({ page: 1, size: 10, keyword: '星河', followUpOperator: 2004, cooperationStatus: 'ACTIVE' })
    await revokeOrganizer(2003, '材料长期未补齐')
    assert.deepEqual(requests, [
      { url: '/api/user/console/organizers?page=1&size=10&keyword=%E6%98%9F%E6%B2%B3&followUpOperator=2004&cooperationStatus=ACTIVE', method: 'GET', body: '' },
      { url: '/api/user/console/organizers/2003/revoke', method: 'POST', body: JSON.stringify({ reason: '材料长期未补齐' }) },
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('builds organizer review and batch onsale summary requests', async () => {
  const originalFetch = globalThis.fetch
  const requests: Array<{ url: string; method: string; body: string; signal: AbortSignal | null | undefined }> = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), method: init?.method ?? 'GET', body: String(init?.body ?? ''), signal: init?.signal })
    return jsonResponse({})
  }) as typeof fetch

  try {
    await approveOrganizerApplication(7, '资料完整')
    await rejectOrganizerApplication(8, '缺少资质材料')
    await batchOrganizerOnsaleSummary([2003, 2003, 0, 2005])
    assert.deepEqual(requests.map(({ url, method, body }) => ({ url, method, body })), [
      { url: '/api/user/organizer/applications/7/approve', method: 'POST', body: JSON.stringify({ reviewNote: '资料完整' }) },
      { url: '/api/user/organizer/applications/8/reject', method: 'POST', body: JSON.stringify({ reviewNote: '缺少资质材料' }) },
      { url: '/api/ticket/admin/organizers/batch-onsale-summary', method: 'POST', body: JSON.stringify({ organizerIds: [2003, 2005] }) },
    ])
    assert.ok(requests[2].signal)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('uploads organizer application material as multipart without overriding content type', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  let requestedInit: RequestInit | undefined
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requestedUrl = String(input)
    requestedInit = init
    return jsonResponse({ id: 1, materialType: 'LICENSE', assetId: 2, publicUrl: '/asset', originalName: 'license.pdf', mimeType: 'application/pdf', sizeBytes: 10, createTime: '2026-09-12T00:00:00' })
  }) as typeof fetch

  try {
    await uploadOrganizerApplicationMaterial(9, 'LICENSE', new File(['x'], 'license.pdf', { type: 'application/pdf' }))
    assert.equal(requestedUrl, '/api/user/organizer/applications/9/materials')
    assert.equal(requestedInit?.method, 'POST')
    assert.equal(new Headers(requestedInit?.headers).get('Content-Type'), null)
    assert.ok(requestedInit?.body instanceof FormData)
    const formData = requestedInit?.body as FormData
    assert.equal(formData.get('materialType'), 'LICENSE')
    assert.equal((formData.get('file') as File).name, 'license.pdf')
  } finally {
    globalThis.fetch = originalFetch
  }
})
