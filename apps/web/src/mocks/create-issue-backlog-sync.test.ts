// 이슈 생성 목과 백로그 목의 상태 동기화 계약 검증 (FR-UX-09 F3 FR-13)
//
// ★왜 이 파일이 따로 있나.
// `POST /api/v1/issues`(issue-handlers)와 `GET /projects/{key}/backlog`(backlog-handlers)는
// **서로 다른 저장소**를 쓴다. 그 사실이 F3 의 판정을 통째로 무의미하게 만든다.
//
//   - 가짜 그린 — 「`POST /sprints/{id}/issues` 가 1회 호출됐다」만 보는 테스트는 **항상 통과**한다.
//     `backlog-handlers.ts:230` 이 **모르는 키를 201 로 멱등 처리**하기 때문이다.
//   - 거짓 실패 — 「스프린트 칸에 나타난다」를 보는 테스트는 **구현이 옳아도 실패**한다.
//     만든 이슈가 애초에 `backlogStore` 에 없기 때문이다.
//     그 실패를 만난 사람이 단언을 「호출됐다」로 약화시키면 위의 가짜 그린에 착지한다.
//
// 그래서 상위 UI 테스트를 쓰기 **전에** 목의 계약을 여기서 먼저 고정한다.
// (교훈 동형 — FR-UX-09 F2 의 「MSW 가 신규 필드를 전부 무시해 가짜 그린」)

import { server } from '@/test/server'
import { issueHandlers } from './issue-handlers'
import { backlogHandlers } from './backlog-handlers'
import { resetBacklogStore, seedBacklog, DEFAULT_BACKLOG } from './backlog-fixtures'

beforeEach(() => {
  server.use(...issueHandlers, ...backlogHandlers)
})

beforeEach(() => {
  resetBacklogStore()
  seedBacklog(DEFAULT_BACKLOG)
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface BacklogResponse {
  data: {
    backlog: Array<{ key: string }>
    sprints: Array<{ sprint: { sprintId: string }; issues: Array<{ key: string }> }>
  }
}

/** 이슈를 만들고 생성된 키를 돌려준다. */
async function createIssue(summary: string, projectKey = 'ATLAS'): Promise<string> {
  const res = await fetch('/api/v1/issues', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectKey, summary }),
  })
  expect(res.status).toBe(201)
  const body = (await res.json()) as { data: { key: string } }
  return body.data.key
}

/** 백로그 뷰를 조회한다. */
async function fetchBacklogView(projectKey = 'ATLAS'): Promise<BacklogResponse['data']> {
  const res = await fetch(`/api/v1/projects/${projectKey}/backlog`)
  expect(res.status).toBe(200)
  const body = (await res.json()) as BacklogResponse
  return body.data
}

// ─────────────────────────────────────────────────────────────────────────────
// FR-13 — 생성 결과가 백로그 조회에 반영된다
// ─────────────────────────────────────────────────────────────────────────────

describe('FR-13 — POST /issues 결과가 백로그 조회에 반영된다', () => {
  it('만든 이슈가 직후 백로그 칸에 나타난다', async () => {
    const before = await fetchBacklogView()
    const key = await createIssue('진입점으로 만든 이슈')
    const after = await fetchBacklogView()

    expect(after.backlog.map((i) => i.key)).toContain(key)
    expect(after.backlog).toHaveLength(before.backlog.length + 1)
  })

  it('만든 이슈는 백로그 맨 끝에 붙는다 (rank 순서 관례)', async () => {
    const key = await createIssue('맨 끝에 붙는지')
    const after = await fetchBacklogView()

    expect(after.backlog.at(-1)?.key).toBe(key)
  })

  it('백로그 저장소에 없는 프로젝트로 만들면 조용히 건너뛴다 (기존 테스트 무영향)', async () => {
    // 백로그를 안 쓰는 화면의 기존 테스트가 이 확장 때문에 깨지면 안 된다.
    await expect(createIssue('다른 프로젝트', 'OTHER')).resolves.toBeTruthy()
    const atlas = await fetchBacklogView()
    expect(atlas.backlog.every((i) => i.key !== 'OTHER-1')).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-4 — 만든 이슈를 스프린트에 배정하면 실제로 옮겨진다
// ─────────────────────────────────────────────────────────────────────────────

describe('FR-4 — 만든 직후 스프린트 배정이 실제로 반영된다', () => {
  it('배정 후 그 스프린트 칸에 있고 백로그 칸에는 없다', async () => {
    const key = await createIssue('스프린트로 갈 이슈')
    const view = await fetchBacklogView()
    const sprintId = view.sprints[0]?.sprint.sprintId
    expect(sprintId).toBeTruthy()

    const assignRes = await fetch(`/api/v1/sprints/${sprintId}/issues`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueKey: key }),
    })
    expect(assignRes.status).toBe(201)

    const after = await fetchBacklogView()
    expect(after.sprints[0]?.issues.map((i) => i.key)).toContain(key)
    expect(after.backlog.map((i) => i.key)).not.toContain(key)
  })

  it('★201 만 보는 단언은 가짜 그린이다 — 모르는 키도 201 을 돌려준다', async () => {
    // 이 테스트는 처방이 아니라 **경고**다. 목이 모르는 키를 멱등 처리하므로
    // 상위 테스트가 상태 변화가 아니라 응답 코드만 보면 언제나 통과한다.
    const view = await fetchBacklogView()
    const sprintId = view.sprints[0]?.sprint.sprintId

    const res = await fetch(`/api/v1/sprints/${sprintId}/issues`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueKey: 'NOPE-999' }),
    })

    expect(res.status).toBe(201)
    const after = await fetchBacklogView()
    expect(after.sprints[0]?.issues.map((i) => i.key)).not.toContain('NOPE-999')
  })
})
