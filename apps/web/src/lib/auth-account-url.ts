// 빌드 env VITE_AUTH_ACCOUNT_URL(Auth_platform 계정 화면 주소)을 읽는 단일 출처 — 비밀번호 · MFA · PAT 링크가 쓴다

/** 렌더 때 부른다(test 가 vi.stubEnv 로 바꿀 수 있게). 비면 undefined — BTS 화면을 그대로 쓰고 PAT 항목은 숨긴다 */
export function authAccountUrl(): string | undefined {
  return import.meta.env['VITE_AUTH_ACCOUNT_URL'] || undefined
}
