// ApiError.body에서 표준 에러코드를 추출하는 공유 유틸 (계정 연결 등 식별자-access 에러 응답 정합)

/**
 * 백엔드 에러 응답 body에서 에러코드 문자열을 추출한다.
 *
 * 식별자-access 컨트롤러는 에러를 `{ "error": <code> }` 형태로 내려준다
 * (AccountLinkJwtSupport.errorResponse). 일부 ProblemDetail-like 응답은 `errorCode`
 * 키를 쓸 수 있으므로 둘 다 확인한다(`errorCode` 우선). body가 객체가 아니거나
 * 두 키 모두 문자열이 아니면 null을 반환한다.
 *
 * 단일 구현으로 두기 위해 ReauthDialog와 account-links 라우트가 공유한다 — 키 정책이
 * 컴포넌트별로 갈리면 한쪽만 `error`를 못 읽어 실패 메시지가 일반화되는 drift가 생긴다
 * (PR #106 코드리뷰 B1).
 *
 * @param body ApiError.body (unknown)
 * @returns 추출된 에러코드 문자열, 없으면 null
 */
export function extractErrorCode(body: unknown): string | null {
  if (body === null || typeof body !== 'object') return null
  const b = body as Record<string, unknown>
  const code = b['errorCode'] ?? b['error']
  return typeof code === 'string' ? code : null
}
