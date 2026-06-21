// 칸반 보드 API 클라이언트 단위 테스트 — Zod 스키마 계약 + fetch 함수 검증 (FR-BD-01/02)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  boardSummarySchema,
  boardDetailSchema,
  boardCreatedSchema,
  moveCardResultSchema,
  fetchBoards,
  fetchBoard,
  createBoard,
  moveCard,
  type BoardCardFilterParams,
} from './boards'

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
}

const cardNoAssignee = {
  issueKey: 'ATLAS-2',
  summary: '테스트 이슈 2',
  assigneeId: null,
  version: 2,
}

const columnTodo = {
  columnId: COLUMN_ID_TODO,
  stateKey: 'todo',
  name: '할 일',
  category: 'TODO' as const,
  displayOrder: 1,
  cards: [cardNoAssignee],
}

const columnDone = {
  columnId: COLUMN_ID_DONE,
  stateKey: 'done',
  name: '완료',
  category: 'DONE' as const,
  displayOrder: 3,
  cards: [cardWithAssignee],
}

const boardDetailFixture = {
  boardId: BOARD_ID,
  projectKey: PROJECT_KEY,
  name: 'ATLAS 보드',
  columns: [columnTodo, columnDone],
  truncated: false,
  unplacedCount: 0,
}

const boardSummaryFixture = {
  boardId: BOARD_ID,
  projectKey: PROJECT_KEY,
  name: 'ATLAS 보드',
}

const boardCreatedFixture = {
  boardId: BOARD_ID,
  projectKey: PROJECT_KEY,
  name: '새 보드',
  columns: [
    {
      columnId: COLUMN_ID_TODO,
      stateKey: 'todo',
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
          stateKey: 'in_progress',
          name: '진행 중',
          category: 'IN_PROGRESS' as const,
          displayOrder: 2,
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
    const result = await createBoard(PROJECT_KEY, '새 보드')
    expect(result.boardId).toBe(BOARD_ID)
    expect(result.name).toBe('새 보드')
    expect((capturedBody as Record<string, unknown>)['projectKey']).toBe(PROJECT_KEY)
    expect((capturedBody as Record<string, unknown>)['name']).toBe('새 보드')
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
      toColumnId: COLUMN_ID_DONE,
      expectedVersion: 1,
    })
    expect(result.issueKey).toBe(ISSUE_KEY)
    expect(result.version).toBe(2)
    expect((capturedBody as Record<string, unknown>)['toColumnId']).toBe(COLUMN_ID_DONE)
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
      toColumnId: COLUMN_ID_DONE,
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
      toColumnId: COLUMN_ID_DONE,
      expectedVersion: 1,
      resolutionId: undefined,
    })
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'resolutionId')).toBe(false)
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
