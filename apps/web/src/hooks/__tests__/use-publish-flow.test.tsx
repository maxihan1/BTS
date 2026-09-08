// 상태 이관 마법사 배선(useMigrationWizard) 테스트 — 발행본 조회 · 선택 · 이관 접수 · 폴링 리셋
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { MAX_ERROR_RETRIES, POLL_INTERVAL_MS } from '@/hooks/use-bulk-operation'
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
              projectId: null,
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

afterEach(() => {
  // fake timer 를 쓴 테스트가 다음 테스트의 waitFor 를 얼어붙게 하지 않도록 되돌린다.
  vi.useRealTimers()
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
    // 진행률 파생값도 함께 온다 — 화면이 같은 두 숫자를 다시 나누지 않게 하는 값이다.
    expect(result.current.progressRatio).toBe(1)
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
  /** fake timer 위에서 폴링 사이클 `ticks` 번을 진행시킨다 — 재시도 간격도 POLL_INTERVAL_MS 다. */
  async function advancePolls(ticks: number): Promise<void> {
    for (let tick = 0; tick < ticks; tick += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
      })
    }
  }

  /** 공유 링크로 재진입한 것과 같은 축 — URL 에 이관 id 를 심어 둔다(G-2). */
  function enterWithMigrationUrl(): void {
    window.history.replaceState(null, '', `/admin/workflows/${KEY}?migration=${BULK_ID}`)
  }

  // ★ `pollFailed` 는 「멈췄다」는 뜻이지 「한 번 실패했다」가 아니다. 훅이 알아서 재시도할
  //   상황에 에러 배너와 「다시 시도」 버튼을 띄우면 사용자가 멀쩡한 이관을 중단한다.
  it('★ 5xx 가 한 번 스치는 것만으로는 멈추지 않는다 — 배너도 폐기 해제도 없다', async () => {
    vi.useFakeTimers()
    enterWithMigrationUrl()
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => {
        callCount += 1
        return callCount === 1
          ? HttpResponse.json({}, { status: 500 })
          : HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING' }) })
      }),
    )
    const { result } = renderMigrationWizard(KEY, preview())
    await advancePolls(2)

    expect(result.current.pollFailed).toBe(false)
    expect(result.current.operation?.status).toBe('RUNNING')
    // 폴링이 살아 있으므로 폐기 잠금(G-3)은 그대로다.
    expect(result.current.discardDisabled).toBe(true)
  })

  it('5xx 로 재시도 예산까지 쓰면 멈추고, 재시도가 의미 있다(pollRetryable=true)', async () => {
    vi.useFakeTimers()
    enterWithMigrationUrl()
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => HttpResponse.json({}, { status: 500 })),
    )
    const { result } = renderMigrationWizard(KEY, preview())
    await advancePolls(MAX_ERROR_RETRIES + 2)

    expect(result.current.pollFailed).toBe(true)
    expect(result.current.pollRetryable).toBe(true)
    // 멈춘 폴링이 편집기를 잠그면 안 된다 — 아래 4xx 판정과 같은 이유다.
    expect(result.current.discardDisabled).toBe(false)
  })

  // ★ 아래 4xx 판정(「죽은 id 는 URL 에서 치운다」)과 **짝**이다. 정지를 한 덩어리로 다루면
  //   둘 중 하나는 반드시 틀린다 — 4xx 를 남기면 새로고침이 막다른 상태로 되돌아오고, 5xx 를
  //   지우면 서버에서 계속 도는 이관을 주소에서까지 잃는다.
  it('★ 5xx 로 멈춰도 URL 의 migration id 는 남는다 — 이관은 서버에서 계속 돌 수 있다', async () => {
    vi.useFakeTimers()
    enterWithMigrationUrl()
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => HttpResponse.json({}, { status: 500 })),
    )
    const { result } = renderMigrationWizard(KEY, preview())
    await advancePolls(MAX_ERROR_RETRIES + 2)

    expect(result.current.pollFailed).toBe(true)
    // 남아 있어야 새로고침한 화면이 새 쿼리로 그 이관을 다시 붙들 수 있다(G-2).
    expect(new URLSearchParams(window.location.search).get('migration')).toBe(BULK_ID)
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
    // ★ BLOCKER 회귀 방지 — 폴링이 죽으면 상태를 영영 못 받으므로 종료 판정이 계속 거짓이다.
    //   그걸 그대로 폐기 잠금에 쓰면 소비처가 기본값 복원·초안 폐기·발행을 전부 비활성으로
    //   만들고, 409 충돌 상태의 유일한 출구인 초안 폐기까지 막힌다.
    expect(result.current.discardDisabled).toBe(false)
    // 죽은 id 가 주소에 남으면 새로고침이 같은 막다른 상태로 되돌아온다(G-2).
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get('migration')).toBeNull()
    })
  })

  it('★ 권한 없는 동료가 공유 링크를 열면(403) 폐기가 잠기지 않는다', async () => {
    enterWithMigrationUrl()
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => HttpResponse.json({}, { status: 403 })),
    )
    const { result } = renderMigrationWizard(KEY, preview())

    await waitFor(() => {
      expect(result.current.pollFailed).toBe(true)
    })
    expect(result.current.pollRetryable).toBe(false)
    expect(result.current.discardDisabled).toBe(false)
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get('migration')).toBeNull()
    })
  })

  it('retryPoll 을 부르면 폴링을 다시 시도한다', async () => {
    vi.useFakeTimers()
    enterWithMigrationUrl()
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => {
        callCount += 1
        // 재시도 예산(최초 1 + MAX_ERROR_RETRIES)을 다 쓰게 한 뒤에야 성공을 돌려준다.
        return callCount <= MAX_ERROR_RETRIES + 1
          ? HttpResponse.json({}, { status: 500 })
          : HttpResponse.json({ data: bulkOperationResponse({ status: 'RUNNING' }) })
      }),
    )
    const { result } = renderMigrationWizard(KEY, preview())
    await advancePolls(MAX_ERROR_RETRIES + 2)
    expect(result.current.pollFailed).toBe(true)

    await act(async () => {
      result.current.retryPoll()
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    })

    expect(result.current.operation?.status).toBe('RUNNING')
  })
})
