// issue-tracking BC REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend IssueResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 단건 응답 Zod 스키마 — 22 필드 (12 기존 + 8 FR-IS-04 + 1 FR-IS-03 assigneeId + 1 FR-IS-10 customFields), createdAt/updatedAt nullable */
export const issueResponseSchema = z.object({
  key: z.string().min(1),
  id: z.string().uuid(),
  projectKey: z.string().min(1),
  summary: z.string().min(1),
  currentStateKey: z.string().min(1),
  reporterId: z.string().uuid(),
  /**
   * 담당자 UUID. null이면 미할당.
   * 백엔드 IssueResponse(assigneeId: UUID? = null)는 null이라도 항상 직렬화하므로
   * nullable로 충분하다 — optional은 백엔드가 보내지 않는 형태(키 부재)까지 허용해
   * 계약을 느슨하게 만들어 회귀 감지를 약화시키므로 사용하지 않는다.
   */
  assigneeId: z.string().uuid().nullable(),
  /**
   * FR-CM-02 — 이슈에 할당된 컴포넌트 ID 목록. 백엔드 IssueResponse.componentIds(단건 경로만 채움,
   * 목록 경로는 빈 배열)와 정합. `.default([])`로 두어 componentIds 없는 기존 인라인 mock이 깨지지 않게 한다
   * (메모리 zod-schema-strengthen-inline-mock-fanout).
   */
  componentIds: z.array(z.string().uuid()).default([]),
  version: z.number().int().nonnegative(),
  createdAt: z.string().nullable(),
  updatedAt: z.string().nullable(),
  typeId: z.number().int().positive(),
  typeKey: z.string().min(1),
  typeName: z.string().min(1),
  /** raw Markdown 본문. nullable — DB에 본문이 없으면 null */
  description: z.string().nullable(),
  /**
   * 렌더+정화된 HTML 본문.
   * nullable — 목록 API는 성능상 null 반환, 단건 GET만 채워짐.
   */
  descriptionHtml: z.string().nullable(),
  /** 우선순위 1(Highest)~5(Lowest). non-null, 기본값 3(Medium) */
  priority: z.number().int().min(1).max(5),
  /** 우선순위 표시 이름 (Highest/High/Medium/Low/Lowest) */
  priorityName: z.string(),
  /** 레이블 목록. 없으면 빈 배열 [] */
  labels: z.array(z.string()),
  /** 재현 환경 메모. nullable */
  environment: z.string().nullable(),
  /** 영향도 1(High)~3(Low). nullable — 설정 전 null */
  impact: z.number().int().min(1).max(3).nullable(),
  /** 영향도 표시 이름 (High/Medium/Low). nullable */
  impactName: z.string().nullable(),
  /**
   * 현재 결의안 (B11). 이슈가 종료(DONE)된 경우에만 채워짐.
   * nullable — 미종료·결의안 미설정 이슈는 null.
   */
  resolution: z.object({
    id: z.string().uuid(),
    key: z.string().min(1),
    name: z.string().min(1),
  }).nullable().optional(),
  /**
   * 이슈에 적용된 보안 등급 UUID (FR-PM-06 PR-B).
   * nullable — 등급 미지정(공개) 이슈는 null.
   * optional()을 추가해 기존 인라인 mock(securityLevelId 키 미포함)이 깨지지 않게 한다
   * (zod-schema-strengthen-inline-mock-fanout 교훈).
   */
  securityLevelId: z.string().uuid().nullable().optional(),
  /**
   * FR-IS-10 커스텀 필드 — 프로젝트별 확장 키-값 맵.
   * 백엔드 IssueResponse.customFields: Map<String, Any?> non-null, 기본 {}.
   * - .default({})로 추가해 customFields 키가 없는 기존 인라인 mock이 깨지지 않게 한다
   *   (zod-schema-strengthen-inline-mock-fanout 교훈).
   * - 값은 임의 JSON(unknown) — 배열은 Record가 아니므로 z.record가 거부한다.
   */
  customFields: z.record(z.string(), z.unknown()).default({}),
  /**
   * FR-PM-07 — 열람숨김(restrictedFields) 필드 키 목록(PR-A).
   * 현재 사용자의 필드 권한(FIELD_PERMISSION) 상 VIEW 이하여서 값이 마스킹된 필드 키.
   * UI는 해당 키의 셀을 "열람 권한 없음" 안내로 대체해야 한다.
   * `default([])`: 필드가 응답에 없으면 빈 배열로 처리 — 기존 인라인 mock이 깨지지 않게 한다
   * (zod-schema-strengthen-inline-mock-fanout 교훈).
   */
  restrictedFields: z.array(z.string()).default([]),
  /**
   * FR-PM-07 — 편집비활성(noneditableFields) 필드 키 목록(PR-B).
   * 현재 사용자의 필드 권한 상 VIEW만 허용되어 값은 보이지만 수정할 수 없는 필드 키.
   * UI는 해당 키의 편집 컨트롤을 비활성(disabled) 처리해야 한다.
   * `default([])`: 필드가 응답에 없으면 빈 배열로 처리 — 기존 인라인 mock이 깨지지 않게 한다
   * (zod-schema-strengthen-inline-mock-fanout 교훈).
   */
  noneditableFields: z.array(z.string()).default([]),
  /**
   * FR-VR-03 — 이슈에 연결된 영향 버전 ID 목록.
   * 백엔드 IssueResponse.affectsVersionIds: List<UUID> (단건 경로에서만 채워짐).
   * `optional().default([])`: optional()이 TS 추론 타입을 optional로 만들어
   * 기존 인라인 mock(필드 미포함)이 TS 컴파일 에러 없이 통과하게 한다
   * (zod-schema-strengthen-inline-mock-fanout 교훈 — componentIds 패턴과 동일).
   */
  affectsVersionIds: z.array(z.string()).optional().default([]),
  /**
   * FR-VR-03 — 이슈에 연결된 수정 버전 ID 목록.
   * 백엔드 IssueResponse.fixVersionIds: List<UUID> (단건 경로에서만 채워짐).
   * `optional().default([])`: optional()이 TS 추론 타입을 optional로 만들어
   * 기존 인라인 mock(필드 미포함)이 TS 컴파일 에러 없이 통과하게 한다
   * (zod-schema-strengthen-inline-mock-fanout 교훈 — componentIds 패턴과 동일).
   */
  fixVersionIds: z.array(z.string()).optional().default([]),
})

/** Spring Page 응답 Zod 스키마 — 래퍼 없음 (DataResponse 감싸지 않음) */
const pageSchema = <T>(itemSchema: z.ZodSchema<T>) =>
  z.object({
    content: z.array(itemSchema),
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    size: z.number().int().positive(),
    number: z.number().int().nonnegative(),
    first: z.boolean(),
    last: z.boolean(),
    empty: z.boolean(),
  })

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 가용 전이 항목 Zod 스키마.
 * backend TransitionItem DTO 직렬화 형태와 1:1 대응.
 * workflows.ts의 workflowTransitionViewSchema와 동일 형태이나
 * 이슈 전이 API 계약에 특화된 독립 스키마로 관리한다.
 *
 * - `toCategory`: 목표 상태 카테고리 (B12). "DONE" 이면 종료 전이.
 *   백엔드가 DONE 전이에만 값을 채우고 나머지는 null 반환할 수 있으므로 nullable.
 */
export const issueTransitionSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  fromStateKey: z.string().min(1),
  toStateKey: z.string().min(1),
  /** 목표 상태 카테고리 (B12). "DONE"이면 종료 전이. */
  toCategory: z.string().nullable().optional(),
})

/** 이슈 전이 항목 타입 */
export type IssueTransition = z.infer<typeof issueTransitionSchema>

/**
 * 일괄 가용 전이 조회 응답 Zod 스키마.
 * backend BulkAvailableTransitionsResponse DTO 직렬화 형태와 1:1 대응.
 * - transitions: 모든 대상 이슈에 공통으로 존재하는 전이 교집합
 * - unresolvedIssueKeys: 미존재·워크플로우 미설정·접근 불가로 조회 실패한 이슈 키 목록
 */
export const bulkAvailableTransitionsSchema = z.object({
  transitions: z.array(issueTransitionSchema),
  unresolvedIssueKeys: z.array(z.string()),
})

/** 이슈 전이 요청 입력 타입 */
export interface TransitionIssueInput {
  toStatusKey: string
  expectedVersion: number
  /** 종료(DONE) 전이 시 선택된 결의안 UUID (B9). 비DONE 전이 시 미전달. */
  resolutionId?: string
}

/** 이슈 단건 응답 타입 */
export type IssueResponse = z.infer<typeof issueResponseSchema>

/** 커스텀 필드 값 타입 — 프로젝트별 확장 키-값 맵 (FR-IS-10). */
export type CustomFieldValues = Record<string, unknown>

/** 이슈 생성 입력 타입 */
export interface CreateIssueInput {
  projectKey: string
  summary: string
  /** FR-CM-03 — 생성 시 컴포넌트 지정. 미전달 시 빈 배열(컴포넌트 미할당)로 처리. */
  componentIds?: string[]
  /** FR-PM-06 — 생성 시 보안등급 지정. 미전달/null이면 등급 없음(공개). */
  securityLevelId?: string | null
  /**
   * FR-IS-10 — 생성 시 커스텀 필드 지정.
   * 미전달 시 서버 기본값({}) 사용. 각 키-값은 프로젝트 필드 정의에 따라 처리됨.
   */
  customFields?: CustomFieldValues
}

/**
 * 이슈 수정 입력 타입.
 * summary · typeId 중 하나 이상을 전달하며, expectedVersion은 낙관적 잠금(OCC)을 위해 필수다.
 *
 * ### FR-IS-04 신규 필드 — merge-patch 3-state 규칙 (백엔드 UpdateIssueRequest 정본)
 * - `undefined` (필드 미포함) 또는 `null` (JSON null) → 해당 필드 변경 없음
 * - `""` (빈 문자열) → DB NULL로 클리어 (description · environment만 해당)
 * - `[]` (빈 배열) → 전체 제거 (labels만 해당)
 * - 값 전달 → 해당 값으로 설정
 *
 * 예외: `impact`는 클리어 sentinel이 없어 한 번 설정하면 비울 수 없음.
 */
export interface UpdateIssueInput {
  summary?: string
  /** 변경할 이슈 타입 ID. 미전달 시 타입 유지. */
  typeId?: number
  /**
   * 본문 Markdown 텍스트.
   * "" = DB NULL 클리어, null/미전달 = 변경 없음, 값 = 설정. max 65535자.
   */
  description?: string | null
  /**
   * 우선순위 1~5. null = 무변경. 미전달 = 무변경.
   * (서버는 null과 미전달을 동일하게 처리한다.)
   */
  priority?: number | null
  /**
   * 레이블 배열. null = 무변경, [] = 전체 제거, 값 = 교체.
   * 각 레이블 최대 50자, 최대 20개.
   */
  labels?: string[] | null
  /**
   * 재현 환경 메모.
   * "" = DB NULL 클리어, null/미전달 = 변경 없음, 값 = 설정. max 1000자.
   */
  environment?: string | null
  /**
   * 영향도 1~3. null = 무변경. 미전달 = 무변경.
   * 클리어 sentinel 없음 — 한 번 설정 후 비울 수 없음.
   */
  impact?: number | null
  /**
   * 보안등급 UUID (FR-PM-06, JsonNullable 3-state).
   * - undefined(미전달) = 무변경
   * - null = 해제(공개 복귀)
   * - UUID 문자열 = 지정
   */
  securityLevelId?: string | null
  /**
   * FR-IS-10 — 커스텀 필드 수정.
   * - undefined(미전달) = 무변경
   * - null = 무변경 (백엔드 동일 처리)
   * - {} (빈 맵) = 무변경 (병합할 키 0개·기존 유지). 백엔드에 "전체 제거" 기능 없음.
   * - { key: value } = 키 단위 병합; 값이 null인 키는 삭제 (백엔드 처리)
   */
  customFields?: CustomFieldValues | null
  expectedVersion: number
}

/** fetchIssues 쿼리 파라미터 */
export interface FetchIssuesParams {
  projectKey: string
  page: number
  size: number
}

/**
 * 담당자 변경 입력 타입.
 * PATCH /api/v1/issues/{key}/assignee body 형태.
 * - assigneeId=null → 담당자 해제 (3-state null 시맨틱)
 * - expectedVersion 필수 (낙관적 잠금 OCC)
 */
export interface ChangeAssigneeInput {
  /** 담당자 UUID. null이면 해제. */
  assigneeId: string | null
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

/** Spring Page<IssueResponse> 타입 */
export type IssuePage = z.infer<ReturnType<typeof pageSchema<IssueResponse>>>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 단건을 조회한다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns IssueResponse — 백엔드 `{ data: IssueResponse }` 래퍼를 언래핑해 반환
 * @throws ApiError(404) 해당 key의 이슈가 없을 때
 */
export async function fetchIssue(key: string): Promise<IssueResponse> {
  const wrapped = await apiGet(
    `/api/v1/issues/${key}`,
    dataResponseSchema(issueResponseSchema),
  ).catch((err: unknown) => {
    if (err instanceof ApiError && err.status === 404) {
      throw new ApiError(404, { message: `이슈를 찾을 수 없습니다: ${key}` })
    }
    throw err
  })
  return wrapped.data
}

/**
 * 프로젝트 이슈 목록을 페이징 조회한다.
 *
 * @param params projectKey · page · size 쿼리 파라미터
 * @returns Spring Page 구조 — content 배열 + 페이징 메타 (래퍼 없음)
 */
export async function fetchIssues(params: FetchIssuesParams): Promise<IssuePage> {
  const query = new URLSearchParams({
    projectKey: params.projectKey,
    page: String(params.page),
    size: String(params.size),
  })
  return apiGet(
    `/api/v1/issues?${query.toString()}`,
    pageSchema(issueResponseSchema),
  )
}

/**
 * 새 이슈를 생성한다.
 *
 * @param input projectKey · summary · componentIds(선택, 미전달 시 빈 배열)
 * @returns 생성된 IssueResponse — 백엔드 201 `{ data: IssueResponse }` 언래핑
 */
export async function createIssue(input: CreateIssueInput): Promise<IssueResponse> {
  const body: Record<string, unknown> = {
    projectKey: input.projectKey,
    summary: input.summary,
    // FR-CM-03 — 생성 시 컴포넌트 지정. 미전달 시 빈 배열로 전송.
    componentIds: input.componentIds ?? [],
  }
  // FR-PM-06 — securityLevelId 미전달 시 필드 자체를 body에서 제외해 서버가 null(무등급)으로 처리하게 한다.
  if (input.securityLevelId !== undefined) {
    body['securityLevelId'] = input.securityLevelId
  }
  // FR-IS-10 — customFields 미전달 시 필드 자체를 body에서 제외해 서버가 기본값({})으로 처리하게 한다.
  if (input.customFields !== undefined) {
    body['customFields'] = input.customFields
  }
  const wrapped = await apiPost(
    '/api/v1/issues',
    body,
    dataResponseSchema(issueResponseSchema),
  )
  return wrapped.data
}

/**
 * 이슈 요약을 수정한다.
 * Optimistic Concurrency Control을 위해 expectedVersion을 필수로 전달해야 한다.
 *
 * @param key 수정할 이슈 키
 * @param input summary(선택) · expectedVersion(필수)
 * @returns 수정된 IssueResponse — version이 증가된 상태로 반환
 */
export async function updateIssue(key: string, input: UpdateIssueInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}`, { method: 'PATCH', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈를 삭제한다.
 *
 * @param key 삭제할 이슈 키
 * @returns void — 204 no content
 */
export async function deleteIssue(key: string): Promise<void> {
  const res = await apiFetch(`/api/v1/issues/${key}`, { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/** backend `{ data: { transitions: [...] } }` 전이 목록 응답 파싱 헬퍼 (내부 전용) */
const transitionsResponseSchema = z.object({
  data: z.object({ transitions: z.array(issueTransitionSchema) }),
})

/**
 * 이슈의 현재 상태에서 가용한 전이 목록을 조회한다.
 * GET /api/v1/issues/{key}/transitions → { data: { transitions: IssueTransition[] } }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 전이 항목 배열 — 현재 상태에서 이동 가능한 전이들
 * @throws ApiError(404) 해당 key의 이슈가 없을 때
 */
export async function fetchIssueTransitions(key: string): Promise<IssueTransition[]> {
  const wrapped = await apiGet(
    `/api/v1/issues/${key}/transitions`,
    transitionsResponseSchema,
  )
  return wrapped.data.transitions
}

/**
 * 이슈 상태를 전이한다.
 * POST /api/v1/issues/{key}/transition body { toStatusKey, expectedVersion }
 * 성공 200 시 변경된 IssueResponse를 반환한다.
 * 낙관적 잠금(OCC) 충돌 시 ApiError(409)를 throw한다.
 *
 * @param key 전이할 이슈 식별 키
 * @param input toStatusKey(목표 상태키) · expectedVersion(현재 버전, OCC용)
 * @returns 전이 완료된 IssueResponse — currentStateKey와 version이 갱신된 상태
 * @throws ApiError(404) 이슈가 없을 때
 * @throws ApiError(409) 낙관적 잠금 충돌 또는 전이 불허 시
 * @throws ApiError(422) 유효하지 않은 전이 요청 시
 */
export async function transitionIssue(key: string, input: TransitionIssueInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/transition`, { method: 'POST', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 여러 이슈에 공통으로 적용 가능한 전이 교집합을 조회한다.
 * POST /api/v1/issues/bulk-transitions/available body { issueKeys }
 * 성공 200 시 { transitions, unresolvedIssueKeys }를 반환한다.
 * 미존재·워크플로우 미설정 이슈는 unresolvedIssueKeys에 포함되고 교집합에서 제외된다.
 *
 * @param issueKeys 가용 전이를 조회할 이슈 키 목록
 * @returns transitions(공통 전이 교집합) + unresolvedIssueKeys(조회 실패 키 목록)
 * @throws ApiError(400) issueKeys가 빈 배열이거나 1000 초과 시 (ISSUE_BULK_VALIDATION_FAILED)
 */
export async function fetchBulkAvailableTransitions(
  issueKeys: string[],
): Promise<{ transitions: IssueTransition[]; unresolvedIssueKeys: string[] }> {
  const res = await apiFetch('/api/v1/issues/bulk-transitions/available', {
    method: 'POST',
    body: { issueKeys },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(bulkAvailableTransitionsSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈 담당자를 변경하거나 해제한다.
 * PATCH /api/v1/issues/{key}/assignee body { assigneeId: UUID|null, expectedVersion: Long }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param input assigneeId(UUID 또는 null) · expectedVersion(OCC 버전)
 * @returns 변경된 IssueResponse — assigneeId와 version이 갱신된 상태
 * @throws ApiError(409) 낙관적 잠금 충돌 시
 * @throws ApiError(422) assigneeId가 실재하지 않는 사용자일 때 (ASSIGNEE_NOT_FOUND)
 */
export async function changeAssignee(key: string, input: ChangeAssigneeInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/assignee`, { method: 'PATCH', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 컴포넌트 변경 입력 타입.
 * PATCH /api/v1/issues/{key}/components body 형태.
 * - componentIds: 할당할 컴포넌트 UUID 목록 (빈 배열이면 전체 제거)
 * - expectedVersion 필수 (낙관적 잠금 OCC)
 */
export interface ChangeComponentsInput {
  /** 할당할 컴포넌트 UUID 목록. 빈 배열이면 전체 제거. */
  componentIds: string[]
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

/**
 * 이슈 컴포넌트 목록을 변경한다.
 * PATCH /api/v1/issues/{key}/components body { componentIds: UUID[], expectedVersion: Long }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param input componentIds(UUID 배열) · expectedVersion(OCC 버전)
 * @returns 변경된 IssueResponse — componentIds와 version이 갱신된 상태
 * @throws ApiError(409) 낙관적 잠금 충돌 시
 * @throws ApiError(422) componentIds 중 실재하지 않는 컴포넌트가 있을 때 (COMPONENT_NOT_FOUND)
 */
export async function changeComponents(key: string, input: ChangeComponentsInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/components`, { method: 'PATCH', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/** 이슈 클론 입력 타입 — 모든 필드 선택 */
export interface CloneIssueInput {
  /** 담당자 포함 여부. 미전달 시 백엔드 기본값(true) 사용. */
  includeAssignee?: boolean
  /** 새 이슈 제목. 최대 255자. 미전달 시 원본 제목 사용. */
  summaryOverride?: string
}

/**
 * 이슈를 클론한다.
 * POST /api/v1/issues/{key}/clone → 201 Created + { data: IssueResponse }
 *
 * @param key 클론할 원본 이슈 식별 키 (예: "ATLAS-1")
 * @param input 클론 옵션 (모두 선택 사항)
 * @returns 생성된 클론 IssueResponse — 백엔드 201 `{ data: IssueResponse }` 언래핑
 * @throws ApiError(404) 원본 이슈가 없을 때
 * @throws ApiError(403) 권한 없을 때
 * @throws ApiError(400) summaryOverride 255자 초과 등 유효성 오류
 */
export async function cloneIssue(key: string, input?: CloneIssueInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/clone`, { method: 'POST', body: input ?? {} })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈 PDF를 다운로드한다.
 * GET /api/v1/issues/{key}/pdf → application/pdf 바이너리 스트림
 *
 * 바이너리 응답이므로 Zod 파싱을 수행하지 않는다.
 * GET 요청이므로 CSRF 토큰이 불필요하다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns PDF 바이너리를 담은 Blob
 * @throws ApiError(404) 해당 key의 이슈가 없을 때
 * @throws ApiError(5xx) 서버 오류 시
 */
export async function downloadIssuePdf(key: string): Promise<Blob> {
  const res = await apiFetch(`/api/v1/issues/${key}/pdf`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return res.blob()
}
