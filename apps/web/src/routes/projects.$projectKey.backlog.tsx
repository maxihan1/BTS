// 백로그·스프린트 라우트 — BacklogRouteAdapter + BacklogPage (FR-BL-01/02 D6/D7 Task 9 · FR-UX-13 F16)
import type { JSX } from 'react'
import { useCallback, useMemo } from 'react'
import { useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { BacklogBoard } from '@/components/backlog/BacklogBoard'
import { BoardSelectorDropdown } from '@/components/board/BoardSelectorDropdown'
import { ProjectNavTabs, type ProjectNavTabLink } from '@/components/project/ProjectNavTabs'
import { useBoards } from '@/hooks/use-boards'
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
// URL search 조립 + 무변경 판정 (FR-UX-13 F16-9 · FR-BD-04)
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그 URL search 전량 — 보드 스코프 1축 + 클라이언트 필터 3축 */
interface BacklogSearch extends BacklogFilterSearch {
  /** 보고 있는 보드 UUID. 미지정이면 키 자체가 없다 */
  board?: string
}

/**
 * navigate({ search }) 에 넘길 search 객체를 조립한다 (FR-BD-04 E5).
 *
 * 보드 화면이 같은 문제를 먼저 풀었다 — `projects.$projectKey.board.tsx` 의
 * `buildBoardSearch(currentBoardId, filterToSearch(next))`. 필터만 실어 보내면 `?board=`가
 * **증발해** 필터를 한 번 건드리는 순간 사용자가 모르는 사이 기본 보드로 갈아탄다.
 *
 * `boardId`가 없으면 키를 **만들지 않는다**. `board: undefined`를 남기면 `?board=`가 URL에
 * 남을 수 있고, 프론트가 nav 링크와 다른 모양의 주소를 만드는 것은 편차 X6 이 금지한다.
 *
 * @param boardId 보고 있는 보드 UUID. 미지정이면 undefined
 * @param filterSearch `filterToSearch` 결과. 생략하면 필터 없음(초기화)
 * @returns navigate 에 그대로 넘길 search 객체
 */
function buildBacklogSearch(
  boardId: string | undefined,
  filterSearch: BacklogFilterSearch = {},
): BacklogSearch {
  return {
    ...(boardId !== undefined ? { board: boardId } : {}),
    ...filterSearch,
  }
}

/**
 * 두 search 객체가 **같은 URL**을 뜻하는지 본다.
 *
 * 직렬화 비교가 성립하는 이유는 키 순서가 고정이기 때문이다 — `board`는 {@link buildBacklogSearch}
 * 가 항상 맨 앞에 놓고, 나머지는 `filterToSearch`가 항상 q → assignee → epic 순서로 넣으며
 * 빈 축의 키는 아예 만들지 않는다. 두 인자 모두 그 두 함수를 지나온 값이어야 한다.
 *
 * @param a 비교 대상 (buildBacklogSearch 결과)
 * @param b 비교 대상 (buildBacklogSearch 결과)
 * @returns 같은 URL이면 true
 */
function isSameSearch(a: BacklogSearch, b: BacklogSearch): boolean {
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
  const search = useSearch({ strict: false }) as BacklogSearch

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
  const { data: boards } = useBoards(projectKey)
  const navigate = useNavigate()

  /**
   * 스위처에 실을 보드 — **스크럼만** 남긴다 (편차 X4 · 엣지 E3).
   *
   * 칸반 보드는 백로그 탭 없이 간다. 여기서 칸반을 고를 수 있으면 `?board=<칸반>` 으로 백로그가
   * 열리고(서버는 200 을 준다) 거기서 만든 스프린트에 `boardId=<칸반>` 이 붙는다. 그런데 서버
   * `getBoard` 는 `boardType == SCRUM` 일 때만 활성 스프린트를 조회하므로 **그 스프린트는 어느
   * 보드 화면에도 영원히 안 나타난다** — 사용자에게는 「시작했는데 아무 일도 안 일어남」이다.
   *
   * `BoardSummary.boardType` 이 존재하는 이유가 이 자리다(목록 응답에 실리는 유일한 종류 정보).
   */
  const scrumBoards = useMemo(
    () => boards?.filter((b) => b.boardType === 'SCRUM') ?? [],
    [boards],
  )

  /**
   * 보드 전환을 URL에 싣는다 (FR-BD-04 E6).
   *
   * 필터 3축을 **그대로 들고 간다**. 담당자·에픽은 보드와 독립된 축이라 보드를 바꿨다고
   * 조건까지 풀리면 사용자는 방금 세운 조건을 매번 다시 세워야 한다.
   *
   * `replace`를 쓰지 않는다 — 보드 전환은 디바운스 연타가 아니라 **한 번의 의도적 이동**이고,
   * 뒤로가기로 이전 보드에 돌아갈 수 있어야 한다 (보드 화면의 스위처와 같은 판단).
   */
  const handleBoardChange = useCallback(
    (nextBoardId: string): void => {
      if (nextBoardId === boardId) return
      void navigate({
        to: '/projects/$projectKey/backlog',
        params: { projectKey },
        search: buildBacklogSearch(nextBoardId, filterToSearch(filter)),
      })
    },
    [boardId, filter, navigate, projectKey],
  )

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
      // ★`board`를 **함께** 실어 보낸다 (E5). 필터만 보내면 보고 있던 보드가 URL에서 사라져
      //   필터를 한 번 건드리는 것만으로 기본 보드로 갈아타 버린다.
      const nextSearch = buildBacklogSearch(boardId, filterToSearch(next))
      // URL이 그대로면 navigate하지 않는다. 공백만 있는 검색어처럼 왕복에서 사라지는 값은
      // 라우터가 새 search 객체를 만들고 → 필터바가 같은 값을 다시 올리는 **무한 왕복**이 된다.
      if (isSameSearch(buildBacklogSearch(boardId, filterToSearch(filter)), nextSearch)) return
      void navigate({
        to: '/projects/$projectKey/backlog',
        params: { projectKey },
        search: nextSearch,
        replace: true,
      })
    },
    [boardId, filter, navigate, projectKey],
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
      {/* 헤더 행 — 제목 + 보드 스위처 + 보드 링크 */}
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold">{backlogLabels.page.title}</h1>
          {/* 보드 스위처 — 백로그는 프로젝트가 아니라 **보드**에 속한다(J14). 보드가 1개여도
              상시 노출해 N개 모델임을 드러낸다(보드 화면과 같은 판단 · J1).
              🛑 `showCreate={false}` — 여기는 보드를 **고르는** 자리다. 「새 보드」가 딸려 오면
                 사용자는 백로그를 보드 만드는 자리로 읽는다 (BoardSelectorDropdown KDoc).
              🛑 목록은 `scrumBoards` 다 — 칸반이 섞이면 X4·E3 을 깬다(위 KDoc). 스크럼이 0개면
                 「빈 드롭다운 금지」 분기가 그대로 받아 스위처 자체가 사라진다. */}
          {scrumBoards.length > 0 && (
            <BoardSelectorDropdown
              boards={scrumBoards}
              currentBoardId={boardId}
              projectKey={projectKey}
              onSelect={handleBoardChange}
              showCreate={false}
            />
          )}
        </div>
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
