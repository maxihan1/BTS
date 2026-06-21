// 칸반 보드 MSW 핸들러 stateful 동작 검증 테스트 (FR-BD-01 D6, FR-BD-02 D6)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { boardHandlers } from './board-handlers'
import {
  resetBoardStore,
  seedBoard,
  LS_KEY_BOARD_CONFLICT,
  DEFAULT_BOARD,
  FILTER_BOARD,
} from './board-fixtures'

const server = setupServer(...boardHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetBoardStore()
  localStorage.removeItem(LS_KEY_BOARD_CONFLICT)
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 응답 타입 — 테스트 내부 편의용 (Zod 스키마 z.infer와 동형)
// ─────────────────────────────────────────────────────────────────────────────

interface BoardSummary {
  boardId: string
  projectKey: string
  name: string
}

interface BoardCard {
  issueKey: string
  summary: string
  assigneeId: string | null
  version: number
}

interface BoardColumn {
  columnId: string
  stateKey: string
  name: string
  category: 'TODO' | 'IN_PROGRESS' | 'DONE'
  displayOrder: number
  cards: BoardCard[]
}

interface BoardDetail {
  boardId: string
  projectKey: string
  name: string
  columns: BoardColumn[]
  truncated: boolean
  unplacedCount: number
}

interface BoardCreated {
  boardId: string
  projectKey: string
  name: string
  columns: Array<Omit<BoardColumn, 'cards'>>
}

interface MoveCardResult {
  issueKey: string
  currentStateKey: string
  version: number
  columnId: string
}

interface DataResponse<T> {
  data: T
}

interface ProblemDetail {
  errorCode: string
  message: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 fetch 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getBoards(projectKey: string): Promise<Response> {
  return fetch(`/api/v1/boards?projectKey=${encodeURIComponent(projectKey)}`)
}

async function getBoard(boardId: string): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}`)
}

async function postBoard(body: { projectKey: string; name: string }): Promise<Response> {
  return fetch('/api/v1/boards', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function moveCard(
  boardId: string,
  issueKey: string,
  body: { toColumnId: string; expectedVersion: number },
): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}/cards/${encodeURIComponent(issueKey)}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards?projectKey=
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/boards?projectKey=', () => {
  it('빈 store → 200 { data: [] }', async () => {
    const res = await getBoards('ATLAS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardSummary[]>
    expect(body.data).toEqual([])
  })

  it('seedBoard 후 → GET 목록에 반영 (stateful)', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await getBoards('ATLAS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardSummary[]>
    expect(body.data).toHaveLength(1)
    expect(body.data[0]?.boardId).toBe(DEFAULT_BOARD.boardId)
    expect(body.data[0]?.projectKey).toBe('ATLAS')
  })

  it('다른 projectKey → 보드 없음', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await getBoards('OTHER')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardSummary[]>
    expect(body.data).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards/:id
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/boards/:id', () => {
  it('없는 보드 → 404 errorCode AGILE_BOARD_NOT_FOUND', async () => {
    const res = await getBoard('00000000-0000-4000-8000-000000000099')
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_BOARD_NOT_FOUND')
  })

  it('seedBoard 후 → 200 { data: BoardDetail }', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await getBoard(DEFAULT_BOARD.boardId)
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    expect(body.data.boardId).toBe(DEFAULT_BOARD.boardId)
    expect(body.data.columns).toHaveLength(3)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/boards
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/boards', () => {
  it('생성 → 201 { data: BoardCreated } — 3개 컬럼 포함', async () => {
    const res = await postBoard({ projectKey: 'ATLAS', name: '새 보드' })
    expect(res.status).toBe(201)
    const body = (await res.json()) as DataResponse<BoardCreated>
    expect(body.data.projectKey).toBe('ATLAS')
    expect(body.data.name).toBe('새 보드')
    expect(body.data.columns).toHaveLength(3)
    const categories = body.data.columns.map((c) => c.category)
    expect(categories).toContain('TODO')
    expect(categories).toContain('IN_PROGRESS')
    expect(categories).toContain('DONE')
  })

  it('생성 후 → GET 목록에 반영 (stateful)', async () => {
    await postBoard({ projectKey: 'ATLAS', name: '새 보드' })
    const res = await getBoards('ATLAS')
    const body = (await res.json()) as DataResponse<BoardSummary[]>
    expect(body.data).toHaveLength(1)
    expect(body.data[0]?.name).toBe('새 보드')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/boards/:id/cards/:issueKey/move
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/boards/:id/cards/:issueKey/move', () => {
  it('카드 이동 → 200 { data: MoveCardResult }', async () => {
    seedBoard(DEFAULT_BOARD)

    // DEFAULT_BOARD의 첫 번째 컬럼(TODO)에서 두 번째 컬럼(IN_PROGRESS)으로 이동
    const todoColumn = DEFAULT_BOARD.columns[0]
    const inProgressColumn = DEFAULT_BOARD.columns[1]
    const firstCard = todoColumn?.cards[0]

    if (todoColumn === undefined || inProgressColumn === undefined || firstCard === undefined) {
      throw new Error('fixture 데이터 불완전')
    }

    const res = await moveCard(DEFAULT_BOARD.boardId, firstCard.issueKey, {
      toColumnId: inProgressColumn.columnId,
      expectedVersion: firstCard.version,
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<MoveCardResult>
    expect(body.data.issueKey).toBe(firstCard.issueKey)
    expect(body.data.columnId).toBe(inProgressColumn.columnId)
    expect(body.data.version).toBe(firstCard.version + 1)
  })

  it('이동 후 GET 상세에 카드가 새 컬럼에 있음 (stateful 반영)', async () => {
    seedBoard(DEFAULT_BOARD)

    const todoColumn = DEFAULT_BOARD.columns[0]
    const inProgressColumn = DEFAULT_BOARD.columns[1]
    const firstCard = todoColumn?.cards[0]

    if (todoColumn === undefined || inProgressColumn === undefined || firstCard === undefined) {
      throw new Error('fixture 데이터 불완전')
    }

    await moveCard(DEFAULT_BOARD.boardId, firstCard.issueKey, {
      toColumnId: inProgressColumn.columnId,
      expectedVersion: firstCard.version,
    })

    // GET 상세에서 카드가 새 컬럼에 있는지 확인
    const detailRes = await getBoard(DEFAULT_BOARD.boardId)
    const detail = (await detailRes.json()) as DataResponse<BoardDetail>

    const inProgressCards = detail.data.columns
      .find((c) => c.columnId === inProgressColumn.columnId)
      ?.cards ?? []
    const movedCard = inProgressCards.find((c) => c.issueKey === firstCard.issueKey)
    expect(movedCard).toBeDefined()
    expect(movedCard?.version).toBe(firstCard.version + 1)

    // 기존 TODO 컬럼에서는 제거됨
    const todoCards = detail.data.columns
      .find((c) => c.columnId === todoColumn.columnId)
      ?.cards ?? []
    const removedCard = todoCards.find((c) => c.issueKey === firstCard.issueKey)
    expect(removedCard).toBeUndefined()
  })

  it('409 토글 플래그 설정 시 move → 409 errorCode AGILE_CONFLICT', async () => {
    // 409 충돌 분기는 보드 조회 전에 발생하므로 보드 시드 불필요
    // 단, boardId/issueKey/toColumnId는 임의 값으로도 409 반환됨을 검증
    localStorage.setItem(LS_KEY_BOARD_CONFLICT, 'true')

    const res = await moveCard(
      DEFAULT_BOARD.boardId,
      'ATLAS-1',
      {
        toColumnId: '20000000-0000-4000-8000-000000000002',
        expectedVersion: 0,
      },
    )
    expect(res.status).toBe(409)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_CONFLICT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards/:id — query param 필터 (FR-BD-02)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * FILTER_BOARD 픽스처 카드 구성 (내부 store에만 labels/componentIds 있음).
 *
 * FILTER-1: assigneeId=ALICE_USER_ID, labels=[bug],             componentIds=[COMPONENT_C1_ID]
 * FILTER-2: assigneeId=BOB_USER_ID,   labels=[feature],         componentIds=[COMPONENT_C1_ID, COMPONENT_C2_ID]
 * FILTER-3: assigneeId=ALICE_USER_ID, labels=[bug, documentation], componentIds=[COMPONENT_C2_ID]
 * FILTER-4: assigneeId=null(미배정), labels=[], componentIds=[]
 *
 * 아래 UUID 상수는 board-fixtures.ts ALICE_USER_ID / BOB_USER_ID / COMPONENT_C1_ID / COMPONENT_C2_ID 와 동기화.
 */

const ALICE_USER_ID = 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f'
const BOB_USER_ID = 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a'
const COMPONENT_C1_ID = '40000000-0000-4000-8000-000000000001'

async function getBoardWithFilter(boardId: string, params: string): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}?${params}`)
}

/** 응답에서 전체 컬럼에 걸친 카드 issueKey 목록을 추출한다 */
function collectIssueKeys(board: BoardDetail): string[] {
  return board.columns.flatMap((col) => col.cards.map((c) => c.issueKey))
}

/** 응답 카드에 labels/componentIds 필드가 없음을 검증한다 (DTO 오염 방지) */
function hasNoFilterMeta(board: BoardDetail): boolean {
  return board.columns.every((col) =>
    col.cards.every(
      (card) =>
        !Object.prototype.hasOwnProperty.call(card, 'labels') &&
        !Object.prototype.hasOwnProperty.call(card, 'componentIds'),
    ),
  )
}

describe('GET /api/v1/boards/:id — query param 필터 (FR-BD-02)', () => {
  it('query 없음 → 전체 카드 반환 (필터 없음)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, '')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-1')
    expect(keys).toContain('FILTER-2')
    expect(keys).toContain('FILTER-3')
    expect(keys).toContain('FILTER-4')
  })

  it('?assignee=ALICE → alice 담당 카드만 (FILTER-1, FILTER-3)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, `assignee=${ALICE_USER_ID}`)
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-1')
    expect(keys).toContain('FILTER-3')
    expect(keys).not.toContain('FILTER-2')
    expect(keys).not.toContain('FILTER-4')
  })

  it('?assignee=ALICE&assignee=BOB → alice OR bob (FILTER-1, FILTER-2, FILTER-3)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, `assignee=${ALICE_USER_ID}&assignee=${BOB_USER_ID}`)
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-1')
    expect(keys).toContain('FILTER-2')
    expect(keys).toContain('FILTER-3')
    expect(keys).not.toContain('FILTER-4')
  })

  it('?assignee=unassigned → assigneeId null 카드만 (FILTER-4)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, 'assignee=unassigned')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-4')
    expect(keys).not.toContain('FILTER-1')
    expect(keys).not.toContain('FILTER-2')
    expect(keys).not.toContain('FILTER-3')
  })

  it('?label=bug → bug 라벨 카드만 (FILTER-1, FILTER-3)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, 'label=bug')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-1')
    expect(keys).toContain('FILTER-3')
    expect(keys).not.toContain('FILTER-2')
    expect(keys).not.toContain('FILTER-4')
  })

  it('?component=C1_ID → c1 컴포넌트 카드만 (FILTER-1, FILTER-2)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, `component=${COMPONENT_C1_ID}`)
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-1')
    expect(keys).toContain('FILTER-2')
    expect(keys).not.toContain('FILTER-3')
    expect(keys).not.toContain('FILTER-4')
  })

  it('?assignee=ALICE&label=bug → alice 담당 이면서 bug 라벨 (FILTER-1, FILTER-3)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, `assignee=${ALICE_USER_ID}&label=bug`)
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const keys = collectIssueKeys(body.data)
    expect(keys).toContain('FILTER-1')
    expect(keys).toContain('FILTER-3')
    expect(keys).not.toContain('FILTER-2')
    expect(keys).not.toContain('FILTER-4')
  })

  it('응답 카드에 labels/componentIds 없음 (DTO 오염 방지)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, '')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    expect(hasNoFilterMeta(body.data)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// resetBoardStore / seedBoard 헬퍼 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('resetBoardStore / seedBoard 헬퍼', () => {
  it('seedBoard → resetBoardStore → GET 빈 목록', async () => {
    seedBoard(DEFAULT_BOARD)
    resetBoardStore()

    const res = await getBoards('ATLAS')
    const body = (await res.json()) as DataResponse<BoardSummary[]>
    expect(body.data).toHaveLength(0)
  })

  it('beforeEach 자동 초기화 — 이전 테스트 잔여 없음', async () => {
    const res = await getBoards('ATLAS')
    const body = (await res.json()) as DataResponse<BoardSummary[]>
    expect(body.data).toHaveLength(0)
  })
})
