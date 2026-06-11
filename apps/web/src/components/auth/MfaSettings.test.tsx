// MFA 설정 화면 단위 테스트 — status 조회·활성화·비활성화·에러·secret 상태 정리 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeEach } from 'vitest'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { MfaSettings } from './MfaSettings'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const SETUP_RESPONSE = {
  otpauth_uri: 'otpauth://totp/BTS:alice?secret=JBSWY3DPEHPK3PXP',
  qr_png_data_uri: 'data:image/png;base64,abc123',
  secret_base32: 'JBSWY3DPEHPK3PXP',
}

function mockStatus(enabled: boolean) {
  return http.get('/api/v1/auth/mfa/totp', () =>
    HttpResponse.json({ enabled }),
  )
}

function mockSetupOk() {
  return http.post('/api/v1/auth/mfa/totp/setup', () =>
    HttpResponse.json(SETUP_RESPONSE),
  )
}

function mockEnableError(status: number, errorCode: string) {
  return http.post('/api/v1/auth/mfa/totp/enable', () =>
    HttpResponse.json({ error: errorCode }, { status }),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function renderMfaSettings() {
  const Wrapper = createWrapper()
  return render(<MfaSettings />, { wrapper: Wrapper })
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'test-token',
    user: {
      username: 'alice',
      email: 'alice@bts.local',
      authMethod: 'local',
      userId: 'u-alice',
      mustChangePassword: false,
      isSystemAdmin: false,
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S1: 미활성 상태 초기 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S1: 미활성 상태 초기 렌더', () => {
  it('T3-S1-1: "비활성화됨" 상태 텍스트와 활성화 버튼이 노출된다', async () => {
    server.use(mockStatus(false))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByText('비활성화됨')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '2단계 인증 비활성화' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S2: 활성화 플로우 — setup → QR/secret 표시 → enable → 완료
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S2: 활성화 플로우', () => {
  it('T3-S2-1: 활성화 버튼 클릭 → setup 호출 → QR img와 secret_base32 표시', async () => {
    const user = userEvent.setup()
    server.use(mockStatus(false), mockSetupOk())

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByRole('img')).toBeInTheDocument()
    })
    const img = screen.getByRole('img')
    expect(img).toHaveAttribute('src', SETUP_RESPONSE.qr_png_data_uri)
    expect(screen.getByText(SETUP_RESPONSE.secret_base32)).toBeInTheDocument()
    expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
  })

  it('T3-S2-2: 코드 입력 → enable → 활성 상태로 전환 + QR/secret DOM에서 사라진다', async () => {
    const user = userEvent.setup()

    // enable 성공 후 status refetch → enabled:true
    let enableCalled = false
    server.use(
      mockStatus(false),
      mockSetupOk(),
      http.post('/api/v1/auth/mfa/totp/enable', () => {
        enableCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
      // status 재조회 시 enabled:true 반환
      http.get('/api/v1/auth/mfa/totp', () => {
        if (enableCalled) return HttpResponse.json({ enabled: true })
        return HttpResponse.json({ enabled: false })
      }),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByRole('img')).toBeInTheDocument()
    })

    const codeInput = screen.getByLabelText('인증 코드 (6자리)')
    await user.type(codeInput, '123456', { delay: null })

    const submitBtn = screen.getByRole('button', { name: '활성화 확인' })
    await user.click(submitBtn)

    // 활성 상태로 전환 확인
    await waitFor(() => {
      expect(screen.getByText('활성화됨')).toBeInTheDocument()
    })

    // CONCERN-state: enable 성공 후 secret 텍스트가 DOM에서 사라진다
    expect(screen.queryByText(SETUP_RESPONSE.secret_base32)).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    // 비활성화 버튼이 노출되고 활성화 버튼은 사라진다
    expect(screen.getByRole('button', { name: '2단계 인증 비활성화' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '2단계 인증 활성화' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S3: 활성 상태 초기 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S3: 활성 상태 초기 렌더', () => {
  it('T3-S3-1: "활성화됨" 상태 텍스트와 비활성화 버튼이 노출된다', async () => {
    server.use(mockStatus(true))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByText('활성화됨')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '2단계 인증 비활성화' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '2단계 인증 활성화' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S4: 비활성화 플로우 — step-up 코드 입력 → disable → 비활성 전환
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S4: 비활성화 플로우', () => {
  it('T3-S4-1: 비활성화 버튼 클릭 → step-up 코드 입력 영역 노출', async () => {
    const user = userEvent.setup()
    server.use(mockStatus(true))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 비활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 비활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText('현재 인증 코드 (6자리)')).toBeInTheDocument()
    })
  })

  it('T3-S4-2: step-up 코드 입력 → disable → 비활성 상태로 전환', async () => {
    const user = userEvent.setup()
    let disableCalled = false
    server.use(
      http.delete('/api/v1/auth/mfa/totp', () => {
        disableCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/v1/auth/mfa/totp', () => {
        if (disableCalled) return HttpResponse.json({ enabled: false })
        return HttpResponse.json({ enabled: true })
      }),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 비활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 비활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText('현재 인증 코드 (6자리)')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('현재 인증 코드 (6자리)'), '123456', { delay: null })
    await user.click(screen.getByRole('button', { name: '비활성화 확인' }))

    await waitFor(() => {
      expect(screen.getByText('비활성화됨')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S5: 에러 처리 — enable 실패
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S5: 에러 처리', () => {
  it('T3-S5-1: enable 400 invalid_code → 인라인 에러 메시지 표시, 코드 입력 필드 유지', async () => {
    const user = userEvent.setup()
    server.use(mockStatus(false), mockSetupOk(), mockEnableError(400, 'invalid_code'))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('인증 코드 (6자리)'), '000000', { delay: null })
    await user.click(screen.getByRole('button', { name: '활성화 확인' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toHaveTextContent('코드가 올바르지 않습니다.')
    // 필드 유지 확인
    expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
  })

  it('T3-S5-2: enable 429 too_many_attempts → rate-limit 메시지 표시', async () => {
    const user = userEvent.setup()
    server.use(mockStatus(false), mockSetupOk(), mockEnableError(429, 'too_many_attempts'))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('인증 코드 (6자리)'), '111111', { delay: null })
    await user.click(screen.getByRole('button', { name: '활성화 확인' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toHaveTextContent('시도가 너무 많습니다.')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S6: 로딩 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S6: 로딩 상태', () => {
  it('T3-S6-1: status 로딩 중 스켈레톤/로딩 텍스트가 표시된다', () => {
    // 응답을 지연시켜 로딩 상태 확인
    server.use(
      http.get('/api/v1/auth/mfa/totp', async () => {
        await new Promise(() => undefined) // 무한 대기 — 로딩 상태 고정
        return HttpResponse.json({ enabled: false })
      }),
    )

    renderMfaSettings()

    // 로딩 중엔 스켈레톤이나 aria-label이 있어야 함
    expect(screen.getByRole('status')).toBeInTheDocument()
  })
})
