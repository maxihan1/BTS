// 이슈 목록 담당자 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { useState } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import type { IssueResponse } from '@/api/issues'
import type { UserSummary } from '@/api/users'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { useDebounce } from '@/hooks/use-debounce'
import { useUsers } from '@/hooks/use-users'
import { useIssueListCellField } from '@/hooks/use-issue-list-cell-field'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { CELL_OPTION_CLASS, EditableCell } from './EditableCell'

/**
 * 담당자 미배정 표시어.
 *
 * ★상세 화면의 `issueDetailStrings.assigneeUnassigned` 는 **'미지정'** 으로 글자가 다르다.
 * 목록은 예전부터 '미배정' 이었고(`issue-columns.ts` `renderAssigneeCell`) 필터 체크박스
 * 라벨과도 같은 말이라, 여기서 상세 쪽 문구로 갈아타면 목록 표기가 조용히 바뀐다.
 * 두 화면의 문구 통일은 이 PR 범위 밖이다 — 목록 표기를 verbatim 유지한다.
 */
const ASSIGNEE_UNASSIGNED = '미배정'

/** 담당자 검색 debounce (ms) — 상세 화면 `issues.$key.tsx` 와 같은 값 */
const ASSIGNEE_SEARCH_DEBOUNCE_MS = 250

/** AssigneeCellEditor props */
export interface AssigneeCellEditorProps {
  /** 현재 담당자 UUID. null 이면 미배정 */
  value: string | null
  /**
   * 현재 담당자 표시 이름 — popover 맨 위에 보여준다 (디자인 리뷰 Pass 1). null 이면 미배정.
   *
   * popover 가 셀을 가리므로 "지금 누구인지" 가 화면에서 사라진다. 상세 화면
   * `IssueAssigneeSelect` 도 같은 이유로 현재 담당자를 맨 위에 둔다.
   *
   * ★`UserSummary` 가 아니라 **이름 문자열**을 받는다. 목록은 페이지 단위로
   * `useUsersByIds`(`issues.index.tsx`)를 이미 한 번 돌려 `assigneeNameMap` 을 갖고 있어
   * 셀마다 다시 조회할 이유가 없다 — 요청 0 추가(NFR2)이고, 닫힌 셀과 popover 가
   * **같은 출처**를 쓰므로 이름이 서로 어긋날 수 없다.
   */
  currentAssigneeName: string | null
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
  currentAssigneeName,
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
        {currentAssigneeName ?? ASSIGNEE_UNASSIGNED}
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
          {/* ★문구 정본은 i18n 상수다. 여기 리터럴을 복제하면 상세 화면
              (`IssueAssigneeSelect`)과 목록이 따로 놀게 된다 */}
          {issueDetailStrings.assigneeUnassignButton}
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

/**
 * 닫힌 상태에서 보이는 담당자 텍스트.
 *
 * ★`issue-columns.ts` 의 기존 `renderAssigneeCell` 마크업을 **글자 단위로** 옮긴 것이다.
 * 편집 비활성(`ctx.edit` 부재) 경로도 이 컴포넌트를 소비하므로 두 경로가 같은 DOM 을 낸다.
 *
 * @param props 목록이 해석해 둔 담당자 표시 이름. 미배정·해석 실패면 undefined
 * @returns 담당자 텍스트 span
 */
export function AssigneeCellDisplay({
  assigneeName,
}: {
  assigneeName: string | undefined
}): JSX.Element {
  return <span className="text-(--text-default)">{assigneeName ?? ASSIGNEE_UNASSIGNED}</span>
}

/** AssigneeCell props */
export interface AssigneeCellProps {
  /** 대상 이슈 — `assigneeId`·`expectedVersion` 의 출처 */
  issue: IssueResponse
  /** 목록이 이미 해석해 둔 담당자 표시 이름 (`assigneeNameMap`). 미배정이면 undefined */
  assigneeName: string | undefined
  /** 목록 queryKey — mutation 이 이 캐시를 낙관적으로 patch 한다 */
  listQueryKey: QueryKey
}

/** AssigneeCellPopoverBody props */
interface AssigneeCellPopoverBodyProps {
  issue: IssueResponse
  assigneeName: string | undefined
  isSaving: boolean
  onChange: (userId: string | null) => void
}

/**
 * popover 가 열렸을 때만 마운트된다 — 사용자 검색·권한 조회가 여기서만 발생한다 (FR12·NFR1).
 *
 * 이 컴포넌트를 `EditableCell` **밖으로** 끌어올리면 목록 초기 렌더에서 행 수만큼
 * 조회가 터진다. `IssueTable.test.tsx` 의 FR12 가드가 그 회귀를 잡는다.
 *
 * @param props 대상 이슈 · 현재 담당자 이름 · 저장 진행 여부 · 변경 콜백
 * @returns 담당자 검색·선택 목록
 */
function AssigneeCellPopoverBody({
  issue,
  assigneeName,
  isSaving,
  onChange,
}: AssigneeCellPopoverBodyProps): JSX.Element {
  const [query, setQuery] = useState('')
  // 키 입력마다 요청이 나가지 않도록 지연시킨다 (상세 화면과 같은 처방)
  const debouncedQuery = useDebounce(query, ASSIGNEE_SEARCH_DEBOUNCE_MS)
  const { data: users = [], isLoading } = useUsers(debouncedQuery)
  const permissions = useIssuePermissions(issue.key)

  return (
    <AssigneeCellEditor
      value={issue.assigneeId}
      currentAssigneeName={assigneeName ?? null}
      users={users}
      isLoading={isLoading}
      // fail-closed — 권한이 확정되기 전에는 false (D-6)
      canEdit={permissions.data?.permissions.UPDATE === true}
      isSaving={isSaving}
      onSearch={setQuery}
      onChange={onChange}
    />
  )
}

/**
 * 담당자 셀 조립 — 이름(닫힘) + 검색·선택(열림).
 *
 * mutation 훅은 popover **밖**(이 컴포넌트)에 둔다. 저장은 popover 를 닫은 뒤 시작하므로
 * (Maxi 확정 2026-08-04) 훅이 popover 안에 있으면 mutate 직후 언마운트돼 관찰자가 사라진다.
 * `useMutation` 은 네트워크를 유발하지 않으므로 밖에 두어도 FR12(조회 지연)와 무관하다.
 *
 * @param props 대상 이슈 · 해석된 담당자 이름 · 목록 queryKey
 * @returns 편집 가능한 담당자 셀
 */
export function AssigneeCell({ issue, assigneeName, listQueryKey }: AssigneeCellProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const mutation = useIssueListCellField(listQueryKey)

  /** 담당자 선택/해제 — 먼저 닫고 저장한다. 낙관적 patch 라 닫아도 결과가 셀에 즉시 보인다 */
  function handleChange(userId: string | null): void {
    setOpen(false)
    mutation.mutate({
      issueKey: issue.key,
      field: 'assignee',
      toAssigneeId: userId,
      expectedVersion: issue.version,
    })
  }

  return (
    <EditableCell
      open={open}
      onOpenChange={setOpen}
      // ★목록은 행이 여러 개다. 이슈 키를 접두로 붙이지 않으면 e2e strict mode 로 즉사한다
      label={`${issue.key} 담당자 변경`}
      display={<AssigneeCellDisplay assigneeName={assigneeName} />}
    >
      <AssigneeCellPopoverBody
        issue={issue}
        assigneeName={assigneeName}
        isSaving={mutation.isPending}
        onChange={handleChange}
      />
    </EditableCell>
  )
}
