// identity-access BC 사용자 목록 조회 + 사용자 생성 REST API client + Zod 스키마
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend UserSummaryResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 요약 응답 Zod 스키마.
 * GET /api/v1/users 배열 응답 항목 형태 — { data: } 래퍼 없음.
 */
export const userSummarySchema = z.object({
  id: z.string().uuid(),
  username: z.string().min(1),
  /** 표시 이름 — 미설정 시 null */
  displayName: z.string().nullable(),
  /** 이메일 — 미노출 설정 또는 없을 때 null */
  email: z.string().nullable(),
})

/** 사용자 요약 타입 */
export type UserSummary = z.infer<typeof userSummarySchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 목록을 조회한다.
 *
 * @param query 검색 문자열 — 미전달 시 전체 목록(최대 MAX_RESULTS건) 반환.
 *              username/displayName 부분일치 검색.
 * @returns UserSummary[] — 배열 직접 응답 (래퍼 없음)
 * @throws ApiError(401) 인증 실패 시
 */
export async function fetchUsers(query?: string): Promise<UserSummary[]> {
  const params = new URLSearchParams()
  if (query !== undefined && query !== '') {
    params.set('query', query)
  }
  const queryString = params.toString()
  const path = queryString !== '' ? `/api/v1/users?${queryString}` : '/api/v1/users'
  return apiGet(path, z.array(userSummarySchema))
}

// ─────────────────────────────────────────────────────────────────────────────
// 사용자 생성 API — POST /api/v1/users (SYSTEM_ADMIN 전용)
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 생성 에러 코드 — 백엔드 enum.name 과 1:1 (대문자 스네이크 고정) */
export const CreateUserErrorCode = {
  USERNAME_TAKEN: 'USERNAME_TAKEN',
} as const

/**
 * 사용자 생성 요청 파라미터.
 * 백엔드 CreateUserRequest DTO 와 1:1 정합.
 */
export interface CreateUserParams {
  /** 로그인 식별자 — @NotBlank, unique */
  username: string
  /** 이메일 주소 — 선택 */
  email?: string
  /** 화면 표시 이름 — @NotBlank */
  displayName: string
}

/**
 * 사용자 생성 성공 응답 Zod 스키마.
 * 백엔드: 201 `{ id, username, temporaryPassword }` (UsersController.kt)
 */
const createUserResponseSchema = z.object({
  /** 생성된 사용자 UUID */
  id: z.string().uuid(),
  /** 로그인 식별자 */
  username: z.string().min(1),
  /**
   * 임시 비밀번호 — 응답 표시만 허용.
   * localStorage/로그 저장 절대 금지 (§1.1, DEVELOPMENT.md).
   */
  temporaryPassword: z.string().min(1),
})

/** 사용자 생성 성공 응답 타입 */
export type CreateUserResponse = z.infer<typeof createUserResponseSchema>

/**
 * 사용자를 생성한다. SYSTEM_ADMIN 전용 엔드포인트.
 *
 * `POST /api/v1/users`
 * - X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다 (double submit cookie 패턴).
 * - 성공 시 201 응답 Zod 파싱 후 `{ id, username, temporaryPassword }` 반환.
 * - 비-2xx 시 `ApiError(status, body)` throw — body.code 보존.
 *
 * @param params username / email(선택) / displayName
 * @returns 생성된 사용자 정보 + 임시 비밀번호
 * @throws ApiError(409) USERNAME_TAKEN
 * @throws ApiError(400) 검증 실패 (@NotBlank 등)
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 권한 없음 (SYSTEM_ADMIN 전용)
 */
export async function createUser(params: CreateUserParams): Promise<CreateUserResponse> {
  const res = await apiFetch('/api/v1/users', {
    method: 'POST',
    body: params,
    headers: {
      // double submit cookie 패턴: 쿠키의 XSRF-TOKEN 값을 헤더로 재전송
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })

  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }

  return createUserResponseSchema.parse(await res.json())
}

/**
 * 사용자 id 다건 조회.
 * 현재 담당자 이름을 안정적으로 표시하기 위해 사용한다 (C1 버그 수정).
 * GET /api/v1/users?ids=<uuid>,<uuid>,... — ids 우선 모드.
 *
 * @param ids UUID 문자열 배열 — 빈 배열이면 네트워크 호출 없이 [] 반환.
 * @returns UserSummary[] — 미존재 id는 결과에서 조용히 제외.
 * @throws ApiError(401) 인증 실패 시
 */
export async function fetchUsersByIds(ids: string[]): Promise<UserSummary[]> {
  if (ids.length === 0) {
    return []
  }
  const path = `/api/v1/users?ids=${ids.join(',')}`
  return apiGet(path, z.array(userSummarySchema))
}
