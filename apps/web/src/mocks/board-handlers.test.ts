// 칸반 보드 MSW 핸들러 stateful 동작 검증 테스트 (FR-BD-01 D6, FR-BD-02 D6)
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { boardHandlers, QUICK_FILTER_PERM_SEED } from './board-handlers'
// FR-BD-04 D6 Task 5 — 보드 생성 응답을 실제 API 함수로 파싱해 검증한다.
// 이 파일의 다른 테스트처럼 핸들러 JSON 만 직접 읽으면 boardCreatedSchema 를 타지 않아
// 응답에서 필드가 통째로 빠져도 런타임 테스트가 하나도 깨지지 않는다(Task 1 실측).
// audit-log-handlers.test.ts(fetchAuditLogs) · workflow-admin-handlers.test.ts(fetchWorkflows)가
// 같은 관례로 API 함수를 직접 호출한다.
import { createBoard, boardCreatedSchema, fetchBoard, fetchBoards } from '@/api/boards'
// 부채 177 Task 22 — 카드 레이아웃 PATCH 도 **API 함수로** 부른다. 핸들러 JSON 을 직접 읽으면
// `cardLayoutResponseSchema` 를 타지 않아 응답에서 뷰가 통째로 빠져도 아무 테스트도 안 깨진다.
import { replaceCardLayout } from '@/api/board-settings'
import {
  resetBoardStore,
  seedBoard,
  seedBoardWithMeta,
  createBoardInStore,
  LS_KEY_BOARD_CONFLICT,
  DEFAULT_BOARD,
  FILTER_BOARD,
  SWIMLANE_BOARD,
  WIP_BOARD,
  EPIC_SWIMLANE_BOARD,
  REORDER_SWIMLANE_BOARD,
} from './board-fixtures'
// FR-BD-04 Task 10 — 「시작하면 보드가 바뀐다」는 backlog BC 핸들러가 방아쇠다.
// 스프린트 시작/완료를 실제로 호출해야 파생 동작이 재현되므로 그 핸들러를 함께 등록한다
// (issueHandlers 를 같은 파일에서 server.use 로 얹은 선례와 동일 — 엔드포인트 중복 없음).
import { backlogHandlers } from './backlog-handlers'
import {
  resetBacklogStore,
  seedBacklog,
  generateUUID as generateBacklogUUID,
  DEFAULT_BACKLOG,
} from './backlog-fixtures'
import type { BacklogIssue, SprintMeta } from '@/api/backlog'
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
  /** 삭제 권한 보유 여부 (FR-BD-01-2d). 스키마가 `.optional()` 이라 여기도 optional 이다. */
  canDelete?: boolean
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
  /** 보드 종류. 백엔드 `BoardCreatedResponse.boardType` 은 non-null 이다 (FR-BD-04 D6). */
  boardType: 'SCRUM' | 'KANBAN'
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
// POST /api/v1/boards — 보드 종류 (FR-BD-04 D6)
//
// 이 describe 만이 `createBoard()` 를 거쳐 boardCreatedSchema 파싱을 실제로 통과한다.
// 위 describe 들처럼 핸들러 JSON 만 직접 읽으면 응답에서 필드가 빠져도 런타임이 조용히 초록이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/boards — 보드 종류 (FR-BD-04 D6)', () => {
  it('boardType=SCRUM 요청 → 응답이 SCRUM 을 되싣는다 (boardCreatedSchema 파싱 경유)', async () => {
    const created = await createBoard('ATLAS', '스크럼 보드', 'SCRUM')
    expect(created.boardType).toBe('SCRUM')
  })

  it('boardType 미전송 → KANBAN (백엔드 BoardCreateRequest.boardType 이 선택 인자라 같은 기본값)', async () => {
    const res = await postBoard({ projectKey: 'ATLAS', name: '기본 보드' })
    expect(res.status).toBe(201)
    const body = (await res.json()) as DataResponse<unknown>
    // 스키마로 파싱해 「필드가 아예 없음」과 「KANBAN 임」을 한 단언으로 가른다.
    expect(boardCreatedSchema.parse(body.data).boardType).toBe('KANBAN')
  })

  it('고른 종류가 store 에 남아 GET 상세에 실린다 (stateful · boardDetailSchema 파싱 경유)', async () => {
    const created = await createBoard('ATLAS', '스크럼 보드', 'SCRUM')

    // PR ② 에서는 JSON 으로만 확인했다(그때 boardDetailSchema 에 필드가 없었다). PR ③ 이
    // 필드를 필수로 올렸으므로 화면이 쓰는 경로(fetchBoard → z.parse)로 되읽는다 —
    // 응답 기본값 'KANBAN' 이 고른 종류를 삼키지 않는지가 여기서 갈린다.
    const detail = await fetchBoard(created.boardId)
    expect(detail.boardType).toBe('SCRUM')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards — 보드 종류·활성 스프린트 응답 계약 (FR-BD-04 · PR ③)
//
// ★왜 스키마 파싱 경유로 재는가.
// 이 파일의 대부분은 핸들러 JSON 을 그대로 읽어 단언한다. 그러면 `boardDetailSchema` 가
// 필수로 올린 필드가 응답에서 통째로 빠져도 유닛이 하나도 안 깨진다. 게다가 시드 6개는
// `StoredBoardDetail`(= `BoardDetail` 과 **별개 타입**) 이라 컴파일러도 못 잡는다 —
// 컴파일러와 테스트가 **둘 다 침묵**하는 자리다. 화면이 실제로 밟는 경로로 잰다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 모듈 로드 시 자동 시드되는 보드 전량 (board-fixtures.ts 6개 + board-handlers.ts 1개).
 *
 * ★`QUICK_FILTER_PERM_SEED` 는 **픽스처 파일 밖**에 있다 — board-fixtures.ts 만 보고 세면
 * 이 한 건이 빠지고 quick-filter.spec.ts S7 만 죽는다. 목록을 여기 한 곳에 모아 둔다.
 */
const SEEDED_BOARDS: [string, Parameters<typeof seedBoard>[0]][] = [
  ['DEFAULT_BOARD', DEFAULT_BOARD],
  ['FILTER_BOARD', FILTER_BOARD],
  ['WIP_BOARD', WIP_BOARD],
  ['SWIMLANE_BOARD', SWIMLANE_BOARD],
  ['EPIC_SWIMLANE_BOARD', EPIC_SWIMLANE_BOARD],
  ['REORDER_SWIMLANE_BOARD', REORDER_SWIMLANE_BOARD],
  ['QUICK_FILTER_PERM_SEED', QUICK_FILTER_PERM_SEED],
]

describe('시드 전량 — 보드 종류 계약 (FR-BD-04)', () => {
  it.each(SEEDED_BOARDS)(
    '%s 가 boardType 을 명시한다 (응답 기본값이 가리지 못하도록 시드에서 직접 잰다)',
    (_name, board) => {
      // 응답만 재면 `toResponseDetail` 의 `?? 'KANBAN'` 이 미설정 시드를 덮어 공허해진다.
      // 시드 자체를 재는 단언만이 「7개를 전부 고쳤는가」를 잰다.
      expect(board.boardType).toBe('KANBAN')
    },
  )

  it.each(SEEDED_BOARDS)(
    '%s 의 GET 상세 응답이 boardDetailSchema 를 통과한다 (activeSprint 는 null)',
    async (_name, board) => {
      seedBoard(board)
      const detail = await fetchBoard(board.boardId)
      expect(detail.boardType).toBe('KANBAN')
      // 칸반 보드는 활성 스프린트 개념이 없다 — 키가 빠지면 스키마가 거부한다.
      expect(detail.activeSprint).toBeNull()
    },
  )

  it('boardType 이 없는 레거시 stored 는 KANBAN 으로 응답한다 (toResponseDetail 기본값)', async () => {
    // 위 단언의 짝 — 기본값이 실제로 존재하는지를 「미설정 시드」로만 잴 수 있다.
    const legacy = { ...SWIMLANE_BOARD, boardId: '10000000-0000-4000-8000-0000000000f1' }
    delete legacy.boardType
    seedBoardWithMeta(legacy)

    const detail = await fetchBoard(legacy.boardId)
    expect(detail.boardType).toBe('KANBAN')
    expect(detail.activeSprint).toBeNull()
  })

  it('보드 목록 응답이 boardSummarySchema 를 통과하고 boardType 을 싣는다', async () => {
    seedBoard(DEFAULT_BOARD)
    // 목록은 상세와 다른 조립부다 — 상세만 고치면 스위처가 목록 파싱에서 죽는다.
    const summaries = await fetchBoards('ATLAS')
    expect(summaries).toHaveLength(1)
    expect(summaries[0]?.boardType).toBe('KANBAN')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 목록 조립부 — canDelete (FR-BD-01-2d · 캠페인 PR ⑧)
//
// 세 분기를 전부 잰다. 하나라도 빠지면 판정이 공허해진다.
//   ① true 만 재면 핸들러가 **상수 true** 를 박아도 통과한다 → ②가 그것을 가른다.
//   ② store 값을 통과시키는 것만 재면 「미정의일 때 무엇인가」가 안 잡힌다 → ③이 잰다.
//   ③ 상세 조립부(toResponseDetail)와 **같은 기본값**이어야 한다는 것이 스펙 E5 다.
//      두 조립부의 기본값이 갈리는 것이 목록/상세 분리가 부른 사고의 모양이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('보드 목록 조립부 — canDelete (FR-BD-01-2d)', () => {
  it('store 의 canDelete=true 를 목록 응답에 그대로 싣는다', async () => {
    seedBoard(DEFAULT_BOARD)
    const summaries = await fetchBoards('ATLAS')
    expect(summaries).toHaveLength(1)
    expect(summaries[0]?.canDelete).toBe(true)
  })

  it('store 의 canDelete=false 를 목록 응답에 그대로 싣는다 (상수 true 를 박으면 여기서 깨진다)', async () => {
    seedBoard({ ...DEFAULT_BOARD, canDelete: false })
    const summaries = await fetchBoards('ATLAS')
    expect(summaries).toHaveLength(1)
    expect(summaries[0]?.canDelete).toBe(false)
  })

  it('store 에 canDelete 가 없으면 상세 조립부와 같은 기본값 true 로 응답한다', async () => {
    // 기본값이 실제로 존재하는지는 「미설정 시드」로만 잴 수 있다 (boardType 기본값 테스트와 같은 형태).
    const legacy = { ...DEFAULT_BOARD }
    delete legacy.canDelete
    seedBoard(legacy)

    const summaries = await fetchBoards('ATLAS')
    expect(summaries).toHaveLength(1)
    expect(summaries[0]?.canDelete).toBe(true)
    // 짝 단언 — 같은 store 를 상세로 읽어도 같은 값이어야 한다. 두 조립부가 갈리면 여기가 깨진다.
    const detail = await fetchBoard(DEFAULT_BOARD.boardId)
    expect(detail.canDelete).toBe(true)
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

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/boards/:id — 스크럼 보드의 활성 스프린트 파생 (FR-BD-04 · Task 10)
//
// ★왜 보드 테스트에 「스프린트 시작」이 섞여 있나.
// 「스프린트를 시작하면 보드가 바뀐다」(ADR §D3 · J18)는 **두 store 를 가로지르는 파생 동작**이다.
// 시작 핸들러(backlog BC)가 boardStore 를 안 건드리면 보드 상세의 `activeSprint` 는 시드값(null)
// 그대로고 카드도 0건이다 — 각 핸들러를 따로 재는 한 그 갈림이 **어느 쪽 유닛에도 안 잡힌다**.
// 실제로 D7 E2E S7 이 이 자리에서 red 였다(2026-09-02 실측 · 화면이 시작 전후로 동일).
//
// 각 단언에 「시작 전」짝을 둔다 — 「보였다」는 「바뀌었다」의 증거가 아니다.
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/boards/:id — 스크럼 보드 활성 스프린트 (FR-BD-04 Task 10)', () => {
  const SPRINT_PROJECT = 'SPRINTBD'
  const SCRUM_SPRINT_NAME = '스크럼 스프린트'
  const SPRINT_START_DATE = '2026-09-01'
  const SPRINT_END_DATE = '2026-09-14'

  /** createBoardInStore 의 3컬럼(open·in_progress·done) 어디에도 없는 상태 키 — unplaced 관측점 */
  const UNMAPPED_STATE_KEY = 'archived'

  let kanbanBoardId = ''
  let scrumBoardId = ''
  let scrumSprintId = ''
  let kanbanSprintId = ''

  /** 시나리오 전용 백로그 이슈 — 필드는 BacklogIssue 계약 그대로다 */
  function sprintIssue(key: string, currentStateKey: string): BacklogIssue {
    return {
      key,
      summary: `활성 스프린트 파생 테스트 ${key}`,
      currentStateKey,
      assigneeId: null,
      priority: 1,
      rank: `0|${key}:`,
      version: 0,
      epicKey: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    }
  }

  /** 시나리오 전용 스프린트 메타 — 보드 축은 StoredSprint.boardId 에 있다(응답 DTO 에 없음) */
  function plannedSprint(sprintId: string, name: string): SprintMeta {
    return {
      sprintId,
      boardId: '10000000-0000-4000-8000-000000000001',
      name,
      goal: null,
      status: 'PLANNED',
      startDate: SPRINT_START_DATE,
      endDate: SPRINT_END_DATE,
      version: 0,
    }
  }

  async function startSprint(sprintId: string): Promise<Response> {
    return fetch(`/api/v1/sprints/${sprintId}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
  }

  async function completeSprint(sprintId: string): Promise<Response> {
    return fetch(`/api/v1/sprints/${sprintId}/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
  }

  /** 응답 카드 키 전량 — 컬럼 순서대로 평탄화 (fetchBoard = boardDetailSchema 파싱 경유) */
  function responseCardKeys(detail: Awaited<ReturnType<typeof fetchBoard>>): string[] {
    return detail.columns.flatMap((col) => col.cards.map((c) => c.issueKey))
  }

  beforeEach(() => {
    server.use(...backlogHandlers)
    resetBacklogStore()

    // 칸반을 먼저 만든다 — 「첫 보드」와 「첫 스크럼 보드」를 갈라 두는 것이 이 파일의 관례다.
    kanbanBoardId = createBoardInStore(SPRINT_PROJECT, '칸반 보드', 'KANBAN').created.boardId
    scrumBoardId = createBoardInStore(SPRINT_PROJECT, '스크럼 보드', 'SCRUM').created.boardId
    scrumSprintId = generateBacklogUUID()
    kanbanSprintId = generateBacklogUUID()

    seedBacklog({
      projectKey: SPRINT_PROJECT,
      // 어느 스프린트에도 없는 이슈 — 「그 스프린트의 이슈만」의 나머지 절반
      backlog: [sprintIssue('SPRINTBD-9', 'open')],
      sprints: [
        {
          sprint: plannedSprint(scrumSprintId, SCRUM_SPRINT_NAME),
          boardId: scrumBoardId,
          issues: [
            sprintIssue('SPRINTBD-1', 'open'),
            sprintIssue('SPRINTBD-2', 'in_progress'),
            // 컬럼에 매핑되지 않는 상태 — 백엔드 placeCards 의 unplacedCount 대응
            sprintIssue('SPRINTBD-3', UNMAPPED_STATE_KEY),
          ],
        },
        {
          // 칸반 보드에도 스프린트가 붙을 수 있다(백엔드 sprints.board_id 는 종류를 가리지 않는다).
          // 「칸반 무변경」을 재려면 그 대조군이 실재해야 한다.
          sprint: plannedSprint(kanbanSprintId, '칸반 스프린트'),
          boardId: kanbanBoardId,
          issues: [sprintIssue('SPRINTBD-8', 'open')],
        },
      ],
      truncated: false,
    })
  })

  afterEach(() => {
    resetBacklogStore()
  })

  it('① 시작하면 그 보드의 GET 상세에 activeSprint 4필드가 실린다 (시작 전에는 null)', async () => {
    // 대조군 — 시작 전에는 없다. 이게 없으면 「원래 실려 있었을 뿐」과 구별되지 않는다.
    expect((await fetchBoard(scrumBoardId)).activeSprint).toBeNull()

    expect((await startSprint(scrumSprintId)).status).toBe(200)

    // toEqual 로 4필드를 통째로 잰다 — `activeSprintSchema` 는 정확히 4필드다.
    expect((await fetchBoard(scrumBoardId)).activeSprint).toEqual({
      sprintId: scrumSprintId,
      name: SCRUM_SPRINT_NAME,
      startDate: SPRINT_START_DATE,
      endDate: SPRINT_END_DATE,
    })
  })

  it('② 활성 스프린트의 이슈가 currentStateKey ↔ stateKey 축으로 컬럼에 배치된다 (시작 전 0건)', async () => {
    // 대조군 — 새 보드는 카드가 0건이다(createBoardInStore). 배치가 「시작」에서 비롯됐음을 잰다.
    expect(responseCardKeys(await fetchBoard(scrumBoardId))).toEqual([])

    await startSprint(scrumSprintId)

    const detail = await fetchBoard(scrumBoardId)
    const cardsOf = (stateKey: string): string[] =>
      detail.columns
        .find((col) => col.states.some((s) => s.key === stateKey))
        ?.cards.map((c) => c.issueKey) ?? []
    expect(cardsOf('open')).toEqual(['SPRINTBD-1'])
    expect(cardsOf('in_progress')).toEqual(['SPRINTBD-2'])
    expect(cardsOf('done')).toEqual([])
  })

  it('② 스프린트 밖 이슈는 보드에 없다 — 백로그 이슈도, 남의 스프린트 이슈도 (J5 「만」)', async () => {
    await startSprint(scrumSprintId)

    const keys = responseCardKeys(await fetchBoard(scrumBoardId))
    expect(keys).not.toContain('SPRINTBD-9')
    expect(keys).not.toContain('SPRINTBD-8')
  })

  it('② 컬럼에 매핑되지 않는 상태의 이슈는 unplacedCount 로 샌다 (백엔드 placeCards 대응)', async () => {
    await startSprint(scrumSprintId)

    const detail = await fetchBoard(scrumBoardId)
    expect(responseCardKeys(detail)).not.toContain('SPRINTBD-3')
    expect(detail.unplacedCount).toBe(1)
  })

  it('③ 완료하면 activeSprint 가 null 로 돌아가고 카드도 사라진다 (시작의 대칭)', async () => {
    await startSprint(scrumSprintId)
    expect((await fetchBoard(scrumBoardId)).activeSprint).not.toBeNull()

    expect((await completeSprint(scrumSprintId)).status).toBe(200)

    const detail = await fetchBoard(scrumBoardId)
    expect(detail.activeSprint).toBeNull()
    expect(responseCardKeys(detail)).toEqual([])
  })

  it('④ 칸반 보드는 그 보드 소속 스프린트를 시작해도 activeSprint 가 안 생긴다', async () => {
    await startSprint(kanbanSprintId)

    const detail = await fetchBoard(kanbanBoardId)
    // 백엔드 getBoard 는 boardType 이 SCRUM 일 때만 findActiveByBoard 를 부른다 —
    // 칸반은 데이터에 활성 스프린트가 있어도 응답에 나타나지 않는다.
    expect(detail.activeSprint).toBeNull()
    expect(responseCardKeys(detail)).toEqual([])
  })

  it('④ DEFAULT_BOARD(칸반) 는 소속 스프린트를 시작해도 카드 구성이 그대로다 (보드 E2E 회귀 가드)', async () => {
    // 기존 보드 E2E 전량이 이 시드를 쓴다. DEFAULT_BACKLOG 의 스프린트는 전부 이 칸반 보드
    // 소속이라(ATLAS_DEFAULT_BOARD_ID) 스크럼 경로가 새면 여기서 카드가 통째로 갈린다.
    seedBoard(DEFAULT_BOARD)
    seedBacklog(DEFAULT_BACKLOG)

    const before = responseCardKeys(await fetchBoard(DEFAULT_BOARD.boardId))
    expect(before.length).toBeGreaterThan(0)

    const planned = DEFAULT_BACKLOG.sprints.find((s) => s.sprint.status === 'PLANNED')
    expect(planned).toBeDefined()
    expect((await startSprint(planned?.sprint.sprintId ?? '')).status).toBe(200)

    const after = await fetchBoard(DEFAULT_BOARD.boardId)
    expect(after.activeSprint).toBeNull()
    expect(responseCardKeys(after)).toEqual(before)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/boards/:id/card-layout — 뷰별 구성 (부채 177 Task 22 · J17 · J18)
//
// ★**E2E `board-settings.spec.ts` S10 이 기대는 계약을 여기서 못박는다.** S10 은 두 뷰에 서로
//   다른 값을 넣고 패널을 재마운트해 「보드 뷰는 자기 것만」을 재는데, 그 재마운트가 읽는 값이
//   바로 이 목의 `GET /boards/:id` 응답이다. 목이 뷰를 뭉개면 S10 은 화면이 옳아도 red 가 되고,
//   반대로 목이 요청을 echo 하기만 하면 S10 은 저장이 안 돼도 초록이 된다 — 어느 쪽이든
//   **화면이 아니라 목이 판정을 정하게 된다.** 그 자리를 단위로 고정한다.
// ─────────────────────────────────────────────────────────────────────────────
describe('PATCH /api/v1/boards/:id/card-layout — 뷰별 구성 (부채 177 Task 22)', () => {
  /** 시드는 전부 칸반이고 칸반에 `BACKLOG` 를 보내면 400 이다(R3) — 스크럼을 하나 만들어 쓴다. */
  let scrumBoardId = ''

  beforeEach(() => {
    scrumBoardId = createBoardInStore('ATLAS', '설정용 스크럼 보드', 'SCRUM').created.boardId
  })

  it('T-MSW-CL-1: 요청한 뷰만 저장된다 — 보내지 않은 뷰의 키는 생기지 않는다', async () => {
    // ★`toEqual` 로 **통째** 잰다. `BACKLOG` 를 빈 배열로 채우는 구현이면 화면이
    //   「구성 없음」과 「0개 구성」을 못 가른다(`boards.ts:228` 계약).
    expect(await replaceCardLayout(scrumBoardId, 'BOARD', ['EPIC'])).toEqual({ BOARD: ['EPIC'] })
  })

  it('T-MSW-CL-2: 다른 뷰를 저장해도 앞 뷰가 살아남는다 (통째 교체 금지)', async () => {
    await replaceCardLayout(scrumBoardId, 'BOARD', ['EPIC'])

    // ★**일부러 다른 값**이다. 같은 값을 넣으면 「한 벌만 저장하는」 구현도 통과한다(J18).
    expect(await replaceCardLayout(scrumBoardId, 'BACKLOG', ['PRIORITY', 'LABELS'])).toEqual({
      BOARD: ['EPIC'],
      BACKLOG: ['PRIORITY', 'LABELS'],
    })
  })

  it('T-MSW-CL-3: 보드 조회가 두 뷰의 구성을 그대로 실어 온다 — E2E 재마운트가 읽는 값', async () => {
    // 대조군 — 저장 전에는 키가 없다. 이게 없으면 「원래 실려 있었을 뿐」과 구별되지 않는다.
    expect((await fetchBoard(scrumBoardId)).cardLayout).toEqual({})

    await replaceCardLayout(scrumBoardId, 'BOARD', ['EPIC'])
    await replaceCardLayout(scrumBoardId, 'BACKLOG', ['PRIORITY', 'LABELS'])

    expect((await fetchBoard(scrumBoardId)).cardLayout).toEqual({
      BOARD: ['EPIC'],
      BACKLOG: ['PRIORITY', 'LABELS'],
    })
  })
})
