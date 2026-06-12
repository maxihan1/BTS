// 이슈 템플릿 단일 행 — 이름 · 타입명 · 수정시각 + 수정/삭제 액션 + 인라인 삭제 확인 (FR-TM-01 D6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { issueTemplateLabels } from '@/i18n/issue-template-labels'
import type { IssueTemplate } from '@/api/issue-templates.types'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface IssueTemplateRowProps {
  /** 표시할 이슈 템플릿 데이터 */
  readonly template: IssueTemplate
  /**
   * issueTypeId → 표시명 해석 결과.
   * IssueTemplateList가 useIssueTypes()로 계산해 전달한다.
   * undefined이면 issueTypeId 숫자를 fallback으로 표시한다.
   */
  readonly issueTypeName: string | undefined
  /** 수정 버튼 클릭 시 상위에서 Dialog를 열기 위한 콜백 */
  readonly onEdit: (template: IssueTemplate) => void
  /** 삭제 확인 후 상위에서 mutation을 호출하기 위한 콜백 */
  readonly onDelete: (templateId: string) => void
  /** 삭제 mutation pending 상태 */
  readonly isDeleting: boolean
  /**
   * MANAGE_TEMPLATES 권한 여부 — IssueTemplateList가 useProjectPermissions로 계산해 전달.
   * false(로딩/에러/미인가)이면 수정·삭제 버튼을 disabled로 게이팅(fail-closed).
   */
  readonly canManage: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// DeleteConfirm — 인라인 삭제 확인 UI
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmProps {
  readonly onConfirm: () => void
  readonly onCancel: () => void
  readonly isPending: boolean
}

function DeleteConfirm({ onConfirm, onCancel, isPending }: DeleteConfirmProps): JSX.Element {
  const { actions } = issueTemplateLabels
  return (
    <div className="flex items-center gap-2">
      <span className="text-sm text-muted-foreground">{actions.deleteConfirm}</span>
      <Button
        variant="destructive"
        size="sm"
        disabled={isPending}
        onClick={onConfirm}
      >
        {actions.deleteButton}
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={isPending}
        onClick={onCancel}
      >
        취소
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueTemplateRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 단일 행 컴포넌트.
 *
 * - 이름 표시
 * - 이슈 타입명 칩 표시 (issueTypeName prop — IssueTemplateList에서 useIssueTypes로 해석해 전달)
 * - updatedAt 수정시각 표시 (time[dateTime] 요소)
 * - 수정 버튼: onEdit(template) 호출 → 상위에서 IssueTemplateFormDialog를 엶
 * - 삭제 버튼: 인라인 확인 UI → onDelete(templateId) 호출
 * - canManage=false(로딩/에러/미인가)이면 수정·삭제 버튼 disabled (fail-closed)
 */
export function IssueTemplateRow({
  template,
  issueTypeName,
  onEdit,
  onDelete,
  isDeleting,
  canManage,
}: IssueTemplateRowProps): JSX.Element {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const { actions } = issueTemplateLabels

  const displayTypeName = issueTypeName ?? String(template.issueTypeId)

  function handleEditClick(): void {
    onEdit(template)
  }

  function handleDeleteClick(): void {
    setShowDeleteConfirm(true)
  }

  function handleDeleteConfirm(): void {
    onDelete(template.id)
    setShowDeleteConfirm(false)
  }

  function handleDeleteCancel(): void {
    setShowDeleteConfirm(false)
  }

  return (
    <li className="flex flex-col gap-2 rounded-md border px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      {/* 이름 + 타입명 + 수정시각 영역 */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium truncate">{template.name}</span>
          {/* 이슈 타입 칩 */}
          <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {displayTypeName}
          </span>
        </div>
        {/* 수정시각 */}
        <time
          dateTime={template.updatedAt}
          className="block text-xs text-muted-foreground mt-0.5"
        >
          {new Date(template.updatedAt).toLocaleString('ko-KR', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
          })}
        </time>
      </div>

      {/* 액션 영역 */}
      <div className="flex items-center gap-2 shrink-0">
        {showDeleteConfirm ? (
          <DeleteConfirm
            onConfirm={handleDeleteConfirm}
            onCancel={handleDeleteCancel}
            isPending={isDeleting}
          />
        ) : (
          <>
            <Button
              variant="outline"
              size="sm"
              aria-label={`${template.name} ${actions.editButton}`}
              disabled={!canManage}
              title={!canManage ? actions.noPermission : undefined}
              onClick={handleEditClick}
            >
              {actions.editButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${template.name} ${actions.deleteButton}`}
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
