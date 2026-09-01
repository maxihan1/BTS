// 초안 로드·자동저장·앵커 고정 훅 테스트 — 디바운스 · 언마운트 flush · 앵커 불변
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useWorkflowDraft, DRAFT_AUTOSAVE_DELAY_MS } from '../use-workflow-draft'
import type { DraftDefinition } from '@/api/workflows-draft.types'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

const KEY = 'software-default'
const DRAFT_PATH = `/api/v1/workflows/${KEY}/draft`

const DEFINITION: DraftDefinition = {
  key: KEY,
  name: '워크플로우',
  description: null,
  states: [
    { key: 'open', name: '열림', category: 'TODO', displayOrder: 1 },
    { key: 'done', name: '완료', category: 'DONE', displayOrder: 2 },
  ],
  transitions: [
    { from: null, to: 'open', name: '이슈 생성', kind: 'INITIAL', validators: [], postActions: [] },
  ],
}

/** 자동저장이 실제로 보낸 본문들 */
let savedBodies: unknown[] = []

/** 서버 앵커. 테스트가 「남이 발행했다」를 흉내 낼 때 올린다. */
let serverVersion = 4

function stubDraftEndpoints(): void {
  server.use(
    http.get(DRAFT_PATH, () =>
      HttpResponse.json({
        data: { definition: DEFINITION, baseVersion: serverVersion, exists: true, canResetToDefault: true },
      }),
    ),
    http.put(DRAFT_PATH, async ({ request }) => {
      savedBodies.push(await request.json())
      return new HttpResponse(null, { status: 204 })
    }),
  )
}

beforeEach(() => {
  savedBodies = []
  serverVersion = 4
  stubDraftEndpoints()
  vi.useFakeTimers({ shouldAdvanceTime: true })
})

afterEach(() => {
  vi.useRealTimers()
})

/** 초안이 로드될 때까지 기다린다. */
async function renderLoaded() {
  const rendered = renderHook(() => useWorkflowDraft(KEY), { wrapper: createWrapper() })
  await waitFor(() => {
    expect(rendered.result.current.state.draft.states).toHaveLength(2)
  })
  return rendered
}

describe('초안 로드', () => {
  it('서버 정의와 앵커를 싣는다', async () => {
    const { result } = await renderLoaded()

    expect(result.current.state.baseVersion).toBe(4)
    expect(result.current.state.canResetToDefault).toBe(true)
    expect(result.current.saveState).toBe('idle')
  })
})

describe('자동저장 디바운스', () => {
  it('디바운스 경과 전에는 저장하지 않는다', async () => {
    const { result } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: '고친 이름' })
    })
    act(() => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS - 50)
    })

    expect(savedBodies).toHaveLength(0)
  })

  it('연속 편집 3회가 요청 1회로 합쳐진다', async () => {
    const { result } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: 'a' })
      result.current.dispatch({ type: 'setName', value: 'ab' })
      result.current.dispatch({ type: 'setName', value: 'abc' })
    })
    await act(async () => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS + 50)
    })

    await waitFor(() => {
      expect(savedBodies).toHaveLength(1)
    })
    expect(savedBodies[0]).toMatchObject({ definition: { name: 'abc' } })
  })

  it('편집이 없으면 열자마자 저장하지 않는다', async () => {
    // revision 이 0 인 채로 디바운스가 돌면 「열어만 보고 닫은」 워크플로우에 초안 행이 생긴다.
    await renderLoaded()

    await act(async () => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS * 3)
    })

    expect(savedBodies).toHaveLength(0)
  })

  it('저장 상태가 dirty → saving → saved 로 간다', async () => {
    const { result } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: '고친 이름' })
    })
    expect(result.current.saveState).toBe('dirty')

    await act(async () => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS + 50)
    })
    await waitFor(() => {
      expect(result.current.saveState).toBe('saved')
    })
  })
})

describe('★ 앵커 고정 (낙관적 락)', () => {
  it('자동저장을 여러 번 해도 앵커가 최초 값 그대로다', async () => {
    // ★ 앵커는 「편집기가 무엇을 보고 있었는가」다. 저장 응답이나 재조회로 갱신하면
    //   A 가 초안을 뜨고 → B 가 발행하고 → A 가 저장하는 순서에서 A 의 발행이 B 의 변경을
    //   조용히 덮어쓴다. 서버 앵커는 write-once 지만 화면이 값을 갈아 끼우면 의미가 사라진다.
    const { result } = await renderLoaded()

    for (const value of ['a', 'b', 'c']) {
      act(() => {
        result.current.dispatch({ type: 'setName', value })
      })
      await act(async () => {
        vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS + 50)
      })
    }

    await waitFor(() => {
      expect(savedBodies.length).toBeGreaterThanOrEqual(2)
    })
    // 보낸 모든 요청의 앵커가 같다.
    expect(savedBodies.every((b) => (b as { baseVersion: number }).baseVersion === 4)).toBe(true)
    expect(result.current.state.baseVersion).toBe(4)
  })

  it('그 사이 서버 쪽 판이 올라가도 앵커를 따라가지 않는다', async () => {
    const { result } = await renderLoaded()

    // 남이 발행해 서버 쪽 판이 올랐다. 재조회가 일어나도 앵커는 편집 시작 시점 값이어야 한다.
    serverVersion = 9

    act(() => {
      result.current.dispatch({ type: 'setName', value: '고친 이름' })
    })
    await act(async () => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS + 50)
    })

    await waitFor(() => {
      expect(savedBodies).toHaveLength(1)
    })
    expect((savedBodies[0] as { baseVersion: number }).baseVersion).toBe(4)
  })
})

describe('flush', () => {
  it('타이머를 기다리지 않고 즉시 저장한다', async () => {
    const { result } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: '고친 이름' })
    })
    await act(async () => {
      await result.current.flush()
    })

    expect(savedBodies).toHaveLength(1)
  })

  it('저장할 것이 없으면 요청을 보내지 않는다', async () => {
    const { result } = await renderLoaded()

    await act(async () => {
      await result.current.flush()
    })

    expect(savedBodies).toHaveLength(0)
  })

  it('flush 뒤 디바운스가 다시 돌아도 같은 편집을 두 번 보내지 않는다', async () => {
    const { result } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: '고친 이름' })
    })
    await act(async () => {
      await result.current.flush()
    })
    await act(async () => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS * 2)
    })

    expect(savedBodies).toHaveLength(1)
  })
})

describe('저장 뒤 캐시', () => {
  it('★ 재마운트해도 방금 저장한 편집이 남아 있다', async () => {
    // ★ 초안 쿼리는 `staleTime: Infinity` 라 재진입해도 서버를 다시 안 읽는다. 저장 성공 시
    //   캐시를 갱신하지 않으면 「목록으로 나갔다 돌아오면 편집이 사라지는」 자리가 생긴다 —
    //   서버에는 저장돼 있는데 화면만 옛 응답을 그리므로 **저장이 실패한 것처럼 보인다**.
    //   E2E 가 먼저 잡은 결함이고, 여기서 유닛으로도 못박는다.
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const first = renderHook(() => useWorkflowDraft(KEY), { wrapper })
    await waitFor(() => {
      expect(first.result.current.state.draft.states).toHaveLength(2)
    })
    act(() => {
      first.result.current.dispatch({ type: 'setName', value: '재진입해도 남을 이름' })
    })
    await act(async () => {
      await first.result.current.flush()
    })
    first.unmount()

    // 같은 QueryClient 로 다시 마운트 — 화면을 나갔다 돌아온 것과 같다.
    const second = renderHook(() => useWorkflowDraft(KEY), { wrapper })
    await waitFor(() => {
      expect(second.result.current.state.draft.name).toBe('재진입해도 남을 이름')
    })
    // 앵커도 그대로여야 한다 — 캐시 갱신이 앵커를 흔들면 락이 풀린다.
    expect(second.result.current.state.baseVersion).toBe(4)
  })
})

describe('언마운트', () => {
  it('미저장 편집을 flush 한다', async () => {
    // ★ 「목록으로」를 눌러 나가면 디바운스가 아직 안 돌았을 수 있다. 그대로 두면 방금 한
    //   편집이 사라지고, 사용자는 저장된 줄 안다.
    const { result, unmount } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: '나가기 직전 편집' })
    })
    unmount()

    await waitFor(() => {
      expect(savedBodies).toHaveLength(1)
    })
    expect(savedBodies[0]).toMatchObject({ definition: { name: '나가기 직전 편집' } })
  })
})

describe('저장 실패', () => {
  it('400 이면 error 상태이고 사유를 든다', async () => {
    server.use(
      http.put(DRAFT_PATH, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_INVALID_REQUEST', message: '시작 전환은 정확히 하나여야 한다' } },
          { status: 400 },
        ),
      ),
    )
    const { result } = await renderLoaded()

    act(() => {
      result.current.dispatch({ type: 'setName', value: '고친 이름' })
    })
    await act(async () => {
      vi.advanceTimersByTime(DRAFT_AUTOSAVE_DELAY_MS + 50)
    })

    await waitFor(() => {
      expect(result.current.saveState).toBe('error')
    })
    expect(result.current.saveError).toContain('시작 전환')
  })
})
