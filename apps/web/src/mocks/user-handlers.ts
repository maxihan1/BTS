// identity-access BC 사용자 목록 MSW mock handlers — GET /api/v1/users
import { http, HttpResponse } from 'msw'
import { userListFixture } from './user-fixtures'

/**
 * GET /api/v1/users — 사용자 목록 조회 핸들러.
 *
 * ids 파라미터 모드 (ids 우선, 백엔드 분기와 일치):
 *   ?ids=<uuid>,<uuid>,... → 해당 id들의 사용자만 반환 (미존재 id 조용히 제외)
 *
 * query 파라미터 모드 (ids 없을 때):
 *   ?query=<검색어> → username/displayName 부분일치 필터링
 *   query 없으면 전체 목록 반환
 *
 * 응답: UserSummary[] (배열 직접, {data:} 래퍼 없음).
 */
const listUsersHandler = http.get('/api/v1/users', ({ request }) => {
  const url = new URL(request.url)
  const idsParam = url.searchParams.get('ids')

  // ids 파라미터 모드 — 백엔드 분기 순서와 일치 (ids 우선)
  if (idsParam !== null && idsParam !== '') {
    const ids = idsParam.split(',').map((id) => id.trim())
    const filtered = userListFixture.filter((u) => ids.includes(u.id))
    return HttpResponse.json(filtered)
  }

  // query 파라미터 모드
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
