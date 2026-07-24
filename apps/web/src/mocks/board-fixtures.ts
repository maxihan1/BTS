// 칸반 보드 MSW 픽스처 — 기본 시드 데이터 + store 관리 함수 (FR-BD-01 D6, FR-BD-02 D6, FR-UX-01)
import type {
  BoardCard,
  BoardDetail,
  BoardCreated,
} from '@/api/boards'
import type { QuickFilter } from '@/api/board-quick-filters'

// ─────────────────────────────────────────────────────────────────────────────
// FILTER_BOARD 담당자 UUID 상수 — user-fixtures.ts와 동기화
//
// board-fixtures.ts는 모듈 레벨에서 import.meta.env.MODE를 참조하므로
// E2E Node.js 런타임에서 직접 import 불가 (import.meta 접근 오류).
// 따라서 user-fixtures를 import하는 대신 UUID를 인라인 상수로 동기화한다.
// user-fixtures.ts 변경 시 이 두 상수도 함께 업데이트해야 한다.
// ─────────────────────────────────────────────────────────────────────────────

/** userAliceFixture.id 와 동기화 */
const ALICE_USER_ID = 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f'

/** userBobFixture.id 와 동기화 */
const BOB_USER_ID = 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 store 전용 확장 타입 — 필터용 메타. 응답 DTO에 포함되지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW store 내부에서만 사용하는 카드 확장 타입.
 * labels·componentIds는 필터 술어 평가에만 쓰이며 응답 JSON에 포함되지 않는다.
 */
export interface StoredCard extends BoardCard {
  /** 카드에 달린 라벨 목록. GET 응답 BoardCard에는 노출 안 함. */
  labels: string[]
  /** 카드가 속한 컴포넌트 ID 목록. GET 응답 BoardCard에는 노출 안 함. */
  componentIds: string[]
}

/**
 * MSW store 내부 보드 타입.
 * 컬럼 카드가 StoredCard[]라 BoardDetail과 구조적으로 다르다.
 */
export interface StoredBoardDetail {
  boardId: string
  projectKey: string
  name: string
  /** 스윔레인 기준 필드. NONE=없음, ASSIGNEE=담당자별, PRIORITY=우선순위별, EPIC=에픽별 */
  swimlaneField: 'NONE' | 'ASSIGNEE' | 'PRIORITY' | 'EPIC'
  columns: Array<{
    columnId: string
    stateKey: string
    name: string
    category: 'TODO' | 'IN_PROGRESS' | 'DONE'
    displayOrder: number
    /** WIP 제한 수. null이면 무제한. 백엔드 FR-BD-03 D4 신호. */
    wipLimit: number | null
    /** 카드 수가 wipLimit을 초과했는지 여부. 백엔드 FR-BD-03 D4 신호. */
    wipExceeded: boolean
    cards: StoredCard[]
  }>
  truncated: boolean
  unplacedCount: number
  /**
   * 보드 퀵필터 목록 (FR-UX-01). optional — WIP_BOARD/SWIMLANE_BOARD 등 퀵필터를 다루지 않는
   * 기존 fixture를 강제로 갱신하지 않기 위함. 미정의 시 `toResponseDetail`이 빈 배열로 방어한다.
   */
  quickFilters?: QuickFilter[]
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID 생성 헬퍼 — 신규 의존성 금지, crypto.randomUUID 표준 API 사용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod v4 z.string().uuid() 검증을 통과하는 형식을 보장한다.
 *
 * export — board-handlers.ts가 퀵필터 생성(FR-UX-01) 시 신규 filterId 발급에 재사용한다.
 */
export function generateUUID(): string {
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
 * 보드 store — boardId → StoredBoardDetail (컬럼·카드 + 필터 메타 포함).
 * move 핸들러가 변이하고, GET 핸들러가 읽어 BoardCard 필드만 응답에 포함한다.
 */
export let boardStore: Map<string, StoredBoardDetail> = new Map()

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
 * 카드 객체에 필터 메타(labels, componentIds)가 이미 있는지 확인한다.
 *
 * @param card BoardCard 또는 StoredCard
 */
function isStoredCard(card: BoardCard): card is StoredCard {
  return Array.isArray((card as Partial<StoredCard>).labels)
}

/**
 * BoardDetail 또는 StoredBoardDetail을 store에 시드한다.
 * 카드에 labels/componentIds가 없는 경우 빈 배열로 보완해 StoredBoardDetail로 변환한다.
 * 동일 boardId가 이미 있으면 덮어쓴다.
 *
 * @param board 시드할 보드 상세 데이터 (필터 메타 유무 불문)
 */
export function seedBoard(board: BoardDetail | StoredBoardDetail): void {
  const stored: StoredBoardDetail = {
    ...board,
    columns: board.columns.map((col) => ({
      ...col,
      cards: col.cards.map((card): StoredCard => {
        if (isStoredCard(card)) {
          return card
        }
        return { ...card, labels: [], componentIds: [] }
      }),
    })),
  }
  boardStore.set(board.boardId, stored)

  const existing = projectBoardIndex.get(board.projectKey) ?? []
  if (!existing.includes(board.boardId)) {
    projectBoardIndex.set(board.projectKey, [...existing, board.boardId])
  }
}

/**
 * StoredBoardDetail(필터 메타 포함)을 store에 직접 시드한다.
 * 라벨·컴포넌트 필터 테스트용 픽스처에 사용한다.
 * 동일 boardId가 이미 있으면 덮어쓴다.
 *
 * @param board 시드할 보드 상세 데이터 (StoredBoardDetail)
 */
export function seedBoardWithMeta(board: StoredBoardDetail): void {
  boardStore.set(board.boardId, board)

  const existing = projectBoardIndex.get(board.projectKey) ?? []
  if (!existing.includes(board.boardId)) {
    projectBoardIndex.set(board.projectKey, [...existing, board.boardId])
  }
}

/**
 * 새 보드를 store에 추가하고 생성 응답 형식으로 반환한다.
 * POST /api/v1/boards 핸들러가 내부적으로 호출한다.
 *
 * @param projectKey 프로젝트 키
 * @param name 보드 이름
 * @returns 생성된 BoardCreated 응답 + 내부 저장용 StoredBoardDetail
 */
export function createBoardInStore(
  projectKey: string,
  name: string,
): { created: BoardCreated; detail: StoredBoardDetail } {
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

  const detail: StoredBoardDetail = {
    boardId,
    projectKey,
    name,
    swimlaneField: 'NONE',
    columns: createdColumns.map((col) => ({ ...col, wipLimit: null, wipExceeded: false, cards: [] })),
    truncated: false,
    unplacedCount: 0,
    quickFilters: [],
  }

  const created: BoardCreated = {
    boardId,
    projectKey,
    name,
    columns: createdColumns,
  }

  seedBoardWithMeta(detail)

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
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: '20000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'ATLAS-1',
          summary: '첫 번째 이슈 — 로그인 페이지 구현',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          version: 0,
          priority: 1,
          epicKey: null,
          // 유효 LexoRank 문자열 — dnd-kit/sortable 드래그 순서 검증용 (FR-UX-06 PR21)
          rank: '0|hzzzzz:',
        },
        {
          issueKey: 'ATLAS-4',
          summary: '네 번째 이슈 — 보드 뷰 구현',
          assigneeId: null,
          version: 0,
          priority: 4,
          epicKey: null,
          // ATLAS-1보다 뒤 순서 (LexoRank 오름차순)
          rank: '0|i00007:',
        },
      ],
    },
    {
      columnId: '20000000-0000-4000-8000-000000000002',
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'ATLAS-2',
          summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          version: 1,
          priority: 2,
          epicKey: null,
          // rank 미부여 — null 방어 경로 검증용
          rank: null,
        },
      ],
    },
    {
      columnId: '20000000-0000-4000-8000-000000000003',
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'ATLAS-3',
          summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
          assigneeId: null,
          version: 2,
          priority: 3,
          epicKey: null,
          // rank 미부여 — null 방어 경로 검증용
          rank: null,
        },
      ],
    },
  ],
  truncated: false,
  unplacedCount: 0,
  quickFilters: [],
}

/**
 * 필터 검증용 보드 픽스처 (FR-BD-02 D6).
 *
 * 카드 구성.
 *   FILTER-1: assigneeId=alice(ALICE_USER_ID), labels=[bug],                  componentIds=[c1]
 *   FILTER-2: assigneeId=bob(BOB_USER_ID),     labels=[feature],              componentIds=[c1, c2]
 *   FILTER-3: assigneeId=alice(ALICE_USER_ID), labels=[bug, documentation],   componentIds=[c2]
 *   FILTER-4: assigneeId=null(미배정),          labels=[],                     componentIds=[]
 *
 * labels 값은 label-handlers.ts LABEL_SEED에 있는 값과 동기화한다.
 * FILTER-3의 두 번째 라벨을 'documentation'으로 지정한 것은 LABEL_SEED에 'documentation'이 있고
 * 'docs'는 없기 때문이다. E2E S5(복합 AND)에서 자동완성 'doc' prefix → 'documentation' 선택으로 검증한다.
 *
 * 담당자 ID는 user-fixtures.ts의 ALICE_USER_ID / BOB_USER_ID 인라인 상수와 동기화한다.
 * typeahead 검색이 GET /api/v1/users?query=로 사용자 목록을 반환하고
 * 필터 param으로 전달된 assigneeId와 matchesFilter가 직접 비교하므로 UUID 일치가 필수다.
 *
 * componentIds는 component-handlers.ts 자동 시드 UUID와 동기화한다.
 *   '40000000-0000-4000-8000-000000000001' = 컴포넌트A (componentStore 자동 시드 id)
 *   '40000000-0000-4000-8000-000000000002' = 컴포넌트B (componentStore 자동 시드 id)
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장 (boardId, columnId).
 */

/** component-handlers.ts 자동 시드 컴포넌트A UUID — 두 파일 동시 변경 필수 */
const COMPONENT_C1_ID = '40000000-0000-4000-8000-000000000001'
/** component-handlers.ts 자동 시드 컴포넌트B UUID — 두 파일 동시 변경 필수 */
const COMPONENT_C2_ID = '40000000-0000-4000-8000-000000000002'

export const FILTER_BOARD: StoredBoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000002',
  projectKey: 'FILTER',
  name: 'FILTER 보드',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: '30000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'FILTER-1',
          summary: '첫 번째 필터 이슈 — alice 담당, bug 라벨, c1 컴포넌트',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 1,
          epicKey: null,
          rank: null,
          labels: ['bug'],
          componentIds: [COMPONENT_C1_ID],
        },
        {
          issueKey: 'FILTER-2',
          summary: '두 번째 필터 이슈 — bob 담당, feature 라벨, c1+c2 컴포넌트',
          assigneeId: BOB_USER_ID,
          version: 0,
          priority: 2,
          epicKey: null,
          rank: null,
          labels: ['feature'],
          componentIds: [COMPONENT_C1_ID, COMPONENT_C2_ID],
        },
        {
          issueKey: 'FILTER-3',
          summary: '세 번째 필터 이슈 — alice 담당, bug+documentation 라벨, c2 컴포넌트',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 3,
          epicKey: null,
          rank: null,
          labels: ['bug', 'documentation'],
          componentIds: [COMPONENT_C2_ID],
        },
        {
          issueKey: 'FILTER-4',
          summary: '네 번째 필터 이슈 — 미배정, 라벨·컴포넌트 없음',
          assigneeId: null,
          version: 0,
          priority: 4,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
      ],
    },
  ],
  truncated: false,
  unplacedCount: 0,
}

/**
 * WIP 초과 경고 검증용 보드 픽스처 (FR-BD-03 D7 E2E S1).
 *
 * IN PROGRESS 컬럼에 wipLimit=2, wipExceeded=true, 카드 3개.
 * 헤더에 "3/2" 텍스트 + WIP 초과 경고(amber 배지)가 표시됨을 E2E로 검증한다.
 *
 * alice(userId=00000000-0000-4000-8000-000000000001)가 ATLAS 프로젝트 진입 전제.
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 */
export const WIP_BOARD: StoredBoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000003',
  projectKey: 'WIPTEST',
  name: 'WIP 테스트 보드',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: '50000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'WIP-1',
          summary: 'WIP 테스트 이슈 1',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          version: 0,
          priority: 1,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
      ],
    },
    {
      columnId: '50000000-0000-4000-8000-000000000002',
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: 2,
      wipExceeded: true,
      cards: [
        {
          issueKey: 'WIP-2',
          summary: 'WIP 테스트 이슈 2',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          version: 0,
          priority: 2,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'WIP-3',
          summary: 'WIP 테스트 이슈 3',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 3,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'WIP-4',
          summary: 'WIP 테스트 이슈 4',
          assigneeId: BOB_USER_ID,
          version: 0,
          priority: 1,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
      ],
    },
    {
      columnId: '50000000-0000-4000-8000-000000000003',
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
  truncated: false,
  unplacedCount: 0,
}

/**
 * 스윔레인 전환 검증용 보드 픽스처 (FR-BD-03 D7 E2E S3/S4).
 *
 * TODO 컬럼에 alice 담당 1건 + bob 담당 1건 + 우선순위 1/2 혼합.
 * ASSIGNEE 스윔레인: 담당자별 서브그룹("앨리스" displayName / "bob" username) 검증.
 * alice는 displayName="앨리스"로, bob은 displayName=null이므로 username "bob"으로 그룹화된다.
 * PRIORITY 스윔레인: "우선순위 1", "우선순위 2" 서브그룹 검증.
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 */
export const SWIMLANE_BOARD: StoredBoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000004',
  projectKey: 'SWIMTEST',
  name: '스윔레인 테스트 보드',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: '60000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'SWIM-1',
          summary: '스윔레인 테스트 이슈 1 — alice 담당 우선순위1',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 1,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'SWIM-2',
          summary: '스윔레인 테스트 이슈 2 — bob 담당 우선순위2',
          assigneeId: BOB_USER_ID,
          version: 0,
          priority: 2,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
      ],
    },
    {
      columnId: '60000000-0000-4000-8000-000000000002',
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
    {
      columnId: '60000000-0000-4000-8000-000000000003',
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
  truncated: false,
  unplacedCount: 0,
}

/**
 * EPIC 스윔레인 전환 검증용 보드 픽스처 (FR-EP-01 D6/D7 E2E).
 *
 * TODO 컬럼에 에픽A 소속 이슈 1건 + 에픽B 소속 이슈 1건 + 에픽 없음 이슈 1건.
 * EPIC 스윔레인: "SWIMTEST-EP-1" / "SWIMTEST-EP-2" / "에픽 없음" 서브그룹 검증.
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 */
export const EPIC_SWIMLANE_BOARD: StoredBoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000005',
  projectKey: 'EPICTEST',
  name: 'EPIC 스윔레인 테스트 보드',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: '70000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'EPICTEST-1',
          summary: 'EPIC 스윔레인 테스트 이슈 1 — 에픽A 소속',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 1,
          epicKey: 'EPICTEST-EP-1',
          rank: null,
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'EPICTEST-2',
          summary: 'EPIC 스윔레인 테스트 이슈 2 — 에픽B 소속',
          assigneeId: BOB_USER_ID,
          version: 0,
          priority: 2,
          epicKey: 'EPICTEST-EP-2',
          rank: null,
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'EPICTEST-3',
          summary: 'EPIC 스윔레인 테스트 이슈 3 — 에픽 없음',
          assigneeId: null,
          version: 0,
          priority: 3,
          epicKey: null,
          rank: null,
          labels: [],
          componentIds: [],
        },
      ],
    },
    {
      columnId: '70000000-0000-4000-8000-000000000002',
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
    {
      columnId: '70000000-0000-4000-8000-000000000003',
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
  truncated: false,
  unplacedCount: 0,
}

/**
 * 셀 내 순서변경(useReorderCard) 검증용 보드 픽스처 (FR-UX-06 PR21 Task 8 E2E).
 *
 * TODO 컬럼에 alice 담당 2건(같은 셀) + bob 담당 1건(다른 셀)을 배치해
 * ASSIGNEE 스윔레인 활성 시 "같은 셀 내 순서변경"(D4)과 "셀 경계를 넘는 드래그 → noop"(D5)을
 * 하나의 컬럼에서 함께 검증할 수 있게 한다.
 *
 * DEFAULT_BOARD/SWIMLANE_BOARD를 재사용하지 않는 이유 — quick-filter.spec.ts의
 * QUICK_FILTER_PERM_SEED와 동일한 격리 원칙(전역 fixture 공유 금지). 기존 보드에 카드를
 * 추가하면 board-kanban.spec.ts/board-wip-swimlane.spec.ts 등 기존 26개 E2E 스펙의
 * 카드 수·그룹 구성 전제가 흔들릴 위험이 있다.
 *
 * RT-1/RT-2/RT-3은 backlog-fixtures.ts DEFAULT_BACKLOG에 대응 이슈가 없으므로
 * (board-handlers.ts resolveLiveRank의 backlogStore 오버레이 대상 아님) 새로고침 후
 * 순서 유지 검증은 이 보드가 아닌 DEFAULT_BOARD(ATLAS-1/ATLAS-4)로 수행한다.
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 */
export const REORDER_SWIMLANE_BOARD: StoredBoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000007',
  projectKey: 'REORDERTEST',
  name: '순서변경 테스트 보드',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: 'a0000000-0000-4000-8000-000000000001',
      stateKey: 'open',
      name: 'TODO',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        {
          issueKey: 'RT-1',
          summary: '순서변경 테스트 이슈 1 — alice 담당(같은 셀)',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 1,
          epicKey: null,
          rank: '0|hzzzzz:',
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'RT-2',
          summary: '순서변경 테스트 이슈 2 — alice 담당(같은 셀)',
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 2,
          epicKey: null,
          rank: '0|i00007:',
          labels: [],
          componentIds: [],
        },
        {
          issueKey: 'RT-3',
          summary: '순서변경 테스트 이슈 3 — bob 담당(다른 셀)',
          assigneeId: BOB_USER_ID,
          version: 0,
          priority: 3,
          epicKey: null,
          rank: '0|i00010:',
          labels: [],
          componentIds: [],
        },
      ],
    },
    {
      columnId: 'a0000000-0000-4000-8000-000000000002',
      stateKey: 'in_progress',
      name: 'IN PROGRESS',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
    {
      columnId: 'a0000000-0000-4000-8000-000000000003',
      stateKey: 'done',
      name: 'DONE',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
  truncated: false,
  unplacedCount: 0,
  quickFilters: [],
}

// 모듈 로드 시 기본 보드와 필터 보드를 자동 시드한다 — notification-policy-handlers buildSeedStore() 패턴 동일.
// dev(pnpm dev) · E2E 진입 시 boardStore가 비어 있어 생성 폼이 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedBoard(DEFAULT_BOARD)
  seedBoardWithMeta(FILTER_BOARD)
  seedBoardWithMeta(WIP_BOARD)
  seedBoardWithMeta(SWIMLANE_BOARD)
  seedBoardWithMeta(EPIC_SWIMLANE_BOARD)
  seedBoardWithMeta(REORDER_SWIMLANE_BOARD)
}
