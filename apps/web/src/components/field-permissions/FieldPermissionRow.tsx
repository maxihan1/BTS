// 필드 권한 규칙 단일 행 — 필드/그룹명/접근수준 표시 + 삭제 인라인 확인 (FR-PM-07)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import type { FieldPermissionResponse } from '@/api/field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface FieldPermissionRowProps {
  /** 표시할 필드 권한 규칙 데이터 */
  readonly rule: FieldPermissionResponse
  /** 삭제 확인 후 상위에서 mutation을 호출하기 위한 콜백 */
  readonly onDelete: (ruleId: string) => void
  /** 삭제 mutation pending 상태 */
  readonly isDeleting: boolean
  /**
   * MANAGE_FIELD_PERMISSIONS 권한 여부 — FieldPermissionList가 useProjectPermissions로 계산해 전달.
   * false(로딩/에러/미인가)이면 삭제 버튼을 disabled로 게이팅(fail-closed).
   */
  readonly canManage: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 접근 수준 레이블 매핑
// ─────────────────────────────────────────────────────────────────────────────

const ACCESS_LEVEL_LABEL: Record<string, string> = {
  VIEW: '보기',
  EDIT: '편집',
}

// ─────────────────────────────────────────────────────────────────────────────
// 필드 종류 레이블 매핑
// ─────────────────────────────────────────────────────────────────────────────

const FIELD_KIND_LABEL: Record<string, string> = {
  CORE: '기본 필드',
  CUSTOM: '커스텀 필드',
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
  return (
    <div className="flex items-center gap-2">
      <span className="text-sm text-muted-foreground">삭제하시겠습니까?</span>
      <Button
        variant="destructive"
        size="sm"
        disabled={isPending}
        onClick={onConfirm}
        aria-label="삭제 확인"
      >
        삭제 확인
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
// FieldPermissionRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙 단일 행 컴포넌트.
 *
 * - fieldKind 종류 칩(기본 필드/커스텀 필드) + fieldKey + 그룹명 + 접근 수준(보기/편집) 표시.
 * - 삭제 버튼: 인라인 확인 UI → onDelete(ruleId) 호출.
 * - canManage=false(로딩/에러/미인가)이면 삭제 버튼 disabled (fail-closed).
 *
 * @param rule 표시할 필드 권한 규칙
 * @param onDelete 삭제 확인 후 호출되는 콜백
 * @param isDeleting 삭제 뮤테이션 pending 여부
 * @param canManage MANAGE_FIELD_PERMISSIONS 권한 여부
 */
export function FieldPermissionRow({
  rule,
  onDelete,
  isDeleting,
  canManage,
}: FieldPermissionRowProps): JSX.Element {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  function handleDeleteClick(): void {
    setShowDeleteConfirm(true)
  }

  function handleDeleteConfirm(): void {
    onDelete(rule.id)
    setShowDeleteConfirm(false)
  }

  function handleDeleteCancel(): void {
    setShowDeleteConfirm(false)
  }

  return (
    <li
      data-testid="field-permission-row"
      className="flex flex-col gap-2 rounded-md border px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
    >
      {/* 정보 영역 */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          {/* fieldKind 칩 */}
          <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {FIELD_KIND_LABEL[rule.fieldKind] ?? rule.fieldKind}
          </span>
          {/* fieldKey */}
          <span className="text-sm font-medium truncate">{rule.fieldKey}</span>
        </div>
        <div className="flex items-center gap-2 mt-1 flex-wrap">
          {/* 그룹명 */}
          <span className="text-xs text-muted-foreground">{rule.groupName}</span>
          {/* 접근 수준 뱃지 */}
          <span
            className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
              rule.accessLevel === 'EDIT'
                ? 'bg-primary/10 text-primary'
                : 'bg-secondary text-secondary-foreground'
            }`}
          >
            {ACCESS_LEVEL_LABEL[rule.accessLevel] ?? rule.accessLevel}
          </span>
        </div>
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
          <Button
            variant="destructive"
            size="sm"
            aria-label={`${rule.fieldKey} 규칙 삭제`}
            disabled={!canManage}
            title={!canManage ? '권한이 없습니다.' : undefined}
            onClick={handleDeleteClick}
          >
            삭제
          </Button>
        )}
      </div>
    </li>
  )
}
