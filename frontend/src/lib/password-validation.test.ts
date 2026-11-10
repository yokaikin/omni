import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getPasswordMismatchIndexes,
  validatePasswordPair,
} from './password-validation.ts'

test('只标记确认密码中实际不匹配的位置', () => {
  assert.deepEqual(getPasswordMismatchIndexes('Omni1234', 'Omxi1234'), [2])
  assert.deepEqual(getPasswordMismatchIndexes('Omni1234', 'Omxi1'), [2])
  assert.deepEqual(getPasswordMismatchIndexes('', ''), [])
})

test('注册密码少于六位时校验失败', () => {
  assert.equal(validatePasswordPair({ password: '12345', confirmPassword: '12345', required: true }), '密码长度不能少于 6 位')
})

test('确认密码不一致时校验失败', () => {
  assert.equal(validatePasswordPair({ password: '123456', confirmPassword: '123457', required: true }), '两次输入的密码不一致')
})

test('编辑态两项密码同时为空时允许保存', () => {
  assert.equal(validatePasswordPair({ password: '', confirmPassword: '', required: false }), '')
})

test('编辑态只填写一项密码时拦截', () => {
  assert.equal(validatePasswordPair({ password: '123456', confirmPassword: '', required: false }), '请同时填写密码和确认密码')
})

test('新建态空密码时校验失败', () => {
  assert.equal(validatePasswordPair({ password: '', confirmPassword: '', required: true }), '请填写登录密码')
})
