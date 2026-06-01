// MemberList 컴포넌트 단위 테스트 — 4분기(로딩/에러/빈/목록) + ADMIN 판정 + orphan 폴백
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { toast } from 'sonner'
import { useAuthStore } from '@/auth/authStore'
import { projectMemberLabels } from '@/i18n/project-member-labels'
import { MemberList } from './MemberList'

// ─────────────────────────────────────────────────────────────────────────────
// sonner mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_UUID = 'fixture-alice-uuid'
const BOB_UUID = 'fixture-bob-uuid'
const PROJECT_KEY = 'ATLAS'

const ALICE_ADMIN = {
  projectId: 'project-atlas-uuid',
  userId: ALICE_UUID,
  role: 'PROJECT_ADMIN',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  displayName: '앨리스',
  username: 'alice',
}

const BOB_MEMBER = {
  projectId: 'project-atlas-uuid',
  userId: BOB_UUID,
  role: 'MEMBER',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  displayName: '밥',
  username: 'bob',
}

/** displayName/username 모두 null — EC-1/C-3 orphan 케이스 */
const ORPHAN_MEMBER = {
  projectId: 'project-atlas-uuid',
  userId: 'orphan-user-uuid',
  role: 'MEMBER',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  displayName: null,
  username: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

/** authStore에 whoami userId를 주입한다 */
function setCurrentUser(userId: string | null) {
  useAuthStore.setState({
    accessToken: 'test-token',
    user: userId !== null
      ? { userId, username: 'testuser', email: 'test@example.com', authMethod: 'local' }
      : null,
  })
}

beforeEach(() => {
  setCurrentUser(null)
  vi.clearAllMocks()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('MemberList — 4분기 렌더', () => {
  it('로딩 중에는 로딩 상태를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', async () => {
        await new Promise(() => { /* pending forever */ })
        return HttpResponse.json({ members: [] })
      }),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    expect(
      screen.getByRole('status', { name: projectMemberLabels.list.loadingStatus }),
    ).toBeInTheDocument()
  })

  it('에러 발생 시 에러 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(projectMemberLabels.list.errorMessage)).toBeInTheDocument()
    })
  })

  it('멤버가 없으면 빈 상태 안내를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(projectMemberLabels.list.emptyMessage)).toBeInTheDocument()
    })
  })

  it('멤버 목록을 표시한다 (displayName 우선)', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('앨리스')).toBeInTheDocument()
    })
    expect(screen.getByText('밥')).toBeInTheDocument()
  })
})

describe('MemberList — 이름 표시 우선순위', () => {
  it('displayName이 있으면 displayName을 사용한다', async () => {
    const member = { ...BOB_MEMBER, displayName: '표시이름', username: 'bob_username' }
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [member] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('표시이름')).toBeInTheDocument()
    })
  })

  it('displayName null이면 username을 폴백으로 사용한다', async () => {
    const member = { ...BOB_MEMBER, displayName: null, username: 'bob_username' }
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [member] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('bob_username')).toBeInTheDocument()
    })
  })

  it('displayName/username 모두 null인 orphan 멤버는 "(알 수 없는 사용자)"를 표시한다 (EC-1/C-3)', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ORPHAN_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(projectMemberLabels.row.unknownUser)).toBeInTheDocument()
    })
  })
})

describe('MemberList — 역할 배지', () => {
  it('PROJECT_ADMIN 멤버에게 관리자 배지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(projectMemberLabels.row.adminBadge)).toBeInTheDocument()
    })
  })

  it('MEMBER 역할에게 멤버 배지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(projectMemberLabels.row.memberBadge)).toBeInTheDocument()
    })
  })
})

describe('MemberList — ADMIN 판정 (C-1 리뷰)', () => {
  it('현재 사용자가 PROJECT_ADMIN이면 "멤버 추가" 버튼을 표시한다', async () => {
    setCurrentUser(ALICE_UUID)
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: projectMemberLabels.list.addMemberButton }),
      ).toBeInTheDocument()
    })
  })

  it('현재 사용자가 MEMBER이면 "멤버 추가" 버튼을 표시하지 않는다', async () => {
    setCurrentUser(BOB_UUID)
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('앨리스')).toBeInTheDocument()
    })

    expect(
      screen.queryByRole('button', { name: projectMemberLabels.list.addMemberButton }),
    ).not.toBeInTheDocument()
  })

  it('whoami.userId가 null이면 "멤버 추가" 버튼을 표시하지 않는다 (C-1 null 분기)', async () => {
    setCurrentUser(null)
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('앨리스')).toBeInTheDocument()
    })

    expect(
      screen.queryByRole('button', { name: projectMemberLabels.list.addMemberButton }),
    ).not.toBeInTheDocument()
  })

  it('whoami.userId가 멤버 목록에 없으면 "멤버 추가" 버튼을 표시하지 않는다 (C-1 목록 부재)', async () => {
    // 목록에 없는 userId 주입
    setCurrentUser('non-existent-user-uuid')
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('앨리스')).toBeInTheDocument()
    })

    expect(
      screen.queryByRole('button', { name: projectMemberLabels.list.addMemberButton }),
    ).not.toBeInTheDocument()
  })
})

describe('MemberList — ADMIN 판정 (행 액션 컨트롤)', () => {
  it('현재 사용자가 PROJECT_ADMIN이면 각 행에 역할 변경/제거 컨트롤을 표시한다', async () => {
    setCurrentUser(ALICE_UUID)
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('앨리스')).toBeInTheDocument()
    })

    // 제거 버튼이 1개 이상 있어야 한다 (MEMBER 행에 있음)
    expect(
      screen.getAllByRole('button', { name: /멤버 제거/ }),
    ).toHaveLength(1)
  })

  it('현재 사용자가 MEMBER이면 역할 변경/제거 컨트롤을 표시하지 않는다', async () => {
    setCurrentUser(BOB_UUID)
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('앨리스')).toBeInTheDocument()
    })

    expect(screen.queryByRole('button', { name: /멤버 제거/ })).not.toBeInTheDocument()
  })
})

describe('MemberList — 제거 액션', () => {
  it('제거 버튼 클릭 시 removeMember mutation을 호출하고 toast.success를 표시한다', async () => {
    setCurrentUser(ALICE_UUID)
    server.use(
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [ALICE_ADMIN, BOB_MEMBER] }),
      ),
      http.delete('/api/v1/projects/:projectKey/members/:userId', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<MemberList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('밥')).toBeInTheDocument()
    })

    const user = userEvent.setup()
    // 행 컨테이너 안에서 제거 버튼 탐색 (strict-mode 교훈)
    const bobRow = screen.getByText('밥').closest('li') ?? screen.getByText('밥').closest('[data-testid="member-row"]')
    if (bobRow === null) {
      throw new Error('밥 행을 찾을 수 없습니다')
    }
    const removeBtn = within(bobRow).getByRole('button', { name: /멤버 제거/ })
    await user.click(removeBtn)

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalled()
    })
  })
})
