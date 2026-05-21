// 로그인 mutation 훅 — POST /api/v1/auth/login 후 GET /api/v1/users/me/whoami 연쇄 호출
import { useMutation } from '@tanstack/react-query'
import { apiGet, ApiError } from '@/api/client'
import { LoginRequestSchema, TokenResponseSchema, WhoamiResponseSchema, ApiErrorResponseSchema } from '@/api/schemas'
import type { LoginRequest } from '@/api/schemas'
import { useAuthStore } from './authStore'

/**
 * 백엔드 에러 코드 → 사용자에게 보여줄 한국어 메시지 매핑.
 * T14에서 i18n/ko.ts로 옮길 예정. 현재는 이 파일 내부 임시 const로 관리.
 */
const LOGIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  invalid_credentials: '사용자명 또는 비밀번호가 올바르지 않습니다.',
  mfa_required: '추가 인증이 필요합니다. 관리자에게 문의하세요.',
}

const DEFAULT_LOGIN_ERROR_MESSAGE = '로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * ApiError의 body에서 에러 코드를 추출해 한국어 메시지를 반환하는 헬퍼.
 * 알 수 없는 코드면 기본 메시지를 반환한다.
 */
function resolveLoginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return DEFAULT_LOGIN_ERROR_MESSAGE
  }

  const body = error.body
  if (
    typeof body === 'object' &&
    body !== null &&
    'error' in body &&
    typeof (body as Record<string, unknown>)['error'] === 'string'
  ) {
    const code = (body as Record<string, string>)['error']
    return LOGIN_ERROR_MESSAGES[code] ?? DEFAULT_LOGIN_ERROR_MESSAGE
  }

  return DEFAULT_LOGIN_ERROR_MESSAGE
}

/**
 * 로그인 mutation.
 *
 * 1. POST /api/v1/auth/login → TokenResponse
 * 2. authStore에 accessToken 즉시 저장 (whoami 호출 시 Authorization 헤더에 필요)
 * 3. GET /api/v1/users/me/whoami → WhoamiResponse
 * 4. authStore에 user 추가 저장 (setSession으로 두 값 함께 갱신)
 *
 * 실패 시 authStore를 변경하지 않고 한국어 메시지가 담긴 Error를 throw한다.
 */
export function useLoginMutation() {
  const setAccessToken = useAuthStore((s) => s.setAccessToken)
  const setSession = useAuthStore((s) => s.setSession)
  const clearSession = useAuthStore((s) => s.clearSession)

  return useMutation({
    mutationFn: async (credentials: LoginRequest) => {
      // Zod 스키마로 요청 데이터 검증
      const parsed = LoginRequestSchema.parse(credentials)

      // 1단계. 로그인 API 호출.
      // apiFetch 대신 fetch 직접 사용 — apiFetch는 401 응답 시 /refresh를 자동 시도하는데,
      // 로그인 실패(invalid_credentials 등) 401은 refresh 대상이 아니므로 bypass한다.
      const loginRes = await fetch('/api/v1/auth/login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed),
      })

      if (!loginRes.ok) {
        const errorBody: unknown = await loginRes.json().catch(() => ({}))
        const parsed_error = ApiErrorResponseSchema.safeParse(errorBody)
        const apiError = new ApiError(
          loginRes.status,
          parsed_error.success ? parsed_error.data : errorBody,
        )
        throw new Error(resolveLoginErrorMessage(apiError))
      }

      const tokenData: unknown = await loginRes.json()
      const token = TokenResponseSchema.parse(tokenData)

      // 2단계. access token 저장 — whoami 호출 시 Authorization 헤더 자동 주입에 필요
      setAccessToken(token.access_token)

      // 3단계. whoami 호출로 사용자 정보 조회 (apiGet은 refresh 인터셉터 포함이지만,
      // 이 시점에서 토큰이 이미 저장되어 있으므로 정상 동작)
      const user = await apiGet('/api/v1/users/me/whoami', WhoamiResponseSchema).catch(
        (err: unknown) => {
          // whoami 실패 시 토큰도 롤백
          clearSession()
          throw new Error(resolveLoginErrorMessage(err))
        },
      )

      // 4단계. 최종 세션 저장 (accessToken + user 함께)
      setSession({ accessToken: token.access_token, user })

      return { token, user }
    },
  })
}
