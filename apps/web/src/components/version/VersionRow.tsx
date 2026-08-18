// 버전 단일 행 — 상태 뱃지 + 전환 버튼 + 수정/삭제 + ARCHIVED 비활성화 + 릴리즈 노트 버튼 (FR-VR-01, FR-VR-02, FR-VR-04)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { useDeleteVersion, useChangeVersionStatus } from '@/hooks/use-versions'
import { versionLabels, versionStatusLabel, versionTransitionLabel } from '@/i18n/version-labels'
import { useDateFormat } from '@/hooks/use-date-format'
import type { Version, VersionStatus } from '@/api/versions.types'
import { ReleaseNotesDialog } from './ReleaseNotesDialog'

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
// 상태 뱃지 Tailwind 클래스 매핑
// ─────────────────────────────────────────────────────────────────────────────

const BADGE_BASE = 'inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold'

const STATUS_BADGE_CLASS: Record<VersionStatus, string> = {
  UNRELEASED: `${BADGE_BASE} bg-secondary text-secondary-foreground`,
  RELEASED: `${BADGE_BASE} bg-primary text-primary-foreground`,
  ARCHIVED: `${BADGE_BASE} text-muted-foreground`,
}

// ─────────────────────────────────────────────────────────────────────────────
// 전환 액션 맵 — 현재 상태 → 가능한 전환 목록
// backend 전환 그래프와 1:1 미러 (FR-VR-02)
// ─────────────────────────────────────────────────────────────────────────────

interface TransitionAction {
  /** 전환 대상 상태 */
  targetStatus: VersionStatus
  /** 버튼 라벨 */
  label: string
  /** 버튼 variant */
  variant: 'outline' | 'secondary'
}

const TRANSITION_ACTIONS: Record<VersionStatus, readonly TransitionAction[]> = {
  UNRELEASED: [
    {
      targetStatus: 'RELEASED',
      label: versionTransitionLabel('release'),
      variant: 'outline',
    },
    {
      targetStatus: 'ARCHIVED',
      label: versionTransitionLabel('archive'),
      variant: 'outline',
    },
  ],
  RELEASED: [
    {
      targetStatus: 'UNRELEASED',
      label: versionTransitionLabel('unrelease'),
      variant: 'outline',
    },
    {
      targetStatus: 'ARCHIVED',
      label: versionTransitionLabel('archive'),
      variant: 'outline',
    },
  ],
  ARCHIVED: [
    {
      targetStatus: 'UNRELEASED',
      label: versionTransitionLabel('unarchive'),
      variant: 'outline',
    },
  ],
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
 * - 상태 뱃지 (UNRELEASED/RELEASED/ARCHIVED)
 * - 날짜 2열: startDate / releaseDate (null → "—")
 * - 전환 버튼: 현재 상태에서 가능한 전환만 — TRANSITION_ACTIONS 맵
 * - 수정 버튼: onEdit(version) 호출 → 상위에서 VersionFormDialog를 엶
 * - 삭제 버튼: 인라인 확인 UI → useDeleteVersion.mutate → onDelete(id) 호출
 * - ARCHIVED 상태에서 수정/삭제 버튼 disabled (읽기 전용)
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
  const [releaseNotesOpen, setReleaseNotesOpen] = useState(false)
  const { formatDate } = useDateFormat()

  const deleteMutation = useDeleteVersion(projectKey)
  const statusMutation = useChangeVersionStatus(projectKey)

  const { actions } = versionLabels

  const isArchived = version.status === 'ARCHIVED'
  // ARCHIVED이면 수정/삭제 불가 (canManage 무관)
  const canEditOrDelete = canManage && !isArchived

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

  function handleTransition(targetStatus: VersionStatus): void {
    statusMutation.mutate({ id: version.id, status: targetStatus })
  }

  const transitionActions = TRANSITION_ACTIONS[version.status]

  return (
    <li className="flex flex-col gap-3 rounded-md border px-4 py-3 sm:flex-row sm:items-start sm:justify-between">
      {/* 이름 + 설명 + 상태 뱃지 */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="block truncate text-sm font-medium">{version.name}</span>
          <span className={STATUS_BADGE_CLASS[version.status]}>
            {versionStatusLabel(version.status)}
          </span>
        </div>
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
        {/* 전환 버튼 — 현재 상태에서 가능한 전환만 */}
        {transitionActions.map((action) => (
          <Button
            key={action.targetStatus}
            variant={action.variant}
            size="sm"
            disabled={!canManage || statusMutation.isPending}
            aria-label={`${version.name} ${action.label}`}
            onClick={() => handleTransition(action.targetStatus)}
          >
            {action.label}
          </Button>
        ))}

        {/* 릴리즈 노트 버튼 — ARCHIVED 무관 활성 (읽기 동작) */}
        <Button
          variant="outline"
          size="sm"
          aria-label={`${version.name} ${actions.releaseNotesButton}`}
          onClick={() => { setReleaseNotesOpen(true) }}
        >
          {actions.releaseNotesButton}
        </Button>

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
              disabled={!canEditOrDelete}
              title={
                isArchived
                  ? 'ARCHIVED 버전은 수정할 수 없습니다.'
                  : !canManage
                    ? versionLabels.actions.noPermission
                    : undefined
              }
              onClick={handleEditClick}
            >
              {actions.editButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${version.name} ${actions.deleteButton}`}
              disabled={!canEditOrDelete}
              title={
                isArchived
                  ? 'ARCHIVED 버전은 삭제할 수 없습니다.'
                  : !canManage
                    ? versionLabels.actions.noPermission
                    : undefined
              }
              onClick={handleDeleteClick}
            >
              {actions.deleteButton}
            </Button>
          </>
        )}
      </div>

      {/* 릴리즈 노트 다이얼로그 — 행 단위 open state */}
      <ReleaseNotesDialog
        versionId={version.id}
        versionName={version.name}
        projectKey={projectKey}
        open={releaseNotesOpen}
        onOpenChange={setReleaseNotesOpen}
      />
    </li>
  )
}
