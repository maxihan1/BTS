// 워크플로우 전이 CALL_WEBHOOK post-action 추가/수정 다이얼로그
import type { JSX } from 'react'
import { useState, useId } from 'react'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { postActionLabels } from '@/i18n/post-action-labels'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** CALL_WEBHOOK 에서 지원하는 HTTP 메서드 목록 */
const HTTP_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'] as const

/** URL이 반드시 http:// 또는 https://로 시작해야 한다는 제약 — 백엔드 검증과 정합 */
const URL_PREFIX_PATTERN = /^https?:\/\//

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
    errors.url = postActionLabels.form.errorUrlRequired
  } else if (!URL_PREFIX_PATTERN.test(values.url)) {
    errors.url = postActionLabels.form.errorUrlInvalid
  }

  if (!values.method.trim()) {
    errors.method = postActionLabels.form.errorMethodRequired
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
 * - ui/dialog 래퍼(Dialog/DialogContent/DialogTitle) — aria-labelledby 자동 연결
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

  const [url, setUrl] = useState(initialValues?.url ?? '')
  const [method, setMethod] = useState(initialValues?.method ?? '')
  const [errors, setErrors] = useState<FormErrors>({})

  const title = mode === 'create' ? postActionLabels.dialog.createTitle : postActionLabels.dialog.editTitle
  const submitLabel = submitting
    ? postActionLabels.dialog.submittingButton
    : mode === 'create'
      ? postActionLabels.dialog.createButton
      : postActionLabels.dialog.saveButton

  /** 저장 버튼 클릭 — 중복 제출 방지 가드 후 검증, onSubmit 호출 */
  function handleSubmit() {
    // D5: in-flight 상태에서 재진입 차단 — disabled 버튼 programmatic click 방어
    if (submitting) return
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
    <Dialog open={open} onOpenChange={handleOpenChange}>
      {/*
        aria-describedby={undefined} — Radix Dialog.Description 불필요 경고 억제.
        이 폼은 제목(DialogTitle)만으로 맥락이 충분하며, 폼 필드 자체가 설명 역할을 한다.
        Radix 공식 권고: https://radix-ui.com/primitives/docs/components/dialog
      */}
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>

        {/* 인라인 에러 알림 (검증 실패 시) */}
        {hasError && (
          <div
            role="alert"
            className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {firstErrorMessage}
          </div>
        )}

        {/* 폼 */}
        <div className="space-y-4">
          {/* URL 입력 */}
          <div className="space-y-1.5">
            <label
              htmlFor={urlId}
              className="text-sm font-medium text-foreground"
            >
              {postActionLabels.form.urlLabel}
            </label>
            <input
              id={urlId}
              type="url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder={postActionLabels.form.urlPlaceholder}
              aria-invalid={errors.url !== undefined}
              disabled={submitting}
              className={cn(
                'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
                'placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
                'disabled:cursor-not-allowed disabled:opacity-50',
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
              {postActionLabels.form.methodLabel}
            </label>
            <select
              id={methodId}
              value={method}
              onChange={(e) => setMethod(e.target.value)}
              aria-invalid={errors.method !== undefined}
              disabled={submitting}
              className={cn(
                'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
                'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
                'disabled:cursor-not-allowed disabled:opacity-50',
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
        <DialogFooter>
          {/* onClick이 onCancel을 직접 호출하므로 DialogClose로 미포장 (이중 호출 방지) */}
          <Button
            type="button"
            variant="outline"
            size="lg"
            onClick={onCancel}
            disabled={submitting}
            className={cn('rounded-md px-4', 'disabled:cursor-not-allowed')}
          >
            {postActionLabels.dialog.cancelButton}
          </Button>
          <Button
            type="button"
            variant="default"
            size="lg"
            onClick={handleSubmit}
            disabled={submitting}
            className={cn('rounded-md px-4', 'hover:bg-primary/90 disabled:cursor-not-allowed')}
          >
            {submitLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
