// 아웃바운드 webhook 구독 생성/수정 겸용 인라인 폼 (FR-API-03 PR4 Task 5)
import type { JSX, FormEvent, ChangeEvent } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { CreateWebhookRequest, UpdateWebhookRequest, WebhookResponse } from '@/api/webhooks'
import { WEBHOOK_PUBLISHABLE_EVENTS } from '@/api/webhooks'
import { labelForEvent } from '@/i18n/webhook-labels'

/** 폼 화면 문구 — BC 내 고정 한국어(별도 i18n 파일 도입 전, CreateUserForm.tsx 선례 관례) */
const labels = {
  name: '이름',
  url: 'URL',
  events: '구독 이벤트',
  secret: '서명 Secret',
  secretEditHint: '비워두면 기존 서명 키를 유지합니다.',
  projectKey: '프로젝트 키',
  enabled: '활성화',
  submit: { create: '생성', edit: '저장' },
  cancel: '취소',
  errorName: '이름은 필수입니다.',
  errorUrl: 'URL은 필수입니다.',
  errorEvents: '이벤트를 최소 1개 선택해야 합니다.',
} as const

/** 폼이 관리하는 입력 필드 상태 스냅샷 — secret은 항상 빈 값에서 시작(3-state, EC-3) */
interface WebhookFormFields {
  name: string
  url: string
  eventFilter: string[]
  secret: string
  projectKey: string
  enabled: boolean
}

/** 클라 검증 결과 — 필드별 에러 메시지, 통과 시 null */
interface WebhookFormErrors {
  name: string | null
  url: string | null
  events: string | null
}

const NO_ERRORS: WebhookFormErrors = { name: null, url: null, events: null }

/** initialValue(edit 프리필) 또는 빈 값(create)으로 초기 필드 상태를 만든다 */
function buildInitialFields(initialValue?: WebhookResponse): WebhookFormFields {
  return {
    name: initialValue?.name ?? '',
    url: initialValue?.url ?? '',
    eventFilter: initialValue?.eventFilter ?? [],
    secret: '',
    projectKey: initialValue?.projectKey ?? '',
    enabled: initialValue?.enabled ?? true,
  }
}

/** WebhookForm 컴포넌트 props — 생성/수정 겸용 */
interface WebhookFormProps {
  /** 'create'면 신규 구독 생성, 'edit'이면 기존 구독 수정 */
  readonly mode: 'create' | 'edit'
  /** edit 모드의 프리필 원본 — version(OCC 키) 포함 */
  readonly initialValue?: WebhookResponse
  /** 클라 검증 통과 후 호출되는 제출 콜백 — payload는 mode에 맞는 요청 바디 */
  readonly onSubmit: (payload: CreateWebhookRequest | UpdateWebhookRequest) => void
  /** 부모가 전달하는 서버 에러 메시지(400/409/403). 없으면 배너 미표시 */
  readonly submitError?: string
  /** 제출 진행 중 여부 — true면 입력/버튼 비활성 */
  readonly isSubmitting: boolean
  /** 취소 버튼 핸들러 */
  readonly onCancel: () => void
}

/** 텍스트 입력 필드 하나(Label+Input+선택적 에러/힌트)를 렌더하는 내부 헬퍼 props */
interface WebhookTextFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  type?: string
  disabled: boolean
  error?: string | null
  hint?: string
}

/**
 * name/url/secret/projectKey가 공유하는 Label+Input+에러/힌트 골격을 렌더한다.
 *
 * 파일 내부 전용(export 없음) — 범용 폼 컴포넌트가 아니라 WebhookForm의 필드 반복을 줄이기 위한 헬퍼.
 */
function WebhookTextField({
  id,
  label,
  value,
  onChange,
  type = 'text',
  disabled,
  error,
  hint,
}: WebhookTextFieldProps): JSX.Element {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type={type}
        value={value}
        onChange={(e: ChangeEvent<HTMLInputElement>) => { onChange(e.target.value) }}
        disabled={disabled}
        aria-invalid={error !== null && error !== undefined}
      />
      {error !== null && error !== undefined && (
        <p role="alert" className="text-xs text-destructive">{error}</p>
      )}
      {hint !== undefined && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}

/**
 * webhook 구독 생성/수정 겸용 인라인 폼.
 *
 * - 필드. name/url(필수) · 구독 이벤트(체크박스, 최소 1개 강제 EC-9) · secret(선택) · projectKey(선택) · enabled(체크박스)
 * - secret 3-state(edit 모드). 비워두면 요청 payload에서 키 자체를 생략해 기존 서명 키를 유지한다(EC-3).
 * - edit 모드는 `initialValue.version`을 폼 상태로 보유해 제출 payload에 그대로 포함한다(OCC EC-4).
 * - 저장 전 클라 검증(name/url blank, 이벤트 0개)을 통과하지 못하면 onSubmit을 호출하지 않고 인라인 에러만 표시한다.
 */
export function WebhookForm({
  mode,
  initialValue,
  onSubmit,
  submitError,
  isSubmitting,
  onCancel,
}: WebhookFormProps): JSX.Element {
  const [fields, setFields] = useState<WebhookFormFields>(() => buildInitialFields(initialValue))
  const [version] = useState(initialValue?.version)
  const [errors, setErrors] = useState<WebhookFormErrors>(NO_ERRORS)

  function updateField<K extends keyof WebhookFormFields>(key: K, value: WebhookFormFields[K]): void {
    setFields((prev) => ({ ...prev, [key]: value }))
  }

  function toggleEvent(event: string, checked: boolean): void {
    setFields((prev) => ({
      ...prev,
      eventFilter: checked ? [...prev.eventFilter, event] : prev.eventFilter.filter((e) => e !== event),
    }))
  }

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()

    const trimmedName = fields.name.trim()
    const trimmedUrl = fields.url.trim()
    const nextErrors: WebhookFormErrors = {
      name: trimmedName === '' ? labels.errorName : null,
      url: trimmedUrl === '' ? labels.errorUrl : null,
      events: fields.eventFilter.length === 0 ? labels.errorEvents : null,
    }
    setErrors(nextErrors)
    if (nextErrors.name !== null || nextErrors.url !== null || nextErrors.events !== null) return

    const trimmedSecret = fields.secret.trim()
    const trimmedProjectKey = fields.projectKey.trim()
    const base = { name: trimmedName, url: trimmedUrl, eventFilter: fields.eventFilter, enabled: fields.enabled }
    const payload: CreateWebhookRequest | UpdateWebhookRequest =
      mode === 'create' ? { ...base } : { ...base, version: version ?? 0 }
    if (trimmedSecret !== '') payload.secret = trimmedSecret
    if (trimmedProjectKey !== '') payload.projectKey = trimmedProjectKey
    onSubmit(payload)
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      {submitError !== undefined && (
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {submitError}
        </div>
      )}

      <WebhookTextField
        id="webhook-form-name"
        label={labels.name}
        value={fields.name}
        onChange={(v) => { updateField('name', v) }}
        disabled={isSubmitting}
        error={errors.name}
      />

      <WebhookTextField
        id="webhook-form-url"
        label={labels.url}
        value={fields.url}
        onChange={(v) => { updateField('url', v) }}
        type="url"
        disabled={isSubmitting}
        error={errors.url}
      />

      <fieldset className="space-y-1.5">
        <legend className="text-sm font-medium">{labels.events}</legend>
        {WEBHOOK_PUBLISHABLE_EVENTS.map((event) => (
          <label key={event} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={fields.eventFilter.includes(event)}
              onChange={(e) => { toggleEvent(event, e.target.checked) }}
              disabled={isSubmitting}
            />
            {labelForEvent(event)}
          </label>
        ))}
        {errors.events !== null && (
          <p role="alert" className="text-xs text-destructive">{errors.events}</p>
        )}
      </fieldset>

      <WebhookTextField
        id="webhook-form-secret"
        label={labels.secret}
        value={fields.secret}
        onChange={(v) => { updateField('secret', v) }}
        type="password"
        disabled={isSubmitting}
        hint={mode === 'edit' ? labels.secretEditHint : undefined}
      />

      <WebhookTextField
        id="webhook-form-project-key"
        label={labels.projectKey}
        value={fields.projectKey}
        onChange={(v) => { updateField('projectKey', v) }}
        disabled={isSubmitting}
      />

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={fields.enabled}
          onChange={(e) => { updateField('enabled', e.target.checked) }}
          disabled={isSubmitting}
        />
        {labels.enabled}
      </label>

      <div className="flex gap-2">
        <Button type="submit" disabled={isSubmitting}>
          {labels.submit[mode]}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
          {labels.cancel}
        </Button>
      </div>
    </form>
  )
}
