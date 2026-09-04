// 칸반 보드 API 클라이언트 단위 테스트 — Zod 스키마 계약 + fetch 함수 검증 (FR-BD-01/02/03)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  boardSummarySchema,
  boardCardSchema,
  boardColumnSchema,
  boardDetailSchema,
  boardCreatedSchema,
  moveCardResultSchema,
  boardMetaSchema,
  fetchBoards,
  fetchBoard,
  createBoard,
  moveCard,
  updateBoardSwimlane,
  updateBoardName,
  deleteBoard,
  type BoardCardFilterParams,
} from './boards'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — Zod v4 RFC4122 UUID 형식 필수
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const PROJECT_KEY = 'ATLAS'
const COLUMN_ID_TODO = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'
const COLUMN_ID_DONE = 'c3d4e5f6-a7b8-4012-9cde-f01234567892'
const ASSIGNEE_UUID = 'd4e5f6a7-b8c9-4123-8def-a12345678903'
const ISSUE_KEY = 'ATLAS-1'

const cardWithAssignee = {
  issueKey: ISSUE_KEY,
  summary: '테스트 이슈 1',
  assigneeId: ASSIGNEE_UUID,
  version: 1,
  priority: 1,
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

const cardNoAssignee = {
  issueKey: 'ATLAS-2',
  summary: '테스트 이슈 2',
  assigneeId: null,
  version: 2,
  priority: 3,
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

const columnTodo = {
  columnId: COLUMN_ID_TODO,
  states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
  name: '할 일',
  category: 'TODO' as const,
  displayOrder: 1,
  wipLimit: null,
  wipExceeded: false,
  cards: [cardNoAssignee],
}

const columnDone = {
  columnId: COLUMN_ID_DONE,
  states: [{ key: 'done', name: '완료', category: 'DONE' }],
  name: '완료',
  category: 'DONE' as const,
  displayOrder: 3,
  wipLimit: null,
  wipExceeded: false,
  cards: [cardWithAssignee],
}

const boardDetailFixture = {
  boardId: BOARD_ID,
  projectKey: PROJECT_KEY,
  name: 'ATLAS 보드',
  columns: [columnTodo, columnDone],
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'NONE' as const,
  // FR-BD-04 — 기본 픽스처는 칸반이라 activeSprint 가 항상 null 이다.
  boardType: 'KANBAN' as const,
  activeSprint: null,
}

const boardSummaryFixture = {
  boardId: BOARD_ID,
  projectKey: PROJECT_KEY,
  name: 'ATLAS 보드',
  boardType: 'KANBAN' as const,
}

const boardCreatedFixture = {
  boardId: BOARD_ID,
  projectKey: PROJECT_KEY,
  name: '새 보드',
  boardType: 'KANBAN' as const,
  columns: [
    {
      columnId: COLUMN_ID_TODO,
      states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
      name: '할 일',
      category: 'TODO' as const,
      displayOrder: 1,
    },
  ],
}

const moveCardResultFixture = {
  issueKey: ISSUE_KEY,
  currentStateKey: 'done',
  version: 2,
  columnId: COLUMN_ID_DONE,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-1. boardDetailSchema — 유효 픽스처 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('boardDetailSchema — 유효 픽스처 파싱', () => {
  it('T-BD-1a: 2개 컬럼·카드 포함·assigneeId null/uuid 혼합 픽스처를 파싱한다', () => {
    const result = boardDetailSchema.safeParse(boardDetailFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.columns).toHaveLength(2)
    expect(result.data.columns[0]?.cards[0]?.assigneeId).toBeNull()
    expect(result.data.columns[1]?.cards[0]?.assigneeId).toBe(ASSIGNEE_UUID)
    expect(result.data.truncated).toBe(false)
    expect(result.data.unplacedCount).toBe(0)
  })

  it('T-BD-1b: category enum 값(TODO/IN_PROGRESS/DONE)이 파싱된다', () => {
    const withInProgress = {
      ...boardDetailFixture,
      columns: [
        columnTodo,
        {
          columnId: 'e5f6a7b8-c9d0-4234-8fab-234567890124',
          states: [{ key: 'in_progress', name: '진행 중', category: 'IN_PROGRESS' }],
          name: '진행 중',
          category: 'IN_PROGRESS' as const,
          displayOrder: 2,
          wipLimit: null,
          wipExceeded: false,
          cards: [],
        },
        columnDone,
      ],
    }
    const result = boardDetailSchema.safeParse(withInProgress)
    expect(result.success).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-2. boardDetailSchema — 잘못된 값 거부
// ─────────────────────────────────────────────────────────────────────────────

describe('boardDetailSchema — 잘못된 값 거부', () => {
  it('T-BD-2a: category에 허용되지 않는 값(FOO)이 있으면 파싱을 거부한다', () => {
    const invalid = {
      ...boardDetailFixture,
      columns: [{ ...columnTodo, category: 'FOO' }],
    }
    const result = boardDetailSchema.safeParse(invalid)
    expect(result.success).toBe(false)
  })

  it('T-BD-2b: boardId 필드 누락 시 파싱을 거부한다', () => {
    const missingBoardId: Record<string, unknown> = { ...boardDetailFixture }
    delete missingBoardId['boardId']
    const result = boardDetailSchema.safeParse(missingBoardId)
    expect(result.success).toBe(false)
  })

  it('T-BD-2c: columns 배열 누락 시 파싱을 거부한다', () => {
    const missingColumns: Record<string, unknown> = { ...boardDetailFixture }
    delete missingColumns['columns']
    const result = boardDetailSchema.safeParse(missingColumns)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-3. boardSummarySchema — 유효 픽스처 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('boardSummarySchema — 유효 픽스처 파싱', () => {
  it('T-BD-3a: boardSummarySchema가 유효 픽스처를 파싱한다', () => {
    const result = boardSummarySchema.safeParse(boardSummaryFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardId).toBe(BOARD_ID)
    expect(result.data.projectKey).toBe(PROJECT_KEY)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-4. boardCreatedSchema — 유효 픽스처 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('boardCreatedSchema — 유효 픽스처 파싱', () => {
  it('T-BD-4a: boardCreatedSchema가 유효 픽스처를 파싱한다', () => {
    const result = boardCreatedSchema.safeParse(boardCreatedFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardId).toBe(BOARD_ID)
    expect(result.data.columns).toHaveLength(1)
    expect(result.data.columns[0]?.category).toBe('TODO')
  })

  it('T-BD-4b: boardCreatedSchema 컬럼에 cards 필드가 없어도 파싱된다', () => {
    // boardCreatedColumnSchema는 cards 없음 (생성 응답용)
    const col = boardCreatedFixture.columns[0]
    expect(col).not.toHaveProperty('cards')
    const result = boardCreatedSchema.safeParse(boardCreatedFixture)
    expect(result.success).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-18. boardCreatedSchema — boardType 필수 계약 (FR-BD-04 D6)
//
// 백엔드 `BoardCreatedResponse.boardType` 은 non-null String 이다(BoardResponses.kt:110).
// `.optional()`/`.default()` 로 때우면 백엔드가 필드를 빠뜨리는 결함이 파싱에서 안 잡히고,
// 화면이 고른 종류가 조용히 증발해도 아무도 모른다.
// ─────────────────────────────────────────────────────────────────────────────

describe('boardCreatedSchema — boardType 필수 계약 (FR-BD-04 D6)', () => {
  it('T-BD-18a: boardType 이 없으면 파싱을 거부한다', () => {
    const withoutBoardType: Record<string, unknown> = { ...boardCreatedFixture }
    delete withoutBoardType['boardType']
    const result = boardCreatedSchema.safeParse(withoutBoardType)
    expect(result.success).toBe(false)
  })
})

describe('boardMetaSchema — boardType 필수 계약 (FR-BD-04 PR ⑤)', () => {
  // 종류를 노출하는 응답이 5개인데 PATCH 응답(boardMetaSchema)만 빠져 있었다.
  // optional 로 두면 백엔드가 필드를 흘려도 프론트가 조용히 통과해 계약이 다시 갈린다.
  it('T-BD-11c: boardType 이 있으면 파싱하고 그대로 노출한다', () => {
    const result = boardMetaSchema.safeParse({
      boardId: BOARD_ID,
      projectKey: PROJECT_KEY,
      name: 'ATLAS 보드',
      swimlaneField: 'ASSIGNEE' as const,
      boardType: 'SCRUM' as const,
    })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardType).toBe('SCRUM')
  })

  it('T-BD-11d: boardType 이 없으면 파싱을 거부한다', () => {
    const result = boardMetaSchema.safeParse({
      boardId: BOARD_ID,
      projectKey: PROJECT_KEY,
      name: 'ATLAS 보드',
      swimlaneField: 'ASSIGNEE' as const,
    })
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-5. moveCardResultSchema — 유효 픽스처 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('moveCardResultSchema — 유효 픽스처 파싱', () => {
  it('T-BD-5a: moveCardResultSchema가 유효 픽스처를 파싱한다', () => {
    const result = moveCardResultSchema.safeParse(moveCardResultFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.issueKey).toBe(ISSUE_KEY)
    expect(result.data.currentStateKey).toBe('done')
    expect(result.data.version).toBe(2)
    expect(result.data.columnId).toBe(COLUMN_ID_DONE)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-6. fetchBoards — GET /api/v1/boards?projectKey=...
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchBoards — GET 경로 + {data:[]} 언랩', () => {
  it('T-BD-6a: projectKey를 쿼리 파라미터로 전달하고 BoardSummary[] 를 반환한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: [boardSummaryFixture] })
      }),
    )
    const result = await fetchBoards(PROJECT_KEY)
    expect(result).toHaveLength(1)
    expect(result[0]?.boardId).toBe(BOARD_ID)
    expect(capturedUrl).toContain('projectKey=ATLAS')
  })

  it('T-BD-6b: projectKey에 특수문자가 있으면 encodeURIComponent 처리된다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: [] })
      }),
    )
    await fetchBoards('MY PROJECT')
    expect(capturedUrl).toContain('projectKey=MY%20PROJECT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-7. fetchBoard — GET /api/v1/boards/{id}
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchBoard — GET /api/v1/boards/{boardId}', () => {
  it('T-BD-7a: boardId 경로로 GET 호출하고 BoardDetail 을 반환한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards/:boardId', ({ request, params }) => {
        capturedUrl = request.url
        expect(params['boardId']).toBe(BOARD_ID)
        return HttpResponse.json({ data: boardDetailFixture })
      }),
    )
    const result = await fetchBoard(BOARD_ID)
    expect(result.boardId).toBe(BOARD_ID)
    expect(result.columns).toHaveLength(2)
    expect(capturedUrl).toContain(BOARD_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-8. createBoard — POST /api/v1/boards
// ─────────────────────────────────────────────────────────────────────────────

describe('createBoard — POST /api/v1/boards', () => {
  it('T-BD-8a: projectKey·name을 body로 POST하고 BoardCreated 를 반환한다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/boards', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: boardCreatedFixture }, { status: 201 })
      }),
    )
    const result = await createBoard(PROJECT_KEY, '새 보드', 'KANBAN')
    expect(result.boardId).toBe(BOARD_ID)
    expect(result.name).toBe('새 보드')
    expect((capturedBody as Record<string, unknown>)['projectKey']).toBe(PROJECT_KEY)
    expect((capturedBody as Record<string, unknown>)['name']).toBe('새 보드')
  })

  it('T-BD-8b: boardType 을 요청 바디에 싣고 응답의 boardType 을 되읽는다 (FR-BD-04 D6)', async () => {
    // 생략하면 백엔드가 KANBAN 으로 채운다(BoardResponses.kt:46) — 화면의 선택이 조용히 증발한다.
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/boards', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(
          { data: { ...boardCreatedFixture, boardType: 'SCRUM' } },
          { status: 201 },
        )
      }),
    )
    const result = await createBoard(PROJECT_KEY, '스크럼 보드', 'SCRUM')
    expect((capturedBody as Record<string, unknown>)['boardType']).toBe('SCRUM')
    expect(result.boardType).toBe('SCRUM')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-9. moveCard — POST /api/v1/boards/{boardId}/cards/{issueKey}/move
// ─────────────────────────────────────────────────────────────────────────────

describe('moveCard — POST /api/v1/boards/{boardId}/cards/{issueKey}/move', () => {
  it('T-BD-9a: 경로·method·body가 올바르고 MoveCardResult를 반환한다', async () => {
    let capturedUrl: string | null = null
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/boards/:boardId/cards/:issueKey/move', async ({ request, params }) => {
        capturedUrl = request.url
        capturedBody = await request.json()
        expect(params['boardId']).toBe(BOARD_ID)
        expect(params['issueKey']).toBe(ISSUE_KEY)
        return HttpResponse.json({ data: moveCardResultFixture })
      }),
    )
    const result = await moveCard(BOARD_ID, ISSUE_KEY, {
      toStateKey: 'done',
      expectedVersion: 1,
    })
    expect(result.issueKey).toBe(ISSUE_KEY)
    expect(result.version).toBe(2)
    // R6·R12 — 요청은 컬럼이 아니라 **상태**를 지목한다. 지라도 컬럼 안의 각 상태를
    // 드롭존으로 그려서 「컬럼으로 드롭」이라는 조작 자체가 없다(J3·J4).
    expect((capturedBody as Record<string, unknown>)['toStateKey']).toBe('done')
    expect(capturedBody).not.toHaveProperty('toColumnId')
    expect((capturedBody as Record<string, unknown>)['expectedVersion']).toBe(1)
    expect(capturedUrl).toContain('/move')
  })

  it('T-BD-9b: resolutionId가 있으면 body에 포함된다', async () => {
    const RESOLUTION_ID = 'e5f6a7b8-c9d0-4345-fabc-345678901235'
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/boards/:boardId/cards/:issueKey/move', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: moveCardResultFixture })
      }),
    )
    await moveCard(BOARD_ID, ISSUE_KEY, {
      toStateKey: 'done',
      expectedVersion: 1,
      resolutionId: RESOLUTION_ID,
    })
    expect((capturedBody as Record<string, unknown>)['resolutionId']).toBe(RESOLUTION_ID)
  })

  it('T-BD-9c: resolutionId가 undefined면 body에 포함되지 않는다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/boards/:boardId/cards/:issueKey/move', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: moveCardResultFixture })
      }),
    )
    await moveCard(BOARD_ID, ISSUE_KEY, {
      toStateKey: 'done',
      expectedVersion: 1,
      resolutionId: undefined,
    })
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'resolutionId')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-11. boardCardSchema — priority 필드 파싱 (FR-BD-03 D4)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardCardSchema — priority 필드 파싱 (FR-BD-03 D4)', () => {
  it('T-BD-11a: priority 정수를 포함한 카드 픽스처를 파싱한다', () => {
    const card = { ...cardWithAssignee, priority: 2 }
    const result = boardCardSchema.safeParse(card)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.priority).toBe(2)
  })

  it('T-BD-11b: priority가 없으면 파싱을 거부한다', () => {
    // priority 필드를 명시적으로 제외한 카드 객체
    const cardWithoutPriority: Record<string, unknown> = {
      issueKey: ISSUE_KEY,
      summary: '테스트 이슈',
      assigneeId: ASSIGNEE_UUID,
      version: 1,
    }
    const result = boardCardSchema.safeParse(cardWithoutPriority)
    expect(result.success).toBe(false)
  })

  it('T-BD-11c: priority가 소수(1.5)이면 파싱을 거부한다', () => {
    const card = { ...cardWithAssignee, priority: 1.5 }
    const result = boardCardSchema.safeParse(card)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-14. boardCardSchema — rank 필드 파싱 (FR-UX-06 PR21 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardCardSchema — rank 필드 파싱 (FR-UX-06 PR21 Task 3)', () => {
  it('T-BD-14a: 유효 LexoRank 문자열을 포함한 카드는 rank 값을 보유한다', () => {
    const card = { ...cardWithAssignee, rank: '0|hzzzzz:' }
    const result = boardCardSchema.safeParse(card)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.rank).toBe('0|hzzzzz:')
  })

  it('T-BD-14b: rank 필드가 응답에 없으면 null로 방어된다 (백엔드 @JsonInclude(NON_NULL) 대비)', () => {
    // rank 필드를 명시적으로 제외한 카드 객체 — 레거시/인라인 mock 응답 재현
    const cardWithoutRank: Record<string, unknown> = { ...cardWithAssignee }
    delete cardWithoutRank['rank']
    const result = boardCardSchema.safeParse(cardWithoutRank)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.rank).toBeNull()
  })

  it('T-BD-14c: rank가 명시적으로 null이면 그대로 null을 반환한다', () => {
    const card = { ...cardWithAssignee, rank: null }
    const result = boardCardSchema.safeParse(card)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.rank).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-15. boardCardSchema — typeKey/labels/originalEstimateSeconds 필수 계약 (FR-UX-14 F14 Task 1)
//
// B2(#346)부터 백엔드가 이 3필드를 항상 전송한다. `.default()`/`.optional()`/`.nullish()` 로
// 조용히 때우면 백엔드가 필드를 빠뜨리는 결함이 파싱 단계에서 잡히지 않는다 — Maxi 확정(2026-08-07).
// ─────────────────────────────────────────────────────────────────────────────

describe('boardCardSchema — typeKey/labels/originalEstimateSeconds 필수 계약 (FR-UX-14 F14)', () => {
  const validCard = cardWithAssignee

  it('typeKey 가 없으면 파싱이 실패한다', () => {
    const withoutType: Record<string, unknown> = { ...validCard }
    delete withoutType['typeKey']
    expect(() => boardCardSchema.parse(withoutType)).toThrow()
  })

  it('labels 가 없으면 파싱이 실패한다 (기본값으로 때우지 않는다)', () => {
    const withoutLabels: Record<string, unknown> = { ...validCard }
    delete withoutLabels['labels']
    expect(() => boardCardSchema.parse(withoutLabels)).toThrow()
  })

  it('originalEstimateSeconds 는 null 을 허용하되 키 자체는 필수다', () => {
    expect(
      boardCardSchema.parse({ ...validCard, originalEstimateSeconds: null }).originalEstimateSeconds,
    ).toBeNull()
    const withoutEstimate: Record<string, unknown> = { ...validCard }
    delete withoutEstimate['originalEstimateSeconds']
    expect(() => boardCardSchema.parse(withoutEstimate)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-12. boardColumnSchema — wipLimit / wipExceeded 필드 파싱 (FR-BD-03 D4)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardColumnSchema — wipLimit/wipExceeded 파싱 (FR-BD-03 D4)', () => {
  const cardWithPriority = { ...cardWithAssignee, priority: 1 }
  const cardNoPriorityNull = { ...cardNoAssignee, priority: 3 }

  it('T-BD-12a: wipLimit=숫자, wipExceeded=false 컬럼을 파싱한다', () => {
    const col = { ...columnTodo, cards: [cardNoPriorityNull], wipLimit: 5, wipExceeded: false }
    const result = boardColumnSchema.safeParse(col)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.wipLimit).toBe(5)
    expect(result.data.wipExceeded).toBe(false)
  })

  it('T-BD-12b: wipLimit=null (무제한) 컬럼을 파싱한다', () => {
    const col = { ...columnTodo, cards: [cardNoPriorityNull], wipLimit: null, wipExceeded: false }
    const result = boardColumnSchema.safeParse(col)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.wipLimit).toBeNull()
  })

  it('T-BD-12c: wipLimit 누락 시 파싱을 거부한다', () => {
    // wipLimit 필드를 포함하지 않은 컬럼 객체를 직접 구성
    const col: Record<string, unknown> = {
      columnId: COLUMN_ID_TODO,
      states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
      name: '할 일',
      category: 'TODO',
      displayOrder: 1,
      wipExceeded: false,
      cards: [cardNoPriorityNull],
    }
    const result = boardColumnSchema.safeParse(col)
    expect(result.success).toBe(false)
  })

  it('T-BD-12d: wipExceeded 누락 시 파싱을 거부한다', () => {
    // wipExceeded 필드를 포함하지 않은 컬럼 객체를 직접 구성
    const col: Record<string, unknown> = {
      columnId: COLUMN_ID_TODO,
      states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
      name: '할 일',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      cards: [cardNoPriorityNull],
    }
    const result = boardColumnSchema.safeParse(col)
    expect(result.success).toBe(false)
  })

  it('T-BD-12e: wipExceeded=true 컬럼을 파싱한다', () => {
    const col = { ...columnTodo, cards: [cardWithPriority], wipLimit: 1, wipExceeded: true }
    const result = boardColumnSchema.safeParse(col)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.wipExceeded).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-13. boardDetailSchema — swimlaneField 필드 파싱 (FR-BD-03 D4)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardDetailSchema — swimlaneField 파싱 (FR-BD-03 D4)', () => {
  const columnWithWip = {
    ...columnTodo,
    cards: [{ ...cardNoAssignee, priority: 2 }],
    wipLimit: null,
    wipExceeded: false,
  }

  it('T-BD-13a: swimlaneField=NONE 보드를 파싱한다', () => {
    const board = { ...boardDetailFixture, columns: [columnWithWip], swimlaneField: 'NONE' }
    const result = boardDetailSchema.safeParse(board)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.swimlaneField).toBe('NONE')
  })

  it('T-BD-13b: swimlaneField=ASSIGNEE 보드를 파싱한다', () => {
    const board = { ...boardDetailFixture, columns: [columnWithWip], swimlaneField: 'ASSIGNEE' }
    const result = boardDetailSchema.safeParse(board)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.swimlaneField).toBe('ASSIGNEE')
  })

  it('T-BD-13c: swimlaneField=PRIORITY 보드를 파싱한다', () => {
    const board = { ...boardDetailFixture, columns: [columnWithWip], swimlaneField: 'PRIORITY' }
    const result = boardDetailSchema.safeParse(board)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.swimlaneField).toBe('PRIORITY')
  })

  it('T-BD-13d: swimlaneField 누락 시 파싱을 거부한다', () => {
    // swimlaneField 필드를 포함하지 않은 보드 객체를 직접 구성
    const board: Record<string, unknown> = {
      boardId: BOARD_ID,
      projectKey: PROJECT_KEY,
      name: 'ATLAS 보드',
      columns: [columnWithWip],
      truncated: false,
      unplacedCount: 0,
      unmappedStates: [],
    }
    const result = boardDetailSchema.safeParse(board)
    expect(result.success).toBe(false)
  })

  it('T-BD-13e: swimlaneField에 허용되지 않는 값(CUSTOM)이면 파싱을 거부한다', () => {
    const board = { ...boardDetailFixture, columns: [columnWithWip], swimlaneField: 'CUSTOM' }
    const result = boardDetailSchema.safeParse(board)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-10. fetchBoard 필터 — query string 조립 (FR-BD-02)
// ─────────────────────────────────────────────────────────────────────────────

const ASSIGNEE_ID_1 = 'a1a1a1a1-b2b2-4c3c-8d4d-e5e5e5e5e5e5'
const COMPONENT_ID_1 = 'c1c1c1c1-d2d2-4e3e-8f4f-a5a5a5a5a5a5'

describe('fetchBoard — filter query string 조립 (FR-BD-02)', () => {
  it('T-BD-10a: assigneeIds + includeUnassigned:true → assignee=<uuid>&assignee=unassigned', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards/:boardId', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: boardDetailFixture })
      }),
    )
    const filter: BoardCardFilterParams = {
      assigneeIds: [ASSIGNEE_ID_1],
      includeUnassigned: true,
      labels: [],
      componentIds: [],
    }
    await fetchBoard(BOARD_ID, filter)
    expect(capturedUrl).not.toBeNull()
    const url = new URL(capturedUrl ?? '')
    const assigneeValues = url.searchParams.getAll('assignee')
    expect(assigneeValues).toContain(ASSIGNEE_ID_1)
    expect(assigneeValues).toContain('unassigned')
  })

  it('T-BD-10b: labels 배열 → label=bug&label=urgent (assigneeIds 비어 있음)', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards/:boardId', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: boardDetailFixture })
      }),
    )
    const filter: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: ['bug', 'urgent'],
      componentIds: [],
    }
    await fetchBoard(BOARD_ID, filter)
    expect(capturedUrl).not.toBeNull()
    const url = new URL(capturedUrl ?? '')
    expect(url.searchParams.getAll('label')).toEqual(['bug', 'urgent'])
    expect(url.searchParams.has('assignee')).toBe(false)
  })

  it('T-BD-10c: componentIds → component=<uuid>', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards/:boardId', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: boardDetailFixture })
      }),
    )
    const filter: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [COMPONENT_ID_1],
    }
    await fetchBoard(BOARD_ID, filter)
    expect(capturedUrl).not.toBeNull()
    const url = new URL(capturedUrl ?? '')
    expect(url.searchParams.getAll('component')).toContain(COMPONENT_ID_1)
  })

  it('T-BD-10d: 빈 필터 전달 시 query param 없는 기본 URL 호출', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards/:boardId', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: boardDetailFixture })
      }),
    )
    const emptyFilter: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    await fetchBoard(BOARD_ID, emptyFilter)
    expect(capturedUrl).not.toBeNull()
    const url = new URL(capturedUrl ?? '')
    expect(url.search).toBe('')
  })

  it('T-BD-10e: filter 인자 미전달 시 query param 없는 기본 URL 호출', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/boards/:boardId', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: boardDetailFixture })
      }),
    )
    await fetchBoard(BOARD_ID)
    expect(capturedUrl).not.toBeNull()
    const url = new URL(capturedUrl ?? '')
    expect(url.search).toBe('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// boardMetaSchema — 스키마 파싱 (FR-BD-03 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardMetaSchema — 유효 픽스처 파싱', () => {
  it('T-BD-11a: boardId/projectKey/name/swimlaneField 모두 파싱된다', () => {
    const fixture = {
      boardId: BOARD_ID,
      projectKey: PROJECT_KEY,
      name: 'ATLAS 보드',
      swimlaneField: 'ASSIGNEE' as const,
      boardType: 'KANBAN' as const,
    }
    const result = boardMetaSchema.safeParse(fixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardId).toBe(BOARD_ID)
    expect(result.data.swimlaneField).toBe('ASSIGNEE')
  })

  it('T-BD-11b: swimlaneField 값이 NONE/ASSIGNEE/PRIORITY 세 가지만 허용된다', () => {
    const valid = ['NONE', 'ASSIGNEE', 'PRIORITY'] as const
    for (const field of valid) {
      const result = boardMetaSchema.safeParse({
        boardId: BOARD_ID,
        projectKey: PROJECT_KEY,
        name: 'ATLAS 보드',
        swimlaneField: field,
        boardType: 'KANBAN' as const,
      })
      expect(result.success, `${field} should be valid`).toBe(true)
    }
    const invalid = boardMetaSchema.safeParse({
      boardId: BOARD_ID,
      projectKey: PROJECT_KEY,
      name: 'ATLAS 보드',
      swimlaneField: 'COMPONENT',
      boardType: 'KANBAN' as const,
    })
    expect(invalid.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateBoardSwimlane — PATCH /api/v1/boards/{id} (FR-BD-03 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('updateBoardSwimlane', () => {
  const boardMetaFixture = {
    boardId: BOARD_ID,
    projectKey: PROJECT_KEY,
    name: 'ATLAS 보드',
    swimlaneField: 'ASSIGNEE' as const,
    boardType: 'KANBAN' as const,
  }

  it('T-BD-12a: PATCH /api/v1/boards/{id}를 body {swimlaneField} 로 호출하고 BoardMeta를 반환한다', async () => {
    let capturedMethod: string | null = null
    let capturedBody: unknown = null

    server.use(
      http.patch('/api/v1/boards/:boardId', async ({ request }) => {
        capturedMethod = request.method
        capturedBody = await request.json()
        return HttpResponse.json({ data: boardMetaFixture })
      }),
    )

    const result = await updateBoardSwimlane(BOARD_ID, 'ASSIGNEE')
    expect(capturedMethod).toBe('PATCH')
    expect(capturedBody).toEqual({ swimlaneField: 'ASSIGNEE' })
    expect(result.boardId).toBe(BOARD_ID)
    expect(result.swimlaneField).toBe('ASSIGNEE')
  })

  it('T-BD-12b: swimlaneField=NONE으로 호출해 NONE을 반환한다', async () => {
    server.use(
      http.patch('/api/v1/boards/:boardId', async () => {
        return HttpResponse.json({
          data: { ...boardMetaFixture, swimlaneField: 'NONE' as const },
        })
      }),
    )

    const result = await updateBoardSwimlane(BOARD_ID, 'NONE')
    expect(result.swimlaneField).toBe('NONE')
  })

  it('T-BD-12c: 비-2xx 응답 시 ApiError가 throw된다', async () => {
    server.use(
      http.patch('/api/v1/boards/:boardId', () => {
        return HttpResponse.json({ code: 'FORBIDDEN', message: 'Access denied' }, { status: 403 })
      }),
    )

    await expect(updateBoardSwimlane(BOARD_ID, 'ASSIGNEE')).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// boardDetailSchema — canDelete 파싱 (FR-BD-01-2d)
//
// 백엔드 BoardDetailResponse.canDelete = IssuePermission.SOFT_DELETE 판정 결과.
// 목록(BoardSummaryResponse)에도 함께 실린다 — 소비처는 캠페인 PR ⑨ 의 사이드바 보드 `⋯` 다.
// 목록 쪽 판정은 아래 T-BD-21 이 별도로 잰다 (조립부가 서로 다르기 때문이다).
// ─────────────────────────────────────────────────────────────────────────────

describe('boardDetailSchema — canDelete 파싱 (FR-BD-01-2d)', () => {
  it('T-BD-15a: canDelete=true 를 파싱한다', () => {
    const result = boardDetailSchema.safeParse({ ...boardDetailFixture, canDelete: true })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.canDelete).toBe(true)
  })

  it('T-BD-15b: canDelete=false 를 파싱한다', () => {
    const result = boardDetailSchema.safeParse({ ...boardDetailFixture, canDelete: false })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.canDelete).toBe(false)
  })

  it('T-BD-15c: canDelete 가 없어도 파싱에 성공하고 삭제 불가로 읽힌다 (fail-closed · 보드 화면이 죽지 않는다)', () => {
    const result = boardDetailSchema.safeParse(boardDetailFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.canDelete ?? false).toBe(false)
  })

  it('T-BD-15d: canDelete 가 boolean 이 아니면 파싱을 거부한다', () => {
    const result = boardDetailSchema.safeParse({ ...boardDetailFixture, canDelete: 'yes' })
    expect(result.success).toBe(false)
  })

  it('T-BD-15e: fetchBoard 가 응답의 canDelete 를 그대로 돌려준다', async () => {
    server.use(
      http.get('/api/v1/boards/:boardId', () => {
        return HttpResponse.json({ data: { ...boardDetailFixture, canDelete: true } })
      }),
    )

    const result = await fetchBoard(BOARD_ID)
    expect(result.canDelete).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateBoardName — PATCH /api/v1/boards/{id} (FR-BD-01-2a)
// ─────────────────────────────────────────────────────────────────────────────

describe('updateBoardName — PATCH /api/v1/boards/{id}', () => {
  const NEW_NAME = '버그 보드'
  const renamedMetaFixture = {
    boardId: BOARD_ID,
    projectKey: PROJECT_KEY,
    name: NEW_NAME,
    swimlaneField: 'NONE' as const,
    boardType: 'KANBAN' as const,
  }

  it('T-BD-16a: PATCH 로 { name } 만 보낸다 — swimlaneField 를 함께 보내지 않는다', async () => {
    let capturedMethod: string | null = null
    let capturedBoardId: string | null = null
    let capturedBody: Record<string, unknown> = {}

    server.use(
      http.patch('/api/v1/boards/:boardId', async ({ request, params }) => {
        capturedMethod = request.method
        capturedBoardId = params['boardId'] as string
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ data: renamedMetaFixture })
      }),
    )

    const result = await updateBoardName(BOARD_ID, NEW_NAME)

    expect(capturedMethod).toBe('PATCH')
    expect(capturedBoardId).toBe(BOARD_ID)
    // 부분 갱신 계약 — 이름만 바꾸려는 요청에 swimlaneField 가 섞이면 백엔드가 두 필드를 모두 갱신한다.
    expect(Object.keys(capturedBody)).toEqual(['name'])
    expect(capturedBody).toEqual({ name: NEW_NAME })
    expect(result.name).toBe(NEW_NAME)
    expect(result.boardId).toBe(BOARD_ID)
  })

  it('T-BD-16b: 404 는 ApiError(status 404) 로 온다', async () => {
    server.use(
      http.patch('/api/v1/boards/:boardId', () => {
        return HttpResponse.json(
          { errorCode: 'AGILE_BOARD_NOT_FOUND', message: '보드를 찾을 수 없습니다' },
          { status: 404 },
        )
      }),
    )

    await expect(updateBoardName(BOARD_ID, NEW_NAME)).rejects.toBeInstanceOf(ApiError)
    await expect(updateBoardName(BOARD_ID, NEW_NAME)).rejects.toMatchObject({ status: 404 })
  })

  it('T-BD-16c: 403 은 ApiError(status 403) 로 온다', async () => {
    server.use(
      http.patch('/api/v1/boards/:boardId', () => {
        return HttpResponse.json(
          { errorCode: 'AGILE_ACCESS_DENIED', message: '권한이 없습니다' },
          { status: 403 },
        )
      }),
    )

    await expect(updateBoardName(BOARD_ID, NEW_NAME)).rejects.toBeInstanceOf(ApiError)
    await expect(updateBoardName(BOARD_ID, NEW_NAME)).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteBoard — DELETE /api/v1/boards/{id} (FR-BD-01-2b)
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteBoard — DELETE /api/v1/boards/{id}', () => {
  it('T-BD-17a: 204 No Content 를 처리한다 — 본문 없는 응답을 파싱하려 들지 않는다', async () => {
    let capturedMethod: string | null = null
    let capturedBoardId: string | null = null

    server.use(
      http.delete('/api/v1/boards/:boardId', ({ request, params }) => {
        capturedMethod = request.method
        capturedBoardId = params['boardId'] as string
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await expect(deleteBoard(BOARD_ID)).resolves.toBeUndefined()
    expect(capturedMethod).toBe('DELETE')
    expect(capturedBoardId).toBe(BOARD_ID)
  })

  it('T-BD-17b: 404 는 ApiError(status 404) 로 온다', async () => {
    server.use(
      http.delete('/api/v1/boards/:boardId', () => {
        return HttpResponse.json(
          { errorCode: 'AGILE_BOARD_NOT_FOUND', message: '보드를 찾을 수 없습니다' },
          { status: 404 },
        )
      }),
    )

    await expect(deleteBoard(BOARD_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(deleteBoard(BOARD_ID)).rejects.toMatchObject({ status: 404 })
  })

  it('T-BD-17c: 403 은 ApiError(status 403) 로 온다', async () => {
    server.use(
      http.delete('/api/v1/boards/:boardId', () => {
        return HttpResponse.json(
          { errorCode: 'AGILE_ACCESS_DENIED', message: '권한이 없습니다' },
          { status: 403 },
        )
      }),
    )

    await expect(deleteBoard(BOARD_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(deleteBoard(BOARD_ID)).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-19. boardDetailSchema — boardType / activeSprint (FR-BD-04 · J5 · J15)
//
// J15 "A scrum board is always made up of two parts - the backlog and the active-sprint board."
// J5  "the board displays only the work items added to the sprint you started"
// → 화면이 스크럼/칸반을 갈라 그리려면 상세 응답이 종류와 활성 스프린트를 실어야 한다.
//
// boardType 은 서버 `BoardDetailResponse.boardType` 이 non-null String 이라 필수로 둔다
// (Maxi 확정 2026-09-02). `.optional()`/`.default()` 로 때우면 백엔드가 필드를 빠뜨려도
// 파싱이 조용히 통과해 칸반으로 오인된 스크럼 보드가 화면에 그려진다.
// ─────────────────────────────────────────────────────────────────────────────

const SPRINT_ID = 'f6a7b8c9-d0e1-4456-9abc-456789012346'

const activeSprintFixture = {
  sprintId: SPRINT_ID,
  name: 'Sprint 3',
  startDate: '2026-09-01',
  endDate: '2026-09-14',
}

describe('boardDetailSchema — boardType 필수 계약 (FR-BD-04)', () => {
  it('T-BD-19a: boardType 이 없으면 파싱을 거부한다', () => {
    const withoutBoardType: Record<string, unknown> = { ...boardDetailFixture }
    delete withoutBoardType['boardType']
    const result = boardDetailSchema.safeParse(withoutBoardType)
    expect(result.success).toBe(false)
  })

  it('T-BD-19b: boardType=SCRUM 보드를 파싱한다', () => {
    const result = boardDetailSchema.safeParse({
      ...boardDetailFixture,
      boardType: 'SCRUM',
      activeSprint: activeSprintFixture,
    })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardType).toBe('SCRUM')
  })

  it('T-BD-19c: 허용되지 않는 boardType(SCRUMBAN)이면 파싱을 거부한다', () => {
    const result = boardDetailSchema.safeParse({ ...boardDetailFixture, boardType: 'SCRUMBAN' })
    expect(result.success).toBe(false)
  })
})

describe('boardDetailSchema — activeSprint 파싱 (FR-BD-04 · J15)', () => {
  it('T-BD-19d: 칸반 보드는 activeSprint=null 로 파싱된다', () => {
    const result = boardDetailSchema.safeParse(boardDetailFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.activeSprint).toBeNull()
  })

  it('T-BD-19e: 스크럼 보드의 activeSprint 4필드를 파싱한다', () => {
    const result = boardDetailSchema.safeParse({
      ...boardDetailFixture,
      boardType: 'SCRUM',
      activeSprint: activeSprintFixture,
    })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.activeSprint?.sprintId).toBe(SPRINT_ID)
    expect(result.data.activeSprint?.name).toBe('Sprint 3')
    expect(result.data.activeSprint?.startDate).toBe('2026-09-01')
    expect(result.data.activeSprint?.endDate).toBe('2026-09-14')
  })

  it('T-BD-19f: 기간 미설정 스프린트는 startDate/endDate 가 null 로 파싱된다', () => {
    const result = boardDetailSchema.safeParse({
      ...boardDetailFixture,
      boardType: 'SCRUM',
      activeSprint: { ...activeSprintFixture, startDate: null, endDate: null },
    })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.activeSprint?.startDate).toBeNull()
    expect(result.data.activeSprint?.endDate).toBeNull()
  })

  it('T-BD-19g: activeSprint 키 자체가 없으면 파싱을 거부한다', () => {
    // agile-planning 모듈은 @JsonInclude(NON_NULL) 이 없어 칸반에서도 키가 null 로 살아 온다
    // (boardCardSchema.originalEstimateSeconds 와 같은 근거). 키가 사라졌다면 그것이 곧 결함이다.
    const withoutActiveSprint: Record<string, unknown> = { ...boardDetailFixture }
    delete withoutActiveSprint['activeSprint']
    const result = boardDetailSchema.safeParse(withoutActiveSprint)
    expect(result.success).toBe(false)
  })

  it('T-BD-19h: activeSprint.sprintId 가 UUID 가 아니면 파싱을 거부한다', () => {
    const result = boardDetailSchema.safeParse({
      ...boardDetailFixture,
      boardType: 'SCRUM',
      activeSprint: { ...activeSprintFixture, sprintId: 'sprint-3' },
    })
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-20. boardSummarySchema — boardType (FR-BD-04)
//
// 보드 선택 드롭다운이 목록만 보고 스크럼/칸반을 구분해야 하므로 요약에도 종류가 실린다.
// ─────────────────────────────────────────────────────────────────────────────

describe('boardSummarySchema — boardType (FR-BD-04)', () => {
  it('T-BD-20a: 목록 요약의 boardType 을 파싱한다', () => {
    const result = boardSummarySchema.safeParse({ ...boardSummaryFixture, boardType: 'SCRUM' })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardType).toBe('SCRUM')
  })

  it('T-BD-20b: boardType 이 없으면 파싱을 거부한다', () => {
    const withoutBoardType: Record<string, unknown> = { ...boardSummaryFixture }
    delete withoutBoardType['boardType']
    const result = boardSummarySchema.safeParse(withoutBoardType)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BD-21. boardSummarySchema — canDelete (FR-BD-01-2d · 캠페인 PR ⑧)
//
// ★ 바로 위 T-BD-20b(boardType 은 없으면 **거부**) 옆에 붙여 둔다. 두 필드의 필수성이 왜 갈리는지가
//   한 화면에 보여야 하기 때문이다.
//   - boardType 이 없는 것은 그 자체가 결함이고, 기본값으로 때우면 스크럼이 칸반으로 오인된다.
//   - canDelete 가 없는 것은 「삭제 못 함」이 옳은 해석이라 기본값이 fail-closed 와 일치한다.
//     기본값이 안전한 쪽과 겹치는 유일한 필드라 optional 이 옳은 선택이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('boardSummarySchema — canDelete (FR-BD-01-2d)', () => {
  it('T-BD-21a: 목록 요약의 canDelete=true 를 파싱한다', () => {
    const result = boardSummarySchema.safeParse({ ...boardSummaryFixture, canDelete: true })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.canDelete).toBe(true)
  })

  it('T-BD-21b: 목록 요약의 canDelete=false 를 파싱한다', () => {
    const result = boardSummarySchema.safeParse({ ...boardSummaryFixture, canDelete: false })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.canDelete).toBe(false)
  })

  it('T-BD-21c: canDelete 가 없어도 파싱에 성공하고 삭제 불가로 읽힌다 (fail-closed)', () => {
    // T-BD-20b 와 정반대의 판정이다 — 여기서 거부하면 필드를 뺀 응답 1건이 보드 스위처 ·
    // 백로그 헤더 · ProjectViewChrome 탭바 3곳을 한꺼번에 지운다.
    const result = boardSummarySchema.safeParse(boardSummaryFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.canDelete ?? false).toBe(false)
  })

  it('T-BD-21d: canDelete 가 boolean 이 아니면 파싱을 거부한다', () => {
    const result = boardSummarySchema.safeParse({ ...boardSummaryFixture, canDelete: 'yes' })
    expect(result.success).toBe(false)
  })
})
