// 컴포넌트 단일 행 — 이름/설명/리드 표시 + 인라인 리드 변경 + 수정/삭제 액션 (FR-CM-01)
import { useState, useRef, useCallback } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { ComponentLeadSelect } from './ComponentLeadSelect'
import { useUsersByIds, useUsers } from '@/hooks/use-users'
import { useDeleteComponent, useChangeComponentLead } from '@/hooks/use-components'
import { componentLabels } from '@/i18n/component-labels'
import type { Component } from '@/api/components.types'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 리드 검색 debounce 대기 시간(ms) */
const SEARCH_DEBOUNCE_MS = 300

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface ComponentRowProps {
  /** 표시할 컴포넌트 데이터 */
  readonly component: Component
  /** 프로젝트 식별 키 */
  readonly projectKey: string
  /** 수정 버튼 클릭 시 상위에서 Dialog를 열기 위한 콜백 */
  readonly onEdit: (component: Component) => void
  /**
   * MANAGE_COMPONENTS 권한 여부 — ComponentList가 useProjectPermissions로 계산해 전달.
   * false(로딩/에러/미인가)이면 수정·삭제·리드변경 버튼을 disabled로 게이팅(fail-closed).
   */
  readonly canManage: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// useSearchUsers — 검색어 상태 + debounce + useUsers 조합 내부 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 리드 검색 상태를 관리하는 내부 훅.
 *
 * - handleSearch 호출 시 이전 타이머를 취소하고 새 타이머를 시작한다.
 *   (useRef로 타이머 id 보관 — Strict Mode 2회 실행에서도 안전)
 * - debouncedQuery가 안정되면 useUsers가 실제 API 요청을 보낸다.
 */
function useSearchUsers() {
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const timerRef = useRef<number | null>(null)

  const handleSearch = useCallback((query: string): void => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
    }
    timerRef.current = window.setTimeout(() => {
      setDebouncedQuery(query)
    }, SEARCH_DEBOUNCE_MS)
  }, [])

  const { data: searchResults = [] } = useUsers(debouncedQuery)

  return { handleSearch, searchResults }
}

// ─────────────────────────────────────────────────────────────────────────────
// DeleteConfirm — 인라인 삭제 확인 UI (window.confirm 대신 inline)
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmProps {
  readonly onConfirm: () => void
  readonly onCancel: () => void
  readonly isDeleting: boolean
}

function DeleteConfirm({ onConfirm, onCancel, isDeleting }: DeleteConfirmProps): JSX.Element {
  const { actions } = componentLabels
  return (
    <div className="flex items-center gap-2">
      <span className="text-sm text-muted-foreground">{actions.deleteConfirm}</span>
      <Button
        variant="destructive"
        size="sm"
        disabled={isDeleting}
        onClick={onConfirm}
      >
        {actions.deleteButton}
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={isDeleting}
        onClick={onCancel}
      >
        {actions.cancelButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ComponentRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 단일 행 컴포넌트.
 *
 * - 이름 · 설명 표시
 * - 리드 표시: useUsersByIds로 조회. leadUserId null이면 "미지정"
 * - 수정 버튼: onEdit(component) 호출 → 상위에서 ComponentFormDialog를 엶
 * - 삭제 버튼: 인라인 확인 UI → useDeleteComponent.mutate
 * - 인라인 리드 변경: ComponentLeadSelect + useChangeComponentLead
 * - 행 단위 aria-label (E2E strict mode 회피)
 * - 토스트는 hook 레이어(use-components)에서 발사 — 행에서 중복 발사 금지
 */
export function ComponentRow({
  component,
  projectKey,
  onEdit,
  canManage,
}: ComponentRowProps): JSX.Element {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  const { handleSearch, searchResults } = useSearchUsers()

  // 리드 표시 — leadUserId가 있을 때만 조회
  const leadIds = component.leadUserId !== null ? [component.leadUserId] : []
  const { data: leadUsers = [] } = useUsersByIds(leadIds)
  const currentLead = leadUsers[0] ?? null

  const deleteMutation = useDeleteComponent(projectKey)
  const changeLeadMutation = useChangeComponentLead(projectKey)

  const { actions } = componentLabels

  function handleEditClick(): void {
    onEdit(component)
  }

  function handleDeleteClick(): void {
    setShowDeleteConfirm(true)
  }

  function handleDeleteConfirm(): void {
    deleteMutation.mutate(component.id, {
      onSuccess: () => {
        setShowDeleteConfirm(false)
      },
      onError: () => {
        setShowDeleteConfirm(false)
      },
    })
  }

  function handleDeleteCancel(): void {
    setShowDeleteConfirm(false)
  }

  function handleLeadChange(userId: string | null): void {
    changeLeadMutation.mutate({ id: component.id, leadUserId: userId })
  }

  return (
    <li className="flex flex-col gap-3 rounded-md border px-4 py-3 sm:flex-row sm:items-start sm:justify-between">
      {/* 이름 + 설명 */}
      <div className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium">{component.name}</span>
        {component.description !== null && (
          <span className="block truncate text-xs text-muted-foreground">
            {component.description}
          </span>
        )}
      </div>

      {/* 인라인 리드 변경 셀렉터 — ComponentLeadSelect가 currentLead 표시도 담당 */}
      <div className="w-full sm:w-48 shrink-0">
        <ComponentLeadSelect
          users={searchResults}
          currentLead={currentLead}
          onSearch={handleSearch}
          onChange={handleLeadChange}
          disabled={!canManage || changeLeadMutation.isPending}
        />
      </div>

      {/* 액션 영역 */}
      <div className="flex items-center gap-2 shrink-0">
        {showDeleteConfirm ? (
          <DeleteConfirm
            onConfirm={handleDeleteConfirm}
            onCancel={handleDeleteCancel}
            isDeleting={deleteMutation.isPending}
          />
        ) : (
          <>
            <Button
              variant="outline"
              size="sm"
              aria-label={`${component.name} ${actions.editButton}`}
              disabled={!canManage}
              title={!canManage ? actions.noPermission : undefined}
              onClick={handleEditClick}
            >
              {actions.editButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${component.name} ${actions.deleteButton}`}
              disabled={!canManage}
              title={!canManage ? actions.noPermission : undefined}
              onClick={handleDeleteClick}
            >
              {actions.deleteButton}
            </Button>
          </>
        )}
      </div>
    </li>
  )
}
