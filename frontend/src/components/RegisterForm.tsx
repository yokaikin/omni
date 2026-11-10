'use client'

import { useState, FormEvent } from 'react'
import { useRouter } from 'next/navigation'
import { register } from '@/lib/api'
import { Phone } from 'lucide-react'
import { GraphicCaptcha } from '@/components/GraphicCaptcha'
import { globalAlert } from '@/components/GlobalDialog'
import { PasswordMatchInput } from '@/components/PasswordMatchInput'
import { validatePasswordPair } from '@/lib/password-validation'

export function RegisterForm() {
  const router = useRouter()
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [agreed, setAgreed] = useState(false)
  const [verified, setVerified] = useState(false)
  const [loading, setLoading] = useState(false)
  const [errorMsg, setErrorMsg] = useState('')
  const [shakePassword, setShakePassword] = useState(false)

  const clearError = () => { if (errorMsg) setErrorMsg('') }

  const handleRegister = async (e: FormEvent) => {
    e.preventDefault()
    if (!phone.trim()) { setErrorMsg('请输入手机号'); return }
    const passwordError = validatePasswordPair({ password, confirmPassword, required: true })
    if (passwordError) {
      setErrorMsg(passwordError)
      setShakePassword(true)
      window.setTimeout(() => setShakePassword(false), 240)
      return
    }
    if (!verified) { setErrorMsg('请先完成图形验证码校验'); return }
    if (!agreed) { setErrorMsg('请先阅读并同意相关协议'); return }

    setLoading(true)
    setErrorMsg('')
    try {
      await register({ phone: phone.trim(), password, confirmPassword })
      await globalAlert('注册成功，请登录')
      router.push('/login')
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="w-full max-w-[500px] mx-auto bg-white/95 backdrop-blur-2xl rounded-[24px] shadow-[0_8px_30px_rgb(0,0,0,0.12)] p-10 border border-white/20">
      <h2 className="text-2xl font-bold text-gray-800 mb-8 text-center">注册万象账号</h2>
      
      <form onSubmit={handleRegister} autoComplete="off" className="space-y-5">
        <input type="text" name="prevent_autofill_user" className="hidden" aria-hidden="true" />
        <input type="password" name="prevent_autofill_pass" className="hidden" aria-hidden="true" />

        {/* Phone */}
        <div className="relative group flex">
          <div className="flex items-center justify-center bg-gray-100 border border-r-0 border-gray-200 rounded-l-xl px-4 text-gray-600 text-sm font-medium">
            +86
          </div>
          <div className="relative flex-1">
            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <Phone className="h-5 w-5 text-gray-400 group-focus-within:text-[#ff1268] transition-colors" />
            </div>
            <input
              type="text"
              value={phone}
              onChange={(e) => { setPhone(e.target.value); clearError() }}
              placeholder="请输入手机号"
              className="w-full bg-gray-50/50 border border-gray-200 text-gray-900 rounded-r-xl block pl-10 p-3.5 focus:ring-2 focus:ring-[#ff1268]/20 focus:border-[#ff1268] focus:bg-white outline-none transition-all placeholder:text-gray-400 font-medium"
            />
          </div>
        </div>

        {/* Password */}
        <div className={shakePassword ? 'credential-shake' : ''}>
          <PasswordMatchInput
            value={password}
            onChange={(value) => { setPassword(value); clearError() }}
            placeholder="请输入密码"
            name="password"
            ariaLabel="设置密码"
            caretClassName="caret-[#ff1268]"
          />
        </div>

        {/* Confirm Password */}
        <div className={shakePassword ? 'credential-shake' : ''}>
          <PasswordMatchInput
            value={confirmPassword}
            onChange={(value) => { setConfirmPassword(value); clearError() }}
            compareValue={password}
            placeholder="请再次输入密码"
            name="confirmPassword"
            ariaLabel="确认密码"
            caretClassName="caret-[#ff1268]"
          />
        </div>

        {/* Captcha */}
        <GraphicCaptcha 
          onSuccess={() => { setVerified(true); clearError() }} 
          onFail={() => { setVerified(false) }} 
        />

        {/* Checkbox */}
        <div className="flex items-start mt-4">
          <input
            type="checkbox"
            id="agreement"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-1 w-4 h-4 text-[#ff1268] bg-gray-100 border-gray-300 rounded focus:ring-[#ff1268] cursor-pointer"
          />
          <label htmlFor="agreement" className="ml-2 text-xs text-gray-500 leading-relaxed cursor-pointer select-none">
            我已阅读并接受
            <span className="mx-1 text-[#2192D9]">《万象会员服务协议》</span>
            <span className="mx-1 text-[#2192D9]">《隐私权政策》</span>
            <span className="mx-1 text-[#2192D9]">《订票服务条款》</span>
            并同意自动注册成为会员
          </label>
        </div>

        {errorMsg && (
          <div className="p-3 bg-red-50 text-red-500 rounded-lg text-sm border border-red-100 flex items-center gap-2 animate-in fade-in slide-in-from-top-2">
            <div className="w-1.5 h-1.5 rounded-full bg-red-500" />
            {errorMsg}
          </div>
        )}

        <button
          type="submit"
          disabled={loading}
          className="mt-8 w-full text-white bg-gradient-to-r from-[#ff1268] to-[#ff4b8b] hover:from-[#e00958] hover:to-[#e6306c] focus:ring-4 focus:ring-[#ff1268]/30 font-medium rounded-xl text-[16px] px-5 py-3.5 text-center transition-all shadow-lg shadow-[#ff1268]/25 active:scale-[0.98] disabled:opacity-70 disabled:cursor-not-allowed"
        >
          {loading ? '注册中...' : '同意并注册'}
        </button>

        <div className="text-center mt-6">
          <a onClick={() => router.push('/login')} className="text-[#2192D9] hover:text-[#ff1268] text-sm cursor-pointer transition-colors font-medium">
            已有账号？立即登录
          </a>
        </div>
      </form>
    </div>
  )
}
