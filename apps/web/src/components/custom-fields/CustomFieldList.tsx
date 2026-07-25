// 커스텀 필드 목록 — 4분기(로딩/에러/빈/목록) + 생성/수정 Dialog 연동 + 권한 게이팅 (FR-IS-10 D6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useCustomFields, useCreateCustomField, useUpdateCustomField, useDeleteCustomField } from '@/hooks/use-custom-fields'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { customFieldLabels, customFieldErrorMessage } from '@/i18n/custom-field-labels'
import { CustomFieldRow } from './CustomFieldRow'
import { CustomFieldFormDialog } from './CustomFieldFormDialog'
import type { CustomField, CreateCustomFieldInput, UpdateCustomFieldInput } from '@/api/custom-fields.types'
import { extractCustomFieldErrorCode } from '@/api/custom-fields'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface CustomFieldListProps {
  /** 커스텀 필드를 표시할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog 상태 타입
// ─────────────────────────────────────────────────────────────────────────────

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; field: CustomField }

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

function CustomFieldListSkeleton(): JSX.Element {
  return (
    <ul
      role="status"
      aria-label={customFieldLabels.page.loadingStatus}
      className="space-y-2"
    >
      {[1, 2, 3].map((i) => (
        <li key={i}>
          <Skeleton className="h-14 w-full border" />
        </li>
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CustomFieldList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 커스텀 필드 목록 컴포넌트.
 *
 * - useCustomFields로 목록 조회. displayOrder 오름차순 정렬은 서버(MSW/백엔드)가 담당.
 * - 4분기: 로딩 → 스켈레톤, 에러 → 에러 메시지, 빈 → 빈 상태, 목록 → CustomFieldRow 렌더.
 * - "필드 추가" 버튼 → CustomFieldFormDialog(mode='create') 엶.
 * - CustomFieldRow의 onEdit → CustomFieldFormDialog(mode='edit', initial=field) 엶.
 * - 생성/수정 에러(409 등) → CustomFieldFormDialog submitError 표시, Dialog 유지.
 * - 삭제: CustomFieldRow의 onDelete → useDeleteCustomField.mutate 호출.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function CustomFieldList({ projectKey }: CustomFieldListProps): JSX.Element {
  const { data: fields, isLoading, isError, error } = useCustomFields(projectKey)
  // silent:true — Dialog 경로는 per-call onError의 submitError 인라인 표시만 사용, toast 이중 발사 방지 (C3)
  const createCustomField = useCreateCustomField(projectKey, { silent: true })
  const updateCustomField = useUpdateCustomField(projectKey, { silent: true })
  const deleteCustomField = useDeleteCustomField(projectKey)

  // 권한 게이팅 — fail-closed: 로딩/에러/미인가이면 false (FR-PM-03 D6)
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)
  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_CUSTOM_FIELDS === true

  const [dialogState, setDialogState] = useState<DialogState>({ open: false })
  const [submitError, setSubmitError] = useState<string | null>(null)

  // ── Dialog 열기
  function openCreateDialog(): void {
    setSubmitError(null)
    setDialogState({ open: true, mode: 'create' })
  }

  function openEditDialog(field: CustomField): void {
    setSubmitError(null)
    setDialogState({ open: true, mode: 'edit', field })
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
      const code = extractCustomFieldErrorCode(err)
      setSubmitError(customFieldErrorMessage(code))
    },
  }

  // ── 저장 콜백 — create/edit 분기
  function handleSubmit(input: CreateCustomFieldInput | UpdateCustomFieldInput): void {
    if (!dialogState.open) return

    if (dialogState.mode === 'create') {
      createCustomField.mutate(input as CreateCustomFieldInput, mutationCallbacks)
    } else {
      updateCustomField.mutate(
        { fieldId: dialogState.field.id, input: input as UpdateCustomFieldInput },
        mutationCallbacks,
      )
    }
  }

  // ── 삭제 콜백
  function handleDelete(fieldId: string): void {
    deleteCustomField.mutate(fieldId)
  }

  // ── 로딩
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{customFieldLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <CustomFieldListSkeleton />
        </CardContent>
      </Card>
    )
  }

  // ── 에러 (PROJECT_NOT_FOUND는 상위 페이지가 처리)
  if (isError) {
    const code = extractCustomFieldErrorCode(error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>{customFieldLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive">{customFieldErrorMessage(code)}</p>
        </CardContent>
      </Card>
    )
  }

  const fieldList = fields ?? []

  // ── Dialog 렌더 시 initial 결정
  const dialogInitial: CustomField | undefined =
    dialogState.open && dialogState.mode === 'edit' ? dialogState.field : undefined

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{customFieldLabels.page.heading}</CardTitle>
          <Button
            size="sm"
            disabled={!canManage}
            aria-disabled={!canManage}
            title={!canManage ? customFieldLabels.actions.noPermission : undefined}
            onClick={openCreateDialog}
          >
            {customFieldLabels.actions.addButton}
          </Button>
        </CardHeader>
        <CardContent>
          {fieldList.length === 0 ? (
            <p className="text-sm text-muted-foreground">{customFieldLabels.page.emptyMessage}</p>
          ) : (
            <ul className="space-y-2">
              {fieldList.map((field) => (
                <CustomFieldRow
                  key={field.id}
                  field={field}
                  onEdit={openEditDialog}
                  onDelete={handleDelete}
                  isDeleting={deleteCustomField.isPending}
                  canManage={canManage}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <CustomFieldFormDialog
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
