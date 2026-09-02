// 상태 이관 마법사 배선(useMigrationWizard) 테스트 — 발행본 조회 · 선택 · 이관 접수 · 폴링 리셋
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useMigrationWizard } from '../use-publish-flow'
import type { PublishPreview } from '@/api/workflows-draft.types'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const KEY = 'software-default'
const BULK_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

function createWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function preview(over: Partial<PublishPreview> = {}): PublishPreview {
  return {
    baseVersion: 4,
    currentVersion: 4,
    removedStatusKeys: ['done'],
    pendingIssueCounts: { done: 3 },
    ...over,
  }
}

/** `GET /api/v1/workflows/:key` — 발행본 조회. `MigrationWizard` 가 도착지 후보 계산에 쓴다. */
function workflowDetailHandler() {
  return http.get(`/api/v1/workflows/${KEY}`, () =>
    HttpResponse.json({
      data: {
        key: KEY,
        name: '소프트웨어 개발 기본 워크플로우',
        description: '',
        states: [
          { key: 'open', name: '열림', category: 'TODO', displayOrder: 1 },
          { key: 'done', name: '완료', category: 'DONE', displayOrder: 2 },
        ],
        transitions: [],
      },
    }),
  )
}

function bulkOperationResponse(over: Record<string, unknown> = {}) {
  return {
    id: BULK_ID,
    operationType: 'STATUS_MIGRATION',
    status: 'PENDING',
    payload: { mappings: { done: 'open' }, projectKeys: ['PRJ'] },
    totalCount: 3,
    processedCount: 0,
    succeededCount: 0,
    failedCount: 0,
    items: [],
    ...over,
  }
}

/**
 * `useMigrationWizard` 를 안정된 `preview` 참조로 렌더한다.
 *
 * ★ `renderHook(() => useMigrationWizard(key, preview()))` 처럼 콜백 안에서 직접 값을 만들면
 * `preview()` 가 매 렌더마다 **새 객체**를 낳는다. 이 훅은 `preview` 참조가 바뀔 때마다 세션을
 * 리셋하므로(의도된 동작), 그 리셋이 낳은 재렌더가 다시 새 `preview()` 를 부르는 무한 루프가
 * 된다 — `initialProps` 로 참조를 고정해야 한다.
 */
function renderMigrationWizard(key: string, p: PublishPreview | null) {
  return renderHook(({ p: current }: { p: PublishPreview | null }) => useMigrationWizard(key, current), {
    wrapper: createWrapper(),
    initialProps: { p },
  })
}

beforeEach(() => {
  server.use(workflowDetailHandler())
  // 이전 테스트가 남긴 `?migration=` 쿼리가 다음 테스트로 새지 않게 매번 초기화한다(G-2 격리).
  window.history.replaceState(null, '', `/admin/workflows/${KEY}`)
})

describe('발행본 조회', () => {
  it('빈 키에서는 조회하지 않는다 — published 가 null 로 남는다', async () => {
    const { result } = renderMigrationWizard('', preview())

    // 비활성 쿼리라 절대 채워지지 않는다는 것을 짧은 대기로 확인한다.
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(result.current.published).toBeNull()
    expect(result.current.publishedFailed).toBe(false)
  })

  it('발행본을 불러오면 상태 목록을 채운다', async () => {
    const { result } = renderMigrationWizard(KEY, preview())

    await waitFor(() => {
      expect(result.current.published).not.toBeNull()
    })
    expect(result.current.published?.states.map((s) => s.key)).toEqual(['open', 'done'])
  })

  it('발행본 조회가 실패하면 publishedFailed 를 세운다', async () => {
    server.use(http.get(`/api/v1/workflows/${KEY}`, () => HttpResponse.json({}, { status: 500 })))
    const { result } = renderMigrationWizard(KEY, preview())

    await waitFor(() => {
      expect(result.current.publishedFailed).toBe(true)
    })
    expect(result.current.published).toBeNull()
  })

  // ★ concern 1 — 편집기 마운트마다 발행본을 조회하면 이관이 필요 없는 대다수 경우에도
  //   불필요한 GET 이 나간다. `preview` 로 이관 필요 여부를 판정해 그때만 켠다.
  it('★ preview 가 아직 없으면(마운트 직후) 발행본을 조회하지 않는다(concern 1)', async () => {
    const { result } = renderMigrationWizard(KEY, null)

    // 비활성 쿼리라 절대 채워지지 않는다는 것을 짧은 대기로 확인한다.
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(result.current.published).toBeNull()
    expect(result.current.publishedFailed).toBe(false)
  })

  it('★ 사라지는 상태에 남은 이슈가 없으면(pendingIssueCounts 비어 있음) 발행본을 조회하지 않는다(concern 1)', async () => {
    const { result } = renderMigrationWizard(KEY, preview({ pendingIssueCounts: {} }))

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(result.current.published).toBeNull()
    expect(result.current.publishedFailed).toBe(false)
  })
})

describe('도착지 선택', () => {
  it('선택을 갱신한다 — 사라지는 상태마다 각각 담긴다', () => {
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onSelectionChange('done', 'open')
    })

    expect(result.current.selection).toEqual({ done: 'open' })
  })
})

describe('이관 시작', () => {
  it('202 를 받으면 그 id 로 폴링이 붙는다(F6 · J6)', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING', processedCount: 1 }) }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.operation?.id).toBe(BULK_ID)
    })
    expect(result.current.operation?.status).toBe('RUNNING')
    expect(result.current.startError).toBeNull()
  })

  it('COMPLETED 에 도달하면 진행률이 그 값으로 멈춘다', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({
          data: bulkOperationResponse({ status: 'COMPLETED', processedCount: 3, succeededCount: 3 }),
        }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.operation?.status).toBe('COMPLETED')
    })
    expect(result.current.operation?.failedCount).toBe(0)
  })

  it('접수 실패는 서버 코드를 한국어 문장으로 옮겨 startError 에 담는다', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_MIGRATION_INVALID_MAPPING', message: '옮길 매핑이 없다' } },
          { status: 400 },
        ),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([])
    })

    await waitFor(() => {
      expect(result.current.startError).not.toBeNull()
    })
    expect(result.current.startError).toContain('옮길 상태 지정이 올바르지 않습니다')
    // 접수 실패는 폴링을 붙이지 않는다 — 시작 안 한 작업의 진행률을 보여줄 수 없다.
    expect(result.current.operation).toBeNull()
  })
})

describe('★ 세션 리셋 — preview 가 바뀌면 이전 선택·이관 상태를 지운다', () => {
  it('새 preview(참조 변경)를 받으면 selection·operation 이 초기화된다', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING' }) }),
      ),
    )
    const first = preview()
    const { result, rerender } = renderHook(({ p }: { p: PublishPreview | null }) => useMigrationWizard(KEY, p), {
      wrapper: createWrapper(),
      initialProps: { p: first },
    })

    act(() => {
      result.current.onSelectionChange('done', 'open')
    })
    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })
    await waitFor(() => {
      expect(result.current.operation).not.toBeNull()
    })

    // 다이얼로그를 다시 연 것과 같은 축 — 새 preview 객체(참조가 다름)로 리렌더한다.
    rerender({ p: preview({ pendingIssueCounts: { done: 5 } }) })

    expect(result.current.selection).toEqual({})
    expect(result.current.operation).toBeNull()
  })
})

describe('진행률 URL 보관(G-2) — 새로고침·링크 공유에도 진행률이 살아남는다', () => {
  it('이관을 접수하면 bulkOperationId 가 URL 쿼리(?migration=)에 실린다', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING' }) }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())
    expect(new URLSearchParams(window.location.search).get('migration')).toBeNull()

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get('migration')).toBe(BULK_ID)
    })
  })

  it('URL 에 남은 migration 쿼리로 재진입하면 onStartMigration 없이도 그 id 로 폴링이 붙는다', async () => {
    window.history.replaceState(null, '', `/admin/workflows/${KEY}?migration=${BULK_ID}`)
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING', processedCount: 2 }) }),
      ),
    )

    const { result } = renderMigrationWizard(KEY, preview())

    await waitFor(() => {
      expect(result.current.operation?.id).toBe(BULK_ID)
    })
    expect(result.current.operation?.status).toBe('RUNNING')
    expect(result.current.starting).toBe(false)
  })

  it('종료 상태(COMPLETED)에 도달하면 URL 의 migration 쿼리를 정리한다', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({
          data: bulkOperationResponse({ status: 'COMPLETED', processedCount: 3, succeededCount: 3 }),
        }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.operation?.status).toBe('COMPLETED')
    })
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get('migration')).toBeNull()
    })
  })

  it('종료 상태(FAILED)에 도달해도 URL 의 migration 쿼리를 정리한다', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({
          data: bulkOperationResponse({ status: 'FAILED', processedCount: 3, failedCount: 3 }),
        }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.operation?.status).toBe('FAILED')
    })
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get('migration')).toBeNull()
    })
  })
})

describe('초안 폐기 차단(G-3) — 폴링 중에는 막고 종료 상태에서 다시 연다', () => {
  it('이관을 시작하기 전에는 폐기를 막지 않는다', () => {
    const { result } = renderMigrationWizard(KEY, preview())

    expect(result.current.discardDisabled).toBe(false)
  })

  it('폴링 중에는 초안 폐기가 비활성이다(discardDisabled=true)', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING' }) }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.discardDisabled).toBe(true)
    })
  })

  it('이관이 COMPLETED 로 끝나면 초안 폐기가 다시 활성화된다(discardDisabled=false)', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({
          data: bulkOperationResponse({ status: 'COMPLETED', processedCount: 3, succeededCount: 3 }),
        }),
      ),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.operation?.status).toBe('COMPLETED')
    })
    expect(result.current.discardDisabled).toBe(false)
  })
})

describe('폴링 실패 재시도(concern 2) — 5xx·네트워크만 재시도를 주고 4xx 는 주지 않는다', () => {
  it('5xx 로 폴링이 멈추면 재시도가 의미 있다(pollRetryable=true)', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => HttpResponse.json({}, { status: 500 })),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.pollFailed).toBe(true)
    })
    expect(result.current.pollRetryable).toBe(true)
  })

  it('4xx 로 폴링이 멈추면 재시도를 주지 않는다(pollRetryable=false — 같은 응답이 반복된다)', async () => {
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => HttpResponse.json({}, { status: 404 })),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.pollFailed).toBe(true)
    })
    expect(result.current.pollRetryable).toBe(false)
  })

  it('retryPoll 을 부르면 폴링을 다시 시도한다', async () => {
    let callCount = 0
    server.use(
      http.post(`/api/v1/workflows/${KEY}/publish/migrate`, () =>
        HttpResponse.json({ data: { bulkOperationId: BULK_ID } }, { status: 202 }),
      ),
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => {
        callCount += 1
        return callCount === 1
          ? HttpResponse.json({}, { status: 500 })
          : HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING' }) })
      }),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    act(() => {
      result.current.onStartMigration([{ fromStatusKey: 'done', toStatusKey: 'open' }])
    })

    await waitFor(() => {
      expect(result.current.pollFailed).toBe(true)
    })

    act(() => {
      result.current.retryPoll()
    })

    await waitFor(() => {
      expect(result.current.operation?.status).toBe('RUNNING')
    })
  })
})
