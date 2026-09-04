// 칸반 보드 REST API 클라이언트 — Zod 스키마 + fetch 함수 (FR-BD-01/02/03, FR-UX-01)
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'
import { quickFilterSchema } from './board-quick-filters'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 파싱 (resolutions.ts 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/** backend 공통 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 종류 enum 스키마.
 * 백엔드 `BoardType` enum(SCRUM/KANBAN) 대응 — 값이 늘면 백엔드가 먼저 늘고 여기가 따라간다.
 *
 * 선언이 이 자리인 이유. `boardSummarySchema`·`boardDetailSchema`·`boardCreatedSchema` 세 곳이
 * 참조하므로 **첫 소비자보다 위**에 있어야 한다. 아래로 내리면 `const` TDZ 로 TS2448 이 난다.
 */
const boardTypeSchema = z.enum(['SCRUM', 'KANBAN'])

/**
 * 보드 목록 단건 요약 스키마.
 * 백엔드 `BoardSummaryResponse` DTO 대응.
 */
export const boardSummarySchema = z.object({
  /** 보드 UUID */
  boardId: z.string().uuid(),
  /** 프로젝트 키. 예: "ATLAS" */
  projectKey: z.string().min(1),
  /** 보드 표시 이름 */
  name: z.string().min(1),
  /**
   * 보드 종류. 목록만 보고 스크럼/칸반을 갈라야 하는 화면(보드 선택기)의 유일한 근거다 (FR-BD-04).
   * 백엔드 `BoardSummaryResponse.boardType` 이 non-null String 이라 필수로 둔다.
   */
  boardType: boardTypeSchema,
})

/**
 * 보드 카드(이슈) 스키마.
 * 백엔드 `BoardCardResponse` DTO 대응.
 */
export const boardCardSchema = z.object({
  /** 이슈 키. 예: "ATLAS-1" */
  issueKey: z.string().min(1),
  /** 이슈 제목 */
  summary: z.string(),
  /** 담당자 UUID. 미배정 시 null */
  assigneeId: z.string().uuid().nullable(),
  /** 낙관적 잠금(Optimistic Lock) 버전 번호 */
  version: z.number().int(),
  /** 우선순위 정수. 값이 작을수록 우선순위 높음. 백엔드 FR-BD-03 D4 신호. */
  priority: z.number().int(),
  /**
   * 이슈가 속한 에픽 이슈 키. 에픽 없는 이슈는 null.
   * 백엔드 BoardCardResponse.epicKey — 항상 키 직렬화(null 포함).
   * 동일 프로젝트 에픽만 포함 (cross-project 에픽은 null).
   * .default(null) — 기존 인라인 mock 방어용. 백엔드는 항상 키를 전송.
   * FR-EP-01 D6/D7 EPIC 스윔레인 근거 필드.
   */
  epicKey: z.string().nullable().default(null),
  /**
   * LexoRank 문자열. 아직 rank 미부여 시 null.
   * 백엔드 @JsonInclude(NON_NULL) 방어 — nullish 처리
   * (apps/web/src/api/backlog.ts backlogIssueSchema.rank 선례와 동일 패턴).
   * FR-UX-06 PR21 — @dnd-kit/sortable 드래그 순서 유지 근거 필드.
   *
   * rank는 issue-tracking BC가 소유하는 필드의 미러다. BoardCardResponse(agile-planning BC)는
   * 조회 편의를 위해 값을 그대로 노출할 뿐 — 쓰기는 이슈 rank 변경 API
   * (PATCH /api/v1/issues/{key}/rank, backlog.ts rerankIssue)를 통해서만 이루어진다.
   * 보드 카드 드래그 앤 드롭도 이 API를 호출해 rank를 갱신해야 한다(직접 소유·변이 금지).
   */
  rank: z.string().nullish().transform((v) => v ?? null),
  /**
   * 이슈 유형 키. 예: "task", "bug", "story".
   * 백엔드 BoardCardResponse.typeKey — 항상 전송(필수·non-null). FR-UX-14 B2 #346.
   */
  typeKey: z.string().min(1),
  /**
   * 라벨 이름 목록. 값이 없어도 빈 배열로 온다(null 아님). FR-UX-14 B2 #346.
   */
  labels: z.array(z.string()),
  /**
   * 최초 추정 시간(초). 미추정이면 null.
   * agile-planning 모듈은 @JsonInclude(NON_NULL)이 적용되지 않아 키가 항상 살아 온다.
   * FR-UX-14 B2 #346.
   */
  originalEstimateSeconds: z.number().int().nullable(),
})

/**
 * 컬럼 카테고리 enum.
 * 백엔드 `ColumnCategory` enum 대응.
 */
const columnCategorySchema = z.enum(['TODO', 'IN_PROGRESS', 'DONE'])

/**
 * 보드 컬럼 스키마.
 * 백엔드 `BoardColumnWithCardsResponse` DTO 대응.
 */
/**
 * 컬럼이 담은 워크플로우 상태 1건.
 * 백엔드 `ColumnStateResponse` DTO 대응 (R11).
 *
 * ★`category` 는 **상태의 것**이지 컬럼의 것이 아니다. 컬럼 `category` 는 담은 상태들의
 * 최댓값이라(R5) 개별 상태와 다를 수 있고, 해결 방안 모달 판정은 **상태**의 값을 읽어야 한다(R13).
 */
export const columnStateSchema = z.object({
  /** 워크플로우 상태 키. 예: `"in_progress"` */
  key: z.string(),
  /** 사용자에게 표시되는 상태 이름 */
  name: z.string(),
  /** 상태의 칸반 카테고리 */
  category: columnCategorySchema,
})

export const boardColumnSchema = z.object({
  /** 컬럼 UUID */
  columnId: z.string().uuid(),
  /**
   * 이 컬럼에 매핑된 워크플로우 상태 목록(0개 이상 · display_order 순). R11.
   * **첫 항목이 「첫 상태」다**(E5) — 백엔드가 이미 그 순서로 보내므로 다시 정렬하지 않는다.
   */
  states: z.array(columnStateSchema),
  /** 컬럼 표시 이름 */
  name: z.string(),
  /** 컬럼 카테고리 (TODO / IN_PROGRESS / DONE) */
  category: columnCategorySchema,
  /** 화면 표시 순서. 1부터 시작 */
  displayOrder: z.number().int(),
  /** 컬럼에 포함된 카드(이슈) 목록 */
  cards: z.array(boardCardSchema),
  /** WIP 제한 수. null이면 무제한. 백엔드 FR-BD-03 D4 신호. */
  wipLimit: z.number().int().nullable(),
  /** 카드 수가 wipLimit을 초과(strictly greater)했는지 여부. 백엔드 FR-BD-03 D4 신호. */
  wipExceeded: z.boolean(),
})

/**
 * 스윔레인 필드 enum 스키마.
 * 백엔드 `SwimlaneField` enum 대응.
 * NONE=스윔레인 없음, ASSIGNEE=담당자별, PRIORITY=우선순위별, EPIC=에픽별.
 */
export const swimlaneFieldSchema = z.enum(['NONE', 'ASSIGNEE', 'PRIORITY', 'EPIC'])

/**
 * 활성 스프린트 요약 스키마 (FR-BD-04).
 * 백엔드 `ActiveSprintResponse` DTO 대응 — **정확히 4필드다**(`goal`·`status`·`version` 없음).
 * 스프린트 상세는 스프린트 API 소관이라 보드 응답이 중복해 싣지 않는다.
 *
 * **칸반 보드는 이 값이 항상 null 이다** — 스프린트라는 개념 자체가 없다.
 * 그래서 소비자는 `boardType` 이 아니라 `activeSprint` 유무로 스프린트 헤더를 분기해도 안전하다.
 * 서버가 필드를 늘리면 여기가 따라 늘리되, 추측으로 미리 채우지 않는다.
 */
export const activeSprintSchema = z.object({
  /** 활성 스프린트 UUID */
  sprintId: z.string().uuid(),
  /** 스프린트 표시 이름. 예: "Sprint 3" */
  name: z.string(),
  /** 시작일(ISO-8601 date). 미설정이면 null */
  startDate: z.string().nullable(),
  /** 종료일(ISO-8601 date). 미설정이면 null */
  endDate: z.string().nullable(),
})

/**
 * 보드 상세 스키마.
 * 백엔드 `BoardDetailResponse` DTO 대응.
 */
export const boardDetailSchema = z.object({
  /** 보드 UUID */
  boardId: z.string().uuid(),
  /** 프로젝트 키 */
  projectKey: z.string(),
  /** 보드 이름 */
  name: z.string(),
  /** 컬럼 목록 (displayOrder asc) */
  columns: z.array(boardColumnSchema),
  /** 카드 수가 cap을 초과해 잘렸는지 여부 */
  truncated: z.boolean(),
  /** 어떤 컬럼에도 배치되지 않은 이슈 수 */
  unplacedCount: z.number().int(),
  /**
   * 어느 컬럼에도 매핑되지 않은 워크플로우 상태 목록 (R8 · J2).
   * 지라의 **Unmapped statuses** 패널에 대응한다 — `unplacedCount` 가 양수인 **이유**다.
   * 레거시 응답 대비 `.default([])`(백엔드 `@JsonInclude` 대비 · epicKey 선례와 같은 패턴).
   */
  unmappedStates: z.array(columnStateSchema).default([]),
  /** 스윔레인 기준 필드. NONE=없음, ASSIGNEE=담당자별, PRIORITY=우선순위별. 백엔드 FR-BD-03 D4 신호. */
  swimlaneField: swimlaneFieldSchema,
  /**
   * 보드 퀵필터 목록(created_at ASC). FR-UX-01.
   * 백엔드가 필드를 생략해도(레거시 응답·인라인 mock) 빈 배열로 방어한다
   * (백엔드 @JsonInclude 대비 .default — memory: FR-EP-01 epicKey 선례 동일 패턴).
   */
  quickFilters: z.array(quickFilterSchema).default([]),
  /**
   * 이 보드를 삭제할 수 있는지 여부 (FR-BD-01-2d).
   * 백엔드 `BoardDetailResponse.canDelete` — `IssuePermission.SOFT_DELETE` 판정 결과다.
   * 목록 응답(`BoardSummaryResponse`)에는 없고 단건 조회에만 실린다.
   *
   * `.optional()` 인 이유 두 가지.
   * 1. 필수로 두면 필드를 생략하는 응답 하나에 파싱이 통째로 실패해 보드 화면 전체가 죽는다.
   * 2. `.default(false)` 로 두면 출력 타입에서 필수가 되어 `BoardDetail` 로 선언된 기존
   *    인라인 픽스처가 전부 타입 에러가 된다 (quickFilters `.default([])` 가 그 전례다).
   *
   * 소비자는 `canDelete === true` 로만 삭제 UI 를 연다 — undefined 는 fail-closed 다.
   */
  canDelete: z.boolean().optional(),
  /**
   * 보드 종류 (FR-BD-04). 화면이 스크럼 헤더를 그릴지 칸반으로 그릴지 가르는 유일한 근거다.
   *
   * `canDelete` 와 달리 **필수**다. 서버 `BoardDetailResponse.boardType` 이 non-null String 이라
   * (`BoardResponses.kt`) 필드가 비면 그것이 곧 결함이고, 기본값으로 때우면 스크럼 보드가
   * 칸반으로 오인돼 활성 스프린트 헤더가 조용히 사라진다 (Maxi 확정 2026-09-02).
   */
  boardType: boardTypeSchema,
  /** 활성 스프린트. 칸반 보드는 항상 null (FR-BD-04). */
  activeSprint: activeSprintSchema.nullable(),
})

/**
 * 보드 생성 응답용 컬럼 스키마.
 * 카드 배열 없음 — 생성 직후에는 빈 보드이므로.
 */
export const boardCreatedColumnSchema = z.object({
  /** 컬럼 UUID */
  columnId: z.string().uuid(),
  /** 이 컬럼에 매핑된 워크플로우 상태 목록(생성 직후에는 시드된 1개). R11. */
  states: z.array(columnStateSchema),
  /** 컬럼 표시 이름 */
  name: z.string(),
  /** 컬럼 카테고리 (TODO / IN_PROGRESS / DONE) */
  category: columnCategorySchema,
  /** 화면 표시 순서 */
  displayOrder: z.number().int(),
})

/**
 * 보드 생성 응답 스키마.
 * 백엔드 `BoardCreatedResponse` DTO 대응.
 */
export const boardCreatedSchema = z.object({
  /** 생성된 보드 UUID */
  boardId: z.string().uuid(),
  /** 프로젝트 키 */
  projectKey: z.string(),
  /** 보드 이름 */
  name: z.string(),
  /**
   * 생성된 보드의 종류. 생성 직후 클라이언트가 종류를 되읽는 유일한 자리다 (FR-BD-04).
   *
   * `.optional()`/`.default()` 로 두지 않는다 — 백엔드 `BoardCreatedResponse.boardType` 은
   * non-null String 이라(`BoardResponses.kt:110`) 필드가 비면 그것이 곧 결함이다.
   * 기본값으로 때우면 화면이 고른 종류가 증발해도 파싱이 조용히 통과한다.
   */
  boardType: boardTypeSchema,
  /** 초기 컬럼 목록 */
  columns: z.array(boardCreatedColumnSchema),
})

/**
 * 카드 이동 결과 스키마.
 * 백엔드 `MoveCardResponse` DTO 대응.
 */
export const moveCardResultSchema = z.object({
  /** 이동된 이슈 키 */
  issueKey: z.string(),
  /** 현재 워크플로우 상태 키 */
  currentStateKey: z.string(),
  /** 갱신된 낙관적 잠금 버전 */
  version: z.number().int(),
  /** 현재 위치한 컬럼 UUID */
  columnId: z.string().uuid(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 — z.infer 사용 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 목록 단건 요약 타입 */
export type BoardSummary = z.infer<typeof boardSummarySchema>

/** 보드 카드(이슈) 타입 */
export type BoardCard = z.infer<typeof boardCardSchema>

/** 보드 컬럼 타입 */
export type BoardColumn = z.infer<typeof boardColumnSchema>

/** 스윔레인 기준 필드 타입. NONE | ASSIGNEE | PRIORITY */
export type SwimlaneField = z.infer<typeof swimlaneFieldSchema>

/** 활성 스프린트 요약 타입. 칸반 보드에서는 `BoardDetail.activeSprint` 가 null 이다 */
export type ActiveSprint = z.infer<typeof activeSprintSchema>

/** 보드 상세 타입 */
export type BoardDetail = z.infer<typeof boardDetailSchema>

/** 보드 종류 타입. SCRUM=스프린트로 일하는 보드, KANBAN=흐름으로 일하는 보드 */
export type BoardType = z.infer<typeof boardTypeSchema>

/** 보드 생성 응답용 컬럼 타입 */
export type BoardCreatedColumn = z.infer<typeof boardCreatedColumnSchema>

/** 보드 생성 응답 타입 */
export type BoardCreated = z.infer<typeof boardCreatedSchema>

/** 카드 이동 결과 타입 */
export type MoveCardResult = z.infer<typeof moveCardResultSchema>

/** 컬럼이 담은 상태 1건 타입 */
export type ColumnState = z.infer<typeof columnStateSchema>

/**
 * 카드 이동 요청 body 타입.
 *
 * ★대상은 **상태**로 지목한다(R6 · R12). 지라는 컬럼 안의 각 상태를 드롭존으로 그려서
 * 「컬럼으로 드롭」이라는 조작 자체가 없고(J3·J4), 서버도 상태를 추론하지 않는다.
 * 백엔드는 `toColumnId` 도 받지만(하위 호환 · R7) 컬럼의 상태가 2개 이상이면 400 이다.
 */
export interface MoveCardBody {
  /** 목표 워크플로우 상태 키 */
  toStateKey: string
  /** 낙관적 잠금 버전 (충돌 감지용) */
  expectedVersion: number
  /** 결의안 UUID. 대상 상태가 DONE 이면 필요. 생략 가능 */
  resolutionId?: string
}

/**
 * 보드 메타 스키마.
 * 백엔드 `BoardMetaResponse` DTO 대응.
 * PATCH /api/v1/boards/{id} 응답에 사용된다 (FR-BD-03 D6).
 */
export const boardMetaSchema = z.object({
  /** 보드 UUID */
  boardId: z.string().uuid(),
  /** 프로젝트 키. 예: "ATLAS" */
  projectKey: z.string().min(1),
  /** 보드 표시 이름 */
  name: z.string().min(1),
  /** 스윔레인 기준 필드. NONE=없음, ASSIGNEE=담당자별, PRIORITY=우선순위별. */
  swimlaneField: swimlaneFieldSchema,
  /**
   * 보드 종류. `"SCRUM"` · `"KANBAN"` (FR-BD-04 PR ⑤).
   *
   * 생성 후 변경 경로가 없어(ADR 편차 X3) PATCH 로 바뀌지는 않지만, 종류를 노출하는 응답 DTO 중
   * 여기만 빠져 있으면 소비자가 「PATCH 응답으로는 종류를 알 수 없다」는 예외를 학습한다.
   * **optional 로 두지 않는다** — 백엔드가 필드를 흘려도 조용히 통과해 계약이 다시 갈린다.
   */
  boardType: boardTypeSchema,
})

/** 보드 메타 타입 (PATCH 응답) */
export type BoardMeta = z.infer<typeof boardMetaSchema>

/**
 * 보드 카드 필터 파라미터.
 * GET /api/v1/boards/{boardId} 의 선택적 쿼리 필터를 표현한다.
 *
 * 백엔드 계약 (FR-BD-02, #168).
 * - assignee: 담당자 UUID. 복수 반복 파라미터. 'unassigned' 센티널로 미배정 포함.
 * - label: 라벨 이름. 복수 반복 파라미터.
 * - component: 컴포넌트 UUID. 복수 반복 파라미터.
 */
export interface BoardCardFilterParams {
  /** 담당자 UUID 목록. 각 항목마다 assignee= 파라미터를 하나씩 추가한다. */
  assigneeIds: string[]
  /** true면 assignee=unassigned 파라미터를 추가해 미배정 카드를 포함한다. */
  includeUnassigned: boolean
  /** 라벨 이름 목록. 각 항목마다 label= 파라미터를 하나씩 추가한다. */
  labels: string[]
  /** 컴포넌트 UUID 목록. 각 항목마다 component= 파라미터를 하나씩 추가한다. */
  componentIds: string[]
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 속한 보드 목록을 조회한다.
 *
 * GET /api/v1/boards?projectKey={projectKey} → `{ data: BoardSummary[] }` 언랩.
 *
 * @param projectKey 프로젝트 키. 예: "ATLAS"
 * @returns BoardSummary[] — 백엔드 `{ data: [...] }` 래퍼를 언래핑해 반환
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchBoards(projectKey: string): Promise<BoardSummary[]> {
  const wrapped = await apiGet(
    `/api/v1/boards?projectKey=${encodeURIComponent(projectKey)}`,
    dataResponseSchema(z.array(boardSummarySchema)),
  )
  return wrapped.data
}

/**
 * 보드 카드 필터 파라미터를 URLSearchParams 기반 query string으로 변환한다.
 *
 * 백엔드 계약 (FR-BD-02).
 * - assigneeIds 각 UUID → `assignee=<uuid>` 반복 파라미터
 * - includeUnassigned=true → `assignee=unassigned` 추가 (센티널 소문자 고정)
 * - labels 각 이름 → `label=<name>` 반복 파라미터
 * - componentIds 각 UUID → `component=<uuid>` 반복 파라미터
 *
 * 모든 배열이 비어 있고 includeUnassigned=false이면 빈 문자열('')을 반환한다.
 * 파라미터가 하나라도 있으면 '?...' 형태로 반환한다.
 *
 * @param filter 필터 파라미터
 * @returns '' 또는 '?key=value&...' 형태의 query string
 */
export function buildBoardFilterQuery(filter: BoardCardFilterParams): string {
  const params = new URLSearchParams()
  for (const id of filter.assigneeIds) {
    params.append('assignee', id)
  }
  if (filter.includeUnassigned) {
    params.append('assignee', 'unassigned')
  }
  for (const label of filter.labels) {
    params.append('label', label)
  }
  for (const id of filter.componentIds) {
    params.append('component', id)
  }
  const qs = params.toString()
  return qs === '' ? '' : `?${qs}`
}

/**
 * 보드 상세 정보를 조회한다.
 *
 * GET /api/v1/boards/{boardId}[?assignee=...&label=...&component=...] → `{ data: BoardDetail }` 언랩.
 * filter가 없거나 모든 필드가 비어 있으면 query string 없이 호출한다.
 *
 * @param boardId 보드 UUID
 * @param filter 선택적 카드 필터 파라미터 (FR-BD-02)
 * @returns BoardDetail — 컬럼·카드 포함 (필터 적용 시 해당 카드만 포함)
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchBoard(boardId: string, filter?: BoardCardFilterParams): Promise<BoardDetail> {
  const qs = filter !== undefined ? buildBoardFilterQuery(filter) : ''
  const wrapped = await apiGet(
    `/api/v1/boards/${boardId}${qs}`,
    dataResponseSchema(boardDetailSchema),
  )
  return wrapped.data
}

/**
 * 새 보드를 생성한다.
 *
 * POST /api/v1/boards → `{ data: BoardCreated }` 언랩.
 *
 * @param projectKey 프로젝트 키
 * @param name 보드 이름
 * @param boardType 보드 종류. **선택 인자가 아니다** — 백엔드가 생략 시 KANBAN 으로 채우므로
 *   (`BoardResponses.kt:46`) 옵셔널로 두면 화면의 선택이 조용히 무시돼도 아무도 모른다.
 * @returns BoardCreated — 생성된 보드 (초기 컬럼 포함)
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function createBoard(
  projectKey: string,
  name: string,
  boardType: BoardType,
): Promise<BoardCreated> {
  const wrapped = await apiPost(
    '/api/v1/boards',
    { projectKey, name, boardType },
    dataResponseSchema(boardCreatedSchema),
  )
  return wrapped.data
}

/**
 * 카드(이슈)를 다른 컬럼으로 이동한다.
 *
 * POST /api/v1/boards/{boardId}/cards/{issueKey}/move → `{ data: MoveCardResult }` 언랩.
 * resolutionId가 undefined면 JSON.stringify가 자동으로 필드를 제외한다.
 *
 * @param boardId 보드 UUID
 * @param issueKey 이슈 키. 예: "ATLAS-1"
 * @param body 이동 요청 body (toColumnId, expectedVersion, resolutionId?)
 * @returns MoveCardResult — 이동 후 상태
 * @throws ApiError 비-2xx 응답 시 (409 OCC 충돌 포함)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function moveCard(
  boardId: string,
  issueKey: string,
  body: MoveCardBody,
): Promise<MoveCardResult> {
  const { toStateKey, expectedVersion, resolutionId } = body
  const requestBody = {
    toStateKey,
    expectedVersion,
    ...(resolutionId !== undefined ? { resolutionId } : {}),
  }
  const wrapped = await apiPost(
    `/api/v1/boards/${boardId}/cards/${encodeURIComponent(issueKey)}/move`,
    requestBody,
    dataResponseSchema(moveCardResultSchema),
  )
  return wrapped.data
}

/**
 * 보드의 스윔레인 기준 필드를 변경한다.
 *
 * PATCH /api/v1/boards/{boardId} body `{ swimlaneField }` → `{ data: BoardMeta }` 언랩.
 *
 * @param boardId 보드 UUID
 * @param swimlaneField 변경할 스윔레인 기준. NONE=없음, ASSIGNEE=담당자별, PRIORITY=우선순위별.
 * @returns BoardMeta — 변경된 보드 메타 정보
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function updateBoardSwimlane(boardId: string, swimlaneField: SwimlaneField): Promise<BoardMeta> {
  const res = await apiFetch(`/api/v1/boards/${boardId}`, {
    method: 'PATCH',
    body: { swimlaneField },
  })
  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  const wrapped = dataResponseSchema(boardMetaSchema).parse(data)
  return wrapped.data
}

/**
 * 보드 이름을 변경한다 (FR-BD-01-2a).
 *
 * PATCH /api/v1/boards/{boardId} body `{ name }` → `{ data: BoardMeta }` 언랩.
 *
 * 백엔드 `UpdateBoardRequest` 는 `JsonNullable` 3-state 라 **보내지 않은 필드는 건드리지 않는다.**
 * 그래서 body 에 `swimlaneField` 를 얹지 않는다 — 얹으면 이름만 바꾸려는 요청이 스윔레인까지
 * 덮어쓴다. 공백 이름·명시 null·빈 바디는 백엔드가 400 으로 막는다.
 *
 * @param boardId 보드 UUID
 * @param name 새 보드 이름
 * @returns BoardMeta — 변경된 보드 메타 정보
 * @throws ApiError 비-2xx 응답 시 (400 검증 실패 · 403 CREATE 권한 없음 · 404 미존재)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function updateBoardName(boardId: string, name: string): Promise<BoardMeta> {
  const res = await apiFetch(`/api/v1/boards/${boardId}`, {
    method: 'PATCH',
    body: { name },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  const wrapped = dataResponseSchema(boardMetaSchema).parse(data)
  return wrapped.data
}

/**
 * 보드를 소프트 삭제한다 (FR-BD-01-2b). 보드에 있던 이슈는 삭제되지 않는다.
 *
 * DELETE /api/v1/boards/{boardId} → **204 No Content**.
 * 본문이 없으므로 응답을 파싱하지 않는다 — `res.json()` 을 부르면 즉시 터진다.
 * 같은 BC 의 `deleteQuickFilter`(board-quick-filters.ts) 와 동일한 관례다.
 *
 * @param boardId 보드 UUID
 * @param signal 요청 취소 신호. 삭제 확인 창의 상한(`lib/delete-timeout.ts`)이 여기로 취소를
 *   흘려보낸다. 선택 인자라 상한 없이 부르는 호출자는 그대로 둔다.
 * @throws ApiError 비-2xx 응답 시 (403 SOFT_DELETE 권한 없음 · 404 미존재/이미 삭제됨)
 * @throws DOMException abort 로 요청이 끊겼을 때 (`AbortError`)
 */
export async function deleteBoard(boardId: string, signal?: AbortSignal): Promise<void> {
  const res = await apiFetch(`/api/v1/boards/${boardId}`, {
    method: 'DELETE',
    signal,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
