// 백로그·스프린트 REST API 클라이언트 — Zod 스키마 + fetch 함수 (FR-BL-01/02 D6/D7)
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 파싱 (boards.ts 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/** backend 공통 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그/스프린트 이슈 단건 스키마.
 * 백엔드 BacklogIssue DTO 대응 (FR-BL-01/02).
 *
 * nullable 필드는 `.nullish()` 사용.
 * 백엔드 @JsonInclude(NON_NULL)이 설정된 경우 필드 자체가 누락될 수 있으므로
 * undefined → null로 처리하는 nullish()가 안전하다.
 */
export const backlogIssueSchema = z.object({
  /** 이슈 키. 예: "ATLAS-1" */
  key: z.string().min(1),
  /** 이슈 제목 */
  summary: z.string(),
  /** 현재 워크플로우 상태 키 */
  currentStateKey: z.string(),
  /** 담당자 UUID. 미배정 시 null. @JsonInclude(NON_NULL) 방어 — nullish */
  assigneeId: z.string().uuid().nullish().transform((v) => v ?? null),
  /** 우선순위 정수. 값이 작을수록 우선순위 높음 */
  priority: z.number().int(),
  /** LexoRank 문자열. 아직 rank 미부여 시 null. nullish 방어 */
  rank: z.string().nullish().transform((v) => v ?? null),
  /** 낙관적 잠금(Optimistic Lock) 버전 번호 */
  version: z.number().int(),
  /** 속한 에픽 이슈 키. 에픽 없으면 null. nullish 방어 */
  epicKey: z.string().nullish().transform((v) => v ?? null),
  /**
   * 이슈 유형 키. 예: "task", "bug", "story".
   * 백엔드 BacklogIssueResponse.typeKey — 항상 전송(필수·non-null). FR-UX-14 B2 #346.
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
 * 스프린트 메타 스키마.
 * 백엔드 SprintResponse DTO 대응 (FR-BL-02 Task 5).
 *
 * SprintResponse.sprintId는 UUID 타입이지만 JSON 직렬화 시 문자열로 전송된다.
 * projectKey는 백엔드 DTO에 포함되어 있으나 BacklogView에서는 sprint 수준에서
 * 별도로 노출되지 않으므로 optional로 처리한다.
 */
export const sprintMetaSchema = z.object({
  /** 스프린트 UUID */
  sprintId: z.string().uuid(),
  /** 스프린트 이름 */
  name: z.string(),
  /** 스프린트 목표. 미설정 시 null. nullish 방어 */
  goal: z.string().nullish().transform((v) => v ?? null),
  /** 현재 상태. "PLANNED" | "ACTIVE" | "COMPLETED" */
  status: z.string(),
  /** 시작일 (ISO 8601 date string). 미지정 시 null. nullish 방어 */
  startDate: z.string().nullish().transform((v) => v ?? null),
  /** 종료일 (ISO 8601 date string). 미지정 시 null. nullish 방어 */
  endDate: z.string().nullish().transform((v) => v ?? null),
  /** 낙관적 잠금 버전 */
  version: z.number().int(),
})

/**
 * 스프린트 단위 묶음 스키마.
 * 스프린트 메타 + 해당 스프린트에 할당된 이슈 목록.
 */
const sprintWithIssuesSchema = z.object({
  /** 스프린트 메타 정보 */
  sprint: sprintMetaSchema,
  /** 스프린트에 할당된 이슈 목록 (rank 순) */
  issues: z.array(backlogIssueSchema),
})

/**
 * 백로그 전체 뷰 스키마.
 * GET /api/v1/projects/{projectKey}/backlog 응답 data 필드 대응.
 */
export const backlogViewSchema = z.object({
  /** 스프린트 미할당 이슈 목록 (rank 순) */
  backlog: z.array(backlogIssueSchema),
  /** 스프린트별 이슈 묶음 목록 */
  sprints: z.array(sprintWithIssuesSchema),
  /** 카드 수 cap 초과로 잘렸는지 여부 */
  truncated: z.boolean(),
})

/**
 * 이슈 rank 변경 결과 스키마.
 * 백엔드 IssueRankResponse DTO 대응 (FR-BL-01 Task 5).
 */
export const issueRankResultSchema = z.object({
  /** 이슈 키 */
  key: z.string().min(1),
  /** 새 LexoRank 문자열. null 가능 */
  rank: z.string().nullable(),
  /** 갱신된 낙관적 잠금 버전 */
  version: z.number().int(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 — z.infer 사용 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그/스프린트 이슈 단건 타입 */
export type BacklogIssue = z.infer<typeof backlogIssueSchema>

/** 스프린트 메타 타입 */
export type SprintMeta = z.infer<typeof sprintMetaSchema>

/** 스프린트 + 이슈 묶음 타입 */
export type SprintWithIssues = z.infer<typeof sprintWithIssuesSchema>

/** 백로그 전체 뷰 타입 */
export type BacklogView = z.infer<typeof backlogViewSchema>

/** 이슈 rank 변경 결과 타입 */
export type IssueRankResult = z.infer<typeof issueRankResultSchema>

/** 이슈 rank 변경 요청 body 타입 */
export interface RerankIssueBody {
  /** 앞에 위치할 이슈 키. undefined면 body에서 제외 (맨 앞으로 이동) */
  previousIssueKey?: string
  /** 뒤에 위치할 이슈 키. undefined면 body에서 제외 (맨 뒤로 이동) */
  nextIssueKey?: string
}

/**
 * 스프린트 메타 수정 요청 body 타입 (3-state partial).
 *
 * 백엔드 `UpdateSprintRequest`(`SprintController.kt:149`)는 `name`·`goal`·`startDate`·`endDate`
 * 를 `JsonNullable` 로 받는다 — **필드 미전송 = 무변경**, **명시 `null` = 값 삭제**로 뜻이 다르다.
 * 그래서 `undefined` 를 「값 없음」이 아니라 「키 없음」으로 다뤄야 하고,
 * {@link updateSprint} 가 `hasOwnProperty` 로 그 구분을 지킨다.
 *
 * `version` 은 낙관적 잠금(Optimistic Lock)용으로 **항상 필수**다.
 */
export interface UpdateSprintBody {
  /** 스프린트 이름. 미전송이면 무변경 (백엔드가 null 을 허용하지 않는다) */
  name?: string
  /** 스프린트 목표. null 이면 값 삭제 */
  goal?: string | null
  /** 시작일 (ISO 8601 date string). null 이면 값 삭제 */
  startDate?: string | null
  /** 종료일 (ISO 8601 date string). null 이면 값 삭제 */
  endDate?: string | null
  /** 낙관적 잠금 버전. 어긋나면 409 */
  version: number
}

/** {@link UpdateSprintBody} 에서 3-state 로 전송되는 필드 이름 전수 */
const UPDATE_SPRINT_PATCHABLE_FIELDS = ['name', 'goal', 'startDate', 'endDate'] as const

/** 스프린트 생성 요청 파라미터 타입 */
export interface CreateSprintParams {
  /** 스프린트를 생성할 프로젝트 키 */
  projectKey: string
  /** 스프린트 이름 */
  name: string
  /** 스프린트 목표 설명. 미전송 시 null */
  goal?: string
  /** 시작일 (ISO 8601 date string). 미전송 시 null */
  startDate?: string
  /** 종료일 (ISO 8601 date string). 미전송 시 null */
  endDate?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 경로 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 rank 변경 경로 베이스 (issue-tracking BC) */
const ISSUES_BASE = '/api/v1/issues'

/** 스프린트 경로 베이스 (agile-planning BC) */
const SPRINTS_BASE = '/api/v1/sprints'

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 백로그 전체 뷰를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/backlog → `{ data: BacklogView }` 언랩.
 *
 * @param projectKey 프로젝트 키. 예: "ATLAS"
 * @returns BacklogView — 미할당 backlog 목록 + 스프린트별 이슈 + truncated 여부
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchBacklog(projectKey: string): Promise<BacklogView> {
  const wrapped = await apiGet(
    `/api/v1/projects/${encodeURIComponent(projectKey)}/backlog`,
    dataResponseSchema(backlogViewSchema),
  )
  return wrapped.data
}

/**
 * 이슈 rank를 변경한다.
 *
 * PATCH /api/v1/issues/{key}/rank → `{ data: IssueRankResult }` 언랩.
 * issue-tracking 경로이므로 apiFetch 사용 (boards.ts BC 선례: apiFetch for PATCH/DELETE).
 *
 * previousIssueKey·nextIssueKey가 undefined이면 body에서 필드 자체를 제외한다.
 *
 * @param issueKey 이슈 키. 예: "ATLAS-1"
 * @param body 이웃 이슈 키 (선택). 둘 다 undefined이면 400
 * @returns IssueRankResult — 이슈 키 + 새 rank + 버전
 * @throws ApiError 비-2xx 응답 시 (400 = 이웃 키 누락, 409 = OCC 충돌)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function rerankIssue(issueKey: string, body: RerankIssueBody): Promise<IssueRankResult> {
  const requestBody = {
    ...(body.previousIssueKey !== undefined ? { previousIssueKey: body.previousIssueKey } : {}),
    ...(body.nextIssueKey !== undefined ? { nextIssueKey: body.nextIssueKey } : {}),
  }
  const res = await apiFetch(`${ISSUES_BASE}/${encodeURIComponent(issueKey)}/rank`, {
    method: 'PATCH',
    body: requestBody,
  })
  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  const wrapped = dataResponseSchema(issueRankResultSchema).parse(data)
  return wrapped.data
}

/**
 * 이슈를 스프린트에 할당한다.
 *
 * POST /api/v1/sprints/{id}/issues → 201, body 없음(void).
 * agile-planning 경로이므로 apiPost 사용 불가(응답 스키마가 없음).
 * apiFetch로 호출해 201을 검증한다.
 *
 * @param sprintId 스프린트 UUID
 * @param issueKey 할당할 이슈 키. 예: "ATLAS-1"
 * @throws ApiError 비-2xx 응답 시
 */
export async function assignToSprint(sprintId: string, issueKey: string): Promise<void> {
  const res = await apiFetch(`${SPRINTS_BASE}/${encodeURIComponent(sprintId)}/issues`, {
    method: 'POST',
    body: { issueKey },
  })
  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 이슈를 스프린트에서 제거한다.
 *
 * DELETE /api/v1/sprints/{id}/issues/{issueKey} → 204, body 없음(void).
 *
 * @param sprintId 스프린트 UUID
 * @param issueKey 제거할 이슈 키. 예: "ATLAS-1"
 * @throws ApiError 비-2xx 응답 시
 */
export async function unassignFromSprint(sprintId: string, issueKey: string): Promise<void> {
  const res = await apiFetch(
    `${SPRINTS_BASE}/${encodeURIComponent(sprintId)}/issues/${encodeURIComponent(issueKey)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 새 스프린트를 생성한다.
 *
 * POST /api/v1/sprints → `{ data: SprintMeta }` 언랩.
 * goal·startDate·endDate는 undefined이면 body에서 제외한다.
 *
 * @param params 생성 파라미터 (projectKey·name 필수, goal·startDate·endDate 선택)
 * @returns SprintMeta — 생성된 스프린트 정보
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function createSprint(params: CreateSprintParams): Promise<SprintMeta> {
  const { projectKey, name, goal, startDate, endDate } = params
  const requestBody = {
    projectKey,
    name,
    ...(goal !== undefined ? { goal } : {}),
    ...(startDate !== undefined ? { startDate } : {}),
    ...(endDate !== undefined ? { endDate } : {}),
  }
  const wrapped = await apiPost(
    SPRINTS_BASE,
    requestBody,
    dataResponseSchema(sprintMetaSchema),
  )
  return wrapped.data
}

/**
 * 스프린트의 이름·목표·기간을 수정한다 (partial update).
 *
 * PATCH /api/v1/sprints/{id} → `{ data: SprintMeta }` 언랩.
 * 같은 파일의 {@link rerankIssue} 와 같은 관례를 따른다 — PATCH 는 `apiFetch` + 수동 `ApiError`.
 *
 * **`body` 에 실제로 존재하는 키만 전송한다.** 3-state 계약상 미전송과 명시 `null` 의 뜻이
 * 다르므로(`UpdateSprintBody` 참조) 값이 `undefined` 인지가 아니라 **키가 있는지**로 판단한다.
 *
 * @param sprintId 스프린트 UUID
 * @param body 바뀐 필드 + `version`(필수)
 * @returns SprintMeta — 갱신된 스프린트 정보 (`version` 이 +1 된 값)
 * @throws ApiError 비-2xx 응답 시 (409 = 낙관적 잠금 충돌, 404 = 스프린트 미존재)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function updateSprint(sprintId: string, body: UpdateSprintBody): Promise<SprintMeta> {
  const requestBody: Record<string, unknown> = { version: body.version }
  for (const field of UPDATE_SPRINT_PATCHABLE_FIELDS) {
    if (Object.prototype.hasOwnProperty.call(body, field)) {
      requestBody[field] = body[field]
    }
  }

  const res = await apiFetch(`${SPRINTS_BASE}/${encodeURIComponent(sprintId)}`, {
    method: 'PATCH',
    body: requestBody,
  })
  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  const wrapped = dataResponseSchema(sprintMetaSchema).parse(data)
  return wrapped.data
}

/**
 * 스프린트를 시작한다.
 *
 * POST /api/v1/sprints/{id}/start → `{ data: SprintMeta }` 언랩.
 * 스프린트 상태가 PLANNED → ACTIVE로 전이된다.
 *
 * @param sprintId 스프린트 UUID
 * @returns SprintMeta — 전이 후 스프린트 정보 (status: "ACTIVE")
 * @throws ApiError 비-2xx 응답 시 (409 = 이미 ACTIVE/COMPLETED)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function startSprint(sprintId: string): Promise<SprintMeta> {
  const wrapped = await apiPost(
    `${SPRINTS_BASE}/${encodeURIComponent(sprintId)}/start`,
    {},
    dataResponseSchema(sprintMetaSchema),
  )
  return wrapped.data
}

/**
 * 스프린트를 완료 처리한다.
 *
 * POST /api/v1/sprints/{id}/complete → `{ data: SprintMeta }` 언랩.
 * 스프린트 상태가 ACTIVE → COMPLETED로 전이된다.
 *
 * @param sprintId 스프린트 UUID
 * @returns SprintMeta — 전이 후 스프린트 정보 (status: "COMPLETED")
 * @throws ApiError 비-2xx 응답 시 (409 = ACTIVE 상태가 아닌 경우)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function completeSprint(sprintId: string): Promise<SprintMeta> {
  const wrapped = await apiPost(
    `${SPRINTS_BASE}/${encodeURIComponent(sprintId)}/complete`,
    {},
    dataResponseSchema(sprintMetaSchema),
  )
  return wrapped.data
}
