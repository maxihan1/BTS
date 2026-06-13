// WebAuthn 보안 키 설정 섹션 단위 테스트 — 목록·등록·삭제·FR-8 refreshSession·에러·미지원 브라우저 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { mfaStrings } from '@/i18n/ko'
import { WebauthnSection } from './WebauthnSection'

// TanStack Router useNavigate mock
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// refreshSession mock
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return {
    ...actual,
    refreshSession: vi.fn(),
  }
})

// @simplewebauthn/browser mock — jsdom은 PublicKeyCredential 미정의라
// browserSupportsWebAuthn()이 자연적으로 false를 반환하지만, 등록 성공 케이스에서는
// registerSecurityKey를 직접 mock해 브라우저 지원 여부와 무관하게 동작하도록 한다.
vi.mock('@/api/webauthn', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/webauthn')>()
  return {
    ...actual,
    registerSecurityKey: vi.fn(),
    deleteWebauthnKey: vi.fn(),
    listWebauthnKeys: vi.fn(),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const KEY_1 = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '회사 노트북',
  createdAt: '2024-01-15T10:00:00Z',
  lastUsedAt: '2024-03-01T09:30:00Z',
}

const KEY_2 = {
  id: '22222222-2222-4222-8222-222222222222',
  name: null,
  createdAt: '2024-02-01T12:00:00Z',
  lastUsedAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function mockListKeys(keys: typeof KEY_1[]) {
  return http.get('/api/v1/auth/mfa/webauthn', () =>
    HttpResponse.json({ keys }),
  )
}

function mockDeleteKeyOk(id: string) {
  return http.delete(`/api/v1/auth/mfa/webauthn/${id}`, () =>
    new HttpResponse(null, { status: 204 }),
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

function renderWebauthnSection() {
  const Wrapper = createWrapper()
  return render(<WebauthnSection />, { wrapper: Wrapper })
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
// T4-WA-S1: 목록 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S1: 보안 키 목록 표시', () => {
  it('T4-WA-S1-1: 키 2개 조회 시 이름과 등록일이 표시된다', async () => {
    server.use(mockListKeys([KEY_1, KEY_2]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(KEY_1.name!)).toBeInTheDocument()
    })
    // KEY_2는 name=null → fallback 이름이나 id가 표시되어야 한다
    expect(screen.getAllByRole('listitem').length).toBe(2)
  })

  it('T4-WA-S1-2: lastUsedAt=null인 키는 "사용 안 함" 텍스트를 표시한다', async () => {
    server.use(mockListKeys([KEY_2]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnLastUsedNever)).toBeInTheDocument()
    })
  })

  it('T4-WA-S1-3: 키가 0개이면 "등록된 보안 키가 없습니다." 빈 상태 메시지를 표시한다', async () => {
    server.use(mockListKeys([]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnEmptyState)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-WA-S2: 보안 키 등록 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S2: 보안 키 등록 플로우', () => {
  it('T4-WA-S2-1: "보안 키 추가" 버튼 클릭 → 별칭 입력 필드 노출', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })
  })

  it('T4-WA-S2-2: 별칭 입력 후 등록 성공 → registerSecurityKey가 name과 함께 호출되고 목록이 invalidateQueries로 갱신된다', async () => {
    const { registerSecurityKey } = await import('@/api/webauthn')
    const registerMock = vi.mocked(registerSecurityKey)
    registerMock.mockResolvedValue(undefined)

    const user = userEvent.setup({ delay: null })

    let listCallCount = 0
    server.use(
      http.get('/api/v1/auth/mfa/webauthn', () => {
        listCallCount += 1
        if (listCallCount === 1) return HttpResponse.json({ keys: [] })
        return HttpResponse.json({ keys: [KEY_1] })
      }),
    )

    const queryClientRef = { current: null as QueryClient | null }
    function CapturingWrapper({ children }: { children: React.ReactNode }) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      queryClientRef.current = client
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>
    }

    render(<WebauthnSection />, { wrapper: CapturingWrapper })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(mfaStrings.webauthnNameLabel), '회사 노트북')

    const invalidateSpy = vi.spyOn(queryClientRef.current!, 'invalidateQueries')

    const submitBtn = screen.getByRole('button', { name: /등록|확인/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(registerMock).toHaveBeenCalledWith('회사 노트북')
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['webauthn', 'keys'] }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-WA-S3: 보안 키 삭제 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S3: 보안 키 삭제 플로우', () => {
  it('T4-WA-S3-1: 삭제 버튼 클릭 → 인라인 삭제 확인 박스 노출', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([KEY_1]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(KEY_1.name!)).toBeInTheDocument()
    })

    const deleteBtn = screen.getByRole('button', { name: mfaStrings.webauthnDeleteButton })
    await user.click(deleteBtn)

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnDeleteConfirmBody)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteConfirmButton })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteCancelButton })).toBeInTheDocument()
  })

  it('T4-WA-S3-2: 삭제 취소 → deleteWebauthnKey 미호출, 확인 박스 닫힘', async () => {
    const { deleteWebauthnKey } = await import('@/api/webauthn')
    const deleteMock = vi.mocked(deleteWebauthnKey)
    deleteMock.mockReset()

    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([KEY_1]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnDeleteButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteCancelButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnDeleteCancelButton }))

    await waitFor(() => {
      expect(screen.queryByText(mfaStrings.webauthnDeleteConfirmBody)).not.toBeInTheDocument()
    })
    expect(deleteMock).not.toHaveBeenCalled()
  })

  it('T4-WA-S3-3: 삭제 확인 → deleteWebauthnKey 호출 → 목록 invalidateQueries', async () => {
    const { deleteWebauthnKey } = await import('@/api/webauthn')
    const deleteMock = vi.mocked(deleteWebauthnKey)
    deleteMock.mockResolvedValue(undefined)

    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([KEY_1]), mockDeleteKeyOk(KEY_1.id))

    const queryClientRef = { current: null as QueryClient | null }
    function CapturingWrapper({ children }: { children: React.ReactNode }) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      queryClientRef.current = client
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>
    }

    render(<WebauthnSection />, { wrapper: CapturingWrapper })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnDeleteButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteConfirmButton })).toBeInTheDocument()
    })

    const invalidateSpy = vi.spyOn(queryClientRef.current!, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnDeleteConfirmButton }))

    await waitFor(() => {
      expect(deleteMock).toHaveBeenCalledWith(KEY_1.id)
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['webauthn', 'keys'] }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-WA-S4: FR-8 (P0) — mfaEnrollmentRequired=true 시 등록 후 refreshSession 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S4: FR-8 등록 후 클레임 게이트 해제', () => {
  it('T4-WA-S4-1: mfaEnrollmentRequired=true 사용자가 등록 성공 시 refreshSession이 호출된다', async () => {
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

    const { registerSecurityKey } = await import('@/api/webauthn')
    const registerMock = vi.mocked(registerSecurityKey)
    registerMock.mockResolvedValue(undefined)

    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(mfaStrings.webauthnNameLabel), '보안 키')

    const submitBtn = screen.getByRole('button', { name: /등록|확인/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(refreshSessionMock).toHaveBeenCalledTimes(1)
    })
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/dashboard' })
  })

  it('T4-WA-S4-2: mfaEnrollmentRequired=false 사용자가 등록 성공 시 refreshSession이 호출되지 않는다', async () => {
    const { refreshSession } = await import('@/api/client')
    const refreshSessionMock = vi.mocked(refreshSession)
    refreshSessionMock.mockReset()
    mockNavigate.mockReset()

    // beforeEach에서 mfaEnrollmentRequired: false로 초기화됨

    const { registerSecurityKey } = await import('@/api/webauthn')
    const registerMock = vi.mocked(registerSecurityKey)
    registerMock.mockResolvedValue(undefined)

    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(mfaStrings.webauthnNameLabel), '보안 키')

    const submitBtn = screen.getByRole('button', { name: /등록|확인/i })
    await user.click(submitBtn)

    await waitFor(() => {
      // 등록 성공 후 폼이 닫히거나 성공 상태로 전환됨을 확인
      expect(registerMock).toHaveBeenCalledTimes(1)
    })

    expect(refreshSessionMock).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-WA-S5: 에러 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S5: 에러 처리', () => {
  it('T4-WA-S5-1: EC-1 NotAllowedError (사용자 취소) → 인라인 에러 메시지 + 화면 유지', async () => {
    const { registerSecurityKey } = await import('@/api/webauthn')
    const registerMock = vi.mocked(registerSecurityKey)
    const notAllowedError = new Error('The operation either timed out or was not allowed.')
    notAllowedError.name = 'NotAllowedError'
    registerMock.mockRejectedValue(notAllowedError)

    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    const submitBtn = screen.getByRole('button', { name: /등록|확인/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // 입력 필드가 여전히 화면에 유지된다
    expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
  })

  it('T4-WA-S5-2: EC-2 ApiError 409 already_registered → "이미 등록된 보안 키" 메시지', async () => {
    const { registerSecurityKey } = await import('@/api/webauthn')
    const { ApiError } = await import('@/api/client')
    const registerMock = vi.mocked(registerSecurityKey)
    registerMock.mockRejectedValue(new ApiError(409, { error: 'already_registered' }))

    const user = userEvent.setup({ delay: null })
    server.use(mockListKeys([]))

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    const submitBtn = screen.getByRole('button', { name: /등록|확인/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toHaveTextContent('이미 등록된 보안 키')
  })

  it('T4-WA-S5-3: EC-3 미지원 브라우저(browserSupportsWebAuthn=false) → 추가 버튼 비활성 + 안내 문구', async () => {
    // jsdom에서는 PublicKeyCredential이 없어 browserSupportsWebAuthn()이 false를 반환한다.
    // 이 케이스는 컴포넌트가 @simplewebauthn/browser의 browserSupportsWebAuthn을 호출하고
    // false일 때 버튼을 disabled 처리하거나 안내를 표시하는지 검증한다.
    server.use(mockListKeys([]))

    // WebAuthn mock을 재설정해 원본 browserSupportsWebAuthn을 사용하도록 한다
    // (vi.mock은 @/api/webauthn만 대상 — @simplewebauthn/browser는 별도 mock)
    vi.mock('@simplewebauthn/browser', () => ({
      browserSupportsWebAuthn: vi.fn().mockReturnValue(false),
      startRegistration: vi.fn(),
      startAuthentication: vi.fn(),
    }))

    renderWebauthnSection()

    await waitFor(() => {
      // 안내 문구 또는 버튼 비활성 확인
      const addBtn = screen.queryByRole('button', { name: mfaStrings.webauthnAddButton })
      const unsupportedMsg = screen.queryByText(mfaStrings.webauthnUnsupportedBrowser)
      expect(addBtn === null || addBtn.hasAttribute('disabled') || unsupportedMsg !== null).toBe(true)
    })
  })
})
