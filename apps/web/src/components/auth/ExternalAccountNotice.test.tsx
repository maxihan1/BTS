// 비밀번호 · MFA 설정 화면 단위 테스트 — VITE_AUTH_ACCOUNT_URL 이 있으면 폼 대신 Auth 계정 링크를 보인다
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PasswordSettingsPage } from '@/routes/settings.password'
import { MfaSettingsPage } from '@/routes/settings.mfa'
import { useAuthStore } from '@/auth/authStore'

vi.mock('@/components/auth/ChangePasswordForm', () => ({
  ChangePasswordForm: () => <div data-testid="change-password-form" />,
}))
vi.mock('@/components/auth/MfaSettings', () => ({
  MfaSettings: () => <div data-testid="mfa-settings" />,
}))

const ACCOUNT_URL = 'https://auth.example/account'

afterEach(() => {
  vi.unstubAllEnvs()
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('강제 이동 대상자는 VITE_AUTH_ACCOUNT_URL 이 있어도 BTS 폼을 쓴다 (가드가 이 화면으로만 보낸다)', () => {
  it('mustChangePassword 면 비밀번호 화면에 변경 폼이 있다', () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', ACCOUNT_URL)
    useAuthStore.setState({ user: { mustChangePassword: true } as never })

    render(<PasswordSettingsPage />)

    expect(screen.getByTestId('change-password-form')).toBeInTheDocument()
  })

  it('mfaEnrollmentRequired 면 2단계 인증 화면에 BTS 설정과 Auth 링크가 둘 다 있다', () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', ACCOUNT_URL)
    useAuthStore.setState({ user: { mfaEnrollmentRequired: true } as never })

    render(<MfaSettingsPage />)

    expect(screen.getByTestId('mfa-settings')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '통합 계정 열기' })).toHaveAttribute('href', ACCOUNT_URL)
  })
})

describe('VITE_AUTH_ACCOUNT_URL 이 있으면 (Auth_platform 이 비밀번호 · MFA 를 맡는다)', () => {
  it('비밀번호 화면은 폼 대신 Auth 계정 링크를 보인다', () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', ACCOUNT_URL)

    render(<PasswordSettingsPage />)

    expect(screen.getByRole('link', { name: '통합 계정 열기' })).toHaveAttribute('href', ACCOUNT_URL)
    expect(screen.queryByTestId('change-password-form')).toBeNull()
  })

  it('2단계 인증 화면은 설정 대신 Auth 계정 링크와 다시 로그인 안내를 보인다', () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', ACCOUNT_URL)

    render(<MfaSettingsPage />)

    expect(screen.getByRole('link', { name: '통합 계정 열기' })).toHaveAttribute('href', ACCOUNT_URL)
    expect(screen.getByText(/다시 로그인/)).toBeInTheDocument()
    expect(screen.queryByTestId('mfa-settings')).toBeNull()
  })
})

describe('VITE_AUTH_ACCOUNT_URL 이 없으면 지금 그대로', () => {
  it('비밀번호 화면은 변경 폼을 보인다', () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', '')

    render(<PasswordSettingsPage />)

    expect(screen.getByTestId('change-password-form')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '통합 계정 열기' })).toBeNull()
  })

  it('2단계 인증 화면은 설정을 보인다', () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', '')

    render(<MfaSettingsPage />)

    expect(screen.getByTestId('mfa-settings')).toBeInTheDocument()
  })
})
