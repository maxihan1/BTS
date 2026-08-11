// PreferencesForm 컴포넌트 테스트 — 셀렉터 즉시 PATCH 저장·라이브 프리뷰·언어/시작 페이지 안내 문구 (FR-PF-01 Task 7, FR-PF-02 Task 6)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { server } from '@/test/server'
import { makeWhoami, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'

// ─────────────────────────────────────────────────────────────────────────────
// shadcn Select mock — jsdom에서 Radix Select Portal의 pointer-capture 미지원
// 문제를 우회한다(ResolutionModal.test.tsx 선례와 동일한 최소 mock).
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
import { PreferencesForm } from './PreferencesForm'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
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

function renderForm() {
  const { wrapper } = createWrapper()
  return render(<PreferencesForm />, { wrapper })
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
// 테마 — 변경 즉시 PATCH + whoami 재조회
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesForm — 테마', () => {
  it('T1: 다크 선택 시 PATCH가 {theme: "dark"}로 호출된다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/preferences', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ theme: 'dark', locale: 'ko', dateFormat: 'iso', startPage: 'dashboards' })
      }),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ theme: 'dark' }))),
    )
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('option', { name: '다크' }))

    await waitFor(() => {
      expect(capturedBody).toEqual({ theme: 'dark' })
    })
  })

  it('T2: 저장 성공 후 whoami가 재조회되어 authStore.user.theme이 갱신된다', async () => {
    server.use(
      http.patch('/api/v1/users/me/preferences', () =>
        HttpResponse.json({ theme: 'dark', locale: 'ko', dateFormat: 'iso', startPage: 'dashboards' }),
      ),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ theme: 'dark' }))),
    )
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('option', { name: '다크' }))

    await waitFor(() => {
      expect(useAuthStore.getState().user?.theme).toBe('dark')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 표시 형식 — 라이브 프리뷰(네트워크 응답 무관 즉시 갱신)
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesForm — 날짜 표시 형식 라이브 프리뷰', () => {
  it('T3: 초기 프리뷰는 기본 iso 프리셋(2026-07-07)이다', () => {
    renderForm()
    expect(screen.getByText('미리보기: 2026-07-07')).toBeInTheDocument()
  })

  it('T4: 미국식 선택 시 PATCH 응답(50ms 지연)을 기다리지 않고 프리뷰가 즉시 07/07/2026으로 바뀐다', async () => {
    server.use(
      http.patch('/api/v1/users/me/preferences', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
        return HttpResponse.json({ theme: 'system', locale: 'ko', dateFormat: 'us', startPage: 'dashboards' })
      }),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ dateFormat: 'us' }))),
    )
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('option', { name: '미국식 (07/07/2026)' }))

    // waitFor 없이 동기 단언 — PATCH가 아직 응답하지 않은 시점에도 프리뷰가 갱신돼야 한다
    expect(screen.getByText('미리보기: 07/07/2026')).toBeInTheDocument()

    // 지연 PATCH 가 도착할 때까지 기다린다 — 안 기다리면 다음 파일이 도는 중에 콜백이 실행된다.
    await settlePendingMutations()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 언어 — 저장만(UI 번역 후속 안내)
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesForm — 언어', () => {
  it('T5: 언어 셀렉터가 렌더된다', () => {
    renderForm()
    expect(screen.getByRole('combobox', { name: '언어' })).toBeInTheDocument()
  })

  it('T6: "UI 번역은 후속" 안내 문구가 렌더된다', () => {
    renderForm()
    expect(
      screen.getByText('언어 설정은 저장되며, 화면 문구 번역은 후속 업데이트에서 제공됩니다.'),
    ).toBeInTheDocument()
  })

  it('T7: 영어 선택 시 PATCH가 {locale: "en"}으로 호출된다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/preferences', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ theme: 'system', locale: 'en', dateFormat: 'iso', startPage: 'dashboards' })
      }),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ locale: 'en' }))),
    )
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('option', { name: 'English' }))

    await waitFor(() => {
      expect(capturedBody).toEqual({ locale: 'en' })
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 시작 페이지 — 4옵션 렌더·즉시 PATCH 저장·whoami 재조회·안내 문구 (FR-PF-02 Task 6)
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesForm — 시작 페이지', () => {
  it('T9: 시작 페이지 Select가 4옵션(대시보드 목록/내 이슈/전체 이슈 목록/받은 알림함)을 렌더한다', () => {
    renderForm()
    expect(screen.getByRole('option', { name: '대시보드 목록' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '내 이슈' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '전체 이슈 목록' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '받은 알림함' })).toBeInTheDocument()
  })

  it('T10: whoami startPage 미설정 시 초기값은 dashboards로 폴백한다', () => {
    renderForm()
    expect(screen.getByRole('combobox', { name: '시작 페이지' })).toHaveTextContent('dashboards')
  })

  it('T11: whoami startPage가 설정돼 있으면 그 값이 초기값이다', () => {
    useAuthStore.getState().setSession({
      accessToken: ALICE_TOKEN,
      user: makeWhoami({ startPage: 'issues' }),
    })
    renderForm()
    expect(screen.getByRole('combobox', { name: '시작 페이지' })).toHaveTextContent('issues')
  })

  it('T12: 내 이슈 선택 시 PATCH가 {startPage: "my_issues"}로 호출되고 응답으로 재동기화된다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/preferences', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ theme: 'system', locale: 'ko', dateFormat: 'iso', startPage: 'my_issues' })
      }),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ startPage: 'my_issues' }))),
    )
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('option', { name: '내 이슈' }))

    await waitFor(() => {
      expect(capturedBody).toEqual({ startPage: 'my_issues' })
    })
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: '시작 페이지' })).toHaveTextContent('my_issues')
    })
  })

  it('T13: "다음 로그인부터 적용됩니다" 안내 문구가 렌더된다', () => {
    renderForm()
    expect(screen.getByText('다음 로그인부터 적용됩니다.')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 저장 실패 — role=alert 에러 메시지
// ─────────────────────────────────────────────────────────────────────────────

describe('PreferencesForm — 저장 실패', () => {
  it('T8: PATCH가 500을 반환하면 role=alert 에러 메시지를 표시한다', async () => {
    server.use(
      http.patch('/api/v1/users/me/preferences', () => new HttpResponse(null, { status: 500 })),
    )
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('option', { name: '다크' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        '환경설정을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    })
  })
})
