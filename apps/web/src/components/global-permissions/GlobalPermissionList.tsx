// 전역 권한 관리 화면 — 목록 조합(useGlobalPermissionRows) + 부여/회수 결선 + 빈/로딩/에러 상태 (FR-PM-10 D6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import {
  useGlobalPermissionRows,
  useRevokeGlobalPermission,
} from '@/hooks/use-global-permissions'
import { GlobalPermissionRow } from './GlobalPermissionRow'
import { GlobalPermissionFormDialog } from './GlobalPermissionFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 표 컬럼 헤더 — 권한/대상 종류/대상 이름/부여자/부여 시각/작업 순 (FR-3) */
const COLUMN_HEADERS = ['권한', '대상 종류', '대상 이름', '부여자', '부여 시각', '작업'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 (FR-9)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 부여된 전역 권한이 없을 때 노출되는 안내.
 * SYSTEM_ADMIN은 grant 없이도 모든 전역 권한을 암묵 보유하므로(EC-5),
 * 빈 목록이 정상 초기 상태임을 함께 설명한다.
 */
function GlobalPermissionEmptyState(): JSX.Element {
  return (
    <div className="rounded-md border py-8 text-center">
      <p className="text-sm text-muted-foreground">부여된 전역 권한이 없습니다</p>
      <p className="mt-1 text-xs text-muted-foreground">
        SYSTEM_ADMIN 은 모든 전역 권한을 자동 보유합니다
      </p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 로딩/에러 상태 (FR-8, 방어적)
// ─────────────────────────────────────────────────────────────────────────────

/** grant/사용자/그룹 셋 중 하나라도 최초 로딩 중일 때 노출되는 로딩 표시. */
function GlobalPermissionListLoading(): JSX.Element {
  return (
    <div role="status" aria-label="전역 권한 목록 로딩 중" className="py-8 text-center text-sm text-muted-foreground">
      로딩 중...
    </div>
  )
}

/**
 * 목록 조회(grant/사용자/그룹) 중 하나라도 실패했을 때 노출되는 방어적 에러 메시지(FR-8).
 * 현재 조합 훅({@link useGlobalPermissionRows})이 refetch를 노출하지 않아
 * 별도 재시도 버튼 없이 새로고침을 안내한다.
 */
function GlobalPermissionListError(): JSX.Element {
  return (
    <div role="alert" className="py-8 text-center text-sm text-destructive">
      전역 권한 목록을 불러오지 못했습니다. 새로고침 후 다시 시도해주세요.
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 표 본문
// ─────────────────────────────────────────────────────────────────────────────

interface GlobalPermissionTableProps {
  readonly rows: ReturnType<typeof useGlobalPermissionRows>['rows']
  readonly onRevoke: (id: string) => void
  readonly isRevoking: boolean
}

/**
 * 전역 권한 부여 표시행을 시맨틱 `<table>`로 렌더한다.
 * `<th scope="col">`로 열 제목을 명시한다(스크린리더 접근성).
 */
function GlobalPermissionTable({
  rows,
  onRevoke,
  isRevoking,
}: GlobalPermissionTableProps): JSX.Element {
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            {COLUMN_HEADERS.map((header) => (
              <th key={header} scope="col" className="px-4 py-2">
                {header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <GlobalPermissionRow
              key={row.id}
              id={row.id}
              permissionLabel={row.permissionLabel}
              granteeTypeLabel={row.granteeTypeLabel}
              granteeName={row.granteeName}
              grantedByName={row.grantedByName}
              createdAtLabel={row.createdAtLabel}
              onRevoke={onRevoke}
              isPending={isRevoking}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GlobalPermissionList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 관리 화면.
 *
 * - {@link useGlobalPermissionRows}로 grant 목록 + grantee/부여자 이름 해소를 조합해 표로 렌더한다.
 * - 로딩/에러/빈/목록 4분기를 처리한다(에러·빈 상태는 FR-8/FR-9, 방어적).
 * - "권한 부여" 버튼으로 {@link GlobalPermissionFormDialog}를 열고 닫는다.
 *   부여 성공 시 목록 쿼리가 invalidate되어(Task 2) 목록이 자동 반영된다.
 * - 회수는 {@link useRevokeGlobalPermission}으로 수행한다. 진행 중에는 모든 행의 회수 버튼을
 *   비활성화한다(field-permissions 선례와 동일 — 동시 다중 회수 방지).
 *
 * @returns 전역 권한 관리 페이지 컴포넌트
 */
export function GlobalPermissionList(): JSX.Element {
  const { rows, isLoading, isError } = useGlobalPermissionRows()
  const revokeMutation = useRevokeGlobalPermission()
  const [dialogOpen, setDialogOpen] = useState(false)

  function handleRevoke(id: string): void {
    revokeMutation.mutate(id)
  }

  function openGrantDialog(): void {
    setDialogOpen(true)
  }

  function closeGrantDialog(): void {
    setDialogOpen(false)
  }

  function renderBody(): JSX.Element {
    if (isLoading) return <GlobalPermissionListLoading />
    if (isError) return <GlobalPermissionListError />
    if (rows.length === 0) return <GlobalPermissionEmptyState />
    return (
      <GlobalPermissionTable
        rows={rows}
        onRevoke={handleRevoke}
        isRevoking={revokeMutation.isPending}
      />
    )
  }

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold">전역 권한 관리</h1>
        <Button size="sm" onClick={openGrantDialog}>
          권한 부여
        </Button>
      </div>

      {renderBody()}

      <GlobalPermissionFormDialog isOpen={dialogOpen} onClose={closeGrantDialog} />
    </div>
  )
}
