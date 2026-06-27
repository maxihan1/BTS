// 저장 필터 생성/편집 모달 Dialog (FR-SR-03 Task-3)
import type { JSX } from 'react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import {
  createFilter,
  updateFilter,
  SAVED_FILTER_ERROR_CODES,
  type SavedFilterResponse,
} from '@/api/saved-filters'
import { ApiError } from '@/api/client'
import { savedFilterLabels } from '@/i18n/saved-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마 (Zod v4 — string() 에러 메시지를 문자열로, required_error 미사용)
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z.object({
  name: z.string().min(1, '이름을 입력하세요').max(100, '이름은 100자 이하입니다'),
})

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ApiError.body에서 errorCode 문자열을 추출한다.
 * body가 객체가 아니거나 errorCode 키가 없으면 undefined를 반환한다.
 * error-key drift 방지 원칙 준수 — SAVED_FILTER_ERROR_CODES 상수와 비교하는 쪽에서 사용.
 */
function resolveErrorCode(error: ApiError): string | undefined {
  if (
    typeof error.body !== 'object' ||
    error.body === null ||
    !('errorCode' in error.body)
  ) {
    return undefined
  }
  const code = (error.body as Record<string, unknown>)['errorCode']
  return typeof code === 'string' ? code : undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 SaveFilterDialog와 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface SaveFilterFormProps {
  readonly mode: 'create' | 'edit'
  readonly filter?: SavedFilterResponse
  readonly aqlQuery: string
  readonly projectKey: string
  readonly onSaved?: (filter: SavedFilterResponse) => void
  readonly onConflict?: () => void
  readonly onClose: () => void
}

/**
 * 저장 필터 폼 (내부 컴포넌트).
 *
 * SaveFilterDialog가 `key={filter?.id ?? 'new'}`로 이 컴포넌트를 재마운트해
 * 편집 대상이 바뀔 때 폼 stale state를 방지한다
 * (react-usestate-stale-key-prop 교훈 — defaultValues 기반 초기화는 재마운트 필수).
 *
 * submitError는 이 컴포넌트가 소유한다.
 * 부모에 위임하면 dead-path가 생기므로 금지 (dialog-submiterror-ownership-dead-path 교훈).
 */
function SaveFilterForm({
  mode,
  filter,
  aqlQuery,
  projectKey,
  onSaved,
  onConflict,
  onClose,
}: SaveFilterFormProps): JSX.Element {
  const [submitError, setSubmitError] = useState<string | null>(null)

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { name: filter?.name ?? '' },
  })

  const mutation = useMutation({
    mutationFn: async (values: FormValues): Promise<SavedFilterResponse> => {
      if (mode === 'create') {
        return createFilter({ name: values.name, aqlQuery, projectKey })
      }
      if (filter === undefined) {
        throw new Error('편집 모드에는 filter가 필요합니다')
      }
      return updateFilter(filter.id, {
        name: values.name,
        aqlQuery,
        version: filter.version,
      })
    },
    onSuccess: (result) => {
      setSubmitError(null)
      onSaved?.(result)
      onClose()
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) {
        setSubmitError(savedFilterLabels.saveError)
        return
      }
      const code = resolveErrorCode(error)
      if (code === SAVED_FILTER_ERROR_CODES.NAME_CONFLICT) {
        setSubmitError(savedFilterLabels.nameConflictError)
      } else if (code === SAVED_FILTER_ERROR_CODES.CONFLICT) {
        setSubmitError(savedFilterLabels.conflictError)
        onConflict?.()
      } else {
        setSubmitError(savedFilterLabels.saveError)
      }
    },
  })

  const nameValue = form.watch('name')
  const canSubmit =
    nameValue.trim().length > 0 &&
    nameValue.length <= 100 &&
    !mutation.isPending

  function onValid(values: FormValues): void {
    setSubmitError(null)
    mutation.mutate(values)
  }

  return (
    <form onSubmit={form.handleSubmit(onValid)} noValidate>
      {/* 이름 입력 */}
      <div className="mb-4">
        <label
          htmlFor="save-filter-name"
          className="block text-sm font-medium mb-1"
        >
          {savedFilterLabels.nameLabel}
        </label>
        <input
          id="save-filter-name"
          type="text"
          aria-label={savedFilterLabels.nameLabel}
          placeholder={savedFilterLabels.namePlaceholder}
          maxLength={100}
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...form.register('name')}
        />
      </div>

      {/* 제출 오류 인라인 표시 (role=alert — 스크린리더 즉시 고지) */}
      {submitError !== null && (
        <p
          role="alert"
          className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {submitError}
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="flex justify-end gap-2 mt-2">
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          {savedFilterLabels.cancelButton}
        </Button>
        <Button type="submit" size="sm" disabled={!canSubmit}>
          {savedFilterLabels.saveButton}
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SaveFilterDialog Props */
export interface SaveFilterDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 열림 상태 변경 핸들러 */
  readonly onOpenChange: (open: boolean) => void
  /** 생성 모드는 'create', 편집 모드는 'edit' */
  readonly mode: 'create' | 'edit'
  /**
   * 편집 모드에서 기존 필터 데이터.
   * 편집 대상이 바뀌면 부모에서 `key={filter.id}`를 전달해 다이얼로그를 재마운트할 수 있다.
   * 내부에서도 SaveFilterForm을 `key={filter?.id ?? 'new'}`로 재마운트하므로
   * stale state는 자동으로 방지된다.
   */
  readonly filter?: SavedFilterResponse
  /** 저장 시 함께 전송할 AQL 쿼리 (이름만 편집 가능 — AQL은 상위에서 관리) */
  readonly aqlQuery: string
  /** 필터가 속할 프로젝트 키 (생성 모드에서 사용) */
  readonly projectKey: string
  /**
   * 저장 성공 시 콜백.
   * queryKey invalidate는 T5(dropdown)에서 수행하므로 여기서는 하지 않는다.
   */
  readonly onSaved?: (filter: SavedFilterResponse) => void
  /**
   * OCC 충돌(409 SEARCH_FILTER_CONFLICT) 시 콜백.
   * 호출자는 최신 필터를 다시 조회해 UI를 갱신해야 한다.
   */
  readonly onConflict?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// SaveFilterDialog (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 필터 생성/편집 Dialog.
 *
 * - 생성(`mode='create'`): POST /api/v1/filters
 * - 편집(`mode='edit'`): PUT /api/v1/filters/{id}
 * - 이름 필드만 편집 가능. aqlQuery·projectKey는 prop으로 전달해 API에만 사용
 * - SaveFilterForm을 `key`로 재마운트해 편집 대상 변경 시 stale state 방지
 * - 성공 시 onSaved 콜백 호출 + 다이얼로그 닫힘
 *
 * @see SaveFilterFormProps 인라인 라벨 CONCERN 참고
 */
export const SaveFilterDialog = ({
  open,
  onOpenChange,
  mode,
  filter,
  aqlQuery,
  projectKey,
  onSaved,
  onConflict,
}: SaveFilterDialogProps): JSX.Element => {
  // 인라인 문자열 CONCERN: 다이얼로그 제목('필터 저장'/'필터 수정')은 savedFilterLabels에 미포함.
  // saved-filter-labels.ts(허용 파일 아님)에 dialogTitleCreate/dialogTitleEdit 추가 필요.
  const title = mode === 'create' ? '필터 저장' : '필터 수정'
  const formKey = filter?.id ?? 'new'

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="text-base font-semibold mb-4">
            {title}
          </DialogPrimitive.Title>

          <SaveFilterForm
            key={formKey}
            mode={mode}
            filter={filter}
            aqlQuery={aqlQuery}
            projectKey={projectKey}
            onSaved={onSaved}
            onConflict={onConflict}
            onClose={() => { onOpenChange(false) }}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
