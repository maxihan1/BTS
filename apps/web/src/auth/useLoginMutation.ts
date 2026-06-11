// 로그인 mutation 훅 — POST /api/v1/auth/login 후 union 분기(MFA/정상) + whoami 연쇄 호출
import { useMutation } from '@tanstack/react-query'
import { apiGet, ApiError } from '@/api/client'
import {
  LoginRequestSchema,
  TokenResponseSchema,
  MfaRequiredResponseSchema,
  WhoamiResponseSchema,
  ApiErrorResponseSchema,
} from '@/api/schemas'
import type { LoginRequest, WhoamiResponse } from '@/api/schemas'
import { useAuthStore } from './authStore'

/**
 * 백엔드 에러 코드 → 사용자에게 보여줄 한국어 메시지 매핑.
 * mfa_required 에러 코드 경로는 제거됨 — login 200 mfa_required:true 응답은 에러가 아니라
 * 2단계 진입 신호로 처리한다.
 */
const LOGIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  invalid_credentials: '사용자명 또는 비밀번호가 올바르지 않습니다.',
  unknown_provider: '지원하지 않는 로그인 방식입니다. 다시 시도해 주세요.',
}

const DEFAULT_LOGIN_ERROR_MESSAGE = '로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * ApiError의 body에서 에러 코드를 추출해 한국어 메시지를 반환하는 헬퍼.
 * 알 수 없는 에러 코드면 기본 메시지를 반환한다.
 */
function resolveLoginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return DEFAULT_LOGIN_ERROR_MESSAGE
  }

  const parsedBody = ApiErrorResponseSchema.safeParse(error.body)
  if (!parsedBody.success) {
    return DEFAULT_LOGIN_ERROR_MESSAGE
  }

  return LOGIN_ERROR_MESSAGES[parsedBody.data.error] ?? DEFAULT_LOGIN_ERROR_MESSAGE
}

/**
 * login mutationFn이 반환하는 tagged union 결과 타입.
 *
 * - `kind: 'success'` — 정상 로그인 완료. token + user 포함. authStore 세션 저장 완료.
 * - `kind: 'mfa_required'` — MFA 챌린지 진입 신호. challengeToken만 반환. authStore 변경 없음.
 */
export type LoginResult =
  | { kind: 'success'; accessToken: string; user: WhoamiResponse }
  | { kind: 'mfa_required'; challengeToken: string }

/**
 * 로그인 mutation.
 *
 * **분기 규칙 (mfa_required 우선 검사 — CONCERN-union).**
 * login 200 응답의 `mfa_required` 필드를 Zod parse 전에 먼저 검사한다.
 * - `mfa_required === true` → MfaRequiredResponseSchema.parse → `kind: 'mfa_required'` 반환
 *   (authStore 변경 없음. access_token이 동봉돼 있어도 무조건 무시)
 * - 그 외 → TokenResponseSchema.parse → whoami 호출 → `kind: 'success'` 반환
 *
 * **MFA 우회 방지:** "access_token 존재 여부"로 분기하면 mfa_required:true + access_token
 * 동봉 응답이 정상 로그인으로 처리될 수 있다. 이 구현은 mfa_required 플래그를 최우선 기준으로 삼아
 * 이 취약점을 원천 차단한다.
 *
 * 실패 시 authStore를 변경하지 않고 한국어 메시지가 담긴 Error를 throw한다.
 */
export function useLoginMutation() {
  const setAccessToken = useAuthStore((s) => s.setAccessToken)
  const setSession = useAuthStore((s) => s.setSession)
  const clearSession = useAuthStore((s) => s.clearSession)

  return useMutation<LoginResult, Error, LoginRequest>({
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
        throw new Error(resolveLoginErrorMessage(new ApiError(loginRes.status, errorBody)))
      }

      const responseData: unknown = await loginRes.json()

      // ── mfa_required 우선 분기 (parse 전에 플래그 검사) ────────────────
      // responseData가 객체이고 mfa_required === true인지 먼저 확인한다.
      // access_token이 함께 내려와도 mfa_required:true이면 MFA step으로 진입한다(CONCERN-union).
      if (
        typeof responseData === 'object' &&
        responseData !== null &&
        'mfa_required' in responseData &&
        (responseData as Record<string, unknown>)['mfa_required'] === true
      ) {
        const mfaData = MfaRequiredResponseSchema.parse(responseData)
        return { kind: 'mfa_required', challengeToken: mfaData.mfa_challenge_token }
      }

      // ── 정상 토큰 경로 ─────────────────────────────────────────────────
      const token = TokenResponseSchema.parse(responseData)

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

      return { kind: 'success', accessToken: token.access_token, user }
    },
  })
}
