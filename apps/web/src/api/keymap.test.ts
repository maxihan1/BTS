// identity-access 사용자 단축키 커스터마이즈 API client 단위 테스트 — MSW + Zod 파싱 검증 (FR-PF-03 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { server } from '@/test/server'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import {
  keymapBindingSchema,
  keymapResponseSchema,
  keymapConflictSchema,
  keymapConflictErrorSchema,
  getKeymap,
  patchKeymap,
  useKeymap,
  useUpdateKeymap,
  KEYMAP_QUERY_KEY,
} from './keymap'
import { ApiError } from './client'
import { keymapHandlers, resetKeymapStore } from '@/mocks/keymap-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — PATCH X-XSRF-TOKEN 검증용 (preferences.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — KeymapResponse (백엔드 KeymapResponse DTO 1:1, task 프롬프트 실제 JSON 계약)
// ─────────────────────────────────────────────────────────────────────────────
const KEYMAP_FIXTURE_DEFAULT = {
  bindings: [
    { action: 'help', keyCombo: '?', trigger: 'single', customized: false },
    { action: 'create-issue', keyCombo: 'c', trigger: 'single', customized: false },
    { action: 'search', keyCombo: '/', trigger: 'single', customized: false },
    { action: 'goto-my-issues', keyCombo: 'g i', trigger: 'leader', customized: false },
    { action: 'goto-dashboard', keyCombo: 'g d', trigger: 'leader', customized: false },
  ],
}

const KEYMAP_FIXTURE_CUSTOM = {
  bindings: [
    { action: 'help', keyCombo: '?', trigger: 'single', customized: false },
    { action: 'create-issue', keyCombo: 'c', trigger: 'single', customized: false },
    { action: 'search', keyCombo: 'k', trigger: 'single', customized: true },
    { action: 'goto-my-issues', keyCombo: 'g i', trigger: 'leader', customized: false },
    { action: 'goto-dashboard', keyCombo: 'g d', trigger: 'leader', customized: false },
  ],
}

const KEYMAP_CONFLICT_ERROR_FIXTURE = {
  code: 'KEYMAP_CONFLICT',
  message: '겹치는 단축키가 있습니다.',
  conflicts: [{ type: 'duplicate', actions: ['create-issue', 'search'], keyCombo: 'c' }],
}

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-S. keymapBindingSchema / keymapResponseSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('keymapResponseSchema', () => {
  it('T-KM-S-1: 기본값 5종(customized=false)을 파싱한다', () => {
    const result = keymapResponseSchema.parse(KEYMAP_FIXTURE_DEFAULT)
    expect(result.bindings).toHaveLength(5)
    expect(result.bindings[0]).toEqual({
      action: 'help',
      keyCombo: '?',
      trigger: 'single',
      customized: false,
    })
  })

  it('T-KM-S-2: 재배치된 커스텀 조합(search→k, customized=true)도 파싱한다', () => {
    const result = keymapResponseSchema.parse(KEYMAP_FIXTURE_CUSTOM)
    const search = result.bindings.find((b) => b.action === 'search')
    expect(search).toEqual({ action: 'search', keyCombo: 'k', trigger: 'single', customized: true })
  })

  it.each(['single', 'leader'])('T-KM-S-3: trigger=%s → safeParse success', (trigger) => {
    const result = keymapBindingSchema.safeParse({
      action: 'help',
      keyCombo: '?',
      trigger,
      customized: false,
    })
    expect(result.success).toBe(true)
  })

  it.each(['help', 'create-issue', 'search', 'goto-my-issues', 'goto-dashboard'])(
    'T-KM-S-4: action=%s → safeParse success',
    (action) => {
      const result = keymapBindingSchema.safeParse({
        action,
        keyCombo: 'x',
        trigger: 'single',
        customized: false,
      })
      expect(result.success).toBe(true)
    },
  )

  it('T-KM-S-5: trigger가 허용 외 값(double)이면 safeParse fail', () => {
    const result = keymapBindingSchema.safeParse({
      action: 'help',
      keyCombo: '?',
      trigger: 'double',
      customized: false,
    })
    expect(result.success).toBe(false)
  })

  it('T-KM-S-6: action이 화이트리스트 밖 값이면 safeParse fail', () => {
    const result = keymapBindingSchema.safeParse({
      action: 'unknown-action',
      keyCombo: '?',
      trigger: 'single',
      customized: false,
    })
    expect(result.success).toBe(false)
  })

  it('T-KM-S-7: customized 필드 누락 시 safeParse fail', () => {
    const result = keymapBindingSchema.safeParse({ action: 'help', keyCombo: '?', trigger: 'single' })
    expect(result.success).toBe(false)
  })

  it('T-KM-S-8: bindings가 4종만 있어도 스키마 자체는 통과한다(완비 검증은 서버 책임)', () => {
    const result = keymapResponseSchema.safeParse({ bindings: KEYMAP_FIXTURE_DEFAULT.bindings.slice(0, 4) })
    expect(result.success).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-C. keymapConflictSchema / keymapConflictErrorSchema — 409 에러 바디 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('keymapConflictSchema', () => {
  it('T-KM-C-1: type=duplicate는 keyCombo 문자열을 포함한다', () => {
    const result = keymapConflictSchema.safeParse({
      type: 'duplicate',
      actions: ['create-issue', 'search'],
      keyCombo: 'c',
    })
    expect(result.success).toBe(true)
  })

  it.each(['leaderPrefix', 'deadLeader'])(
    'T-KM-C-2: type=%s는 keyCombo가 null이어도 통과한다(필드는 present, 값만 null)',
    (type) => {
      const result = keymapConflictSchema.safeParse({ type, actions: ['goto-my-issues'], keyCombo: null })
      expect(result.success).toBe(true)
    },
  )

  it('T-KM-C-3: keyCombo 필드 자체가 없으면(undefined) safeParse fail — nullable이지 optional이 아니다', () => {
    const result = keymapConflictSchema.safeParse({ type: 'deadLeader', actions: ['goto-my-issues'] })
    expect(result.success).toBe(false)
  })

  it('T-KM-C-4: type이 허용 외 값이면 safeParse fail', () => {
    const result = keymapConflictSchema.safeParse({ type: 'unknown', actions: [], keyCombo: null })
    expect(result.success).toBe(false)
  })
})

describe('keymapConflictErrorSchema', () => {
  it('T-KM-C-5: 409 에러 바디 전체(code/message/conflicts)를 파싱한다', () => {
    const result = keymapConflictErrorSchema.parse(KEYMAP_CONFLICT_ERROR_FIXTURE)
    expect(result.code).toBe('KEYMAP_CONFLICT')
    expect(result.conflicts).toHaveLength(1)
    expect(result.conflicts[0]).toEqual({
      type: 'duplicate',
      actions: ['create-issue', 'search'],
      keyCombo: 'c',
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-1. getKeymap — GET /api/v1/users/me/keymap
// ─────────────────────────────────────────────────────────────────────────────
describe('getKeymap', () => {
  it('T-KM-1-1: 200 응답을 KeymapResponse로 파싱해 반환한다', async () => {
    server.use(http.get('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_FIXTURE_DEFAULT)))
    const result = await getKeymap()
    expect(result.bindings).toHaveLength(5)
    expect(result.bindings.map((b) => b.action)).toEqual([
      'help',
      'create-issue',
      'search',
      'goto-my-issues',
      'goto-dashboard',
    ])
  })

  it('T-KM-1-2: 재배치된 응답도 파싱해 반환한다', async () => {
    server.use(http.get('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_FIXTURE_CUSTOM)))
    const result = await getKeymap()
    expect(result.bindings.find((b) => b.action === 'search')?.keyCombo).toBe('k')
  })

  it('T-KM-1-3: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/users/me/keymap', () => HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 })),
    )
    await expect(getKeymap()).rejects.toBeInstanceOf(ApiError)
    await expect(getKeymap()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-2. patchKeymap — PATCH /api/v1/users/me/keymap (replace-all, action 5종 완비)
// ─────────────────────────────────────────────────────────────────────────────
describe('patchKeymap', () => {
  it('T-KM-2-1: PATCH 후 갱신된 KeymapResponse를 반환한다', async () => {
    server.use(http.patch('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_FIXTURE_CUSTOM)))
    const result = await patchKeymap({
      bindings: [
        { action: 'help', keyCombo: '?' },
        { action: 'create-issue', keyCombo: 'c' },
        { action: 'search', keyCombo: 'k' },
        { action: 'goto-my-issues', keyCombo: 'g i' },
        { action: 'goto-dashboard', keyCombo: 'g d' },
      ],
    })
    expect(result.bindings.find((b) => b.action === 'search')).toEqual({
      action: 'search',
      keyCombo: 'k',
      trigger: 'single',
      customized: true,
    })
  })

  it('T-KM-2-2: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/users/me/keymap', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(KEYMAP_FIXTURE_DEFAULT)
      }),
    )
    await patchKeymap({ bindings: [{ action: 'help', keyCombo: '?' }] })
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-KM-2-3: replace-all — action 5종 전체가 {action, keyCombo}만 담아 요청 바디에 실린다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/keymap', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(KEYMAP_FIXTURE_DEFAULT)
      }),
    )
    const bindings = [
      { action: 'help' as const, keyCombo: '?' },
      { action: 'create-issue' as const, keyCombo: 'c' },
      { action: 'search' as const, keyCombo: '/' },
      { action: 'goto-my-issues' as const, keyCombo: 'g i' },
      { action: 'goto-dashboard' as const, keyCombo: 'g d' },
    ]
    await patchKeymap({ bindings })
    expect(capturedBody).toEqual({ bindings })
  })

  it('T-KM-2-4: 400 화이트리스트/형식 위반 → ApiError(400) throw', async () => {
    server.use(
      http.patch('/api/v1/users/me/keymap', () =>
        HttpResponse.json(
          { code: 'KEYMAP_VALIDATION_FAILED', message: '유효하지 않은 단축키 설정입니다.' },
          { status: 400 },
        ),
      ),
    )
    let thrown: unknown
    try {
      await patchKeymap({ bindings: [{ action: 'help', keyCombo: '?' }] })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
    expect((thrown.body as { code?: string } | null)?.code).toBe('KEYMAP_VALIDATION_FAILED')
  })

  it('T-KM-2-5: 409 충돌 → ApiError(409) throw, body는 keymapConflictErrorSchema로 파싱 가능하다', async () => {
    server.use(
      http.patch('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_CONFLICT_ERROR_FIXTURE, { status: 409 })),
    )
    let thrown: unknown
    try {
      await patchKeymap({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: 'c' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(409)
    const parsed = keymapConflictErrorSchema.safeParse(thrown.body)
    expect(parsed.success).toBe(true)
    if (parsed.success) {
      expect(parsed.data.conflicts[0]?.type).toBe('duplicate')
      expect(parsed.data.conflicts[0]?.actions).toEqual(['create-issue', 'search'])
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — react-query wrapper (useStatus.test.ts/issue-graph.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────
function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

const ALICE_TOKEN = mockAccessToken('alice')

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-Q. useKeymap — GET 조회 쿼리
// ─────────────────────────────────────────────────────────────────────────────
describe('useKeymap', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_FIXTURE_DEFAULT)))
  })

  it('T-KM-Q-1: 기본값(옵션 없음)이면 즉시 조회한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useKeymap(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.bindings).toHaveLength(5)
  })

  it('T-KM-Q-2: 쿼리 키는 ["keymap", "me"]로 등록된다', async () => {
    const { queryClient, wrapper } = createWrapper()
    renderHook(() => useKeymap(), { wrapper })

    await waitFor(() => expect(queryClient.getQueryState(['keymap', 'me'])).not.toBeUndefined())
    expect(KEYMAP_QUERY_KEY).toEqual(['keymap', 'me'])
  })

  it('T-KM-Q-3: enabled=false이면 쿼리를 실행하지 않는다(fetchStatus=idle)', () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useKeymap({ enabled: false }), { wrapper })

    expect(result.current.data).toBeUndefined()
    expect(result.current.fetchStatus).toBe('idle')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-M. useUpdateKeymap — PATCH mutation (invalidate-only)
// ─────────────────────────────────────────────────────────────────────────────
describe('useUpdateKeymap', () => {
  it('T-KM-M-1: 성공 시 keymap 쿼리를 invalidate한다', async () => {
    server.use(http.patch('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_FIXTURE_CUSTOM)))
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateKeymap(), { wrapper })

    await act(async () => {
      result.current.mutate({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: 'k' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: KEYMAP_QUERY_KEY }))
  })

  it('T-KM-M-2: 실패(409 충돌) 시 isError가 true가 된다', async () => {
    server.use(
      http.patch('/api/v1/users/me/keymap', () => HttpResponse.json(KEYMAP_CONFLICT_ERROR_FIXTURE, { status: 409 })),
    )
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUpdateKeymap(), { wrapper })

    await act(async () => {
      result.current.mutate({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: 'c' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
      await waitFor(() => expect(result.current.isError).toBe(true))
    })

    expect(result.current.error?.status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-KM-H. mocks/keymap-handlers.ts 통합 — stateful override 저장 후 GET에 반영
// (msw-mutation-stateful-refetch 선례 — mutation 성공 후 재조회하면 갱신값이 영속된다)
// ─────────────────────────────────────────────────────────────────────────────
describe('keymapHandlers(stateful)', () => {
  beforeEach(() => {
    resetKeymapStore()
    server.use(...keymapHandlers)
    useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
  })

  afterEach(() => {
    useAuthStore.getState().clearSession()
  })

  it('T-KM-H-1: override 없으면 GET은 기본값 5종(customized=false)을 반환한다', async () => {
    const result = await getKeymap()
    expect(result.bindings.every((b) => !b.customized)).toBe(true)
    expect(result.bindings.find((b) => b.action === 'search')?.keyCombo).toBe('/')
  })

  it('T-KM-H-2: PATCH로 재배치하면 이후 GET에 override가 반영된다(stateful 영속)', async () => {
    await patchKeymap({
      bindings: [
        { action: 'help', keyCombo: '?' },
        { action: 'create-issue', keyCombo: 'c' },
        { action: 'search', keyCombo: 'k' },
        { action: 'goto-my-issues', keyCombo: 'g i' },
        { action: 'goto-dashboard', keyCombo: 'g d' },
      ],
    })
    const result = await getKeymap()
    const search = result.bindings.find((b) => b.action === 'search')
    expect(search).toEqual({ action: 'search', keyCombo: 'k', trigger: 'single', customized: true })
    // 재배치하지 않은 나머지는 customized=false 그대로 유지
    expect(result.bindings.find((b) => b.action === 'help')?.customized).toBe(false)
  })

  it('T-KM-H-3: 완전중복(create-issue/search 둘 다 c)이면 409 KEYMAP_CONFLICT를 던진다', async () => {
    let thrown: unknown
    try {
      await patchKeymap({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: 'c' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(409)
    const parsed = keymapConflictErrorSchema.safeParse(thrown.body)
    expect(parsed.success).toBe(true)
    if (parsed.success) {
      expect(parsed.data.conflicts).toContainEqual({
        type: 'duplicate',
        actions: ['create-issue', 'search'],
        keyCombo: 'c',
      })
    }
  })

  it('T-KM-H-4: action 5종 미완비(4종만 전송)면 400 KEYMAP_VALIDATION_FAILED를 던진다', async () => {
    let thrown: unknown
    try {
      await patchKeymap({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: '/' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
        ],
      })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
    expect((thrown.body as { code?: string } | null)?.code).toBe('KEYMAP_VALIDATION_FAILED')
  })

  it('T-KM-H-5: 빈 값(공백)이면 400 KEYMAP_VALIDATION_FAILED를 던진다', async () => {
    let thrown: unknown
    try {
      await patchKeymap({
        bindings: [
          { action: 'help', keyCombo: '' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: '/' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
  })
})
