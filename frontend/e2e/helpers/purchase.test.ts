import assert from 'node:assert/strict'
import test from 'node:test'
import { splitOwnedPendingOrders, type OrderWithId } from './purchase-order-policy.ts'

const order = (id: number): OrderWithId => ({ id })

test('只把登记在 E2E 状态中的待支付订单判定为可清理订单', () => {
  const result = splitOwnedPendingOrders([order(1), order(2)], [2])

  assert.deepEqual(result.owned.map(item => item.id), [2])
  assert.deepEqual(result.unowned.map(item => item.id), [1])
})

test('没有登记订单时，所有待支付订单都保持为非 E2E 所有', () => {
  const result = splitOwnedPendingOrders([order(1)], [])

  assert.deepEqual(result.owned, [])
  assert.deepEqual(result.unowned.map(item => item.id), [1])
})
