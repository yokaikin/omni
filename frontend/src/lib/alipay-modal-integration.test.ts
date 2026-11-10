import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'

function source(path: string) {
  return readFileSync(join(import.meta.dirname, path), 'utf8')
}

test('alipay popup shows a payment jump link instead of rendering a QR image', () => {
  const content = source('../components/AlipayQrPayModal.tsx')

  assert.doesNotMatch(content, /QRCodeSVG/)
  assert.doesNotMatch(content, /支付宝沙盒/)
  assert.match(content, /打开支付宝支付页面/)
  assert.match(content, /target="_blank"/)
  assert.match(content, /rel="noreferrer"/)
  assert.match(content, /pay\.payForm/)
})

test('payment entry pages request alipay page pay instead of qr precreate', () => {
  const pages = [
    '../app/orders/page.tsx',
    '../app/activity/[id]/page.tsx',
  ]

  for (const page of pages) {
    const content = source(page)
    assert.match(content, /createAlipayPagePay/)
    assert.doesNotMatch(content, /createAlipayQrPay/)
  }
})
