// PAT 발급 폼 — name·scope 체크박스(5종)·만료 프리셋 select + scope 미강제 경고 배너 (FR-API-04 Task 7)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { PAT_SCOPE_CATALOG, type CreatePatRequest, type PatScope } from '@/api/pats'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 / 문구 — BC 내 고정 한국어 (WebhookForm.tsx 관례, 별도 i18n 파일 도입 전)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  name: '이름',
  namePlaceholder: '예: CI deploy token',
  scopes: 'Scope',
  expiry: '만료 기간',
  warning: 'scope는 아직 강제되지 않으며, 이 토큰은 계정 전체 권한을 가집니다. 발급 후 안전하게 보관하세요.',
  submit: '발급',
  errorName: '이름은 필수입니다.',
} as const

/** 만료 프리셋 옵션 — 일 단위(백엔드 CreatePatRequest.expiresInDays 1..365 범위 내 고정값) */
const EXPIRY_PRESETS = [
  { value: '30', label: '30일' },
  { value: '90', label: '90일' },
  { value: '180', label: '180일' },
  { value: '365', label: '365일' },
] as const

const DEFAULT_EXPIRY_DAYS = '30'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** PatCreateForm props */
export interface PatCreateFormProps {
  /** 클라 검증(name 공백 아님) 통과 후 호출되는 제출 콜백 */
  readonly onSubmit: (payload: CreatePatRequest) => void
  /** 부모가 전달하는 서버 에러 메시지(400/403, patErrorMessage로 매핑된 문자열). 없으면 배너 미표시 */
  readonly submitError?: string
  /** 제출 진행 중 여부 — true면 입력/버튼 비활성 */
  readonly isSubmitting: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// PatCreateForm
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PAT(Personal Access Token) 발급 폼.
 *
 * - name(필수) · scope 체크박스(PAT_SCOPE_CATALOG 5종 — 선택은 자유이며, 빈 scope 제출은
 *   서버가 400 invalid_scope로 거부한다) · 만료 프리셋 select(30/90/180/365일, 백엔드
 *   1..365 범위 내 고정값이라 클라 측 범위 검증은 불필요).
 * - name이 공백(trim 기준)이면 제출 버튼을 비활성화하고, 방어적으로 폼 제출 시도 시에도
 *   인라인 에러를 표시한 뒤 onSubmit을 호출하지 않는다.
 * - scope가 아직 서버에서 강제되지 않는다는 경고 배너를 폼 영역에 상시 노출한다 —
 *   이 토큰은 scope와 무관하게 계정 전체 권한을 가진다는 사실을 사용자가 인지하게 한다.
 */
export function PatCreateForm({ onSubmit, submitError, isSubmitting }: PatCreateFormProps): JSX.Element {
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<PatScope[]>([])
  const [expiryDays, setExpiryDays] = useState(DEFAULT_EXPIRY_DAYS)
  const [nameError, setNameError] = useState<string | null>(null)

  const trimmedName = name.trim()

  function toggleScope(scope: PatScope, checked: boolean): void {
    setScopes((prev) => (checked ? [...prev, scope] : prev.filter((s) => s !== scope)))
  }

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    if (trimmedName === '') {
      setNameError(labels.errorName)
      return
    }
    setNameError(null)
    onSubmit({ name: trimmedName, scopes, expiresInDays: Number(expiryDays) })
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      {/* scope 미강제 경고 배너 — 상시 노출 */}
      <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
        {labels.warning}
      </div>

      {submitError !== undefined && (
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {submitError}
        </div>
      )}

      <div className="space-y-1.5">
        <Label htmlFor="pat-form-name">{labels.name}</Label>
        <Input
          id="pat-form-name"
          type="text"
          value={name}
          onChange={(e) => { setName(e.target.value) }}
          placeholder={labels.namePlaceholder}
          disabled={isSubmitting}
          aria-invalid={nameError !== null}
        />
        {nameError !== null && (
          <p role="alert" className="text-xs text-destructive">{nameError}</p>
        )}
      </div>

      <fieldset className="space-y-1.5">
        <legend className="text-sm font-medium">{labels.scopes}</legend>
        {PAT_SCOPE_CATALOG.map((scope) => (
          <label key={scope} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={scopes.includes(scope)}
              onChange={(e) => { toggleScope(scope, e.target.checked) }}
              disabled={isSubmitting}
            />
            <span className="font-mono">{scope}</span>
          </label>
        ))}
      </fieldset>

      <div className="space-y-1.5">
        <Label htmlFor="pat-form-expiry">{labels.expiry}</Label>
        <Select value={expiryDays} onValueChange={setExpiryDays} disabled={isSubmitting}>
          <SelectTrigger id="pat-form-expiry" className="w-40" aria-label={labels.expiry}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {EXPIRY_PRESETS.map((preset) => (
              <SelectItem key={preset.value} value={preset.value}>
                {preset.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Button type="submit" disabled={isSubmitting || trimmedName === ''}>
        {labels.submit}
      </Button>
    </form>
  )
}
