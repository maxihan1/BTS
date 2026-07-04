// RootLayout 단위 테스트 — useNotificationStream 마운트 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render } from '@testing-library/react'
import { useAuthStore } from '@/auth/authStore'
import { RootLayout } from './__root'

// TanStack Router Outlet mock — 라우터 컨텍스트 없이 단위 테스트 가능
vi.mock('@tanstack/react-router', () => ({
  Outlet: () => null,
}))

// Header mock — Header 내부 의존성(라우터/쿼리) 격리
vi.mock('@/components/Header', () => ({
  Header: () => null,
}))

// CommandPalette mock — 내부에서 useNavigate(TanStack Router)를 쓰므로 Outlet-only mock과 충돌 방지
vi.mock('@/components/command-palette/CommandPalette', () => ({
  CommandPalette: () => null,
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
})
