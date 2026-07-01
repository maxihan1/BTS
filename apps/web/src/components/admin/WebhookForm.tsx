// 아웃바운드 webhook 구독 생성/수정 겸용 인라인 폼 (FR-API-03 PR4 Task 5)
import type { JSX, FormEvent } from 'react'
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
  const [name, setName] = useState(initialValue?.name ?? '')
  const [url, setUrl] = useState(initialValue?.url ?? '')
  const [eventFilter, setEventFilter] = useState<string[]>(initialValue?.eventFilter ?? [])
  const [secret, setSecret] = useState('')
  const [projectKey, setProjectKey] = useState(initialValue?.projectKey ?? '')
  const [enabled, setEnabled] = useState(initialValue?.enabled ?? true)
  const [version] = useState(initialValue?.version)

  const [nameError, setNameError] = useState<string | null>(null)
  const [urlError, setUrlError] = useState<string | null>(null)
  const [eventsError, setEventsError] = useState<string | null>(null)

  function toggleEvent(event: string, checked: boolean): void {
    setEventFilter((prev) => (checked ? [...prev, event] : prev.filter((e) => e !== event)))
  }

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()

    const trimmedName = name.trim()
    const trimmedUrl = url.trim()

    const nextNameError = trimmedName === '' ? labels.errorName : null
    const nextUrlError = trimmedUrl === '' ? labels.errorUrl : null
    const nextEventsError = eventFilter.length === 0 ? labels.errorEvents : null

    setNameError(nextNameError)
    setUrlError(nextUrlError)
    setEventsError(nextEventsError)

    if (nextNameError !== null || nextUrlError !== null || nextEventsError !== null) {
      return
    }

    const trimmedSecret = secret.trim()
    const trimmedProjectKey = projectKey.trim()

    if (mode === 'create') {
      const payload: CreateWebhookRequest = {
        name: trimmedName,
        url: trimmedUrl,
        eventFilter,
        enabled,
      }
      if (trimmedSecret !== '') payload.secret = trimmedSecret
      if (trimmedProjectKey !== '') payload.projectKey = trimmedProjectKey
      onSubmit(payload)
      return
    }

    const payload: UpdateWebhookRequest = {
      name: trimmedName,
      url: trimmedUrl,
      eventFilter,
      version: version ?? 0,
      enabled,
    }
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

      <div className="space-y-1.5">
        <Label htmlFor="webhook-form-name">{labels.name}</Label>
        <Input
          id="webhook-form-name"
          value={name}
          onChange={(e) => { setName(e.target.value) }}
          disabled={isSubmitting}
          aria-invalid={nameError !== null}
        />
        {nameError !== null && (
          <p role="alert" className="text-xs text-destructive">{nameError}</p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="webhook-form-url">{labels.url}</Label>
        <Input
          id="webhook-form-url"
          type="url"
          value={url}
          onChange={(e) => { setUrl(e.target.value) }}
          disabled={isSubmitting}
          aria-invalid={urlError !== null}
        />
        {urlError !== null && (
          <p role="alert" className="text-xs text-destructive">{urlError}</p>
        )}
      </div>

      <fieldset className="space-y-1.5">
        <legend className="text-sm font-medium">{labels.events}</legend>
        {WEBHOOK_PUBLISHABLE_EVENTS.map((event) => (
          <label key={event} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={eventFilter.includes(event)}
              onChange={(e) => { toggleEvent(event, e.target.checked) }}
              disabled={isSubmitting}
            />
            {labelForEvent(event)}
          </label>
        ))}
        {eventsError !== null && (
          <p role="alert" className="text-xs text-destructive">{eventsError}</p>
        )}
      </fieldset>

      <div className="space-y-1.5">
        <Label htmlFor="webhook-form-secret">{labels.secret}</Label>
        <Input
          id="webhook-form-secret"
          type="password"
          value={secret}
          onChange={(e) => { setSecret(e.target.value) }}
          disabled={isSubmitting}
        />
        {mode === 'edit' && (
          <p className="text-xs text-muted-foreground">{labels.secretEditHint}</p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="webhook-form-project-key">{labels.projectKey}</Label>
        <Input
          id="webhook-form-project-key"
          value={projectKey}
          onChange={(e) => { setProjectKey(e.target.value) }}
          disabled={isSubmitting}
        />
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => { setEnabled(e.target.checked) }}
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
