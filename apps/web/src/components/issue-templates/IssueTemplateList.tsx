// 이슈 템플릿 목록 — 4분기(로딩/에러/빈/목록) + 생성/수정 Dialog 연동 + 권한 게이팅 (FR-TM-01 D6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useIssueTemplates, useDeleteIssueTemplate } from '@/hooks/use-issue-templates'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { issueTemplateLabels, issueTemplateErrorMessage } from '@/i18n/issue-template-labels'
import { extractIssueTemplateErrorCode } from '@/api/issue-templates'
import type { IssueTemplate } from '@/api/issue-templates.types'
import { IssueTemplateRow } from './IssueTemplateRow'
import { IssueTemplateFormDialog } from './IssueTemplateFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface IssueTemplateListProps {
  /** 이슈 템플릿을 표시할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog 상태 타입
// ─────────────────────────────────────────────────────────────────────────────

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; template: IssueTemplate }

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

function IssueTemplateListSkeleton(): JSX.Element {
  return (
    <ul
      role="status"
      aria-label={issueTemplateLabels.page.loadingStatus}
      className="space-y-2"
    >
      {[1, 2, 3].map((i) => (
        <li key={i} className="h-14 rounded-md border animate-pulse bg-muted" />
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueTemplateList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 이슈 템플릿 목록 컴포넌트.
 *
 * - useIssueTemplates로 목록 조회. 정렬은 서버(MSW/백엔드)가 담당.
 * - 4분기: 로딩 → 스켈레톤, 에러 → 에러 메시지, 빈 → 빈 상태 안내, 목록 → IssueTemplateRow 렌더.
 * - "템플릿 추가" 버튼 → IssueTemplateFormDialog(mode='create') 엶.
 * - IssueTemplateRow의 onEdit → IssueTemplateFormDialog(mode='edit', initial=template) 엶.
 * - 삭제: IssueTemplateRow의 onDelete → useDeleteIssueTemplate.mutate 호출.
 * - 권한 게이팅: useProjectPermissions(projectKey).MANAGE_TEMPLATES fail-closed.
 *   로딩/에러/미인가 → 추가/수정/삭제 버튼 disabled + title 안내.
 *   목록 조회는 항상 표시한다.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function IssueTemplateList({ projectKey }: IssueTemplateListProps): JSX.Element {
  const { data: templates, isLoading, isError, error } = useIssueTemplates(projectKey)
  const deleteIssueTemplate = useDeleteIssueTemplate(projectKey)

  // 이슈 타입 목록 — issueTypeId → 표시명 해석에 사용
  const { data: issueTypes = [] } = useIssueTypes()

  // issueTypeId → 표시명 맵 (빠른 조회)
  const issueTypeNameMap = new Map<number, string>(
    issueTypes.map((it) => [it.id, it.name]),
  )

  // 권한 게이팅 — fail-closed: 로딩/에러/미인가이면 false
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)
  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_TEMPLATES === true

  const [dialogState, setDialogState] = useState<DialogState>({ open: false })

  // ── Dialog 열기

  function openCreateDialog(): void {
    setDialogState({ open: true, mode: 'create' })
  }

  function openEditDialog(template: IssueTemplate): void {
    setDialogState({ open: true, mode: 'edit', template })
  }

  // ── Dialog 닫기

  function handleOpenChange(open: boolean): void {
    if (!open) {
      setDialogState({ open: false })
    }
  }

  // ── 삭제 콜백

  function handleDelete(templateId: string): void {
    deleteIssueTemplate.mutate(templateId)
  }

  // ── 로딩

  if (isLoading) {
    return (
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{issueTemplateLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <IssueTemplateListSkeleton />
        </CardContent>
      </Card>
    )
  }

  // ── 에러 (PROJECT_NOT_FOUND는 상위 페이지가 처리)

  if (isError) {
    const code = extractIssueTemplateErrorCode(error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>{issueTemplateLabels.page.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive">{issueTemplateErrorMessage(code)}</p>
        </CardContent>
      </Card>
    )
  }

  const templateList = templates ?? []

  // ── Dialog의 initial — edit 모드일 때만
  const dialogInitial: IssueTemplate | undefined =
    dialogState.open && dialogState.mode === 'edit' ? dialogState.template : undefined

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle>{issueTemplateLabels.page.heading}</CardTitle>
          <Button
            size="sm"
            disabled={!canManage}
            aria-disabled={!canManage}
            title={!canManage ? issueTemplateLabels.actions.noPermission : undefined}
            onClick={openCreateDialog}
          >
            {issueTemplateLabels.actions.addButton}
          </Button>
        </CardHeader>
        <CardContent>
          {templateList.length === 0 ? (
            <p className="text-sm text-muted-foreground">{issueTemplateLabels.page.emptyMessage}</p>
          ) : (
            <ul className="space-y-2">
              {templateList.map((template) => (
                <IssueTemplateRow
                  key={template.id}
                  template={template}
                  issueTypeName={issueTypeNameMap.get(template.issueTypeId)}
                  onEdit={openEditDialog}
                  onDelete={handleDelete}
                  isDeleting={deleteIssueTemplate.isPending}
                  canManage={canManage}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {dialogInitial !== undefined ? (
        <IssueTemplateFormDialog
          open={dialogState.open}
          mode="edit"
          projectKey={projectKey}
          initial={dialogInitial}
          onSubmitSuccess={() => { setDialogState({ open: false }) }}
          onOpenChange={handleOpenChange}
        />
      ) : (
        <IssueTemplateFormDialog
          open={dialogState.open}
          mode="create"
          projectKey={projectKey}
          onSubmitSuccess={() => { setDialogState({ open: false }) }}
          onOpenChange={handleOpenChange}
        />
      )}
    </>
  )
}
