// 저장된 필터 드롭다운 메뉴 — 내 필터/공유받은 필터 두 섹션 + 편집·공유·삭제 액션 (FR-SR-03 Task-5)
import { useState } from 'react'
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { SlidersHorizontal, Pencil, Share2, Trash2 } from 'lucide-react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu'
import {
  fetchOwnedFilters,
  fetchSharedFilters,
  deleteFilter,
  savedFiltersKey,
} from '@/api/saved-filters'
import type { SavedFilterResponse } from '@/api/saved-filters'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { FAVORITE_TARGET_TYPES } from '@/api/favorites'
import { SaveFilterDialog } from './SaveFilterDialog'
import { ShareFilterDialog } from './ShareFilterDialog'
import { savedFilterLabels } from '@/i18n/saved-filter-labels'

/** 공유받은 필터 목록 페이지 크기 — spec FR-3/EC7: size=50 (21~50개 silent 누락 방지) */
const SHARED_PAGE_SIZE = 50

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — SPA 경로 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 필터의 검색 결과 SPA 경로를 반환한다.
 * TanStack Router Link to 타입 호환을 위해 string 반환 (toFilterPath 패턴).
 *
 * @param filterId 필터 UUID
 * @returns /search?filterId=<filterId>
 */
function toFilterPath(filterId: string): string {
  return `/search?filterId=${filterId}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 컴포넌트 — 단일 필터 행
// ─────────────────────────────────────────────────────────────────────────────

interface FilterRowProps {
  /** 렌더할 저장 필터 */
  filter: SavedFilterResponse
  /** 편집 버튼 클릭 핸들러 */
  onEdit: (filter: SavedFilterResponse) => void
  /** 공유 버튼 클릭 핸들러 */
  onShare: (filter: SavedFilterResponse) => void
  /** 삭제 버튼 클릭 핸들러 */
  onDelete: (filterId: string) => void
}

/**
 * 저장 필터 단일 행.
 * isOwner=true이면 편집·공유·삭제 액션을 추가 노출한다 (EC5).
 * isOwner=false이면 FavoriteButton만 노출한다.
 */
function FilterRow({ filter, onEdit, onShare, onDelete }: FilterRowProps): JSX.Element {
  return (
    <div className="flex items-center gap-1 px-1 py-0.5 group">
      <Link
        to={toFilterPath(filter.id)}
        className="flex-1 truncate rounded px-2 py-1.5 text-sm hover:bg-accent"
      >
        {filter.name}
      </Link>

      <FavoriteButton targetType={FAVORITE_TARGET_TYPES.FILTER} targetId={filter.id} />

      {filter.isOwner && (
        <>
          <Button
            variant="ghost"
            size="icon"
            className="size-7 shrink-0"
            aria-label={savedFilterLabels.editButton}
            onClick={() => { onEdit(filter) }}
          >
            <Pencil className="size-3.5" aria-hidden />
          </Button>

          <Button
            variant="ghost"
            size="icon"
            className="size-7 shrink-0"
            aria-label={savedFilterLabels.shareButton}
            onClick={() => { onShare(filter) }}
          >
            <Share2 className="size-3.5" aria-hidden />
          </Button>

          <Button
            variant="ghost"
            size="icon"
            className="size-7 shrink-0 text-destructive hover:text-destructive"
            aria-label={savedFilterLabels.deleteButton}
            onClick={() => { onDelete(filter.id) }}
          >
            <Trash2 className="size-3.5" aria-hidden />
          </Button>
        </>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 컴포넌트 — 삭제 확인 섹션
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmSectionProps {
  /** mutation 진행 중 여부 */
  isPending: boolean
  /** 확인 클릭 핸들러 */
  onConfirm: () => void
  /** 취소 클릭 핸들러 */
  onCancel: () => void
}

/**
 * 드롭다운 내 삭제 확인 섹션.
 * 버튼 요소이므로 클릭 시 DropdownMenu가 닫히지 않는다.
 */
function DeleteConfirmSection({ isPending, onConfirm, onCancel }: DeleteConfirmSectionProps): JSX.Element {
  return (
    <div className="border-t px-2 py-2">
      <p className="mb-2 text-sm">{savedFilterLabels.deleteConfirm}</p>
      <div className="flex justify-end gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={onCancel}
          disabled={isPending}
        >
          {savedFilterLabels.cancelButton}
        </Button>
        <Button
          variant="destructive"
          size="sm"
          onClick={onConfirm}
          disabled={isPending}
        >
          {savedFilterLabels.deleteConfirmButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 공개 컴포넌트 — SavedFilterMenu
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장된 필터 드롭다운 메뉴.
 *
 * - 내 필터(fetchOwnedFilters) / 공유받은 필터(fetchSharedFilters) 두 섹션 렌더
 * - 소유 항목(isOwner=true): 편집·공유·삭제 액션 노출 (EC5)
 * - 공유받은 항목(isOwner=false): FavoriteButton만 노출
 * - 삭제는 인라인 확인 섹션을 통해 이중 확인
 * - 모든 mutation 성공 시 savedFiltersKey.all() 접두사로 전체 invalidate
 *   (단건 캐시 머지 대신 invalidate-only 방식 — mutation-setquerydata-partial-response-flicker 교훈)
 */
export const SavedFilterMenu = (): JSX.Element => {
  const queryClient = useQueryClient()

  const { data: ownedFilters = [] } = useQuery({
    queryKey: savedFiltersKey.owned(),
    queryFn: fetchOwnedFilters,
    staleTime: 30_000,
  })

  const { data: sharedFilters = [] } = useQuery({
    queryKey: savedFiltersKey.shared(0, SHARED_PAGE_SIZE),
    queryFn: () => fetchSharedFilters(0, SHARED_PAGE_SIZE),
    staleTime: 30_000,
  })

  const [editFilter, setEditFilter] = useState<SavedFilterResponse | null>(null)
  const [shareFilter, setShareFilter] = useState<SavedFilterResponse | null>(null)
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)

  /** saved-filters 접두사 전체를 무효화한다 — 편집·삭제 성공 후 공통 호출 */
  function invalidateAll(): void {
    void queryClient.invalidateQueries({ queryKey: savedFiltersKey.all() })
  }

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteFilter(id),
    onSuccess: () => {
      setDeleteConfirmId(null)
      invalidateAll()
    },
    onError: () => {
      toast.error(savedFilterLabels.deleteError)
    },
  })

  const isEmpty = ownedFilters.length === 0 && sharedFilters.length === 0

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className="flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-medium hover:bg-accent"
            aria-label={savedFilterLabels.menuTriggerAriaLabel}
          >
            <SlidersHorizontal className="size-4" aria-hidden />
          </button>
        </DropdownMenuTrigger>

        <DropdownMenuContent align="end" className="w-80">
          <DropdownMenuLabel>{savedFilterLabels.sectionTitle}</DropdownMenuLabel>
          <DropdownMenuSeparator />

          {/* 내 필터 섹션 */}
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
            {savedFilterLabels.myFiltersTab}
          </DropdownMenuLabel>
          {ownedFilters.map((filter) => (
            <FilterRow
              key={filter.id}
              filter={filter}
              onEdit={setEditFilter}
              onShare={setShareFilter}
              onDelete={setDeleteConfirmId}
            />
          ))}

          <DropdownMenuSeparator />

          {/* 공유받은 필터 섹션 */}
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
            {savedFilterLabels.sharedFiltersSection}
          </DropdownMenuLabel>
          {sharedFilters.map((filter) => (
            <FilterRow
              key={filter.id}
              filter={filter}
              onEdit={setEditFilter}
              onShare={setShareFilter}
              onDelete={setDeleteConfirmId}
            />
          ))}

          {/* 빈 상태 */}
          {isEmpty && (
            <p className="px-2 py-3 text-center text-sm text-muted-foreground">
              {savedFilterLabels.menuEmptyMessage}
            </p>
          )}

          {/* 삭제 확인 섹션 */}
          {deleteConfirmId !== null && (
            <DeleteConfirmSection
              isPending={deleteMutation.isPending}
              onConfirm={() => { deleteMutation.mutate(deleteConfirmId) }}
              onCancel={() => { setDeleteConfirmId(null) }}
            />
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* 편집 다이얼로그 — 드롭다운 외부 포탈로 렌더, invalidateAll은 저장 성공 후 공통 경로 사용 */}
      {editFilter !== null && (
        <SaveFilterDialog
          key={editFilter.id}
          open
          onOpenChange={(open) => { if (!open) setEditFilter(null) }}
          mode="edit"
          filter={editFilter}
          aqlQuery={editFilter.aqlQuery}
          projectKey={editFilter.projectKey}
          onSaved={() => {
            invalidateAll()
            setEditFilter(null)
          }}
        />
      )}

      {/* 공유 다이얼로그 — key={filter.id}로 재마운트 보장 (ShareFilterDialog JSDoc 명시) */}
      {shareFilter !== null && (
        <ShareFilterDialog
          key={shareFilter.id}
          open
          filter={shareFilter}
          onClose={() => { setShareFilter(null) }}
        />
      )}
    </>
  )
}
