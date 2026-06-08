// 커스텀 필드 단일 행 — fieldType 칩 + required 표시 + 수정/삭제 액션 + 삭제 인라인 확인 (FR-IS-10 D6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { customFieldLabels } from '@/i18n/custom-field-labels'
import type { CustomField } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface CustomFieldRowProps {
  /** 표시할 커스텀 필드 데이터 */
  readonly field: CustomField
  /** 수정 버튼 클릭 시 상위에서 Dialog를 열기 위한 콜백 */
  readonly onEdit: (field: CustomField) => void
  /** 삭제 확인 후 상위에서 mutation을 호출하기 위한 콜백 */
  readonly onDelete: (fieldId: string) => void
  /** 삭제 mutation pending 상태 */
  readonly isDeleting: boolean
  /**
   * MANAGE_CUSTOM_FIELDS 권한 여부 — CustomFieldList가 useProjectPermissions로 계산해 전달.
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
  const { actions } = customFieldLabels
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
// CustomFieldRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 단일 행 컴포넌트.
 *
 * - 이름 · 설명 표시
 * - fieldType 한국어 칩 표시 (예: NUMBER → '숫자')
 * - required=true이면 '필수' 뱃지 표시
 * - 수정 버튼: onEdit(field) 호출 → 상위에서 CustomFieldFormDialog를 엶
 * - 삭제 버튼: 인라인 확인 UI → onDelete(fieldId) 호출
 * - canManage=false(로딩/에러/미인가)이면 수정·삭제 버튼 disabled (fail-closed)
 */
export function CustomFieldRow({
  field,
  onEdit,
  onDelete,
  isDeleting,
  canManage,
}: CustomFieldRowProps): JSX.Element {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const { actions, fieldTypes } = customFieldLabels

  function handleEditClick(): void {
    onEdit(field)
  }

  function handleDeleteClick(): void {
    setShowDeleteConfirm(true)
  }

  function handleDeleteConfirm(): void {
    onDelete(field.id)
    setShowDeleteConfirm(false)
  }

  function handleDeleteCancel(): void {
    setShowDeleteConfirm(false)
  }

  return (
    <li className="flex flex-col gap-2 rounded-md border px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      {/* 이름 + 설명 + 뱃지 영역 */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium truncate">{field.name}</span>
          {/* fieldType 칩 */}
          <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {fieldTypes[field.fieldType]}
          </span>
          {/* required 뱃지 */}
          {field.required && (
            <span className="inline-flex items-center rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              필수
            </span>
          )}
        </div>
        {field.description !== null && (
          <span className="block truncate text-xs text-muted-foreground mt-0.5">
            {field.description}
          </span>
        )}
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
              aria-label={`${field.name} ${actions.editButton}`}
              disabled={!canManage}
              title={!canManage ? actions.noPermission : undefined}
              onClick={handleEditClick}
            >
              {actions.editButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${field.name} ${actions.deleteButton}`}
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
