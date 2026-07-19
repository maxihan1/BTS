// LoginPage handleSuccess 라우팅 우선순위 테스트 — returnTo(안전 검증) > start_page 매핑 > /dashboards (FR-PF-02 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LoginPage } from './login'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'

const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// LoginForm은 identifier-first 2단계(+MFA) 로그인 플로우를 오케스트레이션하는 무거운 컴포넌트다.
// handleSuccess의 라우팅 우선순위 로직만 격리 검증하기 위해, onSuccess를 즉시 트리거하는
// 버튼 스텁으로 교체한다(로그인 플로우 자체는 LoginForm.test.tsx 등에서 이미 검증됨).
vi.mock('@/auth/LoginForm', () => ({
  LoginForm: ({ onSuccess }: { onSuccess?: () => void }) => (
    <button onClick={() => onSuccess?.()}>성공 트리거</button>
  ),
}))

/** window.location.search를 테스트별로 오버라이드하는 헬퍼 */
function setLocationSearch(search: string) {
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    search,
  } as unknown as Location)
}

beforeEach(() => {
  mockNavigate.mockClear()
  useAuthStore.setState({ accessToken: null, user: null })
  setLocationSearch('')
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.restoreAllMocks()
})

async function triggerSuccess() {
  const user = userEvent.setup()
  render(<LoginPage />)
  await user.click(screen.getByRole('button', { name: '성공 트리거' }))
}

describe('LoginPage — main 랜드마크 (C3, _shell 밖이라 자체 main 필요)', () => {
  it('main 랜드마크가 정확히 1개 존재한다', () => {
    render(<LoginPage />)

    expect(screen.getAllByRole('main')).toHaveLength(1)
  })
})

describe('LoginPage handleSuccess — 로그인 후 라우팅 우선순위', () => {
  it('returnTo가 있고 안전한 내부 경로이면 returnTo로 navigate한다', async () => {
    setLocationSearch('?returnTo=/issues/PROJ-5')
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    await triggerSuccess()

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/PROJ-5' })
  })

  it('returnTo가 안전하지 않으면(외부 URL) 무시하고 start_page 매핑을 따른다', async () => {
    setLocationSearch('?returnTo=http://evil.com')
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    await triggerSuccess()

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/inbox' })
  })

  it('returnTo가 없고 store user.startPage="inbox"이면 /inbox로 navigate한다', async () => {
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    await triggerSuccess()

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/inbox' })
  })

  it('returnTo도 startPage도 없으면 /dashboards로 navigate한다', async () => {
    useAuthStore.setState({ user: makeWhoami() })

    await triggerSuccess()

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/dashboards' })
  })

  it('returnTo가 없고 store user.startPage="my_issues"이면 store의 userId를 실어 /issues?assignee=<userId>로 navigate한다', async () => {
    // my_issues는 resolveStartPageNav가 유일하게 userId를 주입하는 경로 — 이 배선이
    // store에서 실제로 threading되는지(드롭되지 않는지) 검증한다.
    const userId = '00000000-0000-4000-8000-000000000099'
    useAuthStore.setState({ user: makeWhoami({ startPage: 'my_issues', userId }) })

    await triggerSuccess()

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues', search: { assignee: userId } })
  })
})
