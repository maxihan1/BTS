// 공유 필터 모달 컴포넌트 단위 테스트 — EC4 GROUP 보존 및 PUT 필수 필드(B2) 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { SavedFilterResponse, ShareDto } from '@/api/saved-filters'
import { updateFilter } from '@/api/saved-filters'
import { ShareFilterDialog } from './ShareFilterDialog'

// @/api/saved-filters 모듈 전체 모킹 — updateFilter만 stub, savedFiltersKey는 인라인 유지
vi.mock('@/api/saved-filters', () => ({
  updateFilter: vi.fn(),
  savedFiltersKey: {
    all: () => ['saved-filters'] as const,
    owned: () => ['saved-filters', 'owned'] as const,
    detail: (id: string) => ['saved-filters', 'detail', id] as const,
  },
}))

// ──────────────────────────────────────────────────────────────────────────────
// 공통 픽스처
// ──────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'BTS'

function makeFilter(overrides: Partial<SavedFilterResponse> = {}): SavedFilterResponse {
  return {
    id: '00000000-0000-4000-a000-000000000001',
    ownerId: '00000000-0000-4000-a000-000000000002',
    name: '테스트 필터',
    aqlQuery: 'status = open',
    projectKey: PROJECT_KEY,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    version: 3,
    isOwner: true,
    shares: [],
    ...overrides,
  }
}

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function renderDialog(filter: SavedFilterResponse, onClose = vi.fn()) {
  return render(
    <ShareFilterDialog open={true} filter={filter} onClose={onClose} />,
    { wrapper: createWrapper() },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(updateFilter).mockResolvedValue(makeFilter())
})

// ──────────────────────────────────────────────────────────────────────────────
// (a) 초기 토글 상태 바인딩
// ──────────────────────────────────────────────────────────────────────────────

describe('ShareFilterDialog — 초기 토글 상태(a)', () => {
  it('filter.shares에 AUTHENTICATED가 있으면 "모든 로그인 사용자에게" 체크박스가 초기에 체크된다', () => {
    const filter = makeFilter({
      shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
    })
    renderDialog(filter)

    const authCheckbox = screen.getByRole('checkbox', { name: '모든 로그인 사용자에게' })
    expect(authCheckbox).toBeChecked()
  })

  it('filter.shares에 PROJECT(자기 projectKey)가 있으면 프로젝트 체크박스가 초기에 체크된다', () => {
    const filter = makeFilter({
      shares: [{ shareType: 'PROJECT', targetId: PROJECT_KEY }],
    })
    renderDialog(filter)

    const projectCheckbox = screen.getByRole('checkbox', {
      name: `이 프로젝트(${PROJECT_KEY}) 멤버에게`,
    })
    expect(projectCheckbox).toBeChecked()
  })

  it('filter.shares가 비어 있으면 두 체크박스 모두 체크 해제 상태다', () => {
    renderDialog(makeFilter({ shares: [] }))

    expect(screen.getByRole('checkbox', { name: '모든 로그인 사용자에게' })).not.toBeChecked()
    expect(
      screen.getByRole('checkbox', { name: `이 프로젝트(${PROJECT_KEY}) 멤버에게` }),
    ).not.toBeChecked()
  })

  it('GROUP 항목만 있는 경우 두 체크박스 모두 체크 해제 상태다', () => {
    const filter = makeFilter({
      shares: [{ shareType: 'GROUP', targetId: 'engineers' }],
    })
    renderDialog(filter)

    expect(screen.getByRole('checkbox', { name: '모든 로그인 사용자에게' })).not.toBeChecked()
    expect(
      screen.getByRole('checkbox', { name: `이 프로젝트(${PROJECT_KEY}) 멤버에게` }),
    ).not.toBeChecked()
  })
})

// ──────────────────────────────────────────────────────────────────────────────
// (b) 저장 payload — B2: name/aqlQuery/version 필수 동반
// ──────────────────────────────────────────────────────────────────────────────

describe('ShareFilterDialog — 저장 payload(B2)', () => {
  it('shares만 변경해도 updateFilter payload에 name/aqlQuery/version이 함께 포함된다', async () => {
    const user = userEvent.setup()
    const filter = makeFilter({
      name: '회귀 테스트 필터',
      aqlQuery: 'priority = 1',
      version: 7,
      shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
    })
    renderDialog(filter)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(vi.mocked(updateFilter)).toHaveBeenCalledWith(
        filter.id,
        expect.objectContaining({
          name: '회귀 테스트 필터',
          aqlQuery: 'priority = 1',
          version: 7,
        }),
      )
    })
  })

  it('저장 시 updateFilter의 첫 번째 인자는 filter.id다', async () => {
    const user = userEvent.setup()
    const filter = makeFilter()
    renderDialog(filter)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(vi.mocked(updateFilter)).toHaveBeenCalledWith(
        '00000000-0000-4000-a000-000000000001',
        expect.objectContaining({}),
      )
    })
  })
})

// ──────────────────────────────────────────────────────────────────────────────
// (c) EC4 — GROUP/타 PROJECT 항목 보존 (replace-all 데이터 손실 방지)
// ──────────────────────────────────────────────────────────────────────────────

describe('ShareFilterDialog — GROUP 보존(EC4)', () => {
  it('EC4: filter.shares의 GROUP 항목은 저장 payload shares에 그대로 포함된다', async () => {
    const user = userEvent.setup()
    const groupShare: ShareDto = { shareType: 'GROUP', targetId: 'engineers' }
    const filter = makeFilter({
      shares: [groupShare, { shareType: 'AUTHENTICATED', targetId: null }],
    })
    renderDialog(filter)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      const calls = vi.mocked(updateFilter).mock.calls
      expect(calls.length).toBeGreaterThan(0)
      const payload = calls[0]?.[1]
      expect(payload?.shares).toContainEqual(groupShare)
    })
  })

  it('EC4: 자기 projectKey가 아닌 PROJECT 항목도 저장 payload에 보존된다', async () => {
    const user = userEvent.setup()
    const otherProjectShare: ShareDto = { shareType: 'PROJECT', targetId: 'OTHER' }
    const filter = makeFilter({
      projectKey: PROJECT_KEY,
      shares: [otherProjectShare],
    })
    renderDialog(filter)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      const calls = vi.mocked(updateFilter).mock.calls
      const payload = calls[0]?.[1]
      expect(payload?.shares).toContainEqual(otherProjectShare)
    })
  })

  it('EC4: GROUP + AUTH 토글 ON 저장 시 GROUP이 유실되지 않고 AUTHENTICATED도 포함된다', async () => {
    const user = userEvent.setup()
    const groupShare: ShareDto = { shareType: 'GROUP', targetId: 'team-a' }
    const filter = makeFilter({ shares: [groupShare] })
    renderDialog(filter)

    // AUTH 체크박스를 켠 뒤 저장
    await user.click(screen.getByRole('checkbox', { name: '모든 로그인 사용자에게' }))
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      const payload = vi.mocked(updateFilter).mock.calls[0]?.[1]
      expect(payload?.shares).toContainEqual(groupShare)
      expect(payload?.shares).toContainEqual({ shareType: 'AUTHENTICATED', targetId: null })
    })
  })
})

// ──────────────────────────────────────────────────────────────────────────────
// (d) 두 토글 모두 OFF → shares 빈 배열
// ──────────────────────────────────────────────────────────────────────────────

describe('ShareFilterDialog — 공유 전체 제거(d)', () => {
  it('두 토글 모두 해제하면 저장 payload의 shares가 빈 배열이다', async () => {
    const user = userEvent.setup()
    const filter = makeFilter({
      shares: [
        { shareType: 'AUTHENTICATED', targetId: null },
        { shareType: 'PROJECT', targetId: PROJECT_KEY },
      ],
    })
    renderDialog(filter)

    // 둘 다 체크된 상태에서 해제
    await user.click(screen.getByRole('checkbox', { name: '모든 로그인 사용자에게' }))
    await user.click(
      screen.getByRole('checkbox', { name: `이 프로젝트(${PROJECT_KEY}) 멤버에게` }),
    )
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      const payload = vi.mocked(updateFilter).mock.calls[0]?.[1]
      expect(payload?.shares).toEqual([])
    })
  })

  it('shares가 원래 비어 있고 아무것도 켜지 않으면 저장 후 shares는 빈 배열이다', async () => {
    const user = userEvent.setup()
    renderDialog(makeFilter({ shares: [] }))

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      const payload = vi.mocked(updateFilter).mock.calls[0]?.[1]
      expect(payload?.shares).toEqual([])
    })
  })
})
