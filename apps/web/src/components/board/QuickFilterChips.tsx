// 보드 퀵필터 칩 목록 — 적용/토글(FR6) + 저장/편집/삭제(canManage 게이팅, FR-UX-01 Task 9)
import type { JSX } from 'react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { useDeleteQuickFilter } from '@/hooks/use-board-quick-filters'
import { SaveQuickFilterDialog } from './SaveQuickFilterDialog'
import type { QuickFilter } from '@/api/board-quick-filters'
import { quickFilterLabels } from '@/i18n/quick-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** QuickFilterChips Props */
export interface QuickFilterChipsProps {
  /** 퀵필터가 속한 보드 UUID */
  readonly boardId: string
  /** 렌더할 퀵필터 목록 (created_at ASC — boardDetail.quickFilters 그대로) */
  readonly quickFilters: QuickFilter[]
  /** 현재 활성(적용 중) 퀵필터 id. 없으면 null (FR6) */
  readonly activeQuickFilterId: string | null
  /** 저장/편집/삭제 버튼 게이팅 — useProjectPermissions(projectKey).permissions.CREATE 재사용 */
  readonly canManage: boolean
  /**
   * 현재 보드에 적용된 필터를 쿼리스트링(접두 `?` 없음)으로 표현한 값.
   * "필터 저장" 버튼 활성화 판정(EC1)과 생성/편집 다이얼로그 저장 payload에 쓰인다.
   */
  readonly currentQuery: string
  /**
   * 칩 클릭 콜백.
   * 비활성 칩 클릭 → `onApply(filter)`. 활성 칩 재클릭 → `onApply(null)`(토글 해제, FR6).
   * 실제 navigate/URL 반영은 라우트(BoardPage)가 담당한다 (BLOCKER-B co-location).
   */
  readonly onApply: (filter: QuickFilter | null) => void
  /**
   * 삭제 성공 콜백. `wasActive`는 삭제된 필터가 activeQuickFilterId와 같았는지 여부.
   * 라우트는 이 값으로 activeQuickFilterId를 초기화한다 (C3-c).
   */
  readonly onFilterDeleted: (filterId: string, wasActive: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 다이얼로그 상태 — ComponentList.tsx DialogState 패턴 동일 적용
// ─────────────────────────────────────────────────────────────────────────────

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; filter: QuickFilter }

// ─────────────────────────────────────────────────────────────────────────────
// QuickFilterChips
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 퀵필터 칩 행.
 *
 * - 칩 클릭: 활성/비활성에 따라 onApply(filter | null) 호출 (FR6 토글).
 * - canManage=true: 각 칩에 편집/삭제 버튼 + "필터 저장" 트리거 버튼 노출.
 * - "필터 저장"/저장 다이얼로그의 EC1(빈 필터 저장 불가)은 currentQuery로 판정.
 * - 삭제는 확인 없이 즉시 실행(useDeleteQuickFilter) — 실패 시 toast.error.
 * - 생성/편집은 SaveQuickFilterDialog에 위임 (내부에서 filterId key 재마운트).
 *
 * @param props QuickFilterChipsProps
 */
export function QuickFilterChips({
  boardId,
  quickFilters,
  activeQuickFilterId,
  canManage,
  currentQuery,
  onApply,
  onFilterDeleted,
}: QuickFilterChipsProps): JSX.Element | null {
  const [dialogState, setDialogState] = useState<DialogState>({ open: false })
  const deleteMutation = useDeleteQuickFilter(boardId)

  if (quickFilters.length === 0 && !canManage) return null

  function handleChipClick(filter: QuickFilter): void {
    onApply(filter.filterId === activeQuickFilterId ? null : filter)
  }

  function handleDeleteClick(filter: QuickFilter): void {
    const wasActive = filter.filterId === activeQuickFilterId
    deleteMutation.mutate(filter.filterId, {
      onSuccess: () => onFilterDeleted(filter.filterId, wasActive),
      onError: () => toast.error(quickFilterLabels.errors.deleteFailed),
    })
  }

  function handleDialogOpenChange(open: boolean): void {
    if (!open) setDialogState({ open: false })
  }

  const isSaveDisabled = currentQuery.trim().length === 0
  const dialogFilter = dialogState.open && dialogState.mode === 'edit' ? dialogState.filter : undefined

  return (
    <div className="flex flex-wrap items-center gap-2">
      <div role="list" aria-label={quickFilterLabels.list.ariaLabel} className="flex flex-wrap gap-1.5">
        {quickFilters.map((filter) => (
          <QuickFilterChip
            key={filter.filterId}
            filter={filter}
            isActive={filter.filterId === activeQuickFilterId}
            canManage={canManage}
            isDeleting={deleteMutation.isPending && deleteMutation.variables === filter.filterId}
            onApply={() => handleChipClick(filter)}
            onEdit={() => setDialogState({ open: true, mode: 'edit', filter })}
            onDelete={() => handleDeleteClick(filter)}
          />
        ))}
      </div>

      {canManage && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={isSaveDisabled}
          title={isSaveDisabled ? quickFilterLabels.form.emptyQueryHint : undefined}
          onClick={() => setDialogState({ open: true, mode: 'create' })}
        >
          {quickFilterLabels.list.saveCurrentButton}
        </Button>
      )}

      <SaveQuickFilterDialog
        open={dialogState.open}
        onOpenChange={handleDialogOpenChange}
        mode={dialogState.open ? dialogState.mode : 'create'}
        filter={dialogFilter}
        boardId={boardId}
        currentQuery={currentQuery}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// QuickFilterChip (단일 칩)
// ─────────────────────────────────────────────────────────────────────────────

interface QuickFilterChipProps {
  readonly filter: QuickFilter
  readonly isActive: boolean
  readonly canManage: boolean
  readonly isDeleting: boolean
  readonly onApply: () => void
  readonly onEdit: () => void
  readonly onDelete: () => void
}

/**
 * 단일 퀵필터 칩. 기존 BoardFilterBar `Chip` 스타일(rounded-full·44px 터치타겟)을
 * 계승하되, 활성 상태는 `bg-primary`로 강조하고 비활성은 `bg-muted`를 유지한다.
 * 이름 버튼(`aria-pressed`로 활성 표기)과 편집/삭제 버튼은 형제 요소로 배치해
 * 버튼 중첩(유효하지 않은 HTML)을 피한다.
 */
function QuickFilterChip({
  filter,
  isActive,
  canManage,
  isDeleting,
  onApply,
  onEdit,
  onDelete,
}: QuickFilterChipProps): JSX.Element {
  return (
    <span
      role="listitem"
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs ${
        isActive ? 'bg-primary text-primary-foreground' : 'bg-muted'
      }`}
    >
      <button
        type="button"
        aria-pressed={isActive}
        className="min-h-[44px] px-1"
        onClick={onApply}
      >
        {filter.name}
      </button>
      {canManage && (
        <>
          <button
            type="button"
            aria-label={quickFilterLabels.list.editAriaLabel(filter.name)}
            className="flex min-h-[44px] min-w-[44px] items-center justify-center rounded-full leading-none hover:bg-muted-foreground/20"
            onClick={onEdit}
          >
            ✎
          </button>
          <button
            type="button"
            aria-label={quickFilterLabels.list.deleteAriaLabel(filter.name)}
            disabled={isDeleting}
            className="flex min-h-[44px] min-w-[44px] items-center justify-center rounded-full leading-none hover:bg-muted-foreground/20"
            onClick={onDelete}
          >
            ✕
          </button>
        </>
      )}
    </span>
  )
}
