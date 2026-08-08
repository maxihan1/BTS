// 백로그 에픽 이름 해석 훅 단위 테스트 — distinct 파생 · 요청 수 · 키 폴백 · 조회 상한 (FR-UX-13 F16 Task 3)
import type { ReactNode } from 'react'
import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse, delay } from 'msw'
import { server } from '@/test/server'
import type { BacklogIssue, BacklogView, SprintMeta } from '@/api/backlog'
import type { IssueResponse } from '@/api/issues'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { EPIC_NAME_LOOKUP_LIMIT, useBacklogEpics } from '../use-backlog-epics'

// ─────────────────────────────────────────────────────────────────────────────
// 하네스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * QueryClient 를 함께 돌려준다 — 실패 케이스에서 「아직 로딩」과 「조회 실패」를 구분하려면
 * 쿼리 상태를 직접 봐야 한다. 시간 지연으로 순서를 맞추면 느린 CI 에서 flaky 가 된다.
 */
function createHarness() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper }
}

/** 이슈 상세 응답 fixture — 이 테스트가 보는 축은 `summary` 하나뿐이다. */
function epicIssueResponse(key: string, seed: number): IssueResponse {
  return {
    key,
    id: `33333333-3333-4333-8333-${String(seed).padStart(12, '0')}`,
    projectKey: 'ATLAS',
    summary: `${key} 에픽`,
    currentStateKey: 'open',
    reporterId: '44444444-4444-4444-8444-444444444444',
    assigneeId: null,
    componentIds: [],
    affectsVersionIds: [],
    fixVersionIds: [],
    version: 0,
    createdAt: '2026-01-01T09:00:00Z',
    updatedAt: null,
    typeId: 4,
    typeKey: 'epic',
    typeName: '에픽',
    description: null,
    descriptionHtml: null,
    priority: 3,
    priorityName: 'Medium',
    labels: [],
    environment: null,
    impact: null,
    impactName: null,
    customFields: {},
    restrictedFields: [],
    noneditableFields: [],
  }
}

/** {@link mockEpicIssues} 옵션 */
interface EpicIssueMockOptions {
  /** 404 로 떨어뜨릴 이슈 키 목록 (조회 실패 경로 재현) */
  notFound?: string[]
  /** true 면 응답을 영원히 붙잡아 「조회 중」 상태를 고정한다 */
  hang?: boolean
}

/**
 * `GET /api/v1/issues/{key}` 를 가로채고 **요청된 키를 순서대로 기록**한다.
 *
 * 반환 배열이 요청 수 단언(N2)과 상한 단언의 유일한 증인이다 — 훅의 반환값만 보면
 * 「이름을 못 얻었다」와 「요청조차 안 했다」가 구분되지 않는다.
 */
function mockEpicIssues(options: EpicIssueMockOptions = {}): string[] {
  const requested: string[] = []
  let seed = 0
  server.use(
    http.get('/api/v1/issues/:key', async ({ params }) => {
      const key = String(params.key)
      requested.push(key)
      if (options.hang === true) await delay('infinite')
      if (options.notFound?.includes(key) === true) {
        return HttpResponse.json({ message: `이슈를 찾을 수 없습니다: ${key}` }, { status: 404 })
      }
      seed += 1
      return HttpResponse.json({ data: epicIssueResponse(key, seed) })
    }),
  )
  return requested
}

/** 백로그 카드 fixture — 이 테스트가 보는 축은 `key` 와 `epicKey` 둘뿐이다. */
function card(key: string, epicKey: string | null): BacklogIssue {
  return {
    key,
    summary: `${key} 요약`,
    currentStateKey: 'open',
    assigneeId: null,
    priority: 3,
    rank: null,
    version: 0,
    epicKey,
    typeKey: 'task',
    labels: [],
    originalEstimateSeconds: null,
  }
}

/** 스프린트 메타 fixture — 섹션을 가르는 용도라 이름·id 외에는 의미가 없다. */
function sprintMeta(index: number): SprintMeta {
  return {
    sprintId: `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`,
    name: `스프린트 ${index + 1}`,
    goal: null,
    status: 'PLANNED',
    startDate: null,
    endDate: null,
    version: 0,
  }
}

/** 백로그 섹션 + 스프린트 섹션들로 `BacklogView` 를 조립한다. */
function viewOf(backlog: BacklogIssue[], sprintIssues: BacklogIssue[][] = []): BacklogView {
  return {
    backlog,
    sprints: sprintIssues.map((issues, index) => ({ sprint: sprintMeta(index), issues })),
    truncated: false,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// T1. distinct 파생 — null 제외 · 중복 제거 · 백로그 + 전 스프린트
// ─────────────────────────────────────────────────────────────────────────────

describe('useBacklogEpics — distinct 에픽 키 파생', () => {
  it('T1: 백로그와 모든 스프린트 섹션에서 epicKey 를 모은다 (null 제외·중복 제거)', () => {
    mockEpicIssues()
    const view = viewOf(
      [card('ATLAS-1', 'EPIC-A'), card('ATLAS-2', null), card('ATLAS-3', 'EPIC-A')],
      [
        [card('ATLAS-4', 'EPIC-B'), card('ATLAS-5', null)],
        [card('ATLAS-6', 'EPIC-C'), card('ATLAS-7', 'EPIC-B')],
      ],
    )
    const { wrapper } = createHarness()

    const { result } = renderHook(() => useBacklogEpics(view), { wrapper })

    // 등장 순(백로그 → 스프린트), 중복 1회, null 은 항목이 되지 않는다
    expect(result.current.epicKeys).toEqual(['EPIC-A', 'EPIC-B', 'EPIC-C'])
  })

  it('T1-b: view 가 아직 없으면 에픽 목록이 비고 요청도 0건이다', () => {
    const requested = mockEpicIssues()
    const { wrapper } = createHarness()

    const { result } = renderHook(() => useBacklogEpics(undefined), { wrapper })

    expect(result.current.epicKeys).toEqual([])
    expect(requested).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2. 요청 수 = distinct 에픽 수 (카드 수와 무관) — NFR N2
// ─────────────────────────────────────────────────────────────────────────────

describe('useBacklogEpics — 요청 수', () => {
  it('T2: 카드 40건·에픽 3종이면 요청은 3건이다', async () => {
    const requested = mockEpicIssues()
    const cards = Array.from({ length: 40 }, (_, index) =>
      card(`ATLAS-${index + 1}`, `EPIC-${(index % 3) + 1}`),
    )
    const { wrapper } = createHarness()

    const { result } = renderHook(() => useBacklogEpics(viewOf(cards)), { wrapper })

    await waitFor(() => expect(result.current.epicNames.get('EPIC-3')).toBe('EPIC-3 에픽'))
    expect(requested).toHaveLength(3)
    expect([...requested].sort()).toEqual(['EPIC-1', 'EPIC-2', 'EPIC-3'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3. 이름을 못 얻으면 「키」를 준다 — 빈 문자열·undefined 금지 (EC3 / F16-6)
// ─────────────────────────────────────────────────────────────────────────────

describe('useBacklogEpics — 이름 폴백', () => {
  it('T3: 조회 중에는 이름 자리에 키가 들어간다', () => {
    mockEpicIssues({ hang: true })
    const { wrapper } = createHarness()

    const { result } = renderHook(() => useBacklogEpics(viewOf([card('ATLAS-1', 'EPIC-A')])), {
      wrapper,
    })

    expect(result.current.epicNames.get('EPIC-A')).toBe('EPIC-A')
  })

  it('T3-b: 조회 실패한 에픽은 키, 같은 화면의 성공 에픽은 이름 (짝)', async () => {
    mockEpicIssues({ notFound: ['EPIC-MISSING'] })
    const view = viewOf([card('ATLAS-1', 'EPIC-OK'), card('ATLAS-2', 'EPIC-MISSING')])
    const { client, wrapper } = createHarness()

    const { result } = renderHook(() => useBacklogEpics(view), { wrapper })

    // 「아직 로딩」이 아니라 「실패로 정착」임을 쿼리 상태로 못박는다.
    // 이 단언은 훅이 이슈 상세와 **같은 queryKey**(캐시 공유)를 쓴다는 계약도 함께 잰다.
    await waitFor(() =>
      expect(client.getQueryState(issueQueryKey('EPIC-MISSING'))?.status).toBe('error'),
    )
    await waitFor(() => expect(result.current.epicNames.get('EPIC-OK')).toBe('EPIC-OK 에픽'))

    expect(result.current.epicNames.get('EPIC-MISSING')).toBe('EPIC-MISSING')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4. 조회 상한 — distinct 가 많아도 요청은 EPIC_NAME_LOOKUP_LIMIT 에서 멈춘다 (CONCERN-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('useBacklogEpics — 조회 상한', () => {
  it('T4-a: 상한은 50 이다 (변경은 의도된 행위여야 한다)', () => {
    expect(EPIC_NAME_LOOKUP_LIMIT).toBe(50)
  })

  it('T4-b: 상한을 넘는 에픽은 요청하지 않고 키로 표시한다', async () => {
    const requested = mockEpicIssues()
    const overflow = 10
    const total = EPIC_NAME_LOOKUP_LIMIT + overflow
    const cards = Array.from({ length: total }, (_, index) =>
      card(`ATLAS-${index + 1}`, `EPIC-${index + 1}`),
    )
    const beyondLimitKey = `EPIC-${EPIC_NAME_LOOKUP_LIMIT + 1}`
    const { wrapper } = createHarness()

    const { result } = renderHook(() => useBacklogEpics(viewOf(cards)), { wrapper })

    // 상한 안쪽은 실제로 이름이 온다 — 이 짝이 없으면 아래 단언이 공허해진다
    await waitFor(() => expect(result.current.epicNames.get('EPIC-1')).toBe('EPIC-1 에픽'))

    // 요청은 상한에서 멈춘다 (이슈 1,000건 프로젝트의 요청 폭발 차단)
    expect(requested).toHaveLength(EPIC_NAME_LOOKUP_LIMIT)
    expect(requested).not.toContain(beyondLimitKey)

    // 초과분도 목록에는 남는다 — 사라지는 대신 키로 보인다
    expect(result.current.epicKeys).toHaveLength(total)
    expect(result.current.epicNames.get(beyondLimitKey)).toBe(beyondLimitKey)
  })
})
