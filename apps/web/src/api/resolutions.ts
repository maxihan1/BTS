// 결의안(Resolution) BC REST API 클라이언트 + Zod 스키마 (FR-IS-07)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 ResolutionResponse DTO와 1:1 대응 (B4 확인)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 결의안 단건 응답 Zod 스키마.
 * 백엔드 `ResolutionResponse` DTO 직렬화 형태와 1:1 대응.
 * - `description`: null 허용 (선택적 설명)
 * - UUID 검증: Zod v4 형식 (3번째 그룹 4, 4번째 그룹 8~b)
 */
export const resolutionSchema = z.object({
  /** DB PK (UUID) */
  id: z.string().uuid(),
  /** URL-safe 소문자 슬러그. 예: "fixed" */
  key: z.string().min(1),
  /** 표시 이름 */
  name: z.string().min(1),
  /** 선택적 설명. null 허용 */
  description: z.string().nullable(),
  /** 목록 표시 순서. 1부터 시작. displayOrder asc 정렬로 반환됨 */
  displayOrder: z.number().int().positive(),
  /** 표준 Resolution 여부 */
  isStandard: z.boolean(),
})

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 (내부 전용) */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 결의안 단건 타입 */
export type Resolution = z.infer<typeof resolutionSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/** 결의안 목록 응답 스키마 (내부 전용) */
const resolutionsResponseSchema = dataResponseSchema(z.array(resolutionSchema))

/**
 * 전체 결의안 목록을 조회한다.
 *
 * GET /api/v1/resolutions → `{ data: Resolution[] }` (displayOrder asc)
 * 인증 필요 — Authorization 헤더는 apiFetch가 자동 주입한다.
 *
 * @returns Resolution[] — 백엔드 `{ data: [...] }` 래퍼를 언래핑해 반환
 * @throws ApiError 비-2xx 응답 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchResolutions(): Promise<Resolution[]> {
  const wrapped = await apiGet('/api/v1/resolutions', resolutionsResponseSchema)
  return wrapped.data
}
