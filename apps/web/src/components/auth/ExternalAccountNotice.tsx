// 비밀번호 · MFA 를 Auth_platform 이 맡을 때 BTS 설정 화면 대신 보이는 안내 — VITE_AUTH_ACCOUNT_URL 이 있을 때만
import type { JSX, ReactNode } from 'react'
import { externalAccountStrings } from '@/i18n/ko'

interface ExternalAccountNoticeProps {
  message: string
  hint?: string
  /**
   * 가드가 이 화면으로 강제로 보낸 사용자(mustChangePassword · mfaEnrollmentRequired) — 안내와 함께 BTS 폼도 보인다.
   * 폼을 숨기면 BTS 쪽 플래그를 풀 방법이 없어 화면에 갇힌다.
   */
  keepForm?: boolean
  /** env 가 비면(기본 — BTS 가 직접 관리) 그대로 보이는 BTS 설정 화면 */
  children: ReactNode
}

/** 빌드 env `VITE_AUTH_ACCOUNT_URL` 이 있으면 children 대신(강제 이동이면 함께) Auth 계정 화면 링크를 보인다 */
export function ExternalAccountNotice({ message, hint, keepForm = false, children }: ExternalAccountNoticeProps): JSX.Element {
  const url: string | undefined = import.meta.env['VITE_AUTH_ACCOUNT_URL'] || undefined
  if (url === undefined) return <>{children}</>
  return (
    <div className="space-y-6">
      <div role="status" className="space-y-2 rounded-md border border-border p-4 text-sm">
        <p>{message}</p>
        {hint !== undefined && <p className="text-muted-foreground">{hint}</p>}
        <a className="font-medium text-primary underline" href={url}>
          {externalAccountStrings.openAccount}
        </a>
      </div>
      {keepForm && children}
    </div>
  )
}
