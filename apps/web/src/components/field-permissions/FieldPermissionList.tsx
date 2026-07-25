// 필드 권한 규칙 목록 — 4분기(로딩/에러/빈/목록) + 생성 Dialog 연동 + MANAGE_FIELD_PERMISSIONS 게이팅 (FR-PM-07)
import { useState } from 'react'
import type { JSX } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  useFieldPermissions,
  useCreateFieldPermission,
  useDeleteFieldPermission,
} from '@/hooks/use-field-permissions'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { extractFieldPermissionErrorCode } from '@/api/field-permissions'
import type { CreateFieldPermissionInput } from '@/api/field-permissions.types'
import { FieldPermissionRow } from './FieldPermissionRow'
import { FieldPermissionFormDialog } from './FieldPermissionFormDialog'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface FieldPermissionListProps {
  /** 필드 권한 규칙을 표시할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 메시지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function fieldPermissionErrorMessage(code: string | null): string {
  switch (code) {
    case 'FIELD_PERMISSION_DUPLICATE':
      return '이미 동일한 필드 권한 규칙이 있습니다.'
    case 'FIELD_PERMISSION_NOT_FOUND':
      return '필드 권한 규칙을 찾을 수 없습니다.'
    case 'FIELD_PERMISSION_PROJECT_NOT_FOUND':
      return '프로젝트를 찾을 수 없습니다.'
    case 'FIELD_PERMISSION_ACCESS_DENIED':
      return '권한이 없습니다.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

function FieldPermissionListSkeleton(): JSX.Element {
  return (
    <ul
      role="status"
      aria-label="필드 권한 규칙 목록 로딩 중"
      className="space-y-2"
    >
      {[1, 2, 3].map((i) => (
        <li key={i}>
          <Skeleton className="h-14 w-full rounded-md border" />
        </li>
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FieldPermissionList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 필드 권한 규칙 목록 컴포넌트.
 *
 * - useFieldPermissions로 목록 조회.
 * - 4분기: 로딩 → 스켈레톤, 에러 → 에러 메시지, 빈 → 빈 상태, 목록 → FieldPermissionRow 렌더.
 * - "규칙 추가" 버튼 → FieldPermissionFormDialog 엶.
 * - MANAGE_FIELD_PERMISSIONS 권한 없으면 버튼 disabled (fail-closed).
 * - 생성 에러(409 등) → FieldPermissionFormDialog submitError 표시, Dialog 유지.
 *   (dialog-submiterror-ownership-dead-path 교훈: submitError는 부모에서 관리)
 * - 삭제: FieldPermissionRow의 onDelete → useDeleteFieldPermission.mutate 호출.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function FieldPermissionList({ projectKey }: FieldPermissionListProps): JSX.Element {
  const { data: rules, isLoading, isError, error } = useFieldPermissions(projectKey)

  // silent:true — Dialog 경로는 per-call onError의 submitError 인라인 표시만 사용, toast 이중 발사 방지
  const createFieldPermission = useCreateFieldPermission(projectKey, { silent: true })
  const deleteFieldPermission = useDeleteFieldPermission(projectKey)

  // 권한 게이팅 — fail-closed: 로딩/에러/미인가이면 false
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)
  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_FIELD_PERMISSIONS === true

  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  // ── Dialog 열기
  function openCreateDialog(): void {
    setSubmitError(null)
    setDialogOpen(true)
  }

  // ── Dialog 닫기
  function handleOpenChange(open: boolean): void {
    if (!open) {
      setDialogOpen(false)
      setSubmitError(null)
    }
  }

  // ── 저장 콜백
  function handleSubmit(input: CreateFieldPermissionInput): void {
    createFieldPermission.mutate(input, {
      onSuccess: () => {
        setDialogOpen(false)
        setSubmitError(null)
      },
      onError: (err: unknown) => {
        const code = extractFieldPermissionErrorCode(err)
        setSubmitError(fieldPermissionErrorMessage(code))
      },
    })
  }

  // ── 삭제 콜백
  function handleDelete(ruleId: string): void {
    deleteFieldPermission.mutate(ruleId)
  }

  // ── 로딩
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>필드 권한 규칙</CardTitle>
        </CardHeader>
        <CardContent>
          <FieldPermissionListSkeleton />
        </CardContent>
      </Card>
    )
  }

  // ── 에러
  if (isError) {
    const code = extractFieldPermissionErrorCode(error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>필드 권한 규칙</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive">{fieldPermissionErrorMessage(code)}</p>
        </CardContent>
      </Card>
    )
  }

  const ruleList = rules ?? []

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>필드 권한 규칙</CardTitle>
          <Button
            size="sm"
            disabled={!canManage}
            aria-disabled={!canManage}
            title={!canManage ? '권한이 없습니다.' : undefined}
            onClick={openCreateDialog}
          >
            규칙 추가
          </Button>
        </CardHeader>
        <CardContent>
          {ruleList.length === 0 ? (
            <p className="text-sm text-muted-foreground">아직 필드 권한 규칙이 없습니다.</p>
          ) : (
            <ul className="space-y-2">
              {ruleList.map((rule) => (
                <FieldPermissionRow
                  key={rule.id}
                  rule={rule}
                  onDelete={handleDelete}
                  isDeleting={deleteFieldPermission.isPending}
                  canManage={canManage}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <FieldPermissionFormDialog
        open={dialogOpen}
        onSubmit={handleSubmit}
        onOpenChange={handleOpenChange}
        submitError={submitError}
        projectKey={projectKey}
      />
    </>
  )
}
