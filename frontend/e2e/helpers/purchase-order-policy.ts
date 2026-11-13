export interface OrderWithId {
  id: number
}

export function splitOwnedPendingOrders<T extends OrderWithId>(orders: T[], ownedOrderIds: number[]) {
  const ownedIds = new Set(ownedOrderIds)
  return {
    owned: orders.filter(order => ownedIds.has(order.id)),
    unowned: orders.filter(order => !ownedIds.has(order.id)),
  }
}
