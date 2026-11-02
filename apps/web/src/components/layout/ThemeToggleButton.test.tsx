// 상단바 테마 전환 버튼 테스트 — 누르면 반대 테마로 PATCH · 즉시 적용 · 실패 시 되돌림 (FR-PF-01)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { makeWhoami, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { navLabels } from '@/i18n/nav-labels'
import { ThemeToggleButton } from './ThemeToggleButton'

function renderButton() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><ThemeToggleButton /></QueryClientProvider>)
}

beforeEach(() => {
  useAuthStore.getState().setSession({ accessToken: mockAccessToken('alice'), user: makeWhoami({ theme: 'light' }) })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  document.documentElement.classList.remove('dark')
})

describe('ThemeToggleButton', () => {
  it('라이트에서 누르면 다크로 저장하고 바로 어두워지며, 저장 뒤 버튼이 「라이트 모드로 전환」이 된다', async () => {
    let body: unknown
    server.use(
      http.patch('/api/v1/users/me/preferences', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ theme: 'dark', locale: 'ko', dateFormat: 'iso', startPage: 'dashboards' })
      }),
      http.get('/api/v1/users/me/whoami', () => HttpResponse.json(makeWhoami({ theme: 'dark' }))),
    )
    const user = userEvent.setup()
    renderButton()

    await user.click(screen.getByRole('button', { name: navLabels.themeToDark }))

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(await screen.findByRole('button', { name: navLabels.themeToLight })).toBeInTheDocument()
    expect(body).toEqual({ theme: 'dark' })
  })

  it('저장이 실패하면 원래 테마(라이트)로 되돌린다', async () => {
    server.use(http.patch('/api/v1/users/me/preferences', () => HttpResponse.json({ code: 'X', message: 'x' }, { status: 500 })))
    const user = userEvent.setup()
    renderButton()

    await user.click(screen.getByRole('button', { name: navLabels.themeToDark }))

    await waitFor(() => { expect(document.documentElement.classList.contains('dark')).toBe(false) })
    expect(screen.getByRole('button', { name: navLabels.themeToDark })).toBeEnabled()
  })
})
