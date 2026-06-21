// 칸반 보드 REST API 클라이언트 — Zod 스키마 + fetch 함수 (FR-BD-01)
import { z } from 'zod'
import { apiGet, apiPost } from './client'

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
})

/**
 * 컬럼 카테고리 enum.
 * 백엔드 `ColumnCategory` enum 대응.
 */
const columnCategorySchema = z.enum(['TODO', 'IN_PROGRESS', 'DONE'])

/**
 * 보드 컬럼 스키마.
 * 백엔드 `BoardColumnResponse` DTO 대응.
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
 * 보드 상세 정보를 조회한다.
 *
 * GET /api/v1/boards/{boardId} → `{ data: BoardDetail }` 언랩.
 *
 * @param boardId 보드 UUID
 * @returns BoardDetail — 컬럼·카드 포함
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchBoard(boardId: string): Promise<BoardDetail> {
  const wrapped = await apiGet(
    `/api/v1/boards/${boardId}`,
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
  const requestBody: Record<string, unknown> = { toColumnId, expectedVersion }
  if (resolutionId !== undefined) {
    requestBody['resolutionId'] = resolutionId
  }
  const wrapped = await apiPost(
    `/api/v1/boards/${boardId}/cards/${encodeURIComponent(issueKey)}/move`,
    requestBody,
    dataResponseSchema(moveCardResultSchema),
  )
  return wrapped.data
}
