// useGadgetData 훅 단위 테스트 — FR-DB-02 D6/D7 Task-4 RED phase
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// API 모듈 mock — 실제 HTTP 요청 없이 단위 테스트 (vi.mock은 vitest가 hoisting)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/issues')
vi.mock('@/api/search')
vi.mock('@/api/saved-filters')

import { fetchIssues } from '@/api/issues'
import type { IssuePage, IssueResponse } from '@/api/issues'
import { searchAql } from '@/api/search'
import type { AqlSearchHit, AqlSearchPage } from '@/api/search'
import { fetchFilter } from '@/api/saved-filters'
import type { SavedFilterResponse } from '@/api/saved-filters'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser } from '@/mocks/auth-fixtures'

import { issueResponseToRow, aqlHitToRow, clampMaxItems, useGadgetData } from './useGadgetData'
import type { GadgetConfig } from './gadget-types'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 상수
// ─────────────────────────────────────────────────────────────────────────────

/** aliceUser.userId 와 동기화 */
const ALICE_ID = '00000000-0000-4000-8000-000000000001'
const PROJECT_KEY = 'ATLAS'
const FILTER_ID = '10000000-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — IssueResponse (최소 필드, 정규화 함수·목록 가젯 테스트용)
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_ISSUE: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: PROJECT_KEY,
  summary: '첫 번째 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
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

/** Spring Page<IssueResponse> 픽스처 생성 헬퍼 */
function makeIssuePage(issues: IssueResponse[], total?: number): IssuePage {
  const totalElements = total ?? issues.length
  return {
    content: issues,
    totalElements,
    totalPages: Math.max(1, Math.ceil(totalElements / 20)),
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: issues.length === 0,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — AqlSearchHit (AQL 검색 결과 정규화 테스트용)
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_HIT: AqlSearchHit = {
  key: 'ATLAS-2',
  summary: 'AQL 검색 결과',
  typeKey: 'task',
  currentStateKey: 'in_progress',
  assigneeId: ALICE_ID,
  priority: 2,
  priorityName: 'High',
  projectKey: PROJECT_KEY,
  updatedAt: '2026-06-25T10:00:00Z',
}

/** AqlSearchPage envelope 픽스처 생성 헬퍼 — searchAql 실제 응답 계약(`{data, meta.page}`)과 1:1 대응 */
function makeSearchPage(hits: AqlSearchHit[], total?: number): AqlSearchPage {
  const totalElements = total ?? hits.length
  return {
    data: hits,
    meta: {
      page: {
        number: 0,
        size: 20,
        totalElements,
        totalPages: Math.max(1, Math.ceil(totalElements / 20)),
      },
    },
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — SavedFilterResponse (저장 필터 가젯 테스트용)
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_FILTER: SavedFilterResponse = {
  id: FILTER_ID,
  ownerId: ALICE_ID,
  name: '내 필터',
  aqlQuery: 'status = open',
  projectKey: PROJECT_KEY,
  createdAt: '2026-06-01T00:00:00Z',
  updatedAt: null,
  version: 0,
  isOwner: true,
  shares: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 팩토리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 정규화 순수 함수 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('issueResponseToRow', () => {
  it('IssueResponse에서 key와 summary를 추출한다', () => {
    const row = issueResponseToRow(MOCK_ISSUE)
    expect(row).toEqual({ key: 'ATLAS-1', summary: '첫 번째 이슈' })
  })
})

describe('aqlHitToRow', () => {
  it('AqlSearchHit에서 key와 summary를 추출한다', () => {
    const row = aqlHitToRow(MOCK_HIT)
    expect(row).toEqual({ key: 'ATLAS-2', summary: 'AQL 검색 결과' })
  })
})

describe('clampMaxItems', () => {
  it('값이 없으면 기본값 10을 반환한다', () => {
    expect(clampMaxItems(undefined)).toBe(10)
  })

  it('50을 초과하면 50으로 클램프한다', () => {
    expect(clampMaxItems(100)).toBe(50)
    expect(clampMaxItems(51)).toBe(50)
  })

  it('1 미만이면 1로 클램프한다', () => {
    expect(clampMaxItems(0)).toBe(1)
    expect(clampMaxItems(-5)).toBe(1)
  })

  it('1~50 범위 내 값은 그대로 반환한다', () => {
    expect(clampMaxItems(1)).toBe(1)
    expect(clampMaxItems(10)).toBe(10)
    expect(clampMaxItems(50)).toBe(50)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useGadgetData 훅 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useGadgetData', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = createQueryClient()
    // Alice 세션 설정 — useAuthUser()가 aliceUser를 반환하도록
    useAuthStore.getState().setSession({ accessToken: 'test-token', user: aliceUser })
  })

  afterEach(() => {
    queryClient.clear()
    useAuthStore.getState().clearSession()
    vi.clearAllMocks()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // assigned_to_me
  // ─────────────────────────────────────────────────────────────────────────

  describe('assigned_to_me', () => {
    it('fetchIssues를 assignee 필터로 호출하고 rows를 반환한다', async () => {
      vi.mocked(fetchIssues).mockResolvedValue(makeIssuePage([MOCK_ISSUE]))

      const config: GadgetConfig = { projectKey: PROJECT_KEY, maxItems: 10 }
      const { result } = renderHook(() => useGadgetData('assigned_to_me', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      expect(result.current.isError).toBe(false)
      expect(result.current.rows).toEqual([{ key: 'ATLAS-1', summary: '첫 번째 이슈' }])
      expect(result.current.totalElements).toBeUndefined()

      expect(vi.mocked(fetchIssues)).toHaveBeenCalledWith({
        projectKey: PROJECT_KEY,
        page: 0,
        size: 10,
        filter: {
          assigneeIds: [ALICE_ID],
          statusKeys: [],
          includeUnassigned: false,
          labels: [],
          componentIds: [],
        },
      })
    })

    it('maxItems가 50을 초과하면 50으로 클램프해 호출한다', async () => {
      vi.mocked(fetchIssues).mockResolvedValue(makeIssuePage([]))

      const config: GadgetConfig = { projectKey: PROJECT_KEY, maxItems: 999 }
      const { result } = renderHook(() => useGadgetData('assigned_to_me', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      expect(vi.mocked(fetchIssues)).toHaveBeenCalledWith(
        expect.objectContaining({ size: 50 }),
      )
    })

    it('userId가 없으면 쿼리가 비활성화되어 fetchIssues가 호출되지 않는다', async () => {
      // 로그아웃 상태 — useAuthUser()가 null을 반환
      useAuthStore.getState().clearSession()

      const config: GadgetConfig = { projectKey: PROJECT_KEY }
      renderHook(() => useGadgetData('assigned_to_me', config), {
        wrapper: createWrapper(queryClient),
      })

      // 쿼리 비활성화 → 50ms 대기 후에도 fetch 호출 없어야 함
      await new Promise<void>((resolve) => setTimeout(resolve, 50))
      expect(vi.mocked(fetchIssues)).not.toHaveBeenCalled()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // recently_created
  // ─────────────────────────────────────────────────────────────────────────

  describe('recently_created', () => {
    it('fetchIssues를 filter 없이 호출하고 rows를 반환한다', async () => {
      vi.mocked(fetchIssues).mockResolvedValue(makeIssuePage([MOCK_ISSUE]))

      const config: GadgetConfig = { projectKey: PROJECT_KEY, maxItems: 5 }
      const { result } = renderHook(() => useGadgetData('recently_created', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      expect(result.current.rows).toEqual([{ key: 'ATLAS-1', summary: '첫 번째 이슈' }])
      expect(result.current.totalElements).toBeUndefined()

      // filter 필드 없이 호출되어야 함 (recently_created = 기본 created_at DESC)
      expect(vi.mocked(fetchIssues)).toHaveBeenCalledWith({
        projectKey: PROJECT_KEY,
        page: 0,
        size: 5,
      })
    })

    it('maxItems가 1 미만이면 1로 클램프해 호출한다', async () => {
      vi.mocked(fetchIssues).mockResolvedValue(makeIssuePage([]))

      const config: GadgetConfig = { projectKey: PROJECT_KEY, maxItems: 0 }
      const { result } = renderHook(() => useGadgetData('recently_created', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      expect(vi.mocked(fetchIssues)).toHaveBeenCalledWith(
        expect.objectContaining({ size: 1 }),
      )
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // filter_result
  // ─────────────────────────────────────────────────────────────────────────

  describe('filter_result', () => {
    it('fetchFilter → searchAql 순으로 호출하고 rows를 반환한다', async () => {
      vi.mocked(fetchFilter).mockResolvedValue(MOCK_FILTER)
      vi.mocked(searchAql).mockResolvedValue(makeSearchPage([MOCK_HIT]))

      const config: GadgetConfig = { filterId: FILTER_ID, maxItems: 10 }
      const { result } = renderHook(() => useGadgetData('filter_result', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      expect(result.current.isError).toBe(false)
      expect(result.current.rows).toEqual([{ key: 'ATLAS-2', summary: 'AQL 검색 결과' }])
      expect(result.current.totalElements).toBeUndefined()

      expect(vi.mocked(fetchFilter)).toHaveBeenCalledWith(FILTER_ID)
      expect(vi.mocked(searchAql)).toHaveBeenCalledWith({
        projectKey: PROJECT_KEY,
        query: 'status = open',
        size: 10,
      })
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // issue_count
  // ─────────────────────────────────────────────────────────────────────────

  describe('issue_count', () => {
    it('fetchFilter → searchAql 순으로 호출하고 totalElements를 반환한다', async () => {
      vi.mocked(fetchFilter).mockResolvedValue(MOCK_FILTER)
      vi.mocked(searchAql).mockResolvedValue(makeSearchPage([], 42))

      const config: GadgetConfig = { filterId: FILTER_ID }
      const { result } = renderHook(() => useGadgetData('issue_count', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      expect(result.current.totalElements).toBe(42)
      expect(result.current.rows).toBeUndefined()

      expect(vi.mocked(fetchFilter)).toHaveBeenCalledWith(FILTER_ID)
      // issue_count는 size: 1로 최소화해 데이터 전송량을 줄인다
      expect(vi.mocked(searchAql)).toHaveBeenCalledWith({
        projectKey: PROJECT_KEY,
        query: 'status = open',
        size: 1,
      })
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // 에러 상태
  // ─────────────────────────────────────────────────────────────────────────

  describe('에러 상태', () => {
    it('fetchIssues가 실패하면 isError가 true가 된다', async () => {
      vi.mocked(fetchIssues).mockRejectedValue(new Error('네트워크 오류'))

      const config: GadgetConfig = { projectKey: PROJECT_KEY }
      const { result } = renderHook(() => useGadgetData('recently_created', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
      expect(result.current.rows).toBeUndefined()
      expect(result.current.isLoading).toBe(false)
    })

    it('fetchFilter가 실패하면 isError가 true가 된다', async () => {
      vi.mocked(fetchFilter).mockRejectedValue(new Error('필터 없음'))

      const config: GadgetConfig = { filterId: FILTER_ID }
      const { result } = renderHook(() => useGadgetData('filter_result', config), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
      expect(result.current.rows).toBeUndefined()
    })
  })
})
