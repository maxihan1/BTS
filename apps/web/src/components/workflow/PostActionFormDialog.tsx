// 워크플로우 전이 CALL_WEBHOOK post-action 추가/수정 다이얼로그
import type { JSX } from 'react'
import { useState, useId } from 'react'
import { Dialog } from 'radix-ui'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** CALL_WEBHOOK 에서 지원하는 HTTP 메서드 목록 */
const HTTP_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'] as const

/** URL이 반드시 http:// 또는 https://로 시작해야 한다는 제약 — 백엔드 검증과 정합 */
const URL_PREFIX_PATTERN = /^https?:\/\//

// ─────────────────────────────────────────────────────────────────────────────
// i18n 라벨 — post-action 폼 전용
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  createTitle: 'Post-Action 추가',
  editTitle: 'Post-Action 수정',
  urlLabel: 'Webhook URL',
  urlPlaceholder: 'https://example.com/webhook',
  methodLabel: '메서드 (HTTP Method)',
  saveButton: '저장',
  createButton: '추가',
  cancelButton: '취소',
  submittingButton: '처리 중...',
  errorUrlRequired: 'URL을 입력해 주세요.',
  errorUrlInvalid: 'URL은 http:// 또는 https://로 시작해야 합니다.',
  errorMethodRequired: '메서드를 선택해 주세요.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/** CALL_WEBHOOK post-action 폼 입력값 */
export interface PostActionFormValues {
  url: string
  method: string
}

/** PostActionFormDialog 컴포넌트 props */
export interface PostActionFormDialogProps {
  /** 다이얼로그 열림 여부 */
  open: boolean
  /** create: 추가 모드 / edit: 수정 모드 */
  mode: 'create' | 'edit'
  /** edit 모드 시 기존 값 프리필 */
  initialValues?: PostActionFormValues
  /** mutation 진행 중 여부 — true이면 저장 버튼 disabled */
  submitting?: boolean
  /** 폼 제출 콜백 — 검증 통과 시만 호출 */
  onSubmit: (values: PostActionFormValues) => void
  /** 취소 또는 다이얼로그 닫기 콜백 */
  onCancel: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 폼 검증
// ─────────────────────────────────────────────────────────────────────────────

interface FormErrors {
  url?: string
  method?: string
}

/**
 * CALL_WEBHOOK 폼 입력값을 검증한다.
 *
 * - url: 빈값 거부 + http:// 또는 https:// 시작 필수
 * - method: 빈값 거부
 *
 * @param values 검증할 폼 입력값
 * @returns 에러 객체 — 에러 없으면 빈 객체
 */
function validateForm(values: PostActionFormValues): FormErrors {
  const errors: FormErrors = {}

  if (!values.url.trim()) {
    errors.url = labels.errorUrlRequired
  } else if (!URL_PREFIX_PATTERN.test(values.url)) {
    errors.url = labels.errorUrlInvalid
  }

  if (!values.method.trim()) {
    errors.method = labels.errorMethodRequired
  }

  return errors
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 전이 CALL_WEBHOOK post-action 추가/수정 다이얼로그.
 *
 * - create 모드: 빈 폼, 제목 "Post-Action 추가", 버튼 "추가"
 * - edit 모드: initialValues로 프리필, 제목 "Post-Action 수정", 버튼 "저장"
 * - url 검증: 빈값 거부 + http/https 접두어 필수 (백엔드 동일 규칙)
 * - method 검증: 빈값 거부
 * - 검증 실패 시 인라인 에러(role=alert), onSubmit 미호출
 * - submitting=true 시 저장 버튼 disabled
 * - Radix Dialog.Root + Dialog.Portal — aria-labelledby 자동 연결
 */
export function PostActionFormDialog({
  open,
  mode,
  initialValues,
  submitting = false,
  onSubmit,
  onCancel,
}: PostActionFormDialogProps): JSX.Element {
  const urlId = useId()
  const methodId = useId()
  const titleId = useId()

  const [url, setUrl] = useState(initialValues?.url ?? '')
  const [method, setMethod] = useState(initialValues?.method ?? '')
  const [errors, setErrors] = useState<FormErrors>({})

  const title = mode === 'create' ? labels.createTitle : labels.editTitle
  const submitLabel = submitting
    ? labels.submittingButton
    : mode === 'create'
      ? labels.createButton
      : labels.saveButton

  /** 저장 버튼 클릭 — 검증 후 onSubmit 호출 */
  function handleSubmit() {
    const validationErrors = validateForm({ url, method })
    setErrors(validationErrors)
    if (Object.keys(validationErrors).length > 0) {
      return
    }
    onSubmit({ url, method })
  }

  /** onOpenChange(false) 발생 시 취소로 처리 */
  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      onCancel()
    }
  }

  const hasError = errors.url !== undefined || errors.method !== undefined
  const firstErrorMessage = errors.url ?? errors.method

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay
          className={cn(
            'fixed inset-0 z-50 bg-black/50',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          )}
        />
        <Dialog.Content
          aria-labelledby={titleId}
          className={cn(
            'fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2',
            'rounded-xl border border-border bg-background p-6 shadow-lg',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          )}
        >
          {/* 제목 */}
          <Dialog.Title
            id={titleId}
            className="text-base font-semibold text-foreground"
          >
            {title}
          </Dialog.Title>

          {/* 인라인 에러 알림 (검증 실패 시) */}
          {hasError && (
            <div
              role="alert"
              className="mt-3 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
            >
              {firstErrorMessage}
            </div>
          )}

          {/* 폼 */}
          <div className="mt-4 space-y-4">
            {/* URL 입력 */}
            <div className="space-y-1.5">
              <label
                htmlFor={urlId}
                className="text-sm font-medium text-foreground"
              >
                {labels.urlLabel}
              </label>
              <input
                id={urlId}
                type="url"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder={labels.urlPlaceholder}
                aria-invalid={errors.url !== undefined}
                className={cn(
                  'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
                  'placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
                  errors.url && 'border-destructive focus-visible:border-destructive',
                )}
              />
            </div>

            {/* 메서드 선택 */}
            <div className="space-y-1.5">
              <label
                htmlFor={methodId}
                className="text-sm font-medium text-foreground"
              >
                {labels.methodLabel}
              </label>
              <select
                id={methodId}
                value={method}
                onChange={(e) => setMethod(e.target.value)}
                aria-invalid={errors.method !== undefined}
                className={cn(
                  'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
                  'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
                  errors.method && 'border-destructive focus-visible:border-destructive',
                )}
              >
                <option value="">-- 선택 --</option>
                {HTTP_METHODS.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* 액션 버튼 */}
          <div className="mt-6 flex justify-end gap-2">
            <Dialog.Close asChild>
              <button
                type="button"
                onClick={onCancel}
                className={cn(
                  'rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground',
                  'hover:bg-muted transition-colors',
                )}
              >
                {labels.cancelButton}
              </button>
            </Dialog.Close>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={submitting}
              className={cn(
                'rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground',
                'hover:bg-primary/90 transition-colors disabled:cursor-not-allowed disabled:opacity-50',
              )}
            >
              {submitLabel}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
