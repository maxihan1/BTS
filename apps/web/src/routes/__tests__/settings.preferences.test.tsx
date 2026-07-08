// 사용자 환경설정 페이지 라우트 단위 테스트 — 렌더 + requireAuth 가드 + 저장 성공 시 whoami 갱신 (FR-PF-01 Task 7)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { makeWhoami, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { requireAuth } from '@/auth/routeGuard'

// ─────────────────────────────────────────────────────────────────────────────
// shadcn Select mock — jsdom에서 Radix Select Portal의 pointer-capture 미지원
// 문제를 우회한다(ResolutionModal.test.tsx / PreferencesForm.test.tsx 선례와 동일한 mock).
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const React = (await vi.importActual<typeof import('react')>('react'))

  interface SelectContextValue {
    value: string
    onValueChange: (v: string) => void
  }
  const SelectContext = React.createContext<SelectContextValue>({ value: '', onValueChange: () => undefined })

  return {
    Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) =>
      React.createElement(SelectContext.Provider, { value: { value, onValueChange } }, children),
    SelectTrigger: ({ children, 'aria-label': ariaLabel, id }: { children: React.ReactNode; 'aria-label'?: string; id?: string }) => {
      const ctx = React.useContext(SelectContext)
      return React.createElement('button', { role: 'combobox', 'aria-label': ariaLabel, id, onClick: () => ctx.onValueChange('__open__') }, children)
    },
    SelectValue: ({ placeholder }: { placeholder?: string }) => {
      const ctx = React.useContext(SelectContext)
      return React.createElement('span', null, ctx.value || placeholder || '')
    },
    SelectContent: ({ children }: { children: React.ReactNode }) => {
      const ctx = React.useContext(SelectContext)
      return React.createElement('ul', { role: 'listbox' },
        React.Children.map(children, (child) => {
          if (!React.isValidElement(child)) return child
          const props = child.props as unknown as { value?: string; children?: React.ReactNode }
          return React.createElement('li', {
            role: 'option',
            key: props.value,
            onClick: () => { if (props.value !== undefined) ctx.onValueChange(props.value) },
            'data-value': props.value,
          }, props.children)
        }),
      )
    },
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) =>
      React.createElement('span', { 'data-value': value }, children),
  }
})

// 대상 import — mock 이후 (vi.mock hoisting)
import { PreferencesSettingsPage } from '@/routes/settings.preferences'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

// redirect() 반환 타입 — Response & { options: { to, search, ... } }
interface RedirectResponse extends Response {
  options: {
    to: string
    search?: Record<string, string>
  }
}

const makeCtx = (pathname: string): MinimalBeforeLoadContext => ({
  location: { href: pathname, pathname },
})

function renderPage(): ReturnType<typeof render> {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <PreferencesSettingsPage />
    </QueryClientProvider>,
  )
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  useAuthStore.getState().setSession({
    accessToken: ALICE_TOKEN,
    user: makeWhoami({ theme: 'system', locale: 'ko', dateFormat: 'iso' }),
  })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// PreferencesSettingsPage 렌더 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesSettingsPage', () => {
  it('T1: 제목 "환경 설정"이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '환경 설정', level: 1 })).toBeInTheDocument()
  })

  it('T2: PreferencesForm의 테마 셀렉터가 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('combobox', { name: '테마' })).toBeInTheDocument()
  })

  it('T3: 페이지 설명 문구가 렌더된다', () => {
    renderPage()
    expect(screen.getByText('테마·언어·날짜 표시 형식을 설정하세요.')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 저장 성공 시 whoami 재조회 + authStore(setUser) 갱신
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesSettingsPage — 저장 성공 시 whoami 갱신', () => {
  it('T4: 테마 저장 성공 시 whoami가 재조회되어 authStore.user.theme이 갱신된다', async () => {
    server.use(
      http.patch('/api/v1/users/me/preferences', () =>
        HttpResponse.json({ theme: 'dark', locale: 'ko', dateFormat: 'iso' }),
      ),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ theme: 'dark' }))),
    )
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('option', { name: '다크' }))

    await waitFor(() => {
      expect(useAuthStore.getState().user?.theme).toBe('dark')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireAuth 가드 — /settings/preferences 라우트 레벨 보호
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuth 가드 — /settings/preferences', () => {
  /**
   * T5. 미인증 상태에서 requireAuth 호출 → /login 리다이렉트.
   * router.ts의 beforeLoad: requireAuth 등록과 동일한 동작을 단위 검증한다.
   */
  it('T5: 미인증 상태에서 requireAuth → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null })

    let thrown: unknown
    try {
      requireAuth(makeCtx('/settings/preferences'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  /**
   * T6. 인증 상태에서 requireAuth 호출 → throw 없음 (통과).
   */
  it('T6: 인증 상태에서 requireAuth → throw 없음', () => {
    useAuthStore.setState({ accessToken: 'valid-token' })
    expect(() => requireAuth(makeCtx('/settings/preferences'))).not.toThrow()
  })
})
