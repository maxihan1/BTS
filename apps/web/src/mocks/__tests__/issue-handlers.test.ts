// PATCH /api/v1/issues/:key — typeId 처리 + expectedVersion 필드 정합 + description/priority/labels/environment/impact merge-patch 단위 테스트
// Task 6 (FR-SR-01 D6): GET /api/v1/issues query param 필터링 단위 테스트
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { issueHandlers, resetIssueState } from '../issue-handlers'

beforeEach(() => {
  server.use(...issueHandlers)
})
afterEach(() => {
  resetIssueState()
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function patchIssue(
  key: string,
  body: Record<string, unknown>,
): Promise<Response> {
  return fetch(`/api/v1/issues/${key}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (1) — 이슈 not-found → 404
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — 이슈 not-found', () => {
  it('존재하지 않는 key → 404 반환', async () => {
    const res = await patchIssue('ATLAS-999', { expectedVersion: 0 })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (2) — typeId 검증 실패 → 404
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — typeId 검증', () => {
  it('존재하지 않는 typeId → 404 반환', async () => {
    const res = await patchIssue('ATLAS-1', { typeId: 9999, expectedVersion: 0 })
    expect(res.status).toBe(404)
  })

  it('유효한 typeId → 200 + typeId/typeKey/typeName 갱신', async () => {
    // issue-type-fixtures: id=3 은 task (key: 'task', name: '작업')
    const res = await patchIssue('ATLAS-1', { typeId: 3, expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { typeId: number; typeKey: string; typeName: string } }
    expect(body.data.typeId).toBe(3)
    expect(body.data.typeKey).toBe('task')
    expect(body.data.typeName).toBe('작업')
  })

  it('typeId 미전달 시 기존 타입 유지', async () => {
    // ATLAS-1 초기 typeId=1 (bug)
    const res = await patchIssue('ATLAS-1', { summary: '수정된 요약', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { typeId: number; typeKey: string; typeName: string } }
    expect(body.data.typeId).toBe(1)
    expect(body.data.typeKey).toBe('bug')
    expect(body.data.typeName).toBe('버그')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (3) — version 충돌 → 409
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — version 충돌', () => {
  it('MOCK_CONFLICT_TRIGGER summary → 409 VERSION_CONFLICT', async () => {
    // 기존 E2E-5 회귀 가드 — summary 트리거 방식은 유지
    const { MOCK_CONFLICT_TRIGGER } = await import('../issue-handlers')
    const res = await patchIssue('ATLAS-1', {
      summary: MOCK_CONFLICT_TRIGGER,
      expectedVersion: 0,
    })
    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('VERSION_CONFLICT')
  })

  it('typeId 변경 시 stale expectedVersion → 409 VERSION_CONFLICT (OCC 시맨틱)', async () => {
    // ATLAS-1 현재 version=0, 9999 를 전달해 불일치 유발
    const res = await patchIssue('ATLAS-1', {
      typeId: 3,
      expectedVersion: 9999,
    })
    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('VERSION_CONFLICT')
  })

  it('typeId 변경 시 정확한 expectedVersion → 409 아님(200)', async () => {
    // ATLAS-1 현재 version=0 — 정확히 일치하면 409 가 발생하지 않아야 함
    const res = await patchIssue('ATLAS-1', {
      typeId: 3,
      expectedVersion: 0,
    })
    expect(res.status).toBe(200)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (4) — 성공 케이스 종합
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — 성공', () => {
  it('summary + typeId 동시 수정 → 200 + version+1 + 두 필드 모두 갱신', async () => {
    // issue-type-fixtures: id=4 은 epic (key: 'epic', name: '에픽')
    const res = await patchIssue('ATLAS-2', {
      summary: '수정된 요약',
      typeId: 4,
      expectedVersion: 1,
    })
    expect(res.status).toBe(200)
    const body = await res.json() as {
      data: {
        summary: string
        typeId: number
        typeKey: string
        typeName: string
        version: number
      }
    }
    expect(body.data.summary).toBe('수정된 요약')
    expect(body.data.typeId).toBe(4)
    expect(body.data.typeKey).toBe('epic')
    expect(body.data.typeName).toBe('에픽')
    expect(body.data.version).toBe(2) // ATLAS-2 초기 version=1, +1
  })

  it('expectedVersion 필드로 이슈 조회 가능 — version 필드 하위호환 없음', async () => {
    // updateIssue API 함수가 expectedVersion 을 전송함을 MSW 핸들러가 올바르게 수신하는지 검증
    const res = await patchIssue('ATLAS-3', { expectedVersion: 2 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { version: number } }
    expect(body.data.version).toBe(3) // ATLAS-3 초기 version=2, +1
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 3 — description/priority/labels/environment/impact merge-patch 5필드
// ─────────────────────────────────────────────────────────────────────────────

async function getIssue(key: string): Promise<Response> {
  return fetch(`/api/v1/issues/${key}`)
}

describe('PATCH /api/v1/issues/:key — description merge-patch 3-state', () => {
  it('description 값 전달 → 200 + description 갱신 + stateful 영속(GET 후 동일값)', async () => {
    const res = await patchIssue('ATLAS-1', { description: '## 새 본문\n내용', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const patchBody = await res.json() as { data: { description: string | null } }
    expect(patchBody.data.description).toBe('## 새 본문\n내용')

    // stateful 영속 — 후속 GET 이 갱신값 반환
    const getRes = await getIssue('ATLAS-1')
    expect(getRes.status).toBe(200)
    const getBody = await getRes.json() as { data: { description: string | null; descriptionHtml: string | null } }
    expect(getBody.data.description).toBe('## 새 본문\n내용')
    // 단건 GET 에서 descriptionHtml 이 채워짐
    expect(getBody.data.descriptionHtml).not.toBeNull()
  })

  it('description "" 전달 → DB NULL 클리어(null 반환)', async () => {
    // 먼저 값 설정
    await patchIssue('ATLAS-1', { description: '기존 본문', expectedVersion: 0 })
    // "" 로 클리어
    const res = await patchIssue('ATLAS-1', { description: '', expectedVersion: 1 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { description: string | null } }
    expect(body.data.description).toBeNull()
  })

  it('description 미전달 → 기존값 그대로 유지(무변경)', async () => {
    // description 포함하지 않고 summary 만 수정
    const res = await patchIssue('ATLAS-1', { summary: '요약만 변경', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { description: string | null } }
    // ATLAS-1 초기 description 은 null (기본값)
    expect(body.data.description).toBeNull()
  })
})

describe('PATCH /api/v1/issues/:key — priority 처리', () => {
  it('priority 값 전달 → 200 + priority/priorityName 갱신 + stateful 영속', async () => {
    const res = await patchIssue('ATLAS-1', { priority: 1, expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { priority: number; priorityName: string } }
    expect(body.data.priority).toBe(1)
    expect(body.data.priorityName).toBe('Highest')

    // stateful 영속
    const getRes = await getIssue('ATLAS-1')
    const getBody = await getRes.json() as { data: { priority: number } }
    expect(getBody.data.priority).toBe(1)
  })

  it('priority 미전달 → 기존 priority 유지', async () => {
    const res = await patchIssue('ATLAS-1', { summary: '요약만', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { priority: number } }
    // ATLAS-1 초기 priority=3 (Medium, 기본값)
    expect(body.data.priority).toBe(3)
  })
})

describe('PATCH /api/v1/issues/:key — labels merge-patch 3-state', () => {
  it('labels 값 전달 → 200 + labels 교체 + stateful 영속', async () => {
    const res = await patchIssue('ATLAS-1', { labels: ['frontend', 'urgent'], expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { labels: string[] } }
    expect(body.data.labels).toEqual(['frontend', 'urgent'])

    // stateful 영속
    const getRes = await getIssue('ATLAS-1')
    const getBody = await getRes.json() as { data: { labels: string[] } }
    expect(getBody.data.labels).toEqual(['frontend', 'urgent'])
  })

  it('labels [] 전달 → 전체 제거(빈 배열)', async () => {
    // 먼저 레이블 설정
    await patchIssue('ATLAS-1', { labels: ['label-a'], expectedVersion: 0 })
    // [] 로 제거
    const res = await patchIssue('ATLAS-1', { labels: [], expectedVersion: 1 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { labels: string[] } }
    expect(body.data.labels).toEqual([])
  })

  it('labels 미전달 → 기존값 유지', async () => {
    const res = await patchIssue('ATLAS-1', { summary: '요약만', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { labels: string[] } }
    // ATLAS-1 초기 labels=[] (기본값)
    expect(body.data.labels).toEqual([])
  })
})

describe('PATCH /api/v1/issues/:key — environment merge-patch 3-state', () => {
  it('environment 값 전달 → 200 + environment 갱신 + stateful 영속', async () => {
    const res = await patchIssue('ATLAS-1', { environment: 'macOS 14, Chrome 124', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { environment: string | null } }
    expect(body.data.environment).toBe('macOS 14, Chrome 124')

    const getRes = await getIssue('ATLAS-1')
    const getBody = await getRes.json() as { data: { environment: string | null } }
    expect(getBody.data.environment).toBe('macOS 14, Chrome 124')
  })

  it('environment "" 전달 → null 클리어', async () => {
    await patchIssue('ATLAS-1', { environment: '기존 환경', expectedVersion: 0 })
    const res = await patchIssue('ATLAS-1', { environment: '', expectedVersion: 1 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { environment: string | null } }
    expect(body.data.environment).toBeNull()
  })

  it('environment 미전달 → 기존값 유지', async () => {
    const res = await patchIssue('ATLAS-1', { summary: '요약만', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { environment: string | null } }
    // ATLAS-1 초기 environment=null
    expect(body.data.environment).toBeNull()
  })
})

describe('PATCH /api/v1/issues/:key — impact 처리', () => {
  it('impact 값 전달 → 200 + impact/impactName 갱신 + stateful 영속', async () => {
    const res = await patchIssue('ATLAS-1', { impact: 1, expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { impact: number | null; impactName: string | null } }
    expect(body.data.impact).toBe(1)
    expect(body.data.impactName).toBe('High')

    const getRes = await getIssue('ATLAS-1')
    const getBody = await getRes.json() as { data: { impact: number | null } }
    expect(getBody.data.impact).toBe(1)
  })

  it('impact 미전달 → 기존 impact 유지', async () => {
    const res = await patchIssue('ATLAS-1', { summary: '요약만', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { impact: number | null } }
    // ATLAS-1 초기 impact=null
    expect(body.data.impact).toBeNull()
  })
})

describe('GET /api/v1/issues/:key — descriptionHtml 단건 GET 모킹', () => {
  it('description 있는 이슈 단건 GET → descriptionHtml 이 채워짐(null 아님)', async () => {
    // description 설정 후 GET 으로 descriptionHtml 확인
    await patchIssue('ATLAS-1', { description: '본문 텍스트', expectedVersion: 0 })
    const res = await getIssue('ATLAS-1')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { descriptionHtml: string | null } }
    expect(body.data.descriptionHtml).not.toBeNull()
    // 간단 <p> 래핑 — 내용이 담겨있어야 함
    expect(body.data.descriptionHtml).toContain('본문 텍스트')
  })

  it('description null 인 이슈 단건 GET → descriptionHtml 도 null', async () => {
    // ATLAS-1 초기 description=null
    const res = await getIssue('ATLAS-1')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { descriptionHtml: string | null } }
    expect(body.data.descriptionHtml).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 6 (FR-SR-01 D6) — GET /api/v1/issues query param 필터링 (B2 + B3)
//
// 이슈 4건(ATLAS-1/2/3/5) 분포:
//   status:       open, in_progress, done, in_review (모두 다름)
//   assigneeId:   null, bob-id, alice-id, null (2건 미배정)
//     ★ alice.id = issueAtlas2.id 충돌 회피 → assignee에 별도 UUID 사용
//   labels:       [], ['bug'], ['frontend'], [] (2건 비어있음)
//   componentIds: [], [], [COMP_A_ID], [] (1건만 있음)
//
// 분별 시드가 올바르면 각 필터가 "전체(4)보다 적은" 건수를 반환한다.
// 현재 listIssuesHandler는 query param을 읽지 않으므로 — 이 테스트들은 RED 상태.
// ─────────────────────────────────────────────────────────────────────────────

/** ATLAS 컴포넌트A UUID — issue-fixtures.ts 분별 시드와 동기화 */
const COMP_A_ID = '40000000-0000-4000-8000-000000000001'

/** bob UUID — user-fixtures.ts userBobFixture.id */
const BOB_ID = '00000000-0000-4000-8000-000000000002'

async function listIssues(params: Record<string, string | string[]>): Promise<Response> {
  const sp = new URLSearchParams()
  for (const [key, val] of Object.entries(params)) {
    if (Array.isArray(val)) {
      for (const v of val) sp.append(key, v)
    } else {
      sp.set(key, val)
    }
  }
  return fetch(`/api/v1/issues?${sp.toString()}`)
}

interface IssuePage {
  content: Array<{ key: string; currentStateKey: string; assigneeId: string | null; labels: string[]; componentIds: string[] }>
  totalElements: number
}

async function listIssuesJson(params: Record<string, string | string[]>): Promise<IssuePage> {
  const res = await listIssues(params)
  return res.json() as Promise<IssuePage>
}

describe('GET /api/v1/issues — status 필터링 (Task 6 B2)', () => {
  it('status=open → open 상태 이슈만 반환(전체보다 적음)', async () => {
    const page = await listIssuesJson({ status: 'open' })
    // ATLAS-1만 open — 4건보다 적어야 함 (vacuous 금지)
    expect(page.content.length).toBeGreaterThan(0)
    expect(page.content.length).toBeLessThan(4)
    expect(page.content.every((i) => i.currentStateKey === 'open')).toBe(true)
  })

  it('status=open&status=done → OR 결합 (두 상태 모두 반환, 나머지 제외)', async () => {
    const page = await listIssuesJson({ status: ['open', 'done'] })
    // ATLAS-1(open) + ATLAS-3(done) = 2건 — 4건보다 적음
    expect(page.content.length).toBeGreaterThan(0)
    expect(page.content.length).toBeLessThan(4)
    expect(page.content.every((i) => ['open', 'done'].includes(i.currentStateKey))).toBe(true)
  })

  it('빈 status param → 전체 반환(소프트삭제 제외)', async () => {
    const page = await listIssuesJson({})
    expect(page.content.length).toBe(4)
  })
})

describe('GET /api/v1/issues — assignee 필터링 (Task 6 B2)', () => {
  it('assignee=<bob-id> → bob 담당 이슈만 반환(전체보다 적음)', async () => {
    const page = await listIssuesJson({ assignee: BOB_ID })
    expect(page.content.length).toBeGreaterThan(0)
    expect(page.content.length).toBeLessThan(4)
    expect(page.content.every((i) => i.assigneeId === BOB_ID)).toBe(true)
  })

  it('assignee=unassigned → 미배정(null) 이슈만 반환(전체보다 적음)', async () => {
    const page = await listIssuesJson({ assignee: 'unassigned' })
    expect(page.content.length).toBeGreaterThan(0)
    expect(page.content.length).toBeLessThan(4)
    expect(page.content.every((i) => i.assigneeId === null)).toBe(true)
  })

  it('assignee=<bob-id>&assignee=unassigned → OR 결합 (bob + 미배정)', async () => {
    const page = await listIssuesJson({ assignee: [BOB_ID, 'unassigned'] })
    // 미배정 2건 + bob 1건 = 3건 (alice 1건 제외)
    expect(page.content.length).toBeGreaterThan(1)
    expect(page.content.length).toBeLessThan(4)
    expect(
      page.content.every((i) => i.assigneeId === null || i.assigneeId === BOB_ID),
    ).toBe(true)
  })
})

describe('GET /api/v1/issues — label 필터링 (Task 6 B2)', () => {
  it('label=bug → bug 라벨 이슈만 반환(전체보다 적음)', async () => {
    const page = await listIssuesJson({ label: 'bug' })
    expect(page.content.length).toBeGreaterThan(0)
    expect(page.content.length).toBeLessThan(4)
    expect(page.content.every((i) => i.labels.includes('bug'))).toBe(true)
  })

  it('label=bug&label=frontend → OR 결합 (bug 또는 frontend 라벨)', async () => {
    const page = await listIssuesJson({ label: ['bug', 'frontend'] })
    expect(page.content.length).toBeGreaterThan(1)
    expect(page.content.length).toBeLessThan(4)
    expect(
      page.content.every((i) => i.labels.includes('bug') || i.labels.includes('frontend')),
    ).toBe(true)
  })
})

describe('GET /api/v1/issues — component 필터링 (Task 6 B2)', () => {
  it('component=<COMP_A_ID> → 해당 컴포넌트 이슈만 반환(전체보다 적음)', async () => {
    const page = await listIssuesJson({ component: COMP_A_ID })
    expect(page.content.length).toBeGreaterThan(0)
    expect(page.content.length).toBeLessThan(4)
    expect(page.content.every((i) => i.componentIds.includes(COMP_A_ID))).toBe(true)
  })
})

describe('GET /api/v1/issues — 복합 AND 필터링 (Task 6 B2)', () => {
  it('status=open AND assignee=<bob-id> → 두 조건 모두 만족하는 이슈만', async () => {
    const page = await listIssuesJson({ status: 'open', assignee: BOB_ID })
    // status=open(ATLAS-1) + assignee=bob(ATLAS-2) 의 교집합 확인
    // ATLAS-2는 in_progress이므로 교집합 0건 또는 open인 bob 이슈만 반환
    // 전체(4)보다 적어야 함
    expect(page.content.length).toBeLessThan(4)
    expect(
      page.content.every((i) => i.currentStateKey === 'open' && i.assigneeId === BOB_ID),
    ).toBe(true)
  })

  it('status=done AND label=frontend → 두 조건 교집합', async () => {
    const page = await listIssuesJson({ status: 'done', label: 'frontend' })
    expect(page.content.length).toBeLessThan(4)
    expect(
      page.content.every((i) => i.currentStateKey === 'done' && i.labels.includes('frontend')),
    ).toBe(true)
  })
})
