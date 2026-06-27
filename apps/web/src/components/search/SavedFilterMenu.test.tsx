// 저장된 필터 드롭다운 메뉴 단위 테스트 (FR-SR-03 Task-5)
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { mockAccessToken } from '@/mocks/auth-fixtures'
import { savedFilterLabels } from '@/i18n/saved-filter-labels'
import { SavedFilterMenu } from './SavedFilterMenu'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    children,
    className,
  }: {
    to: string
    children: React.ReactNode
    className?: string
  }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// FavoriteButton 모킹 — 즐겨찾기 API 설정 없이 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/favorite/FavoriteButton', () => ({
  FavoriteButton: ({
    targetType,
    targetId,
  }: {
    targetType: string
    targetId: string
  }) => (
    <button
      data-testid="favorite-button"
      data-target-type={targetType}
      data-target-id={targetId}
      aria-label="즐겨찾기"
    />
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 다이얼로그 모킹 — SavedFilterMenu 격리 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('./SaveFilterDialog', () => ({
  SaveFilterDialog: ({
    open,
    mode,
    filter,
  }: {
    open: boolean
    mode: string
    filter?: { id: string }
  }) =>
    open ? (
      <div
        data-testid="save-filter-dialog"
        data-mode={mode}
        data-filter-id={filter?.id}
      />
    ) : null,
}))

vi.mock('./ShareFilterDialog', () => ({
  ShareFilterDialog: ({
    open,
    filter,
  }: {
    open: boolean
    filter: { id: string }
  }) =>
    open ? (
      <div
        data-testid="share-filter-dialog"
        data-filter-id={filter.id}
      />
    ) : null,
}))

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient 래퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderMenu() {
  return render(<SavedFilterMenu />, { wrapper: makeWrapper() })
}

// ─────────────────────────────────────────────────────────────────────────────
// 공용 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

const ownedFilter = {
  id: '00000000-0000-4000-8000-000000000010',
  ownerId: ALICE_USER_ID,
  name: '내 이슈 필터',
  aqlQuery: 'status = OPEN',
  projectKey: 'ATLAS',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: null,
  version: 0,
  isOwner: true,
  shares: [],
}

const sharedFilter = {
  id: '00000000-0000-4000-8000-000000000020',
  ownerId: '00000000-0000-4000-8000-000000000002',
  name: '팀 공유 필터',
  aqlQuery: 'project = ATLAS',
  projectKey: 'ATLAS',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: null,
  version: 0,
  isOwner: false,
  shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
}

// ─────────────────────────────────────────────────────────────────────────────
// alice 인증 상태 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: mockAccessToken('alice'),
    user: {
      username: 'alice',
      email: 'alice@bts.local',
      authMethod: 'local',
      userId: ALICE_USER_ID,
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    },
  })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 공용 MSW 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function setupBothEmpty() {
  server.use(
    http.get('/api/v1/filters', () => HttpResponse.json([])),
    http.get('/api/v1/filters/shared', () => HttpResponse.json([])),
  )
}

function setupWithFilters() {
  server.use(
    http.get('/api/v1/filters', () => HttpResponse.json([ownedFilter])),
    http.get('/api/v1/filters/shared', () => HttpResponse.json([sharedFilter])),
  )
}

// 트리거 버튼을 클릭해 드롭다운을 연다
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  const trigger = screen.getByRole('button', {
    name: savedFilterLabels.menuTriggerAriaLabel,
  })
  await user.click(trigger)
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 두 섹션 렌더 — 내 필터 + 공유받은 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('SavedFilterMenu — S1 두 섹션 렌더', () => {
  it('S1a: 드롭다운 열면 "내 필터" 섹션 헤더가 표시된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(
      await screen.findByText(savedFilterLabels.myFiltersTab),
    ).toBeInTheDocument()
  })

  it('S1b: 드롭다운 열면 "공유받은 필터" 섹션 헤더가 표시된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(
      await screen.findByText(savedFilterLabels.sharedFiltersSection),
    ).toBeInTheDocument()
  })

  it('S1c: 소유 필터 이름이 "내 필터" 섹션에 표시된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(await screen.findByText(ownedFilter.name)).toBeInTheDocument()
  })

  it('S1d: 공유받은 필터 이름이 "공유받은 필터" 섹션에 표시된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(await screen.findByText(sharedFilter.name)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 항목 클릭 → /search?filterId=<id> 네비게이션
// ─────────────────────────────────────────────────────────────────────────────

describe('SavedFilterMenu — S2 항목 링크', () => {
  it('S2a: 소유 필터 항목 링크가 /search?filterId=<id> href를 갖는다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const link = await screen.findByRole('link', { name: ownedFilter.name })
    expect(link).toHaveAttribute('href', `/search?filterId=${ownedFilter.id}`)
  })

  it('S2b: 공유받은 필터 항목 링크도 /search?filterId=<id> href를 갖는다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const link = await screen.findByRole('link', { name: sharedFilter.name })
    expect(link).toHaveAttribute('href', `/search?filterId=${sharedFilter.id}`)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 액션 게이팅 — 소유(isOwner=true)만 편집/공유/삭제 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('SavedFilterMenu — S3 액션 게이팅', () => {
  it('S3a: 소유 필터에만 편집 버튼이 있다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    // 소유 필터가 로드된 후 확인
    await screen.findByText(ownedFilter.name)
    const editBtns = screen.getAllByRole('button', {
      name: savedFilterLabels.editButton,
    })
    expect(editBtns).toHaveLength(1)
  })

  it('S3b: 소유 필터에만 삭제 버튼이 있다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await screen.findByText(ownedFilter.name)
    const deleteBtns = screen.getAllByRole('button', {
      name: savedFilterLabels.deleteButton,
    })
    expect(deleteBtns).toHaveLength(1)
  })

  it('S3c: 공유받은(isOwner=false) 항목에는 편집/삭제 버튼이 없다', async () => {
    // 공유 필터만 있는 경우 — 편집/삭제 버튼 0개
    server.use(
      http.get('/api/v1/filters', () => HttpResponse.json([])),
      http.get('/api/v1/filters/shared', () =>
        HttpResponse.json([sharedFilter]),
      ),
    )
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await screen.findByText(sharedFilter.name)
    expect(
      screen.queryByRole('button', { name: savedFilterLabels.editButton }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: savedFilterLabels.deleteButton }),
    ).not.toBeInTheDocument()
  })

  it('S3d: 편집 버튼 클릭 시 SaveFilterDialog가 열린다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const editBtn = await screen.findByRole('button', {
      name: savedFilterLabels.editButton,
    })
    await user.click(editBtn)

    expect(screen.getByTestId('save-filter-dialog')).toBeInTheDocument()
    expect(screen.getByTestId('save-filter-dialog')).toHaveAttribute(
      'data-mode',
      'edit',
    )
  })

  it('S3e: 공유 버튼 클릭 시 ShareFilterDialog가 열린다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const shareBtn = await screen.findByRole('button', {
      name: savedFilterLabels.shareButton,
    })
    await user.click(shareBtn)

    expect(screen.getByTestId('share-filter-dialog')).toBeInTheDocument()
    expect(screen.getByTestId('share-filter-dialog')).toHaveAttribute(
      'data-filter-id',
      ownedFilter.id,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. FavoriteButton 재사용 — targetType='FILTER'
// ─────────────────────────────────────────────────────────────────────────────

describe('SavedFilterMenu — S4 FavoriteButton 렌더', () => {
  it('S4a: 소유 필터 행에 FavoriteButton(targetType=FILTER)이 렌더된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await screen.findByText(ownedFilter.name)
    const favBtns = screen.getAllByTestId('favorite-button')
    const ownedFavBtn = favBtns.find(
      (btn) => btn.getAttribute('data-target-id') === ownedFilter.id,
    )
    expect(ownedFavBtn).toBeDefined()
    expect(ownedFavBtn).toHaveAttribute('data-target-type', 'FILTER')
  })

  it('S4b: 공유받은 필터 행에도 FavoriteButton이 렌더된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await screen.findByText(sharedFilter.name)
    const favBtns = screen.getAllByTestId('favorite-button')
    const sharedFavBtn = favBtns.find(
      (btn) => btn.getAttribute('data-target-id') === sharedFilter.id,
    )
    expect(sharedFavBtn).toBeDefined()
    expect(sharedFavBtn).toHaveAttribute('data-target-type', 'FILTER')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 빈 상태 — "저장된 필터 없음"
// ─────────────────────────────────────────────────────────────────────────────

describe('SavedFilterMenu — S5 빈 상태', () => {
  it('S5a: 소유·공유 모두 비어 있으면 빈 상태 메시지가 표시된다', async () => {
    setupBothEmpty()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(
      await screen.findByText(savedFilterLabels.menuEmptyMessage),
    ).toBeInTheDocument()
  })

  it('S5b: 필터가 있으면 빈 상태 메시지가 표시되지 않는다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await screen.findByText(ownedFilter.name)
    expect(
      screen.queryByText(savedFilterLabels.menuEmptyMessage),
    ).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 삭제 확인 → deleteFilter 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SavedFilterMenu — S6 삭제 확인', () => {
  it('S6a: 삭제 버튼 클릭 시 삭제 확인 UI가 표시된다', async () => {
    setupWithFilters()
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const deleteBtn = await screen.findByRole('button', {
      name: savedFilterLabels.deleteButton,
    })
    await user.click(deleteBtn)

    expect(
      await screen.findByText(savedFilterLabels.deleteConfirm),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: savedFilterLabels.deleteConfirmButton }),
    ).toBeInTheDocument()
  })

  it('S6b: 삭제 확인 후 deleteFilter API가 호출된다', async () => {
    const deleteHandler = vi.fn()
    server.use(
      http.get('/api/v1/filters', () => HttpResponse.json([ownedFilter])),
      http.get('/api/v1/filters/shared', () => HttpResponse.json([])),
      http.delete('/api/v1/filters/:id', ({ params }) => {
        deleteHandler(params['id'])
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const deleteBtn = await screen.findByRole('button', {
      name: savedFilterLabels.deleteButton,
    })
    await user.click(deleteBtn)

    const confirmBtn = await screen.findByRole('button', {
      name: savedFilterLabels.deleteConfirmButton,
    })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(deleteHandler).toHaveBeenCalledWith(ownedFilter.id)
    })
  })

  it('S6c: 삭제 취소 시 deleteFilter API가 호출되지 않는다', async () => {
    const deleteHandler = vi.fn()
    server.use(
      http.get('/api/v1/filters', () => HttpResponse.json([ownedFilter])),
      http.get('/api/v1/filters/shared', () => HttpResponse.json([])),
      http.delete('/api/v1/filters/:id', () => {
        deleteHandler()
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    const deleteBtn = await screen.findByRole('button', {
      name: savedFilterLabels.deleteButton,
    })
    await user.click(deleteBtn)

    const cancelBtn = await screen.findByRole('button', {
      name: savedFilterLabels.cancelButton,
      // deleteConfirm 섹션의 취소 버튼을 찾기 위해 정확한 매칭 사용
    })
    await user.click(cancelBtn)

    expect(deleteHandler).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(
        screen.queryByText(savedFilterLabels.deleteConfirm),
      ).not.toBeInTheDocument()
    })
  })
})
