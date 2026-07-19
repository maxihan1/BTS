// Git 웹훅 등록 폼 — provider 선택 + secret 입력(원문 보존, mutation은 GitWebhookSection 소유)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import type { GitProvider, CreateGitWebhookInput } from '@/api/automation-git-webhooks.types'

// ─────────────────────────────────────────────────────────────────────────────
// secret 검증 — backend GitWebhookRegistrationService.validateSecret 미러(FR4-b).
// blank 판정은 trim 기준(secret.isBlank() 동형) / 길이 판정은 원문(untrimmed) 기준으로
// 비대칭이다. 이 판정 함수는 trim을 읽기 전용으로만 쓴다 — 폼 state·요청 payload에는
// 절대 write-back하지 않는다(FR4-c). secret 원문에 trim/normalize를 적용해 전송하면
// provider HMAC 서명이 영구 불일치한다(GitWebhookRegistrationService.kt:180-181).
// ─────────────────────────────────────────────────────────────────────────────

const MIN_SECRET_LENGTH = 16
const MAX_SECRET_LENGTH = 4096

function isSecretValid(secret: string): boolean {
  if (secret.trim() === '') return false
  return secret.length >= MIN_SECRET_LENGTH && secret.length <= MAX_SECRET_LENGTH
}

const PROVIDER_OPTIONS: ReadonlyArray<{ value: GitProvider; label: string }> = [
  { value: 'GITHUB', label: 'GitHub' },
  { value: 'GITLAB', label: 'GitLab' },
]

const DEFAULT_PROVIDER: GitProvider = 'GITHUB'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어(GitWebhookUrlModal.tsx 선례, i18n 미도입 BC 관례).
// secretInvalid는 backend 고정 응답 문구를 그대로 미러한다
// (GitWebhookRegistrationController.kt handleSecretInvalid의 detail).
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  title: 'Git 웹훅 등록',
  providerLabel: 'Provider',
  secretLabel: 'Secret',
  secretPlaceholder: '웹훅 서명 검증에 쓸 secret을 입력하세요',
  secretHelp:
    'provider 웹훅 설정에 입력할 값과 동일해야 합니다. 서명 검증에 쓰입니다. 앞뒤 공백도 값의 일부로 저장됩니다.',
  secretInvalid: 'secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다.',
  existingProviderWarning:
    '이 provider로 등록된 웹훅이 이미 있습니다. 계속 등록하면 두 웹훅이 동시에 활성화됩니다.',
  submit: '등록',
  cancel: '취소',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** GitWebhookRegisterDialog props — mutation·open 상태는 GitWebhookSection(Task 6)이 소유한다. */
export interface GitWebhookRegisterDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 클라 검증 통과 후 호출되는 제출 콜백 — secret은 사용자 입력 원문 그대로 전달한다 */
  readonly onSubmit: (input: CreateGitWebhookInput) => void
  /** 등록 요청 진행 중 여부 — true면 등록 버튼을 비활성화한다 */
  readonly isPending: boolean
  /** 부모가 전달하는 서버 에러 메시지. 없으면 배너 미표시 */
  readonly submitError?: string
  /** 프로젝트에 이미 등록된 provider 목록 — 중복 등록 경고(FR13) 판정에 쓰인다 */
  readonly existingProviders: GitProvider[]
  /** 목록 조회가 실패해 existingProviders를 신뢰할 수 없을 때 true — 이때는 중복 경고를 내지 않는다(EC21) */
  readonly listUnavailable: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// GitWebhookRegisterDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 등록 폼 Dialog — provider(GITHUB/GITLAB) 선택 + secret 입력을 모아 `onSubmit`으로
 * 올리는 controlled 표시 컴포넌트. mutation은 소유하지 않는다(GitWebhookUrlModal 동형 관례).
 *
 * - secret은 어떤 경로로도 trim/normalize하지 않는다 — 서버 HMAC 서명이 바이트열 원본을 쓴다.
 * - secret 클라 검증은 서버 비대칭 판정을 그대로 미러한다: blank는 trim 기준, 길이는 원문
 *   기준(FR4-b). {@link isSecretValid} 참고.
 * - `open`을 key로 내부 상태를 재마운트해, 재오픈 시 이전 입력이 잔존하지 않는다.
 */
export const GitWebhookRegisterDialog = ({
  open,
  onOpenChange,
  onSubmit,
  isPending,
  submitError,
  existingProviders,
  listUnavailable,
}: GitWebhookRegisterDialogProps): JSX.Element => {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          data-testid="git-webhook-register-dialog"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">{labels.title}</DialogPrimitive.Title>
          <FormBody
            key={open ? 'open' : 'closed'}
            onOpenChange={onOpenChange}
            onSubmit={onSubmit}
            isPending={isPending}
            submitError={submitError}
            existingProviders={existingProviders}
            listUnavailable={listUnavailable}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FormBody — 내부 폼 (key prop 재마운트를 위해 분리, react-usestate-stale-key-prop 교훈)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly onOpenChange: (open: boolean) => void
  readonly onSubmit: (input: CreateGitWebhookInput) => void
  readonly isPending: boolean
  readonly submitError?: string
  readonly existingProviders: GitProvider[]
  readonly listUnavailable: boolean
}

function FormBody({
  onOpenChange,
  onSubmit,
  isPending,
  submitError,
  existingProviders,
  listUnavailable,
}: FormBodyProps): JSX.Element {
  const [provider, setProvider] = useState<GitProvider>(DEFAULT_PROVIDER)
  const [secret, setSecret] = useState('')
  const [secretError, setSecretError] = useState<string | null>(null)

  const showExistingProviderWarning = !listUnavailable && existingProviders.includes(provider)

  function handleProviderChange(value: string): void {
    const match = PROVIDER_OPTIONS.find((option) => option.value === value)
    if (match !== undefined) setProvider(match.value)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    // ★ secret은 원문 그대로 전송한다 — trim/normalize 절대 금지(파일 상단 §secret 검증 참고).
    if (!isSecretValid(secret)) {
      setSecretError(labels.secretInvalid)
      return
    }
    setSecretError(null)
    onSubmit({ provider, secret })
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor="git-webhook-register-provider">{labels.providerLabel}</Label>
        <Select value={provider} onValueChange={handleProviderChange} disabled={isPending}>
          <SelectTrigger id="git-webhook-register-provider" aria-label={labels.providerLabel} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {PROVIDER_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {showExistingProviderWarning && (
          <p className="text-xs text-warning-text">{labels.existingProviderWarning}</p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="git-webhook-register-secret">{labels.secretLabel}</Label>
        <Input
          id="git-webhook-register-secret"
          type="password"
          autoComplete="off"
          placeholder={labels.secretPlaceholder}
          value={secret}
          onChange={(event) => { setSecret(event.target.value) }}
          disabled={isPending}
          aria-invalid={secretError !== null}
        />
        <p className="text-xs text-muted-foreground">{labels.secretHelp}</p>
        {secretError !== null && (
          <p role="alert" className="text-xs text-destructive">{secretError}</p>
        )}
      </div>

      {submitError !== undefined && (
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {submitError}
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="git-webhook-register-cancel"
          onClick={() => { onOpenChange(false) }}
        >
          {labels.cancel}
        </Button>
        <Button type="submit" size="sm" data-testid="git-webhook-register-submit" disabled={isPending}>
          {labels.submit}
        </Button>
      </div>
    </form>
  )
}
