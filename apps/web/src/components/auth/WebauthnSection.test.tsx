// WebAuthn 보안 키 설정 섹션 단위 테스트 — 목록·등록·삭제·FR-8 refreshSession·에러·미지원 브라우저 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useAuthStore } from '@/auth/authStore'
import { mfaStrings } from '@/i18n/ko'
import { WebauthnSection } from './WebauthnSection'

// TanStack Router useNavigate mock
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// refreshSession mock — vi.mock 팩토리는 호이스팅되므로 외부 변수 참조 금지
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return {
    ...actual,
    refreshSession: vi.fn(),
  }
})

// @simplewebauthn/browser mock — jsdom은 PublicKeyCredential 미정의라 false 반환.
// 대부분의 테스트에서 지원 환경을 가정하므로 vi.fn()으로 등록하고 beforeEach에서 true로 설정한다.
vi.mock('@simplewebauthn/browser', () => ({
  browserSupportsWebAuthn: vi.fn(),
  startRegistration: vi.fn(),
  startAuthentication: vi.fn(),
}))

// @/api/webauthn mock — listWebauthnKeys·registerSecurityKey·deleteWebauthnKey를 vi.fn으로 교체
vi.mock('@/api/webauthn', () => ({
  listWebauthnKeys: vi.fn(),
  registerSecurityKey: vi.fn(),
  deleteWebauthnKey: vi.fn(),
  webauthnRegisterStart: vi.fn(),
  webauthnRegisterFinish: vi.fn(),
  webauthnAuthenticateStart: vi.fn(),
  verifyWebauthn: vi.fn(),
  authenticateWithSecurityKey: vi.fn(),
}))

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

beforeEach(async () => {
  // 이전 테스트의 mock 호출 카운트를 초기화한다 (구현은 유지, 호출 기록만 삭제).
  vi.clearAllMocks()

  // @simplewebauthn/browser: 기본적으로 지원 환경으로 설정
  const { browserSupportsWebAuthn } = await import('@simplewebauthn/browser')
  vi.mocked(browserSupportsWebAuthn).mockReturnValue(true)

  // @/api/webauthn: 기본값 설정
  const { listWebauthnKeys, registerSecurityKey, deleteWebauthnKey } = await import('@/api/webauthn')
  vi.mocked(listWebauthnKeys).mockResolvedValue({ keys: [] })
  vi.mocked(registerSecurityKey).mockResolvedValue(undefined)
  vi.mocked(deleteWebauthnKey).mockResolvedValue(undefined)

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
      mfaEnrollmentRequired: false,
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-WA-S1: 목록 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S1: 보안 키 목록 표시', () => {
  it('T4-WA-S1-1: 키 2개 조회 시 이름과 등록일이 표시된다', async () => {
    const { listWebauthnKeys } = await import('@/api/webauthn')
    vi.mocked(listWebauthnKeys).mockResolvedValue({ keys: [KEY_1, KEY_2] })

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(KEY_1.name)).toBeInTheDocument()
    })
    expect(screen.getAllByRole('listitem').length).toBe(2)
  })

  it('T4-WA-S1-2: lastUsedAt=null인 키는 "사용 안 함" 텍스트를 표시한다', async () => {
    const { listWebauthnKeys } = await import('@/api/webauthn')
    vi.mocked(listWebauthnKeys).mockResolvedValue({ keys: [KEY_2] })

    renderWebauthnSection()

    await waitFor(() => {
      // "사용 안 함"은 <span> 안에 있어 부모 텍스트에서 분리되므로 span 선택자로 조회한다
      expect(screen.getByText(mfaStrings.webauthnLastUsedNever, { selector: 'span' })).toBeInTheDocument()
    })
  })

  it('T4-WA-S1-3: 키가 0개이면 "등록된 보안 키가 없습니다." 빈 상태 메시지를 표시한다', async () => {
    // beforeEach에서 listWebauthnKeys: { keys: [] }로 초기화됨

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
    const user = userEvent.setup({ delay: null })

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

    // 폼 제출 버튼 클릭 (form 내부의 submit 버튼 — type="submit")
    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(vi.mocked(registerSecurityKey)).toHaveBeenCalledWith('회사 노트북')
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
    const { listWebauthnKeys } = await import('@/api/webauthn')
    vi.mocked(listWebauthnKeys).mockResolvedValue({ keys: [KEY_1] })
    const user = userEvent.setup({ delay: null })

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(KEY_1.name)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnDeleteButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnDeleteConfirmBody)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteConfirmButton })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: mfaStrings.webauthnDeleteCancelButton })).toBeInTheDocument()
  })

  it('T4-WA-S3-2: 삭제 취소 → deleteWebauthnKey 미호출, 확인 박스 닫힘', async () => {
    const { listWebauthnKeys, deleteWebauthnKey } = await import('@/api/webauthn')
    vi.mocked(listWebauthnKeys).mockResolvedValue({ keys: [KEY_1] })
    vi.mocked(deleteWebauthnKey).mockReset()

    const user = userEvent.setup({ delay: null })

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
    expect(vi.mocked(deleteWebauthnKey)).not.toHaveBeenCalled()
  })

  it('T4-WA-S3-3: 삭제 확인 → deleteWebauthnKey 호출 → 목록 invalidateQueries', async () => {
    const { listWebauthnKeys, deleteWebauthnKey } = await import('@/api/webauthn')
    vi.mocked(listWebauthnKeys).mockResolvedValue({ keys: [KEY_1] })

    const user = userEvent.setup({ delay: null })

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
      expect(vi.mocked(deleteWebauthnKey)).toHaveBeenCalledWith(KEY_1.id)
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
    vi.mocked(refreshSession).mockResolvedValue({
      username: 'alice',
      email: 'alice@bts.local',
      authMethod: 'local',
      userId: 'u-alice',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    })

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

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(mfaStrings.webauthnNameLabel), '보안 키')

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(vi.mocked(refreshSession)).toHaveBeenCalledTimes(1)
    })
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/dashboard' })
  })

  it('T4-WA-S4-2: mfaEnrollmentRequired=false 사용자가 등록 성공 시 refreshSession이 호출되지 않는다', async () => {
    const { refreshSession } = await import('@/api/client')
    const { registerSecurityKey } = await import('@/api/webauthn')
    vi.mocked(refreshSession).mockReset()

    // beforeEach에서 mfaEnrollmentRequired: false로 초기화됨

    const user = userEvent.setup({ delay: null })

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(mfaStrings.webauthnNameLabel), '보안 키')

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(vi.mocked(registerSecurityKey)).toHaveBeenCalledTimes(1)
    })

    expect(vi.mocked(refreshSession)).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-WA-S5: 에러 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-WA-S5: 에러 처리', () => {
  it('T4-WA-S5-1: EC-1 NotAllowedError (사용자 취소) → 인라인 에러 메시지 + 화면 유지', async () => {
    const { registerSecurityKey } = await import('@/api/webauthn')
    const notAllowedError = new Error('The operation either timed out or was not allowed.')
    notAllowedError.name = 'NotAllowedError'
    vi.mocked(registerSecurityKey).mockRejectedValue(notAllowedError)

    const user = userEvent.setup({ delay: null })

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // 입력 필드가 여전히 화면에 유지된다
    expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
  })

  it('T4-WA-S5-2: EC-2 ApiError 409 already_registered → "이미 등록된 보안 키" 메시지', async () => {
    const { registerSecurityKey } = await import('@/api/webauthn')
    const { ApiError } = await import('@/api/client')
    vi.mocked(registerSecurityKey).mockRejectedValue(new ApiError(409, { error: 'already_registered' }))

    const user = userEvent.setup({ delay: null })

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toHaveTextContent('이미 등록된 보안 키')
  })

  it('T4-WA-S5-2b: InvalidStateError(이미 등록된 인증기) → "이미 등록된 보안 키" 메시지', async () => {
    // @simplewebauthn은 excludeCredentials에 매칭되는 인증기를 재등록하면 register/finish 요청
    // 전에 navigator.credentials.create가 InvalidStateError를 던진다(name 보존). 백엔드 409가
    // 트리거되지 않으므로 이 name을 already_registered 메시지로 매핑해야 한다.
    const { registerSecurityKey } = await import('@/api/webauthn')
    const invalidStateError = new Error('authenticator already registered')
    invalidStateError.name = 'InvalidStateError'
    vi.mocked(registerSecurityKey).mockRejectedValue(invalidStateError)

    const user = userEvent.setup({ delay: null })

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.webauthnAddButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByLabelText(mfaStrings.webauthnNameLabel)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.webauthnAddButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toHaveTextContent('이미 등록된 보안 키')
  })

  it('T4-WA-S5-3: EC-3 미지원 브라우저(browserSupportsWebAuthn=false) → 추가 버튼 비활성 + 안내 문구', async () => {
    const { browserSupportsWebAuthn } = await import('@simplewebauthn/browser')
    vi.mocked(browserSupportsWebAuthn).mockReturnValue(false)

    renderWebauthnSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.webauthnUnsupportedBrowser)).toBeInTheDocument()
    })

    const addBtn = screen.getByRole('button', { name: mfaStrings.webauthnAddButton })
    expect(addBtn).toBeDisabled()
  })
})
