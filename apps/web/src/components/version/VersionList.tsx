// 프로젝트 버전 목록 — 4분기(로딩/에러/빈/목록) + 생성/수정 Dialog 연동 + 권한 게이팅 (FR-VR-01, FR-PM-03)
import { useState } from 'react'
import type { JSX } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useVersions } from '@/hooks/use-versions'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { versionLabels, versionErrorMessage } from '@/i18n/version-labels'
import { VersionRow } from './VersionRow'
import { VersionFormDialog } from './VersionFormDialog'
import type { Version } from '@/api/versions.types'
import { extractVersionErrorCode } from '@/api/versions'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface VersionListProps {
  /** 버전을 표시할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog 상태 타입
// ─────────────────────────────────────────────────────────────────────────────

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; version: Version }

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

function VersionListSkeleton(): JSX.Element {
  return (
    <ul
      role="status"
      aria-label={versionLabels.page.loadingStatus}
      className="space-y-2"
    >
      {[1, 2, 3].map((i) => (
        <li key={i} className="h-14 rounded-md border animate-pulse bg-muted" />
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// VersionList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 버전 목록 컴포넌트.
 *
 * - useVersions로 목록 조회. name 오름차순 정렬은 서버(MSW/백엔드)가 담당.
 * - 4분기: 로딩 → 스켈레톤, 에러 → 에러 메시지, 빈 → 빈 상태, 목록 → VersionRow 렌더.
 * - "버전 추가" 버튼 → VersionFormDialog(mode='create') 엶.
 * - VersionRow의 onEdit → VersionFormDialog(mode='edit', initial=version) 엶.
 * - VersionRow의 onDelete → useDeleteVersion은 VersionRow/훅 레이어에서 처리.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function VersionList({ projectKey }: VersionListProps): JSX.Element {
  const { data: versions, isLoading, isError, error } = useVersions(projectKey)

  // 권한 게이팅 — fail-closed: 로딩/에러/미인가이면 false (FR-PM-03 D6)
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)
  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_VERSIONS === true

  const [dialogState, setDialogState] = useState<DialogState>({ open: false })

  // ── Dialog 열기
  function openCreateDialog(): void {
    setDialogState({ open: true, mode: 'create' })
  }

  function openEditDialog(version: Version): void {
    setDialogState({ open: true, mode: 'edit', version })
  }

  // ── Dialog 닫기
  function handleClose(): void {
    setDialogState({ open: false })
  }

  /** 삭제 콜백 — invalidateQueries는 useDeleteVersion 훅 레이어가 담당하므로 추가 처리 없음. */
  const handleDelete: (id: string) => void = () => { /* noop */ }

  // ── 로딩
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{versionLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <VersionListSkeleton />
        </CardContent>
      </Card>
    )
  }

  // ── 에러 (PROJECT_NOT_FOUND는 상위 페이지가 처리)
  if (isError) {
    const code = extractVersionErrorCode(error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>{versionLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive">{versionErrorMessage(code)}</p>
        </CardContent>
      </Card>
    )
  }

  const versionList = versions ?? []

  // ── Dialog 렌더 시 initial 결정
  const dialogInitial: Version | undefined =
    dialogState.open && dialogState.mode === 'edit' ? dialogState.version : undefined

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{versionLabels.page.heading}</CardTitle>
          <Button
            size="sm"
            disabled={!canManage}
            aria-disabled={!canManage}
            title={!canManage ? versionLabels.actions.noPermission : undefined}
            onClick={openCreateDialog}
          >
            {versionLabels.actions.addButton}
          </Button>
        </CardHeader>
        <CardContent>
          {versionList.length === 0 ? (
            <p className="text-sm text-muted-foreground">{versionLabels.page.emptyMessage}</p>
          ) : (
            <ul className="space-y-2">
              {versionList.map((version) => (
                <VersionRow
                  key={version.id}
                  version={version}
                  projectKey={projectKey}
                  onEdit={openEditDialog}
                  onDelete={handleDelete}
                  canManage={canManage}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <VersionFormDialog
        open={dialogState.open}
        mode={dialogState.open ? dialogState.mode : 'create'}
        projectKey={projectKey}
        initial={dialogInitial}
        onClose={handleClose}
      />
    </>
  )
}
