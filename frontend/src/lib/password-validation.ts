export function getPasswordMismatchIndexes(password: string, confirmPassword: string): number[] {
  const mismatches: number[] = []
  for (let index = 0; index < confirmPassword.length; index += 1) {
    if (confirmPassword[index] !== password[index]) mismatches.push(index)
  }
  return mismatches
}

export function validatePasswordPair(params: {
  password: string
  confirmPassword: string
  required: boolean
}): string {
  const password = params.password
  const confirmPassword = params.confirmPassword

  if (!password && params.required) return '请填写登录密码'
  if (!password && !confirmPassword) return ''
  if (!password || !confirmPassword) return '请同时填写密码和确认密码'
  if (password.length < 6) return '密码长度不能少于 6 位'
  if (password !== confirmPassword) return '两次输入的密码不一致'
  return ''
}
