// MFA(TOTP) 설정 화면 — status 조회·활성화·비활성화 플로우 + CONCERN-state(secret null 리셋)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { setupMfa, getMfaStatus, enableMfa, disableMfa } from '@/api/mfa'
import type { MfaSetupResponse } from '@/api/schemas'
import { ApiError } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'
import { mfaStrings, mfaErrorMessage } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

// ─────────────────────────────────────────────────────────────────────────────
// QR + Secret 표시 하위 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface MfaSetupViewProps {
  readonly setup: MfaSetupResponse
}

/**
 * TOTP setup 응답(QR PNG + secret_base32)을 표시하는 하위 컴포넌트.
 *
 * @param setup setupMfa() 응답 (MfaSetupResponse)
 */
function MfaSetupView({ setup }: MfaSetupViewProps): JSX.Element {
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">{mfaStrings.qrScanGuide}</p>
      <img
        src={setup.qr_png_data_uri}
        alt="TOTP QR 코드"
        className="h-40 w-40 rounded border"
      />
      <div>
        <p className="text-sm text-muted-foreground">{mfaStrings.secretManualGuide}</p>
        <code className="mt-1 block rounded bg-muted px-3 py-2 text-sm font-mono">
          {setup.secret_base32}
        </code>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 코드 입력 폼 하위 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface MfaCodeFormProps {
  /** 입력 필드 id — 같은 페이지 내 중복 방지용 고유 값 */
  readonly inputId: string
  /** 입력 필드 레이블 */
  readonly label: string
  /** 제출 버튼 레이블 */
  readonly submitLabel: string
  /** 에러 메시지 (null이면 표시 안 함) */
  readonly errorMessage: string | null
  /** 제출 중 여부 */
  readonly isPending: boolean
  /** 폼 제출 콜백 */
  readonly onSubmit: (code: string) => void
}

/**
 * 6자리 TOTP 코드 입력 폼 — enable·disable 공용.
 *
 * @param props MfaCodeFormProps
 */
function MfaCodeForm({
  inputId,
  label,
  submitLabel,
  errorMessage,
  isPending,
  onSubmit,
}: MfaCodeFormProps): JSX.Element {
  const [code, setCode] = useState('')

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    if (code.trim() === '') return
    onSubmit(code)
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      {errorMessage !== null && (
        <div
          role="alert"
          aria-live="polite"
          className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          {errorMessage}
        </div>
      )}

      <div className="space-y-1.5">
        <Label htmlFor={inputId}>{label}</Label>
        <Input
          id={inputId}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          placeholder={mfaStrings.codePlaceholder}
          maxLength={6}
          value={code}
          onChange={(e) => { setCode(e.target.value) }}
          disabled={isPending}
        />
      </div>

      <Button type="submit" disabled={isPending}>
        {submitLabel}
      </Button>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// MfaSettings — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MFA(TOTP) 설정 화면.
 *
 * - GET /api/v1/auth/mfa/totp 로 현재 활성화 여부를 조회한다.
 * - 미활성: 활성화 버튼 → setupMfa() → QR/secret 표시 → 코드 입력 → enableMfa().
 * - 활성: 비활성화 버튼 → step-up 코드 입력 → disableMfa().
 * - enable 성공 후 setup 응답(secret_base32, qr_png_data_uri)을 null 리셋
 *   (CONCERN-state: 조건부 렌더로 숨기기만 하면 React state·메모리에 secret 잔존).
 * - mutation 성공 후 invalidateQueries로만 status 갱신(setQueryData 부분응답 금지).
 */
export function MfaSettings(): JSX.Element {
  const queryClient = useQueryClient()

  // ── status 조회 ──────────────────────────────────────────────────────────
  const {
    data: status,
    isLoading: statusLoading,
    isError: statusError,
  } = useQuery({
    queryKey: ['mfa', 'status'],
    queryFn: getMfaStatus,
    staleTime: 30_000,
  })

  // ── setup 응답 state — CONCERN-state: 명시적 null 리셋 필수 ────────────
  const [setupData, setSetupData] = useState<MfaSetupResponse | null>(null)
  // 비활성화 플로우 진입 여부
  const [showDisableForm, setShowDisableForm] = useState(false)

  // ── 에러 메시지 state ────────────────────────────────────────────────────
  const [enableError, setEnableError] = useState<string | null>(null)
  const [disableError, setDisableError] = useState<string | null>(null)

  // ── setup mutation ───────────────────────────────────────────────────────
  const setupMutation = useMutation({
    mutationFn: setupMfa,
    onSuccess: (data) => {
      setSetupData(data)
      setEnableError(null)
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        const code = extractErrorCode(err.body)
        setEnableError(mfaErrorMessage(code ?? ''))
      } else {
        setEnableError(mfaErrorMessage(''))
      }
    },
  })

  // ── enable mutation ──────────────────────────────────────────────────────
  const enableMutation = useMutation({
    mutationFn: enableMfa,
    onSuccess: () => {
      // CONCERN-state: secret_base32/qr_png_data_uri 명시적 null 리셋
      setSetupData(null)
      setEnableError(null)
      void queryClient.invalidateQueries({ queryKey: ['mfa', 'status'] })
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        const code = extractErrorCode(err.body)
        setEnableError(mfaErrorMessage(code ?? ''))
      } else {
        setEnableError(mfaErrorMessage(''))
      }
    },
  })

  // ── disable mutation ─────────────────────────────────────────────────────
  const disableMutation = useMutation({
    mutationFn: disableMfa,
    onSuccess: () => {
      setShowDisableForm(false)
      setDisableError(null)
      void queryClient.invalidateQueries({ queryKey: ['mfa', 'status'] })
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        const code = extractErrorCode(err.body)
        setDisableError(mfaErrorMessage(code ?? ''))
      } else {
        setDisableError(mfaErrorMessage(''))
      }
    },
  })

  // ── 이벤트 핸들러 ─────────────────────────────────────────────────────────

  function handleActivateClick(): void {
    setEnableError(null)
    setupMutation.mutate()
  }

  function handleEnableSubmit(code: string): void {
    enableMutation.mutate(code)
  }

  function handleDisableClick(): void {
    setDisableError(null)
    setShowDisableForm(true)
  }

  function handleDisableSubmit(code: string): void {
    disableMutation.mutate(code)
  }

  // ── 렌더 ─────────────────────────────────────────────────────────────────

  if (statusLoading) {
    return (
      <div role="status" aria-label="2단계 인증 상태 로딩 중" className="space-y-4 animate-pulse">
        <div className="h-6 w-32 rounded bg-muted" />
        <div className="h-10 w-40 rounded bg-muted" />
      </div>
    )
  }

  if (statusError || status === undefined) {
    return (
      <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
        2단계 인증 상태를 불러오지 못했습니다. 페이지를 새로고침해 주세요.
      </div>
    )
  }

  const isEnabled = status.enabled

  return (
    <div className="space-y-6">
      {/* 현재 상태 표시 */}
      <div className="flex items-center gap-3">
        <span
          className={
            isEnabled
              ? 'rounded-full bg-primary/15 px-3 py-1 text-sm font-medium text-primary'
              : 'rounded-full bg-muted px-3 py-1 text-sm font-medium text-muted-foreground'
          }
        >
          {isEnabled ? mfaStrings.statusEnabled : mfaStrings.statusDisabled}
        </span>
      </div>

      {/* 미활성 상태: 활성화 플로우 */}
      {!isEnabled && (
        <div className="space-y-6">
          {/* setup 응답이 없으면 활성화 버튼 */}
          {setupData === null && (
            <Button
              onClick={handleActivateClick}
              disabled={setupMutation.isPending}
            >
              {mfaStrings.enableButton}
            </Button>
          )}

          {/* setup 응답이 있으면 QR/secret + 코드 입력 폼 */}
          {setupData !== null && (
            <div className="space-y-6">
              <MfaSetupView setup={setupData} />
              <MfaCodeForm
                inputId="mfa-enable-code"
                label={mfaStrings.codeLabel}
                submitLabel={mfaStrings.enableConfirmButton}
                errorMessage={enableError}
                isPending={enableMutation.isPending}
                onSubmit={handleEnableSubmit}
              />
            </div>
          )}
        </div>
      )}

      {/* 활성 상태: 비활성화 플로우 */}
      {isEnabled && (
        <div className="space-y-6">
          {!showDisableForm && (
            <Button
              variant="destructive"
              onClick={handleDisableClick}
            >
              {mfaStrings.disableButton}
            </Button>
          )}

          {showDisableForm && (
            <MfaCodeForm
              inputId="mfa-disable-code"
              label={mfaStrings.disableCodeLabel}
              submitLabel={mfaStrings.disableConfirmButton}
              errorMessage={disableError}
              isPending={disableMutation.isPending}
              onSubmit={handleDisableSubmit}
            />
          )}
        </div>
      )}
    </div>
  )
}
