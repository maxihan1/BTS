// 퀵필터 생성/편집 모달 Dialog — 현재 보드 필터를 이름 붙여 저장 (FR-UX-01 Task 9)
import type { JSX } from 'react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import type { QuickFilter } from '@/api/board-quick-filters'
import { useCreateQuickFilter, useUpdateQuickFilter } from '@/hooks/use-board-quick-filters'
import { quickFilterLabels } from '@/i18n/quick-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 백엔드 QuickFilter.MAX_NAME_LENGTH(plan Task 2)와 동일
// ─────────────────────────────────────────────────────────────────────────────

const MAX_NAME_LENGTH = 50

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마 (Zod v4 — string() 에러 메시지를 문자열로, required_error 미사용)
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z.object({
  name: z
    .string()
    .min(1, quickFilterLabels.form.nameRequired)
    .max(MAX_NAME_LENGTH, quickFilterLabels.form.nameTooLong),
})

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// errorCode → 사용자 메시지 매핑 (백엔드가 errorCode 문자열 계약을 확정 — 코드리뷰 CONCERN-1)
//
// 이전에는 status만으로 매핑해 EC2(이름 중복)와 EC3(20건 상한 초과)가 모두 409로 와
// 둘 다 nameConflict로 잘못 표기됐다. errorCode를 우선 확인해 분기하고, errorCode가
// 없거나 알려지지 않은 값이면 status 기반 매핑으로 fallback한다(하위호환).
// ─────────────────────────────────────────────────────────────────────────────

/** 이 다이얼로그가 인식하는 백엔드 errorCode 상수 (board BC — CreateBoardForm.tsx 동일 로컬 정의 패턴) */
const QUICK_FILTER_ERROR_CODES = {
  LIMIT_EXCEEDED: 'AGILE_QUICK_FILTER_LIMIT_EXCEEDED',
  NAME_CONFLICT: 'AGILE_QUICK_FILTER_NAME_CONFLICT',
  EMPTY_QUERY: 'AGILE_QUICK_FILTER_EMPTY_QUERY',
} as const

/** ApiError.body에서 errorCode 문자열을 추출한다 (CreateBoardForm.tsx 동일 패턴) */
function extractErrorCode(body: unknown): string | undefined {
  if (body !== null && typeof body === 'object' && 'errorCode' in body) {
    const code = (body as Record<string, unknown>)['errorCode']
    return typeof code === 'string' ? code : undefined
  }
  return undefined
}

/** ApiError.status를 사용자 노출 메시지로 변환한다 (errorCode 미상 시 fallback). */
function mapStatusToMessage(status: number): string {
  if (status === 409) return quickFilterLabels.errors.nameConflict
  if (status === 400) return quickFilterLabels.errors.invalidQuery
  return quickFilterLabels.errors.saveFailed
}

/**
 * ApiError를 사용자 노출 메시지로 변환한다.
 *
 * errorCode를 우선 확인해 분기한다. errorCode가 없거나(구버전 응답) 알려지지 않은
 * 값이면 status 기반 mapStatusToMessage로 fallback한다.
 */
function mapErrorToMessage(error: ApiError): string {
  const errorCode = extractErrorCode(error.body)
  if (errorCode === QUICK_FILTER_ERROR_CODES.LIMIT_EXCEEDED) return quickFilterLabels.errors.limitExceeded
  if (errorCode === QUICK_FILTER_ERROR_CODES.NAME_CONFLICT) return quickFilterLabels.errors.nameConflict
  if (errorCode === QUICK_FILTER_ERROR_CODES.EMPTY_QUERY) return quickFilterLabels.errors.invalidQuery
  return mapStatusToMessage(error.status)
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 SaveQuickFilterDialog와 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface SaveQuickFilterFormProps {
  readonly mode: 'create' | 'edit'
  readonly filter?: QuickFilter
  readonly boardId: string
  readonly currentQuery: string
  readonly onSaved?: (filter: QuickFilter) => void
  readonly onClose: () => void
}

/**
 * 퀵필터 저장/편집 폼 (내부 컴포넌트).
 *
 * SaveQuickFilterDialog가 `key={filter?.filterId ?? 'new'}`로 이 컴포넌트를 재마운트해
 * 편집 대상이 바뀔 때 폼 stale state를 방지한다
 * (react-usestate-stale-key-prop 교훈 — defaultValues 기반 초기화는 재마운트 필수).
 *
 * name만 사용자가 입력한다. query는 항상 `currentQuery`(부모=BoardPage가 관리하는
 * 현재 적용 보드 필터)를 그대로 사용한다 — SaveFilterDialog의 "AQL은 상위에서 관리"
 * 패턴과 동일. 편집 모드에서 조건을 바꾸려면 먼저 BoardFilterBar로 원하는 조건을
 * 적용한 뒤 편집을 열어 저장한다.
 */
function SaveQuickFilterForm({
  mode,
  filter,
  boardId,
  currentQuery,
  onSaved,
  onClose,
}: SaveQuickFilterFormProps): JSX.Element {
  const [submitError, setSubmitError] = useState<string | null>(null)

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { name: filter?.name ?? '' },
  })

  const createMutation = useCreateQuickFilter(boardId)
  const updateMutation = useUpdateQuickFilter(boardId)
  const isPending = createMutation.isPending || updateMutation.isPending

  function handleSuccess(result: QuickFilter): void {
    setSubmitError(null)
    onSaved?.(result)
    onClose()
  }

  function handleError(error: unknown): void {
    setSubmitError(error instanceof ApiError ? mapErrorToMessage(error) : quickFilterLabels.errors.saveFailed)
  }

  function onValid(values: FormValues): void {
    setSubmitError(null)
    if (mode === 'create') {
      createMutation.mutate(
        { name: values.name, query: currentQuery },
        { onSuccess: handleSuccess, onError: handleError },
      )
      return
    }
    if (filter === undefined) {
      throw new Error('편집 모드에는 filter가 필요합니다')
    }
    updateMutation.mutate(
      { filterId: filter.filterId, request: { name: values.name, query: currentQuery } },
      { onSuccess: handleSuccess, onError: handleError },
    )
  }

  const nameValue = form.watch('name')
  const isQueryEmpty = currentQuery.trim().length === 0
  const canSubmit =
    nameValue.trim().length > 0 &&
    nameValue.length <= MAX_NAME_LENGTH &&
    !isQueryEmpty &&
    !isPending

  return (
    <form onSubmit={form.handleSubmit(onValid)} noValidate>
      {/* 이름 입력 */}
      <div className="mb-4">
        <label htmlFor="quick-filter-name" className="block text-sm font-medium mb-1">
          {quickFilterLabels.form.nameLabel}
        </label>
        <input
          id="quick-filter-name"
          type="text"
          aria-label={quickFilterLabels.form.nameLabel}
          placeholder={quickFilterLabels.form.namePlaceholder}
          maxLength={MAX_NAME_LENGTH}
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...form.register('name')}
        />
      </div>

      {/* EC1 — 현재 적용된 필터가 없으면 저장 불가 안내 */}
      {isQueryEmpty && (
        <p className="mb-4 text-xs text-muted-foreground">{quickFilterLabels.form.emptyQueryHint}</p>
      )}

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
          {quickFilterLabels.form.cancelButton}
        </Button>
        <Button type="submit" size="sm" disabled={!canSubmit}>
          {quickFilterLabels.form.saveButton}
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SaveQuickFilterDialog Props */
export interface SaveQuickFilterDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 열림 상태 변경 핸들러 */
  readonly onOpenChange: (open: boolean) => void
  /** 생성 모드는 'create', 편집 모드는 'edit' */
  readonly mode: 'create' | 'edit'
  /**
   * 편집 모드에서 기존 퀵필터 데이터.
   * 내부에서 SaveQuickFilterForm을 `key={filter?.filterId ?? 'new'}`로 재마운트하므로
   * 편집 대상이 바뀌어도 stale state는 자동으로 방지된다.
   */
  readonly filter?: QuickFilter
  /** 퀵필터가 속한 보드 UUID */
  readonly boardId: string
  /**
   * 현재 보드에 적용된 필터를 쿼리스트링(접두 `?` 없음)으로 표현한 값.
   * 생성·편집 모두 이 값을 그대로 저장한다 (name만 사용자가 편집).
   * 빈 문자열이면 EC1 — 저장 버튼이 비활성화된다.
   */
  readonly currentQuery: string
  /**
   * 저장 성공 시 콜백.
   * queryKey invalidate는 useCreateQuickFilter/useUpdateQuickFilter 훅이 수행하므로
   * 여기서는 하지 않는다.
   */
  readonly onSaved?: (filter: QuickFilter) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// SaveQuickFilterDialog (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 퀵필터 저장/편집 Dialog.
 *
 * - 생성(`mode='create'`): POST /api/v1/boards/{boardId}/quick-filters
 * - 편집(`mode='edit'`): PATCH /api/v1/boards/{boardId}/quick-filters/{filterId}
 * - 이름 필드만 편집 가능. query는 `currentQuery` prop 그대로 사용
 * - SaveQuickFilterForm을 `key`로 재마운트해 편집 대상 변경 시 stale state 방지
 * - 성공 시 onSaved 콜백 호출 + 다이얼로그 닫힘
 */
export const SaveQuickFilterDialog = ({
  open,
  onOpenChange,
  mode,
  filter,
  boardId,
  currentQuery,
  onSaved,
}: SaveQuickFilterDialogProps): JSX.Element => {
  const title = mode === 'create' ? quickFilterLabels.dialog.titleCreate : quickFilterLabels.dialog.titleEdit
  const formKey = filter?.filterId ?? 'new'

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

          <SaveQuickFilterForm
            key={formKey}
            mode={mode}
            filter={filter}
            boardId={boardId}
            currentQuery={currentQuery}
            onSaved={onSaved}
            onClose={() => { onOpenChange(false) }}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
