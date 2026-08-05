// identity-access BC 사용자 목록 MSW mock handlers — GET /api/v1/users
import { http, HttpResponse } from 'msw'
import { userListFixture } from './user-fixtures'

/**
 * 사용자 다건 조회(`?ids=`) 1회 상한.
 *
 * 백엔드 `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/
 * UsersController.kt`의 `MAX_RESULTS`(= 50) **미러**다. 그쪽은 상한 초과 시 400을 주는데
 * (`fetchByIds`), mock이 그냥 필터링해 200을 돌려주면 **mock이 현실보다 관대해져**
 * "51개를 한 번에 보내는" 계약 위반을 유닛·E2E 어느 쪽도 잡지 못한다(프로덕션에서만 터진다).
 * 프론트 측 짝은 `src/hooks/use-users.ts`의 `USERS_BY_IDS_CHUNK_SIZE`(같은 50)다.
 *
 * 세는 기준의 의도적 차이. 백엔드는 UUID로 **파싱되는** 토큰만 세지만, 이 mock은 UUID 파싱을
 * 모델링하지 않으므로 비어 있지 않은 토큰을 전부 센다. 방향은 항상 "백엔드보다 관대하지 않게"다
 * — 백엔드가 400을 줄 요청에 mock이 200을 주는 일은 없다.
 */
const MAX_IDS_PER_REQUEST = 50

/**
 * GET /api/v1/users — 사용자 목록 조회 핸들러.
 *
 * ids 파라미터 모드 (ids 우선, 백엔드 분기와 일치):
 *   ?ids=<uuid>,<uuid>,... → 해당 id들의 사용자만 반환 (미존재 id 조용히 제외)
 *   id 개수가 {@link MAX_IDS_PER_REQUEST} 초과면 400 (백엔드 UsersController와 동일)
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
    const ids = idsParam
      .split(',')
      .map((id) => id.trim())
      .filter((id) => id !== '')

    // 개수 상한 — 백엔드 UsersController.MAX_RESULTS 미러 (초과 시 400)
    if (ids.length > MAX_IDS_PER_REQUEST) {
      return new HttpResponse(null, { status: 400 })
    }

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
