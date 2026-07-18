// 전역 권한 부여 목록의 단일 행 — 권한/대상/부여자/일시 표시 + 인라인 회수 확인 (FR-PM-10 D6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface GlobalPermissionRowProps {
  /** 전역 권한 부여 레코드 id (grantId) */
  readonly id: string
  /** 권한 한글 라벨 (예: '프로젝트 생성') */
  readonly permissionLabel: string
  /** 대상 종류 한글 라벨 ('사용자' | '그룹') */
  readonly granteeTypeLabel: string
  /** 해소된 대상 이름 — orphan 이면 '삭제된 사용자'/'삭제된 그룹' */
  readonly granteeName: string
  /** 해소된 부여자 이름 */
  readonly grantedByName: string
  /** 표시용으로 포맷된 부여 일시 문자열 */
  readonly createdAtLabel: string
  /** 회수 확인 시 호출되는 콜백 — grantId(id) 전달 */
  readonly onRevoke: (id: string) => void
  /** 회수 뮤테이션 pending 여부 */
  readonly isPending: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// RevokeConfirm — 인라인 회수 확인 UI
// ─────────────────────────────────────────────────────────────────────────────

interface RevokeConfirmProps {
  readonly onConfirm: () => void
  readonly onCancel: () => void
  readonly isPending: boolean
}

/**
 * 회수 버튼 클릭 후 노출되는 인라인 확인 UI.
 *
 * @param onConfirm 확인 클릭 시 호출되는 콜백
 * @param onCancel 취소 클릭 시 호출되는 콜백
 * @param isPending 회수 뮤테이션 pending 여부 — true 이면 확인 버튼 disabled
 */
function RevokeConfirm({ onConfirm, onCancel, isPending }: RevokeConfirmProps): JSX.Element {
  return (
    <div className="flex items-center gap-2">
      <span className="text-sm text-muted-foreground">삭제하시겠습니까?</span>
      <Button
        variant="destructive"
        size="sm"
        disabled={isPending}
        onClick={onConfirm}
        aria-label="확인"
      >
        확인
      </Button>
      <Button variant="outline" size="sm" disabled={isPending} onClick={onCancel}>
        취소
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GlobalPermissionRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여 목록의 단일 행 컴포넌트.
 *
 * - 권한/대상 종류/대상 이름/부여자/부여 일시를 표 셀로 렌더한다.
 * - 회수 버튼 클릭 시 인라인 확인 UI("삭제하시겠습니까?" + 확인/취소)를 노출한다.
 * - "확인" 클릭 시 onRevoke(id)를 1회 호출하고 확인 UI를 닫는다. "취소" 클릭 시 콜백 없이 닫는다.
 * - 부모가 `<table>`을 제공한다는 전제 하에 `<tr>`로 렌더한다.
 *
 * @param props {@link GlobalPermissionRowProps}
 */
export function GlobalPermissionRow({
  id,
  permissionLabel,
  granteeTypeLabel,
  granteeName,
  grantedByName,
  createdAtLabel,
  onRevoke,
  isPending,
}: GlobalPermissionRowProps): JSX.Element {
  const [showRevokeConfirm, setShowRevokeConfirm] = useState(false)

  function handleRevokeClick(): void {
    setShowRevokeConfirm(true)
  }

  function handleRevokeConfirm(): void {
    onRevoke(id)
    setShowRevokeConfirm(false)
  }

  function handleRevokeCancel(): void {
    setShowRevokeConfirm(false)
  }

  return (
    <tr data-testid="global-permission-row">
      <td className="px-4 py-3 text-sm">{permissionLabel}</td>
      <td className="px-4 py-3 text-sm">{granteeTypeLabel}</td>
      <td className="px-4 py-3 text-sm">{granteeName}</td>
      <td className="px-4 py-3 text-sm">{grantedByName}</td>
      <td className="px-4 py-3 text-sm">{createdAtLabel}</td>
      <td className="px-4 py-3 text-sm">
        {showRevokeConfirm ? (
          <RevokeConfirm
            onConfirm={handleRevokeConfirm}
            onCancel={handleRevokeCancel}
            isPending={isPending}
          />
        ) : (
          <Button
            variant="destructive"
            size="sm"
            aria-label={`${granteeName} 전역 권한 회수`}
            onClick={handleRevokeClick}
          >
            회수
          </Button>
        )}
      </td>
    </tr>
  )
}
