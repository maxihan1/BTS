// 프로젝트 컴포넌트 목록 — 4분기(로딩/에러/빈/목록) + 생성/수정 Dialog 연동 + 권한 게이팅 (FR-CM-01, FR-PM-03)
import { useState } from 'react'
import type { JSX } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useComponents, useCreateComponent, useUpdateComponent } from '@/hooks/use-components'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { componentLabels, componentErrorMessage } from '@/i18n/component-labels'
import { ComponentRow } from './ComponentRow'
import { ComponentFormDialog } from './ComponentFormDialog'
import type { Component, CreateComponentInput } from '@/api/components.types'
import { extractComponentErrorCode } from '@/api/components'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface ComponentListProps {
  /** 컴포넌트를 표시할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog 상태 타입
// ─────────────────────────────────────────────────────────────────────────────

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; component: Component }

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

function ComponentListSkeleton(): JSX.Element {
  return (
    <ul
      role="status"
      aria-label={componentLabels.page.loadingStatus}
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
// ComponentList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 컴포넌트 목록 컴포넌트.
 *
 * - useComponents로 목록 조회. name 오름차순 정렬은 서버(MSW/백엔드)가 담당.
 * - 4분기: 로딩 → 스켈레톤, 에러 → 에러 메시지, 빈 → 빈 상태, 목록 → ComponentRow 렌더.
 * - "컴포넌트 추가" 버튼 → ComponentFormDialog(mode='create') 엶.
 * - ComponentRow의 onEdit → ComponentFormDialog(mode='edit', initial=component) 엶.
 * - 생성/수정 에러(409 등) → ComponentFormDialog submitError 표시, Dialog 유지.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function ComponentList({ projectKey }: ComponentListProps): JSX.Element {
  const { data: components, isLoading, isError, error } = useComponents(projectKey)
  const createComponent = useCreateComponent(projectKey)
  const updateComponent = useUpdateComponent(projectKey)

  // 권한 게이팅 — fail-closed: 로딩/에러/미인가이면 false (FR-PM-03 D6)
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)
  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_COMPONENTS === true

  const [dialogState, setDialogState] = useState<DialogState>({ open: false })
  const [submitError, setSubmitError] = useState<string | null>(null)

  // ── Dialog 열기
  function openCreateDialog(): void {
    setSubmitError(null)
    setDialogState({ open: true, mode: 'create' })
  }

  function openEditDialog(component: Component): void {
    setSubmitError(null)
    setDialogState({ open: true, mode: 'edit', component })
  }

  // ── Dialog 닫기
  function handleOpenChange(open: boolean): void {
    if (!open) {
      setDialogState({ open: false })
      setSubmitError(null)
    }
  }

  // ── mutation 공통 콜백 헬퍼
  const mutationCallbacks = {
    onSuccess: () => {
      setDialogState({ open: false })
      setSubmitError(null)
    },
    onError: (err: unknown) => {
      const code = extractComponentErrorCode(err)
      setSubmitError(componentErrorMessage(code))
    },
  }

  // ── 저장 콜백 — create/edit 분기
  function handleSubmit(input: CreateComponentInput): void {
    if (!dialogState.open) return

    if (dialogState.mode === 'create') {
      createComponent.mutate(input, mutationCallbacks)
    } else {
      updateComponent.mutate(
        { id: dialogState.component.id, input },
        mutationCallbacks,
      )
    }
  }

  // ── 로딩
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{componentLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <ComponentListSkeleton />
        </CardContent>
      </Card>
    )
  }

  // ── 에러 (PROJECT_NOT_FOUND는 상위 Task 9 페이지가 처리)
  if (isError) {
    const code = extractComponentErrorCode(error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>{componentLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive">{componentErrorMessage(code)}</p>
        </CardContent>
      </Card>
    )
  }

  const componentList = components ?? []

  // ── Dialog 렌더 시 initial 결정
  const dialogInitial: Component | undefined =
    dialogState.open && dialogState.mode === 'edit' ? dialogState.component : undefined

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{componentLabels.page.heading}</CardTitle>
          <Button
            size="sm"
            disabled={!canManage}
            aria-disabled={!canManage}
            title={!canManage ? componentLabels.actions.noPermission : undefined}
            onClick={openCreateDialog}
          >
            {componentLabels.actions.addButton}
          </Button>
        </CardHeader>
        <CardContent>
          {componentList.length === 0 ? (
            <p className="text-sm text-muted-foreground">{componentLabels.page.emptyMessage}</p>
          ) : (
            <ul className="space-y-2">
              {componentList.map((component) => (
                <ComponentRow
                  key={component.id}
                  component={component}
                  projectKey={projectKey}
                  onEdit={openEditDialog}
                  canManage={canManage}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <ComponentFormDialog
        open={dialogState.open}
        mode={dialogState.open ? dialogState.mode : 'create'}
        initial={dialogInitial}
        onSubmit={handleSubmit}
        onOpenChange={handleOpenChange}
        submitError={submitError}
      />
    </>
  )
}
