// 칸반 보드 MSW 픽스처 — 기본 시드 데이터 + store 관리 함수 (FR-BD-01 D6)
import type {
  BoardDetail,
  BoardCreated,
} from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// UUID 생성 헬퍼 — 신규 의존성 금지, crypto.randomUUID 표준 API 사용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod v4 z.string().uuid() 검증을 통과하는 형식을 보장한다.
 */
function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Math.random 폴백 — RFC4122 v4 형식 (version=4, variant=8~b)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16)
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글 — localStorage 플래그 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키.
 * 이 키가 'true'이면 move 핸들러가 정상 200 대신 409 충돌 응답을 반환한다.
 *
 * Playwright addInitScript로 goto 전에 설정하면
 * 첫 move fetch 시점부터 적용된다 (e2e-msw-scenario-toggle-localstorage-flag 패턴).
 */
export const LS_KEY_BOARD_CONFLICT = '__bts_e2e_board_conflict'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 store — boardId → BoardDetail (컬럼·카드 포함).
 * move 핸들러가 변이하고, GET 핸들러가 읽는다.
 */
export let boardStore: Map<string, BoardDetail> = new Map()

/**
 * projectKey → boardId 목록 store.
 * GET /api/v1/boards?projectKey= 핸들러가 읽는다.
 */
export let projectBoardIndex: Map<string, string[]> = new Map()

// ─────────────────────────────────────────────────────────────────────────────
// store 관리 함수 — E2E addInitScript 또는 테스트 setup에서 호출
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 store와 projectKey 인덱스를 초기 상태로 리셋한다.
 * 각 테스트 afterEach에서 호출해 테스트 간 격리를 보장한다.
 */
export function resetBoardStore(): void {
  boardStore = new Map()
  projectBoardIndex = new Map()
}

/**
 * BoardDetail을 store에 시드한다.
 * 동일 boardId가 이미 있으면 덮어쓴다.
 *
 * @param board 시드할 보드 상세 데이터
 */
export function seedBoard(board: BoardDetail): void {
  boardStore.set(board.boardId, board)

  const existing = projectBoardIndex.get(board.projectKey) ?? []
  if (!existing.includes(board.boardId)) {
    projectBoardIndex.set(board.projectKey, [...existing, board.boardId])
  }
}

/**
 * 새 BoardDetail을 store에 추가하고 생성 응답 형식으로 반환한다.
 * POST /api/v1/boards 핸들러가 내부적으로 호출한다.
 *
 * @param projectKey 프로젝트 키
 * @param name 보드 이름
 * @returns 생성된 BoardCreated 응답 + 내부 저장용 BoardDetail
 */
export function createBoardInStore(
  projectKey: string,
  name: string,
): { created: BoardCreated; detail: BoardDetail } {
  const boardId = generateUUID()
  const todoColumnId = generateUUID()
  const inProgressColumnId = generateUUID()
  const doneColumnId = generateUUID()

  const createdColumns: BoardCreated['columns'] = [
    {
      columnId: todoColumnId,
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
    },
    {
      columnId: inProgressColumnId,
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
    },
    {
      columnId: doneColumnId,
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
    },
  ]

  const detail: BoardDetail = {
    boardId,
    projectKey,
    name,
    columns: createdColumns.map((col) => ({ ...col, cards: [] })),
    truncated: false,
    unplacedCount: 0,
  }

  const created: BoardCreated = {
    boardId,
    projectKey,
    name,
    columns: createdColumns,
  }

  seedBoard(detail)

  return { created, detail }
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — alice(userId=00000000-0000-4000-8000-000000000001) 로그인 기준
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 기본 보드 픽스처 — ATLAS 프로젝트, 3컬럼, 카드 3개.
 *
 * UUID는 RFC4122 v4 형식(version=4, variant=8) — Zod v4 z.string().uuid() 통과 보장.
 * alice(userId=00000000-0000-4000-8000-000000000001)가 ATLAS 프로젝트 BROWSE 가능 전제.
 */
export const DEFAULT_BOARD: BoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  columns: [
    {
      columnId: '20000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      cards: [
        {
          issueKey: 'ATLAS-1',
          summary: '첫 번째 이슈 — 로그인 페이지 구현',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          version: 0,
        },
        {
          issueKey: 'ATLAS-4',
          summary: '네 번째 이슈 — 보드 뷰 구현',
          assigneeId: null,
          version: 0,
        },
      ],
    },
    {
      columnId: '20000000-0000-4000-8000-000000000002',
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      cards: [
        {
          issueKey: 'ATLAS-2',
          summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          version: 1,
        },
      ],
    },
    {
      columnId: '20000000-0000-4000-8000-000000000003',
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
      cards: [
        {
          issueKey: 'ATLAS-3',
          summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
          assigneeId: null,
          version: 2,
        },
      ],
    },
  ],
  truncated: false,
  unplacedCount: 0,
}
