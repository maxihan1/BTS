// 칸반 보드 MSW 픽스처 — 기본 시드 데이터 + store 관리 함수 (FR-BD-01 D6, FR-BD-02 D6, FR-UX-01)
import type {
  ActiveSprint,
  BoardCard,
  BoardDetail,
  BoardCreated,
  BoardType,
} from '@/api/boards'
import type { QuickFilter } from '@/api/board-quick-filters'
// FR-UX-06 PR21 Task 8 — 셀 내 순서변경(useReorderCard) E2E 지원.
// PATCH /api/v1/issues/:key/rank(backlog-handlers.ts rerankIssueHandler)는 issueKey를
// backlogStore(issue-tracking BC 백로그 mock) 전체에서 탐색해 없으면 404(ISSUE_NOT_FOUND)를
// 반환한다 — 즉 보드 카드가 reorder되려면 같은 issueKey가 backlogStore에도 있어야 한다
// (board-fixtures.ts 자체 시드만으로는 rerank가 항상 404로 실패해 낙관적 업데이트가 롤백된다).
// seedBacklog는 backlog-fixtures.ts의 공개 API이므로 그 파일을 수정하지 않고 호출만 한다.
import { seedBacklog, ATLAS_DEFAULT_BOARD_ID } from './backlog-fixtures'
import type { StoredBacklogProject } from './backlog-fixtures'
// FILTER_BOARD 담당자 UUID — 정본은 auth-fixtures.ts.
// 예전에는 "board-fixtures 가 import.meta.env.MODE 를 참조해 E2E 에서 import 불가" 라는 이유로
// UUID 를 인라인 복사했는데, 그 제약은 **이 파일을 남이 import 할 때**의 제약이지 이 파일이 남을
// import 할 때의 제약이 아니다. auth-fixtures 는 import.meta 를 쓰지 않으므로 그대로 참조한다.
// 복사본을 두면 정본이 바뀌어도 조용히 어긋난 채 테스트가 전부 초록으로 남는다.
import { ALICE_USER_ID, BOB_USER_ID } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 store 전용 확장 타입 — 필터용 메타. 응답 DTO에 포함되지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW store 내부에서만 사용하는 카드 확장 타입.
 * labels는 BoardCard(FR-UX-14 B2 #346)의 정식 필드이자 필터 술어 평가에도 쓰인다.
 * componentIds는 store 전용 필터 메타이며 응답 JSON(BoardCard)에 포함되지 않는다.
 */
export interface StoredCard extends BoardCard {
  /** 카드가 속한 컴포넌트 ID 목록. GET 응답 BoardCard에는 노출 안 함(store 전용 필터 메타). */
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
  /**
   * 삭제 권한 보유 여부 (FR-BD-01-2d). 백엔드는 IssuePermission.SOFT_DELETE 판정 결과를 싣는다.
   * optional — 미정의 시 `toResponseDetail`이 true로 응답한다. mock 기본 로그인 사용자 alice가
   * SOFT_DELETE를 보유하기 때문이다(issue-permission-fixtures.ts adminPermissionsFixture).
   * 권한 없는 화면을 시드하려면 명시적으로 false를 준다.
   */
  canDelete?: boolean
  /**
   * 보드 종류 (FR-BD-04). 생성 경로(`createBoardInStore`)가 채우고 GET 상세 응답에 그대로 실린다.
   * optional — quickFilters/canDelete와 같은 이유다. 필수로 두면 종류 개념 이전에 만들어진
   * 기존 fixture 전부와 `seedBoard(BoardDetail)` 경로가 한꺼번에 타입 에러가 된다.
   *
   * ⚠️ optional 이라 **컴파일러가 시드의 누락을 잡지 못한다**. 「시드 전량이 boardType 을
   * 명시한다」는 `board-handlers.test.ts` 의 SEEDED_BOARDS 표가 대신 잰다 — 시드를 늘리면
   * 그 표에도 추가한다.
   */
  boardType?: BoardType
  /**
   * 활성 스프린트 (FR-BD-04). 칸반 보드는 항상 null 이고, 미정의도 응답에서 null 로 내려간다.
   *
   * 스키마(`boardDetailSchema.activeSprint`)는 **키 자체가 필수**라 `toResponseDetail` 이
   * 반드시 채운다 — store 가 값을 안 들고 있으면 `undefined` 가 새어 나가 파싱이 통째로 실패한다.
   */
  activeSprint?: ActiveSprint | null
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
 * 카드 객체에 store 전용 필터 메타(componentIds)가 이미 있는지 확인한다.
 *
 * labels는 FR-UX-14 B2(#346)부터 BoardCard 정식 필드라 항상 존재한다 — componentIds만이
 * StoredCard 여부를 가르는 신호다(labels로 판별하면 API 응답 그대로인 BoardCard도 항상
 * true가 되어 componentIds 보완이 누락된다).
 *
 * @param card BoardCard 또는 StoredCard
 */
function isStoredCard(card: BoardCard): card is StoredCard {
  return Array.isArray((card as Partial<StoredCard>).componentIds)
}

/**
 * BoardDetail 또는 StoredBoardDetail을 store에 시드한다.
 * 카드에 componentIds가 없는 경우 빈 배열로 보완해 StoredBoardDetail로 변환한다.
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
        return { ...card, componentIds: [] }
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
 * 보드의 활성 스프린트 마커를 심는다 (FR-BD-04).
 *
 * ★왜 핸들러 응답이 아니라 store 인가.
 * 스프린트 시작은 **backlog BC 핸들러**가 처리한다. 그 응답만 바꾸면 **다른 요청이 그것을
 * 모른다** — 뒤이은 `GET /api/v1/boards/{id}` 는 시드값(`activeSprint: null`)을 그대로 돌려주고
 * 화면이 시작 전후로 한 픽셀도 변하지 않는다(2026-09-02 D7 E2E S7 실측 · 교훈
 * `msw-derived-behavior-shared-store-e2e`). 파생 동작은 두 요청이 함께 보는 store 를 거쳐야 한다.
 *
 * **보드 종류를 여기서 가리지 않는다.** 백엔드 `sprints.board_id` 에도 종류 제약이 없어
 * 칸반 보드에 붙은 스프린트가 실재하고(`DEFAULT_BACKLOG`), 칸반이 활성 스프린트를 **보여주지
 * 않는** 것은 조회 시점 분기다(`BoardApplicationService.getBoard`). 그 분기는 응답 조립부 소관이다.
 *
 * @param boardId 대상 보드 UUID. store 에 없으면 아무것도 하지 않는다
 * @param activeSprint 심을 활성 스프린트 4필드
 */
export function setBoardActiveSprint(boardId: string, activeSprint: ActiveSprint): void {
  const board = boardStore.get(boardId)
  if (board === undefined) return
  board.activeSprint = activeSprint
}

/**
 * 보드의 활성 스프린트 마커를 지운다 — {@link setBoardActiveSprint} 의 대칭 (FR-BD-04).
 *
 * 완료한 스프린트가 **지금 그 보드의 활성 스프린트일 때만** 지운다. 무조건 지우면 다른
 * 스프린트가 활성인 보드에서 남의 마커를 잃는다 — 시작 핸들러가 상태 전환을 검증하지 않으므로
 * 그 조합이 실제로 만들어진다. UUID 는 `===` 완전 일치로만 판정한다.
 *
 * @param boardId 대상 보드 UUID
 * @param sprintId 완료된 스프린트 UUID
 */
export function clearBoardActiveSprint(boardId: string, sprintId: string): void {
  const board = boardStore.get(boardId)
  if (board === undefined || board.activeSprint?.sprintId !== sprintId) return
  board.activeSprint = null
}

/**
 * 새 보드를 store에 추가하고 생성 응답 형식으로 반환한다.
 * POST /api/v1/boards 핸들러가 내부적으로 호출한다.
 *
 * @param projectKey 프로젝트 키
 * @param name 보드 이름
 * @param boardType 보드 종류 (FR-BD-04). 호출자가 기본값을 정해 넘긴다 — 여기서 다시 기본값을
 *   두면 기본값 정의가 두 곳으로 갈라진다.
 * @returns 생성된 BoardCreated 응답 + 내부 저장용 StoredBoardDetail
 */
export function createBoardInStore(
  projectKey: string,
  name: string,
  boardType: BoardType,
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
    boardType,
    swimlaneField: 'NONE',
    columns: createdColumns.map((col) => ({ ...col, wipLimit: null, wipExceeded: false, cards: [] })),
    truncated: false,
    unplacedCount: 0,
    quickFilters: [],
    // 보드를 만들 수 있는 사용자는 삭제도 할 수 있다는 mock 전제 (FR-BD-01-2d).
    canDelete: true,
  }

  const created: BoardCreated = {
    boardId,
    projectKey,
    name,
    boardType,
    columns: createdColumns,
  }

  seedBoardWithMeta(detail)

  return { created, detail }
}

/**
 * 보드를 store에서 소프트 삭제한다 (FR-BD-01-2b).
 * DELETE /api/v1/boards/{id} 핸들러가 내부적으로 호출한다.
 *
 * boardStore와 projectBoardIndex를 함께 정리한다. 한쪽만 지우면 목록 조회가 유령 ID를 읽어
 * 보드가 사라지지 않은 것처럼 보인다(msw-mutation-stateful-refetch).
 * 백엔드가 soft delete라 실물 행은 남지만, mock은 조회 경로에서 사라지는 것만 재현하면 된다.
 *
 * @param boardId 삭제할 보드 UUID
 * @returns 실제로 지웠으면 true, 애초에 없었으면 false (핸들러가 404 판정에 쓴다)
 */
export function deleteBoardFromStore(boardId: string): boolean {
  const board = boardStore.get(boardId)
  if (board === undefined) {
    return false
  }

  boardStore.delete(boardId)

  const remaining = (projectBoardIndex.get(board.projectKey) ?? []).filter((id) => id !== boardId)
  projectBoardIndex.set(board.projectKey, remaining)

  return true
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
  // 값의 정본은 backlog-fixtures.ts 다 — DEFAULT_BACKLOG 의 스프린트가 이 보드에 귀속된다.
  // import 방향이 board-fixtures → backlog-fixtures 한 방향뿐이라 상수가 그쪽에 산다.
  boardId: ATLAS_DEFAULT_BOARD_ID,
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  swimlaneField: 'NONE',
  // 기존 보드 E2E 전량이 칸반 동작을 전제한다 — 스크럼으로 바꾸면 활성 스프린트 분기를 타
  // 카드가 사라진다 (FR-BD-04).
  boardType: 'KANBAN',
  // 칸반 보드는 스프린트 개념이 없다. 스키마가 키를 필수로 요구하므로 명시한다.
  activeSprint: null,
  // alice가 ATLAS에서 SOFT_DELETE를 보유하므로 true (FR-BD-01-2d).
  canDelete: true,
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
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 1,
          epicKey: null,
          // 유효 LexoRank 문자열 — dnd-kit/sortable 드래그 순서 검증용 (FR-UX-06 PR21)
          rank: '0|hzzzzz:',
          typeKey: 'story',
          // 라벨 2개 — FR-UX-14 D7 E2E 라벨 칩 렌더링 검증용 (라벨 수 0/2/4 최소 1건씩)
          labels: ['frontend', 'backend'],
          // 추정 있음 — FR-UX-14 D7 E2E 추정 배지 렌더링 검증용 (null/9000 최소 1건씩)
          originalEstimateSeconds: 9000,
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
          typeKey: 'task',
          // 라벨 없음 — 라벨 칩 미노출 경로 검증용
          labels: [],
          // 미추정 — 추정 배지 미노출 경로 검증용
          originalEstimateSeconds: null,
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
          assigneeId: ALICE_USER_ID,
          version: 1,
          priority: 2,
          epicKey: null,
          // rank 미부여 — null 방어 경로 검증용
          rank: null,
          typeKey: 'bug',
          labels: ['frontend'],
          originalEstimateSeconds: 3600,
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
          typeKey: 'task',
          // 라벨 4개 — 라벨 수 0/2/4 최소 1건씩 요건의 4건 케이스
          labels: ['frontend', 'backend', 'testing', 'documentation'],
          originalEstimateSeconds: null,
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
  // 칸반 동작 전제 시드 — 종류를 바꾸면 이 보드를 쓰는 E2E 가 활성 스프린트 분기를 탄다 (FR-BD-04).
  boardType: 'KANBAN',
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
          typeKey: 'bug',
          labels: ['bug'],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: ['feature'],
          originalEstimateSeconds: 1800,
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
          typeKey: 'story',
          labels: ['bug', 'documentation'],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
  // 칸반 동작 전제 시드 — 종류를 바꾸면 이 보드를 쓰는 E2E 가 활성 스프린트 분기를 탄다 (FR-BD-04).
  boardType: 'KANBAN',
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
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 1,
          epicKey: null,
          rank: null,
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          assigneeId: ALICE_USER_ID,
          version: 0,
          priority: 2,
          epicKey: null,
          rank: null,
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
  // 칸반 동작 전제 시드 — 종류를 바꾸면 이 보드를 쓰는 E2E 가 활성 스프린트 분기를 탄다 (FR-BD-04).
  boardType: 'KANBAN',
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
  // 칸반 동작 전제 시드 — 종류를 바꾸면 이 보드를 쓰는 E2E 가 활성 스프린트 분기를 탄다 (FR-BD-04).
  boardType: 'KANBAN',
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
 * TODO 컬럼에 alice 담당 2건 + bob 담당 1건을 배치한다. **픽스처 자체의 swimlaneField는 'NONE'**
 * 이고, 카드 summary의 "(같은 셀)/(다른 셀)" 라벨은 **E2E가 UI에서 ASSIGNEE 스윔레인으로 토글한
 * 뒤에만** 성립하는 뷰 의존 속성이다(담당자별 서브그룹 = 셀). 그렇게 ASSIGNEE 스윔레인 활성 시
 * "같은 셀 내 순서변경"(D4)과 "셀 경계를 넘는 드래그 → noop"(D5)을 한 컬럼에서 함께 검증한다.
 *
 * DEFAULT_BOARD/SWIMLANE_BOARD를 재사용하지 않는 이유 — quick-filter.spec.ts의
 * QUICK_FILTER_PERM_SEED와 동일한 격리 원칙(전역 fixture 공유 금지). 기존 보드에 카드를
 * 추가하면 board-kanban.spec.ts/board-wip-swimlane.spec.ts 등 기존 26개 E2E 스펙의
 * 카드 수·그룹 구성 전제가 흔들릴 위험이 있다.
 *
 * RT-1/RT-2/RT-3은 이 파일 하단의 REORDER_BACKLOG로 backlogStore에도 짝을 맞춰 시드된다 —
 * rerankIssueHandler(backlog-handlers.ts)가 issueKey를 backlogStore에서 탐색하므로, 이 시드가
 * 없으면 PATCH /api/v1/issues/:key/rank가 항상 404(ISSUE_NOT_FOUND)로 실패해 낙관적 순서변경이
 * 롤백된다(E2E로 실측 확인 — board-reorder.spec.ts 작성 중 발견한 실제 갭).
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 */
export const REORDER_SWIMLANE_BOARD: StoredBoardDetail = {
  boardId: '10000000-0000-4000-8000-000000000007',
  // 칸반 동작 전제 시드 — 종류를 바꾸면 이 보드를 쓰는 E2E 가 활성 스프린트 분기를 탄다 (FR-BD-04).
  boardType: 'KANBAN',
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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

/**
 * REORDER_SWIMLANE_BOARD(RT-1/RT-2/RT-3)와 짝을 이루는 backlogStore 시드 (FR-UX-06 PR21 Task 8).
 *
 * rerankIssueHandler(backlog-handlers.ts)가 issueKey를 backlogStore에서 탐색하므로,
 * 이 시드가 없으면 useReorderCard의 PATCH /api/v1/issues/:key/rank 호출이 항상
 * 404(ISSUE_NOT_FOUND)로 실패해 낙관적 순서변경이 롤백된다(E2E로 실측 확인).
 * rank 값은 REORDER_SWIMLANE_BOARD 카드와 동일 문자열로 맞춰 두 store의 초기 순서가
 * 일치하도록 한다(board-handlers.ts resolveLiveRank 오버레이와 정합).
 */
const REORDER_BACKLOG: StoredBacklogProject = {
  projectKey: 'REORDERTEST',
  backlog: [
    {
      key: 'RT-1',
      summary: '순서변경 테스트 이슈 1 — alice 담당(같은 셀)',
      currentStateKey: 'open',
      assigneeId: ALICE_USER_ID,
      priority: 1,
      rank: '0|hzzzzz:',
      version: 0,
      epicKey: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    },
    {
      key: 'RT-2',
      summary: '순서변경 테스트 이슈 2 — alice 담당(같은 셀)',
      currentStateKey: 'open',
      assigneeId: ALICE_USER_ID,
      priority: 2,
      rank: '0|i00007:',
      version: 0,
      epicKey: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    },
    {
      key: 'RT-3',
      summary: '순서변경 테스트 이슈 3 — bob 담당(다른 셀)',
      currentStateKey: 'open',
      assigneeId: BOB_USER_ID,
      priority: 3,
      rank: '0|i00010:',
      version: 0,
      epicKey: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    },
  ],
  sprints: [],
  truncated: false,
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
  seedBacklog(REORDER_BACKLOG)
}
