// 댓글 MSW 핸들러 단위 테스트 — 백엔드와 판정 순서(이슈 UPDATE 게이트 우선) 정합 검증
import { server } from '@/test/server'
import { describe, it, expect } from 'vitest'
import { commentHandlers } from './comment-handlers'
import { mockAccessToken } from './auth-fixtures'

beforeEach(() => {
  server.use(...commentHandlers)
})
/** 지정 사용자로 요청한다 — 핸들러는 Bearer 토큰에서 username 을 해석한다. */
function as(username: string): HeadersInit {
  return { Authorization: `Bearer ${mockAccessToken(username)}` }
}

const ISSUE = 'ATLAS-1'
const MISSING_COMMENT = '99999999-9999-4999-8999-999999999999'

/**
 * **판정 순서 정합 (TODOS §댓글 MSW 모크가 백엔드와 다르다 — (b) 판정 순서 역전).**
 *
 * 백엔드 `CommentApplicationService` 는 **이슈 `UPDATE` 게이트를 댓글 조회보다 먼저** 통과시킨다
 * (`update:194` → `:200`, `delete:279` → `:290`). 즉 권한 없는 사용자는 **댓글 존재 여부와
 * 무관하게 403** 을 받는다 — 존재하지 않는 댓글 id 로도 403 이다.
 *
 * 모크에는 그 게이트가 **아예 없어** 댓글 조회 404 를 먼저 냈다. 같은 상황에서 **404** 가 나가
 * 「권한이 없다」와 「그런 댓글이 없다」가 뒤바뀐다.
 *
 * ★이 갭이 오래 안 보인 이유는 **`UPDATE=false` 인 픽스처 사용자가 없어서**다.
 * alice(ADMIN)·bob(MEMBER) 둘 다 `UPDATE=true` 라 어떤 테스트도 게이트를 밟지 못했다.
 * `carol`(VIEWER) + `viewerPermissionsFixture` 가 그 판별자다.
 *
 * 판별식 — **"백엔드가 리소스를 resolve 하기 전에 통과시키는 게이트를, 모크도 resolve 전에
 * 통과시키는가."** 존재하지 않는 댓글 id 로 요청했을 때의 상태코드가 그것을 드러낸다.
 */
describe('commentHandlers — 이슈 UPDATE 게이트가 댓글 조회보다 먼저다', () => {
  it('PATCH — UPDATE 없는 사용자는 없는 댓글에도 403 (404 아님)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/comments/${MISSING_COMMENT}`, {
      method: 'PATCH',
      headers: { ...as('carol'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: '수정 시도' }),
    })

    expect(res.status).toBe(403)
  })

  it('DELETE — UPDATE 없는 사용자는 없는 댓글에도 403 (404 아님)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/comments/${MISSING_COMMENT}`, {
      method: 'DELETE',
      headers: as('carol'),
    })

    expect(res.status).toBe(403)
  })

  it('POST — UPDATE 없는 사용자는 작성도 403', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/comments`, {
      method: 'POST',
      headers: { ...as('carol'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: '작성 시도' }),
    })

    expect(res.status).toBe(403)
  })

  /**
   * 대조군 — 게이트가 "전부 403" 으로 무너지지 않았음을 확인한다.
   * 이 단언이 없으면 게이트를 과하게 걸어도 위 3건이 통과해 초록으로 보인다.
   */
  it('대조군 — UPDATE 보유자는 없는 댓글에 404 (게이트 통과 후 조회 실패)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/comments/${MISSING_COMMENT}`, {
      method: 'PATCH',
      headers: { ...as('bob'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: '수정 시도' }),
    })

    expect(res.status).toBe(404)
  })

  it('대조군 — UPDATE 보유자는 댓글 작성에 성공한다', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/comments`, {
      method: 'POST',
      headers: { ...as('bob'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: '정상 작성' }),
    })

    expect(res.status).toBe(201)
  })
})
