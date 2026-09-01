// 발행·미리보기·복원 뮤테이션 훅 테스트 — 캐시 금지 · 앵커 전달 · 409 잔여건수
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useWorkflowPublish } from '../use-workflow-publish'
import { WorkflowPublishMappingRequiredError } from '@/api/workflows-admin.http'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

const KEY = 'software-default'
const BASE = `/api/v1/workflows/${KEY}`

let previewCalls = 0
let publishBodies: unknown[] = []

beforeEach(() => {
  previewCalls = 0
  publishBodies = []
  server.use(
    http.post(`${BASE}/publish/preview`, () => {
      previewCalls += 1
      return HttpResponse.json({
        data: {
          baseVersion: 4,
          // ★ 앵커와 다른 값을 준다 — 화면이 이 값을 되실어 보내면 락이 풀린다.
          currentVersion: 11,
          removedStatusKeys: ['done'],
          pendingIssueCounts: {},
        },
      })
    }),
    http.post(`${BASE}/publish`, async ({ request }) => {
      publishBodies.push(await request.json())
      return HttpResponse.json({ data: { versionNo: 5 } })
    }),
    http.post(`${BASE}/reset-to-default`, () =>
      HttpResponse.json({
        data: {
          definition: { key: KEY, name: '기본값', description: null, states: [], transitions: [] },
          baseVersion: 7,
          exists: true,
          canResetToDefault: true,
        },
      }),
    ),
  )
})

describe('preview', () => {
  it('빠지는 상태와 잔여 건수를 돌려준다', async () => {
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    let preview: Awaited<ReturnType<typeof result.current.preview>> | undefined
    await act(async () => {
      preview = await result.current.preview()
    })

    expect(preview?.removedStatusKeys).toEqual(['done'])
  })

  it('부를 때마다 서버에 새로 묻는다 — 캐시하지 않는다', async () => {
    // ★ 초안이 바뀌면 옛 preview 는 거짓이다. 캐시에 얹으면 상태를 빼고 발행해도
    //   「바뀐 것 없음」이 떠서 마법사가 뜨지 않는다.
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    await act(async () => {
      await result.current.preview()
      await result.current.preview()
    })

    expect(previewCalls).toBe(2)
  })
})

describe('publish', () => {
  it('넘겨준 앵커를 그대로 싣는다', async () => {
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    await act(async () => {
      await result.current.publish(4)
    })

    expect(publishBodies[0]).toEqual({ baseVersion: 4 })
  })

  it('preview 를 먼저 불러도 앵커가 currentVersion 으로 바뀌지 않는다', async () => {
    // ★ 낙관적 락의 핵심. preview 응답의 currentVersion(11)을 되실어 보내면 락이 풀린다.
    //   훅이 그 값을 보관하거나 기본값으로 쓰지 않는지 실제 요청 본문으로 잰다.
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    await act(async () => {
      await result.current.preview()
      await result.current.publish(4)
    })

    expect(publishBodies[0]).toEqual({ baseVersion: 4 })
  })

  it('이관 필요 409 를 잔여 건수와 함께 올린다', async () => {
    server.use(
      http.post(`${BASE}/publish`, () =>
        HttpResponse.json(
          {
            error: { code: 'WORKFLOW_PUBLISH_MAPPING_REQUIRED', message: '옮길 상태를 정하세요' },
            pendingIssueCounts: { done: 3 },
          },
          { status: 409 },
        ),
      ),
    )
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    const error = await act(async () => result.current.publish(4).catch((e: unknown) => e))

    expect(error).toBeInstanceOf(WorkflowPublishMappingRequiredError)
    expect((error as WorkflowPublishMappingRequiredError).pendingIssueCounts).toEqual({ done: 3 })
  })

  it('발행이 성공하면 워크플로우 조회 캐시를 무효화한다', async () => {
    // 발행해야 목록·상세가 바뀐다 — 무효화가 없으면 화면이 옛 정의를 계속 보여준다.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const spy = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper })

    await act(async () => {
      await result.current.publish(4)
    })

    await waitFor(() => {
      expect(spy).toHaveBeenCalled()
    })
  })
})

describe('resetToDefault', () => {
  it('복원된 초안과 새 앵커를 돌려준다', async () => {
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    let restored: Awaited<ReturnType<typeof result.current.resetToDefault>> | undefined
    await act(async () => {
      restored = await result.current.resetToDefault(4)
    })

    expect(restored?.baseVersion).toBe(7)
    expect(restored?.definition.name).toBe('기본값')
  })
})

describe('discard', () => {
  it('초안을 폐기하고 초안 캐시를 지운다', async () => {
    let discarded = false
    server.use(
      http.delete(`${BASE}/draft`, () => {
        discarded = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { result } = renderHook(() => useWorkflowPublish(KEY), { wrapper: createWrapper() })

    await act(async () => {
      await result.current.discard()
    })

    expect(discarded).toBe(true)
  })
})
