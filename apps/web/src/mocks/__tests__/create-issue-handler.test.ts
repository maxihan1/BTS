// 이슈 생성 MSW 핸들러 단위 테스트 — FR-UX-09 F2 신규 5필드 반영 + assigneeId 3-state
//
// 이 파일이 존재하는 이유. 핸들러가 요청 본문의 신규 5필드를 읽지 않고 fixture 를 그대로
// 돌려주면, "5필드가 반영됐다" 를 단언하는 상위 테스트가 **프론트가 아무것도 안 보내도 통과**한다
// (learnings 2026-06-25 `<input type="date">` MSW lexical 비교 가짜그린과 동형).
// 여기서 핸들러 자체를 계약으로 고정한다.
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { issueHandlers, resetIssueState, MOCK_ASSIGNEE_NOT_FOUND } from '../issue-handlers'
import { resetProjectLeadStore, seedProjectLead } from '../project-lead-handlers'

const server = setupServer(...issueHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  resetIssueState()
  resetProjectLeadStore()
  localStorage.clear()
})
afterAll(() => server.close())

const CREATE_URL = '/api/v1/issues'
const LEAD_USER_ID = '99999999-9999-4999-8999-999999999999'
const PICKED_USER_ID = '33333333-3333-4333-8333-333333333333'
/**
 * componentStore 에 **시드되지 않은** 컴포넌트 id.
 * 핸들러의 default-assignee resolve 는 `componentIds.length > 0` 일 때만 돌고,
 * 컴포넌트 리드를 못 찾으면 프로젝트 리드로 폴백한다. 그 폴백 경로를 타기 위한 값이다.
 */
const UNSEEDED_COMPONENT_ID = '11111111-1111-4111-8111-111111111111'

/** POST /api/v1/issues 를 호출하고 응답 본문의 data 를 돌려준다. */
async function postIssue(body: Record<string, unknown>): Promise<{
  status: number
  data: Record<string, unknown>
}> {
  const res = await fetch(CREATE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const parsed = await res.json() as { data?: Record<string, unknown> } & Record<string, unknown>
  return { status: res.status, data: parsed.data ?? parsed }
}

// ─────────────────────────────────────────────────────────────────────────────
// 신규 4필드 — 요청 값이 응답에 반영되는가 (fixture 고정값이 아니라)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/issues — FR-UX-09 F2 신규 필드 반영', () => {
  it('typeId 를 보내면 응답 typeId 가 그 값이다', async () => {
    const { data } = await postIssue({ projectKey: 'ATLAS', summary: '유형 지정', typeId: 1 })
    expect(data['typeId']).toBe(1)
  })

  it('description 을 보내면 응답 description 이 그 값이다', async () => {
    const { data } = await postIssue({ projectKey: 'ATLAS', summary: '본문 지정', description: '본문입니다' })
    expect(data['description']).toBe('본문입니다')
  })

  it('priority 를 보내면 응답 priority 가 그 값이다', async () => {
    const { data } = await postIssue({ projectKey: 'ATLAS', summary: '우선순위 지정', priority: 1 })
    expect(data['priority']).toBe(1)
  })

  it('labels 를 보내면 응답 labels 가 그 값이다', async () => {
    const { data } = await postIssue({
      projectKey: 'ATLAS',
      summary: '라벨 지정',
      labels: ['backend', 'urgent'],
    })
    expect(data['labels']).toEqual(['backend', 'urgent'])
  })

  it('미전달 필드는 서버 기본값을 따른다 — priority 3 · labels 빈 배열', async () => {
    const { data } = await postIssue({ projectKey: 'ATLAS', summary: '기본값 이슈' })
    expect(data['priority']).toBe(3)
    expect(data['labels']).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// assigneeId 3-state — 백엔드 JsonNullable 계약 (ADR 2026-07-31 D-2)
//
// 프로젝트 리드를 시드해 「자동 배정이 돌면 LEAD_USER_ID 가 나온다」는 대비 축을 만든다.
// 이 대비가 없으면 「명시 null 이 자동 배정을 껐다」를 증명할 수 없다.
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/issues — assigneeId 3-state', () => {
  it('키 부재 — 자동 배정이 돌아 프로젝트 리드가 담당자가 된다', async () => {
    seedProjectLead('ATLAS', LEAD_USER_ID)

    const { data } = await postIssue({
      projectKey: 'ATLAS',
      summary: '자동 배정 이슈',
      componentIds: [UNSEEDED_COMPONENT_ID],
    })

    expect(data['assigneeId']).toBe(LEAD_USER_ID)
  })

  it('명시 null — 자동 배정이 비활성되고 미할당으로 확정된다', async () => {
    // 같은 시드인데도 null 이 나와야 「자동 배정을 껐다」가 증명된다.
    seedProjectLead('ATLAS', LEAD_USER_ID)

    const { data } = await postIssue({
      projectKey: 'ATLAS',
      summary: '미할당 확정 이슈',
      componentIds: [UNSEEDED_COMPONENT_ID],
      assigneeId: null,
    })

    expect(data['assigneeId']).toBeNull()
  })

  it('값 지정 — 자동 배정을 무시하고 지정한 사용자가 담당자가 된다', async () => {
    seedProjectLead('ATLAS', LEAD_USER_ID)

    const { data } = await postIssue({
      projectKey: 'ATLAS',
      summary: '담당자 지정 이슈',
      componentIds: [UNSEEDED_COMPONENT_ID],
      assigneeId: PICKED_USER_ID,
    })

    expect(data['assigneeId']).toBe(PICKED_USER_ID)
  })

  it('미존재 사용자 sentinel — 422 ASSIGNEE_NOT_FOUND 를 돌려준다', async () => {
    const res = await fetch(CREATE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '없는 담당자',
        assigneeId: MOCK_ASSIGNEE_NOT_FOUND,
      }),
    })

    expect(res.status).toBe(422)
    const body = await res.json() as { errorCode?: string }
    expect(body.errorCode).toBe('ASSIGNEE_NOT_FOUND')
  })
})
