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
   */
  rank: z.string().nullish().transform((v) => v ?? null),
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
export const boardColumnSchema = z.object({
  /** 컬럼 UUID */
  columnId: z.string().uuid(),
  /** 워크플로우 상태 키 */
  stateKey: z.string(),
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
  /** 스윔레인 기준 필드. NONE=없음, ASSIGNEE=담당자별, PRIORITY=우선순위별. 백엔드 FR-BD-03 D4 신호. */
  swimlaneField: swimlaneFieldSchema,
  /**
   * 보드 퀵필터 목록(created_at ASC). FR-UX-01.
   * 백엔드가 필드를 생략해도(레거시 응답·인라인 mock) 빈 배열로 방어한다
   * (백엔드 @JsonInclude 대비 .default — memory: FR-EP-01 epicKey 선례 동일 패턴).
   */
  quickFilters: z.array(quickFilterSchema).default([]),
})

/**
 * 보드 생성 응답용 컬럼 스키마.
 * 카드 배열 없음 — 생성 직후에는 빈 보드이므로.
 */
export const boardCreatedColumnSchema = z.object({
  /** 컬럼 UUID */
  columnId: z.string().uuid(),
  /** 워크플로우 상태 키 */
  stateKey: z.string(),
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

/** 보드 상세 타입 */
export type BoardDetail = z.infer<typeof boardDetailSchema>

/** 보드 생성 응답용 컬럼 타입 */
export type BoardCreatedColumn = z.infer<typeof boardCreatedColumnSchema>

/** 보드 생성 응답 타입 */
export type BoardCreated = z.infer<typeof boardCreatedSchema>

/** 카드 이동 결과 타입 */
export type MoveCardResult = z.infer<typeof moveCardResultSchema>

/** 카드 이동 요청 body 타입 */
export interface MoveCardBody {
  /** 목표 컬럼 UUID */
  toColumnId: string
  /** 낙관적 잠금 버전 (충돌 감지용) */
  expectedVersion: number
  /** 결의안 UUID. DONE 카테고리 이동 시 필요. 생략 가능 */
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
 * @returns BoardCreated — 생성된 보드 (초기 컬럼 포함)
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function createBoard(projectKey: string, name: string): Promise<BoardCreated> {
  const wrapped = await apiPost(
    '/api/v1/boards',
    { projectKey, name },
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
  const { toColumnId, expectedVersion, resolutionId } = body
  const requestBody = {
    toColumnId,
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
