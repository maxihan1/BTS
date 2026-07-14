// useAutomationExecutions 훅 테스트 — 커서 무한스크롤 목록 + 단건 trace enabled 가드 + replay 캐시 갱신 검증 (FR-AT-05 D6/D7)
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import {
  AUTOMATION_EXECUTIONS_QUERY_KEY,
  AUTOMATION_EXECUTION_DETAIL_QUERY_KEY,
  useRuleExecutions,
  useRuleExecutionDetail,
  useReplayRuleExecution,
} from './useAutomationExecutions'
import type { RuleExecutionSummary, RuleExecutionDetail } from './automation-executions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — automation-executions.test.ts와 동형 v4 UUID 형식(Zod v4 uuid() 검증 통과)
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const RULE_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e'
const FILTERED_ISSUE_KEY = 'ATLAS-1'
const PAGE_LIMIT = 50
const TOTAL_EXECUTIONS = 53
const BASE_TIME_MS = Date.parse('2026-07-10T10:00:00Z')

/** n을 4자리 hex로 넣어 서로 다른 v4 형식 UUID를 생성 — 버전(4)/변형(8) 니블은 고정 */
function uuidFromIndex(n: number): string {
  const segment = n.toString(16).padStart(4, '0')
  return `aaaaaaaa-${segment}-4aaa-8aaa-aaaaaaaaaaaa`
}

/** index가 클수록 과거(startedAt이 작음) — 백엔드 desc 정렬(최신 먼저)을 모사 */
function buildSummary(index: number): RuleExecutionSummary {
  const startedAt = new Date(BASE_TIME_MS - index * 60_000).toISOString()
  const finishedAt = new Date(BASE_TIME_MS - index * 60_000 + 1_000).toISOString()
  return {
    id: uuidFromIndex(index),
    ruleId: RULE_ID,
    triggerType: 'ISSUE_CREATED',
    issueKey: 'ATLAS-100',
    status: 'SUCCESS',
    actionCount: 1,
    successCount: 1,
    startedAt,
    finishedAt,
    replayedFrom: null,
  }
}

const ALL_EXECUTIONS: RuleExecutionSummary[] = Array.from({ length: TOTAL_EXECUTIONS }, (_, index) =>
  buildSummary(index),
)

const FILTERED_EXECUTION: RuleExecutionSummary = { ...buildSummary(9000), issueKey: FILTERED_ISSUE_KEY }

const DETAIL_ID = uuidFromIndex(9100)
const DETAIL_FIXTURE: RuleExecutionDetail = {
  id: DETAIL_ID,
  ruleId: RULE_ID,
  projectKey: PROJECT_KEY,
  triggerType: 'ISSUE_CREATED',
  triggerEvent: { issueKey: 'ATLAS-100', type: 'ISSUE_CREATED' },
  issueKey: 'ATLAS-100',
  status: 'SUCCESS',
  outcomes: [{ position: 0, actionType: 'SET_FIELD', success: true, error: null }],
  replayedFrom: null,
  startedAt: '2026-07-10T09:00:00Z',
  finishedAt: '2026-07-10T09:00:01Z',
}

const REPLAY_SOURCE_ID = uuidFromIndex(9200)
const REPLAYED_DETAIL: RuleExecutionDetail = {
  id: uuidFromIndex(9201),
  ruleId: RULE_ID,
  projectKey: PROJECT_KEY,
  triggerType: 'ISSUE_CREATED',
  triggerEvent: { issueKey: 'ATLAS-100', type: 'ISSUE_CREATED' },
  issueKey: 'ATLAS-100',
  status: 'PARTIAL',
  outcomes: [
    { position: 0, actionType: 'SET_FIELD', success: true, error: null },
    { position: 1, actionType: 'ADD_COMMENT', success: false, error: 'ISSUE_LOCKED' },
  ],
  replayedFrom: REPLAY_SOURCE_ID,
  startedAt: '2026-07-10T11:00:00Z',
  finishedAt: '2026-07-10T11:00:02Z',
}

// CONCERN 4 회귀 테스트 전용 — issueKey 필터가 활성인 상태에서 replay 시 그 필터 쿼리 캐시에도
// 반영되는지 검증한다. FILTER_MATCH_ISSUE_KEY는 replay 응답이 물려받는 issueKey와 같아 매칭되고,
// FILTER_MISMATCH_ISSUE_KEY는 달라 skip돼야 한다.
const FILTER_MATCH_ISSUE_KEY = 'ATLAS-2'
const FILTER_MISMATCH_ISSUE_KEY = 'ATLAS-9'
const REPLAY_SOURCE_ID_2 = uuidFromIndex(9300)
const REPLAYED_DETAIL_2: RuleExecutionDetail = {
  id: uuidFromIndex(9301),
  ruleId: RULE_ID,
  projectKey: PROJECT_KEY,
  triggerType: 'ISSUE_CREATED',
  triggerEvent: { issueKey: FILTER_MATCH_ISSUE_KEY, type: 'ISSUE_CREATED' },
  issueKey: FILTER_MATCH_ISSUE_KEY,
  status: 'SUCCESS',
  outcomes: [{ position: 0, actionType: 'SET_FIELD', success: true, error: null }],
  replayedFrom: REPLAY_SOURCE_ID_2,
  startedAt: '2026-07-10T12:00:00Z',
  finishedAt: '2026-07-10T12:00:01Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — useCalendarFeed.test.ts 선례(전역 `@/test/server` + `server.use()`)를 미러한다.
// automation-executions.test.ts처럼 이 파일 로컬 `setupServer()`를 새로 띄우면 src/test/setup.ts가
// 이미 listen 중인 전역 서버와 두 개의 MSW 인스턴스가 동시에 활성화되어, 요청 1건이 두 인스턴스 모두에
// 전달돼 핸들러가 중복 호출된다(요청 횟수를 세는 이 테스트에서 실측 확인됨) — 이 task의 허용 파일이
// 2개(.ts/.test.tsx)뿐이라 별도 mocks 핸들러 파일 신설 대신 전역 서버에 `server.use()`로 인라인 등록한다.
// ─────────────────────────────────────────────────────────────────────────────

let detailRequestCount = 0

function registerExecutionHandlers(): void {
  server.use(
    http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', ({ request }) => {
      const url = new URL(request.url)
      const issueKeyParam = url.searchParams.get('issueKey')
      const beforeParam = url.searchParams.get('before')
      const limitParam = url.searchParams.get('limit')
      const limit = limitParam !== null ? Number(limitParam) : ALL_EXECUTIONS.length

      if (issueKeyParam === FILTERED_ISSUE_KEY) {
        return HttpResponse.json([FILTERED_EXECUTION])
      }
      if (issueKeyParam !== null) {
        // FILTERED_ISSUE_KEY 외 임의 issueKey 필터 — ALL_EXECUTIONS는 전부 'ATLAS-100'이라
        // FILTER_MATCH_ISSUE_KEY/FILTER_MISMATCH_ISSUE_KEY 둘 다 초기엔 빈 배열을 반환한다
        // (replay로 캐시에 직접 prepend되는지를 검증하는 것이 목적이라 서버 응답은 항상 비움).
        return HttpResponse.json(ALL_EXECUTIONS.filter((item) => item.issueKey === issueKeyParam).slice(0, limit))
      }

      if (beforeParam === null) {
        return HttpResponse.json(ALL_EXECUTIONS.slice(0, limit))
      }
      const cursorIndex = ALL_EXECUTIONS.findIndex((item) => item.startedAt === beforeParam)
      const startIndex = cursorIndex === -1 ? ALL_EXECUTIONS.length : cursorIndex + 1
      return HttpResponse.json(ALL_EXECUTIONS.slice(startIndex, startIndex + limit))
    }),
    http.get('/api/v1/automation/executions/:id', ({ params }) => {
      detailRequestCount += 1
      if (params['id'] === DETAIL_ID) {
        return HttpResponse.json(DETAIL_FIXTURE)
      }
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }),
    http.post('/api/v1/automation/executions/:id/replay', ({ params }) => {
      if (params['id'] === REPLAY_SOURCE_ID) {
        return HttpResponse.json(REPLAYED_DETAIL)
      }
      if (params['id'] === REPLAY_SOURCE_ID_2) {
        return HttpResponse.json(REPLAYED_DETAIL_2)
      }
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }),
  )
}

beforeEach(() => {
  registerExecutionHandlers()
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
  detailRequestCount = 0
})

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

describe('useAutomationExecutions 훅 묶음', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useRuleExecutions
  // ─────────────────────────────────────────────────────────────────────────

  describe('useRuleExecutions', () => {
    it('초기 목록을 로드한다 (첫 페이지, limit 개수만큼)', async () => {
      const { result } = renderHook(() => useRuleExecutions(PROJECT_KEY, RULE_ID), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))
      expect(result.current.executions).toHaveLength(PAGE_LIMIT)
      expect(result.current.executions[0]?.id).toBe(ALL_EXECUTIONS[0]?.id)
      expect(result.current.error).toBeNull()
    })

    it('첫 페이지 길이가 limit과 같으면 hasNextPage가 true다', async () => {
      const { result } = renderHook(() => useRuleExecutions(PROJECT_KEY, RULE_ID), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))
      expect(result.current.hasNextPage).toBe(true)
    })

    it('fetchNextPage 호출 시 다음 페이지가 누적되고, 더 없으면 hasNextPage가 false가 된다', async () => {
      const { result } = renderHook(() => useRuleExecutions(PROJECT_KEY, RULE_ID), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      await act(async () => {
        await result.current.fetchNextPage()
      })

      await waitFor(() => expect(result.current.isFetchingNextPage).toBe(false))
      expect(result.current.executions).toHaveLength(TOTAL_EXECUTIONS)
      expect(result.current.executions[PAGE_LIMIT]?.id).toBe(ALL_EXECUTIONS[PAGE_LIMIT]?.id)
      expect(result.current.hasNextPage).toBe(false)
    })

    it('issueKey가 다르면 별도 쿼리 캐시를 사용한다 (filter-aware)', async () => {
      const wrapper = createWrapper(queryClient)
      const { result: unfiltered } = renderHook(() => useRuleExecutions(PROJECT_KEY, RULE_ID), { wrapper })
      const { result: filtered } = renderHook(
        () => useRuleExecutions(PROJECT_KEY, RULE_ID, { issueKey: FILTERED_ISSUE_KEY }),
        { wrapper },
      )

      await waitFor(() => expect(unfiltered.current.isLoading).toBe(false))
      await waitFor(() => expect(filtered.current.isLoading).toBe(false))

      expect(unfiltered.current.executions).toHaveLength(PAGE_LIMIT)
      expect(filtered.current.executions).toHaveLength(1)
      expect(filtered.current.executions[0]?.issueKey).toBe(FILTERED_ISSUE_KEY)

      expect(AUTOMATION_EXECUTIONS_QUERY_KEY(PROJECT_KEY, RULE_ID)).toEqual([
        'automation-executions',
        PROJECT_KEY,
        RULE_ID,
        null,
      ])
      expect(AUTOMATION_EXECUTIONS_QUERY_KEY(PROJECT_KEY, RULE_ID, FILTERED_ISSUE_KEY)).not.toEqual(
        AUTOMATION_EXECUTIONS_QUERY_KEY(PROJECT_KEY, RULE_ID),
      )
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useRuleExecutionDetail
  // ─────────────────────────────────────────────────────────────────────────

  describe('useRuleExecutionDetail', () => {
    it('enabled가 false면 조회하지 않는다', () => {
      const { result } = renderHook(() => useRuleExecutionDetail(DETAIL_ID, { enabled: false }), {
        wrapper: createWrapper(queryClient),
      })

      expect(result.current.fetchStatus).toBe('idle')
      expect(detailRequestCount).toBe(0)
    })

    it('id가 null이면 enabled=true여도 조회하지 않는다', () => {
      const { result } = renderHook(() => useRuleExecutionDetail(null, { enabled: true }), {
        wrapper: createWrapper(queryClient),
      })

      expect(result.current.fetchStatus).toBe('idle')
      expect(detailRequestCount).toBe(0)
    })

    it('enabled=true + id 지정 시 detail을 반환한다', async () => {
      const { result } = renderHook(() => useRuleExecutionDetail(DETAIL_ID, { enabled: true }), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data?.id).toBe(DETAIL_ID)
      expect(result.current.data?.outcomes).toHaveLength(1)
      expect(detailRequestCount).toBe(1)
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useReplayRuleExecution
  // ─────────────────────────────────────────────────────────────────────────

  describe('useReplayRuleExecution', () => {
    it('재실행 성공 시 detail 캐시를 시드하고, 목록 첫 페이지에 새 실행을 prepend한다 (summary 매핑 포함)', async () => {
      const wrapper = createWrapper(queryClient)
      const { result: list } = renderHook(() => useRuleExecutions(PROJECT_KEY, RULE_ID), { wrapper })
      await waitFor(() => expect(list.current.isLoading).toBe(false))
      const beforeCount = list.current.executions.length

      const { result: replay } = renderHook(() => useReplayRuleExecution(PROJECT_KEY, RULE_ID), { wrapper })

      await act(async () => {
        await replay.current.mutateAsync(REPLAY_SOURCE_ID)
      })

      await waitFor(() => expect(replay.current.isSuccess).toBe(true))

      const seededDetail = queryClient.getQueryData(AUTOMATION_EXECUTION_DETAIL_QUERY_KEY(REPLAYED_DETAIL.id))
      expect(seededDetail).toEqual(REPLAYED_DETAIL)

      await waitFor(() => expect(list.current.executions.length).toBe(beforeCount + 1))
      const prepended = list.current.executions[0]
      expect(prepended?.id).toBe(REPLAYED_DETAIL.id)
      expect(prepended?.replayedFrom).toBe(REPLAY_SOURCE_ID)
      expect(prepended?.actionCount).toBe(2)
      expect(prepended?.successCount).toBe(1)
    })

    it('detail 캐시 시드 덕분에 재실행 직후 detail 훅을 펼쳐도 재fetch하지 않는다', async () => {
      const wrapper = createWrapper(queryClient)
      const { result: replay } = renderHook(() => useReplayRuleExecution(PROJECT_KEY, RULE_ID), { wrapper })

      await act(async () => {
        await replay.current.mutateAsync(REPLAY_SOURCE_ID)
      })
      await waitFor(() => expect(replay.current.isSuccess).toBe(true))

      const detailRequestsBefore = detailRequestCount
      const { result: detail } = renderHook(() => useRuleExecutionDetail(REPLAYED_DETAIL.id, { enabled: true }), {
        wrapper,
      })

      expect(detail.current.data?.id).toBe(REPLAYED_DETAIL.id)
      expect(detailRequestCount).toBe(detailRequestsBefore)
    })

    it('목록 전체를 덮지 않고 첫 페이지 배열만 교체한다 (이미 로드된 다음 페이지 보존)', async () => {
      const wrapper = createWrapper(queryClient)
      const { result: list } = renderHook(() => useRuleExecutions(PROJECT_KEY, RULE_ID), { wrapper })
      await waitFor(() => expect(list.current.isLoading).toBe(false))

      await act(async () => {
        await list.current.fetchNextPage()
      })
      await waitFor(() => expect(list.current.isFetchingNextPage).toBe(false))
      expect(list.current.executions).toHaveLength(TOTAL_EXECUTIONS)

      const { result: replay } = renderHook(() => useReplayRuleExecution(PROJECT_KEY, RULE_ID), { wrapper })
      await act(async () => {
        await replay.current.mutateAsync(REPLAY_SOURCE_ID)
      })
      await waitFor(() => expect(replay.current.isSuccess).toBe(true))

      await waitFor(() => expect(list.current.executions.length).toBe(TOTAL_EXECUTIONS + 1))
      // 두 번째 페이지의 마지막 항목이 여전히 살아있어야 한다 (prepend가 첫 페이지만 건드림)
      expect(list.current.executions[TOTAL_EXECUTIONS]?.id).toBe(ALL_EXECUTIONS[TOTAL_EXECUTIONS - 1]?.id)
    })

    it('issueKey 필터가 활성인 상태에서 재실행하면 매칭 필터 캐시엔 prepend되고, 불일치 필터엔 반영되지 않는다 (CONCERN 4)', async () => {
      const wrapper = createWrapper(queryClient)
      const { result: matched } = renderHook(
        () => useRuleExecutions(PROJECT_KEY, RULE_ID, { issueKey: FILTER_MATCH_ISSUE_KEY }),
        { wrapper },
      )
      const { result: mismatched } = renderHook(
        () => useRuleExecutions(PROJECT_KEY, RULE_ID, { issueKey: FILTER_MISMATCH_ISSUE_KEY }),
        { wrapper },
      )

      await waitFor(() => expect(matched.current.isLoading).toBe(false))
      await waitFor(() => expect(mismatched.current.isLoading).toBe(false))
      expect(matched.current.executions).toHaveLength(0)
      expect(mismatched.current.executions).toHaveLength(0)

      const { result: replay } = renderHook(() => useReplayRuleExecution(PROJECT_KEY, RULE_ID), { wrapper })
      await act(async () => {
        await replay.current.mutateAsync(REPLAY_SOURCE_ID_2)
      })
      await waitFor(() => expect(replay.current.isSuccess).toBe(true))

      await waitFor(() => expect(matched.current.executions).toHaveLength(1))
      expect(matched.current.executions[0]?.id).toBe(REPLAYED_DETAIL_2.id)
      expect(matched.current.executions[0]?.issueKey).toBe(FILTER_MATCH_ISSUE_KEY)
      expect(mismatched.current.executions).toHaveLength(0)
    })
  })
})
