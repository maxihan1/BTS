// 계정 추가 다이얼로그 — linkable provider 피커 + LDAP 인라인 폼 + SSO 리다이렉트 위임 (FR-AU-08/08b D6)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { RadioGroup as RadioGroupPrimitive } from 'radix-ui'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useLinkableProvidersQuery } from '@/auth/useLinkableProvidersQuery'
import { accountLinkLabels } from '@/i18n/account-link-labels'
import type { LinkableProvider } from '@/api/account-links'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** LDAP 연결 요청 — providerId/username/password */
interface LdapLinkRequest {
  readonly providerId: string
  readonly username: string
  readonly password: string
}

/** SSO 연결 시작 요청 — registrationId/providerType */
interface SsoStartRequest {
  readonly registrationId: string
  readonly providerType: 'SAML' | 'OIDC'
}

/**
 * AddAccountDialog props.
 *
 * 결과 메시지(성공/에러 토스트)는 부모(Task 10)가 처리하므로
 * 이 컴포넌트는 입력 수집과 콜백 호출만 담당한다.
 */
interface AddAccountDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /**
   * LDAP 연결 제출 콜백.
   * 부모가 step-up 게이팅 후 linkAccount mutation을 실행한다.
   */
  readonly onLink: (req: LdapLinkRequest) => void
  /**
   * SSO 연결 시작 콜백.
   * 부모가 step-up 게이팅 후 ssoLinkStart + 리다이렉트를 처리한다.
   */
  readonly onSsoStart: (req: SsoStartRequest) => void
  /**
   * 부모의 mutation 진행 중 여부.
   * true이면 폼/라디오/버튼을 disabled 처리한다.
   */
  readonly isSubmitting?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸 — provider 식별자 추출
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RadioGroup의 `value` prop에 사용할 provider 고유 식별자 문자열을 반환한다.
 *
 * - LDAP: `ldap::{providerId}` — UUID 기반 식별자
 * - SAML/OIDC: `sso::{registrationId}` — Spring Security registration ID 기반
 *
 * kind 접두어를 붙이는 이유: providerId와 registrationId는 서로 다른 공간의 값이므로
 * 충돌 가능성을 원천 차단한다.
 *
 * @param provider 연결 가능한 공급자 discriminated union 항목
 * @returns RadioGroup value 문자열
 */
function providerRadioValue(provider: LinkableProvider): string {
  if (provider.kind === 'LDAP') return `ldap::${provider.providerId}`
  return `sso::${provider.registrationId}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 컴포넌트 — provider 항목 렌더
// ─────────────────────────────────────────────────────────────────────────────

interface ProviderItemProps {
  readonly provider: LinkableProvider
  readonly disabled: boolean
}

/**
 * provider 선택 RadioGroup 항목 하나를 렌더한다.
 * displayName + kind 배지를 표시한다.
 */
function ProviderItem({ provider, disabled }: ProviderItemProps): JSX.Element {
  const value = providerRadioValue(provider)
  const ariaLabel = `${provider.displayName} (${provider.kind})`

  return (
    <div className="flex items-center gap-3 rounded-lg border px-3 py-2.5 hover:bg-accent transition-colors">
      <RadioGroupPrimitive.Item
        id={value}
        value={value}
        disabled={disabled}
        aria-label={ariaLabel}
        className="size-4 rounded-full border border-input focus:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:border-primary data-[state=checked]:bg-primary"
      >
        <RadioGroupPrimitive.Indicator className="flex items-center justify-center">
          <span className="size-2 rounded-full bg-background" />
        </RadioGroupPrimitive.Indicator>
      </RadioGroupPrimitive.Item>

      <Label htmlFor={value} className="flex-1 cursor-pointer flex items-center gap-2">
        <span className="text-sm font-medium">{provider.displayName}</span>
        <span className="text-xs rounded px-1.5 py-0.5 bg-muted text-muted-foreground font-mono">
          {provider.kind}
        </span>
      </Label>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 컴포넌트 — LDAP 인라인 폼
// ─────────────────────────────────────────────────────────────────────────────

interface LdapInlineFormProps {
  readonly disabled: boolean
  readonly username: string
  readonly password: string
  readonly onUsernameChange: (v: string) => void
  readonly onPasswordChange: (v: string) => void
}

/**
 * LDAP 선택 시 나타나는 인라인 사용자명/비밀번호 폼.
 * 폼 submit은 부모 다이얼로그의 "연결" 버튼(form="add-account-form")으로 처리한다.
 */
function LdapInlineForm({
  disabled,
  username,
  password,
  onUsernameChange,
  onPasswordChange,
}: LdapInlineFormProps): JSX.Element {
  return (
    <div className="mt-3 space-y-3 rounded-lg border bg-muted/30 px-3 py-3">
      <div className="space-y-1.5">
        <Label htmlFor="add-account-ldap-username">
          {accountLinkLabels.add.ldapForm.usernameLabel}
        </Label>
        <Input
          id="add-account-ldap-username"
          type="text"
          autoComplete="username"
          value={username}
          onChange={(e) => { onUsernameChange(e.target.value) }}
          disabled={disabled}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="add-account-ldap-password">
          {accountLinkLabels.add.ldapForm.passwordLabel}
        </Label>
        <Input
          id="add-account-ldap-password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => { onPasswordChange(e.target.value) }}
          disabled={disabled}
        />
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AddAccountDialog — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 계정 추가 다이얼로그.
 *
 * 1. 열릴 때 useLinkableProvidersQuery로 연결 가능한 공급자 목록을 fetch한다.
 * 2. RadioGroup으로 공급자를 선택한다.
 * 3. LDAP 선택 → 인라인 폼 노출 → "연결" 클릭 → `onLink({ providerId, username, password })` 호출.
 * 4. SSO(SAML/OIDC) 선택 → "연결" 클릭 → `onSsoStart({ registrationId, providerType })` 호출.
 * 5. linkable 0건 → 안내 메시지 표시.
 *
 * 결과(성공/에러) 처리는 부모 컴포넌트(AccountLinksPage, Task 10)에 위임한다.
 * 비밀번호는 메모리 state에만 유지하며 localStorage에 저장하지 않는다.
 */
export function AddAccountDialog({
  open,
  onOpenChange,
  onLink,
  onSsoStart,
  isSubmitting = false,
}: AddAccountDialogProps): JSX.Element {
  const [selectedValue, setSelectedValue] = useState<string | null>(null)
  const [ldapUsername, setLdapUsername] = useState('')
  const [ldapPassword, setLdapPassword] = useState('')

  const { data: providers } = useLinkableProvidersQuery(open)

  /**
   * 현재 selectedValue에 해당하는 LinkableProvider를 반환한다.
   * selectedValue가 null이거나 providers가 아직 로드되지 않았으면 null을 반환한다.
   */
  function findSelected(): LinkableProvider | null {
    if (selectedValue === null || providers === undefined) return null
    return providers.find((p) => providerRadioValue(p) === selectedValue) ?? null
  }

  /**
   * 다이얼로그 닫힐 때 LDAP 폼 입력 상태를 초기화한다.
   * 비밀번호가 메모리에 잔류하지 않도록 닫힘 시점에 즉시 제거한다.
   */
  function handleOpenChange(next: boolean): void {
    if (!next) {
      setSelectedValue(null)
      setLdapUsername('')
      setLdapPassword('')
    }
    onOpenChange(next)
  }

  /**
   * "연결" 버튼 제출 핸들러.
   *
   * - LDAP: username/password 유효성 확인 후 `onLink` 호출 (부모가 step-up 게이팅+linkAccount)
   * - SSO: `onSsoStart` 호출 (부모가 step-up 게이팅+ssoLinkStart+리다이렉트)
   *
   * 빈 username/password로 제출하면 아무 동작을 하지 않는다.
   * HTML `required` 대신 명시적 가드로 처리해 커스텀 메시지 표시 여지를 남긴다.
   */
  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    const selected = findSelected()
    if (selected === null) return

    if (selected.kind === 'LDAP') {
      if (ldapUsername === '' || ldapPassword === '') return
      onLink({ providerId: selected.providerId, username: ldapUsername, password: ldapPassword })
      return
    }

    // SAML 또는 OIDC — 입력 폼 없이 즉시 SSO 흐름 위임
    onSsoStart({ registrationId: selected.registrationId, providerType: selected.kind })
  }

  const selected = findSelected()
  const isLdapSelected = selected?.kind === 'LDAP'

  const canSubmit =
    !isSubmitting &&
    selected !== null &&
    (selected.kind !== 'LDAP' || (ldapUsername !== '' && ldapPassword !== ''))

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{accountLinkLabels.add.addButton}</DialogTitle>
          <DialogDescription>
            {accountLinkLabels.add.providerSelectGuide}
          </DialogDescription>
        </DialogHeader>

        <form id="add-account-form" onSubmit={handleSubmit} noValidate>
          {/* 공급자 목록 — linkable 0건 안내 */}
          {providers !== undefined && providers.length === 0 ? (
            <p className="text-sm text-muted-foreground py-2">
              {accountLinkLabels.add.noLinkableProviders}
            </p>
          ) : (
            <RadioGroupPrimitive.Root
              value={selectedValue ?? ''}
              onValueChange={(v) => { setSelectedValue(v) }}
              disabled={isSubmitting}
              className="space-y-2"
              aria-label="인증 방식 선택"
            >
              {(providers ?? []).map((provider) => (
                <ProviderItem
                  key={providerRadioValue(provider)}
                  provider={provider}
                  disabled={isSubmitting}
                />
              ))}
            </RadioGroupPrimitive.Root>
          )}

          {/* LDAP 선택 시 인라인 폼 */}
          {isLdapSelected && (
            <LdapInlineForm
              disabled={isSubmitting}
              username={ldapUsername}
              password={ldapPassword}
              onUsernameChange={setLdapUsername}
              onPasswordChange={setLdapPassword}
            />
          )}

          {/* SSO 선택 시 안내 문구 */}
          {selected !== null && !isLdapSelected && (
            <p className="mt-3 text-sm text-muted-foreground">
              {accountLinkLabels.add.ssoGuide}
            </p>
          )}
        </form>

        {/* 액션 버튼 */}
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" size="sm" disabled={isSubmitting}>
              취소
            </Button>
          </DialogClose>

          <Button
            type="submit"
            form="add-account-form"
            size="sm"
            disabled={!canSubmit}
          >
            {accountLinkLabels.add.ldapForm.submitButton}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
