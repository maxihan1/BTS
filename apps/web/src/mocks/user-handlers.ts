// identity-access BC 사용자 목록 MSW mock handlers — GET /api/v1/users
import { http, HttpResponse } from 'msw'
import { userListFixture } from './user-fixtures'

/**
 * GET /api/v1/users — 사용자 목록 조회 핸들러.
 * query 파라미터가 있으면 username/displayName 부분일치 필터링.
 * 응답: UserSummary[] (배열 직접, {data:} 래퍼 없음).
 */
const listUsersHandler = http.get('/api/v1/users', ({ request }) => {
  const url = new URL(request.url)
  const query = url.searchParams.get('query') ?? ''

  if (query === '') {
    return HttpResponse.json(userListFixture)
  }

  const lowerQuery = query.toLowerCase()
  const filtered = userListFixture.filter(
    (u) =>
      u.username.toLowerCase().includes(lowerQuery) ||
      (u.displayName !== null && u.displayName.toLowerCase().includes(lowerQuery)),
  )
  return HttpResponse.json(filtered)
})

export const userHandlers = [listUsersHandler]
