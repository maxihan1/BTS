// 백로그·스프린트 MSW 핸들러 stateful 동작 검증 (FR-BL-01/02 D6/D7)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: mutation 후 GET 재조회 시 변경이 반영되는지 검증
//   - msw-derived-behavior-shared-store-e2e: 정적 픽스처 반환은 가짜그린 — store에서 읽어야 함
//
import { setupServer } from 'msw/node'
import { backlogHandlers } from './backlog-handlers'
import {
  resetBacklogStore,
  seedBacklog,
  DEFAULT_BACKLOG,
} from './backlog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트용 MSW 서버 — backlogHandlers만 등록
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...backlogHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

beforeEach(() => {
  resetBacklogStore()
  seedBacklog(DEFAULT_BACKLOG)
})

afterEach(() => {
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface BacklogResponse {
  data: {
    backlog: Array<{ key: string; rank: string | null }>
    sprints: Array<{
      sprint: { sprintId: string; name: string; status: string; version: number }
      issues: Array<{ key: string; rank: string | null }>
    }>
    truncated: boolean
  }
}

async function getBacklog(projectKey: string): Promise<BacklogResponse> {
  const res = await fetch(`/api/v1/projects/${projectKey}/backlog`)
  return res.json() as Promise<BacklogResponse>
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/backlog
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/backlog', () => {
  it('기본 시드 데이터를 반환한다', async () => {
    const { data } = await getBacklog('ATLAS')

    expect(data.backlog.length).toBeGreaterThan(0)
    expect(data.sprints.length).toBeGreaterThan(0)
    expect(data.truncated).toBe(false)
  })

  it('존재하지 않는 프로젝트는 빈 backlog와 빈 sprints를 반환한다', async () => {
    const { data } = await getBacklog('NO_SUCH')

    expect(data.backlog).toEqual([])
    expect(data.sprints).toEqual([])
    expect(data.truncated).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/issues/:key/rank
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key/rank (stateful)', () => {
  it('rank 변경 후 GET backlog 재조회 시 새 rank가 반영된다', async () => {
    const issueKey = 'ATLAS-1'

    const patchRes = await fetch(`/api/v1/issues/${issueKey}/rank`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ previousIssueKey: 'ATLAS-2' }),
    })
    expect(patchRes.status).toBe(200)

    const patchData = (await patchRes.json()) as {
      data: { key: string; rank: string | null; version: number }
    }
    expect(patchData.data.key).toBe(issueKey)
    expect(patchData.data.version).toBe(1) // version +1

    // GET 재조회 — store에서 읽으므로 새 rank가 반영됨
    const { data } = await getBacklog('ATLAS')
    const updated = [
      ...data.backlog,
      ...data.sprints.flatMap((s) => s.issues),
    ].find((i) => i.key === issueKey)

    expect(updated).toBeDefined()
    // rank는 null이 아닌 문자열이어야 함 (중간 rank 계산)
    expect(typeof updated?.rank).toBe('string')
  })

  it('존재하지 않는 이슈 rank 변경 시 404를 반환한다', async () => {
    const res = await fetch('/api/v1/issues/NO-SUCH/rank', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nextIssueKey: 'ATLAS-1' }),
    })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints/:id/issues (이슈 → 스프린트 할당)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/sprints/:id/issues (stateful)', () => {
  it('백로그 이슈를 스프린트로 할당한 뒤 GET 재조회 시 스프린트로 이동한다', async () => {
    // ATLAS-1은 초기 백로그에 있음
    const { data: before } = await getBacklog('ATLAS')
    const backlogBefore = before.backlog.map((i) => i.key)
    expect(backlogBefore).toContain('ATLAS-1')

    const sprint = before.sprints[0]
    expect(sprint).toBeDefined()
    const sprintId = sprint!.sprint.sprintId

    const res = await fetch(`/api/v1/sprints/${sprintId}/issues`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueKey: 'ATLAS-1' }),
    })
    expect(res.status).toBe(201)

    // GET 재조회 — stateful store에서 읽어야 이동이 반영됨
    const { data: after } = await getBacklog('ATLAS')
    const backlogAfter = after.backlog.map((i) => i.key)
    const sprintIssuesAfter = after.sprints
      .find((s) => s.sprint.sprintId === sprintId)
      ?.issues.map((i) => i.key) ?? []

    expect(backlogAfter).not.toContain('ATLAS-1')
    expect(sprintIssuesAfter).toContain('ATLAS-1')
  })

  it('존재하지 않는 스프린트에 할당 시 404를 반환한다', async () => {
    const res = await fetch(
      '/api/v1/sprints/00000000-0000-4000-8000-000000000000/issues',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ issueKey: 'ATLAS-1' }),
      },
    )
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/sprints/:id/issues/:issueKey (스프린트 → 백로그)
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/sprints/:id/issues/:issueKey (stateful)', () => {
  it('스프린트 이슈를 해제한 뒤 GET 재조회 시 백로그로 복귀한다', async () => {
    const { data: before } = await getBacklog('ATLAS')
    const sprint = before.sprints[0]
    expect(sprint).toBeDefined()

    const sprintId = sprint!.sprint.sprintId
    const sprintIssuesBefore = sprint!.issues.map((i) => i.key)
    // DEFAULT_BACKLOG에서 스프린트에 이슈가 있어야 함
    expect(sprintIssuesBefore.length).toBeGreaterThan(0)

    const targetKey = sprintIssuesBefore[0]!

    const res = await fetch(`/api/v1/sprints/${sprintId}/issues/${targetKey}`, {
      method: 'DELETE',
    })
    expect(res.status).toBe(204)

    // GET 재조회 — store에서 읽어야 해제가 반영됨
    const { data: after } = await getBacklog('ATLAS')
    const backlogAfter = after.backlog.map((i) => i.key)
    const sprintIssuesAfter = after.sprints
      .find((s) => s.sprint.sprintId === sprintId)
      ?.issues.map((i) => i.key) ?? []

    expect(backlogAfter).toContain(targetKey)
    expect(sprintIssuesAfter).not.toContain(targetKey)
  })

  it('존재하지 않는 스프린트에서 해제 시 404를 반환한다', async () => {
    const res = await fetch(
      '/api/v1/sprints/00000000-0000-4000-8000-000000000000/issues/ATLAS-1',
      { method: 'DELETE' },
    )
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints (스프린트 생성)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/sprints (stateful)', () => {
  it('새 스프린트를 생성한 뒤 GET backlog 재조회 시 sprints 목록에 등장한다', async () => {
    const { data: before } = await getBacklog('ATLAS')
    const countBefore = before.sprints.length

    const res = await fetch('/api/v1/sprints', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectKey: 'ATLAS', name: '새 스프린트' }),
    })
    expect(res.status).toBe(201)

    const created = (await res.json()) as {
      data: { sprintId: string; name: string; status: string; version: number }
    }
    expect(created.data.name).toBe('새 스프린트')
    expect(created.data.status).toBe('PLANNED')

    // GET 재조회 — stateful store에서 읽어야 새 스프린트가 등장함
    const { data: after } = await getBacklog('ATLAS')
    expect(after.sprints.length).toBe(countBefore + 1)

    const found = after.sprints.find((s) => s.sprint.sprintId === created.data.sprintId)
    expect(found).toBeDefined()
    expect(found?.sprint.name).toBe('새 스프린트')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints/:id/start
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/sprints/:id/start (stateful)', () => {
  it('PLANNED 스프린트를 시작하면 GET 재조회 시 status가 ACTIVE로 변경된다', async () => {
    const { data: before } = await getBacklog('ATLAS')
    const planned = before.sprints.find((s) => s.sprint.status === 'PLANNED')
    expect(planned).toBeDefined()

    const sprintId = planned!.sprint.sprintId

    const res = await fetch(`/api/v1/sprints/${sprintId}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    })
    expect(res.status).toBe(200)

    const result = (await res.json()) as { data: { status: string } }
    expect(result.data.status).toBe('ACTIVE')

    // GET 재조회 — store에서 읽어야 상태 전이가 반영됨
    const { data: after } = await getBacklog('ATLAS')
    const updated = after.sprints.find((s) => s.sprint.sprintId === sprintId)
    expect(updated?.sprint.status).toBe('ACTIVE')
  })

  it('존재하지 않는 스프린트 시작 시 404를 반환한다', async () => {
    const res = await fetch(
      '/api/v1/sprints/00000000-0000-4000-8000-000000000000/start',
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
    )
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints/:id/complete
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/sprints/:id/complete (stateful)', () => {
  it('ACTIVE 스프린트를 완료하면 GET 재조회 시 status가 COMPLETED로 변경된다', async () => {
    // ACTIVE 스프린트가 있어야 하므로 먼저 start 호출
    const { data: before } = await getBacklog('ATLAS')
    const planned = before.sprints.find((s) => s.sprint.status === 'PLANNED')
    expect(planned).toBeDefined()

    const sprintId = planned!.sprint.sprintId

    await fetch(`/api/v1/sprints/${sprintId}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })

    const res = await fetch(`/api/v1/sprints/${sprintId}/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    })
    expect(res.status).toBe(200)

    const result = (await res.json()) as { data: { status: string } }
    expect(result.data.status).toBe('COMPLETED')

    // GET 재조회
    const { data: after } = await getBacklog('ATLAS')
    const updated = after.sprints.find((s) => s.sprint.sprintId === sprintId)
    expect(updated?.sprint.status).toBe('COMPLETED')
  })

  it('존재하지 않는 스프린트 완료 시 404를 반환한다', async () => {
    const res = await fetch(
      '/api/v1/sprints/00000000-0000-4000-8000-000000000000/complete',
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
    )
    expect(res.status).toBe(404)
  })
})
