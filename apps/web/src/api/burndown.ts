// 스프린트 번다운/번업 API 클라이언트 — Zod 스키마 + fetch 함수 (FR-RP-01 D6/D7)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 유니온 타입 — 차트 뷰 종류
// ─────────────────────────────────────────────────────────────────────────────

/** 차트 뷰 종류 — 번다운(잔여) / 번업(완료) */
export type BurndownView = 'burndown' | 'burnup'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO(BurndownResponse/BurndownPointResponse, PR #219)와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 번다운 시계열 단건 포인트 Zod 스키마.
 * 백엔드 BurndownPointResponse DTO와 1:1 대응.
 * - date: 시계열 날짜 (ISO "YYYY-MM-DD")
 * - remainingSeconds: 잔여 추정시간(초) — 미래일 null 가능
 * - idealSeconds: 이상선(초)
 * - completedSeconds: 완료 누계(초) — 미래일 null 가능
 * - scopeSeconds: 해당 시점 범위(초)
 */
export const burndownPointSchema = z.object({
  date: z.string(),
  remainingSeconds: z.number().nullish(),
  idealSeconds: z.number(),
  completedSeconds: z.number().nullish(),
  scopeSeconds: z.number(),
})

/** 번다운 시계열 포인트 타입 — Zod 스키마에서 추론 */
export type BurndownPoint = z.infer<typeof burndownPointSchema>

/**
 * 번다운 세로축의 **단위** — 백엔드 `BurndownUnit` 열거형과 1:1 (부채 177 task-35).
 *
 * 정본은 `backend/modules/agile-planning/.../domain/burndown/BurndownUnit.kt` 다.
 * 보드 「추정」 탭의 `time_tracking` 이 고른다(J36 · `SprintBurndownService.resolveUnit`).
 * - `REMAINING_AND_SPENT` → `SECONDS`
 * - `NONE` → `ISSUE_COUNT`
 *
 * ★★**`point.remainingSeconds` 같은 필드 이름이 계약을 말하지 못한다.** `ISSUE_COUNT` 축에서
 * 그 값들은 초가 아니라 **개수**다. 백엔드가 이름을 안 바꾼 이유는 그것이 이미 공개 계약이기
 * 때문이고, 대신 이 필드를 함께 실어 소비측이 단위를 오해할 수 없게 했다.
 * **그러니 값을 그릴 때는 반드시 이 값을 보고 포맷을 갈라야 한다** — 안 가르면 「12개」가
 * 「12초」로 보인다(`BurndownChart` 가 그 분기의 유일한 자리다).
 *
 * ★`z.string()` 이 아니라 `z.enum` 인 이유 — 서버가 오타를 내면 화면이 그것을 축 이름으로 믿는다.
 */
export const burndownUnitSchema = z.enum(['SECONDS', 'ISSUE_COUNT'])

/** 번다운 세로축 단위 — Zod 스키마에서 추론 */
export type BurndownUnit = z.infer<typeof burndownUnitSchema>

/**
 * 스프린트 번다운/번업 응답 Zod 스키마.
 * GET /api/v1/sprints/{sprintId}/burndown 의 data 필드 형식.
 * - sprintId: 조회한 스프린트 ID
 * - projectKey: 소속 프로젝트 키
 * - status: 스프린트 상태 (SprintStatus enum 문자열)
 * - startDate/endDate: 스프린트 시작/종료일 (ISO "YYYY-MM-DD")
 * - totalScopeSeconds: 전체 범위 합계 — 단위는 `unit` 이 정한다(초 또는 이슈 수)
 * - unit: 세로축 단위 (`SECONDS` / `ISSUE_COUNT`)
 * - points: 시계열 포인트 배열
 *
 * ★**`unit` 은 optional 이 아니다.** 백엔드 `BurndownResponse.unit` 이 non-null 이라
 * 「없음」은 정상 응답이 아니라 계약 위반이다. `.optional()` 로 받아 `?? 'SECONDS'` 로 채우면
 * **모르는 축을 아는 척**하게 되고, 그것이 이 필드가 막으려는 거짓말과 정확히 같은 것이다.
 * 못 읽으면 파싱이 실패하고 화면은 오류 상태를 그린다 — 그편이 조용히 틀리는 것보다 낫다.
 */
export const burndownResponseSchema = z.object({
  sprintId: z.string(),
  projectKey: z.string(),
  status: z.string(),
  startDate: z.string(),
  endDate: z.string(),
  totalScopeSeconds: z.number(),
  unit: burndownUnitSchema,
  points: z.array(burndownPointSchema),
})

/** 스프린트 번다운/번업 응답 타입 — Zod 스키마에서 추론 */
export type BurndownResponse = z.infer<typeof burndownResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트의 번다운/번업 시계열을 조회한다.
 *
 * GET /api/v1/sprints/{sprintId}/burndown → 200 { data: BurndownResponse }
 *
 * @param sprintId 조회할 스프린트 ID (UUID)
 * @returns 번다운/번업 시계열 응답
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 * @throws ApiError(404) 스프린트 없음
 * @throws ApiError(422) 스프린트 시작/종료일 미설정 (SPRINT_DATES_REQUIRED)
 */
export async function fetchSprintBurndown(sprintId: string): Promise<BurndownResponse> {
  const res = await apiFetch(`/api/v1/sprints/${sprintId}/burndown`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(burndownResponseSchema).parse(raw).data
}
