// @멘션 추출·actor 도출 공용 헬퍼 — 설명 경로와 댓글 경로가 같은 규칙을 쓰게 하는 리프 모듈

import { AUTH_USERS } from './auth-fixtures'

/**
 * 텍스트에서 `@username` 을 추출한다 (백엔드 `MentionParser.MENTION_PATTERN` 미러).
 *
 * ## 왜 리프 모듈인가
 * 원래 `issue-handlers.ts` 안에 있었고 댓글 경로가 그것을 import 하자 **순환 의존**이 생겨
 * 핸들러 모듈이 통째로 로드에 실패했다(2026-09-05 실측 — 댓글 POST 가 500). 규칙을 복사하면
 * 두 경로가 서로를 검사하지 않아 조용히 갈리므로, 복사 대신 **의존 없는 리프**로 내렸다.
 *
 * @param text 원본 텍스트(마크다운 또는 HTML).
 * @returns 중복 제거된 username 배열 (`@` 제외).
 */
export function extractMentionedUsernames(text: string): string[] {
  // 코드스팬(`...`)을 제거해 내부 @ 를 보호
  const withoutCode = text.replace(/`[^`]*`/g, '')
  // 이메일/겹침/선행구두점 @ 제외: 앞에 [A-Za-z0-9._@-] 가 없는 @ 만 매칭
  const matches = withoutCode.matchAll(/(?<![A-Za-z0-9._@-])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)/g)
  return [...new Set([...matches].map((m) => m[1] as string))]
}

/** mock access token 접두사 — auth-fixtures.mockAccessToken 과 동일 형식 */
const MENTION_MOCK_TOKEN_PREFIX = 'mock-access-token-'

/**
 * Authorization Bearer 헤더에서 요청자(actor)의 userId 를 도출한다.
 *
 * 자기 자신 멘션 제외와 `actorUserId` 기록에 쓴다.
 */
export function resolveActorUserIdFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null
  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MENTION_MOCK_TOKEN_PREFIX)) return null
  const username = token.slice(MENTION_MOCK_TOKEN_PREFIX.length)
  return AUTH_USERS[username]?.userId ?? null
}
