// MFA 설정 화면 단위 테스트 — status 조회·활성화·비활성화·에러·secret 상태 정리·백업코드 섹션 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { mfaStrings } from '@/i18n/ko'
import { MfaSettings } from './MfaSettings'

// TanStack Router useNavigate mock — 라우터 컨텍스트 없이 단위 테스트 가능
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// refreshSession mock — client.ts 모듈 경로 별칭
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return {
    ...actual,
    refreshSession: vi.fn(),
  }
})

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
      mfaEnrollmentRequired: false,
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
    const user = userEvent.setup({ delay: null })
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
    const user = userEvent.setup({ delay: null })

    // 최초 1회는 false, enable 성공 후 refetch 시 true를 반환하도록 핸들러 설정
    let fetchCount = 0
    server.use(
      mockSetupOk(),
      http.post('/api/v1/auth/mfa/totp/enable', () =>
        new HttpResponse(null, { status: 204 }),
      ),
      http.get('/api/v1/auth/mfa/totp', () => {
        fetchCount += 1
        // 최초 조회는 비활성, 이후(enable 후 invalidate refetch)는 활성
        if (fetchCount === 1) return HttpResponse.json({ enabled: false })
        return HttpResponse.json({ enabled: true })
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
    await user.type(codeInput, '123456')

    const submitBtn = screen.getByRole('button', { name: mfaStrings.enableConfirmButton })
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
    const user = userEvent.setup({ delay: null })
    server.use(mockStatus(true))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 비활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 비활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.disableCodeLabel)).toBeInTheDocument()
    })
  })

  it('T3-S4-2: step-up 코드 입력 → disable → 비활성 상태로 전환', async () => {
    const user = userEvent.setup({ delay: null })
    let fetchCount = 0
    server.use(
      http.delete('/api/v1/auth/mfa/totp', () =>
        new HttpResponse(null, { status: 204 }),
      ),
      http.get('/api/v1/auth/mfa/totp', () => {
        fetchCount += 1
        if (fetchCount === 1) return HttpResponse.json({ enabled: true })
        return HttpResponse.json({ enabled: false })
      }),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 비활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 비활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.disableCodeLabel)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(mfaStrings.disableCodeLabel), '123456')
    await user.click(screen.getByRole('button', { name: mfaStrings.disableConfirmButton }))

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
    const user = userEvent.setup({ delay: null })
    server.use(mockStatus(false), mockSetupOk(), mockEnableError(400, 'invalid_code'))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('인증 코드 (6자리)'), '000000')
    await user.click(screen.getByRole('button', { name: mfaStrings.enableConfirmButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toHaveTextContent('코드가 올바르지 않습니다.')
    // 필드 유지 확인
    expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
  })

  it('T3-S5-2: enable 429 too_many_attempts → rate-limit 메시지 표시', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(mockStatus(false), mockSetupOk(), mockEnableError(429, 'too_many_attempts'))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByLabelText('인증 코드 (6자리)')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('인증 코드 (6자리)'), '111111')
    await user.click(screen.getByRole('button', { name: mfaStrings.enableConfirmButton }))

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

// ─────────────────────────────────────────────────────────────────────────────
// T3-S7: 백업코드 섹션 — 노출 여부
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S7: 백업코드 섹션 노출 여부', () => {
  it('T3-S7-1: TOTP 활성(enabled=true) + 백업코드 미생성 상태에서 섹션이 노출된다', async () => {
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupSectionTitle)).toBeInTheDocument()
    })
    // backup-codes status 쿼리도 완료될 때까지 대기
    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupGenerateButton })).toBeInTheDocument()
    })
  })

  it('T3-S7-2: TOTP 미활성(enabled=false) 시 백업코드 섹션이 노출되지 않는다', async () => {
    server.use(mockStatus(false))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByText('비활성화됨')).toBeInTheDocument()
    })
    expect(screen.queryByText(mfaStrings.backupSectionTitle)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S8: 백업코드 생성 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S8: 백업코드 생성 플로우', () => {
  const CODES = Array.from({ length: 10 }, (_, i) => `code-${String(i).padStart(2, '0')}`)

  beforeEach(() => {
    // navigator.clipboard.writeText mock
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      writable: true,
      configurable: true,
    })
    // URL API mock (다운로드)
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn().mockReturnValue('blob:mock'),
      writable: true,
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      writable: true,
      configurable: true,
    })
  })

  it('T3-S8-1: generated=false → "백업 코드 생성" 버튼 → generateBackupCodes 호출 → 평문 10개 + 저장 경고 표시', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ codes: CODES }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupGenerateButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupGenerateButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupSaveWarning)).toBeInTheDocument()
    })

    // 평문 코드 10개가 화면에 노출된다
    for (const code of CODES) {
      expect(screen.getByText(code)).toBeInTheDocument()
    }

    // 복사 + 다운로드 버튼이 노출된다
    expect(screen.getByRole('button', { name: mfaStrings.backupCopyButton })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: mfaStrings.backupDownloadButton })).toBeInTheDocument()
  })

  it('T3-S8-2: 복사 버튼 클릭 → navigator.clipboard.writeText가 코드들로 호출된다', async () => {
    const user = userEvent.setup({ delay: null })
    // vi.spyOn으로 spy 생성 — Object.defineProperty 단순 할당은 vitest spy가 아님
    const writeTextSpy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined)

    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ codes: CODES }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupGenerateButton })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: mfaStrings.backupGenerateButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupCopyButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupCopyButton }))

    expect(writeTextSpy).toHaveBeenCalledWith(CODES.join('\n'))
    writeTextSpy.mockRestore()
  })

  it('T3-S8-3: 다운로드 버튼 클릭 → URL.createObjectURL이 호출된다', async () => {
    const user = userEvent.setup({ delay: null })

    // anchor click을 intercept하기 위해 document.createElement를 부분 mock
    const mockClick = vi.fn()
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      if (tagName === 'a') {
        const anchor = originalCreateElement('a')
        anchor.click = mockClick
        return anchor
      }
      return originalCreateElement(tagName)
    })

    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ codes: CODES }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupGenerateButton })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: mfaStrings.backupGenerateButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupDownloadButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupDownloadButton }))

    expect(URL.createObjectURL).toHaveBeenCalled()
    expect(mockClick).toHaveBeenCalled()

    vi.restoreAllMocks()
  })

  it('T3-S8-4: generate 성공 후 invalidateQueries([mfa, backup-codes])가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })
    const queryClientRef = { current: null as QueryClient | null }

    // QueryClient를 캡처하는 래퍼
    function CapturingWrapper({ children }: { children: React.ReactNode }) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      queryClientRef.current = client
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>
    }

    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ codes: CODES }),
      ),
    )

    render(<MfaSettings />, { wrapper: CapturingWrapper })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupGenerateButton })).toBeInTheDocument()
    })

    const invalidateSpy = vi.spyOn(queryClientRef.current!, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: mfaStrings.backupGenerateButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupSaveWarning)).toBeInTheDocument()
    })

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['mfa', 'backup-codes'] }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S9: 백업코드 상태 표시 (generated=true)
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S9: 백업코드 상태 표시', () => {
  it('T3-S9-1: generated=true, remaining=2 → 남은 개수 표시 + backupLowWarning 배너', async () => {
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: true, remaining: 2 }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupLowWarning)).toBeInTheDocument()
    })
    // 남은 개수 숫자를 span에서 찾는다 (다른 2 텍스트와 중복 방지)
    expect(screen.getByText('2', { selector: 'span' })).toBeInTheDocument()
    // 재생성 버튼이 노출된다
    expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton })).toBeInTheDocument()
  })

  it('T3-S9-2: generated=true, remaining=0 → backupNoneWarning 배너 표시', async () => {
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: true, remaining: 0 }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupNoneWarning)).toBeInTheDocument()
    })
  })

  it('T3-S9-3: generated=true, remaining=5 → 경고 배너 없음, 재생성 버튼만 노출', async () => {
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: true, remaining: 5 }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton })).toBeInTheDocument()
    })
    expect(screen.queryByText(mfaStrings.backupLowWarning)).not.toBeInTheDocument()
    expect(screen.queryByText(mfaStrings.backupNoneWarning)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-S10: 백업코드 재생성 — 인라인 확인 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('T3-S10: 백업코드 재생성 인라인 확인 플로우', () => {
  const REGEN_CODES = Array.from({ length: 10 }, (_, i) => `regen-${String(i).padStart(2, '0')}`)

  beforeEach(() => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      writable: true,
      configurable: true,
    })
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn().mockReturnValue('blob:mock'),
      writable: true,
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      writable: true,
      configurable: true,
    })
  })

  it('T3-S10-1: "백업 코드 재생성" 클릭 → 인라인 확인 박스(경고문 + 재생성/취소 버튼) 노출', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: true, remaining: 5 }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupRegenerateConfirmBody)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateConfirmButton })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateCancelButton })).toBeInTheDocument()
  })

  it('T3-S10-2: 확인 박스에서 "취소" 클릭 → generateBackupCodes 미호출, 확인 박스 닫힘', async () => {
    const user = userEvent.setup({ delay: null })
    const generateSpy = vi.fn().mockResolvedValue({ codes: REGEN_CODES })

    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: true, remaining: 5 }),
      ),
      http.post('/api/v1/auth/mfa/backup-codes', generateSpy),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateCancelButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupRegenerateCancelButton }))

    // 확인 박스가 닫힌다
    await waitFor(() => {
      expect(screen.queryByText(mfaStrings.backupRegenerateConfirmBody)).not.toBeInTheDocument()
    })
    // generateBackupCodes API는 호출되지 않는다
    expect(generateSpy).not.toHaveBeenCalled()
  })

  it('T3-S10-3: 확인 박스에서 "재생성" 확인 → generateBackupCodes 호출 → 평문 코드 표시', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: true, remaining: 5 }),
      ),
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ codes: REGEN_CODES }),
      ),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupRegenerateButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.backupRegenerateConfirmButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.backupRegenerateConfirmButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupSaveWarning)).toBeInTheDocument()
    })

    for (const code of REGEN_CODES) {
      expect(screen.getByText(code)).toBeInTheDocument()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-S1: 강제 안내 배너 (FR-D6-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-S1: 강제 안내 배너', () => {
  it('T4-S1-1: mfaEnrollmentRequired=true 사용자에게 안내 배너(role=status)가 노출된다', async () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        userId: 'u-alice',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: true,
      },
    })
    server.use(mockStatus(false))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    const banner = screen.getByRole('status', { name: /강제/ })
    expect(banner).toBeInTheDocument()
    expect(banner).toHaveAttribute('aria-live', 'polite')
  })

  it('T4-S1-2: mfaEnrollmentRequired=false 사용자에게는 안내 배너가 노출되지 않는다', async () => {
    // beforeEach에서 false로 초기화됨 — 추가 setState 불필요
    server.use(mockStatus(false))

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    expect(screen.queryByText(mfaStrings.enforcementBanner)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-S1: WebauthnSection — TOTP 미활성 상태에서도 항상 노출 (FR-1/D-A)
// ─────────────────────────────────────────────────────────────────────────────

describe('T5-S1: WebauthnSection은 TOTP 활성 여부와 무관하게 항상 노출된다', () => {
  it('T5-S1-1: TOTP 미활성 + WebAuthn 키 없음 → 보안 키 섹션 헤더와 "보안 키 추가" 버튼이 노출된다', async () => {
    server.use(mockStatus(false))

    renderMfaSettings()

    // TOTP 비활성 상태 텍스트 확인
    await waitFor(() => {
      expect(screen.getByText('비활성화됨')).toBeInTheDocument()
    })

    // 보안 키 섹션 헤더 노출 — WebauthnSection이 isEnabled 분기 밖에 있어야 통과
    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnSectionTitle)).toBeInTheDocument()
    })
    // "보안 키 추가" 버튼 노출
    expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    // 백업코드 섹션은 미노출 (TOTP 비활성)
    expect(screen.queryByText(mfaStrings.backupSectionTitle)).not.toBeInTheDocument()
  })

  it('T5-S1-2: TOTP 활성 → 보안 키 섹션 + 백업코드 섹션 모두 노출된다', async () => {
    server.use(
      mockStatus(true),
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
    )

    renderMfaSettings()

    // 보안 키 섹션 헤더 노출
    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnSectionTitle)).toBeInTheDocument()
    })
    // 백업코드 섹션 노출
    await waitFor(() => {
      expect(screen.getByText(mfaStrings.backupSectionTitle)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-S2: 등록 후 게이트 해제 (FR-D6-4)
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-S2: 등록 후 게이트 해제', () => {
  it('T4-S2-1: 강제모드 enable 성공 → refreshSession + navigate(/dashboard) 호출', async () => {
    const { refreshSession } = await import('@/api/client')
    const refreshSessionMock = vi.mocked(refreshSession)
    refreshSessionMock.mockResolvedValue({
      username: 'alice',
      email: 'alice@bts.local',
      authMethod: 'local',
      userId: 'u-alice',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    })
    mockNavigate.mockReset()

    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        userId: 'u-alice',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: true,
      },
    })

    const user = userEvent.setup({ delay: null })
    server.use(
      mockSetupOk(),
      http.post('/api/v1/auth/mfa/totp/enable', () =>
        new HttpResponse(null, { status: 204 }),
      ),
      http.get('/api/v1/auth/mfa/totp', () => HttpResponse.json({ enabled: false })),
    )

    renderMfaSettings()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '2단계 인증 활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '2단계 인증 활성화' }))

    await waitFor(() => {
      expect(screen.getByRole('img')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('인증 코드 (6자리)'), '123456')
    await user.click(screen.getByRole('button', { name: mfaStrings.enableConfirmButton }))

    await waitFor(() => {
      expect(refreshSessionMock).toHaveBeenCalledTimes(1)
    })
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/dashboard' })
  })

  it('T4-S2-2: 비강제모드 enable 성공 → refreshSession / navigate 미호출', async () => {
    const { refreshSession } = await import('@/api/client')
    const refreshSessionMock = vi.mocked(refreshSession)
    refreshSessionMock.mockReset()
    mockNavigate.mockReset()

    // beforeEach에서 mfaEnrollmentRequired: false로 초기화됨

    const user = userEvent.setup({ delay: null })
    let fetchCount = 0
    server.use(
      mockSetupOk(),
      http.post('/api/v1/auth/mfa/totp/enable', () =>
        new HttpResponse(null, { status: 204 }),
      ),
      http.get('/api/v1/auth/mfa/totp', () => {
        fetchCount += 1
        if (fetchCount === 1) return HttpResponse.json({ enabled: false })
        return HttpResponse.json({ enabled: true })
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

    await user.type(screen.getByLabelText('인증 코드 (6자리)'), '123456')
    await user.click(screen.getByRole('button', { name: mfaStrings.enableConfirmButton }))

    await waitFor(() => {
      expect(screen.getByText('활성화됨')).toBeInTheDocument()
    })

    expect(refreshSessionMock).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })
})
