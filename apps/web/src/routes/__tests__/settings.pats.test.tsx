// PAT 설정 페이지 라우트 단위 테스트 — 조립 렌더·발급·토큰1회모달·storage금지·목록배지·폐기·에러매핑 (FR-API-04 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { SettingsPatsPage } from '@/routes/settings.pats'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock — 실제 DOM 없이 호출 여부로 검증 (admin.webhooks.test.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// 상태 저장 MSW 핸들러 — 발급→목록→폐기 흐름을 파일 내부 store로 시뮬레이션
// (msw-mutation-stateful-refetch 선례 — mutation 응답은 store를 갱신해 후속 GET에 반영,
//  invalidate가 실제로 refetch를 일으키는지 getCallCount로 관찰한다)
// ─────────────────────────────────────────────────────────────────────────────

interface PatFixture {
  id: string
  name: string
  scopes: string[]
  expiresAt: string | null
  lastUsedAt: string | null
  createdAt: string
}

const RAW_TOKEN = `pat_${'a'.repeat(48)}`

let store: PatFixture[] = []
let nextId = 1
let getCallCount = 0

function resetPatStore(): void {
  store = []
  nextId = 1
  getCallCount = 0
}

function seedPat(pat: PatFixture): void {
  store.push(pat)
}

const EXPIRED_PAT: PatFixture = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Expired token',
  scopes: ['read:issues'],
  expiresAt: '2020-01-01T00:00:00Z',
  lastUsedAt: null,
  createdAt: '2019-01-01T00:00:00Z',
}

const ACTIVE_PAT: PatFixture = {
  id: '22222222-2222-4222-8222-222222222222',
  name: 'Active token',
  scopes: ['read:issues', 'write:issues'],
  expiresAt: '2030-01-01T00:00:00Z',
  lastUsedAt: '2026-06-01T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
}

function patHandlers() {
  return [
    http.get('/api/v1/users/me/pats', () => {
      getCallCount += 1
      return HttpResponse.json({ pats: store })
    }),
    http.post('/api/v1/users/me/pats', async ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ error: 'session_management_requires_interactive_login' }, { status: 403 })
      }
      const body = (await request.json()) as { name?: string; scopes?: string[]; expiresInDays?: number }
      const id = `00000000-0000-4000-8000-${String(nextId).padStart(12, '0')}`
      nextId += 1
      const created: PatFixture = {
        id,
        name: body.name ?? '',
        scopes: body.scopes ?? [],
        expiresAt: '2026-08-01T00:00:00Z',
        lastUsedAt: null,
        createdAt: '2026-07-02T00:00:00Z',
      }
      store.push(created)
      return HttpResponse.json({ ...created, token: RAW_TOKEN }, { status: 201 })
    }),
    http.delete('/api/v1/users/me/pats/:id', ({ params, request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ error: 'session_management_requires_interactive_login' }, { status: 403 })
      }
      const id = params['id'] as string
      const idx = store.findIndex((p) => p.id === id)
      if (idx === -1) {
        return HttpResponse.json({ error: 'not_found' }, { status: 404 })
      }
      store.splice(idx, 1)
      return new HttpResponse(null, { status: 204 })
    }),
  ]
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage(): { user: ReturnType<typeof userEvent.setup> } & ReturnType<typeof render> {
  const user = userEvent.setup({ delay: null })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={client}>
      <SettingsPatsPage />
    </QueryClientProvider>,
  )
  return { user, ...result }
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증·MSW 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'valid-token',
    user: makeWhoami(),
  })
  document.cookie = 'XSRF-TOKEN=test-pat-xsrf; path=/'
  resetPatStore()
  server.use(...patHandlers())
  vi.clearAllMocks()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
  server.resetHandlers()
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 조립 렌더 + 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsPatsPage — 조립 렌더', () => {
  it('페이지 제목이 렌더된다', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Personal Access Token' })).toBeInTheDocument()
  })

  it('시드된 PAT 목록이 렌더된다', async () => {
    seedPat(ACTIVE_PAT)
    renderPage()
    expect(await screen.findByText(ACTIVE_PAT.name)).toBeInTheDocument()
  })

  it('만료일이 과거인 PAT는 "만료됨" 배지를 표시한다', async () => {
    seedPat(EXPIRED_PAT)
    renderPage()
    const row = (await screen.findByText(EXPIRED_PAT.name)).closest('li')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('만료됨')).toBeInTheDocument()
  })

  it('lastUsedAt이 null인 PAT는 "미사용"을 표시한다', async () => {
    seedPat(EXPIRED_PAT)
    renderPage()
    const row = (await screen.findByText(EXPIRED_PAT.name)).closest('li')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('미사용')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 발급 + 토큰 1회 모달
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsPatsPage — 발급 + 토큰 1회 모달', () => {
  it('발급 성공 시 목록이 갱신되고(invalidate refetch) 토큰 모달에 raw token이 노출된다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })

    const callsBeforeSubmit = getCallCount

    await user.type(screen.getByLabelText('이름'), 'CI deploy token')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(await screen.findByText(RAW_TOKEN)).toBeInTheDocument()

    // 목록에도 새 항목이 반영된다
    await waitFor(() => {
      expect(screen.getByText('CI deploy token')).toBeInTheDocument()
    })

    // setQueryData 부분응답 캐시 패치가 아니라 invalidateQueries → 실제 refetch로 반영되어야 한다.
    expect(getCallCount).toBeGreaterThan(callsBeforeSubmit)

    // 새로 생성된 행은 서버가 내려준 lastUsedAt=null을 반영해 "미사용"을 표시한다 —
    // 발급 응답(issued)에는 lastUsedAt 필드 자체가 없으므로, 이 값이 정상 표시되려면
    // 실제 GET refetch를 거쳐야 한다(캐시를 issued로 덮어썼다면 undefined가 되어 깨진다).
    const newRow = screen.getByText('CI deploy token').closest('li')
    expect(newRow).not.toBeNull()
    expect(within(newRow as HTMLElement).getByText('미사용')).toBeInTheDocument()
  })

  it('토큰 모달을 닫으면 화면에서 raw token 텍스트가 사라진다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })

    await user.type(screen.getByLabelText('이름'), 'CI deploy token')
    await user.click(screen.getByRole('button', { name: '발급' }))
    await screen.findByText(RAW_TOKEN)

    await user.click(screen.getByRole('button', { name: '닫기' }))

    await waitFor(() => {
      expect(screen.queryByText(RAW_TOKEN)).not.toBeInTheDocument()
    })
  })

  it('발급 흐름 전체에서 raw token 값이 localStorage/sessionStorage에 저장되지 않는다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')

    await user.type(screen.getByLabelText('이름'), 'CI deploy token')
    await user.click(screen.getByRole('button', { name: '발급' }))
    await screen.findByText(RAW_TOKEN)

    await user.click(screen.getByRole('button', { name: '닫기' }))
    await waitFor(() => {
      expect(screen.queryByText(RAW_TOKEN)).not.toBeInTheDocument()
    })

    const leaked = setItemSpy.mock.calls.some(
      ([, value]) => typeof value === 'string' && value.includes(RAW_TOKEN),
    )
    expect(leaked).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 폐기
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsPatsPage — 폐기', () => {
  it('확인 후 폐기하면 목록에서 제거된다', async () => {
    seedPat(ACTIVE_PAT)
    const { user } = renderPage()
    await screen.findByText(ACTIVE_PAT.name)

    await user.click(screen.getByRole('button', { name: `${ACTIVE_PAT.name} 폐기` }))
    await user.click(screen.getByRole('button', { name: `${ACTIVE_PAT.name} 폐기 확인` }))

    await waitFor(() => {
      expect(screen.queryByText(ACTIVE_PAT.name)).not.toBeInTheDocument()
    })
  })

  it('폐기 실패(404 not_found) 시 toast.error가 호출된다', async () => {
    seedPat(ACTIVE_PAT)
    server.use(
      http.delete('/api/v1/users/me/pats/:id', () =>
        HttpResponse.json({ error: 'not_found' }, { status: 404 }),
      ),
    )
    const { user } = renderPage()
    await screen.findByText(ACTIVE_PAT.name)

    await user.click(screen.getByRole('button', { name: `${ACTIVE_PAT.name} 폐기` }))
    await user.click(screen.getByRole('button', { name: `${ACTIVE_PAT.name} 폐기 확인` }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 발급 에러 코드 → 한국어 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsPatsPage — 발급 에러 매핑', () => {
  it('invalid_name(400) → 매핑된 메시지가 표시된다', async () => {
    server.use(
      http.post('/api/v1/users/me/pats', () =>
        HttpResponse.json({ error: 'invalid_name' }, { status: 400 }),
      ),
    )
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })
    await user.type(screen.getByLabelText('이름'), 'anything')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(await screen.findByText('이름은 공백일 수 없습니다.')).toBeInTheDocument()
  })

  it('invalid_scope(400) → 매핑된 메시지가 표시된다', async () => {
    server.use(
      http.post('/api/v1/users/me/pats', () =>
        HttpResponse.json({ error: 'invalid_scope' }, { status: 400 }),
      ),
    )
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })
    await user.type(screen.getByLabelText('이름'), 'anything')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(await screen.findByText(/scope를 확인해 주세요/)).toBeInTheDocument()
  })

  it('invalid_expiry(400) → 매핑된 메시지가 표시된다', async () => {
    server.use(
      http.post('/api/v1/users/me/pats', () =>
        HttpResponse.json({ error: 'invalid_expiry' }, { status: 400 }),
      ),
    )
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })
    await user.type(screen.getByLabelText('이름'), 'anything')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(await screen.findByText('만료 기간이 올바르지 않습니다.')).toBeInTheDocument()
  })

  it('quota_exceeded(403) → 매핑된 메시지가 표시된다', async () => {
    server.use(
      http.post('/api/v1/users/me/pats', () =>
        HttpResponse.json({ error: 'quota_exceeded' }, { status: 403 }),
      ),
    )
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Personal Access Token' })
    await user.type(screen.getByLabelText('이름'), 'anything')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(await screen.findByText(/발급 가능한 PAT 개수를 초과했습니다/)).toBeInTheDocument()
  })
})
