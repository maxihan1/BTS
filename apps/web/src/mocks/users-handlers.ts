// FR-PM-01 사용자 디렉토리 검색 MSW 핸들러 — GET /api/v1/users?query= (substring 필터)
import { http, HttpResponse } from 'msw'
import { userDirectoryFixtures } from './project-member-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users?query=
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 디렉토리 검색 핸들러.
 *
 * query 파라미터로 username 또는 displayName에 대해 대소문자 구분 없이 substring 필터링한다.
 * 응답 형태: 배열 직접 (래퍼 없음) — backend UserSearchResponse[] 계약과 동일.
 * query 가 빈 문자열이면 전체 목록을 반환한다.
 */
const searchUsersHandler = http.get('/api/v1/users', ({ request }) => {
  const url = new URL(request.url)
  const query = url.searchParams.get('query') ?? ''
  const lowerQuery = query.toLowerCase()

  if (lowerQuery === '') {
    return HttpResponse.json(userDirectoryFixtures)
  }

  const matched = userDirectoryFixtures.filter(
    (u) =>
      u.username.toLowerCase().includes(lowerQuery) ||
      u.displayName.toLowerCase().includes(lowerQuery) ||
      u.email.toLowerCase().includes(lowerQuery),
  )

  return HttpResponse.json(matched)
})

/** 사용자 디렉토리 MSW 핸들러 배열 */
export const usersHandlers = [searchUsersHandler]
