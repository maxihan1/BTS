// RootLayout 단위 테스트 — useNotificationStream 마운트 + 단축키/도움말 모달 결선 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { useAuthStore } from '@/auth/authStore'
import { RootLayout } from './__root'

// TanStack Router Outlet/useNavigate mock — useKeyboardShortcuts가 useNavigate를 호출하므로 no-op spy 제공
vi.mock('@tanstack/react-router', () => ({
  Outlet: () => null,
  useNavigate: () => vi.fn(),
}))

// CommandPalette mock — 내부에서 useNavigate(TanStack Router)를 쓰므로 Outlet-only mock과 충돌 방지
vi.mock('@/components/command-palette/CommandPalette', () => ({
  CommandPalette: () => null,
}))

// LoginDialog mock — 같은 사유. 내부에서 useRouterState/useNavigate 를 쓰므로 Outlet-only mock 과
// 충돌한다. 이 파일의 관심사는 useNotificationStream 배선과 단축키이지 로그인 모달이 아니다 —
// 모달 자체의 계약은 auth/LoginDialog.test.tsx 가 소유한다.
vi.mock('@/auth/LoginDialog', () => ({
  LoginDialog: () => null,
}))

// useKeymap mock — RootLayout이 마운트하는 useKeyboardShortcuts가 useKeymap(react-query)을 구독하므로
// (FR-PF-03 Task-8), QueryClientProvider 없이 렌더하기 위해 mock. data undefined면 useKeyboardShortcuts가
// DEFAULT_KEYMAP으로 폴백해 기존 '?' 도움말 동작이 무회귀한다.
vi.mock('@/api/keymap', () => ({
  useKeymap: () => ({ data: undefined }),
}))

// useNotificationStream mock — 호출 여부 검증 대상
vi.mock('@/notifications/useNotificationStream', () => ({
  useNotificationStream: vi.fn(),
}))

import { useNotificationStream } from '@/notifications/useNotificationStream'

describe('RootLayout', () => {
  beforeEach(() => {
    vi.mocked(useNotificationStream).mockReset()
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('인증 여부와 관계없이 마운트 시 useNotificationStream을 호출한다', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    render(<RootLayout />)

    expect(useNotificationStream).toHaveBeenCalledTimes(1)
  })

  it('인증 상태에서도 useNotificationStream을 호출한다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        userId: 'u1',
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })

    render(<RootLayout />)

    expect(useNotificationStream).toHaveBeenCalledTimes(1)
  })

  it('인증 상태에서 ?를 누르면 단축키 도움말 모달이 열린다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        userId: 'u1',
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })

    render(<RootLayout />)

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true }))
    })

    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('비인증 상태에서 ?를 눌러도 단축키 도움말 모달이 열리지 않는다', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    render(<RootLayout />)

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true }))
    })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('<main>을 직접 렌더하지 않는다 — main 랜드마크는 ShellLayout·login이 각자 소유한다(C3, 이중 main 방지)', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    const { container } = render(<RootLayout />)

    expect(container.querySelector('main')).not.toBeInTheDocument()
    expect(screen.queryByRole('main')).not.toBeInTheDocument()
  })
})
