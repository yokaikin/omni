import test from 'node:test'
import assert from 'node:assert/strict'
import { buildInfrastructureHealthItems, buildPlatformOpsHealthItems, canLoadPlatformOpsSummary } from './console-ops.ts'

test('loads platform operation summary only when all operation permissions exist', () => {
  const permissions = ['compensation.execute', 'reconcile.view', 'audit.view']
  assert.equal(canLoadPlatformOpsSummary('platform_super_admin', permissions), true)
  assert.equal(canLoadPlatformOpsSummary('admin', []), false)
  assert.equal(canLoadPlatformOpsSummary('support', ['audit.view']), false)
  assert.equal(canLoadPlatformOpsSummary('organizer', ['refund.review']), false)
  assert.equal(canLoadPlatformOpsSummary('organizer_admin', ['audit.view']), false)
})

test('builds platform operation summary health items with Chinese labels and fallback messages', () => {
  const items = buildPlatformOpsHealthItems([
    { source: 'grab', message: '抢票运营摘要暂不可用' },
    { source: 'unknown', message: '' },
  ])

  assert.deepEqual(items.map(item => item.label), ['票务摘要链路', '退款摘要链路', '抢票摘要链路', '工作台摘要链路', '其他摘要链路'])
  assert.equal(items.find(item => item.key === 'ticket')?.status, 'ok')
  assert.equal(items.find(item => item.key === 'ticket')?.message, '摘要链路正常')
  assert.equal(items.find(item => item.key === 'grab')?.status, 'degraded')
  assert.equal(items.find(item => item.key === 'grab')?.message, '抢票运营摘要暂不可用')
  assert.equal(items.find(item => item.key === 'unknown')?.status, 'degraded')
  assert.equal(items.find(item => item.key === 'unknown')?.message, '摘要链路待核对')
})

test('builds infrastructure health items without treating missing probes as healthy', () => {
  const items = buildInfrastructureHealthItems({
    items: [
      { key: 'nacos', label: 'Nacos 注册中心', status: 'ok', message: 'Nacos 控制台可达' },
      { key: 'redis', label: 'Redis 缓存', status: 'degraded', message: 'Redis 端口不可达' },
      { key: 'rabbitmq', label: 'RabbitMQ 消息队列', status: 'not_configured', message: '' },
      { key: 'future', label: '未来探针', status: 'unexpected', message: '' },
    ],
  })

  assert.equal(items.find(item => item.key === 'nacos')?.status, 'ok')
  assert.equal(items.find(item => item.key === 'nacos')?.statusLabel, '正常')
  assert.equal(items.find(item => item.key === 'redis')?.status, 'degraded')
  assert.equal(items.find(item => item.key === 'redis')?.statusLabel, '异常')
  assert.equal(items.find(item => item.key === 'rabbitmq')?.status, 'not_configured')
  assert.equal(items.find(item => item.key === 'rabbitmq')?.statusLabel, '未配置')
  assert.equal(items.find(item => item.key === 'rabbitmq')?.message, '基础设施探针未配置')
  assert.equal(items.find(item => item.key === 'future')?.status, 'degraded')
  assert.equal(items.find(item => item.key === 'future')?.statusLabel, '异常')
})

test('builds review-needed infrastructure health items when backend has not returned probes', () => {
  const items = buildInfrastructureHealthItems(undefined)

  assert.deepEqual(items.map(item => item.label), ['Nacos 注册中心', 'Redis 缓存', 'RabbitMQ 消息队列', 'Seata 事务协调器'])
  assert.equal(items.every(item => item.status === 'not_configured'), true)
  assert.equal(items.every(item => item.message === '基础设施探针未配置'), true)
})
