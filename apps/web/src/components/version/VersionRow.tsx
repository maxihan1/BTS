// 버전 단일 행 — 이름/설명/날짜 표시 + 수정/삭제 인라인 확인 액션 + 권한 게이팅 (FR-VR-01, FR-PM-03)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { useDeleteVersion } from '@/hooks/use-versions'
import { versionLabels } from '@/i18n/version-labels'
import type { Version } from '@/api/versions.types'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface VersionRowProps {
  /** 표시할 버전 데이터 */
  readonly version: Version
  /** 프로젝트 식별 키 */
  readonly projectKey: string
  /** 수정 버튼 클릭 시 상위에서 Dialog를 열기 위한 콜백 */
  readonly onEdit: (version: Version) => void
  /** 삭제 완료 후 상위에 알리는 콜백 */
  readonly onDelete: (id: string) => void
  /**
   * MANAGE_VERSIONS 권한 여부 — VersionList가 useProjectPermissions로 계산해 전달.
   * false(로딩/에러/미인가)이면 수정·삭제 버튼을 disabled로 게이팅(fail-closed).
   */
  readonly canManage: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 표시 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 날짜 문자열을 표시한다.
 * null인 경우 "—"를 반환한다.
 *
 * @param date yyyy-MM-dd 형식의 날짜 문자열 또는 null
 * @returns 표시할 문자열
 */
function formatDate(date: string | null): string {
  return date ?? '—'
}

// ─────────────────────────────────────────────────────────────────────────────
// DeleteConfirm — 인라인 삭제 확인 UI (window.confirm 대신 inline)
// ComponentRow.tsx L70-93 동형
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmProps {
  readonly onConfirm: () => void
  readonly onCancel: () => void
  readonly isDeleting: boolean
}

function DeleteConfirm({ onConfirm, onCancel, isDeleting }: DeleteConfirmProps): JSX.Element {
  const { actions } = versionLabels
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
// VersionRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 단일 행 컴포넌트.
 *
 * - 이름 · 설명 표시
 * - 날짜 2열: startDate / releaseDate (null → "—")
 * - 수정 버튼: onEdit(version) 호출 → 상위에서 VersionFormDialog를 엶
 * - 삭제 버튼: 인라인 확인 UI → useDeleteVersion.mutate → onDelete(id) 호출
 * - 행 단위 aria-label (E2E strict mode 회피)
 * - 토스트는 hook 레이어(use-versions)에서 발사 — 행에서 중복 발사 금지
 */
export function VersionRow({
  version,
  projectKey,
  onEdit,
  onDelete,
  canManage,
}: VersionRowProps): JSX.Element {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  const deleteMutation = useDeleteVersion(projectKey)

  const { actions } = versionLabels

  function handleEditClick(): void {
    onEdit(version)
  }

  function handleDeleteClick(): void {
    setShowDeleteConfirm(true)
  }

  function handleDeleteConfirm(): void {
    deleteMutation.mutate(version.id, {
      onSuccess: () => {
        setShowDeleteConfirm(false)
        onDelete(version.id)
      },
      onError: () => {
        setShowDeleteConfirm(false)
      },
    })
  }

  function handleDeleteCancel(): void {
    setShowDeleteConfirm(false)
  }

  return (
    <li className="flex flex-col gap-3 rounded-md border px-4 py-3 sm:flex-row sm:items-start sm:justify-between">
      {/* 이름 + 설명 */}
      <div className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium">{version.name}</span>
        {version.description !== null && (
          <span className="block truncate text-xs text-muted-foreground">
            {version.description}
          </span>
        )}
      </div>

      {/* 날짜 2열: 시작일 / 릴리즈 예정일 */}
      <div className="flex shrink-0 gap-4 text-xs text-muted-foreground sm:items-center">
        <div className="flex flex-col gap-0.5">
          <span className="font-medium text-foreground">시작일</span>
          <span>{formatDate(version.startDate)}</span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="font-medium text-foreground">릴리즈 예정일</span>
          <span>{formatDate(version.releaseDate)}</span>
        </div>
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
              aria-label={`${version.name} ${actions.editButton}`}
              disabled={!canManage}
              title={!canManage ? versionLabels.actions.noPermission : undefined}
              onClick={handleEditClick}
            >
              {actions.editButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${version.name} ${actions.deleteButton}`}
              disabled={!canManage}
              title={!canManage ? versionLabels.actions.noPermission : undefined}
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
