// 칸반 보드 BC MSW 핸들러 — stateful CRUD + 카드 이동 + 409 충돌 토글 (FR-BD-01 D6, FR-BD-02 D6, FR-UX-01)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: move 후 GET 상세에 즉시 반영되도록 boardStore 변이
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//     ★스크럼 보드의 카드는 **backlogStore 의 활성 스프린트 이슈**에서 파생된다(FR-BD-04).
//       시작 핸들러가 boardStore 에 심은 마커를 여기서 읽어 배치한다 — 두 요청이 같은 store 를
//       보지 않으면 「시작해도 보드가 안 바뀐다」가 된다(2026-09-02 D7 E2E S7 실측).
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//
import { http, HttpResponse } from 'msw'
import type {
  ActiveSprint,
  BoardDetail,
  BoardCard,
  BoardCardFilterParams,
  SwimlaneField,
  BoardType,
} from '@/api/boards'
import type { BacklogIssue } from '@/api/backlog'
import { buildBoardFilterQuery } from '@/api/boards'
import type { QuickFilter } from '@/api/board-quick-filters'
import { queryStringToSearch, searchToFilter } from '@/lib/board-filter'
import type { StoredBoardDetail, StoredCard } from './board-fixtures'
import {
  boardStore,
  projectBoardIndex,
  createBoardInStore,
  deleteBoardFromStore,
  generateUUID,
  LS_KEY_BOARD_CONFLICT,
  seedBoardWithMeta,
} from './board-fixtures'
// FR-UX-06 PR21 Task 8 — 셀 내 순서변경(useReorderCard) stateful 연결.
// useReorderCard는 PATCH /api/v1/issues/:key/rank(backlog-handlers.ts rerankIssueHandler)를 호출해
// backlogStore(issue-tracking BC 백로그 mock, 이 파일과 별개 Map)를 변이한다. board GET이 boardStore
// 자신의 rank만 읽으면 rerank 후 invalidateQueries 재조회 시 boardStore의 옛 rank로 되돌아간다
// (msw-mutation-stateful-refetch 회귀 — 새로고침 후 순서가 사라짐). backlogStore에 같은 issueKey가
// 있으면 그 최신 rank를 읽기 전용으로 오버레이해 두 store가 같은 진실을 공유하도록 한다.
import { backlogStore, findIssueInProject, findSprintInStore } from './backlog-fixtures'
// FR-UX-06 PR21b Task 6 — 스윔레인 간 드래그 필드변경(담당자/우선순위/에픽) stateful 연결.
// useChangeCardField는 PATCH /api/v1/issues/:key/assignee(changeAssigneeHandler),
// PATCH /api/v1/issues/:key(updateIssueHandler), POST/DELETE
// /api/v1/issues/:epicKey/epic-children(connectEpicChildHandler/disconnectEpicChildHandler)를
// 호출해 issue-handlers.ts의 비공개 store(issueOverrides)를 변이한다. board GET이 boardStore
// 자신의 assigneeId/priority/epicKey만 읽으면 필드변경 후 invalidateQueries 재조회 시 boardStore의
// 옛 시드값으로 되돌아간다(msw-mutation-stateful-refetch 회귀 — resolveLiveRank와 동일 문제).
// getIssueFieldOverride(읽기 전용 접근자, issue-handlers.ts에 순수 추가)로 최신값을 오버레이한다.
import { getIssueFieldOverride } from './issue-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 필터 술어 헬퍼 (FR-BD-02)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드가 query param 필터 조건을 모두 만족하는지 판단한다.
 *
 * 필드 내(assignee 복수, label 복수, component 복수)는 OR,
 * 필드 간(assignee vs label vs component)은 AND.
 *
 * assignee=unassigned → assigneeId가 null인 카드만 통과.
 *
 * @param card 평가할 store 내부 카드
 * @param params URLSearchParams — request URL에서 파싱한 파라미터
 */
function matchesFilter(card: StoredCard, params: URLSearchParams): boolean {
  const assignees = params.getAll('assignee')
  if (assignees.length > 0) {
    const passesAssignee = assignees.some((a) => {
      if (a === 'unassigned') {
        return card.assigneeId === null
      }
      return card.assigneeId === a
    })
    if (!passesAssignee) {
      return false
    }
  }

  const labels = params.getAll('label')
  if (labels.length > 0) {
    const passesLabel = labels.some((l) => card.labels.includes(l))
    if (!passesLabel) {
      return false
    }
  }

  const components = params.getAll('component')
  if (components.length > 0) {
    const passesComponent = components.some((c) => card.componentIds.includes(c))
    if (!passesComponent) {
      return false
    }
  }

  return true
}

/**
 * 카드의 실질 rank를 결정한다 (FR-UX-06 PR21 Task 8).
 *
 * backlogStore(issue-tracking BC 백로그 mock)에 같은 projectKey·issueKey 조합이 있으면
 * 그 최신 rank를 우선 사용한다 — rerankIssueHandler가 그 store만 변이하기 때문에, board GET이
 * boardStore 자체 rank만 읽으면 새로고침 후 순서가 재시드 값으로 되돌아간다.
 * backlogStore에 없는 이슈(다른 fixture 프로젝트 등)는 fallback(보드 자체 rank)을 그대로 쓴다 —
 * 기존 회귀 없음(FILTER_BOARD·WIP_BOARD·SWIMLANE_BOARD·EPIC_SWIMLANE_BOARD·
 * REORDER_SWIMLANE_BOARD 모두 backlogStore에 대응 이슈 없음).
 *
 * @param projectKey 보드가 속한 프로젝트 키
 * @param issueKey rank를 조회할 이슈 키
 * @param fallback backlogStore에 없을 때 사용할 boardStore 자체 rank (null 가능)
 */
function resolveLiveRank(
  projectKey: string,
  issueKey: string,
  fallback: string | null,
): string | null {
  const backlogProject = backlogStore.get(projectKey)
  if (backlogProject === undefined) return fallback
  const liveIssue = findIssueInProject(backlogProject, issueKey)
  return liveIssue?.rank ?? fallback
}

/** resolveLiveField가 다루는 필드 부분집합 — BoardCard의 assignee/priority/epic 3필드. */
interface LiveFieldSet {
  assigneeId: string | null
  priority: number
  epicKey: string | null
}

/**
 * 카드의 실질 담당자/우선순위/에픽을 결정한다 (FR-UX-06 PR21b Task 6).
 *
 * getIssueFieldOverride(issue-handlers.ts 읽기 전용 접근자)에 값이 있으면 — 즉 changeAssignee/
 * updateIssue/connectEpicChild/disconnectEpicChild 핸들러가 실제로 그 이슈를 변경한 적이 있으면
 * — 그 최신값을 우선 사용한다. 없으면(필드변경 이력 없음) board 자체 시드값(fallback)을 그대로
 * 쓴다 — resolveLiveRank와 동일한 오버레이 패턴. 기존 26개+ 보드 fixture/E2E 스펙은 필드변경을
 * 트리거하지 않으므로 무회귀.
 *
 * @param issueKey 담당자/우선순위/에픽을 조회할 이슈 키
 * @param fallback getIssueFieldOverride에 값이 없을 때 사용할 board 자체 카드 시드값
 */
function resolveLiveField(issueKey: string, fallback: LiveFieldSet): LiveFieldSet {
  return getIssueFieldOverride(issueKey) ?? fallback
}

/**
 * rank 오름차순 정렬 — null은 맨 뒤(NULLS LAST). backlog-handlers.ts byRankNullsLast와 동일 규약.
 * Array.prototype.sort는 안정 정렬(stable, ES2019+)이므로 rank가 같거나 둘 다 null이면
 * 원본(스토어) 순서를 그대로 보존한다 — 기존 fixture(rank 미부여 카드들)는 회귀 없음.
 */
function byRankNullsLast(a: { rank: string | null }, b: { rank: string | null }): number {
  if (a.rank === null) return b.rank === null ? 0 : 1
  if (b.rank === null) return -1
  return a.rank < b.rank ? -1 : a.rank > b.rank ? 1 : 0
}

// ─────────────────────────────────────────────────────────────────────────────
// 스크럼 보드 — 활성 스프린트 파생 (FR-BD-04)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이 보드가 스크럼인지 판정한다. 종류 미설정 시드는 기본값(칸반)으로 본다.
 *
 * ★분기가 **조회 시점**에 있는 것이 계약이다. 백엔드 `BoardApplicationService.getBoard` 도
 * `boardType == SCRUM` 일 때만 활성 스프린트를 조회한다 — 칸반 보드에 붙은 스프린트가
 * 데이터에 실재해도(백엔드 `sprints.board_id` 에 종류 제약이 없다) 응답에는 나타나지 않는다
 * (J6 *"Active sprints are only available on Scrum boards."*). 시드나 시작 핸들러 쪽에서
 * 가리면 나중에 생기는 경로가 그 가드를 조용히 우회한다.
 *
 * @param stored store 내부 보드 데이터
 */
function isScrumBoard(stored: StoredBoardDetail): boolean {
  return (stored.boardType ?? DEFAULT_BOARD_TYPE) === 'SCRUM'
}

/**
 * 응답에 실을 활성 스프린트를 정한다. **칸반은 언제나 null** 이다 (FR-BD-04).
 *
 * @param stored store 내부 보드 데이터
 */
function resolveActiveSprint(stored: StoredBoardDetail): ActiveSprint | null {
  return isScrumBoard(stored) ? stored.activeSprint ?? null : null
}

/**
 * 백로그 이슈를 보드 카드로 옮긴다 (FR-BD-04).
 *
 * `componentIds` 는 백로그 이슈에 없는 축이라 빈 배열이다 — store 전용 필터 메타이며
 * 응답 DTO(`BoardCard`)에는 실리지 않는다. 그래서 스크럼 보드 카드는 컴포넌트 필터에 걸리지
 * 않는다(목의 한계 — 백엔드는 이슈의 실제 컴포넌트로 거른다).
 *
 * @param issue backlogStore 의 이슈
 */
function sprintIssueToCard(issue: BacklogIssue): StoredCard {
  return {
    issueKey: issue.key,
    summary: issue.summary,
    assigneeId: issue.assigneeId,
    version: issue.version,
    priority: issue.priority,
    epicKey: issue.epicKey,
    rank: issue.rank,
    typeKey: issue.typeKey,
    labels: issue.labels,
    originalEstimateSeconds: issue.originalEstimateSeconds,
    componentIds: [],
  }
}

/**
 * 스크럼 보드의 컬럼을 **활성 스프린트의 이슈만으로** 다시 채운다 (FR-BD-04 · 백엔드 getBoard 미러).
 *
 * 배치 축은 `currentStateKey` ↔ 컬럼 `stateKey` 다(백엔드 `BoardCardPlacement.placeCards`).
 * 어느 컬럼에도 매핑되지 않는 이슈는 카드가 되지 못하고 `unplacedCount` 로 샌다.
 * 활성 스프린트가 없는 스크럼 보드는 카드가 0건이다 — 백엔드도 스프린트 이슈 키 집합이
 * 비면 모든 이슈가 걸러진다(그 자리는 「활성 스프린트가 없습니다」 빈 상태다).
 *
 * 🛑 **칸반은 이 경로를 아예 타지 않는다.** 기존 보드 시드가 전부 칸반이라 여기서 새면
 * 보드 E2E 전량이 자기 카드를 잃는다.
 *
 * @param stored store 내부 보드 데이터
 * @returns 스크럼이면 활성 스프린트 이슈로 채운 사본, 칸반이면 원본 그대로
 */
function withActiveSprintCards(stored: StoredBoardDetail): StoredBoardDetail {
  if (!isScrumBoard(stored)) return stored

  const activeSprint = stored.activeSprint ?? null
  const issues =
    activeSprint === null
      ? []
      : findSprintInStore(activeSprint.sprintId)?.storedSprint.issues ?? []

  const columns = stored.columns.map((col) => ({
    ...col,
    // 컬럼이 담은 **모든** 상태의 카드를 모은다(R3). 1:1 시절의 `=== col.stateKey` 자리다.
    cards: issues
      .filter((i) => col.states.some((s) => s.key === i.currentStateKey))
      .map(sprintIssueToCard),
  }))
  const placedCount = columns.reduce((sum, col) => sum + col.cards.length, 0)

  return { ...stored, columns, unplacedCount: issues.length - placedCount }
}

/**
 * store 카드 한 장을 응답 DTO(`BoardCard`)로 옮긴다.
 *
 * store 전용 필터 메타(`componentIds`)는 **싣지 않는다** — 필드를 하나씩 적는 것이 그 계약이다
 * (스프레드로 바꾸면 메타가 조용히 새고, 그것을 막는 가드는 `board-handlers.test.ts` 뿐이다).
 * 담당자·우선순위·에픽은 issueOverrides 오버레이({@link resolveLiveField}), rank 는 backlogStore
 * 오버레이({@link resolveLiveRank})를 각각 적용한다.
 *
 * @param projectKey 보드가 속한 프로젝트 키 — rank 오버레이의 조회 축
 * @param card store 내부 카드
 */
function toResponseCard(projectKey: string, card: StoredCard): BoardCard {
  const liveField = resolveLiveField(card.issueKey, {
    assigneeId: card.assigneeId,
    priority: card.priority,
    epicKey: card.epicKey ?? null,
  })
  return {
    issueKey: card.issueKey,
    summary: card.summary,
    assigneeId: liveField.assigneeId,
    version: card.version,
    priority: liveField.priority,
    epicKey: liveField.epicKey,
    rank: resolveLiveRank(projectKey, card.issueKey, card.rank ?? null),
    typeKey: card.typeKey,
    labels: card.labels,
    originalEstimateSeconds: card.originalEstimateSeconds,
  }
}

/**
 * StoredBoardDetail을 BoardDetail 응답 형식으로 변환한다.
 *
 * componentIds는 store 내부 필터용 메타이며 응답 DTO(BoardCard)에 포함하지 않는다.
 * labels는 FR-UX-14 B2(#346)부터 BoardCard 정식 필드라 필터 술어 평가와 응답 양쪽에 쓰인다.
 * params가 주어지면 matchesFilter를 적용해 카드를 걸러낸다.
 * quickFilters는 store에 없으면(WIP_BOARD 등 퀵필터를 다루지 않는 기존 fixture) 빈 배열로 방어한다.
 * 컬럼 카드는 rank(backlogStore 오버레이 적용) 오름차순으로 정렬해 반환한다(FR-UX-06 PR21 Task 8).
 * assigneeId/priority/epicKey는 issueOverrides 오버레이(resolveLiveField)를 적용한다
 * (FR-UX-06 PR21b Task 6). typeKey/labels/originalEstimateSeconds는 store 시드값을 그대로 반환한다
 * (FR-UX-14 F14 — 필드변경 오버레이 대상이 아니다).
 *
 * 스크럼 보드는 컬럼 카드가 **활성 스프린트 이슈로 대체된 뒤** 나머지 조립이 돌아간다
 * ({@link withActiveSprintCards} · FR-BD-04). 칸반은 그 함수를 통과해도 원본 그대로다.
 *
 * @param stored store 내부 보드 데이터
 * @param params 필터 파라미터 (없으면 전체 카드 반환)
 */
function toResponseDetail(stored: StoredBoardDetail, params: URLSearchParams): BoardDetail {
  const placed = withActiveSprintCards(stored)
  return {
    ...placed,
    // mock 은 워크플로우 카탈로그를 모르므로 미매핑 목록을 계산할 수 없다. 빈 배열이 정직한 값이다 —
    // 이 mock 의 시드는 모든 상태가 컬럼에 매핑돼 있다(R8 은 실제 카탈로그가 있어야 의미가 있다).
    unmappedStates: [],
    columns: placed.columns.map((col) => ({
      ...col,
      cards: col.cards
        .filter((card) => matchesFilter(card, params))
        .map((card) => toResponseCard(placed.projectKey, card))
        .sort(byRankNullsLast),
    })),
    quickFilters: placed.quickFilters ?? [],
    // 백엔드는 단건 조회 응답에 canDelete를 항상 싣는다(FR-BD-01-2d). mock도 항상 실어
    // 「응답에 있다」를 전제로 한 소비자가 mock 위에서만 통과하는 일이 없게 한다.
    canDelete: placed.canDelete ?? true,
    // ★`...placed` 만으로는 두 필드가 `undefined` 로 새어 나간다 — StoredBoardDetail 에서
    //   둘 다 optional 이기 때문이다(BoardDetail 과 별개 타입이라 컴파일러가 안 잡는다).
    //   boardDetailSchema 는 둘을 **필수 키**로 요구하므로 여기서 반드시 값을 채운다.
    boardType: placed.boardType ?? DEFAULT_BOARD_TYPE,
    // 칸반은 언제나 null — 판정은 조회 시점에 한다({@link resolveActiveSprint}).
    activeSprint: resolveActiveSprint(placed),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 퀵필터 query 정규화 (FR-UX-01)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 query 문자열을 백엔드 `BoardFilterQueryParser.serialize` 계약과 동일한 규칙으로 정규화한다.
 *
 * 필드 순서(assignee→unassigned→label→component, `buildBoardFilterQuery`가 이미 이 순서로 조립)
 * + trim + 필드별 중복 제거. raw 요청 query를 그대로 저장/에코하면(정규화 미재현) 실제 백엔드가
 * 정규화를 적용한 후에는 프론트 테스트가 가짜그린이 된다 — 반드시 재계산해서 반환한다
 * (memory: msw-derived-behavior-shared-store-e2e, 리뷰 C2).
 *
 * @param query 접두 `?` 없는 쿼리스트링(요청 body의 query)
 * @returns 정규화된 접두 `?` 없는 쿼리스트링. 조건이 없으면 빈 문자열
 */
function normalizeQuickFilterQuery(query: string): string {
  const search = queryStringToSearch(query)
  const filter = searchToFilter(search)
  const dedupeTrim = (values: string[]): string[] =>
    Array.from(new Set(values.map((v) => v.trim()).filter((v) => v.length > 0)))
  const normalized: BoardCardFilterParams = {
    assigneeIds: dedupeTrim(filter.assigneeIds),
    includeUnassigned: filter.includeUnassigned,
    labels: dedupeTrim(filter.labels),
    componentIds: dedupeTrim(filter.componentIds),
  }
  const qs = buildBoardFilterQuery(normalized)
  return qs.startsWith('?') ? qs.slice(1) : qs
}

/**
 * 퀵필터 생성/수정 요청 body({ name, query })를 파싱한다.
 * POST/PATCH 핸들러가 공유하는 파싱 로직 응집 — createQuickFilterHandler/updateQuickFilterHandler 동일 계약.
 *
 * @param request MSW가 전달한 원본 Request
 * @returns 파싱된 { name, query } (누락 필드는 빈 문자열). JSON 파싱 자체가 실패하면 null
 */
async function parseQuickFilterBody(request: Request): Promise<{ name: string; query: string } | null> {
  try {
    const body = (await request.json()) as { name?: string; query?: string }
    return { name: body.name ?? '', query: body.query ?? '' }
  } catch {
    return null
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards?projectKey=
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/boards?projectKey={projectKey} — 프로젝트 보드 목록 조회.
 *
 * store의 projectBoardIndex에서 해당 projectKey 보드 ID 목록을 읽어
 * BoardSummary 배열로 반환한다. 없으면 빈 배열 반환.
 * 성공 → 200 { data: BoardSummary[] }
 */
const getBoardsHandler = http.get('/api/v1/boards', ({ request }) => {
  const url = new URL(request.url)
  const projectKey = url.searchParams.get('projectKey') ?? ''

  const boardIds = projectBoardIndex.get(projectKey) ?? []
  const summaries = boardIds
    .map((boardId) => boardStore.get(boardId))
    .filter((b): b is NonNullable<typeof b> => b !== undefined)
    // boardType 은 상세와 **다른 조립부**다 — 상세만 고치면 보드 스위처가 목록 파싱에서 죽는다
    // (boardSummarySchema.boardType 필수 · FR-BD-04).
    .map(({ boardId, projectKey: pk, name, boardType }) => ({
      boardId,
      projectKey: pk,
      name,
      boardType: boardType ?? DEFAULT_BOARD_TYPE,
    }))

  return HttpResponse.json({ data: summaries })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/boards/{boardId} — 보드 상세 조회.
 *
 * query param 필터 (FR-BD-02):
 *   ?assignee=<id>       → 해당 담당자 카드만 (복수 OR)
 *   ?assignee=unassigned → 미배정(null) 카드만
 *   ?label=<name>        → 해당 라벨 카드만 (복수 OR)
 *   ?component=<id>      → 해당 컴포넌트 카드만 (복수 OR)
 *   필드 간 AND 적용.
 *
 * 성공 → 200 { data: BoardDetail } — 응답 카드는 BoardCard DTO (labels/componentIds 제외)
 * 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 */
const getBoardHandler = http.get('/api/v1/boards/:id', ({ params, request }) => {
  const boardId = params['id'] as string
  const board = boardStore.get(boardId)

  if (board === undefined) {
    return HttpResponse.json(
      {
        errorCode: 'AGILE_BOARD_NOT_FOUND',
        message: `보드를 찾을 수 없습니다: ${boardId}`,
      },
      { status: 404 },
    )
  }

  const searchParams = new URL(request.url).searchParams
  return HttpResponse.json({ data: toResponseDetail(board, searchParams) })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/boards
// ─────────────────────────────────────────────────────────────────────────────

/**
 * boardType을 생략한 요청에 적용하는 기본 보드 종류 (FR-BD-04).
 *
 * 백엔드 `BoardCreateRequest.boardType`이 선택 인자라 같은 기본값을 둔다
 * (`BoardResponses.kt:46` — null이면 `BoardType.from`이 KANBAN을 돌려준다).
 * 다른 값을 두면 종류를 생략한 요청이 mock 위에서만 다른 보드를 만든다.
 */
const DEFAULT_BOARD_TYPE: BoardType = 'KANBAN'

/**
 * POST /api/v1/boards — 새 보드 생성.
 *
 * 요청 body: { projectKey: string, name: string, boardType?: 'SCRUM' | 'KANBAN' }
 * 초기 컬럼 3개(TODO/IN_PROGRESS/DONE)를 자동 생성해 store에 추가한다.
 * boardType은 store에 저장되어 이후 GET 상세 응답에도 실린다 (FR-BD-04 D6).
 * 성공 → 201 { data: BoardCreated }
 */
const createBoardHandler = http.post('/api/v1/boards', async ({ request }) => {
  let projectKey = ''
  let name = ''
  let boardType: BoardType = DEFAULT_BOARD_TYPE

  try {
    const body = (await request.json()) as { projectKey?: string; name?: string; boardType?: BoardType }
    projectKey = body.projectKey ?? ''
    name = body.name ?? ''
    boardType = body.boardType ?? DEFAULT_BOARD_TYPE
  } catch {
    return HttpResponse.json(
      { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
      { status: 400 },
    )
  }

  const { created } = createBoardInStore(projectKey, name, boardType)

  return HttpResponse.json({ data: created }, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/boards/:id/cards/:issueKey/move
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/boards/{boardId}/cards/{issueKey}/move — 카드 이동.
 *
 * 요청 body: { toColumnId: string, expectedVersion: number, resolutionId?: string }
 *
 * 409 충돌 토글 — E2E 시나리오용:
 *   localStorage 플래그 LS_KEY_BOARD_CONFLICT='true'이면 409(AGILE_CONFLICT) 반환.
 *   특정 issueKey 패턴 없이 전역으로 적용.
 *
 * stateful 동작:
 *   - store에서 카드를 현재 컬럼에서 제거하고 toColumnId 컬럼에 추가
 *   - 카드 version을 +1 증가
 *   - 이후 GET 상세에 즉시 반영 (가짜그린 회피 — msw-mutation-stateful-refetch)
 *
 * 성공 → 200 { data: MoveCardResult }
 * 충돌 토글 시 → 409 ProblemDetail { errorCode: 'AGILE_CONFLICT' }
 * 보드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 * 카드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_CARD_NOT_FOUND' }
 * 컬럼 미존재 → 404 ProblemDetail { errorCode: 'AGILE_COLUMN_NOT_FOUND' }
 */
const moveCardHandler = http.post(
  '/api/v1/boards/:id/cards/:issueKey/move',
  async ({ params, request }) => {
    const boardId = params['id'] as string
    const issueKey = params['issueKey'] as string

    // 409 충돌 토글 — E2E 시나리오 (e2e-msw-scenario-toggle-localstorage-flag)
    const conflictFlag = globalThis.localStorage?.getItem(LS_KEY_BOARD_CONFLICT) ?? ''
    if (conflictFlag === 'true') {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_CONFLICT',
          message: '낙관적 잠금 충돌 — 다른 사용자가 카드를 변경했습니다',
        },
        { status: 409 },
      )
    }

    // 보드 존재 확인
    const board = boardStore.get(boardId)
    if (board === undefined) {
      return HttpResponse.json(
        { errorCode: 'AGILE_BOARD_NOT_FOUND', message: `보드를 찾을 수 없습니다: ${boardId}` },
        { status: 404 },
      )
    }

    // 요청 body 파싱
    let toColumnId = ''
    let expectedVersion = -1
    let resolutionId: string | undefined

    try {
      const body = (await request.json()) as {
        toColumnId?: string
        expectedVersion?: number
        resolutionId?: string
      }
      toColumnId = body.toColumnId ?? ''
      expectedVersion = body.expectedVersion ?? -1
      resolutionId = body.resolutionId
    } catch {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }

    // 카드를 현재 컬럼에서 찾기
    let sourceColumnId: string | undefined
    let cardIndex = -1

    for (const column of board.columns) {
      const idx = column.cards.findIndex((c) => c.issueKey === issueKey)
      if (idx !== -1) {
        sourceColumnId = column.columnId
        cardIndex = idx
        break
      }
    }

    if (sourceColumnId === undefined || cardIndex === -1) {
      return HttpResponse.json(
        { errorCode: 'AGILE_CARD_NOT_FOUND', message: `카드를 찾을 수 없습니다: ${issueKey}` },
        { status: 404 },
      )
    }

    // 대상 컬럼 존재 확인
    const targetColumn = board.columns.find((c) => c.columnId === toColumnId)
    if (targetColumn === undefined) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_COLUMN_NOT_FOUND',
          message: `컬럼을 찾을 수 없습니다: ${toColumnId}`,
        },
        { status: 404 },
      )
    }

    // 소스 컬럼에서 카드 제거 + version 증가
    const sourceColumn = board.columns.find((c) => c.columnId === sourceColumnId)
    if (sourceColumn === undefined) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_COLUMN_NOT_FOUND',
          message: `소스 컬럼을 찾을 수 없습니다: ${sourceColumnId}`,
        },
        { status: 404 },
      )
    }

    const [removedCard] = sourceColumn.cards.splice(cardIndex, 1)
    if (removedCard === undefined) {
      return HttpResponse.json(
        { errorCode: 'AGILE_CARD_NOT_FOUND', message: `카드를 찾을 수 없습니다: ${issueKey}` },
        { status: 404 },
      )
    }

    // 새 버전으로 업데이트해 대상 컬럼에 추가
    const newVersion = expectedVersion + 1
    const updatedCard = { ...removedCard, version: newVersion }
    targetColumn.cards.push(updatedCard)

    // store 갱신 — 이후 GET 상세에 반영 (msw-mutation-stateful-refetch)
    boardStore.set(boardId, board)

    // resolutionId 사용 여부는 현재 mock에서 무시 (응답 구조에 영향 없음)
    void resolutionId

    return HttpResponse.json({
      data: {
        issueKey,
        // 서버는 요청이 지목한 상태로 전환한다(R6). mock 은 컬럼의 첫 상태로 근사한다(E5).
        currentStateKey: targetColumn.states[0]?.key ?? '',
        version: newVersion,
        columnId: toColumnId,
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/boards/:id — 이름 · 스윔레인 기준 부분 갱신 (FR-BD-03 D6, FR-BD-01-2a)
// ─────────────────────────────────────────────────────────────────────────────

/** 허용된 SwimlaneField 값 목록 — 타입가드용 */
const VALID_SWIMLANE_FIELDS: ReadonlyArray<SwimlaneField> = ['NONE', 'ASSIGNEE', 'PRIORITY', 'EPIC']

/**
 * PATCH 요청 body — 백엔드 `UpdateBoardRequest`(JsonNullable 3-state) 미러.
 * 두 필드 모두 optional 이고, 값 검증은 핸들러가 직접 한다(명시 null도 400이라 unknown으로 받는다).
 */
interface UpdateBoardBody {
  name?: unknown
  swimlaneField?: unknown
}

/**
 * PATCH /api/v1/boards/{boardId} — 보드 이름·스윔레인 기준 부분 갱신.
 *
 * 요청 body(3-state): 보내지 않은 필드는 건드리지 않는다.
 *   { name: string }                         → 이름만 변경
 *   { swimlaneField: 'NONE' | ... }          → 스윔레인 기준만 변경
 *   { name, swimlaneField }                  → 둘 다 변경
 *
 * stateful 동작 (msw-mutation-stateful-refetch 교훈).
 *   - boardStore의 해당 보드를 변이한다.
 *   - 이후 GET 상세/목록에 변경이 즉시 반영됨을 보장한다.
 *
 * 성공 → 200 { data: BoardMeta } (boardId, projectKey, name, swimlaneField, boardType)
 * 보드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 * 빈 바디 · 명시 null · 공백 이름 → 400 ProblemDetail { errorCode: 'AGILE_VALIDATION_FAILED' }
 * 잘못된 swimlaneField → 400 ProblemDetail { errorCode: 'INVALID_SWIMLANE_FIELD' }
 */
const updateBoardHandler = http.patch(
  '/api/v1/boards/:id',
  async ({ params, request }) => {
    const boardId = params['id'] as string

    // 보드 존재 확인
    const board = boardStore.get(boardId)
    if (board === undefined) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_BOARD_NOT_FOUND',
          message: `보드를 찾을 수 없습니다: ${boardId}`,
        },
        { status: 404 },
      )
    }

    // 요청 body 파싱
    let body: UpdateBoardBody
    try {
      body = (await request.json()) as UpdateBoardBody
    } catch {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }

    // 3-state 판정 — 키 존재 여부가 「전송함」이다. 값이 null이어도 전송한 것으로 친다.
    const nameSent = Object.hasOwn(body, 'name')
    const swimlaneSent = Object.hasOwn(body, 'swimlaneField')

    // 최소 1필드 규칙 — 아무것도 바꾸지 않는 요청이 조용히 200을 받지 않게 한다.
    if (!nameSent && !swimlaneSent) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_VALIDATION_FAILED',
          message: 'name 또는 swimlaneField 중 하나는 전송해야 합니다.',
        },
        { status: 400 },
      )
    }

    if (nameSent) {
      const rawName = body.name
      // 명시 null · 공백 이름 모두 400 — 보드 이름은 「해제」 의미가 없다.
      if (typeof rawName !== 'string' || rawName.trim() === '') {
        return HttpResponse.json(
          { errorCode: 'AGILE_VALIDATION_FAILED', message: '보드 이름은 비어 있을 수 없습니다.' },
          { status: 400 },
        )
      }
      board.name = rawName
    }

    if (swimlaneSent) {
      const rawField = body.swimlaneField
      if (typeof rawField !== 'string' || !(VALID_SWIMLANE_FIELDS as string[]).includes(rawField)) {
        return HttpResponse.json(
          {
            errorCode: 'INVALID_SWIMLANE_FIELD',
            message: `swimlaneField는 NONE, ASSIGNEE, PRIORITY, EPIC 중 하나여야 합니다`,
          },
          { status: 400 },
        )
      }
      board.swimlaneField = rawField as SwimlaneField
    }

    // store 변이 — 이후 GET 상세에서 새 값이 반영됨 (msw-mutation-stateful-refetch)
    boardStore.set(boardId, board)

    // BoardMeta 응답 반환 (boards.ts updateBoardSwimlane / updateBoardName 공통 계약)
    return HttpResponse.json({
      data: {
        boardId: board.boardId,
        projectKey: board.projectKey,
        name: board.name,
        swimlaneField: board.swimlaneField,
        // FR-BD-04 PR ⑤ — 종류를 노출하는 응답들이 같은 계약을 쓴다.
        // ★`?? DEFAULT_BOARD_TYPE` 이 필수다. StoredBoardDetail.boardType 은 optional 이라
        //   시드가 빼먹어도 컴파일러가 못 잡고, JSON.stringify 가 undefined 키를 지운다 —
        //   그러면 필수인 boardMetaSchema.boardType 이 ZodError 로 이름 변경을 하드 실패시킨다.
        //   같은 파일의 다른 소비 지점 5곳(:174 :310 :387 :452 :458)이 전부 이 폴백을 쓴다.
        boardType: board.boardType ?? DEFAULT_BOARD_TYPE,
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/boards/:id — 보드 소프트 삭제 (FR-BD-01-2b)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/boards/{boardId} — 보드 소프트 삭제. 보드의 이슈는 남는다.
 *
 * stateful 동작 — boardStore와 projectBoardIndex에서 함께 제거해 이후 목록·상세 조회에서
 * 즉시 사라지게 한다 (msw-mutation-stateful-refetch).
 *
 * 성공 → 204 No Content (본문 없음)
 * 미존재·이미 삭제됨 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 */
const deleteBoardHandler = http.delete('/api/v1/boards/:id', ({ params }) => {
  const boardId = params['id'] as string

  if (!deleteBoardFromStore(boardId)) {
    return HttpResponse.json(
      {
        errorCode: 'AGILE_BOARD_NOT_FOUND',
        message: `보드를 찾을 수 없습니다: ${boardId}`,
      },
      { status: 404 },
    )
  }

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/boards/:id/quick-filters — 퀵필터 생성 (FR-UX-01)
// ─────────────────────────────────────────────────────────────────────────────

/** 보드당 퀵필터 최대 개수 상한 (spec EC3 — backend 동일 상수와 정합) */
const MAX_QUICK_FILTER_COUNT = 20

/**
 * POST /api/v1/boards/{boardId}/quick-filters — 퀵필터 생성.
 *
 * 요청 body: { name: string, query: string }
 *
 * stateful 동작.
 *   - query는 normalizeQuickFilterQuery로 정규화(정렬/trim/중복 제거)해 저장한다.
 *   - filterId는 generateUUID로 신규 발급(store.quickFilters에 append).
 *   - 이후 GET 상세의 quickFilters에 즉시 반영된다 (msw-mutation-stateful-refetch).
 *
 * 성공 → 201 { data: QuickFilter }
 * EC1(정규화 후 빈 query) → 400 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_EMPTY_QUERY' }
 * EC2(같은 보드 내 name 중복) → 409 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_NAME_CONFLICT' }
 * EC3(보드당 20건 상한 초과) → 409 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_LIMIT_EXCEEDED' }
 * 보드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 */
const createQuickFilterHandler = http.post(
  '/api/v1/boards/:id/quick-filters',
  async ({ params, request }) => {
    const boardId = params['id'] as string
    const board = boardStore.get(boardId)
    if (board === undefined) {
      return HttpResponse.json(
        { errorCode: 'AGILE_BOARD_NOT_FOUND', message: `보드를 찾을 수 없습니다: ${boardId}` },
        { status: 404 },
      )
    }

    const parsed = await parseQuickFilterBody(request)
    if (parsed === null) {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }
    const { name, query } = parsed

    const normalizedQuery = normalizeQuickFilterQuery(query)
    if (normalizedQuery === '') {
      return HttpResponse.json(
        { errorCode: 'AGILE_QUICK_FILTER_EMPTY_QUERY', message: '빈 필터는 저장할 수 없습니다' },
        { status: 400 },
      )
    }

    const existingFilters = board.quickFilters ?? []
    if (existingFilters.some((f) => f.name === name)) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_QUICK_FILTER_NAME_CONFLICT',
          message: `같은 이름의 퀵필터가 이미 있습니다: ${name}`,
        },
        { status: 409 },
      )
    }

    if (existingFilters.length >= MAX_QUICK_FILTER_COUNT) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_QUICK_FILTER_LIMIT_EXCEEDED',
          message: `보드당 퀵필터는 최대 ${MAX_QUICK_FILTER_COUNT}건까지 저장할 수 있습니다`,
        },
        { status: 409 },
      )
    }

    const created: QuickFilter = { filterId: generateUUID(), name, query: normalizedQuery }
    board.quickFilters = [...existingFilters, created]
    boardStore.set(boardId, board)

    return HttpResponse.json({ data: created }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/boards/:id/quick-filters/:filterId — 퀵필터 수정 (FR-UX-01)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PATCH /api/v1/boards/{boardId}/quick-filters/{filterId} — 퀵필터 수정(name, query 전체 치환).
 *
 * 요청 body: { name: string, query: string }
 *
 * stateful 동작 — board.quickFilters의 해당 항목을 갱신한다(filterId는 불변).
 *
 * 성공 → 200 { data: QuickFilter }
 * EC1(정규화 후 빈 query) → 400 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_EMPTY_QUERY' }
 * EC2(같은 보드 내 다른 항목과 name 중복) → 409 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_NAME_CONFLICT' }
 * EC5(타 보드 소속 또는 미존재 filterId) → 404 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_NOT_FOUND' }
 * 보드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 */
const updateQuickFilterHandler = http.patch(
  '/api/v1/boards/:id/quick-filters/:filterId',
  async ({ params, request }) => {
    const boardId = params['id'] as string
    const filterId = params['filterId'] as string
    const board = boardStore.get(boardId)
    if (board === undefined) {
      return HttpResponse.json(
        { errorCode: 'AGILE_BOARD_NOT_FOUND', message: `보드를 찾을 수 없습니다: ${boardId}` },
        { status: 404 },
      )
    }

    const existingFilters = board.quickFilters ?? []
    const targetIndex = existingFilters.findIndex((f) => f.filterId === filterId)
    if (targetIndex === -1) {
      return HttpResponse.json(
        { errorCode: 'AGILE_QUICK_FILTER_NOT_FOUND', message: `퀵필터를 찾을 수 없습니다: ${filterId}` },
        { status: 404 },
      )
    }

    const parsed = await parseQuickFilterBody(request)
    if (parsed === null) {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }
    const { name, query } = parsed

    const normalizedQuery = normalizeQuickFilterQuery(query)
    if (normalizedQuery === '') {
      return HttpResponse.json(
        { errorCode: 'AGILE_QUICK_FILTER_EMPTY_QUERY', message: '빈 필터는 저장할 수 없습니다' },
        { status: 400 },
      )
    }

    const nameConflict = existingFilters.some((f, idx) => idx !== targetIndex && f.name === name)
    if (nameConflict) {
      return HttpResponse.json(
        {
          errorCode: 'AGILE_QUICK_FILTER_NAME_CONFLICT',
          message: `같은 이름의 퀵필터가 이미 있습니다: ${name}`,
        },
        { status: 409 },
      )
    }

    const updated: QuickFilter = { filterId, name, query: normalizedQuery }
    const nextFilters = [...existingFilters]
    nextFilters[targetIndex] = updated
    board.quickFilters = nextFilters
    boardStore.set(boardId, board)

    return HttpResponse.json({ data: updated })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/boards/:id/quick-filters/:filterId — 퀵필터 삭제 (FR-UX-01)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/boards/{boardId}/quick-filters/{filterId} — 퀵필터 삭제.
 *
 * stateful 동작 — board.quickFilters에서 해당 항목을 제거한다.
 *
 * 성공 → 204 No Content
 * EC5(타 보드 소속 또는 미존재 filterId) → 404 ProblemDetail { errorCode: 'AGILE_QUICK_FILTER_NOT_FOUND' }
 * 보드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 */
const deleteQuickFilterHandler = http.delete(
  '/api/v1/boards/:id/quick-filters/:filterId',
  ({ params }) => {
    const boardId = params['id'] as string
    const filterId = params['filterId'] as string
    const board = boardStore.get(boardId)
    if (board === undefined) {
      return HttpResponse.json(
        { errorCode: 'AGILE_BOARD_NOT_FOUND', message: `보드를 찾을 수 없습니다: ${boardId}` },
        { status: 404 },
      )
    }

    const existingFilters = board.quickFilters ?? []
    const targetIndex = existingFilters.findIndex((f) => f.filterId === filterId)
    if (targetIndex === -1) {
      return HttpResponse.json(
        { errorCode: 'AGILE_QUICK_FILTER_NOT_FOUND', message: `퀵필터를 찾을 수 없습니다: ${filterId}` },
        { status: 404 },
      )
    }

    board.quickFilters = existingFilters.filter((_, idx) => idx !== targetIndex)
    boardStore.set(boardId, board)

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-01 Task 10 E2E 시나리오 전용 시드 — 권한 게이팅(FR5) 검증용 보드
//
// board-fixtures.ts는 이 작업(qa-engineer, Task 10)의 파일 범위(files: [quick-filter.spec.ts,
// board-handlers.ts])에 포함되지 않아 신규 보드 데이터를 이 파일에 직접 정의한다.
// FILTER_BOARD를 재사용하지 않는 이유 — FILTER_BOARD에 퀵필터를 영구 시드하면
// board-filter.spec.ts(FR-BD-02, S1~S9)와 quick-filter.spec.ts 자체 happy-path 테스트
// (빈 칩 목록에서 출발해야 함)에 교차 오염된다. 전용 보드로 데이터 격리한다.
//
// CREATE:false(E2E_FORCE_CREATE_FALSE_KEY) 사용자는 "필터 저장" 버튼이 canManage
// 게이팅으로 숨겨져 UI로 퀵필터를 생성할 수 없다. 따라서 퀵필터 1건을 모듈 로드 시
// 미리 시드해 "칩은 보이지만 저장/편집/삭제 버튼은 없음"을 검증할 수 있게 한다.
// ─────────────────────────────────────────────────────────────────────────────

/** quick-filter.spec.ts S7 권한 게이팅 시나리오 전용 보드 UUID */
const QUICK_FILTER_PERM_BOARD_ID = '10000000-0000-4000-8000-000000000006'
/** quick-filter.spec.ts S7 권한 게이팅 시나리오 전용 프로젝트 키 */
const QUICK_FILTER_PERM_PROJECT_KEY = 'QFPERM'

/**
 * S7 시나리오 전용 보드 — 퀵필터 1건("버그만", query=`label=bug`) 사전 시드.
 * QFPERM-1(bug 라벨)만 통과, QFPERM-2(feature 라벨)는 제외되어 칩 클릭 시
 * 2개→1개로 감소하는 실제 필터링을 검증할 수 있다.
 *
 * export 인 이유 (FR-BD-04 PR ③). 이 시드는 **픽스처 파일 밖**에 홀로 있어
 * `board-fixtures.ts` 만 훑는 점검에서 매번 빠진다. `board-handlers.test.ts` 의
 * 「시드 전량」 표에 실어 그 누락을 유닛이 잡게 한다 — E2E(quick-filter S7)만 죽는 것을 막는다.
 */
export const QUICK_FILTER_PERM_SEED: StoredBoardDetail = {
  boardId: QUICK_FILTER_PERM_BOARD_ID,
  projectKey: QUICK_FILTER_PERM_PROJECT_KEY,
  name: '퀵필터 권한 게이팅 테스트 보드',
  swimlaneField: 'NONE',
  // 칸반 동작 전제 시드 — 종류를 바꾸면 quick-filter.spec.ts S7 이 활성 스프린트 분기를 탄다.
  boardType: 'KANBAN',
  columns: [
    {
      columnId: '80000000-0000-4000-8000-000000000001',
      states: [{ key: 'open', name: 'TODO', category: 'TODO' }],
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'QFPERM-1',
          summary: '퀵필터 권한 테스트 이슈 1 — bug 라벨',
          assigneeId: null,
          version: 0,
          priority: 1,
          epicKey: null,
          rank: null,
          typeKey: 'bug',
          labels: ['bug'],
          originalEstimateSeconds: null,
          componentIds: [],
        },
        {
          issueKey: 'QFPERM-2',
          summary: '퀵필터 권한 테스트 이슈 2 — feature 라벨',
          assigneeId: null,
          version: 0,
          priority: 2,
          epicKey: null,
          rank: null,
          typeKey: 'task',
          labels: ['feature'],
          originalEstimateSeconds: null,
          componentIds: [],
        },
      ],
    },
  ],
  truncated: false,
  unplacedCount: 0,
  quickFilters: [
    { filterId: '90000000-0000-4000-8000-000000000001', name: '버그만', query: 'label=bug' },
  ],
}

// 모듈 로드 시 자동 시드 — board-fixtures.ts DEFAULT_BOARD 등과 동일한 가드 패턴.
// Vitest 단위 테스트(MODE='test')에서는 건너뜀 — board-handlers.test.ts가 resetBoardStore로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedBoardWithMeta(QUICK_FILTER_PERM_SEED)
}

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 BC MSW 핸들러 배열.
 *
 * handlers.ts에서 boardHandlers를 spread해 등록한다.
 * GET /api/v1/boards?projectKey= 와 GET /api/v1/boards/:id 모두 포함.
 * PATCH /api/v1/boards/:id (이름·스윔레인 기준 부분 갱신) 포함.
 * DELETE /api/v1/boards/:id (보드 소프트 삭제, FR-BD-01-2b) 포함.
 * POST/PATCH/DELETE /api/v1/boards/:id/quick-filters[/:filterId] (퀵필터 CRUD, FR-UX-01) 포함.
 */
export const boardHandlers = [
  getBoardsHandler,
  getBoardHandler,
  createBoardHandler,
  moveCardHandler,
  updateBoardHandler,
  deleteBoardHandler,
  createQuickFilterHandler,
  updateQuickFilterHandler,
  deleteQuickFilterHandler,
]
