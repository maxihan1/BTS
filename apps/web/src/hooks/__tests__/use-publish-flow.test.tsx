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
