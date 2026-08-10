// 이슈 목록 담당자 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { useMemo, useState } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import type { IssueResponse } from '@/api/issues'
import type { UserSummary } from '@/api/users'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
// 필드 단위 열람/편집가부 판정 정본 — 복제하지 않고 재사용한다.
// `meta/IssueCustomFieldsEdit.tsx:12` 가 같은 방식으로 값 import 하는 선례가 있다.
// ★두 술어는 **짝**이다. `isFieldDisabled` 만 가져오면 열람 숨김이 통째로 열린다 —
//   백엔드가 두 목록을 배타적으로 만들기 때문이다(아래 ASSIGNEE_RESTRICTED 주석).
import { isFieldDisabled, isFieldHidden } from '@/components/issue/IssueMetaPanel'
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

/**
 * 담당자 열람 권한이 없을 때 셀에 보이는 짧은 표기.
 *
 * ★**「미배정」으로 보이면 안 된다.** 백엔드는 열람 불가 필드를 `assigneeId: null` 로
 * **마스킹**하고 키를 `restrictedFields` 에 넣는다(`IssueResponse.kt` maskInvisible —
 * nullable CORE 마스킹 규칙). 그 null 을 그대로 그리면 담당자가 **있는데 없다고** 말하게 된다.
 *
 * 사유 전문은 `issueDetailStrings.descriptionRestricted`(*"이 필드를 볼 권한이 없습니다."*)를
 * `title` 로 단다 — 같은 상황의 정본이고(`IssueDescription.tsx:122`) 키 신설이 필요 없다.
 * 표 열은 `w-36` 이라 문장을 그대로 그리면 줄이 넘쳐 열 정렬이 무너지므로 화면에는 짧게 쓴다.
 */
const ASSIGNEE_RESTRICTED = '비공개'

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
  /**
   * 담당자 변경 콜백 — UUID 또는 null(해제).
   *
   * `displayName` 을 함께 넘긴다. 목록의 이름 맵(`useUsersByIds`)은 새 담당자를 조회로
   * 알아내므로 한 왕복만큼 늦는데, 그동안 방금 고른 셀이 '미배정' 으로 보이면 낙관적
   * 반영(FR11·S1)이 깨져 보인다. 고르는 순간 이름을 아는 곳은 **여기뿐**이다.
   */
  onChange: (userId: string | null, displayName: string | null) => void
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
          onClick={() => onChange(null, null)}
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
                onClick={() => onChange(user.id, getDisplayName(user))}
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
  isRestricted = false,
}: {
  assigneeName: string | undefined
  /** 열람 권한 없음(`restrictedFields` 에 `assigneeId` 포함). true 면 값 대신 사유를 보인다 */
  isRestricted?: boolean
}): JSX.Element {
  if (isRestricted) {
    return (
      <span
        className="text-(--text-subtle)"
        data-testid="cell-assignee-restricted"
        title={issueDetailStrings.descriptionRestricted}
      >
        {ASSIGNEE_RESTRICTED}
      </span>
    )
  }
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
  onChange: (userId: string | null, displayName: string | null) => void
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

  /**
   * 담당자 후보는 **검색해야 나온다**.
   *
   * `useUsers` 는 빈 검색어에 전체 사용자 목록을 돌려준다(훅 KDoc 참조 — 필터 계열 소비처가
   * 그 동작에 의존하므로 훅에 전역 `enabled` 를 달 수 없다). 그대로 넘기면 담당자 셀을 여는
   * 순간 사내 1,000명이 펼쳐진다.
   *
   * 판정 표현을 정본과 **문자 그대로 동일**하게 둔다 —
   * `routes/issues.$key.tsx` 의 `assigneeCandidates` · `components/issue/create/use-assignee-picker.ts`.
   * 세 곳이 같은 규칙임을 grep 으로 확인할 수 있어야 한다.
   *
   * `currentAssigneeName` 은 이 배열과 독립이라 현재 담당자 표시는 영향받지 않는다.
   */
  const assigneeCandidates = useMemo(
    () => (debouncedQuery.trim() === '' ? [] : users),
    [debouncedQuery, users],
  )

  return (
    <AssigneeCellEditor
      value={issue.assigneeId}
      currentAssigneeName={assigneeName ?? null}
      users={assigneeCandidates}
      isLoading={isLoading}
      // fail-closed — 권한이 확정되기 전에는 false (D-6).
      // ★이슈 단위 UPDATE **와** 필드 단위 편집가부를 둘 다 본다 (리뷰 C2). 상세 화면
      // `IssueMetaPanel.tsx:315` 와 같은 판정이다. `noneditableFields` 는 목록 응답에도
      // 이미 실려 오므로(backend `listIssues` 경로) **추가 요청 0** 이다.
      canEdit={
        !isFieldDisabled(
          'assigneeId',
          permissions.data?.permissions.UPDATE === true,
          issue.noneditableFields,
        )
      }
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

  // ★열람 숨김이면 **편집 트리거를 아예 걸지 않는다** (재리뷰).
  //
  // `isFieldDisabled` 로는 못 막는다 — 백엔드 `buildNoneditableKeys` 가
  // `.filter { key -> key !in restrictedSet }` 로 두 목록을 **배타적**으로 만들어,
  // 열람 숨김 키는 `noneditableFields` 에 **절대** 안 들어온다. 그래서
  // `isFieldDisabled('assigneeId', true, [])` 는 false 를 내고 셀이 완전히 열린다.
  //
  // popover 를 열어 "권한 없음" 을 보이는 대안도 있지만 택하지 않았다 — 트리거가 남으면
  // 접근성 이름이 "…담당자 변경" 이라고 **할 수 없는 일을 약속**하고, 눌러서야 못 한다는
  // 걸 알게 된다. 클릭 가능한 표시 자체를 없애는 쪽이 정직하다.
  // 표시 노드는 같은 `<span>` 이라 열 정렬도 그대로다.
  //
  // 🛑 조기 반환은 **모든 훅 아래**에 둔다. 훅 사이에 두면 렌더마다 훅 개수가 달라져
  //    `react-hooks/rules-of-hooks` 위반이다 — 실제로 처음엔 여기 뒀다가 eslint 가 잡았고,
  //    **유닛 22건은 전부 초록이었다.** 테스트가 못 보는 종류의 결함이다.

  /**
   * 방금 고른 담당자 — 목록 이름 맵이 따라올 때까지의 임시 표기 (리뷰 C3).
   *
   * 이름 맵은 `useUsersByIds` 조회 결과라 새 담당자는 **한 왕복 뒤**에야 이름이 생긴다.
   * 그동안 방금 고친 셀이 '미배정' 으로 보이면 낙관적 반영(FR11·S1)이 깨져 보인다.
   *
   * ★stale 방지 — 표시할 때 **현재 `issue.assigneeId` 와 대조**한다. 저장이 실패해 롤백되면
   * id 가 되돌아가 자동으로 무시되고, 맵이 따라오면 그쪽이 우선한다. 그래서 되돌리는
   * 뒷정리 코드가 필요 없다 (props 파생 useState 의 stale 함정을 구조로 피한다).
   */
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null)

  /** 담당자 선택/해제 — 먼저 닫고 저장한다. 낙관적 patch 라 닫아도 결과가 셀에 즉시 보인다 */
  function handleChange(userId: string | null, displayName: string | null): void {
    setOpen(false)
    setPicked(userId !== null && displayName !== null ? { id: userId, name: displayName } : null)
    mutation.mutate({
      issueKey: issue.key,
      field: 'assignee',
      toAssigneeId: userId,
      expectedVersion: issue.version,
    })
  }

  // 맵이 이름을 알면 그쪽이 정본. 아직 모를 때만 방금 고른 이름으로 메운다.
  const shownName =
    assigneeName ?? (picked !== null && picked.id === issue.assigneeId ? picked.name : undefined)

  // 열람 숨김 차단 — 위 주석 참조. 훅을 전부 부른 뒤에 반환한다.
  if (isFieldHidden('assigneeId', issue.restrictedFields)) {
    return <AssigneeCellDisplay assigneeName={undefined} isRestricted />
  }

  return (
    <EditableCell
      open={open}
      onOpenChange={setOpen}
      // ★목록은 행이 여러 개다. 이슈 키를 접두로 붙이지 않으면 e2e strict mode 로 즉사한다
      label={`${issue.key} 담당자 변경`}
      // 접근성 이름에 현재 값을 함께 싣는다 (리뷰 C4) — 화면에 보이는 것과 같은 문자열
      valueLabel={shownName ?? ASSIGNEE_UNASSIGNED}
      display={<AssigneeCellDisplay assigneeName={shownName} />}
    >
      <AssigneeCellPopoverBody
        issue={issue}
        assigneeName={shownName}
        isSaving={mutation.isPending}
        onChange={handleChange}
      />
    </EditableCell>
  )
}
