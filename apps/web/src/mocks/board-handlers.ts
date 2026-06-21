// 칸반 보드 BC MSW 핸들러 — stateful CRUD + 카드 이동 + 409 충돌 토글 (FR-BD-01 D6, FR-BD-02 D6)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: move 후 GET 상세에 즉시 반영되도록 boardStore 변이
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//
import { http, HttpResponse } from 'msw'
import type { BoardDetail, BoardCard } from '@/api/boards'
import type { StoredBoardDetail, StoredCard } from './board-fixtures'
import {
  boardStore,
  projectBoardIndex,
  createBoardInStore,
  LS_KEY_BOARD_CONFLICT,
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
        .map(({ issueKey, summary, assigneeId, version }): BoardCard => ({
          issueKey,
          summary,
          assigneeId,
          version,
        })),
    })),
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
// export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 BC MSW 핸들러 배열.
 *
 * handlers.ts에서 boardHandlers를 spread해 등록한다.
 * GET /api/v1/boards?projectKey= 와 GET /api/v1/boards/:id 모두 포함.
 */
export const boardHandlers = [
  getBoardsHandler,
  getBoardHandler,
  createBoardHandler,
  moveCardHandler,
]
