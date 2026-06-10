// step-up 재인증 모달 — LOCAL/LDAP 폼 또는 SSO 버튼으로 수단 자동 결정 (FR-AU-08/08b)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAccountLinkMutations } from '@/auth/useAccountLinkMutations'
import { accountLinkLabels, accountLinkErrorMessage } from '@/i18n/account-link-labels'
import { ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SSO 재인증에 필요한 공급자 정보 */
interface SsoReauthProvider {
  readonly registrationId: string
  readonly providerType: 'SAML' | 'OIDC'
}

/**
 * ReauthDialog props.
 *
 * hasLocalPassword / ldapProviderId / ssoReauthProvider 세 가지 조합으로
 * 표시할 재인증 수단이 결정된다(아래 resolveReauthMode 참조).
 */
interface ReauthDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 로컬 계정 비밀번호 보유 여부 — true이면 LOCAL 폼 표시 */
  readonly hasLocalPassword: boolean
  /** LDAP 연결의 providerId — 존재하면 LDAP 폼 표시(hasLocalPassword=false 전제) */
  readonly ldapProviderId?: string
  /** LDAP 연결의 기존 사용자명 — 폼 초기값으로 사용 */
  readonly ldapUsername?: string
  /** SSO 재인증에 사용할 공급자 정보 — LOCAL/LDAP 없을 때 SSO 버튼 표시 */
  readonly ssoReauthProvider?: SsoReauthProvider
  /**
   * step-up 재인증 성공 시 호출.
   * 부모가 step-up 윈도우 만료 시각을 받아 대기 동작을 재개한다.
   *
   * @param expiresAt step-up 세션 만료 시각 (ISO 8601)
   */
  readonly onStepUpGranted: (expiresAt: string) => void
  /**
   * window.location.assign 대체 함수 — 테스트에서 주입해 리디렉트 검증.
   * 기본값: `(url) => window.location.assign(url)`.
   */
  readonly assignLocation?: (url: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 재인증 수단 결정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 재인증 수단 식별 유형.
 *
 * - LOCAL: 로컬 계정 비밀번호로 재인증
 * - LDAP: LDAP 사용자명/비밀번호로 재인증
 * - SSO: SSO 공급자로 리디렉트해 재인증
 */
type ReauthMode = 'LOCAL' | 'LDAP' | 'SSO'

/**
 * props로부터 재인증 수단을 결정한다.
 *
 * 우선순위:
 * 1. `hasLocalPassword=true` → LOCAL 비밀번호 폼
 * 2. `ldapProviderId` 존재 → LDAP 사용자명/비밀번호 폼
 * 3. 둘 다 없음(SSO 전용 계정) → SSO 버튼
 *
 * 반환값은 컴포넌트가 렌더할 UI 수단을 결정하는 데만 사용하며,
 * 실제 API 요청 시 `method` 필드로 변환된다.
 *
 * @param props hasLocalPassword + ldapProviderId 두 필드만 사용
 * @returns 결정된 재인증 수단
 */
function resolveReauthMode(props: Pick<ReauthDialogProps, 'hasLocalPassword' | 'ldapProviderId'>): ReauthMode {
  if (props.hasLocalPassword) return 'LOCAL'
  if (props.ldapProviderId !== undefined) return 'LDAP'
  return 'SSO'
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 body에서 errorCode 추출
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백엔드 ProblemDetail-like 응답 body에서 errorCode를 추출한다.
 * body가 객체가 아니거나 errorCode가 문자열이 아니면 null을 반환한다.
 * accountLinkErrorMessage와 함께 사용해 계정 열거 방지 메시지를 생성한다.
 *
 * @param body ApiError.body (unknown)
 * @returns errorCode 문자열 또는 null
 */
function extractErrorCode(body: unknown): string | null {
  if (body === null || typeof body !== 'object') return null
  const b = body as Record<string, unknown>
  const code = b['errorCode']
  return typeof code === 'string' ? code : null
}

// ─────────────────────────────────────────────────────────────────────────────
// LOCAL 폼
// ─────────────────────────────────────────────────────────────────────────────

interface LocalFormProps {
  readonly isPending: boolean
  readonly onSubmit: (password: string) => void
  readonly passwordRef: { value: string; onChange: (v: string) => void }
}

function LocalForm({ isPending, onSubmit, passwordRef }: LocalFormProps): JSX.Element {
  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    if (passwordRef.value === '') return
    onSubmit(passwordRef.value)
  }

  return (
    <form id="reauth-form" onSubmit={handleSubmit} noValidate>
      <div className="space-y-1.5">
        <Label htmlFor="reauth-password">
          {accountLinkLabels.reauth.localPasswordLabel}
        </Label>
        <Input
          id="reauth-password"
          type="password"
          autoComplete="current-password"
          value={passwordRef.value}
          onChange={(e) => { passwordRef.onChange(e.target.value) }}
          disabled={isPending}
        />
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// LDAP 폼
// ─────────────────────────────────────────────────────────────────────────────

interface LdapFormProps {
  readonly isPending: boolean
  readonly username: string
  readonly password: string
  readonly onUsernameChange: (v: string) => void
  readonly onPasswordChange: (v: string) => void
  readonly onSubmit: (username: string, password: string) => void
}

function LdapForm({ isPending, username, password, onUsernameChange, onPasswordChange, onSubmit }: LdapFormProps): JSX.Element {
  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    if (username === '' || password === '') return
    onSubmit(username, password)
  }

  return (
    <form id="reauth-form" onSubmit={handleSubmit} noValidate>
      <div className="space-y-3">
        <div className="space-y-1.5">
          <Label htmlFor="reauth-ldap-username">
            {accountLinkLabels.reauth.ldapUsernameLabel}
          </Label>
          <Input
            id="reauth-ldap-username"
            type="text"
            autoComplete="username"
            value={username}
            onChange={(e) => { onUsernameChange(e.target.value) }}
            disabled={isPending}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="reauth-ldap-password">
            {accountLinkLabels.reauth.ldapPasswordLabel}
          </Label>
          <Input
            id="reauth-ldap-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => { onPasswordChange(e.target.value) }}
            disabled={isPending}
          />
        </div>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ReauthDialog — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * step-up 재인증 모달.
 *
 * 민감 동작(연결/해제) 전에 본인 확인을 수행하는 모달이다.
 * 보유 자격에 따라 재인증 수단이 자동으로 결정된다.
 *
 * - LOCAL: 비밀번호 폼 → `reauth({ method: 'LOCAL', password })`
 * - LDAP: 사용자명/비밀번호 폼 → `reauth({ method: 'LDAP', providerId, username, password })`
 * - SSO: 버튼 → `ssoReauthStart` → `assignLocation(authorizeUrl)`
 *
 * 성공 시 `onStepUpGranted(stepUpExpiresAt)`를 호출한다.
 * step-up 윈도우(stepUpExpiresAt)는 서버가 발급한 만료 시각으로, 그 안에서는
 * 재인증 없이 연결/해제 동작을 수행할 수 있다.
 *
 * 비밀번호/입력값은 메모리 state에만 유지하며 제출 성공 후 즉시 초기화한다.
 * localStorage에 민감 정보를 저장하지 않는다 (XSS 차단).
 */
export function ReauthDialog({
  open,
  onOpenChange,
  hasLocalPassword,
  ldapProviderId,
  ldapUsername,
  ssoReauthProvider,
  onStepUpGranted,
  assignLocation = (url) => { window.location.assign(url) },
}: ReauthDialogProps): JSX.Element {
  const mode = resolveReauthMode({ hasLocalPassword, ldapProviderId })

  // 폼 입력 상태 — 민감 데이터이므로 메모리에만 유지
  const [password, setPassword] = useState('')
  const [ldapUser, setLdapUser] = useState(ldapUsername ?? '')
  const [ldapPass, setLdapPass] = useState('')

  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const { reauth, ssoReauthStart } = useAccountLinkMutations()
  const isPending = reauth.isPending || ssoReauthStart.isPending

  /** 폼 입력 상태를 초기화한다 */
  function clearForm(): void {
    setPassword('')
    setLdapUser(ldapUsername ?? '')
    setLdapPass('')
    setErrorMessage(null)
  }

  function handleOpenChange(next: boolean): void {
    if (!next) clearForm()
    onOpenChange(next)
  }

  /** API 에러를 사용자 메시지로 변환해 state에 기록한다 */
  function handleError(err: unknown): void {
    if (err instanceof ApiError) {
      const code = extractErrorCode(err.body)
      setErrorMessage(accountLinkErrorMessage(code))
    } else {
      setErrorMessage(accountLinkErrorMessage(null))
    }
  }

  function handleLocalSubmit(pw: string): void {
    setErrorMessage(null)
    reauth.mutate(
      { method: 'LOCAL', password: pw },
      {
        onSuccess: ({ stepUpExpiresAt }) => {
          setPassword('')
          onStepUpGranted(stepUpExpiresAt)
        },
        onError: handleError,
      },
    )
  }

  function handleLdapSubmit(username: string, pw: string): void {
    if (ldapProviderId === undefined) return
    setErrorMessage(null)
    reauth.mutate(
      { method: 'LDAP', providerId: ldapProviderId, username, password: pw },
      {
        onSuccess: ({ stepUpExpiresAt }) => {
          setLdapPass('')
          onStepUpGranted(stepUpExpiresAt)
        },
        onError: handleError,
      },
    )
  }

  function handleSsoClick(): void {
    if (ssoReauthProvider === undefined) return
    setErrorMessage(null)
    ssoReauthStart.mutate(
      { registrationId: ssoReauthProvider.registrationId, providerType: ssoReauthProvider.providerType },
      {
        onSuccess: ({ authorizeUrl }) => { assignLocation(authorizeUrl) },
        onError: handleError,
      },
    )
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-1">
            {accountLinkLabels.reauth.modalTitle}
          </DialogPrimitive.Title>

          <p className="text-sm text-muted-foreground mb-4">
            {accountLinkLabels.reauth.modalGuide}
          </p>

          {/* 에러 메시지 영역 */}
          {errorMessage !== null && (
            <div
              role="alert"
              aria-live="polite"
              className="mb-4 rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
            >
              {errorMessage}
            </div>
          )}

          {/* 수단별 폼 */}
          {mode === 'LOCAL' && (
            <LocalForm
              isPending={isPending}
              onSubmit={handleLocalSubmit}
              passwordRef={{ value: password, onChange: setPassword }}
            />
          )}

          {mode === 'LDAP' && (
            <LdapForm
              isPending={isPending}
              username={ldapUser}
              password={ldapPass}
              onUsernameChange={setLdapUser}
              onPasswordChange={setLdapPass}
              onSubmit={handleLdapSubmit}
            />
          )}

          {/* 액션 버튼 */}
          <div className="flex justify-end gap-2 mt-6">
            <DialogPrimitive.Close asChild>
              <Button variant="outline" size="sm" disabled={isPending}>
                취소
              </Button>
            </DialogPrimitive.Close>

            {mode === 'SSO' ? (
              <Button
                size="sm"
                disabled={isPending}
                onClick={handleSsoClick}
              >
                {accountLinkLabels.reauth.ssoButton}
              </Button>
            ) : (
              <Button
                type="submit"
                form="reauth-form"
                size="sm"
                disabled={isPending}
              >
                {accountLinkLabels.reauth.submitButton}
              </Button>
            )}
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
