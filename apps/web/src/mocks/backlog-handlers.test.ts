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
  generateUUID,
  DEFAULT_BACKLOG,
} from './backlog-fixtures'
// FR-BD-04 PR ③ — 백로그 보드 스코프. 「이 프로젝트의 보드」의 출처는 boardStore 다
// (백엔드 `boardRepository.findAllByProjectKey` / `findScrumBoardIdByProject` 대응).
import { resetBoardStore, createBoardInStore } from './board-fixtures'
import type { BacklogIssue, SprintMeta } from '@/api/backlog'

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

/**
 * 백로그 조회 원본 응답. 상태 코드를 재는 단언(E7·E8)이 이 헬퍼를 쓴다.
 *
 * @param projectKey 경로 프로젝트 키
 * @param board `?board=` 로 실을 보드 UUID. 생략하면 파라미터 자체를 붙이지 않는다 —
 *   「미전송」과 「빈 값 전송」은 서버가 다르게 볼 수 있어 구분해야 한다.
 */
async function getBacklogRes(projectKey: string, board?: string): Promise<Response> {
  const query = board === undefined ? '' : `?board=${encodeURIComponent(board)}`
  return fetch(`/api/v1/projects/${projectKey}/backlog${query}`)
}

async function getBacklog(projectKey: string, board?: string): Promise<BacklogResponse> {
  const res = await getBacklogRes(projectKey, board)
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
// GET /api/v1/projects/:projectKey/backlog?board= — 보드 스코프 (FR-BD-04 · PR ③)
//
// ★왜 이 describe 가 필요한가.
// 이 핸들러는 지금까지 `searchParams` 를 **한 번도 읽지 않았다**(`request` 조차 안 받았다).
// 그래서 `?board=` 를 안 보내도, 남의 보드 UUID 를 보내도 응답이 같았다 — 화면이 보드 축을
// 배선해도 목이 아무것도 가르지 않아 전부 초록이 된다(`?from=` 사고 2026-06-25 와 같은 자리).
// 「명시 스코프」와 「미전송 폴백」을 함께 단언해야 서로의 가짜 그린을 막는다. 한쪽만 재면
// 「무엇을 보내든 전부 반환」이 두 단언을 동시에 만족시킨다.
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/backlog?board= (보드 스코프)', () => {
  const SCOPE_PROJECT = 'SCOPE'
  const OTHER_PROJECT = 'OTHERPRJ'

  let kanbanBoardId = ''
  let scrumBoardA = ''
  let scrumBoardB = ''
  let otherProjectBoardId = ''

  /** 보드 스코프 시나리오 전용 백로그 이슈 — 필드는 BacklogIssue 계약 그대로다 */
  function scopeIssue(key: string, rank: string): BacklogIssue {
    return {
      key,
      summary: `보드 스코프 테스트 ${key}`,
      currentStateKey: 'open',
      assigneeId: null,
      priority: 1,
      rank,
      version: 0,
      epicKey: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    }
  }

  /** 보드 스코프 시나리오 전용 스프린트 메타 — 응답 DTO 에는 boardId 가 없다(store 전용 축) */
  function scopeSprint(name: string): SprintMeta {
    return {
      sprintId: generateUUID(),
      boardId: '10000000-0000-4000-8000-000000000001',
      name,
      goal: null,
      status: 'PLANNED',
      startDate: null,
      endDate: null,
      version: 0,
    }
  }

  beforeEach(() => {
    resetBoardStore()
    // 생성 순서 = 백엔드 created_at ASC. 칸반을 **먼저** 만들어 「첫 보드」와 「첫 스크럼 보드」를
    // 갈라 둔다 — 폴백이 그냥 첫 보드를 집는 오구현은 여기서 걸린다.
    kanbanBoardId = createBoardInStore(SCOPE_PROJECT, '칸반 보드', 'KANBAN').created.boardId
    scrumBoardA = createBoardInStore(SCOPE_PROJECT, '스크럼 보드 A', 'SCRUM').created.boardId
    scrumBoardB = createBoardInStore(SCOPE_PROJECT, '스크럼 보드 B', 'SCRUM').created.boardId
    otherProjectBoardId = createBoardInStore(OTHER_PROJECT, '남의 보드', 'SCRUM').created.boardId

    seedBacklog({
      projectKey: SCOPE_PROJECT,
      backlog: [scopeIssue('SCOPE-1', '0|a00000:')],
      sprints: [
        {
          sprint: scopeSprint('A 스프린트'),
          boardId: scrumBoardA,
          issues: [scopeIssue('SCOPE-2', '0|b00000:')],
        },
        {
          sprint: scopeSprint('B 스프린트'),
          boardId: scrumBoardB,
          issues: [scopeIssue('SCOPE-3', '0|c00000:')],
        },
      ],
      truncated: false,
    })
  })

  afterEach(() => {
    resetBoardStore()
  })

  it('?board=A 는 A 보드의 스프린트만 돌려준다', async () => {
    const { data } = await getBacklog(SCOPE_PROJECT, scrumBoardA)
    expect(data.sprints.map((s) => s.sprint.name)).toEqual(['A 스프린트'])
  })

  it('?board=B 는 B 보드의 스프린트만 돌려준다 (첫 보드 하드코딩 차단)', async () => {
    const { data } = await getBacklog(SCOPE_PROJECT, scrumBoardB)
    expect(data.sprints.map((s) => s.sprint.name)).toEqual(['B 스프린트'])
  })

  it('E12 — 다른 보드 스프린트의 이슈는 백로그 칸에 남는다 (증발 금지)', async () => {
    const { data } = await getBacklog(SCOPE_PROJECT, scrumBoardA)
    // 백엔드 BacklogApplicationService 의 차집합이 「이 보드의 스프린트에 없는 이슈」다(J20).
    // 프로젝트 전체 스프린트를 빼면 SCOPE-3 이 어느 칸에도 없어 화면에서 사라진다.
    expect(data.backlog.map((i) => i.key)).toEqual(['SCOPE-1', 'SCOPE-3'])
  })

  it('?board= 미전송이면 기본 보드(첫 스크럼 보드)로 폴백한다 (E4·X5)', async () => {
    const { data } = await getBacklog(SCOPE_PROJECT)
    // 칸반 보드가 먼저 만들어졌지만 기본 보드는 **첫 스크럼 보드**다
    // (백엔드 `findScrumBoardIdByProject` — created_at ASC LIMIT 1).
    expect(data.sprints.map((s) => s.sprint.name)).toEqual(['A 스프린트'])
  })

  it('칸반 보드로 스코프하면 스프린트가 없고 모든 이슈가 백로그 칸에 온다', async () => {
    const { data } = await getBacklog(SCOPE_PROJECT, kanbanBoardId)
    expect(data.sprints).toEqual([])
    expect(data.backlog.map((i) => i.key)).toEqual(['SCOPE-1', 'SCOPE-2', 'SCOPE-3'])
  })

  it('E7 — 존재하지 않는 보드 UUID 는 404 다 (기본 보드로 조용히 폴백하지 않는다)', async () => {
    const res = await getBacklogRes(SCOPE_PROJECT, '00000000-0000-4000-8000-0000000000ff')
    expect(res.status).toBe(404)
  })

  it('E8 — 다른 프로젝트의 보드 UUID 도 404 다 (존재 probe 차단)', async () => {
    const res = await getBacklogRes(SCOPE_PROJECT, otherProjectBoardId)
    expect(res.status).toBe(404)
  })

  it('보드가 하나도 없는 프로젝트는 보드 축 없이 전량을 돌려준다 (기존 시드 회귀 방지)', async () => {
    // ATLAS 는 boardStore 에 보드가 없다 — 백엔드도 이때 스코프를 null 로 두고 전량을 준다.
    // 여기서 빈 목록으로 못박으면 DEFAULT_BACKLOG 를 쓰는 백로그 E2E 26개가 전멸한다.
    const { data } = await getBacklog('ATLAS')
    expect(data.sprints).toHaveLength(3)
  })

  it('POST /api/v1/sprints 의 boardId 는 그 보드로 스코프했을 때만 보인다 (E10)', async () => {
    const res = await fetch('/api/v1/sprints', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectKey: SCOPE_PROJECT, name: 'B 보드 새 스프린트', boardId: scrumBoardB }),
    })
    expect(res.status).toBe(201)

    const scopedB = await getBacklog(SCOPE_PROJECT, scrumBoardB)
    expect(scopedB.data.sprints.map((s) => s.sprint.name)).toContain('B 보드 새 스프린트')

    // 두 번째 스크럼 보드에 만든 스프린트가 첫 보드에 붙으면(현행 폴백) 이 단언이 깨진다.
    const scopedA = await getBacklog(SCOPE_PROJECT, scrumBoardA)
    expect(scopedA.data.sprints.map((s) => s.sprint.name)).not.toContain('B 보드 새 스프린트')
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
