// 칸반 보드 BC MSW 핸들러 — stateful CRUD + 카드 이동 + 409 충돌 토글 (FR-BD-01 D6)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: move 후 GET 상세에 즉시 반영되도록 boardStore 변이
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//
import { http, HttpResponse } from 'msw'
import {
  boardStore,
  projectBoardIndex,
  createBoardInStore,
  LS_KEY_BOARD_CONFLICT,
} from './board-fixtures'

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
 * store에서 boardId로 BoardDetail을 찾아 반환한다.
 * 성공 → 200 { data: BoardDetail }
 * 미존재 → 404 ProblemDetail { errorCode: 'AGILE_BOARD_NOT_FOUND' }
 */
const getBoardHandler = http.get('/api/v1/boards/:id', ({ params }) => {
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

  return HttpResponse.json({ data: board })
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
