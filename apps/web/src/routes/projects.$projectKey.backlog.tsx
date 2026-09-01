// 백로그·스프린트 라우트 — BacklogRouteAdapter + BacklogPage (FR-BL-01/02 D6/D7 Task 9 · FR-UX-13 F16)
import type { JSX } from 'react'
import { useCallback, useMemo } from 'react'
import { useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { BacklogBoard } from '@/components/backlog/BacklogBoard'
import { ProjectNavTabs, type ProjectNavTabLink } from '@/components/project/ProjectNavTabs'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { backlogLabels } from '@/i18n/backlog-labels'
import { filterToSearch, searchToFilter } from '@/lib/backlog-filter'
import type { BacklogFilter, BacklogFilterSearch } from '@/lib/backlog-filter'

// ─────────────────────────────────────────────────────────────────────────────
// 뷰 전환 nav 링크 — ProjectNavTabs에 전달(회귀-무해 원칙: 기존 인라인 nav 링크 집합 그대로)
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그 페이지 뷰 전환 링크 — 보드·타임라인·벨로시티·CFD·사이클/리드 타임 (backlog.tsx 인라인 nav 원본과 동일 순서) */
const BACKLOG_VIEW_NAV_LINKS: readonly ProjectNavTabLink[] = [
  { to: '/projects/$projectKey/board', label: backlogLabels.page.boardLink },
  { to: '/projects/$projectKey/timeline', label: backlogLabels.page.timelineLink },
  { to: '/projects/$projectKey/reports/velocity', label: backlogLabels.page.velocityLink },
  { to: '/projects/$projectKey/reports/cfd', label: backlogLabels.page.cfdLink },
  { to: '/projects/$projectKey/reports/cycle-time', label: backlogLabels.page.cycleTimeLink },
]

// ─────────────────────────────────────────────────────────────────────────────
// URL 무변경 판정 (FR-UX-13 F16-9)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 두 search 객체가 **같은 URL**을 뜻하는지 본다.
 *
 * 직렬화 비교가 성립하는 이유는 `filterToSearch`가 키를 항상 q → assignee → epic 순서로
 * 넣고 빈 축의 키는 아예 만들지 않기 때문이다. 두 인자 모두 그 함수의 결과여야 한다.
 *
 * @param a 비교 대상 (filterToSearch 결과)
 * @param b 비교 대상 (filterToSearch 결과)
 * @returns 같은 URL이면 true
 */
function isSameSearch(a: BacklogFilterSearch, b: BacklogFilterSearch): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을, useSearch로 보드 스코프(board)와 필터 3축(q·assignee·epic)을
 * 추출해 BacklogPage에 전달한다 (F16-9 · FR-BD-04 — 보드 어댑터 `projects.$projectKey.board.tsx:100` 선례).
 *
 * `board`는 **읽기만** 한다. 없으면 `undefined`를 그대로 흘려 서버 폴백(기본 보드)에 맡긴다 —
 * 프론트가 URL에 `?board=`를 자동으로 채워 넣으면 nav 링크·공유 링크의 형태가 바뀐다(편차 X6).
 */
export function BacklogRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  // 필터 3축과 board는 축의 성격이 다르다 — board는 **서버 쿼리**이고 나머지는 클라이언트 필터라
  // `BacklogFilterSearch`에 넣지 않고 교차 타입으로 읽는다 (fr-ux-13-f16 결정 존중).
  const search = useSearch({ strict: false }) as BacklogFilterSearch & { board?: string }

  // searchToFilter는 매 렌더마다 새 객체를 반환하므로 실제 search 값이 바뀔 때만 재계산한다.
  // 축을 직접 나열해 react-hooks/exhaustive-deps를 만족시킨다 (보드 어댑터 :109 선례).
  const q = search.q
  const assignee = search.assignee
  const epic = search.epic
  const filter = useMemo(() => searchToFilter({ q, assignee, epic }), [q, assignee, epic])

  return <BacklogPage projectKey={projectKey ?? ''} boardId={search.board} filter={filter} />
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogPage Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogPage Props */
export interface BacklogPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
  /**
   * URL search의 `?board=`에서 읽은 보드 UUID. 미지정이면 `undefined` (FR-BD-04).
   *
   * 백로그는 프로젝트가 아니라 **보드**에 속한다(J14). 다만 사용자가 boardId를 타이핑하지는
   * 않으므로(J17) 미지정 진입은 정상 경로이고, 그때 기본 보드를 고르는 것은 **서버**다 —
   * 프론트가 같이 고르면 판단이 두 곳으로 갈려 화면과 서버가 다른 보드를 말하게 된다.
   */
  readonly boardId: string | undefined
  /**
   * URL search에서 복원한 현재 필터 (F16-9).
   *
   * **진실 출처는 URL 한 곳**이다. 기본값을 두지 않는 것이 계약이다 — 폴백을 두면
   * 「URL 이 비어 있을 때만 도는 두 번째 상태」가 생기고, 그때부터 새로고침·링크 공유가
   * 화면과 다른 말을 하기 시작한다.
   */
  readonly filter: BacklogFilter
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogPage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 보드 페이지.
 *
 * - useProjectPermissions로 현재 사용자 권한을 조회한다.
 * - CREATE 권한 → canManageSprint(스프린트 생성·시작·완료 버튼).
 * - UPDATE 권한 → canReorderIssue(이슈 드래그 재정렬·할당·해제).
 *   두 권한을 분리해 MEMBER(CREATE=true, UPDATE=true)와 비멤버(둘 다 false)를 정확히 게이팅한다.
 *   undefined이면 false-safe — 로딩 중에는 모든 관리 기능 비활성.
 *
 * @param projectKey 프로젝트 식별 키
 * @param boardId URL search의 `?board=`에서 읽은 보드 UUID (미지정이면 undefined)
 * @param filter URL search에서 복원한 현재 필터
 */
export function BacklogPage({ projectKey, boardId, filter }: BacklogPageProps): JSX.Element {
  const { data: projectPermissions } = useProjectPermissions(projectKey)
  const navigate = useNavigate()

  /**
   * 필터 변경을 URL에 싣는다 (F16-9). 보드 화면의 `handleFilterChange`와 같은 형태다.
   *
   * `replace: true` — 제목 검색이 250ms 디바운스로 올라오므로 push를 쓰면 타이핑 한 번에
   * 히스토리가 여러 칸 쌓이고, 뒤로가기가 되돌린 검색어를 필터바의 디바운스가 즉시 다시
   * 밀어 올려 **뒤로가기가 먹통이 된다**. 연타성 갱신에 replace를 쓰는 것은
   * `issues.index.tsx:916`이 같은 이유로 세운 선례다.
   *
   * `useCallback` — 이 함수는 `BacklogFilterBar`의 동기화 effect deps까지 흘러간다.
   * 매 렌더 새 참조면 그 effect가 렌더마다 재실행된다.
   */
  const handleFilterChange = useCallback(
    (next: BacklogFilter): void => {
      const nextSearch = filterToSearch(next)
      // URL이 그대로면 navigate하지 않는다. 공백만 있는 검색어처럼 왕복에서 사라지는 값은
      // 라우터가 새 search 객체를 만들고 → 필터바가 같은 값을 다시 올리는 **무한 왕복**이 된다.
      if (isSameSearch(filterToSearch(filter), nextSearch)) return
      void navigate({
        to: '/projects/$projectKey/backlog',
        params: { projectKey },
        search: nextSearch,
        replace: true,
      })
    },
    [filter, navigate, projectKey],
  )

  // CREATE 권한 — 스프린트 생성·시작·완료 게이팅
  const canManageSprint: boolean = projectPermissions?.permissions.CREATE === true
  // UPDATE 권한 — 이슈 재정렬·할당·해제 드래그 게이팅
  const canReorderIssue: boolean = projectPermissions?.permissions.UPDATE === true
  // CREATE 권한 — 칸 이슈 생성 진입점 게이팅 (FR-UX-09 F3 FR-6)
  //
  // 값은 canManageSprint 와 같지만 **이름을 따로 둔다** — 「스프린트 관리」와
  // 「이슈 생성」은 다른 행위다. 한쪽 권한이 갈라지는 날 같은 변수를 쓰고 있으면
  // 두 기능이 한꺼번에 잘못된다. 판정식은 선례(routes/issues.index.tsx NewIssueButton)와
  // 같은 fail-closed — 로딩·에러·미보유는 전부 false 다.
  const canCreateIssue: boolean = projectPermissions?.permissions.CREATE === true

  return (
    <div className="p-6 space-y-4">
      {/* 헤더 행 — 제목 + 보드 링크 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">{backlogLabels.page.title}</h1>
        <ProjectNavTabs projectKey={projectKey} links={BACKLOG_VIEW_NAV_LINKS} />
      </div>

      <BacklogBoard
        projectKey={projectKey}
        boardId={boardId}
        filter={filter}
        onFilterChange={handleFilterChange}
        canManageSprint={canManageSprint}
        canReorderIssue={canReorderIssue}
        canCreateIssue={canCreateIssue}
      />
    </div>
  )
}
