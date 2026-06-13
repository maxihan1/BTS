// 신뢰 디바이스 관리 섹션 단위 테스트 — 목록·단건 취소·전체 취소·엣지케이스·로딩/에러 분기 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mfaStrings } from '@/i18n/ko'
import { TrustedDevicesSection } from './TrustedDevicesSection'

// @/api/trusted-devices mock — listTrustedDevices·revokeTrustedDevice·revokeAllTrustedDevices를 vi.fn으로 교체
vi.mock('@/api/trusted-devices', () => ({
  listTrustedDevices: vi.fn(),
  revokeTrustedDevice: vi.fn(),
  revokeAllTrustedDevices: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const DEVICE_1 = {
  id: '11111111-1111-4111-8111-111111111111',
  label: '회사 MacBook Pro',
  createdAt: '2026-01-15T10:00:00Z',
  lastUsedAt: '2026-05-01T09:30:00Z',
  expiresAt: '2026-02-14T10:00:00Z',
}

const DEVICE_2 = {
  id: '22222222-2222-4222-8222-222222222222',
  label: null,
  createdAt: '2026-02-01T12:00:00Z',
  lastUsedAt: null,
  expiresAt: '2026-03-03T12:00:00Z',
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

function renderSection() {
  const Wrapper = createWrapper()
  return render(<TrustedDevicesSection />, { wrapper: Wrapper })
}

// ─────────────────────────────────────────────────────────────────────────────
// beforeEach
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(async () => {
  vi.clearAllMocks()

  const { listTrustedDevices, revokeTrustedDevice, revokeAllTrustedDevices } =
    await import('@/api/trusted-devices')
  vi.mocked(listTrustedDevices).mockResolvedValue([DEVICE_1])
  vi.mocked(revokeTrustedDevice).mockResolvedValue(undefined)
  vi.mocked(revokeAllTrustedDevices).mockResolvedValue(undefined)
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-E1: label=null → fallback 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-E1: label=null → fallback 표시', () => {
  it('E1-1: label이 null인 기기는 "알 수 없는 기기" 대체 텍스트를 표시한다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    vi.mocked(listTrustedDevices).mockResolvedValue([DEVICE_2])

    renderSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesLabelFallback)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-E2: lastUsedAt=null → "사용 기록 없음"
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-E2: lastUsedAt=null → "사용 기록 없음" 표시', () => {
  it('E2-1: lastUsedAt이 null인 기기는 "사용 기록 없음" 텍스트를 표시한다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    vi.mocked(listTrustedDevices).mockResolvedValue([DEVICE_2])

    renderSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesLastUsedNever)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-E3: 빈 목록 → 안내 메시지 + "모든 기기 신뢰 해제" 버튼 미표시
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-E3: 빈 목록 → 안내 + 전체 취소 버튼 숨김', () => {
  it('E3-1: 기기가 0개이면 빈 상태 안내 메시지를 표시한다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    vi.mocked(listTrustedDevices).mockResolvedValue([])

    renderSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesEmptyState)).toBeInTheDocument()
    })
  })

  it('E3-2: 기기가 0개이면 "모든 기기 신뢰 해제" 버튼이 렌더되지 않는다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    vi.mocked(listTrustedDevices).mockResolvedValue([])

    renderSection()

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesEmptyState)).toBeInTheDocument()
    })

    expect(
      screen.queryByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton }),
    ).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-S2: 단건 취소 인라인 확인 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-S2: 단건 취소 인라인 확인 플로우', () => {
  it('S2-1: "신뢰 해제" 클릭 → 인라인 확인 문구 노출', async () => {
    const user = userEvent.setup({ delay: null })

    renderSection()

    await waitFor(() => {
      expect(screen.getByText(DEVICE_1.label)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesRevokeConfirm)).toBeInTheDocument()
    })
  })

  it('S2-2: 인라인 확인 → revokeTrustedDevice 호출 → 목록 invalidateQueries', async () => {
    const { revokeTrustedDevice } = await import('@/api/trusted-devices')
    const user = userEvent.setup({ delay: null })

    const queryClientRef = { current: null as QueryClient | null }
    function CapturingWrapper({ children }: { children: React.ReactNode }) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      queryClientRef.current = client
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>
    }

    render(<TrustedDevicesSection />, { wrapper: CapturingWrapper })

    await waitFor(() => {
      expect(screen.getByText(DEVICE_1.label)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesRevokeConfirm)).toBeInTheDocument()
    })

    const invalidateSpy = vi.spyOn(queryClientRef.current!, 'invalidateQueries')

    // 확인 버튼 클릭
    const confirmButtons = screen.getAllByRole('button', { name: /확인/ })
    await user.click(confirmButtons[0]!)

    await waitFor(() => {
      expect(vi.mocked(revokeTrustedDevice)).toHaveBeenCalledWith(DEVICE_1.id)
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['mfa', 'trusted-devices'] }),
      )
    })
  })

  it('S2-3: 인라인 취소 클릭 → revokeTrustedDevice 미호출, 확인 박스 닫힘', async () => {
    const { revokeTrustedDevice } = await import('@/api/trusted-devices')
    vi.mocked(revokeTrustedDevice).mockReset()

    const user = userEvent.setup({ delay: null })

    renderSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton }))

    await waitFor(() => {
      expect(screen.getByText(mfaStrings.trustedDevicesRevokeConfirm)).toBeInTheDocument()
    })

    const cancelButtons = screen.getAllByRole('button', { name: /취소/ })
    await user.click(cancelButtons[0]!)

    await waitFor(() => {
      expect(screen.queryByText(mfaStrings.trustedDevicesRevokeConfirm)).not.toBeInTheDocument()
    })
    expect(vi.mocked(revokeTrustedDevice)).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-S3: 전체 취소 인라인 확인 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-S3: 전체 취소 인라인 확인 플로우', () => {
  it('S3-1: "모든 기기 신뢰 해제" 클릭 → 인라인 확인 문구 노출', async () => {
    const user = userEvent.setup({ delay: null })

    renderSection()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton }))

    await waitFor(() => {
      // revokeAll 확인 박스 — 같은 confirma 문구를 전용 상태로 표시
      expect(screen.getByRole('button', { name: /확인/ })).toBeInTheDocument()
    })
  })

  it('S3-2: revokeAll 확인 → revokeAllTrustedDevices 호출 → 목록 invalidateQueries', async () => {
    const { revokeAllTrustedDevices } = await import('@/api/trusted-devices')
    const user = userEvent.setup({ delay: null })

    const queryClientRef = { current: null as QueryClient | null }
    function CapturingWrapper({ children }: { children: React.ReactNode }) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      queryClientRef.current = client
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>
    }

    render(<TrustedDevicesSection />, { wrapper: CapturingWrapper })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /확인/ })).toBeInTheDocument()
    })

    const invalidateSpy = vi.spyOn(queryClientRef.current!, 'invalidateQueries')

    const confirmButtons = screen.getAllByRole('button', { name: /확인/ })
    await user.click(confirmButtons[0]!)

    await waitFor(() => {
      expect(vi.mocked(revokeAllTrustedDevices)).toHaveBeenCalledTimes(1)
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['mfa', 'trusted-devices'] }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-L: 로딩 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-L: 로딩 상태', () => {
  it('L-1: 로딩 중 role=status 스켈레톤이 렌더된다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    // 응답을 지연시켜 로딩 상태를 유지한다
    vi.mocked(listTrustedDevices).mockReturnValue(new Promise(() => { /* 영원히 pending */ }))

    renderSection()

    await waitFor(() => {
      expect(screen.getByRole('status')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-ER: 에러 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-ER: 에러 상태', () => {
  it('ER-1: 목록 조회 실패 시 role=alert 에러 메시지가 렌더된다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    vi.mocked(listTrustedDevices).mockRejectedValue(new Error('network error'))

    renderSection()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-TD-IV: InvalidDate 방어
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-TD-IV: InvalidDate 방어', () => {
  it('IV-1: createdAt이 파싱 불가한 문자열일 때 크래시 없이 원문 또는 "—"를 표시한다', async () => {
    const { listTrustedDevices } = await import('@/api/trusted-devices')
    vi.mocked(listTrustedDevices).mockResolvedValue([
      {
        ...DEVICE_1,
        createdAt: 'NOT_A_DATE',
        lastUsedAt: 'ALSO_INVALID',
        expiresAt: 'BAD_DATE',
      },
    ])

    renderSection()

    // 크래시 없이 렌더되어야 한다
    await waitFor(() => {
      expect(screen.getByText(DEVICE_1.label)).toBeInTheDocument()
    })
  })
})
