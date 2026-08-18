// 백로그·스프린트 MSW 핸들러 stateful 동작 검증 (FR-BL-01/02 D6/D7)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: mutation 후 GET 재조회 시 변경이 반영되는지 검증
//   - msw-derived-behavior-shared-store-e2e: 정적 픽스처 반환은 가짜그린 — store에서 읽어야 함
//
import { server } from '@/test/server'
import {
  backlogHandlers,
  LS_KEY_BACKLOG_TRUNCATED,
  LS_KEY_SPRINT_START_FAIL,
  LS_KEY_SPRINT_UNASSIGN_FAIL,
} from './backlog-handlers'
import {
  resetBacklogStore,
  seedBacklog,
  DEFAULT_BACKLOG,
} from './backlog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트용 MSW 서버 — backlogHandlers만 등록
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...backlogHandlers)
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

    // GET 재조회 — store에서 읽어야 상태 전환이 반영됨
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

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 — 픽스처 보강 (FR-18)
//
// ★왜 여기서 픽스처를 단언하나.
// 완료 다이얼로그의 「COMPLETED 스프린트가 이관 대상에 없다」는 픽스처에 COMPLETED 가
// 0개면 **자동으로 참**이 된다. 부정 단언만 두면 아무것도 재지 않는 가짜 그린이고,
// PR #342 에서 같은 양식이 2회 적발됐다. 그래서 「없다」의 짝인 「있다」를 목에서 먼저 고정한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('DEFAULT_BACKLOG 픽스처 — 스프린트 상태 3종 (FR-18)', () => {
  it('ACTIVE·PLANNED·COMPLETED 가 각각 실재한다 (짝 단언의 전제)', async () => {
    const { data } = await getBacklog('ATLAS')
    const statuses = data.sprints.map((s) => s.sprint.status)

    expect(statuses).toContain('ACTIVE')
    expect(statuses).toContain('PLANNED')
    expect(statuses).toContain('COMPLETED')
  })

  it('백엔드 sprintComparator 와 같은 순서로 내려온다 (ACTIVE → PLANNED → COMPLETED)', async () => {
    // 클라이언트는 스프린트를 정렬하지 않는다(스펙 FR-1). 목이 백엔드와 다른 순서를 주면
    // 「응답 순서를 그대로 그린다」가 화면에서 검증되지 않는다.
    const { data } = await getBacklog('ATLAS')
    const rank: Record<string, number> = { ACTIVE: 0, PLANNED: 1, COMPLETED: 2 }
    const ranks = data.sprints.map((s) => rank[s.sprint.status] ?? 99)

    expect(ranks).toEqual([...ranks].sort((a, b) => a - b))
  })

  it('첫 스프린트에 이슈가 있다 (드래그·해제 시나리오가 성립하려면 필요)', async () => {
    const { data } = await getBacklog('ATLAS')
    expect(data.sprints[0]?.issues.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/sprints/:id (FR-14)
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/sprints/:id (stateful · 3-state partial)', () => {
  /** PLANNED 스프린트의 메타를 가져온다 */
  async function plannedSprint(): Promise<{
    sprintId: string
    name: string
    status: string
    version: number
  }> {
    const { data } = await getBacklog('ATLAS')
    const planned = data.sprints.find((s) => s.sprint.status === 'PLANNED')
    expect(planned).toBeDefined()
    return planned!.sprint
  }

  async function patchSprint(sprintId: string, body: unknown): Promise<Response> {
    return fetch(`/api/v1/sprints/${sprintId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  }

  it('전송한 필드만 반영하고 version 을 +1 한다', async () => {
    const before = await plannedSprint()

    const res = await patchSprint(before.sprintId, {
      endDate: '2026-07-20',
      version: before.version,
    })
    expect(res.status).toBe(200)

    const body = (await res.json()) as {
      data: { name: string; endDate: string | null; startDate: string | null; version: number }
    }
    expect(body.data.endDate).toBe('2026-07-20')
    expect(body.data.version).toBe(before.version + 1)
    // 미전송 필드는 무변경
    expect(body.data.name).toBe(before.name)

    // GET 재조회 — store 변이가 반영돼야 한다
    const { data: after } = await getBacklog('ATLAS')
    const updated = after.sprints.find((s) => s.sprint.sprintId === before.sprintId)
    expect(updated?.sprint.version).toBe(before.version + 1)
  })

  it('명시 null 은 값을 지운다 (미전송과 다른 뜻)', async () => {
    const before = await plannedSprint()

    const res = await patchSprint(before.sprintId, { goal: null, version: before.version })
    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: { goal: string | null } }
    expect(body.data.goal).toBeNull()
  })

  it('version 이 어긋나면 409 를 반환하고 store 를 바꾸지 않는다', async () => {
    const before = await plannedSprint()

    const res = await patchSprint(before.sprintId, {
      goal: '덮어쓰기 시도',
      version: before.version + 5,
    })
    expect(res.status).toBe(409)

    const { data: after } = await getBacklog('ATLAS')
    const untouched = after.sprints.find((s) => s.sprint.sprintId === before.sprintId)
    expect(untouched?.sprint.version).toBe(before.version)
  })

  it('version 이 없으면 400 을 반환한다 (백엔드 필수 파라미터)', async () => {
    const before = await plannedSprint()
    const res = await patchSprint(before.sprintId, { goal: '목표만' })
    expect(res.status).toBe(400)
  })

  it('존재하지 않는 스프린트는 404 를 반환한다', async () => {
    const res = await patchSprint('00000000-0000-4000-8000-000000000000', { version: 0 })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글 (FR-14 · FR-18)
// ─────────────────────────────────────────────────────────────────────────────

describe('E2E 시나리오 토글', () => {
  afterEach(() => {
    globalThis.localStorage?.clear()
  })

  it('truncated 토글이 켜지면 GET backlog 가 truncated=true 를 반환한다 (E15 완료 차단 재현)', async () => {
    const { data: before } = await getBacklog('ATLAS')
    expect(before.truncated).toBe(false)

    globalThis.localStorage?.setItem(LS_KEY_BACKLOG_TRUNCATED, 'true')

    const { data: after } = await getBacklog('ATLAS')
    expect(after.truncated).toBe(true)
  })

  it('시작 실패 토글 — "true" 는 500, "409" 는 409 를 반환한다 (S5 · E10 재현)', async () => {
    const { data } = await getBacklog('ATLAS')
    const planned = data.sprints.find((s) => s.sprint.status === 'PLANNED')
    const sprintId = planned!.sprint.sprintId

    globalThis.localStorage?.setItem(LS_KEY_SPRINT_START_FAIL, 'true')
    const failed = await fetch(`/api/v1/sprints/${sprintId}/start`, { method: 'POST' })
    expect(failed.status).toBe(500)

    globalThis.localStorage?.setItem(LS_KEY_SPRINT_START_FAIL, '409')
    const conflict = await fetch(`/api/v1/sprints/${sprintId}/start`, { method: 'POST' })
    expect(conflict.status).toBe(409)

    // 토글이 꺼지면 정상 전환 — 실패 토글이 스프린트를 영구히 못 쓰게 만들면 안 된다
    globalThis.localStorage?.removeItem(LS_KEY_SPRINT_START_FAIL)
    const ok = await fetch(`/api/v1/sprints/${sprintId}/start`, { method: 'POST' })
    expect(ok.status).toBe(200)
  })

  it('이관 실패 토글은 지정한 이슈 키의 DELETE 만 500 으로 만든다 (S7 부분 실패 재현)', async () => {
    const { data } = await getBacklog('ATLAS')
    const sprint = data.sprints[0]!
    const [first, second] = sprint.issues
    expect(second).toBeDefined()

    globalThis.localStorage?.setItem(LS_KEY_SPRINT_UNASSIGN_FAIL, second!.key)

    const okRes = await fetch(`/api/v1/sprints/${sprint.sprint.sprintId}/issues/${first!.key}`, {
      method: 'DELETE',
    })
    expect(okRes.status).toBe(204)

    const failRes = await fetch(`/api/v1/sprints/${sprint.sprint.sprintId}/issues/${second!.key}`, {
      method: 'DELETE',
    })
    expect(failRes.status).toBe(500)
  })
})
