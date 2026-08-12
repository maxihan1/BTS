// 워크로그 MSW 핸들러 단위 테스트 — 백엔드와 판정 순서(이슈 UPDATE 게이트 우선) 정합 검증
import { server } from '@/test/server'
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { worklogHandlers, resetWorklogStore } from './worklog-handlers'
import { mockAccessToken } from './auth-fixtures'

beforeEach(() => {
  server.use(...worklogHandlers)
})
beforeEach(() => {
  resetWorklogStore()
})
afterEach(() => {
  resetWorklogStore()
})

/** 지정 사용자로 요청한다 — 핸들러는 Bearer 토큰에서 username 을 해석한다. */
function as(username: string): HeadersInit {
  return { Authorization: `Bearer ${mockAccessToken(username)}` }
}

const ISSUE = 'ATLAS-1'
const MISSING_WORKLOG = '99999999-9999-4999-8999-999999999999'

/**
 * **판정 순서 정합 (TODOS §worklog-handlers 의 이슈 UPDATE 게이트 부재).**
 *
 * 백엔드 `WorklogService` 는 이슈 `UPDATE` 게이트를 **리소스 조회보다 먼저** 통과시킨다
 * (`create:111` · `createImported:183` · `update:241` · `delete:315` — 모두 첫 줄이 `checkPermission`).
 * 그래서 권한 없는 사용자는 **워크로그 존재 여부와 무관하게 403** 을 받는다.
 *
 * 모크에는 그 게이트가 없어 워크로그 조회 404 를 먼저 냈다 — 「권한이 없다」와 「그런 워크로그가
 * 없다」가 뒤바뀐다. 댓글 모크는 2026-07-27 에 같은 갭을 봉합했고(`comment-handlers.test.ts`),
 * 이 파일은 형제 핸들러에 같은 판별식을 적용한다.
 *
 * ★판별자는 `carol`(VIEWER, `UPDATE=false`) 뿐이다. alice(ADMIN)·bob(MEMBER) 둘 다 `UPDATE=true` 라
 * 그들만으로는 게이트를 지워도 전량 초록이다.
 *
 * 판별식 — **"백엔드가 리소스를 resolve 하기 전에 통과시키는 게이트를, 모크도 resolve 전에
 * 통과시키는가."** 존재하지 않는 워크로그 id 로 요청했을 때의 상태코드가 그것을 드러낸다.
 */
describe('worklogHandlers — 이슈 UPDATE 게이트가 워크로그 조회보다 먼저다', () => {
  it('PATCH — UPDATE 없는 사용자는 없는 워크로그에도 403 (404 아님)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs/${MISSING_WORKLOG}`, {
      method: 'PATCH',
      headers: { ...as('carol'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ timeSpentSeconds: 3600 }),
    })

    expect(res.status).toBe(403)
  })

  it('DELETE — UPDATE 없는 사용자는 없는 워크로그에도 403 (404 아님)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs/${MISSING_WORKLOG}`, {
      method: 'DELETE',
      headers: as('carol'),
    })

    expect(res.status).toBe(403)
  })

  it('POST — UPDATE 없는 사용자는 추가도 403', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs`, {
      method: 'POST',
      headers: { ...as('carol'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ timeSpentSeconds: 3600, startedAt: '2026-07-27T09:00:00Z' }),
    })

    expect(res.status).toBe(403)
  })

  /**
   * 대조군 — 게이트가 "전부 403" 으로 무너지지 않았음을 확인한다.
   * 이 단언이 없으면 게이트를 과하게 걸어도 위 3건이 통과해 초록으로 보인다.
   */
  it('대조군 — UPDATE 보유자는 없는 워크로그에 404 (게이트 통과 후 조회 실패)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs/${MISSING_WORKLOG}`, {
      method: 'PATCH',
      headers: { ...as('bob'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ timeSpentSeconds: 3600 }),
    })

    expect(res.status).toBe(404)
  })

  it('대조군 — UPDATE 보유자는 없는 워크로그 삭제에 404', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs/${MISSING_WORKLOG}`, {
      method: 'DELETE',
      headers: as('bob'),
    })

    expect(res.status).toBe(404)
  })

  it('대조군 — UPDATE 보유자는 워크로그 추가에 성공한다', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs`, {
      method: 'POST',
      headers: { ...as('bob'), 'Content-Type': 'application/json' },
      body: JSON.stringify({ timeSpentSeconds: 3600, startedAt: '2026-07-27T09:00:00Z' }),
    })

    expect(res.status).toBe(201)
  })

  /**
   * 게이트는 목록 조회를 막지 않는다 — 백엔드 `listForIssue:369` 는 `VIEW` 를 요구할 뿐이다.
   * 게이트를 GET 에까지 붙이면 읽기 전용 참여자가 워크로그를 아예 못 보게 되어 계약이 어긋난다.
   */
  it('GET 목록은 UPDATE 없는 사용자에게도 열려 있다 (VIEW 권한 계약)', async () => {
    const res = await fetch(`/api/v1/issues/${ISSUE}/worklogs`, { headers: as('carol') })

    expect(res.status).toBe(200)
  })
})
