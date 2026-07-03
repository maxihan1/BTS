// 칸반 보드 BC MSW 핸들러 — stateful CRUD + 카드 이동 + 409 충돌 토글 (FR-BD-01 D6, FR-BD-02 D6, FR-UX-01)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: move 후 GET 상세에 즉시 반영되도록 boardStore 변이
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//
import { http, HttpResponse } from 'msw'
import type { BoardDetail, BoardCard, BoardCardFilterParams, SwimlaneField } from '@/api/boards'
import { buildBoardFilterQuery } from '@/api/boards'
import type { QuickFilter } from '@/api/board-quick-filters'
import { queryStringToSearch, searchToFilter } from '@/lib/board-filter'
import type { StoredBoardDetail, StoredCard } from './board-fixtures'
import {
  boardStore,
  projectBoardIndex,
  createBoardInStore,
  generateUUID,
  LS_KEY_BOARD_CONFLICT,
  seedBoardWithMeta,
} from './board-fixtures'

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
 * StoredBoardDetail을 BoardDetail 응답 형식으로 변환한다.
 *
 * labels/componentIds는 store 내부 필터용 메타이며 응답 DTO(BoardCard)에 포함하지 않는다.
 * params가 주어지면 matchesFilter를 적용해 카드를 걸러낸다.
 * quickFilters는 store에 없으면(WIP_BOARD 등 퀵필터를 다루지 않는 기존 fixture) 빈 배열로 방어한다.
 *
 * @param stored store 내부 보드 데이터
 * @param params 필터 파라미터 (없으면 전체 카드 반환)
 */
function toResponseDetail(stored: StoredBoardDetail, params: URLSearchParams): BoardDetail {
  return {
    ...stored,
    columns: stored.columns.map((col) => ({
      ...col,
      cards: col.cards
        .filter((card) => matchesFilter(card, params))
        .map(({ issueKey, summary, assigneeId, version, priority, epicKey }): BoardCard => ({
          issueKey,
          summary,
          assigneeId,
          version,
          priority,
          epicKey: epicKey ?? null,
        })),
    })),
    quickFilters: stored.quickFilters ?? [],
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
    .map(({ boardId, projectKey: pk, name }) => ({ boardId, projectKey: pk, name }))

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
 * POST /api/v1/boards — 새 보드 생성.
 *
 * 요청 body: { projectKey: string, name: string }
 * 초기 컬럼 3개(TODO/IN_PROGRESS/DONE)를 자동 생성해 store에 추가한다.
 * 성공 → 201 { data: BoardCreated }
 */
const createBoardHandler = http.post('/api/v1/boards', async ({ request }) => {
  let projectKey = ''
  let name = ''

  try {
    const body = (await request.json()) as { projectKey?: string; name?: string }
    projectKey = body.projectKey ?? ''
    name = body.name ?? ''
  } catch {
    return HttpResponse.json(
      { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
      { status: 400 },
    )
  }

  const { created } = createBoardInStore(projectKey, name)

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
        currentStateKey: targetColumn.stateKey,
        version: newVersion,
        columnId: toColumnId,
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/boards/:id — 스윔레인 기준 변경 (FR-BD-03 D6)
// ─────────────────────────────────────────────────────────────────────────────

/** 허용된 SwimlaneField 값 목록 — 타입가드용 */
const VALID_SWIMLANE_FIELDS: ReadonlyArray<SwimlaneField> = ['NONE', 'ASSIGNEE', 'PRIORITY', 'EPIC']

/**
 * PATCH /api/v1/boards/{boardId} — 보드 스윔레인 기준 변경.
 *
 * 요청 body: { swimlaneField: 'NONE' | 'ASSIGNEE' | 'PRIORITY' }
 *
 * stateful 동작 (msw-mutation-stateful-refetch 교훈).
 *   - boardStore의 해당 보드 swimlaneField를 변이한다.
 *   - 이후 GET 상세에서 변경된 swimlaneField가 반영됨을 보장한다.
 *
 * 성공 → 200 { data: BoardMeta } (boardId, projectKey, name, swimlaneField)
 * 보드 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 * 잘못된 swimlaneField → 400 ProblemDetail { errorCode: 'INVALID_SWIMLANE_FIELD' }
 */
const updateSwimlaneHandler = http.patch(
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
    let swimlaneField: SwimlaneField | undefined

    try {
      const body = (await request.json()) as { swimlaneField?: unknown }
      const raw = body.swimlaneField
      if (typeof raw === 'string' && (VALID_SWIMLANE_FIELDS as string[]).includes(raw)) {
        swimlaneField = raw as SwimlaneField
      }
    } catch {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }

    if (swimlaneField === undefined) {
      return HttpResponse.json(
        {
          errorCode: 'INVALID_SWIMLANE_FIELD',
          message: `swimlaneField는 NONE, ASSIGNEE, PRIORITY, EPIC 중 하나여야 합니다`,
        },
        { status: 400 },
      )
    }

    // store 변이 — 이후 GET 상세에서 새 swimlaneField가 반영됨 (msw-mutation-stateful-refetch)
    board.swimlaneField = swimlaneField
    boardStore.set(boardId, board)

    // BoardMeta 응답 반환 (boards.ts updateBoardSwimlane 계약)
    return HttpResponse.json({
      data: {
        boardId: board.boardId,
        projectKey: board.projectKey,
        name: board.name,
        swimlaneField: board.swimlaneField,
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/boards/:id/quick-filters — 퀵필터 생성 (FR-UX-01)
// ─────────────────────────────────────────────────────────────────────────────

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
 */
const QUICK_FILTER_PERM_SEED: StoredBoardDetail = {
  boardId: QUICK_FILTER_PERM_BOARD_ID,
  projectKey: QUICK_FILTER_PERM_PROJECT_KEY,
  name: '퀵필터 권한 게이팅 테스트 보드',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: '80000000-0000-4000-8000-000000000001',
      stateKey: 'open',
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
          labels: ['bug'],
          componentIds: [],
        },
        {
          issueKey: 'QFPERM-2',
          summary: '퀵필터 권한 테스트 이슈 2 — feature 라벨',
          assigneeId: null,
          version: 0,
          priority: 2,
          epicKey: null,
          labels: ['feature'],
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
 * PATCH /api/v1/boards/:id (스윔레인 기준 변경) 포함.
 * POST/PATCH/DELETE /api/v1/boards/:id/quick-filters[/:filterId] (퀵필터 CRUD, FR-UX-01) 포함.
 */
export const boardHandlers = [
  getBoardsHandler,
  getBoardHandler,
  createBoardHandler,
  moveCardHandler,
  updateSwimlaneHandler,
  createQuickFilterHandler,
  updateQuickFilterHandler,
  deleteQuickFilterHandler,
]
