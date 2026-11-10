'use client'

import { getPasswordMismatchIndexes } from '@/lib/password-validation'

interface PasswordMatchInputProps {
  value: string
  onChange: (value: string) => void
  compareValue?: string
  placeholder: string
  name: string
  ariaLabel: string
  id?: string
  caretClassName?: string
  className?: string
  disabled?: boolean
}

const passwordTextStyle = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  fontSize: '15px',
  lineHeight: '20px',
  letterSpacing: '8px',
  padding: '10px 14px',
}

export function PasswordMatchInput({
  value,
  onChange,
  compareValue,
  placeholder,
  name,
  ariaLabel,
  id,
  caretClassName = 'caret-gray-900',
  className = '',
  disabled = false,
}: PasswordMatchInputProps) {
  const mismatchIndexes = new Set(
    compareValue === undefined ? [] : getPasswordMismatchIndexes(compareValue, value),
  )
  const hasMismatch = compareValue !== undefined && value.length > 0 && value !== compareValue

  return (
    <div
      className={`relative overflow-hidden rounded-xl border bg-gray-50/50 transition-colors ${
        hasMismatch
          ? 'border-rose-300'
          : 'border-gray-200 focus-within:border-[#ff1268] focus-within:bg-white'
      } ${className}`}
    >
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 flex items-center whitespace-pre text-gray-900"
        style={passwordTextStyle}
      >
        {value ? (
          value.split('').map((_, index) => (
            <span
              key={`${index}-${value[index]}`}
              className={mismatchIndexes.has(index) ? 'font-bold text-rose-500' : 'text-gray-900'}
            >
              •
            </span>
          ))
        ) : (
          <span className="text-gray-400">{placeholder}</span>
        )}
      </div>
      <input
        id={id}
        name={name}
        type="password"
        value={value}
        disabled={disabled}
        aria-label={ariaLabel}
        autoComplete="new-password"
        onChange={event => onChange(event.target.value)}
        className={`relative z-10 block h-full min-h-11 w-full border-0 bg-transparent text-transparent outline-none placeholder:text-transparent focus:ring-2 focus:ring-[#ff1268]/20 disabled:cursor-not-allowed disabled:opacity-60 ${caretClassName}`}
        style={passwordTextStyle}
      />
    </div>
  )
}
