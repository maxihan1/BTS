// 칸반 보드 MSW 핸들러 stateful 동작 검증 테스트 (FR-BD-01 D6, FR-BD-02 D6)
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { boardHandlers } from './board-handlers'
import {
  resetBoardStore,
  seedBoard,
  seedBoardWithMeta,
  LS_KEY_BOARD_CONFLICT,
  DEFAULT_BOARD,
  FILTER_BOARD,
  SWIMLANE_BOARD,
} from './board-fixtures'
// FR-UX-06 PR21b Task 6 — 필드변경(담당자/우선순위/에픽) MSW stateful 반영 검증.
// issue-tracking BC 핸들러(changeAssignee/updateIssue/connectEpicChild)를 실제로 호출해
// issueOverrides를 채운 뒤, board GET이 그 최신값을 오버레이하는지 확인한다(E2E에 가장 근접
// — server.use로 issueHandlers를 이 테스트 파일 스코프에만 추가 등록, 같은 엔드포인트 핸들러
// 중복 없음). resetIssueState는 issueOverrides 등 모듈-스코프 state를 테스트 간 격리한다.
import { issueHandlers, resetIssueState } from './issue-handlers'

beforeEach(() => {
  server.use(...boardHandlers)
})
afterEach(() => {
  resetBoardStore()
  localStorage.removeItem(LS_KEY_BOARD_CONFLICT)
})

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
  /** LexoRank 문자열. 아직 rank 미부여 시 null (FR-UX-06 PR21 Task 3) */
  rank: string | null
  /** 우선순위 (1=Highest ~ 5=Lowest). FR-UX-06 PR21b Task 6 필드변경 반영 검증용. */
  priority: number
  /** 소속 에픽 키. 미소속이면 null. FR-UX-06 PR21b Task 6 필드변경 반영 검증용. */
  epicKey: string | null
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
  swimlaneField: 'NONE' | 'ASSIGNEE' | 'PRIORITY'
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
// GET /api/v1/boards/:id — rank 필드 응답 (FR-UX-06 PR21 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/boards/:id — rank 필드 응답 (FR-UX-06 PR21 Task 3)', () => {
  it('유효 LexoRank가 부여된 카드는 응답에 rank 문자열을 포함한다 (dnd-kit/sortable 정렬 근거)', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const cards = body.data.columns.flatMap((c) => c.cards)
    const atlas1 = cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(atlas1?.rank).toBe('0|hzzzzz:')
  })

  it('rank가 아직 부여되지 않은 카드는 응답에서 null로 반환된다', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const cards = body.data.columns.flatMap((c) => c.cards)
    const atlas3 = cards.find((c) => c.issueKey === 'ATLAS-3')
    expect(atlas3?.rank).toBeNull()
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

const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'
const BOB_USER_ID = '00000000-0000-4000-8000-000000000002'
const COMPONENT_C1_ID = '40000000-0000-4000-8000-000000000001'

async function getBoardWithFilter(boardId: string, params: string): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}?${params}`)
}

/** 응답에서 전체 컬럼에 걸친 카드 issueKey 목록을 추출한다 */
function collectIssueKeys(board: BoardDetail): string[] {
  return board.columns.flatMap((col) => col.cards.map((c) => c.issueKey))
}

/**
 * 응답 카드에 **저장소 전용** 메타가 새지 않음을 검증한다 (DTO 오염 방지).
 *
 * ★ 2026-08-07 FR-UX-14 F14 로 범위가 좁아졌다. 원래는 `labels` 도 함께 막았지만
 * `labels` 는 **B2(#346)로 `BoardCardResponse` 의 정식 필드가 됐다** — 더는 오염이 아니다.
 * 여전히 저장소 전용인 것은 `componentIds` 하나뿐이라 그것만 막는다.
 * (가드를 지우지 않고 좁힌 이유. `componentIds` 누출은 아직 실재하는 위험이다.)
 */
function hasNoStoreOnlyMeta(board: BoardDetail): boolean {
  return board.columns.every((col) =>
    col.cards.every((card) => !Object.prototype.hasOwnProperty.call(card, 'componentIds')),
  )
}

/**
 * 카드가 B2(#346) 계약 3필드를 **실제로 싣고 있음**을 검증한다.
 *
 * 위 가드에서 `labels` 를 빼는 대신 이 짝을 둔다 — 단순히 단언을 지우면 「응답에서 필드가
 * 통째로 사라져도 통과하는」 공허한 상태가 된다.
 */
function hasCardDensityFields(board: BoardDetail): boolean {
  return board.columns.every((col) =>
    col.cards.every(
      (card) =>
        Object.prototype.hasOwnProperty.call(card, 'typeKey') &&
        Object.prototype.hasOwnProperty.call(card, 'labels') &&
        Object.prototype.hasOwnProperty.call(card, 'originalEstimateSeconds'),
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

  it('응답 카드에 componentIds 없음 (저장소 전용 메타 오염 방지)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, '')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    // 비-공허 짝. 카드가 0개면 every() 가 검사할 것 없이 통과한다.
    expect(collectIssueKeys(body.data).length).toBeGreaterThan(0)
    expect(hasNoStoreOnlyMeta(body.data)).toBe(true)
  })

  it('응답 카드에 typeKey·labels·originalEstimateSeconds 가 실린다 (FR-UX-14 B2 계약)', async () => {
    seedBoard(FILTER_BOARD)
    const res = await getBoardWithFilter(FILTER_BOARD.boardId, '')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardDetail>
    // 비-공허 짝. 카드가 0개면 아래 every() 가 검사할 것 없이 통과한다.
    expect(collectIssueKeys(body.data).length).toBeGreaterThan(0)
    expect(hasCardDensityFields(body.data)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/boards/:id — 스윔레인 기준 변경 (FR-BD-03)
// ─────────────────────────────────────────────────────────────────────────────

interface BoardMeta {
  boardId: string
  projectKey: string
  name: string
  swimlaneField: 'NONE' | 'ASSIGNEE' | 'PRIORITY'
}

async function patchBoardSwimlane(
  boardId: string,
  swimlaneField: string,
): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ swimlaneField }),
  })
}

describe('PATCH /api/v1/boards/:id — 스윔레인 기준 변경 (FR-BD-03)', () => {
  it('ASSIGNEE로 변경 → 200 { data: BoardMeta } swimlaneField=ASSIGNEE', async () => {
    seedBoardWithMeta(SWIMLANE_BOARD)

    const res = await patchBoardSwimlane(SWIMLANE_BOARD.boardId, 'ASSIGNEE')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardMeta>
    expect(body.data.boardId).toBe(SWIMLANE_BOARD.boardId)
    expect(body.data.swimlaneField).toBe('ASSIGNEE')
  })

  it('PRIORITY로 변경 → 200 swimlaneField=PRIORITY', async () => {
    seedBoardWithMeta(SWIMLANE_BOARD)

    const res = await patchBoardSwimlane(SWIMLANE_BOARD.boardId, 'PRIORITY')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardMeta>
    expect(body.data.swimlaneField).toBe('PRIORITY')
  })

  it('NONE으로 복구 → 200 swimlaneField=NONE', async () => {
    seedBoardWithMeta(SWIMLANE_BOARD)

    await patchBoardSwimlane(SWIMLANE_BOARD.boardId, 'ASSIGNEE')
    const res = await patchBoardSwimlane(SWIMLANE_BOARD.boardId, 'NONE')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<BoardMeta>
    expect(body.data.swimlaneField).toBe('NONE')
  })

  it('PATCH 후 GET 상세에서 swimlaneField가 갱신됨 (stateful 반영)', async () => {
    seedBoardWithMeta(SWIMLANE_BOARD)

    await patchBoardSwimlane(SWIMLANE_BOARD.boardId, 'PRIORITY')

    const detailRes = await getBoard(SWIMLANE_BOARD.boardId)
    expect(detailRes.status).toBe(200)
    const body = (await detailRes.json()) as DataResponse<BoardDetail>
    expect(body.data.swimlaneField).toBe('PRIORITY')
  })

  it('없는 보드 → 404 errorCode AGILE_BOARD_NOT_FOUND', async () => {
    const res = await patchBoardSwimlane('00000000-0000-4000-8000-000000000099', 'ASSIGNEE')
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_BOARD_NOT_FOUND')
  })

  it('잘못된 swimlaneField 값 → 400 errorCode INVALID_SWIMLANE_FIELD', async () => {
    seedBoardWithMeta(SWIMLANE_BOARD)

    const res = await patchBoardSwimlane(SWIMLANE_BOARD.boardId, 'INVALID_VALUE')
    expect(res.status).toBe(400)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('INVALID_SWIMLANE_FIELD')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 퀵필터 CRUD (FR-UX-01) — POST/PATCH/DELETE /api/v1/boards/:id/quick-filters[/:filterId]
// ─────────────────────────────────────────────────────────────────────────────

interface QuickFilterDto {
  filterId: string
  name: string
  query: string
}

async function postQuickFilter(
  boardId: string,
  body: { name: string; query: string },
): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}/quick-filters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function patchQuickFilter(
  boardId: string,
  filterId: string,
  body: { name: string; query: string },
): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}/quick-filters/${filterId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function deleteQuickFilterRequest(boardId: string, filterId: string): Promise<Response> {
  return fetch(`/api/v1/boards/${boardId}/quick-filters/${filterId}`, { method: 'DELETE' })
}

describe('POST /api/v1/boards/:id/quick-filters — 퀵필터 생성 (FR-UX-01)', () => {
  it('생성 → 201 { data: QuickFilter } — query가 정규화되어 저장된다(중복 제거, 가짜그린 차단)', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await postQuickFilter(DEFAULT_BOARD.boardId, {
      name: '내 버그',
      query: 'label=bug&label=bug&assignee=00000000-0000-4000-8000-000000000001',
    })
    expect(res.status).toBe(201)
    const body = (await res.json()) as DataResponse<QuickFilterDto>
    expect(body.data.name).toBe('내 버그')
    expect(body.data.filterId).toBeTruthy()
    // 중복 label 정규화(dedupe) 확인 — raw echo가 아님을 보장 (msw-derived-behavior-shared-store-e2e)
    const labelOccurrences = (body.data.query.match(/label=bug/g) ?? []).length
    expect(labelOccurrences).toBe(1)
  })

  it('생성 후 → GET 보드 상세 quickFilters에 반영된다 (stateful)', async () => {
    seedBoard(DEFAULT_BOARD)
    await postQuickFilter(DEFAULT_BOARD.boardId, { name: '내 버그', query: 'label=bug' })

    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<{ quickFilters: QuickFilterDto[] }>
    expect(body.data.quickFilters).toHaveLength(1)
    expect(body.data.quickFilters[0]?.name).toBe('내 버그')
  })

  it('빈 query(EC1) → 400', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await postQuickFilter(DEFAULT_BOARD.boardId, { name: '빈 필터', query: '' })
    expect(res.status).toBe(400)
  })

  it('같은 보드 내 이름 중복(EC2) → 409', async () => {
    seedBoard(DEFAULT_BOARD)
    await postQuickFilter(DEFAULT_BOARD.boardId, { name: '내 버그', query: 'label=bug' })
    const res = await postQuickFilter(DEFAULT_BOARD.boardId, { name: '내 버그', query: 'label=feature' })
    expect(res.status).toBe(409)
  })

  it('없는 보드 → 404 errorCode AGILE_BOARD_NOT_FOUND', async () => {
    const res = await postQuickFilter('00000000-0000-4000-8000-000000000099', {
      name: '내 버그',
      query: 'label=bug',
    })
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_BOARD_NOT_FOUND')
  })
})

describe('PATCH /api/v1/boards/:id/quick-filters/:filterId — 퀵필터 수정 (FR-UX-01)', () => {
  it('수정 → 200 { data: QuickFilter } 갱신 반영', async () => {
    seedBoard(DEFAULT_BOARD)
    const createRes = await postQuickFilter(DEFAULT_BOARD.boardId, { name: '내 버그', query: 'label=bug' })
    const created = (await createRes.json()) as DataResponse<QuickFilterDto>

    const res = await patchQuickFilter(DEFAULT_BOARD.boardId, created.data.filterId, {
      name: '긴급 버그',
      query: 'label=urgent',
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<QuickFilterDto>
    expect(body.data.name).toBe('긴급 버그')
    expect(body.data.query).toBe('label=urgent')
    expect(body.data.filterId).toBe(created.data.filterId)
  })

  it('수정 후 → GET 보드 상세에 반영된다 (stateful)', async () => {
    seedBoard(DEFAULT_BOARD)
    const createRes = await postQuickFilter(DEFAULT_BOARD.boardId, { name: '내 버그', query: 'label=bug' })
    const created = (await createRes.json()) as DataResponse<QuickFilterDto>

    await patchQuickFilter(DEFAULT_BOARD.boardId, created.data.filterId, {
      name: '긴급 버그',
      query: 'label=urgent',
    })

    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<{ quickFilters: QuickFilterDto[] }>
    expect(body.data.quickFilters).toHaveLength(1)
    expect(body.data.quickFilters[0]?.name).toBe('긴급 버그')
  })

  it('타 보드 소속(존재하지 않는) filterId(EC5) → 404', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await patchQuickFilter(DEFAULT_BOARD.boardId, '00000000-0000-4000-8000-000000000099', {
      name: '내 버그',
      query: 'label=bug',
    })
    expect(res.status).toBe(404)
  })

  it('없는 보드 → 404 errorCode AGILE_BOARD_NOT_FOUND', async () => {
    const res = await patchQuickFilter(
      '00000000-0000-4000-8000-000000000099',
      '00000000-0000-4000-8000-000000000001',
      { name: '내 버그', query: 'label=bug' },
    )
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_BOARD_NOT_FOUND')
  })
})

describe('DELETE /api/v1/boards/:id/quick-filters/:filterId — 퀵필터 삭제 (FR-UX-01)', () => {
  it('삭제 → 204, 이후 GET 상세에서 사라짐 (stateful)', async () => {
    seedBoard(DEFAULT_BOARD)
    const createRes = await postQuickFilter(DEFAULT_BOARD.boardId, { name: '내 버그', query: 'label=bug' })
    const created = (await createRes.json()) as DataResponse<QuickFilterDto>

    const res = await deleteQuickFilterRequest(DEFAULT_BOARD.boardId, created.data.filterId)
    expect(res.status).toBe(204)

    const getRes = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await getRes.json()) as DataResponse<{ quickFilters: QuickFilterDto[] }>
    expect(body.data.quickFilters).toHaveLength(0)
  })

  it('없는 filterId → 404', async () => {
    seedBoard(DEFAULT_BOARD)
    const res = await deleteQuickFilterRequest(
      DEFAULT_BOARD.boardId,
      '00000000-0000-4000-8000-000000000099',
    )
    expect(res.status).toBe(404)
  })

  it('없는 보드 → 404 errorCode AGILE_BOARD_NOT_FOUND', async () => {
    const res = await deleteQuickFilterRequest(
      '00000000-0000-4000-8000-000000000099',
      '00000000-0000-4000-8000-000000000001',
    )
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_BOARD_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards/:id — 필드변경(담당자/우선순위/에픽) 반영 (FR-UX-06 PR21b Task 6)
//
// DEFAULT_BOARD(ATLAS 프로젝트) 카드 ATLAS-1/2/4는 issue-handlers.ts issueFixtureMap에
// 이미 짝이 맞아 있어(ATLAS-1~5 전부 등록) 별도 fixture 짝시드가 필요 없다 — 실측 확인.
// ATLAS-EPIC-1은 issueFixtureMap에는 있지만 board에는 없어도 connectEpicChildHandler가
// resolveIssue로 issueFixtureMap까지 조회하므로 문제 없다.
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/boards/:id — 필드변경 반영 (FR-UX-06 PR21b Task 6)', () => {
  // 이 describe 스코프에서만 issueHandlers를 추가 등록 — 같은 엔드포인트 중복 핸들러 없음
  // (board GET/PATCH/POST는 boardHandlers, 이슈 PATCH/POST는 issueHandlers로 경로가 겹치지 않는다).
  beforeEach(() => {
    server.use(...issueHandlers)
  })
  afterEach(() => {
    resetIssueState()
  })

  it('changeAssignee 호출 후 → board GET 카드 assigneeId가 최신값 반영', async () => {
    seedBoard(DEFAULT_BOARD)

    const assigneeRes = await fetch('/api/v1/issues/ATLAS-1/assignee', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ assigneeId: BOB_USER_ID, expectedVersion: 0 }),
    })
    expect(assigneeRes.status).toBe(200)

    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const cards = body.data.columns.flatMap((c) => c.cards)
    const atlas1 = cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(atlas1?.assigneeId).toBe(BOB_USER_ID)
  })

  it('updateIssue priority 변경 후 → board GET 카드 priority가 최신값 반영', async () => {
    seedBoard(DEFAULT_BOARD)

    const patchRes = await fetch('/api/v1/issues/ATLAS-2', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ priority: 5, expectedVersion: 1 }),
    })
    expect(patchRes.status).toBe(200)

    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const cards = body.data.columns.flatMap((c) => c.cards)
    const atlas2 = cards.find((c) => c.issueKey === 'ATLAS-2')
    expect(atlas2?.priority).toBe(5)
  })

  it('connectEpicChild 호출 후 → board GET 카드 epicKey가 최신값 반영', async () => {
    seedBoard(DEFAULT_BOARD)

    const connectRes = await fetch('/api/v1/issues/ATLAS-EPIC-1/epic-children', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ childKey: 'ATLAS-4' }),
    })
    expect(connectRes.status).toBe(201)

    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const cards = body.data.columns.flatMap((c) => c.cards)
    const atlas4 = cards.find((c) => c.issueKey === 'ATLAS-4')
    expect(atlas4?.epicKey).toBe('ATLAS-EPIC-1')
  })

  it('필드변경 이력이 없는 카드는 board 자체 시드값을 그대로 유지 (무회귀)', async () => {
    seedBoard(DEFAULT_BOARD)

    // ATLAS-1만 변경 — ATLAS-3(DONE 컬럼)은 건드리지 않는다.
    await fetch('/api/v1/issues/ATLAS-1/assignee', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ assigneeId: null, expectedVersion: 0 }),
    })

    const res = await getBoard(DEFAULT_BOARD.boardId)
    const body = (await res.json()) as DataResponse<BoardDetail>
    const cards = body.data.columns.flatMap((c) => c.cards)
    const atlas3 = cards.find((c) => c.issueKey === 'ATLAS-3')
    // DEFAULT_BOARD ATLAS-3 시드값 그대로 (board-fixtures.ts DEFAULT_BOARD 참조)
    expect(atlas3?.assigneeId).toBeNull()
    expect(atlas3?.priority).toBe(3)
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
