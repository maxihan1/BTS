// issue-tracking BC REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 이슈 redirect 에러 — 308 옛 키 → 새 키 redirect 감지용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 GET 요청이 308 redirect(옛 키 → 새 키)로 응답된 경우 throw되는 에러.
 *
 * fetch `redirect:'follow'`(기본)는 308을 자동 추적하므로
 * `response.redirected === true` + `response.url`에서 새 키를 추출한다.
 * 호출 측(IssueDetailPage useQuery)에서 navigate로 새 키 라우트로 이동한다.
 *
 * @property newKey 이동된 이슈의 새 키 (예: "INFRA-5")
 */
export class IssueRedirectError extends Error {
  constructor(public readonly newKey: string) {
    super(`Issue redirected to ${newKey}`)
    this.name = 'IssueRedirectError'
  }
}

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
  /**
   * FR-LK-01 — 현재 이슈의 부모 이슈 요약 정보 (parent-child 계층 구조).
   * 백엔드 IssueResponse.parent: ParentRef? — @JsonInclude(NON_NULL) 적용으로
   * null이면 JSON 키 자체가 생략된다. 따라서 `.nullish()`(= nullable + optional)를 사용한다.
   * 단건 GET 경로에서만 채워지며 목록 API에서는 키가 생략된다.
   */
  parent: z.object({
    key: z.string(),
    summary: z.string(),
  }).nullish(),
  /**
   * FR-EP-01 — 현재 이슈가 속한 에픽 요약 정보.
   * 백엔드 IssueResponse.epic: EpicRef? — @JsonInclude(NON_NULL) 적용으로
   * null이면 JSON 키 자체가 생략된다. 따라서 `.nullish()`(= nullable + optional)를 사용한다.
   * 단건 GET 경로에서만 채워지며 목록 API에서는 키가 생략된다.
   * parent 필드와 동형 패턴 (issue-links.ts:138 선례).
   */
  epic: z.object({
    key: z.string(),
    summary: z.string(),
  }).nullish(),
  // ── FR-PL-01 일정 필드 (Schedule Dates) ─────────────────────────────────
  /**
   * FR-PL-01 — 이슈 시작일.
   * 백엔드 IssueResponse.startDate: LocalDate? → @JsonFormat(STRING, "yyyy-MM-dd") 직렬화.
   * null이면 미설정. optional()로 두어 기존 인라인 mock(필드 미포함)이 TS 컴파일 에러 없이
   * 통과하게 한다 (zod-schema-strengthen-inline-mock-fanout 교훈 — securityLevelId 패턴과 동일).
   */
  startDate: z.string().nullable().optional(),
  /**
   * FR-PL-01 — 이슈 마감일(Due Date).
   * 백엔드 IssueResponse.dueDate: LocalDate? → @JsonFormat(STRING, "yyyy-MM-dd") 직렬화.
   * null이면 미설정. optional()로 두어 기존 인라인 mock이 깨지지 않게 한다.
   */
  dueDate: z.string().nullable().optional(),
  /**
   * FR-PL-01 — 이슈 목표일(Target Date).
   * 백엔드 IssueResponse.targetDate: LocalDate? → @JsonFormat(STRING, "yyyy-MM-dd") 직렬화.
   * null이면 미설정. optional()로 두어 기존 인라인 mock이 깨지지 않게 한다.
   */
  targetDate: z.string().nullable().optional(),
  // ── FR-TT-01 추정 필드 (Time Tracking) ─────────────────────────────────────
  /**
   * FR-TT-01 — 원본 추정 시간(초).
   * 백엔드 IssueResponse.originalEstimateSeconds: Long? — @JsonInclude(NON_NULL) 미적용이므로
   * null인 경우에도 키가 항상 존재한다. nullable()이 본질.
   * optional()은 기존 인라인 mock(estimate 필드 0개) fanout 회피 전용
   * (zod-schema-strengthen-inline-mock-fanout 교훈 — startDate 패턴과 동일).
   */
  originalEstimateSeconds: z.number().nullable().optional(),
  /**
   * FR-TT-01 — 실제 소요 시간(초).
   * 백엔드 IssueResponse.timeSpentSeconds: Long — non-null, 기본값 0.
   * @JsonInclude(NON_NULL) 미적용이므로 키가 항상 존재한다.
   * optional()은 기존 인라인 mock 파급 방지용.
   */
  timeSpentSeconds: z.number().optional(),
  /**
   * FR-TT-01 — 잔여 추정 시간(초).
   * 백엔드 IssueResponse.remainingEstimateSeconds: Long? — @JsonInclude(NON_NULL) 미적용이므로
   * null인 경우에도 키가 항상 존재한다. nullable()이 본질.
   * optional()은 기존 인라인 mock 파급 방지용.
   */
  remainingEstimateSeconds: z.number().nullable().optional(),
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
 * 이슈 가용 전환 항목 Zod 스키마.
 * backend TransitionItem DTO 직렬화 형태와 1:1 대응.
 * workflows.ts의 workflowTransitionViewSchema와 동일 형태이나
 * 이슈 전환 API 계약에 특화된 독립 스키마로 관리한다.
 *
 * - `toCategory`: 목표 상태 카테고리 (B12). "DONE" 이면 종료 전환.
 *   백엔드가 DONE 전환에만 값을 채우고 나머지는 null 반환할 수 있으므로 nullable.
 *
 * ### `transitionId`·`kind` 를 required 로 올리지 마라 (ADR 2026-08-18)
 * backend `TransitionItem` 이 두 필드를 **`UUID?`·`String?`** 로 선언한다 —
 * 실 API 응답은 항상 채워지지만 계약상 nullable 이다. 클라이언트를 서버 계약보다 엄격하게
 * 만들면 「서버가 보낸 정상 응답을 클라이언트가 거부」하는 경로가 생긴다.
 * `optional()` 은 그 위에 하나 더 얹은 방어다 — 이 스키마를 소비하는 인라인 목이 저장소에
 * 산재해 있어(`grep -rln "fromStateKey" apps/web/src`) required 로 올리면 그 전부가 동시에
 * `z.parse` 실패로 깨진다 (learnings 2026-05-30 · PR #46 이 정확히 그 사고다).
 * 형제 필드 `toCategory` 가 같은 이유로 같은 모양이다.
 */
export const issueTransitionSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  fromStateKey: z.string().min(1),
  toStateKey: z.string().min(1),
  /** 목표 상태 카테고리 (B12). "DONE"이면 종료 전환. */
  toCategory: z.string().nullable().optional(),
  /**
   * 전환의 1급 식별자 (`workflow_transitions.id`).
   *
   * `UNIQUE(workflow_id, from, to)` 해제로 같은 상태쌍에 이름만 다른 전환이 여럿 있을 수 있어
   * `key`(`from__to`)로는 전환을 지목할 수 없다. 409 `AMBIGUOUS_TRANSITION` 재요청은 이 값을
   * `POST /api/v1/issues/{key}/transition` 의 `transitionId` 에 되실어 보내는 왕복이다.
   */
  transitionId: z.string().uuid().nullable().optional(),
  /**
   * 전환 종류. `"NORMAL"`(출발 상태 지정) · `"GLOBAL"`(어느 상태에서나).
   * `"INITIAL"`(이슈 생성 진입 전용)은 이 목록에 나오지 않는다.
   *
   * `z.enum` 으로 좁히지 않는다 — backend 가 `String?` 로 보내므로 종류가 늘어난 순간
   * 목록 전체가 파싱 실패로 사라진다(전환 버튼이 통째로 증발하는 형태의 회귀).
   */
  kind: z.string().min(1).nullable().optional(),
})

/** 이슈 전환 항목 타입 */
export type IssueTransition = z.infer<typeof issueTransitionSchema>

/**
 * 409 `AMBIGUOUS_TRANSITION` 응답이 돌려주는 전환 후보 1건 Zod 스키마.
 * backend `TransitionCandidate`(전환 UUID + 표시 라벨) 와 1:1.
 */
const ambiguousTransitionCandidateSchema = z.object({
  transitionId: z.string().uuid(),
  name: z.string().min(1),
})

/**
 * 409 `AMBIGUOUS_TRANSITION` 응답 본문 Zod 스키마.
 * backend `AmbiguousTransitionErrorResponse` 직렬화 형태와 1:1 —
 * 표준 `{ error: { code, message } }` 위에 최상위 `candidates` 를 덧붙인 모양이다.
 */
const ambiguousTransitionErrorSchema = z.object({
  error: z.object({
    code: z.literal('AMBIGUOUS_TRANSITION'),
    message: z.string().min(1),
  }),
  candidates: z.array(ambiguousTransitionCandidateSchema).min(1),
})

/** 모호 전환 후보 1건 타입 */
export type AmbiguousTransitionCandidate = z.infer<typeof ambiguousTransitionCandidateSchema>

/** 모호 전환 409 응답 타입 */
export type AmbiguousTransitionErrorBody = z.infer<typeof ambiguousTransitionErrorSchema>

/** 후보 선택 UI 가 쓰는 모호 전환 정보 — 안내 문구 + 후보 전량. */
export interface AmbiguousTransition {
  /** 서버가 만든 안내 문구. 후보 개수가 문구에 들어 있어 클라이언트가 다시 조립하지 않는다. */
  readonly message: string
  /** 사용자가 지목할 수 있는 전환 후보 전량. 서버가 준 순서를 보존한다. */
  readonly candidates: AmbiguousTransitionCandidate[]
}

/**
 * 전환 실행 에러가 409 `AMBIGUOUS_TRANSITION` 인지 판정하고 후보를 뽑는다.
 *
 * ★에러 코드 → 화면 분기 매핑을 화면마다 인라인으로 만들지 마라. 같은 409 에
 * `VERSION_CONFLICT`·`TRANSITION_NOT_ALLOWED` 가 함께 오므로, 판별을 복제하면 화면마다
 * 조금씩 다른 규칙이 생겨 raw 코드가 새는 가짜 그린이 난다 (learnings PR #106).
 * 판정은 이 함수 하나만 쓴다.
 *
 * @param error `transitionIssue` 가 던진 값. `ApiError` 가 아니어도 안전하게 받는다.
 * @returns 모호 전환이면 안내 문구 + 후보 전량, 아니면 null
 */
export function parseAmbiguousTransitionError(error: unknown): AmbiguousTransition | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null
  const parsed = ambiguousTransitionErrorSchema.safeParse(error.body)
  if (!parsed.success) return null
  return { message: parsed.data.error.message, candidates: parsed.data.candidates }
}

/**
 * 일괄 가용 전환 조회 응답 Zod 스키마.
 * backend BulkAvailableTransitionsResponse DTO 직렬화 형태와 1:1 대응.
 * - transitions: 모든 대상 이슈에 공통으로 존재하는 전환 교집합
 * - unresolvedIssueKeys: 미존재·워크플로우 미설정·접근 불가로 조회 실패한 이슈 키 목록
 */
export const bulkAvailableTransitionsSchema = z.object({
  transitions: z.array(issueTransitionSchema),
  unresolvedIssueKeys: z.array(z.string()),
})

/** 이슈 전환 요청 입력 타입 */
export interface TransitionIssueInput {
  toStatusKey: string
  expectedVersion: number
  /** 종료(DONE) 전환 시 선택된 결의안 UUID (B9). 비DONE 전환 시 미전달. */
  resolutionId?: string
  /**
   * 실행할 전환의 1급 식별자 (ADR 2026-08-18 §D3).
   *
   * 미전달이면 서버가 `toStatusKey` 로 후보를 찾아 **정확히 1개일 때만** 실행하고,
   * 2개 이상이면 409 `AMBIGUOUS_TRANSITION` + 후보 목록을 돌려준다. 그 후보 하나의
   * `transitionId` 를 여기에 실어 재요청하는 것이 왕복의 두 번째 절반이다.
   */
  transitionId?: string
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
  /** FR-UX-09 F2 — 이슈 유형. 미전달이면 서버가 task 로 fallback 한다. */
  typeId?: number
  /**
   * FR-UX-09 F2 — 이슈 본문(Markdown).
   * 미전달/공백이면 서버가 프로젝트 템플릿으로 대체한다 (FR-TM-01 옵션 C).
   */
  description?: string
  /**
   * FR-UX-09 F2 — 담당자 **3-state** (백엔드 `JsonNullable`, ADR 2026-07-31 D-2).
   *
   * - **키 미전달(`undefined`)** — 서버 자동 배정(`resolveDefaultAssignee`) 유지. 기존 동작.
   * - **명시 `null`** — 자동 배정을 **끄고** 미할당으로 확정.
   * - **값** — 그 사용자로 확정. 미존재 사용자는 422 `ASSIGNEE_NOT_FOUND`.
   *
   * 2-state 로는 「자동 배정을 끄고 미할당으로 두기」를 표현할 수 없다.
   * `createIssue` 가 `'assigneeId' in input` 으로 키 존재를 판별하므로,
   * **`undefined` 를 명시적으로 넘겨도 키가 있는 것으로 취급되지 않도록** 주의한다.
   */
  assigneeId?: string | null
  /** FR-UX-09 F2 — 우선순위 1..5. 미전달이면 서버가 도메인 기본값 3(Medium)을 적용한다. */
  priority?: number
  /** FR-UX-09 F2 — 라벨. 미전달이면 빈 목록. 개수 ≤20 · 개별 길이 ≤50 · 공백-only 금지. */
  labels?: string[]
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
  /**
   * FR-PL-01 — 시작일 수정 (JsonNullable 3-state).
   * - undefined(미전달) = 무변경 (키 생략 → JSON.stringify 가 제거)
   * - null = 클리어 (키 존재 + 값 null → 백엔드가 DB NULL로 설정)
   * - "yyyy-MM-dd" 문자열 = 설정
   */
  startDate?: string | null
  /**
   * FR-PL-01 — 마감일(Due Date) 수정 (JsonNullable 3-state).
   * - undefined(미전달) = 무변경
   * - null = 클리어
   * - "yyyy-MM-dd" 문자열 = 설정
   */
  dueDate?: string | null
  /**
   * FR-PL-01 — 목표일(Target Date) 수정 (JsonNullable 3-state).
   * - undefined(미전달) = 무변경
   * - null = 클리어
   * - "yyyy-MM-dd" 문자열 = 설정
   */
  targetDate?: string | null
  /**
   * FR-TT-01 — 원본 추정 시간(초) 수정 (JsonNullable 3-state).
   * - undefined(미전달) = 무변경 (JSON.stringify가 키 제거)
   * - null = 클리어 (키 존재 + 값 null → 백엔드가 추정 초기화로 처리)
   * - number = 설정 (단위: 초)
   */
  originalEstimateSeconds?: number | null
  /**
   * FR-TT-01 — 잔여 추정 시간(초) 수정 (JsonNullable 3-state).
   * - undefined(미전달) = 무변경
   * - null = 클리어
   * - number = 설정 (단위: 초)
   */
  remainingEstimateSeconds?: number | null
  expectedVersion: number
}

/**
 * 이슈 목록 필터 파라미터.
 * GET /api/v1/issues 의 선택적 필터 쿼리를 표현한다.
 *
 * 백엔드 계약 (FR-SR-01, #180).
 * - 같은 필드의 다중 값은 OR 결합, 다른 필드 간은 AND 결합.
 * - statusKeys → 반복 `status=` 파라미터.
 * - assigneeIds + includeUnassigned → 반복 `assignee=` 파라미터 (unassigned 센티널 소문자 고정).
 * - labels → 반복 `label=` 파라미터.
 * - componentIds → 반복 `component=` 파라미터.
 */
export interface IssueFilterParams {
  /** 워크플로우 상태 키 목록. 각 항목마다 status= 파라미터를 하나씩 추가한다. */
  statusKeys: string[]
  /** 담당자 UUID 목록. 각 항목마다 assignee= 파라미터를 하나씩 추가한다. */
  assigneeIds: string[]
  /** true면 assignee=unassigned 파라미터를 추가해 미배정 이슈를 포함한다. */
  includeUnassigned: boolean
  /** 라벨 이름 목록. 각 항목마다 label= 파라미터를 하나씩 추가한다. */
  labels: string[]
  /** 컴포넌트 UUID 목록. 각 항목마다 component= 파라미터를 하나씩 추가한다. */
  componentIds: string[]
}

/**
 * 이슈 목록 정렬 허용 필드 목록 — 정렬 필드 계약 5종.
 *
 * `docs/plans/2026-07-24-fr-ux-06-phase-5-pr18-ui-table-split-view.md`의
 * "정렬 필드 계약"에서 T1(백엔드 whitelist)·T2(본 파일)·T4(IssueTable 정렬 헤더)·
 * T5(issues.index 라우트 sort URL 파싱)가 동일하게 사용하는 토큰이다.
 * status는 워크플로우 순서 의미가 모호해 이 목록에서 제외한다(G1).
 */
export const ISSUE_SORT_FIELDS = ['key', 'summary', 'priority', 'createdAt', 'updatedAt'] as const

/** 이슈 목록 정렬 필드 유니온 타입 — ISSUE_SORT_FIELDS에서 파생. */
export type IssueSortField = (typeof ISSUE_SORT_FIELDS)[number]

/** fetchIssues 쿼리 파라미터 */
export interface FetchIssuesParams {
  projectKey: string
  page: number
  size: number
  /** 선택적 필터. 미전달 시 기존 projectKey/page/size 쿼리만 전송 (하위호환). */
  filter?: IssueFilterParams
  /** 선택적 정렬. 미전달 시 sort 쿼리 파라미터를 전송하지 않는다 (하위호환, 서버 기본 정렬 유지). */
  sort?: { field: IssueSortField; dir: 'asc' | 'desc' }
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
 * 308 redirect(옛 키 → 새 키) 감지 처리 포함.
 * fetch `redirect:'follow'`(기본)가 308을 자동 추적하므로
 * `response.redirected === true`이면 `response.url`에서 새 키를 추출해
 * `IssueRedirectError`를 throw한다 (호출 측이 navigate로 처리).
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns IssueResponse — 백엔드 `{ data: IssueResponse }` 래퍼를 언래핑해 반환
 * @throws ApiError(404) 해당 key의 이슈가 없을 때
 * @throws IssueRedirectError 이슈가 이동되어 308 redirect된 경우
 */
export async function fetchIssue(key: string): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}`, { method: 'GET' })

  // 308 redirect 감지 — fetch redirect:follow(기본)가 자동 추적한 경우
  // response.redirected=true + response.url = /api/v1/issues/{newKey}
  if (res.redirected) {
    // URL 마지막 경로 세그먼트가 새 이슈 키
    const urlMatch = /\/issues\/([^/?#]+)/.exec(res.url)
    const newKey = urlMatch?.[1]
    if (newKey !== undefined && newKey !== '') {
      throw new IssueRedirectError(newKey)
    }
  }

  if (!res.ok) {
    if (res.status === 404) {
      throw new ApiError(404, { message: `이슈를 찾을 수 없습니다: ${key}` })
    }
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }

  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈 필터 파라미터를 URLSearchParams에 반영하는 헬퍼.
 *
 * 백엔드 계약 (FR-SR-01, #180).
 * - statusKeys 각 키 → `status=<key>` 반복 파라미터
 * - assigneeIds 각 UUID → `assignee=<uuid>` 반복 파라미터
 * - includeUnassigned=true → `assignee=unassigned` 센티널 추가 (소문자 고정)
 * - labels 각 이름 → `label=<name>` 반복 파라미터
 * - componentIds 각 UUID → `component=<uuid>` 반복 파라미터
 *
 * 빈 배열 및 includeUnassigned=false는 해당 키를 생략한다.
 *
 * @param query 기존 URLSearchParams 인스턴스 (in-place 수정)
 * @param filter 반영할 필터 파라미터. undefined이면 아무 처리도 하지 않는다.
 */
export function buildIssueFilterQuery(query: URLSearchParams, filter?: IssueFilterParams): void {
  if (filter === undefined) {
    return
  }
  for (const key of filter.statusKeys) {
    query.append('status', key)
  }
  for (const id of filter.assigneeIds) {
    query.append('assignee', id)
  }
  if (filter.includeUnassigned) {
    query.append('assignee', 'unassigned')
  }
  for (const label of filter.labels) {
    query.append('label', label)
  }
  for (const id of filter.componentIds) {
    query.append('component', id)
  }
}

/**
 * 프로젝트 이슈 목록을 페이징 조회한다.
 *
 * filter가 전달되면 status/assignee/label/component 파라미터를 추가한다.
 * filter 미전달 시 기존 projectKey/page/size 쿼리만 전송해 하위호환을 유지한다.
 * sort가 전달되면 `sort=<field>,<dir>` 파라미터를 추가한다 (백엔드 `listWithType` 정렬 허용목록과 매핑).
 * sort 미전달 시 sort 파라미터를 전송하지 않아 서버 기본 정렬(created_at desc)이 유지된다 (하위호환).
 *
 * @param params projectKey · page · size · filter(선택) · sort(선택) 쿼리 파라미터
 * @returns Spring Page 구조 — content 배열 + 페이징 메타 (래퍼 없음)
 */
export async function fetchIssues(params: FetchIssuesParams): Promise<IssuePage> {
  const query = new URLSearchParams({
    projectKey: params.projectKey,
    page: String(params.page),
    size: String(params.size),
  })
  buildIssueFilterQuery(query, params.filter)
  if (params.sort) {
    query.append('sort', `${params.sort.field},${params.sort.dir}`)
  }
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
  // FR-UX-09 F2 — typeId·description·priority·labels 는 미전달 시 키를 빼 서버 기본값에 위임한다.
  if (input.typeId !== undefined) {
    body['typeId'] = input.typeId
  }
  if (input.description !== undefined) {
    body['description'] = input.description
  }
  if (input.priority !== undefined) {
    body['priority'] = input.priority
  }
  if (input.labels !== undefined) {
    body['labels'] = input.labels
  }
  // FR-UX-09 F2 — assigneeId 는 3-state 라 `!== undefined` 가 아니라 **키 존재**로 판별한다.
  // 명시 null(미할당 확정)과 키 부재(자동 배정 유지)가 서버에서 다른 동작이므로,
  // null 을 undefined 와 같이 취급하면 「자동 배정 끄기」를 영영 표현할 수 없다.
  if ('assigneeId' in input) {
    body['assigneeId'] = input.assigneeId
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

/** backend `{ data: { transitions: [...] } }` 전환 목록 응답 파싱 헬퍼 (내부 전용) */
const transitionsResponseSchema = z.object({
  data: z.object({ transitions: z.array(issueTransitionSchema) }),
})

/**
 * 이슈의 현재 상태에서 가용한 전환 목록을 조회한다.
 * GET /api/v1/issues/{key}/transitions → { data: { transitions: IssueTransition[] } }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 전환 항목 배열 — 현재 상태에서 이동 가능한 전환들
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
 * 이슈 상태를 전환한다.
 * POST /api/v1/issues/{key}/transition body { toStatusKey, expectedVersion }
 * 성공 200 시 변경된 IssueResponse를 반환한다.
 * 낙관적 잠금(OCC) 충돌 시 ApiError(409)를 throw한다.
 *
 * 409 는 세 가지가 섞여 온다 — 낙관적 잠금 충돌(`VERSION_CONFLICT`) · 전환 불허
 * (`TRANSITION_NOT_ALLOWED`) · 모호 전환(`AMBIGUOUS_TRANSITION`). 마지막 것만
 * {@link parseAmbiguousTransitionError} 로 갈라 후보 선택 UI 로 넘긴다.
 *
 * @param key 전환할 이슈 식별 키
 * @param input toStatusKey(목표 상태키) · expectedVersion(현재 버전, OCC용) · transitionId(후보 지목)
 * @returns 전환 완료된 IssueResponse — currentStateKey와 version이 갱신된 상태
 * @throws ApiError(404) 이슈가 없을 때
 * @throws ApiError(409) 낙관적 잠금 충돌 · 전환 불허 · 모호 전환 시
 * @throws ApiError(422) 유효하지 않은 전환 요청 시
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
 * 여러 이슈에 공통으로 적용 가능한 전환 교집합을 조회한다.
 * POST /api/v1/issues/bulk-transitions/available body { issueKeys }
 * 성공 200 시 { transitions, unresolvedIssueKeys }를 반환한다.
 * 미존재·워크플로우 미설정 이슈는 unresolvedIssueKeys에 포함되고 교집합에서 제외된다.
 *
 * @param issueKeys 가용 전환을 조회할 이슈 키 목록
 * @returns transitions(공통 전환 교집합) + unresolvedIssueKeys(조회 실패 키 목록)
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
