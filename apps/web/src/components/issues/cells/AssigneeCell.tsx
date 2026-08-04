// 이슈 목록 담당자 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import type { UserSummary } from '@/api/users'
import { Button } from '@/components/ui/button'
import { CELL_OPTION_CLASS } from './EditableCell'

/** AssigneeCellEditor props */
export interface AssigneeCellEditorProps {
  /** 현재 담당자 UUID. null 이면 미배정 */
  value: string | null
  /**
   * 현재 담당자 정보 — popover 맨 위에 보여준다 (디자인 리뷰 Pass 1).
   *
   * popover 가 셀을 가리므로 "지금 누구인지" 가 화면에서 사라진다. 상세 화면
   * `IssueAssigneeSelect` 도 같은 이유로 현재 담당자를 맨 위에 둔다.
   */
  currentAssignee: UserSummary | null
  /** 검색 결과 후보 */
  users: UserSummary[]
  /** 검색 진행 중 — true 면 "검색 결과가 없습니다" 를 띄우지 않는다 (Pass 2) */
  isLoading: boolean
  /** 수정 권한 여부 — false 면 검색창·선택지 disabled (fail-closed) */
  canEdit: boolean
  /** 저장 진행 중 — true 면 중복 제출을 막기 위해 disabled (NFR3) */
  isSaving: boolean
  /** 검색어 변경 콜백 */
  onSearch: (query: string) => void
  /** 담당자 변경 콜백 — UUID 또는 null(해제) */
  onChange: (userId: string | null) => void
}

/**
 * 표시 이름 — displayName 우선, 없으면 username (IssueAssigneeSelect 와 동일 규칙).
 *
 * ★의도적 복제. 같은 로직이 `IssueAssigneeSelect` 안에도 있지만 그쪽은 컴포넌트 **내부
 * 지역 함수**라 export 되어 있지 않다. 공유 모듈로 빼려면 이슈 상세 화면 파일을 건드려야
 * 해서 이 PR 범위를 벗어난다 — 지금은 복제하고 통합은 후속 과제로 남긴다.
 *
 * @param user 표시할 사용자
 * @returns 화면에 보일 이름
 */
function getDisplayName(user: UserSummary): string {
  return user.displayName ?? user.username
}

/**
 * popover 안에 뜨는 담당자 검색·선택 목록.
 *
 * `IssueAssigneeSelect` 의 계약 3가지를 승계한다.
 * ① `canEdit=false → disabled` (fail-closed)
 * ② `Enter` 기본동작 차단 (폼 안 암묵 제출 방지 — FR-UX-09 F2 에서 실제 사고)
 * ③ 표시 이름은 `displayName ?? username`
 *
 * 조립(`EditableCell` 로 감싸기)은 `IssueColumnRenderContext` 가 확정된 뒤 붙인다 —
 * 이 컴포넌트는 순수 프레젠테이션이라 조회 훅을 갖지 않는다.
 *
 * @param props 현재 값·현재 담당자·후보 목록·로딩/권한/저장 상태·검색/변경 콜백
 * @returns 담당자 검색창과 후보 목록
 */
export function AssigneeCellEditor({
  value,
  currentAssignee,
  users,
  isLoading,
  canEdit,
  isSaving,
  onSearch,
  onChange,
}: AssigneeCellEditorProps): JSX.Element {
  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 담당자 — popover 가 셀을 가리므로 여기서 다시 보여준다 (Pass 1) */}
      <p className="truncate text-sm font-medium" data-testid="cell-assignee-current">
        {currentAssignee !== null ? getDisplayName(currentAssignee) : '미배정'}
      </p>

      <input
        type="text"
        aria-label="담당자 검색"
        placeholder="이름으로 검색"
        disabled={!canEdit || isSaving}
        onChange={(e) => onSearch(e.target.value)}
        // ★Enter 를 막는다 — 이 칸은 값을 넣는 곳이 아니라 검색창이다. 폼 안에 들어가면
        // HTML 암묵 제출이 일어난다(FR-UX-09 F2 에서 실제로 이슈가 생성된 사고).
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.preventDefault()
        }}
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-40"
      />

      {!canEdit && <p className="text-xs text-(--text-subtle)">편집 권한이 없습니다.</p>}

      {value !== null && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canEdit || isSaving}
          onClick={() => onChange(null)}
          className={`${CELL_OPTION_CLASS} text-muted-foreground hover:text-destructive`}
        >
          담당자 해제
        </Button>
      )}

      {/* ★검색 중에는 "결과 없음" 을 띄우지 않는다 — 아직 모르는 것을 없다고 말하면 거짓이다
          (디자인 리뷰 Pass 2). 로딩과 빈 결과는 서로 다른 상태다. */}
      {isLoading ? (
        <p className="text-xs text-(--text-subtle)">검색 중…</p>
      ) : users.length === 0 ? (
        <p className="text-xs text-(--text-subtle)">검색 결과가 없습니다.</p>
      ) : (
        <ul className="flex max-h-48 flex-col gap-0.5 overflow-y-auto">
          {users.map((user) => (
            <li key={user.id}>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={!canEdit || isSaving}
                onClick={() => onChange(user.id)}
                className={CELL_OPTION_CLASS}
              >
                {getDisplayName(user)}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
