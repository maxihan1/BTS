// Import 매핑 마법사 — 사용자 매핑 단계 컴포넌트 단위 테스트 (FR-IM-02 D6 Task-4)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import type { UserSummary } from '@/api/users'
import type { UserCollectionResponse } from '@/api/import-mappings'
import { UserMappingStep } from './UserMappingStep'
import type { UserMappingStepProps } from './UserMappingStep'

// ─────────────────────────────────────────────────────────────────────────────
// fetchUsers mock — @/api/users (다른 유저 검색 지정 흐름)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/users', () => ({
  fetchUsers: vi.fn(),
}))

import { fetchUsers } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_ID = '11111111-1111-4111-a111-111111111111'
const CAROL_ID = '33333333-3333-4333-c333-333333333333'

const ALICE: UserSummary = {
  id: ALICE_ID,
  username: 'alice',
  displayName: '앨리스',
  email: 'alice@example.com',
}

const CAROL: UserSummary = {
  id: CAROL_ID,
  username: 'carol',
  displayName: '캐롤',
  email: 'carol@example.com',
}

const USERS_WITH_SUGGESTION: UserCollectionResponse['users'] = [
  { sourceIdentifier: 'alice@example.com', suggestedUserId: ALICE_ID, suggestedDisplayName: '앨리스' },
]

const USERS_WITHOUT_SUGGESTION: UserCollectionResponse['users'] = [
  { sourceIdentifier: 'unknown@example.com' },
]

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderStep(props: UserMappingStepProps): ReturnType<typeof render> {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>{renderElement(props)}</QueryClientProvider>,
  )
}

function renderElement(props: UserMappingStepProps): ReactElement {
  return <UserMappingStep {...props} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('UserMappingStep', () => {
  beforeEach(() => {
    vi.mocked(fetchUsers).mockResolvedValue([])
  })

  it('소스 식별자별 행과 기본 추천 사용자를 표시한다', () => {
    renderStep({
      users: USERS_WITH_SUGGESTION,
      value: {},
      onChange: vi.fn(),
      onNext: vi.fn(),
    })

    expect(screen.getByText('alice@example.com')).toBeInTheDocument()
    expect(screen.getByText(/앨리스/)).toBeInTheDocument()
  })

  it('추천이 없으면 기본 미매핑 상태를 표시한다', () => {
    renderStep({
      users: USERS_WITHOUT_SUGGESTION,
      value: {},
      onChange: vi.fn(),
      onNext: vi.fn(),
    })

    const unmappedButton = screen.getByRole('button', { name: /미매핑/ })
    expect(unmappedButton).toHaveAttribute('aria-pressed', 'true')
  })

  it('"미매핑" 선택 시 onChange가 해당 식별자 value null로 호출된다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    renderStep({
      users: USERS_WITH_SUGGESTION,
      value: {},
      onChange,
      onNext: vi.fn(),
    })

    await user.click(screen.getByRole('button', { name: /미매핑/ }))

    expect(onChange).toHaveBeenCalledWith({ 'alice@example.com': null })
  })

  it('검색 입력 시 fetchUsers를 호출하고 결과 선택 시 onChange가 호출된다', async () => {
    vi.mocked(fetchUsers).mockResolvedValue([CAROL])
    const onChange = vi.fn()
    const user = userEvent.setup({ delay: null })
    renderStep({
      users: USERS_WITH_SUGGESTION,
      value: {},
      onChange,
      onNext: vi.fn(),
    })

    const searchInput = screen.getByRole('textbox', { name: /alice@example.com.*검색/ })
    await user.type(searchInput, '캐롤')

    await waitFor(() => {
      expect(vi.mocked(fetchUsers)).toHaveBeenCalledWith('캐롤')
    })

    await waitFor(() => {
      expect(screen.getByRole('option', { name: '캐롤' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('option', { name: '캐롤' }))

    expect(onChange).toHaveBeenCalledWith({ 'alice@example.com': CAROL_ID })
  })

  it('검색 중에는 로딩 표시를 렌더한다 (DR-1)', async () => {
    let resolveFetch: (users: UserSummary[]) => void = () => {}
    vi.mocked(fetchUsers).mockImplementation(
      () =>
        new Promise<UserSummary[]>((resolve) => {
          resolveFetch = resolve
        }),
    )
    const user = userEvent.setup({ delay: null })
    renderStep({
      users: USERS_WITH_SUGGESTION,
      value: {},
      onChange: vi.fn(),
      onNext: vi.fn(),
    })

    const searchInput = screen.getByRole('textbox', { name: /alice@example.com.*검색/ })
    await user.type(searchInput, '캐롤')

    await waitFor(() => {
      expect(screen.getByText('검색 중...')).toBeInTheDocument()
    })

    resolveFetch([CAROL])
  })

  it('users가 빈 배열이면 안내 메시지를 표시한다', () => {
    renderStep({
      users: [],
      value: {},
      onChange: vi.fn(),
      onNext: vi.fn(),
    })

    expect(screen.getByText('매핑할 작성자가 없습니다.')).toBeInTheDocument()
  })

  it('[다음] 클릭 시 onNext가 호출된다', async () => {
    const onNext = vi.fn()
    const user = userEvent.setup()
    renderStep({
      users: USERS_WITH_SUGGESTION,
      value: {},
      onChange: vi.fn(),
      onNext,
    })

    await user.click(screen.getByRole('button', { name: '다음' }))
    expect(onNext).toHaveBeenCalled()
  })

  it('onBack이 전달되면 [이전] 버튼이 렌더되고 클릭 시 호출된다', async () => {
    const onBack = vi.fn()
    const user = userEvent.setup()
    renderStep({
      users: USERS_WITH_SUGGESTION,
      value: {},
      onChange: vi.fn(),
      onNext: vi.fn(),
      onBack,
    })

    await user.click(screen.getByRole('button', { name: '이전' }))
    expect(onBack).toHaveBeenCalled()
  })
})
