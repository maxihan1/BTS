// 이슈 목록 페이지 — IssueListPage(props 기반) + IssueListRouteAdapter(라우터 연결). 테이블·정렬·컬럼 선택 + split view 결선(FR-UX-06 PR20)
import type { JSX } from 'react'
import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { fetchIssues, ISSUE_SORT_FIELDS } from '@/api/issues'
import type { IssuePage, IssueFilterParams, IssueSortField } from '@/api/issues'
import { Button } from '@/components/ui/button'
import { useIssueSelection } from '@/hooks/use-issue-selection'
import { useColumnVisibility } from '@/hooks/use-column-visibility'
import { useUsersByIds } from '@/hooks/use-users'
import { useMediaQuery } from '@/hooks/use-media-query'
import { useContextShortcuts } from '@/components/keyboard-shortcuts/useContextShortcuts'
import { nextCursorKey } from '@/components/keyboard-shortcuts/context-shortcuts'
import { IssueBulkActionBar } from '@/components/issues/IssueBulkActionBar'
import { BulkEditDialog } from '@/components/issues/BulkEditDialog'
import { BulkTransitionDialog } from '@/components/issues/BulkTransitionDialog'
import { BulkOperationResultDialog } from '@/components/issues/BulkOperationResultDialog'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { IssueFilterBar } from '@/components/issues/IssueFilterBar'
import { IssueTable } from '@/components/issues/IssueTable'
import type { IssueTableSortState } from '@/components/issues/IssueTable'
import { ColumnSelector } from '@/components/issues/ColumnSelector'
import { ISSUE_COLUMNS } from '@/components/issues/issue-columns'
import type { IssueCellEditContext } from '@/components/issues/issue-columns'
import { normalizeIssueFilter, isEmptyIssueFilter, searchToIssueFilter, issueFilterToSearch } from '@/lib/issue-filter'
import type { IssueFilterSearch } from '@/lib/issue-filter'
import { IssueDetailPage } from './issues.$key'
import { useIssueListPresentation } from '@/components/issue/use-issue-list-presentation'
import { FilteredEmptyState } from '@/components/filters/FilteredEmptyState'
import { useResolvedActiveProject } from '@/hooks/use-resolved-active-project'
import { ActiveProjectGate } from '@/components/project/ActiveProjectGate'

// ─────────────────────────────────────────────────────────────────────────────
// router.ts 등록 방법 (code-based 패턴 — PR #11 컨벤션).
//
//   import { IssueListRouteAdapter } from './routes/issues.index'
//
//   const issuesIndexRoute = createRoute({
//     getParentRoute: () => rootRoute,
//     path: '/issues',
//     component: IssueListRouteAdapter,
//   })
//
// IssueListRouteAdapter는 useSearch/useNavigate로 page + sort 상태를 추출해
// IssueListPage에 전달한다. 라우터 등록은 Task8(router.ts) 담당.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_PAGE_SIZE = 20

/** 빈 필터 상수 — 매 렌더마다 새 객체 생성 방지 */
const EMPTY_FILTER: IssueFilterParams = {
  statusKeys: [],
  assigneeIds: [],
  includeUnassigned: false,
  labels: [],
  componentIds: [],
}

/** useColumnVisibility localStorage 키 — 기기별 컬럼 표시 상태 persist (S4) */
const ISSUE_TABLE_COLUMNS_STORAGE_KEY = 'issue-table-columns'

/** 컬럼 선택기에 노출할 전체 컬럼 키 목록 (issue-columns.ts ISSUE_COLUMNS 기준) */
const ISSUE_COLUMN_KEYS = ISSUE_COLUMNS.map((column) => column.key)

/** 항상 표시되는 필수 컬럼 키 목록(키·요약) — issue-columns.ts required 플래그 기준 */
const ISSUE_COLUMN_REQUIRED_KEYS = ISSUE_COLUMNS.filter((column) => column.required).map((column) => column.key)

/** 컬럼 표시 기본값 — 최초 방문 시 전체 컬럼을 표시한다 */
const ISSUE_COLUMN_DEFAULT_VISIBLE = ISSUE_COLUMN_KEYS

// ─────────────────────────────────────────────────────────────────────────────
// 정렬 URL 직렬화 헬퍼 (T1↔T2↔T4↔T5 공유 "정렬 필드 계약" 5종 기준)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 문자열이 "정렬 필드 계약"(ISSUE_SORT_FIELDS) 5종에 속하는지 좁히는 타입 가드.
 * URL에서 읽은 임의 문자열을 안전하게 {@link IssueSortField}로 좁힐 때 사용한다
 * (issue-columns.ts의 동형 가드와 별개 — 이쪽은 IssueColumnKey가 아닌 순수 문자열 대상).
 */
function isIssueSortField(field: string): field is IssueSortField {
  return (ISSUE_SORT_FIELDS as readonly string[]).includes(field)
}

/**
 * URL 쿼리 파라미터 `sort=<field>,<dir>` 문자열을 정렬 상태 객체로 파싱한다.
 * 구분자가 없거나 필드/방향이 허용 값이 아니면 안전하게 null(정렬 미적용)로 폴백한다.
 *
 * @param raw URL search의 sort 원문 문자열. undefined면 정렬 미적용.
 * @returns 파싱된 정렬 상태. 형식이 잘못됐으면 null.
 */
function parseSortParam(raw: string | undefined): IssueTableSortState | null {
  if (raw === undefined) return null
  const [field, dir] = raw.split(',')
  if (field === undefined || dir === undefined) return null
  if (!isIssueSortField(field)) return null
  if (dir !== 'asc' && dir !== 'desc') return null
  return { field, dir }
}

/**
 * 정렬 상태를 URL 쿼리 파라미터 문자열로 직렬화한다.
 * null(정렬 해제)이면 undefined를 반환해 상위(어댑터)가 sort 키 자체를 URL에서 생략하게 한다.
 *
 * @param sort 직렬화할 정렬 상태
 * @returns `<field>,<dir>` 형식 문자열. sort가 null이면 undefined.
 */
function serializeSortParam(sort: IssueTableSortState | null): string | undefined {
  return sort === null ? undefined : `${sort.field},${sort.dir}`
}

/**
 * 정렬 헤더 클릭 시 다음 정렬 상태를 계산한다 — asc → desc → 해제(null) 3-state 순환(F2, S2).
 * 현재 정렬 중인 필드와 다른 필드를 클릭하면 그 필드의 asc로 즉시 초기화한다.
 *
 * @param current 현재 정렬 상태
 * @param field 클릭된 정렬 가능 컬럼의 필드
 * @returns 다음 정렬 상태. 3번째 클릭(현재 desc)이면 null(해제).
 */
function nextSortState(current: IssueTableSortState | null, field: IssueSortField): IssueTableSortState | null {
  if (current === null || current.field !== field) {
    return { field, dir: 'asc' }
  }
  return current.dir === 'asc' ? { field, dir: 'desc' } : null
}

/**
 * GAP-5 — 재조회(isFetching) 중 표 영역에 적용할 dim 처리 className을 계산한다.
 * keepPreviousData로 유지된 이전 데이터 위에 겹쳐 전환 중임을 알리되,
 * pointer-events-none으로 전환 중 이중 클릭 등 의도치 않은 조작을 막는다.
 *
 * @param isFetching 재조회 진행 여부
 * @returns dim 처리 className. 재조회 중이 아니면 undefined(클래스 없음).
 */
function issueTableRegionClassName(isFetching: boolean): string | undefined {
  return isFetching ? 'opacity-60 pointer-events-none' : undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueEmptyState — 빈 이슈 목록 안내 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈가 없을 때 표시하는 빈 상태 안내 컴포넌트. */
function IssueEmptyState(): JSX.Element {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
      <p className="text-base">이슈가 없습니다.</p>
      <p className="mt-1 text-sm">새 이슈를 만들어 프로젝트를 시작해 보세요.</p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssuePagination — 이슈 목록 페이지네이션 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface IssuePaginationProps {
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 전체 페이지 수 */
  totalPages: number
  /** 첫 번째 페이지 여부 */
  isFirst: boolean
  /** 마지막 페이지 여부 */
  isLast: boolean
  /** 페이지 변경 콜백 */
  onPageChange: (page: number) => void
}

/**
 * 이슈 목록 페이지네이션 컴포넌트.
 *
 * - 이전/다음 버튼으로 페이지를 탐색한다.
 * - 첫 페이지일 때 이전 버튼, 마지막 페이지일 때 다음 버튼이 비활성화된다.
 */
function IssuePagination({ page, totalPages, isFirst, isLast, onPageChange }: IssuePaginationProps): JSX.Element {
  return (
    <nav
      className="flex items-center justify-between pt-2"
      aria-label="페이지 탐색"
    >
      <Button
        variant="outline"
        size="sm"
        onClick={() => onPageChange(page - 1)}
        disabled={isFirst}
        aria-label="이전 페이지"
      >
        이전
      </Button>

      <span className="text-sm text-muted-foreground">
        {page + 1} / {totalPages}
      </span>

      <Button
        variant="outline"
        size="sm"
        onClick={() => onPageChange(page + 1)}
        disabled={isLast}
        aria-label="다음 페이지"
      >
        다음
      </Button>
    </nav>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListContent — 성공 상태 렌더 컴포넌트 (툴바 + 테이블 + 페이지네이션)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueListContentProps {
  /** Spring Page 형태의 이슈 목록 데이터 */
  data: IssuePage
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 페이지 변경 콜백 */
  onPageChange: (page: number) => void
  /** 항목 클릭 콜백 */
  onNavigate: (key: string) => void
  /** 선택 상태 체크 함수 */
  isSelected: (key: string) => boolean
  /** 개별 토글 콜백 */
  onToggle: (key: string) => void
  /** 전체 선택 토글 콜백 (현재 페이지 기준) */
  onSelectAllPage: () => void
  /** 현재 페이지 전체 선택 여부 */
  isAllPageSelected: boolean
  /** 현재 정렬 상태 — IssueTable 헤더의 aria-sort/방향 아이콘에 반영 */
  sort: IssueTableSortState | null
  /** 정렬 헤더 클릭 콜백 */
  onSort: (field: IssueSortField) => void
  /** 재조회 진행 여부(GAP-5) — true면 표 영역을 dim 처리해 전환 중임을 알린다 */
  isFetching: boolean
  /**
   * split view(FR-UX-06 PR20 Task 5) 우측 상세 페인에 현재 열린 이슈 키.
   * IssueTable.selectedKey로 그대로 전달돼 해당 행을 aria-current로 강조한다.
   * undefined/null이면 어떤 행도 강조하지 않는다(bulk 체크박스 선택과는 완전히 별개).
   */
  selectedKey?: string | null
  /** 셀 인라인 편집 컨텍스트(FR-UX-11 F9) — IssueTable.edit으로 그대로 전달된다 */
  edit?: IssueCellEditContext
}

/**
 * 이슈 목록 성공 상태 렌더 컴포넌트.
 *
 * 빈 목록이면 IssueEmptyState, 아니면 컬럼 선택 툴바(GAP-2) + IssueTable(F1) +
 * IssuePagination을 렌더한다.
 *
 * - 담당자 이름 해석(F5) — 현재 페이지에 등장하는 assigneeId만 useUsersByIds로 조회한다
 *   (FilterBar.tsx assigneeNameMap 관례 미러).
 * - 컬럼 표시 상태(F4)는 useColumnVisibility로 localStorage에 영속한다.
 * - GAP-5 — isFetching(재조회 중)이면 표 영역에 opacity-60 pointer-events-none을 적용해
 *   레이아웃 시프트 없이 전환 중임을 알린다. IssueListPage의 keepPreviousData 덕분에
 *   이전 데이터가 유지된 채 dim된다.
 */
function IssueListContent({
  data,
  page,
  onPageChange,
  onNavigate,
  isSelected,
  onToggle,
  onSelectAllPage,
  isAllPageSelected,
  sort,
  onSort,
  isFetching,
  selectedKey,
  edit,
}: IssueListContentProps): JSX.Element {
  // ── 담당자 이름 해석 — 현재 페이지 assigneeId만 조회 ───────────────────────
  const assigneeIds = useMemo(
    () =>
      Array.from(
        new Set(data.content.map((issue) => issue.assigneeId).filter((id): id is string => id !== null)),
      ),
    [data.content],
  )
  // ★`keepPreviousWhileIdsChange` — 셀 인라인 편집(F9)의 낙관 갱신이 목록 캐시에 새
  // assigneeId 를 심으면 이 훅의 queryKey 가 바뀐다. 옵션이 없으면 캐시 미스로 결과가 빈
  // 배열이 되고, 아래 맵이 통째로 비어 **바꾸지도 않은 다른 행들까지 '미배정'** 으로 깜빡인다
  // (리뷰 C3). 이전 결과를 유지하면 이미 아는 이름은 그대로 남는다.
  const { data: assignees = [] } = useUsersByIds(assigneeIds, {
    keepPreviousWhileIdsChange: true,
  })
  const assigneeNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const user of assignees) {
      map.set(user.id, user.displayName ?? user.username)
    }
    return map
  }, [assignees])

  // ── 컬럼 표시 상태 — localStorage persist(F4) ──────────────────────────────
  const { visible, isVisible, toggle } = useColumnVisibility(
    ISSUE_TABLE_COLUMNS_STORAGE_KEY,
    ISSUE_COLUMN_KEYS,
    ISSUE_COLUMN_REQUIRED_KEYS,
    ISSUE_COLUMN_DEFAULT_VISIBLE,
  )

  if (data.empty) {
    return <IssueEmptyState />
  }

  return (
    <div
      data-testid="issue-table-region"
      className={issueTableRegionClassName(isFetching)}
    >
      {/* GAP-2 — 컬럼 선택 툴바(테이블 상단 우측) */}
      <div className="flex justify-end pb-2">
        <ColumnSelector allColumns={ISSUE_COLUMNS} isVisible={isVisible} onToggle={toggle} />
      </div>

      <IssueTable
        issues={data.content}
        visibleColumnKeys={visible}
        sort={sort}
        onSort={onSort}
        selection={{ isSelected, onToggle, onSelectAllPage, isAllPageSelected }}
        onNavigate={onNavigate}
        assigneeNameMap={assigneeNameMap}
        selectedKey={selectedKey}
        edit={edit}
      />

      <IssuePagination
        page={page}
        totalPages={data.totalPages}
        isFirst={data.first}
        isLast={data.last}
        onPageChange={onPageChange}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListPage — 이슈 목록 페이지 (props 기반, 라우터 비의존)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueListPageProps {
  /** 조회할 프로젝트 키 */
  projectKey: string
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 페이지 변경 시 호출되는 콜백 */
  onPageChange: (page: number) => void
  /** 이슈 항목 클릭 시 상세 페이지로 이동하는 콜백 */
  onNavigate: (key: string) => void
  /**
   * 현재 적용된 필터 파라미터 (FR-SR-01 D6).
   * 미전달 시 빈 필터(전체 조회)로 동작한다.
   */
  filter?: IssueFilterParams
  /**
   * 필터 변경 콜백 (FR-SR-01 D6).
   * IssueFilterBar onChange → 새 필터 전달 + page=0 리셋.
   */
  onFilterChange?: (filter: IssueFilterParams) => void
  /**
   * 현재 정렬 상태(Task 5, F2). undefined/null이면 정렬 미적용(서버 기본 정렬 created_at desc 유지).
   */
  sort?: IssueTableSortState | null
  /**
   * 정렬 변경 콜백(Task 5). IssueListRouteAdapter가 URL sort 쿼리를 갱신한다.
   */
  onSortChange?: (sort: IssueTableSortState | null) => void
  /**
   * split view(FR-UX-06 PR20 Task 5) 우측 상세 페인에 현재 열린 이슈 키.
   * IssueListContent → IssueTable로 그대로 전달된다. undefined/null이면 강조 없음.
   *
   * FR-UX-10 F10 부터 이 값은 **항법 커서를 겸한다** — `j`/`k` 가 움직이는 대상이
   * 곧 상세 페인에 열린 이슈다(ADR D-3, 지라 이슈 네비게이터 동형).
   */
  selectedKey?: string | null
  /**
   * 커서 이동 콜백(FR-UX-10 F10). 이 컴포넌트가 목록 순서로 다음 키를 계산해 넘기면
   * 어댑터가 URL `selected` 를 **`replace`로** 갈아끼운다 — 연타가 히스토리를
   * 오염시키지 않아야 하기 때문이다.
   */
  onCursorTo?: (key: string) => void
  /** `o` — 커서 이슈를 전체화면 상세(`/issues/$key`)로 연다 */
  onOpenCursor?: (key: string) => void
  /** `t` — 상세 페인을 닫는다(`selected` 제거). 여는 쪽은 `onCursorTo`가 겸한다 */
  onCloseDetailPane?: () => void
  /**
   * 커서 단축키(`j`/`k`/`o`/`t`) 활성 여부 — **와이드 전용 계약**(E13).
   *
   * 좁은폭에서는 `selectedKey` 를 null 로 받아 커서 강조 자체가 없으므로, 등록을
   * 끊지 않으면 `j` 가 URL 만 바꾸고 화면은 그대로인 유령 상태가 된다. 어댑터가
   * `isWide` 를 그대로 넘긴다. 이 컴포넌트는 로딩·에러·모달 열림을 여기에 AND 로
   * 얹는다.
   */
  cursorEnabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// NewIssueButton — "새 이슈" 진입점. CREATE 권한 게이트(fail-closed).
// ─────────────────────────────────────────────────────────────────────────────

/** "새 이슈" 버튼/링크의 공통 시각 스타일 */
const NEW_ISSUE_CLASS =
  'inline-flex items-center rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors'

/**
 * CREATE 권한의 **세 상태**.
 *
 * `boolean` 으로는 「권한이 없다」와 「아직 모른다」가 구분되지 않는다. 그 둘을 합치면
 * 접근성 이름이 모르는 것을 안다고 말하게 된다 — 아래 [NewIssueButton] KDoc 참조.
 */
type CreateAccess = 'allowed' | 'denied' | 'unknown'

interface NewIssueButtonProps {
  /** CREATE 권한 상태. `denied` 만 사유를 말한다. */
  access: CreateAccess
}

/**
 * "새 이슈" 진입점 컴포넌트.
 *
 * | 상태 | 렌더 | 접근성 이름 |
 * |---|---|---|
 * | `allowed` | `<a href="/issues/new">` (role=link) | 「새 이슈」 |
 * | `denied` (CREATE === false) | `<button type="button" disabled>` | 「새 이슈 (권한 없음)」 |
 * | `unknown` (로딩 · 조회 실패) | 같은 disabled 버튼 (fail-closed 유지) | **「새 이슈」 — 사유를 말하지 않는다** |
 *
 * 양쪽 모두 `data-testid="new-issue-button"` 을 부여한다.
 *
 * ## ★왜 `unknown` 에서 사유를 말하면 안 되나
 *
 * `aria-label="새 이슈 (권한 없음)"` 은 **사실 주장**이다. 예전에는 그 문구가 붙는 조건이
 * 「CREATE 가 false」가 아니라 「CREATE 가 true 가 아니다」였다 — 그래서 권한 응답이 오기
 * **전**과 **조회 실패**에서도 붙었다.
 *
 * CREATE 를 **실제로 가진** 사용자가 `/issues` 를 열 때마다 스크린리더가 「권한 없음」이라는
 * 거짓 안내를 읽는다. `use-project-permissions.ts` 에 `retry:false` 도 에러 폴백도 없으므로
 * 500·네트워크 단절이면 그 상태로 **영구히 안착**한다.
 *
 * **시각적 disabled 는 유지한다**(fail-closed). 바꾼 것은 이름이 이유를 주장하지 않게 한 것뿐이다.
 * 생성 폼의 판정식(`permissions.CREATE === false`, 명시 거부만 차단)과 같은 정신이다 —
 * `components/issue/create/use-issue-create-permission-gate.ts` KDoc 이 정본 논거다.
 */
function NewIssueButton({ access }: NewIssueButtonProps): JSX.Element {
  if (access === 'allowed') {
    return (
      <a
        href="/issues/new"
        data-testid="new-issue-button"
        className={NEW_ISSUE_CLASS}
      >
        새 이슈
      </a>
    )
  }

  return (
    // PR22 — variant 미지정(default)이 의도다. NEW_ISSUE_CLASS 는 위 canCreate=true 분기의
    // <a> 와 공유하는 상수이므로, 중복돼 보이는 bg-primary 를 정리하면 링크 쪽 시각이 함께 깨진다.
    // 상수는 건드리지 않고 그대로 넘긴다. 프리미티브 기본과 겹치는 disabled:opacity-50 만 뺐다.
    <Button
      type="button"
      disabled
      data-testid="new-issue-button"
      className={`${NEW_ISSUE_CLASS} disabled:cursor-not-allowed`}
      // ★`unknown` 에서는 사유를 말하지 않는다. disabled 자체가 「지금은 못 쓴다」를 이미
      //   전달하므로, 확인되지 않은 이유까지 덧붙이면 거짓 안내가 된다.
      aria-label={access === 'denied' ? '새 이슈 (권한 없음)' : '새 이슈'}
    >
      새 이슈
    </Button>
  )
}

/**
 * 이슈 목록 페이지 컴포넌트.
 *
 * - useQuery로 fetchIssues를 호출한다. queryKey에 sort를 포함해 정렬별 캐시를 분기한다.
 * - placeholderData: keepPreviousData로 page/sort/filter 전환 중에도 이전 데이터를 유지한다
 *   (GAP-5 — IssueListContent가 isFetching으로 dim 처리).
 * - 3 상태 분기: 로딩("로딩 중...") → 에러(role="alert") → 성공(IssueListContent).
 * - 에러 시 role="alert"로 스크린 리더 접근성 보장 (WCAG AA).
 * - "새 이슈" 진입점: CREATE 권한 기반 게이트. fail-closed(로딩/에러/undefined → 비활성).
 * - useIssueSelection으로 페이지 교차 누적 선택 상태를 관리한다.
 * - IssueBulkActionBar, BulkEditDialog, BulkTransitionDialog, BulkOperationResultDialog를 결선한다.
 * - IssueFilterBar 결선 (FR-SR-01 D6): filter prop → queryKey filter-aware + filter 실변경 시 clearAll.
 * - 정렬 헤더 클릭(Task 5, F2) → nextSortState로 3-state 계산 → onSortChange + page=0 리셋(EC3).
 *
 * 라우터 의존 없이 props로 동작해 단위 테스트가 가능하다.
 */
export function IssueListPage({
  projectKey,
  page,
  onPageChange,
  onNavigate,
  filter = EMPTY_FILTER,
  onFilterChange,
  sort = null,
  onSortChange,
  selectedKey = null,
  onCursorTo,
  onOpenCursor,
  onCloseDetailPane,
  cursorEnabled = true,
}: IssueListPageProps): JSX.Element {
  const queryClient = useQueryClient()

  // ── B1 queryKey filter-aware ─────────────────────────────────────────────────
  // normalizeIssueFilter로 배열 순서를 정규화해 동일 필터 → 동일 queryKey 보장.
  // (use-boards.ts normalizeFilter 미러 — PR #168 선례)
  const normalizedFilter = useMemo(() => normalizeIssueFilter(filter), [filter])

  // ★목록 queryKey 정본 — useQuery와 셀 편집 mutation이 **같은 값**을 써야 한다 (FR-UX-11 F9).
  // 두 곳에서 따로 조립하면 한 글자만 어긋나도 낙관적 patch가 아무 캐시에도 닿지 않아,
  // 테스트는 초록인데 화면만 안 바뀌는 가짜 그린이 된다.
  const listQueryKey = useMemo(
    () => ['issues', projectKey, page, normalizedFilter, sort],
    [projectKey, page, normalizedFilter, sort],
  )

  // 셀 인라인 편집 컨텍스트 — 위 queryKey 정본을 그대로 실어 보낸다 (FR-UX-11 F9)
  const editContext = useMemo<IssueCellEditContext>(
    () => ({ listQueryKey, enabled: true }),
    [listQueryKey],
  )

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: listQueryKey,
    queryFn: () =>
      fetchIssues({
        projectKey,
        page,
        size: DEFAULT_PAGE_SIZE,
        filter,
        sort: sort ?? undefined,
      }),
    // GAP-5 — page/sort/filter 전환 중에도 이전 데이터를 유지해 전체 화면 로딩 플리커를 방지한다.
    // 첫 로딩(데이터 없음)은 이 옵션과 무관하게 아래 isLoading 분기가 기존 "로딩 중..." 안내를 담당한다.
    placeholderData: keepPreviousData,
    retry: false,
  })

  // ── CREATE 권한 게이트 (PR #57) ────────────────────────────────────────────
  const { data: permData, isLoading: isPermLoading } = useProjectPermissions(projectKey)

  // 세 상태로 나눈다 — 「거부」와 「모름」을 합치면 접근성 이름이 거짓을 말하게 된다.
  // 시각적으로는 둘 다 fail-closed(disabled)로 남는다. [NewIssueButton] KDoc 참조.
  const createAccess: CreateAccess =
    !isPermLoading && permData?.permissions.CREATE === true
      ? 'allowed'
      : permData?.permissions.CREATE === false
        ? 'denied'
        : 'unknown'

  // ── 선택 상태 ──────────────────────────────────────────────────────────────
  const { selectedKeys, count, isSelected, toggle, selectAllOnPage, clearPageSelection, clearAll } =
    useIssueSelection()

  // ── C3 FR10 — filter 실변경 시 clearAll ────────────────────────────────────
  // 이전 normalizedFilter를 ref로 추적해 실제 변경이 발생했을 때만 clearAll 호출.
  // page 이동(filter 동일) 시에는 선택 누적을 유지한다.
  const prevNormalizedFilterRef = useRef<string>(JSON.stringify(normalizedFilter))
  useEffect(() => {
    const current = JSON.stringify(normalizedFilter)
    if (current !== prevNormalizedFilterRef.current) {
      prevNormalizedFilterRef.current = current
      clearAll()
    }
  }, [normalizedFilter, clearAll])

  // ── IssueFilterBar onChange ─────────────────────────────────────────────────
  /**
   * IssueFilterBar 필터 변경 핸들러.
   * 새 필터를 onFilterChange로 상위에 전달하고 page=0으로 리셋한다.
   * 상위(IssueListRouteAdapter)가 URL search params를 갱신한다.
   */
  const handleFilterChange = useCallback(
    (nextFilter: IssueFilterParams) => {
      onFilterChange?.(nextFilter)
      onPageChange(0)
    },
    [onFilterChange, onPageChange],
  )

  // ── 정렬 헤더 클릭 핸들러(Task 5, F2·EC3) ───────────────────────────────────
  /**
   * IssueTable 정렬 헤더 클릭 핸들러.
   * nextSortState로 3-state(asc→desc→해제)를 계산해 onSortChange로 상위에 전달하고,
   * 정렬 기준이 바뀌면 현재 page 번호는 의미가 없어지므로 page=0으로 리셋한다(EC3).
   */
  const handleSort = useCallback(
    (field: IssueSortField) => {
      onSortChange?.(nextSortState(sort, field))
      onPageChange(0)
    },
    [sort, onSortChange, onPageChange],
  )

  // ── Dialog 열림 상태 ───────────────────────────────────────────────────────
  const [editOpen, setEditOpen] = useState(false)
  const [transitionOpen, setTransitionOpen] = useState(false)
  const [resultOpen, setResultOpen] = useState(false)
  const [bulkOperationId, setBulkOperationId] = useState<string | null>(null)

  // ── 전체 선택 토글 ─────────────────────────────────────────────────────────
  const pageKeys = useMemo(
    () => data?.content.map((i) => i.key) ?? [],
    [data],
  )
  const isAllPageSelected =
    pageKeys.length > 0 && pageKeys.every((k) => isSelected(k))

  const handleSelectAllPage = useCallback(() => {
    if (isAllPageSelected) {
      clearPageSelection(pageKeys)
    } else {
      selectAllOnPage(pageKeys)
    }
  }, [isAllPageSelected, pageKeys, clearPageSelection, selectAllOnPage])

  // ── FR-UX-10 F10 — 목록 항법 커서 (j/k/o/t) ─────────────────────────────────
  //
  // 커서는 새 상태가 아니라 **기존 split 선택(`selectedKey`)** 이다(ADR D-3).
  // 이 컴포넌트는 목록 데이터를 갖고 있으므로 "다음 키가 무엇인가"를 계산하고,
  // URL 갱신·라우팅 같은 부수효과는 props 콜백으로 상위(어댑터)에 위임한다
  // — "props 기반, 라우터 비의존" 계약 유지.
  useContextShortcuts('issue-list', {
    onCursorMove: (delta) => {
      const next = nextCursorKey(pageKeys, selectedKey, delta)
      if (next !== null) onCursorTo?.(next)
    },
    onOpenCurrent: () => {
      if (selectedKey !== null) onOpenCursor?.(selectedKey)
    },
    onToggleDetailPane: () => {
      // 열려 있으면 닫고, 닫혀 있으면 첫 행을 잡아 연다(S4).
      if (selectedKey !== null) {
        onCloseDetailPane?.()
        return
      }
      const first = pageKeys[0]
      if (first !== undefined) onCursorTo?.(first)
    },
  },
    // ★활성 조건. 이 화면이 실제로 커서 조작을 받을 수 있을 때만 등록한다.
    //
    // - `cursorEnabled` — 어댑터가 넘기는 와이드 여부(E13). 좁은폭에는 커서 강조 자체가
    //   없어 `j` 가 URL 만 바꾸는 유령 상태가 된다.
    // - 로딩/에러 — 목록이 없는데 레이어만 활성이면 키를 삼키고 아무 일도 안 한다.
    // - 모달 3종 — 일괄 편집·전환·결과 다이얼로그는 입력 요소가 없어 `shouldIgnoreEvent`
    //   를 통과한다. 막지 않으면 다이얼로그가 떠 있는데 `j` 가 배후 목록을 옮기고,
    //   **`o` 는 다이얼로그를 띄운 채 화면을 통째로 갈아치운다**(독립 리뷰 I-3).
    //
    // 등록 자체를 끊으므로 `preventDefault` 도 하지 않는다 — 브라우저 기본 동작을
    // 삼키지 않는다(콜백만 `undefined` 로 넘기던 옛 방식의 한계).
    cursorEnabled && !isLoading && error === null && !editOpen && !transitionOpen && !resultOpen,
  )

  /** 커서가 몇 번째 행인지 (0-indexed). 커서 없음/목록에 없음이면 -1 */
  const cursorIndex = selectedKey === null ? -1 : pageKeys.indexOf(selectedKey)

  /**
   * 스크린리더 공지 문구 — `aria-current` 속성 변경은 자동으로 읽히지 않는다(FR11).
   *
   * 위치 → 식별자 → 내용 순으로 싣는다. `polite` 는 연타 중 큐가 쌓이지 않고 손이
   * 멈춘 지점만 읽으므로 총 개수를 매번 실어도 피로하지 않고, 멈췄을 때 위치 감각이
   * 완결된다.
   */
  const cursorAnnouncement =
    cursorIndex >= 0
      ? `${pageKeys.length}개 중 ${cursorIndex + 1}번째, ${selectedKey ?? ''}, ${data?.content[cursorIndex]?.summary ?? ''}`
      : ''

  // 커서가 뷰포트 밖으로 나가면 따라간다(FR10). `nearest` 는 이미 보이면 움직이지
  // 않고 벗어나야 최소로 스크롤해 항법 중 화면이 튀지 않는다.
  useEffect(() => {
    if (selectedKey === null) return
    // ★`tr` 태그 한정이 방어의 핵심이다. `aria-current="true"` 를 쓰는 곳은 앱 전체에
    // 둘 — 이 목록 행(IssueTable `getCurrentRowAttrs`)과 WorkflowSchemeSidebar 의 선택
    // 항목(버튼)이다. 후자는 `tr` 이 아니고 화면도 겹치지 않는다. 값이 `page` 인
    // 사이드바/브레드크럼과도 구분된다. 셀렉터에서 `tr` 을 빼면 이 방어가 사라진다.
    const row = document.querySelector('tr[aria-current="true"]')
    row?.scrollIntoView?.({ block: 'nearest' })
    // ★deps 에 `pageKeys` 가 필요하다. `selectedKey` 만 보면 `?selected=` 를 달고 **직접
    // 진입**했을 때(링크 공유·새로고침) effect 가 로딩 중에 한 번 돌고 끝난다 — 그 시점엔
    // 테이블이 없어 `querySelector` 가 null 이고, 데이터가 도착해도 `selectedKey` 는
    // 그대로라 재실행되지 않아 커서 행이 화면 밖에 남는다. `pageKeys` 는 `useMemo([data])`
    // 라 데이터 도착 시 새 참조가 되어 재시도 지점이 된다.
    // 재조회로 다시 돌더라도 `block:'nearest'` 는 이미 보이는 행을 움직이지 않는다.
  }, [selectedKey, pageKeys])

  // ── Dialog onSubmitted 결선 ────────────────────────────────────────────────
  /**
   * BulkEditDialog / BulkTransitionDialog 에서 접수 성공 시 호출.
   * - 결과 Dialog를 열고 이슈 목록을 invalidate(refetch)하며 선택을 해제한다.
   */
  const handleBulkSubmitted = useCallback(
    (id: string) => {
      setBulkOperationId(id)
      setResultOpen(true)
      void queryClient.invalidateQueries({ queryKey: ['issues', projectKey] })
      clearAll()
    },
    [queryClient, projectKey, clearAll],
  )

  // ── 로딩 / 에러 분기 ───────────────────────────────────────────────────────
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  if ((error !== null && error !== undefined) || data === undefined) {
    return (
      <div role="alert" className="p-8 text-destructive">
        이슈 목록을 불러올 수 없습니다.
      </div>
    )
  }

  return (
    <div className="p-4 sm:p-6 lg:p-8 space-y-4">
      {/* 페이지 헤더 — 타이틀 + 새 이슈 진입점 (CREATE 권한 게이트) */}
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">이슈 목록</h1>
        <NewIssueButton access={createAccess} />
      </header>

      {/*
        FR-UX-10 F10 — 커서 이동 스크린리더 공지(FR11).
        `aria-current` 속성이 바뀌어도 스크린리더는 자동으로 읽지 않으므로
        위치·식별자·제목을 라이브 리전으로 따로 알린다. 시각적으로는 숨긴다.
      */}
      <div
        aria-live="polite"
        role="status"
        data-testid="issue-cursor-announcement"
        className="sr-only"
      >
        {cursorAnnouncement}
      </div>

      {/* G4 — 필터 바: 페이지 헤더 아래, 일괄 액션 바 위 (FR-SR-01 D6) */}
      <IssueFilterBar
        projectKey={projectKey}
        value={filter}
        onChange={handleFilterChange}
      />

      {/* 일괄 액션 바 — count > 0 일 때만 렌더 */}
      <IssueBulkActionBar
        count={count}
        onEdit={() => setEditOpen(true)}
        onTransition={() => setTransitionOpen(true)}
        onClear={clearAll}
      />

      {/* EC1 — 0건 + 필터 있음: 필터 초기화 CTA. 0건 + 필터 없음: 기존 IssueEmptyState.
          문구는 흡수 전 로컬 정의의 값을 verbatim 유지한다(NFR-N1). 보드 쪽은 i18n 상수를 쓰지만
          이슈 쪽 3개 문자열의 i18n 이관은 본 PR 범위 밖이다 — 문구 통일은 별도 판단이 필요하다. */}
      {data.empty && !isEmptyIssueFilter(filter) ? (
        <FilteredEmptyState
          title="필터 조건에 맞는 이슈가 없습니다."
          description="다른 조건을 시도하거나 필터를 초기화하세요."
          resetLabel="필터 초기화"
          className="py-16"
          onReset={() => {
            onFilterChange?.(EMPTY_FILTER)
            onPageChange(0)
          }}
        />
      ) : (
        <IssueListContent
          data={data}
          page={page}
          onPageChange={onPageChange}
          onNavigate={onNavigate}
          isSelected={isSelected}
          onToggle={toggle}
          onSelectAllPage={handleSelectAllPage}
          isAllPageSelected={isAllPageSelected}
          sort={sort}
          onSort={handleSort}
          isFetching={isFetching}
          selectedKey={selectedKey}
          edit={editContext}
        />
      )}

      {/* 일괄 편집 Dialog */}
      <BulkEditDialog
        issueKeys={selectedKeys}
        open={editOpen}
        onOpenChange={setEditOpen}
        onSubmitted={handleBulkSubmitted}
      />

      {/* 일괄 전환 Dialog */}
      <BulkTransitionDialog
        issueKeys={selectedKeys}
        open={transitionOpen}
        onOpenChange={setTransitionOpen}
        onSubmitted={handleBulkSubmitted}
      />

      {/* 일괄 작업 결과 Dialog */}
      <BulkOperationResultDialog
        bulkOperationId={bulkOperationId}
        open={resultOpen}
        onOpenChange={(next) => {
          setResultOpen(next)
          // 닫힐 때 id를 null로 리셋해 재오픈 시 이전 작업 데이터 잔상을 방지한다.
          if (!next) setBulkOperationId(null)
        }}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListRouteAdapter — router.ts에 등록되는 라우트 어댑터 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * URL의 selected 파라미터를 정규화한다 — 빈 문자열/공백만 있는 값은 "미선택"으로 취급한다(EC-1).
 * `/issues?selected=`처럼 값이 빈 문자열로 남아있으면 상세 페인이 `issueKey=''`로 열려
 * 404 렌더가 뜨는 것을 방지한다.
 *
 * @param raw URL search의 selected 원문 문자열
 * @returns 정규화된 이슈 키. 미지정/빈값/공백이면 undefined.
 */
function normalizeSelectedKey(raw: string | undefined): string | undefined {
  if (raw === undefined) return undefined
  const trimmed = raw.trim()
  return trimmed === '' ? undefined : trimmed
}

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * useSearch로 URL의 page + status/assignee/label/component 필터 + sort + selected 파라미터를
 * 추출해 IssueListPage(+ split view 우측 페인)에 전달한다.
 *
 * - page → IssueListPage.page (0-indexed)
 * - status/assignee/label/component → searchToIssueFilter → IssueListPage.filter
 * - sort → parseSortParam → IssueListPage.sort (Task 5, S2 — URL에 정렬 상태 반영)
 * - IssueFilterBar onChange → issueFilterToSearch → navigate(URL 갱신, page=0 리셋)
 * - IssueTable 정렬 헤더 → onSortChange → serializeSortParam → navigate(URL sort 갱신)
 * - 빈 필터/정렬 해제 시 해당 키 자체를 URL에서 제거 (issueFilterToSearch/serializeSortParam이 처리)
 * - selected → split view(FR-UX-06 PR20 Task 5) 우측 상세 페인에 열린 이슈 키(D1).
 *   와이드(`useMediaQuery('(min-width: 1024px)')`)이고 selected가 있을 때만 페인이 등장한다(D2).
 *   좁은폭(D3)은 selected를 무시하고 목록만 렌더하며, 행 클릭은 `/issues/$key` 전체화면으로 이동한다.
 *
 * 라우터 등록은 router.ts 담당.
 */
export function IssueListRouteAdapter(): JSX.Element {
  const search = useSearch({ strict: false }) as {
    page?: number
    sort?: string
    selected?: string
    projectKey?: string
  } & IssueFilterSearch
  const navigate = useNavigate()
  const isWide = useMediaQuery('(min-width: 1024px)')
  // FR-UX-07 — URL > 저장값 > 첫 프로젝트 순으로 해소한다. DEFAULT_PROJECT_KEY 하드코딩 대체.
  const activeProject = useResolvedActiveProject(search.projectKey)
  const page = typeof search.page === 'number' ? search.page : 0
  const sort = useMemo(() => parseSortParam(search.sort), [search.sort])
  const selected = normalizeSelectedKey(search.selected)

  // searchToIssueFilter는 매 렌더마다 새 객체를 반환하므로
  // 실제 search 값이 바뀔 때만 재계산한다 (BoardRouteAdapter 패턴 미러).
  const searchStatus = search.status
  const searchAssignee = search.assignee
  const searchLabel = search.label
  const searchComponent = search.component
  const filter = useMemo(
    () => searchToIssueFilter({
      status: searchStatus,
      assignee: searchAssignee,
      label: searchLabel,
      component: searchComponent,
    }),
    [searchStatus, searchAssignee, searchLabel, searchComponent],
  )

  function handlePageChange(nextPage: number): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, page: nextPage }) })
  }

  /**
   * 행 클릭 핸들러 — 폭에 따라 분기한다(D3).
   * - 와이드: `selected` 검색 파라미터를 토글한다. 다른 검색 파라미터(status/sort/page 등)는
   *   `prev` 스프레드로 보존한다. 같은 키를 다시 클릭하면 `selected`를 해제한다(페인 닫힘).
   * - 좁은폭: split view를 건너뛰고 `/issues/$key` 전체화면 상세로 이동한다.
   */
  function handleNavigate(key: string): void {
    if (isWide) {
      // 모달 선호(기본)면 URL 을 건드리지 않는다 — 열림 상태는 스토어가 쥔다(J1).
      if (openViaPresentation(key)) return
      void navigate({
        to: '/issues',
        search: (prev) => ({ ...prev, selected: prev.selected === key ? undefined : key }),
      })
      return
    }
    void navigate({ to: '/issues/$key', params: { key } })
  }

  /**
   * split view 우측 페인 닫기(D2) — `selected`를 URL에서 제거해 페인을 닫고 목록을 전체폭으로 복귀시킨다.
   * IssueDetailPage(variant='pane')의 onClose(닫기 버튼·Escape)·onIssueClosed(삭제 성공) 양쪽에서 쓰인다.
   */
  function clearSelected(): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, selected: undefined }) })
  }

  // 표시 방식 결선 (J1) — 진입 분기 + 전환 직후 URL 정리
  const { openViaPresentation, detailModalOpen } = useIssueListPresentation({ selected, clearSelected })

  /**
   * split view 우측 페인에 열린 이슈 키를 교체한다.
   * IssueDetailPage(variant='pane')가 옛 키 → 새 키 308 redirect를 감지했을 때(onIssueRedirect) 쓰인다.
   */
  function setSelectedKey(nextKey: string): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, selected: nextKey }) })
  }

  /**
   * FR-UX-10 F10 — `j`/`k`/`t` 가 커서를 옮길 때 URL `selected` 를 갈아끼운다.
   *
   * `setSelectedKey` 와 갈라 두는 이유는 **`replace: true`** 다. 커서 이동은 연타가
   * 전제라 매번 히스토리를 쌓으면 뒤로가기 한 번에 목록을 못 벗어난다. 지라 이슈
   * 네비게이터도 같은 방식이다.
   *
   * ★`prev` 스프레드로 다른 검색 파라미터(projectKey·status·sort·page)를 보존한다 —
   * `project-switcher.spec.ts` 의 "전환 시 다른 검색 파라미터가 살아남는다" 가 이걸
   * 검증한다. TanStack `search` 는 객체형이면 병합이 아니라 **치환**이고 전 필드가
   * optional 이라 타입 체크로도 안 잡힌다(handleFilterChange 주석의 같은 함정).
   */
  function moveCursorTo(nextKey: string): void {
    void navigate({
      to: '/issues',
      search: (prev) => ({ ...prev, selected: nextKey }),
      replace: true,
    })
  }

  /** FR-UX-10 F10 — `o` 로 커서 이슈를 전체화면 상세로 연다(폭 무관) */
  function openCursorIssue(key: string): void {
    void navigate({ to: '/issues/$key', params: { key } })
  }

  /**
   * 필터 변경 핸들러 — issueFilterToSearch로 URL search params 갱신.
   * page=0 리셋은 IssueListPage.handleFilterChange가 onPageChange(0)로도 처리한다.
   * 빈 필터 필드는 issueFilterToSearch가 키 자체를 생략해 URL을 깔끔하게 유지한다.
   *
   * CONCERNS-1(NFR-3) — 상세 페인은 selected 키 기준 독립 fetch로, 목록 필터와 무관하게
   * 유지돼야 한다. search를 통째 nextSearch로 교체하지 않고 prev.selected를 보존한다.
   *
   * ★ FR-UX-07(B2) — `projectKey`도 같은 이유로 명시 보존한다. 어댑터의 navigate 5곳 중
   * 이 함수만 `...prev`를 펼치지 않아, 고치지 않으면 필터를 한 번 누르는 것만으로 URL에서
   * projectKey가 증발한다(TanStack `search`는 객체형이면 병합이 아니라 치환, 전 필드가
   * optional이라 타입 체크로도 안 잡히는 조용한 회귀).
   *
   * ⚠️ 통짜 `...prev` 스프레드는 처방이 아니다 — `issueFilterToSearch`는 빈 필터 키를
   * 생략하므로 prev를 통째 펼치면 **해제한 필터가 되살아난다**. 보존할 키만 명시한다.
   */
  function handleFilterChange(nextFilter: IssueFilterParams): void {
    const nextSearch = issueFilterToSearch(nextFilter)
    void navigate({
      to: '/issues',
      search: (prev) => ({
        ...nextSearch,
        page: 0,
        selected: prev.selected,
        projectKey: prev.projectKey,
      }),
    })
  }

  /**
   * 정렬 변경 핸들러(Task 5) — serializeSortParam으로 URL search params 갱신.
   * page=0 리셋은 IssueListPage.handleSort가 onPageChange(0)로도 처리하지만(이중 안전),
   * handleFilterChange와 동일하게 이 navigate 호출 자체에도 page=0을 포함한다.
   * nextSort가 null(해제)이면 serializeSortParam이 undefined를 반환해 sort 키가 URL에서 제거된다.
   */
  function handleSortChange(nextSort: IssueTableSortState | null): void {
    void navigate({
      to: '/issues',
      search: (prev) => ({ ...prev, sort: serializeSortParam(nextSort), page: 0 }),
    })
  }

  // 활성 프로젝트가 정해지기 전(로딩/에러/0개)에는 IssueListPage를 렌더하지 않는다 —
  // `projectKey: string` non-nullable 계약을 지키기 위해서다(스펙 §8 G3). 조기 반환을
  // 어댑터 최상단이 아니라 여기 두는 이유는 아래 split view 분기 주석 참조(C5).
  const listPage =
    activeProject.status === 'ready' ? (
      <IssueListPage
        projectKey={activeProject.projectKey}
        page={page}
        onPageChange={handlePageChange}
        onNavigate={handleNavigate}
        filter={filter}
        onFilterChange={handleFilterChange}
        sort={sort}
        onSortChange={handleSortChange}
        selectedKey={isWide ? selected : null}
        // FR-UX-10 F10 — 커서 단축키는 **와이드에서만** 산다(E13). 좁은폭은
        // `selectedKey` 를 null 로 넘겨(위 줄) 커서 강조 자체가 없으므로, 등록을 끊지
        // 않으면 `j` 가 URL 만 바꾸고 화면엔 아무 변화가 없는 유령 상태가 된다.
        // 콜백을 undefined 로 끊던 옛 방식과 달리 등록 자체를 막아 preventDefault 도
        // 하지 않는다 — 브라우저 기본 동작을 삼키지 않는다.
        // ★상세 모달이 떠 있으면 **여기서** 끊는다(J1). 모달 안 닫기·`⋯`·PDF 버튼은 입력
        //   요소가 아니라 `shouldIgnoreEvent` 를 통과하고, `issue-detail` 레이어의 폴백이
        //   `j` 를 `issue-list` 로 흘려 모달 뒤 목록이 움직이고 배후에 split 페인까지
        //   마운트된다. 행 클릭 기본이 모달로 바뀌면서 드물던 누수가 주경로가 됐다.
        cursorEnabled={isWide && !detailModalOpen}
        onCursorTo={moveCursorTo}
        onOpenCursor={openCursorIssue}
        onCloseDetailPane={clearSelected}
      />
    ) : (
      <ActiveProjectGate state={activeProject} />
    )

  // D2 — 와이드 + selected일 때만 split view(2컬럼)로 전환한다. 그 외(미선택/좁은폭)는 목록 전체폭.
  // ★표시 방식(J1)은 여기 걸지 않는다 — 근거는 `use-issue-list-presentation.ts` JSDoc.
  if (isWide && selected !== undefined) {
    return (
      <IssueListSplitView
        list={listPage}
        detail={
          // CONCERNS-3 — key={selected}로 이슈 전환 시 인스턴스를 강제 재마운트한다.
          // key 없이 인스턴스가 재사용되면 confirmDelete/isEditingTitle 등 잔여 state가
          // 다음 이슈로 이월돼 잘못된 이슈가 삭제되는 위험이 있다([[react-usestate-stale-key-prop]]).
          <IssueDetailPage
            key={selected}
            issueKey={selected}
            variant="pane"
            onClose={clearSelected}
            onIssueClosed={clearSelected}
            onIssueRedirect={setSelectedKey}
          />
        }
      />
    )
  }

  return listPage
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListSplitView — split view 2컬럼 레이아웃 (FR-UX-06 PR20 Task 5)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueListSplitViewProps {
  /** 좌측 목록(축소) — IssueListPage 렌더 결과 */
  list: JSX.Element
  /** 우측 상세 페인 — IssueDetailPage(variant='pane') 렌더 결과 */
  detail: JSX.Element
}

/**
 * split view 2컬럼 레이아웃(D2, PR20-F3).
 *
 * - 좌측 목록과 우측 상세 페인은 각자 독립 스크롤 컨테이너(`overflow-y-auto`)다 — 긴 상세를
 *   스크롤해도 목록 스크롤에 영향을 주지 않는다.
 * - 좌측 목록은 폭이 줄어도 컬럼을 축약하지 않고 유지한 채 가로 스크롤한다(taste A —
 *   반응형 컬럼 축약 도입 금지, `overflow-x-auto`).
 * - `h-full`/`min-h-0` 조합은 ShellLayout의 `<main className="min-w-0 flex-1 overflow-y-auto">`
 *   높이를 그대로 물려받아, 바깥 main이 아닌 이 컴포넌트의 두 자식이 각자 스크롤하게 한다.
 */
function IssueListSplitView({ list, detail }: IssueListSplitViewProps): JSX.Element {
  return (
    <div className="flex h-full min-h-0">
      <div className="min-w-0 shrink-0 basis-[420px] overflow-x-auto overflow-y-auto border-r border-border">
        {list}
      </div>
      <div className="min-w-0 flex-1 overflow-y-auto">
        {detail}
      </div>
    </div>
  )
}
