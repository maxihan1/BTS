// 프로젝트 리드 TanStack Query 훅 테스트 — RED phase (FR-CM-04 Task 3)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchProjectLead, changeProjectLead } from '@/api/project-lead'
import type { ProjectLead } from '@/api/project-lead'
import { useProjectLead, useChangeProjectLead } from './use-project-lead'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/project-lead', () => ({
  fetchProjectLead: vi.fn(),
  changeProjectLead: vi.fn(),
  extractProjectLeadErrorCode: vi.fn().mockReturnValue(null),
}))

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 공통 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** RFC4122 v4 형식 UUID 픽스처 */
const PROJECT_ID = '11111111-1111-4111-8111-111111111111'
const LEAD_USER_ID = '22222222-2222-4222-8222-222222222222'

const mockProjectLead: ProjectLead = {
  projectId: PROJECT_ID,
  leadUserId: LEAD_USER_ID,
}

const mockProjectLeadNoLead: ProjectLead = {
  projectId: PROJECT_ID,
  leadUserId: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// useProjectLead — 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useProjectLead', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('projectKey가 있으면 fetchProjectLead를 호출해 ProjectLead를 반환한다', async () => {
    vi.mocked(fetchProjectLead).mockResolvedValue(mockProjectLead)

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProjectLead('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchProjectLead).toHaveBeenCalledWith('ATLAS')
    expect(result.current.data).toEqual(mockProjectLead)
  })

  it('leadUserId가 null인 경우도 정상 데이터로 반환한다', async () => {
    vi.mocked(fetchProjectLead).mockResolvedValue(mockProjectLeadNoLead)

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProjectLead('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toEqual(mockProjectLeadNoLead)
    expect(result.current.data?.leadUserId).toBeNull()
  })

  it('projectKey가 빈 문자열이면 쿼리가 비활성(idle)이고 fetch가 발생하지 않는다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProjectLead(''), { wrapper })

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchProjectLead).not.toHaveBeenCalled()
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    vi.mocked(fetchProjectLead).mockImplementation(
      () => new Promise(() => undefined), // 영구 pending
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProjectLead('ATLAS'), { wrapper })

    expect(result.current.isPending).toBe(true)
  })

  it('404 에러는 에러 상태로 전파된다', async () => {
    vi.mocked(fetchProjectLead).mockRejectedValue(new Error('Not Found'))

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProjectLead('NOTEXIST'), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeProjectLead — 변경 + invalidate + 토스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeProjectLead', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('변경 성공 시 project-lead 쿼리가 invalidate되고 성공 토스트가 호출된다', async () => {
    vi.mocked(changeProjectLead).mockResolvedValue(mockProjectLead)
    vi.mocked(fetchProjectLead).mockResolvedValue(mockProjectLead)

    const { client, wrapper } = createWrapper()

    // 초기 쿼리 캐시 채우기
    const queryHook = renderHook(() => useProjectLead('ATLAS'), { wrapper })
    await waitFor(() => expect(queryHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useChangeProjectLead('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate(LEAD_USER_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidate 후 refetch가 발생했어야 함
    expect(changeProjectLead).toHaveBeenCalledWith('ATLAS', LEAD_USER_ID)
    expect(toast.success).toHaveBeenCalled()
    // 쿼리 상태가 캐시에 남아있어야 함
    expect(client.getQueryState(['project-lead', 'ATLAS'])).toBeDefined()
  })

  it('leadUserId null 전달 시 리드 해제를 수행한다', async () => {
    vi.mocked(changeProjectLead).mockResolvedValue(mockProjectLeadNoLead)

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangeProjectLead('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate(null)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(changeProjectLead).toHaveBeenCalledWith('ATLAS', null)
  })

  it('422 PROJECT_LEAD_NOT_FOUND 에러 시 toast.error가 에러 메시지로 호출된다', async () => {
    const { extractProjectLeadErrorCode } = await import('@/api/project-lead')
    vi.mocked(extractProjectLeadErrorCode).mockReturnValue('PROJECT_LEAD_NOT_FOUND')
    vi.mocked(changeProjectLead).mockRejectedValue(new Error('422'))

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangeProjectLead('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalledWith('선택한 리드 사용자를 찾을 수 없습니다.')
  })

  it('알 수 없는 에러 시 toast.error가 기본 메시지로 호출된다', async () => {
    const { extractProjectLeadErrorCode } = await import('@/api/project-lead')
    vi.mocked(extractProjectLeadErrorCode).mockReturnValue(null)
    vi.mocked(changeProjectLead).mockRejectedValue(new Error('Unknown'))

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangeProjectLead('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate(LEAD_USER_ID)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalledWith('요청을 처리하지 못했습니다.')
  })
})
